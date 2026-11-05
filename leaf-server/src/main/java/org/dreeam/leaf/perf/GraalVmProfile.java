package org.dreeam.leaf.perf;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Leaf - GraalVM / modern JDK profile.
 * <p>
 * <b>What this class is not.</b> It is not a vendor lock-in and it does not require anything: the server
 * starts and behaves identically on any JDK 21+. It exists because the newest JVMs ship features that
 * are worth a lot of memory and a bit of tick time, and those features are invisible unless someone
 * checks for them and tells the administrator what this particular machine is missing.
 * <p>
 * <b>What it measures.</b> The VM and JDK version, the garbage collector, the heap limit, and - through
 * {@code com.sun.management.HotSpotDiagnosticMXBean}, which exists on every OpenJDK derived VM including
 * GraalVM - the value of the flags that decide the memory profile:
 * <ul>
 *     <li>{@code UseCompactObjectHeaders} (JEP 519, JDK 24+/GraalVM 24+): the object header drops from
 *     12 to 8 bytes and array headers from 16 to 12. On a Minecraft heap that is dominated by millions of
 *     small objects (positions, states, entries) it typically removes 10-25 % of the <i>live set</i>,
 *     which in turn removes that much GC work; it has to be enabled explicitly
 *     ({@code -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders}) and nothing in the code can
 *     turn it on at runtime.</li>
 *     <li>{@code MaxMetaspaceSize}: unbounded by default, and the first thing that turns "the server
 *     eats 2 GB" into an unbounded number when plugins download libraries.</li>
 *     <li>{@code MaxDirectMemorySize}: Netty arenas, chunk buffers and region file reads do not live in
 *     {@code -Xmx}. {@link LeafRuntimeTuning} already caps the arenas and the JDK buffer cache; the
 *     remaining ceiling is this flag.</li>
 *     <li>{@code UseStringDeduplication} (G1): plugin and recipe data hold many identical strings.</li>
 * </ul>
 * Every check is defensive: an unknown flag, a missing MXBean or a denied property read simply means
 * "no advice for that item".
 */
public final class GraalVmProfile {

    private static final String PREFIX = "[Leaf] ";

    private GraalVmProfile() {
    }

    /**
     * System properties that identify the VM. {@code java.vm.name} alone is <b>not</b> enough: GraalVM
     * Community 25 still reports {@code java.vm.name = "OpenJDK 64-Bit Server VM"} and only says
     * "GraalVM Community" in {@code java.vm.vendor}/{@code java.vendor}. Checking the name first made
     * this class print "not running on GraalVM" on a real GraalVM - the alternative VM job in CI caught
     * it, which is exactly why that job exists.
     */
    private static final String[] VM_IDENTITY_PROPERTIES = {
        "java.vm.name", "java.vm.vendor", "java.vm.version", "java.vendor", "java.vendor.version",
        "java.runtime.name", "java.runtime.version",
    };

    /**
     * @return {@code true} when running on a GraalVM build (GraalVM Community, GraalVM for JDK x,
     * Oracle GraalVM, Mandrel, ...)
     */
    public static boolean isGraalVm() {
        for (final String property : VM_IDENTITY_PROPERTIES) {
            if (System.getProperty(property, "").toLowerCase(java.util.Locale.ROOT).contains("graalvm")) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} for a build that carries the JVMCI compiler interface (a stock OpenJDK
     * compiled with JVMCI, or any GraalVM). Such a build is not a GraalVM, but it shares the newer
     * compiler plumbing, so the "you could run GraalVM" note is pointless there.
     */
    public static boolean isJvmciBuild() {
        return System.getProperty("java.vm.version", "").toLowerCase(java.util.Locale.ROOT).contains("-jvmci-");
    }

    public static String vmName() {
        return System.getProperty("java.vm.name", "unknown");
    }

    public static String vmVersion() {
        return System.getProperty("java.vm.version", "unknown") + " / JDK " + System.getProperty("java.specification.version", "?");
    }

    /**
     * Prints the profile and the advice once, at boot. Never throws.
     */
    public static void printProfile() {
        try {
            final StringBuilder out = new StringBuilder(256);
            out.append(PREFIX).append(isGraalVm() ? "GraalVM" : isJvmciBuild() ? "JVM (JVMCI)" : "JVM")
                .append(' ').append(vmVersion())
                .append(" | gc ").append(garbageCollectors())
                .append(" | heap max ").append(Runtime.getRuntime().maxMemory() / (1024L * 1024L)).append(" MiB")
                .append(" | cores ").append(Runtime.getRuntime().availableProcessors());
            System.out.println(out);

            final List<String> advice = advice();
            if (!advice.isEmpty()) {
                System.out.println(PREFIX + "Memory advice for this VM (" + advice.size() + " item(s)):");
                for (final String line : advice) {
                    System.out.println(PREFIX + "  - " + line);
                }
            }
        } catch (Throwable throwable) {
            System.err.println(PREFIX + "VM profile unavailable: " + throwable);
        }
    }

    /**
     * @return the advice lines for the current VM, empty when everything relevant is already configured
     */
    public static List<String> advice() {
        final List<String> advice = new ArrayList<>(4);

        if (!vmFlagEnabled("UseCompactObjectHeaders")) {
            advice.add("compact object headers are off: 8 byte headers instead of 12 typically shrink a"
                + " Minecraft live set by 10-25 %. JDK/GraalVM 24+: -XX:+UnlockExperimentalVMOptions"
                + " -XX:+UseCompactObjectHeaders");
        }

        if ("0".equals(vmFlag("MaxMetaspaceSize"))) {
            advice.add("MaxMetaspaceSize is unlimited - every plugin and every downloaded library lives"
                + " there. A limit (e.g. -XX:MaxMetaspaceSize=512m) turns a slow leak into a clear error");
        }

        if ("0".equals(vmFlag("MaxDirectMemorySize")) && Runtime.getRuntime().maxMemory() > (2L << 30)) {
            advice.add("MaxDirectMemorySize is unlimited; Netty arenas and chunk compression live outside"
                + " -Xmx. A ceiling (roughly a quarter of the heap) keeps them from competing with it");
        }

        if (isG1() && !vmFlagEnabled("UseStringDeduplication")) {
            advice.add("G1 is in use but UseStringDeduplication is off - recipe, plugin and chat data hold"
                + " many identical strings that cost nothing to deduplicate");
        }

        if (!isGraalVm() && !isJvmciBuild() && Runtime.getRuntime().availableProcessors() >= 4) {
            advice.add("not running on GraalVM: its JIT compiles the tick loop with better inlining and"
                + " its JDK 25 builds can use compact object headers; the server does not require it");
        }

        return advice;
    }

    private static String garbageCollectors() {
        final StringBuilder out = new StringBuilder(32);
        for (final GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(gc.getName());
        }
        return out.length() == 0 ? "unknown" : out.toString();
    }

    private static boolean isG1() {
        return garbageCollectors().contains("G1");
    }

    /**
     * @return the value of a VM flag as reported by the diagnostic bean, or {@code null} when the flag
     * does not exist on this VM (an old JDK, a different GC, a future rename)
     */
    public static String vmFlag(final String name) {
        try {
            final com.sun.management.HotSpotDiagnosticMXBean bean =
                ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            if (bean == null) {
                return null;
            }
            return bean.getVMOption(name).getValue();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean vmFlagEnabled(final String name) {
        return "true".equals(vmFlag(name));
    }
}
