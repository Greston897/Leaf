package org.dreeam.leaf.perf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaf - unit tests for {@link MemoryFootprint}.
 * <p>
 * The report has to be cheap and total: it runs at boot and at shutdown of every server, so the only
 * thing that can go wrong is that it throws or prints nothing useful on a platform where one of the
 * pools or {@code /proc} is missing. Both are covered here.
 */
class MemoryFootprintTest {

    @Test
    void reportNamesEveryPoolWeCareAbout() {
        final List<String> lines = MemoryFootprint.describe("test");

        assertFalse(lines.isEmpty(), "the report must never be empty");
        final String first = lines.get(0);
        assertTrue(first.contains("heap used"), first);
        assertTrue(first.contains("metaspace"), first);
        assertTrue(first.contains("direct"), first);
        assertTrue(first.contains("classes"), first);
        assertTrue(first.startsWith("[Leaf] [test]"), first);
    }

    @Test
    void reportIsRepeatableAndCheap() {
        final long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            MemoryFootprint.describe("loop");
        }
        final long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        // 1000 reports must not take a noticeable amount of time; the pools are read from MXBeans
        // which are plain field reads, and /proc is a single small file.
        assertTrue(elapsedMillis < 2000L, "1000 reports took " + elapsedMillis + " ms");
    }

    @Test
    void canBeSilenced() {
        final String previous = System.getProperty("leaf.memoryReport");
        try {
            System.setProperty("leaf.memoryReport", "false");
            assertFalse(MemoryFootprint.enabled());

            System.setProperty("leaf.memoryReport", "true");
            assertTrue(MemoryFootprint.enabled());
        } finally {
            if (previous == null) {
                System.clearProperty("leaf.memoryReport");
            } else {
                System.setProperty("leaf.memoryReport", previous);
            }
        }
    }
}
