package org.dreeam.leaf.perf;

/**
 * Leaf - boot time runtime tuning.
 * <p>
 * Some of the most expensive knobs of a Minecraft server are read exactly once, by classes that are
 * initialised long after {@code LeafBootstrap} ran, and only from the system properties:
 * <ul>
 *     <li>Netty's allocator / leak detector configuration,</li>
 *     <li>the JDK's per-thread direct buffer cache used by {@code sun.nio.ch} (region file I/O).</li>
 * </ul>
 * Setting them here means the administrator does not have to remember a 40 character long
 * {@code -D} incantation in the start script, and - more importantly - the values can be derived from
 * the actual machine instead of a random forum post.
 * <p>
 * Everything is applied <b>only if the user did not configure it explicitly</b>; an explicit
 * {@code -Dkey=value} in the start script always wins. This method never throws: a broken tuning pass
 * must not be able to prevent the server from starting.
 */
public final class LeafRuntimeTuning {

    private static final String PREFIX = "[Leaf] ";

    private LeafRuntimeTuning() {
    }

    public static void apply() {
        try {
            if (Boolean.getBoolean("leaf.disableRuntimeTuning")) {
                return;
            }

            final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            final StringBuilder applied = new StringBuilder(96);

            // --- Netty --------------------------------------------------------------------------
            // Leak detection walks the reference queue and samples allocations on the pipeline
            // threads. A production server does not need it; a developer can still turn it on with
            // -Dio.netty.leakDetection.level=paranoid.
            if (setIfAbsent("io.netty.leakDetection.level", "disabled")) {
                applied.append("netty.leakDetection=disabled ");
            }

            // PooledByteBufAllocator creates 2 * cores direct arenas by default, each of them
            // pre-reserves a 4 MiB chunk on first use, and every arena has its own free lists /
            // caches. Capping this lowers the resident set of the network layer without touching
            // throughput: more than 8 arenas only ever help on machines with > 32 player threads.
            final int arenas = Math.min(2 * cores, 8);
            if (setIfAbsent("io.netty.allocator.numDirectArenas", Integer.toString(Math.max(2, arenas)))) {
                applied.append("netty.directArenas=").append(Math.max(2, arenas)).append(' ');
            }

            // --- JDK direct buffer cache --------------------------------------------------------
            // 0 (the default) means "cache whatever you allocate, forever, per thread". Region file
            // reads and chunk compression allocate multi-megabyte direct buffers; keeping them
            // cached in every thread is a classic hidden few hundred MiB.
            if (setIfAbsent("jdk.nio.maxCachedBufferSize", "262144")) {
                applied.append("nio.maxCachedBufferSize=262144 ");
            }

            // --- CPU/ISA ------------------------------------------------------------------------
            if (CpuFeatures.hasFma()) {
                applied.append("fma=on ");
            }

            if (!applied.isEmpty() && !Boolean.getBoolean("leaf.silentRuntimeTuning")) {
                System.out.println(PREFIX + "Runtime tuning: " + applied.toString().trim());
            }
        } catch (Throwable throwable) {
            // Never break the boot sequence because of an optimisation.
            System.err.println(PREFIX + "Runtime tuning skipped: " + throwable);
        }
    }

    private static boolean setIfAbsent(final String key, final String value) {
        if (System.getProperty(key) != null) {
            return false;
        }
        try {
            System.setProperty(key, value);
            return true;
        } catch (SecurityException | IllegalArgumentException ignored) {
            return false;
        }
    }
}
