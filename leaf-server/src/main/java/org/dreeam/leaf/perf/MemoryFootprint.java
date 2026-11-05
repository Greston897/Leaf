package org.dreeam.leaf.perf;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Leaf - a cheap, dependency free memory report.
 * <p>
 * The reason this exists: "the server uses 2 GB more than it should" is almost never the heap. The
 * heap is what {@code -Xmx} caps, and a server that stays inside it looks innocent while the process
 * grows: metaspace (every class of every plugin and of every library the library loader downloads),
 * the direct buffer pools (Netty arenas, chunk compression, region file reads) and the operating
 * system's view of the process (shared and mapped pages) all live outside it.
 * <p>
 * This class prints one line per pool at boot and at shutdown, plus the size of the jar directories the
 * library loader fills, so the numbers come from the machine instead of from a guess. It only uses
 * {@code java.management} and a single {@code /proc/self/status} read on Linux, never throws, and can
 * be silenced with {@code -Dleaf.memoryReport=false}.
 */
public final class MemoryFootprint {

    private static final String PREFIX = "[Leaf] ";
    private static final long MB = 1024L * 1024L;

    private MemoryFootprint() {
    }

    /**
     * Prints the boot time report and registers the shutdown report. Called from the bootstrap, so the
     * "before plugins" numbers are visible as well.
     */
    public static void install() {
        if (!enabled()) {
            return;
        }
        try {
            print("boot");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> print("shutdown"), "Leaf memory report"));
        } catch (Throwable throwable) {
            System.err.println(PREFIX + "Memory report skipped: " + throwable);
        }
    }

    /**
     * Prints the current numbers. Safe to call at any time from any thread.
     */
    public static void print(final String when) {
        if (!enabled()) {
            return;
        }
        try {
            for (final String line : describe(when)) {
                System.out.println(line);
            }
        } catch (Throwable throwable) {
            System.err.println(PREFIX + "Memory report failed: " + throwable);
        }
    }

    /**
     * @return the report as lines, so it is testable without capturing stdout
     */
    public static List<String> describe(final String when) {
        final StringBuilder out = new StringBuilder(512);
        final String tag = PREFIX + '[' + when + "] ";

        final Runtime runtime = Runtime.getRuntime();
        final long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        out.append(tag).append("heap used ").append(mb(heapUsed))
            .append(" / committed ").append(mb(runtime.totalMemory()))
            .append(" / max ").append(mb(runtime.maxMemory()));

        final long metaspaceUsed = poolUsed("Metaspace", MemoryType.NON_HEAP);
        final long metaspaceCommitted = poolCommitted("Metaspace", MemoryType.NON_HEAP);
        out.append(" | metaspace ").append(mb(metaspaceUsed)).append(" / ").append(mb(metaspaceCommitted));

        out.append(" | code cache ").append(mb(codeCacheUsed()));

        final long directUsed = bufferPool("direct", false);
        final long directCapacity = bufferPool("direct", true);
        final long mappedUsed = bufferPool("mapped", false);
        out.append(" | direct ").append(mb(directUsed)).append(" / ").append(mb(directCapacity))
            .append(" | mapped ").append(mb(mappedUsed));

        final ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        out.append(" | classes ").append(classes.getLoadedClassCount());

        final List<String> lines = new ArrayList<>(3);
        lines.add(out.toString());

        final long rss = readProc("/proc/self/status", "VmRSS:");
        final long rssPeak = readProc("/proc/self/status", "VmHWM:");
        if (rss > 0L) {
            lines.add(tag + "process rss " + mb(rss) + " / peak " + mb(rssPeak));
        }

        final String jars = describeJars("libraries") + describeJars("plugins");
        if (!jars.isEmpty()) {
            lines.add(tag + jars);
        }
        return lines;
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("leaf.memoryReport"));
    }

    /**
     * @return "libraries: 42 jars, 180 MiB" or an empty string when the directory does not exist
     */
    private static String describeJars(final String directory) {
        final Path path = Path.of(directory);
        if (!Files.isDirectory(path)) {
            return "";
        }
        long count = 0L;
        long bytes = 0L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path, "*.jar")) {
            for (final Path jar : stream) {
                count++;
                try {
                    bytes += Files.size(jar);
                } catch (IOException ignored) {
                    // a jar that vanished mid scan is not interesting
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
        return directory + ": " + count + " jars, " + mb(bytes) + ' ';
    }

    /**
     * @return the bytes used by every {@code CodeHeap} segment (there is no single "CodeCache" pool)
     */
    private static long codeCacheUsed() {
        long used = 0L;
        for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.NON_HEAP && pool.getName().startsWith("CodeHeap")) {
                final MemoryUsage usage = pool.getUsage();
                if (usage != null) {
                    used += usage.getUsed();
                }
            }
        }
        return used == 0L ? -1L : used;
    }

    private static long poolUsed(final String name, final MemoryType type) {
        for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (type == pool.getType() && pool.getName().contains(name)) {
                final MemoryUsage usage = pool.getUsage();
                return usage == null ? -1L : usage.getUsed();
            }
        }
        return -1L;
    }

    private static long poolCommitted(final String name, final MemoryType type) {
        for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (type == pool.getType() && pool.getName().contains(name)) {
                final MemoryUsage usage = pool.getUsage();
                return usage == null ? -1L : usage.getCommitted();
            }
        }
        return -1L;
    }

    /**
     * @param capacity {@code true} for the reserved capacity, {@code false} for the bytes in use
     */
    private static long bufferPool(final String name, final boolean capacity) {
        for (final BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if (pool.getName().startsWith(name)) {
                return capacity ? pool.getTotalCapacity() : pool.getMemoryUsed();
            }
        }
        return -1L;
    }

    /**
     * @return the value of {@code key} in {@code /proc/self/status} in bytes, or -1 when unavailable
     */
    private static long readProc(final String file, final String key) {
        try {
            for (final String line : Files.readAllLines(Path.of(file))) {
                if (line.startsWith(key)) {
                    final String value = line.substring(key.length()).trim();
                    // "VmRSS:     1234567 kB"
                    final String[] parts = value.split("\\s+");
                    return Long.parseLong(parts[0]) * 1024L;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux, or the file is unreadable - the rest of the report is still useful
        }
        return -1L;
    }

    private static String mb(final long bytes) {
        if (bytes < 0L) {
            return "n/a";
        }
        return (bytes / MB) + " MiB";
    }
}
