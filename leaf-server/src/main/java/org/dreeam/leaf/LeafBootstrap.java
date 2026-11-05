package org.dreeam.leaf;

import io.papermc.paper.PaperBootstrap;
import joptsimple.OptionSet;
import org.dreeam.leaf.perf.CpuFeatures;
import org.dreeam.leaf.perf.JvmFlagAdvisor;
import org.dreeam.leaf.perf.LeafRuntimeTuning;
import org.dreeam.leaf.perf.MemoryFootprint;

public class LeafBootstrap {

    // Leaf - FMA feature
    // Explicit -DLeaf.enableFMA=true/false always wins; otherwise the CPU decides for us.
    public static final boolean enableFMA = resolveFma();

    public static void boot(final OptionSet options) {
        //runPreBootTasks();

        // Leaf start - boot time tuning (must run before Netty / sun.nio.ch are initialised)
        LeafRuntimeTuning.apply();
        JvmFlagAdvisor.printAdvice();
        // Boot and shutdown snapshot of heap / metaspace / direct memory / RSS and of the jar
        // directories, so "where did the 2 GB go" is answered with numbers.
        MemoryFootprint.install();
        reportLibraryLoaders();
        // Only does something when -Dleaf.offHeap.chunkCacheMB is set.
        org.dreeam.leaf.perf.offheap.OffHeapChunkPayloadCache.installShutdownReport();
        // Leaf end - boot time tuning

        PaperBootstrap.boot(options);
    }

    /**
     * Leaf - prints how many library class loaders were shared between plugins, once, at shutdown.
     * <p>
     * The counters live in {@code org.bukkit.plugin.java.LibraryLoader}; reading them at boot would
     * class load the maven stack this server is trying to avoid, so the report runs at shutdown when
     * every plugin that wanted a library already asked for one.
     */
    private static void reportLibraryLoaders() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    System.out.println("[Leaf] Library loader: "
                        + org.bukkit.plugin.java.LibraryLoader.sharedLoaderStats());
                } catch (Throwable ignored) {
                    // the class may not even have been loaded if no plugin used libraries
                }
            }, "Leaf library loader report"));
        } catch (Throwable ignored) {
            // never break boot because of a diagnostic
        }
    }

    private static boolean resolveFma() {
        final String property = System.getProperty("Leaf.enableFMA");
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        return CpuFeatures.canUseFma();
    }

    private static void runPreBootTasks() {
    }
}
