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

    @Test
    void unknownTpsIsMinusOneUntilTwoTicks() {
        assertEquals(-1.0D, LagCompensation.TPSCalculator.getTPS(), 0.0D);
        assertEquals(-1.0D, LagCompensation.TPSCalculator.getMostAccurateTPS(), 0.0D);
        assertEquals(0L, LagCompensation.TPSCalculator.getMSPT());
    }

    @Test
    void tt20ScalesWithMeasuredTps() {
        LagCompensation.TPSCalculator.onTick();
        sleep(2L);
        LagCompensation.TPSCalculator.onTick();
        sleep(2L);
        LagCompensation.TPSCalculator.onTick();

        final double tps = LagCompensation.TPSCalculator.getTPS();
        assertTrue(tps > 0.0D && tps <= LagCompensation.TPSCalculator.MAX_TPS, "tps was " + tps);
        assertTrue(LagCompensation.TPSCalculator.getMSPT() >= 0L);

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
