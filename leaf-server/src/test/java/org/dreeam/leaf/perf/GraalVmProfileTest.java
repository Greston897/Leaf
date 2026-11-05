package org.dreeam.leaf.perf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaf - unit tests for {@link GraalVmProfile}.
 * <p>
 * The class only reads VM state, but it runs at boot on every server (including exotic VMs where a
 * flag or an MXBean is missing), so the contract is: never throw, never require a flag, and give
 * advice that matches what the VM actually reports.
 */
class GraalVmProfileTest {

    @Test
    void detectsTheVmWithoutFailing() {
        assertNotNull(GraalVmProfile.vmName());
        assertFalse(GraalVmProfile.vmName().isBlank());
        assertNotNull(GraalVmProfile.vmVersion());
        // detection must look at every identity property: GraalVM Community reports
        // java.vm.name = "OpenJDK 64-Bit Server VM" and only its vendor says GraalVM
        final boolean expected = java.util.stream.Stream.of("java.vm.name", "java.vm.vendor",
                "java.vm.version", "java.vendor", "java.vendor.version", "java.runtime.name",
                "java.runtime.version")
            .map(property -> System.getProperty(property, "").toLowerCase(java.util.Locale.ROOT))
            .anyMatch(value -> value.contains("graalvm"));
        assertEquals(expected, GraalVmProfile.isGraalVm());
        assertEquals(System.getProperty("java.vm.version", "").toLowerCase(java.util.Locale.ROOT).contains("-jvmci-"),
            GraalVmProfile.isJvmciBuild());
    }

    @Test
    void adviceIsConsistentWithTheReportedFlags() {
        final List<String> advice = GraalVmProfile.advice();
        assertNotNull(advice);

        final boolean compactHeaders = GraalVmProfile.vmFlagEnabled("UseCompactObjectHeaders");
        // only the dedicated line counts: the GraalVM note mentions compact headers as well
        final boolean mentionsHeaders = advice.stream().anyMatch(line -> line.startsWith("compact object headers are off"));
        // the hint is present exactly when the feature is off - on a VM that does not know the flag at
        // all getVMOption throws and we treat that as "off", which is also the honest answer
        assertEquals(!compactHeaders, mentionsHeaders);
    }

    @Test
    void printingNeverThrows() {
        GraalVmProfile.printProfile();
    }
}
