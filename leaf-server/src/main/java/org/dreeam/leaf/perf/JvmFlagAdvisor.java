package org.dreeam.leaf.perf;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Leaf - JVM flag advisor.
 * <p>
 * A large part of the "my server sits at 0.5 - 1.5 ms MSPT and has GC spikes" reports is not caused by
 * the tick loop at all: it is caused by a default JVM configuration (no pre-touched heap, shared
 * hsperfdata writes, an unbounded metaspace that the library loader happily fills, direct memory that
 * is not accounted, ...).
 * <p>
 * This class inspects the command line the server was started with and prints a short, copy-pasteable
 * list of the flags that are missing. It never changes the running JVM - flags cannot be applied at
 * runtime - it only makes the gap visible, once, at boot. Silence it with
 * {@code -Dleaf.silentJvmAdvice=true}.
 */
public final class JvmFlagAdvisor {

    private static final String PREFIX = "[Leaf] ";
    private static final int MAX_PRINTED_HINTS = 8;

    private JvmFlagAdvisor() {
    }

    public static void printAdvice() {
        try {
            if (Boolean.getBoolean("leaf.silentJvmAdvice")) {
                return;
            }

            final List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
            final int feature = Runtime.version().feature();
            final List<String> hints = new ArrayList<>(8);

            final boolean hasXms = hasPrefix(args, "-Xms");
            final boolean hasPreTouch = args.contains("-XX:+AlwaysPreTouch");
            final boolean hasPerfMem = args.contains("-XX:+PerfDisableSharedMem");
            final boolean hasMetaspaceCap = hasPrefix(args, "-XX:MaxMetaspaceSize");
            final boolean hasDirectCap = hasPrefix(args, "-XX:MaxDirectMemorySize");
            final boolean hasCompactHeaders = args.contains("-XX:+UseCompactObjectHeaders");
            final boolean hasNativeAccess = hasPrefix(args, "--enable-native-access");
            final boolean hasSharedArchive = hasPrefix(args, "-XX:SharedArchiveFile") || hasPrefix(args, "-XX:AOTCache");
            final boolean explicitGc = args.contains("-XX:+UseG1GC") || args.contains("-XX:+UseZGC")
                || args.contains("-XX:+UseParallelGC") || args.contains("-XX:+UseShenandoahGC")
                || args.contains("-XX:+UseSerialGC");

            if (!hasXms) {
                hints.add("-Xms<same as -Xmx>              fixed heap: no resize pauses, no late page faults");
            }
            if (!hasPreTouch) {
                hints.add("-XX:+AlwaysPreTouch              touch the heap during startup instead of during ticks");
            }
            if (!hasPerfMem) {
                hints.add("-XX:+PerfDisableSharedMem        stop writing hsperfdata into /tmp from tick threads");
            }
            if (!hasMetaspaceCap) {
                hints.add("-XX:MaxMetaspaceSize=512m        cap the class metadata the library loader grows");
            }
            if (!hasDirectCap) {
                hints.add("-XX:MaxDirectMemorySize=2g       account Netty/chunk buffers separately from the heap");
            }
            if (!explicitGc) {
                hints.add("-XX:+UseG1GC                     pick the collector explicitly instead of the default");
            }
            if (feature >= 21 && feature < 23 && args.contains("-XX:+UseZGC") && !args.contains("-XX:+ZGenerational")) {
                hints.add("-XX:+ZGenerational               generational ZGC (JDK 21/22 needs it explicitly)");
            }
            if (feature >= 24 && !hasCompactHeaders) {
                hints.add("-XX:+UseCompactObjectHeaders     JDK 24+: 8 byte object headers, less heap, better cache use");
            }
            if (feature >= 22 && !hasNativeAccess) {
                hints.add("--enable-native-access=ALL-UNNAMED  JDK 22+: silences native access warnings");
            }
            if (!hasSharedArchive) {
                hints.add(
                    feature >= 24
                        ? "-XX:AOTCache=leaf.aot            JDK 24+: AOT/CDS archive, faster and leaner startup (see scripts/leaf-perf/leaf-cds.sh)"
                        : "-XX:SharedArchiveFile=leaf-cds.jsa  AppCDS archive, faster and leaner startup (see scripts/leaf-perf/leaf-cds.sh)"
                );
            }

            if (hints.isEmpty()) {
                return;
            }

            final StringBuilder message = new StringBuilder(512);
            message.append(PREFIX).append("JVM flags could be improved (").append(hints.size()).append(" hint(s)):\n");
            final int printed = Math.min(hints.size(), MAX_PRINTED_HINTS);
            for (int i = 0; i < printed; i++) {
                message.append(PREFIX).append("  ").append(hints.get(i)).append('\n');
            }
            if (hints.size() > printed) {
                message.append(PREFIX).append("  ... and ").append(hints.size() - printed).append(" more\n");
            }
            message.append(PREFIX).append("Generated preset: scripts/leaf-perf/leaf-flags.sh, archive: scripts/leaf-perf/leaf-cds.sh");
            message.append('\n').append(PREFIX).append("Silence this report with -Dleaf.silentJvmAdvice=true");
            System.out.println(message);
        } catch (Throwable throwable) {
            System.err.println(PREFIX + "JVM flag advice skipped: " + throwable);
        }
    }

    private static boolean hasPrefix(final List<String> args, final String prefix) {
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
