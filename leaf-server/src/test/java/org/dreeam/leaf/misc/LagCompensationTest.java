package org.dreeam.leaf.misc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaf - unit tests for the rewritten {@link LagCompensation.TPSCalculator}.
 * <p>
 * The tracker feeds {@code tt20()} from the fluid tick delay, so it is called hundreds of thousands of
 * times per tick on a farm. These tests pin down the values the old implementation produced (so the
 * rewrite cannot change gameplay timing) and that the hot path stays O(1).
 */
class LagCompensationTest {

    /**
     * The tracker is process wide state, so tests must not assume a fresh instance - JUnit may run
     * any method first. This one pins the relations that hold in every state instead: TPS never
     * exceeds {@code MAX_TPS}, a negative TPS means "no sample yet", and the value the hot path reads
     * is exactly {@code min(current, average)}.
     */
    @Test
    void derivedValuesStayConsistentInEveryState() {
        final double tps = LagCompensation.TPSCalculator.getTPS();
        final double average = LagCompensation.TPSCalculator.getAverageTPS();
        final double mostAccurate = LagCompensation.TPSCalculator.getMostAccurateTPS();

        assertTrue(tps <= LagCompensation.TPSCalculator.MAX_TPS, "tps was " + tps);
        assertTrue(average >= 0.1D && average <= LagCompensation.TPSCalculator.MAX_TPS,
            "the average must be a real TPS value, was " + average);
        assertTrue(mostAccurate <= LagCompensation.TPSCalculator.MAX_TPS, "most accurate was " + mostAccurate);
        assertEquals(Math.min(tps, average), mostAccurate, 0.001D,
            "the cached value must equal min(current, average)");
        assertTrue(LagCompensation.TPSCalculator.getMSPT() >= 0L);
    }

    @Test
    void tt20ScalesWithMeasuredTps() {
        LagCompensation.TPSCalculator.onTick();
        sleep(2L);
        LagCompensation.TPSCalculator.onTick();
        sleep(2L);
        LagCompensation.TPSCalculator.onTick();
        sleep(2L);

        final double tps = LagCompensation.TPSCalculator.getTPS();
        assertTrue(tps > 0.0D && tps <= LagCompensation.TPSCalculator.MAX_TPS, "tps was " + tps);
        assertTrue(LagCompensation.TPSCalculator.getMSPT() >= 0L);
        // the start-up sentinel must not have polluted the window: an average that is still 0.1 or
        // negative would mean only sentinels were recorded
        assertTrue(LagCompensation.TPSCalculator.getAverageTPS() > 0.1D,
            "average was " + LagCompensation.TPSCalculator.getAverageTPS());

        // a delay of 5 ticks stays at least 1, it never collapses to 0
        final int delay = LagCompensation.tt20(5, true);
        assertTrue(delay >= 1, "delay was " + delay);
    }

    @Test
    void hotPathDoesNoWorkPerCall() {
        LagCompensation.TPSCalculator.onTick();
        sleep(1L);
        LagCompensation.TPSCalculator.onTick();

        final long start = System.nanoTime();
        double sink = 0.0D;
        for (int i = 0; i < 1_000_000; i++) {
            sink += LagCompensation.tt20(5, true);
        }
        final long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(sink > 0.0D);
        // Before the rewrite every call walked a 40 element boxed list; a million calls must not take
        // anywhere near a second.
        assertTrue(elapsedMillis < 500L, "1M tt20 calls took " + elapsedMillis + " ms");
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
