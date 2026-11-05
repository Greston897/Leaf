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
        // What this VM is, and what it is leaving on the table (compact object headers, metaspace and
        // direct memory ceilings, string deduplication). Advice only - nothing here changes behaviour.
        org.dreeam.leaf.perf.GraalVmProfile.printProfile();
        reportLeafCaches();
        // Only does something when -Dleaf.offHeap.chunkCacheMB is set.
        org.dreeam.leaf.perf.offheap.OffHeapChunkPayloadCache.installShutdownReport();
        // Leaf end - boot time tuning

        PaperBootstrap.boot(options);
    }

    /**
     * Leaf - one line per Leaf owned cache, printed at shutdown.
     * <p>
     * Everything here is pooled state that survives ticks: shared library class loaders, the path
     * finding evaluator pool and the powered rail traversal pool. Reading them at shutdown (when the
     * classes are loaded anyway) means a server that holds on to too much tells you which pool it is,
     * instead of forcing a heap dump.
     */
    private static void reportLeafCaches() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(LeafBootstrap::printLeafCaches, "Leaf cache report"));
        } catch (Throwable ignored) {
            // never break boot because of a diagnostic
        }
    }

    private static void printLeafCaches() {
        try {
            System.out.println("[Leaf] Library loader: " + org.bukkit.plugin.java.LibraryLoader.sharedLoaderStats());
        } catch (Throwable ignored) {
            // the class may not even have been loaded if no plugin used libraries
        }
        try {
            System.out.println("[Leaf] Pathfinding pool: " + org.dreeam.leaf.async.path.NodeEvaluatorCache.stats());
        } catch (Throwable ignored) {
            // async pathfinding may be disabled, the pool then was never touched
        }
        try {
            System.out.println("[Leaf] Rail traversal pool: "
                + org.dreeam.leaf.world.block.OptimizedPoweredRails.trackedPositions() + " tracked positions");
        } catch (Throwable ignored) {
            // powered rails were never updated on this server
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
