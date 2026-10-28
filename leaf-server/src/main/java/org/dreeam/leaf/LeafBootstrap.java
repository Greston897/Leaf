package org.dreeam.leaf;

import io.papermc.paper.PaperBootstrap;
import joptsimple.OptionSet;
import org.dreeam.leaf.perf.CpuFeatures;
import org.dreeam.leaf.perf.JvmFlagAdvisor;
import org.dreeam.leaf.perf.LeafRuntimeTuning;

public class LeafBootstrap {

    // Leaf - FMA feature
    // Explicit -DLeaf.enableFMA=true/false always wins; otherwise the CPU decides for us.
    public static final boolean enableFMA = resolveFma();

    public static void boot(final OptionSet options) {
        //runPreBootTasks();

        // Leaf start - boot time tuning (must run before Netty / sun.nio.ch are initialised)
        LeafRuntimeTuning.apply();
        JvmFlagAdvisor.printAdvice();
        // Leaf end - boot time tuning

        PaperBootstrap.boot(options);
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
