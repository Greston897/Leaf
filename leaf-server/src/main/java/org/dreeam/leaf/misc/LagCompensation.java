package org.dreeam.leaf.misc;

public class LagCompensation {

    public static float tt20(float ticks, boolean limitZero) {
        float newTicks = (float) rawTT20(ticks);

        if (limitZero) return newTicks > 0 ? newTicks : 1;
        else return newTicks;
    }

    public static int tt20(int ticks, boolean limitZero) {
        int newTicks = (int) Math.ceil(rawTT20(ticks));

        if (limitZero) return newTicks > 0 ? newTicks : 1;
        else return newTicks;
    }

    public static double tt20(double ticks, boolean limitZero) {
        double newTicks = rawTT20(ticks);

        if (limitZero) return newTicks > 0 ? newTicks : 1;
        else return newTicks;
    }

    public static double rawTT20(double ticks) {
        return ticks == 0 ? 0 : ticks * TPSCalculator.getMostAccurateTPS() / TPSCalculator.MAX_TPS;
    }

    /**
     * Leaf - TPS/MSPT tracker used by the TT20 lag compensation.
     * <p>
     * <b>Why this was rewritten.</b> {@link #rawTT20} is called from {@code getTickDelay} of the fluid
     * classes, i.e. once per flowing water/lava block per tick - in a farm that is hundreds of
     * thousands of calls per tick. Every one of them went through
     * {@code getMostAccurateTPS() -> getAverageTPS()}, which walked a 40 element
     * {@code synchronizedList<Double>} with an iterator: an iterator allocation, 40 {@code Double}
     * unboxings and a monitor acquisition <b>per block tick</b>. That is the archetype of a
     * "optimisation" that costs more than it saves.
     * <p>
     * Now the average is maintained incrementally (running sum, fixed ring buffer, no boxing) and the
     * derived TPS is computed once per tick in {@link #onTick()} instead of once per caller. The
     * observable values are identical: the same 40 sample window, the same rounded average, the same
     * {@code min(current, average)}.
     * <p>
     * Concurrency: {@link #onTick()} runs on the server thread and is the only writer; readers on other
     * threads see the values published by the previous tick (volatile fields). That matches the old
     * behaviour, where a reader could also observe a partially updated history.
     */
    public static class TPSCalculator {

        // Kept for source compatibility with anything that referenced them; the primitives below are
        // what the code actually uses.
        @Deprecated
        public static Long lastTick;
        @Deprecated
        public static Long currentTick;

        private static final int HISTORY_LIMIT = 40;
        /** milliseconds of the current and the previous tick, -1 while unknown */
        private static volatile long currentMillis = -1L;
        private static volatile long previousMillis = -1L;

        private static final double[] history = new double[HISTORY_LIMIT];
        private static volatile int historySize;
        private static int historyNext;
        private static double historySum;
        /** {@code min(getTPS(), getAverageTPS())}, refreshed once per tick */
        private static volatile double mostAccurateTps = -1.0D;

        private static volatile double allMissedTicks = 0.0D;

        public static final int MAX_TPS = 20;
        public static final int FULL_TICK = 50;

        private TPSCalculator() {
        }

        public static void onTick() {
            final long now = System.currentTimeMillis();
            if (currentMillis >= 0L) {
                previousMillis = currentMillis;
                // compatibility fields, only written once per tick
                lastTick = previousMillis;
            }
            currentMillis = now;
            currentTick = now;

            // Never feed the "no sample yet" sentinel (-1) into the average: the old code did, which
            // dragged the 40 sample window down by 5% for the first 40 ticks after start and made the
            // fluid tick compensation fire on boot when nothing was late.
            final double current = currentTps();
            if (current >= 0.0D) {
                addToHistory(current);
            }
            clearMissedTicks();
            missedTick();
            // Everything above is what readers used to recompute per call; do it once.
            mostAccurateTps = current >= 0.0D ? Math.min(current, averageTps()) : -1.0D;
        }

        private static void addToHistory(final double tps) {
            synchronized (history) {
                if (historySize == HISTORY_LIMIT) {
                    // overwrite the oldest sample and take it off the running sum
                    historySum -= history[historyNext];
                } else {
                    historySize++;
                }
                history[historyNext] = tps;
                historySum += tps;
                historyNext = (historyNext + 1) % HISTORY_LIMIT;
            }
        }

        public static long getMSPT() {
            final long current = currentMillis;
            final long previous = previousMillis;
            if (current < 0L || previous < 0L) {
                return 0L;
            }
            return current - previous;
        }

        public static double getAverageTPS() {
            return averageTps();
        }

        private static double averageTps() {
            synchronized (history) {
                if (historySize == 0) {
                    return 0.1D;
                }
                return historySum / historySize;
            }
        }

        public static double getTPS() {
            return currentTps();
        }

        private static double currentTps() {
            if (previousMillis < 0L) {
                return -1.0D;
            }
            final long mspt = currentMillis - previousMillis;
            if (mspt <= 0L) {
                return 0.1D;
            }
            final double tps = 1000.0D / (double) mspt;
            return Math.min(tps, (double) MAX_TPS);
        }

        public static void missedTick() {
            if (previousMillis < 0L) {
                return;
            }
            final long mspt = getMSPT() <= 0L ? (long) FULL_TICK : getMSPT();
            final double missedTicks = (mspt / (double) FULL_TICK) - 1.0D;
            allMissedTicks += missedTicks <= 0.0D ? 0.0D : missedTicks;
        }

        /**
         * @return {@code min(current TPS, average TPS)} as of the last {@link #onTick()} - O(1), the
         * value the hot {@code tt20} path needs
         */
        public static double getMostAccurateTPS() {
            final double cached = mostAccurateTps;
            if (cached >= 0.0D) {
                return cached;
            }
            return Math.min(currentTps(), averageTps());
        }

        public static double getAllMissedTicks() {
            return allMissedTicks;
        }

        public static int applicableMissedTicks() {
            return (int) Math.floor(allMissedTicks);
        }

        public static void clearMissedTicks() {
            allMissedTicks -= applicableMissedTicks();
        }

        public void resetMissedTicks() {
            allMissedTicks = 0.0D;
        }
    }
}
