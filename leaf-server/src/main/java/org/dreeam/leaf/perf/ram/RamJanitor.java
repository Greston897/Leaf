package org.dreeam.leaf.perf.ram;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Leaf - the RAM janitor: a pressure driven, cost aware cleaner for every cache the server owns.
 * <p>
 * <b>The problem it solves.</b> A Minecraft server accumulates caches: serialised chunk payloads, path
 * finding evaluators, memo maps on the tick path, per-player session state. Each of them is bounded -
 * and every bound is wrong in some situation: a server with 200 players holds more payloads than one
 * with 5, a farm holds more path evaluators than an empty world, and {@code -Xmx} is not the same on
 * every machine. The usual answer is a timer ("clean every 5 minutes") or nothing at all, and both are
 * wrong for the same reason: at 4 % heap usage the cleanup is pure waste, at 96 % it comes too late.
 * <p>
 * <b>How this janitor decides.</b> It is not a timer. It watches the heap and only acts when the server
 * is actually under pressure, and then it sheds <i>exactly as much as it needs</i>:
 * <ol>
 *     <li><b>Watermarks.</b> Below the soft watermark (default 70 % of {@code -Xmx}) nothing happens.
 *     Between soft and hard the pass aims at the soft target. Above the hard watermark (90 %) the pass
 *     also takes caches that are expensive to refill, and above the emergency watermark (95 %) it takes
 *     everything, ignoring the cooldown.</li>
 *     <li><b>Needs driven, not ratio driven.</b> A pass computes {@code needed = used - softTarget} and
 *     trims caches one by one until that many bytes are gone. A heap that is 2 % over the line loses a
 *     sliver, not a quarter of every cache.</li>
 *     <li><b>Cold first, second chance for the useful.</b> Each cache reports a monotonic hit counter.
 *     Between two passes the janitor turns it into a <i>recent</i> hit rate and sorts by
 *     {@code coldness x retained bytes}: the big cache nobody touched goes first, the small hot one
 *     keeps its entries. A cache that was hit at least once per entry since the last pass is skipped
 *     entirely below the hard watermark.</li>
 *     <li><b>Time budget.</b> A pass may spend at most {@link Policy#maxPassNanos()} (default 2 ms) and
 *     touches at most {@link Policy#maxCachesPerPass()} caches. Whatever is left waits for the next
 *     pass - eviction must never become the reason a tick is late.</li>
 *     <li><b>Do not fight the collector.</b> If a GC cycle finished since the previous observation and
 *     the heap is below the hard watermark, the pass is skipped: the collector has just done the work,
 *     and evicting caches on top of it would only cost a reload later.</li>
 *     <li><b>Do not add latency to a struggling server.</b> Above {@link Policy#maxMspt()} the pass is
 *     postponed: a server at 60 mspt needs fewer stalls, not more work.</li>
 *     <li><b>Hysteresis.</b> After a pass the janitor re-arms only when the heap has grown by at least
 *     {@code max(3 % of max, 48 MiB)} since the pass, or when the cooldown expired. Without this a
 *     server sitting exactly on the watermark would trim on every single tick.</li>
 * </ol>
 * Everything is a pure function of the numbers passed to {@link #tick}, so the policy is unit tested
 * without a server, a heap or a clock: {@code RamJanitorTest} drives it with fake memory readings.
 */
public final class RamJanitor {

    /**
     * @param softWatermark    fraction of max heap above which passes are allowed
     * @param hardWatermark    fraction above which expensive caches are trimmed as well
     * @param emergencyWatermark fraction above which even the cooldown is ignored
     * @param maxMspt          tick time above which a pass is postponed
     * @param minIntervalNanos cooldown between two passes
     * @param maxPassNanos     hard time budget of one pass
     * @param maxCachesPerPass upper bound on how many caches one pass visits
     * @param rearmBytesMin    minimum heap growth (bytes) before a pass is armed again
     * @param rearmFraction    additional re-arm threshold as a fraction of max heap
     */
    public record Policy(double softWatermark, double hardWatermark, double emergencyWatermark,
                         double maxMspt, long minIntervalNanos, long maxPassNanos,
                         int maxCachesPerPass, long rearmBytesMin, double rearmFraction) {

        public static Policy defaults() {
            return new Policy(0.70D, 0.90D, 0.95D, 45.0D, 15_000_000_000L, 2_000_000L,
                6, 48L * 1024L * 1024L, 0.03D);
        }

        public Policy {
            if (softWatermark <= 0.0D || hardWatermark <= softWatermark
                || emergencyWatermark <= hardWatermark || emergencyWatermark > 1.0D) {
                throw new IllegalArgumentException("watermarks must increase: "
                    + softWatermark + " < " + hardWatermark + " < " + emergencyWatermark + " <= 1.0");
            }
            if (maxPassNanos <= 0L || minIntervalNanos < 0L || maxCachesPerPass <= 0) {
                throw new IllegalArgumentException("non-positive janitor budget");
            }
        }
    }

    /**
     * The result of one pass, kept as the last report and printed by the janitor and by
     * {@code /leaf ram}.
     */
    public record Pass(int number, String trigger, double beforeFraction, double afterFraction,
                       long freedBytes, int visitedCaches, int trimmedCaches, long nanos,
                       boolean budgetExhausted, String detail) {

        public String summary() {
            return "pass #" + number + " (" + trigger + ") heap " + percent(beforeFraction)
                + " -> " + percent(afterFraction) + ", released " + mib(freedBytes) + " from "
                + trimmedCaches + '/' + visitedCaches + " caches in " + (nanos / 1_000_000.0D) + " ms"
                + (budgetExhausted ? " [budget hit]" : "")
                + (detail.isEmpty() ? "" : " [" + detail + ']');
        }

        private static String percent(final double fraction) {
            return String.format(java.util.Locale.ROOT, "%.1f%%", fraction * 100.0D);
        }

        private static String mib(final long bytes) {
            return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0D * 1024.0D));
        }
    }

    private static final class Candidate {
        private Trimmable cache;
        private long bytes;
        private long recentHits;
        private double score;
    }

    private final Policy policy;
    private final LongSupplier clock;
    private final List<Trimmable> caches = new ArrayList<>(8);
    private final Map<Trimmable, Long> hitsAtLastPass = new IdentityHashMap<>(8);
    private final StringBuilder scratch = new StringBuilder(96);

    private long lastPassNanos = Long.MIN_VALUE;
    private long lastPassAfterBytes;
    private long lastGcCount = -1L;
    private long totalFreedBytes;
    private int passCount;
    private Pass lastPass;

    public RamJanitor(final Policy policy, final LongSupplier nanoClock) {
        this.policy = policy;
        this.clock = nanoClock;
    }

    public void register(final Trimmable cache) {
        if (cache == null || caches.contains(cache)) {
            return;
        }
        caches.add(cache);
    }

    public Policy policy() {
        return policy;
    }

    public List<Trimmable> caches() {
        return caches;
    }

    public Pass lastPass() {
        return lastPass;
    }

    public int passCount() {
        return passCount;
    }

    public long totalFreedBytes() {
        return totalFreedBytes;
    }

    /**
     * One call per server tick. The common path - no pressure, or a cooldown - allocates nothing and
     * returns {@code null}.
     *
     * @param tick      server tick counter, only used for logging
     * @param usedBytes floor of {@code total - free}
     * @param maxBytes  {@code -Xmx}
     * @param mspt      current tick time, used to avoid working on a server that is already late
     * @param gcCount   total number of collections so far, or a negative value when unknown
     * @return the pass that ran, or {@code null}
     */
    public Pass tick(final long tick, final long usedBytes, final long maxBytes, final double mspt,
                     final long gcCount) {
        if (maxBytes <= 0L || caches.isEmpty()) {
            return null;
        }

        final double fraction = (double) usedBytes / (double) maxBytes;
        final boolean emergency = fraction >= policy.emergencyWatermark();

        if (!emergency) {
            if (fraction < policy.softWatermark()) {
                lastGcCount = gcCount;
                return null;
            }
            if (mspt > policy.maxMspt()) {
                return null;
            }
            final long now = clock.getAsLong();
            if (lastPassNanos != Long.MIN_VALUE) {
                if (now - lastPassNanos < policy.minIntervalNanos()) {
                    return null;
                }
                if (usedBytes - lastPassAfterBytes < rearmBytes(maxBytes)) {
                    return null;
                }
            }
            // A collector that just ran has already done the expensive part. Below the hard watermark
            // that is the end of the story - evicting caches now would only force a reload later.
            if (gcCount >= 0L && lastGcCount >= 0L && gcCount != lastGcCount
                && fraction < policy.hardWatermark()) {
                lastGcCount = gcCount;
                return null;
            }
        }

        lastGcCount = gcCount;
        return run(tick, usedBytes, maxBytes, emergency ? "pressure" : "soft", false);
    }

    /**
     * Forces a pass regardless of the watermarks and the cooldown - the {@code /leaf ram trim} command.
     *
     * @param dryRun when {@code true} the caches only report what they would release
     */
    public Pass force(final long usedBytes, final long maxBytes, final boolean dryRun) {
        return run(0L, usedBytes, maxBytes, dryRun ? "dry-run" : "manual", dryRun);
    }

    private long run(final long tick, final long usedBytes, final long maxBytes, final String trigger,
                     final boolean dryRun) {
        final long start = clock.getAsLong();
        scratch.setLength(0);
        final double before = maxBytes <= 0L ? 0.0D : (double) usedBytes / (double) maxBytes;
        final boolean aggressive = before >= policy.hardWatermark() || "manual".equals(trigger)
            || "dry-run".equals(trigger);
        final long target = (long) (policy.softWatermark() * (double) maxBytes);
        final long needed = Math.max(usedBytes - target, 0L);

        final List<Candidate> candidates = collect(aggressive);
        long freed = 0L;
        int visited = 0;
        int trimmed = 0;
        boolean budgetExhausted = false;

        for (final Candidate candidate : candidates) {
            if (visited >= policy.maxCachesPerPass()) {
                budgetExhausted = true;
                break;
            }
            if (clock.getAsLong() - start > policy.maxPassNanos()) {
                budgetExhausted = true;
                break;
            }
            visited++;

            // How much of this cache do we want? Only as much as the remaining deficit, so a single
            // large cache does not get wiped just because it happened to be first in the order.
            final long remaining = needed - freed;
            final double ratio;
            if (aggressive) {
                ratio = remaining <= 0L ? 0.25D : clamp((double) remaining / (double) Math.max(1L, candidate.bytes), 0.1D, 1.0D);
            } else {
                ratio = clamp((double) Math.max(1L, remaining) / (double) Math.max(1L, candidate.bytes), 0.05D, 0.5D);
            }

            final long released;
            try {
                released = candidate.cache.trim(ratio, dryRun);
            } catch (final Throwable throwable) {
                // A broken cache must not abort the whole pass.
                continue;
            }
            if (released > 0L) {
                freed += released;
                trimmed++;
                appendDetail(candidate.cache.id(), released);
            }
            if (needed > 0L && freed >= needed && !aggressive) {
                break;
            }
        }

        final long nanos = Math.max(0L, clock.getAsLong() - start);
        if (!dryRun) {
            passCount++;
            totalFreedBytes += freed;
            lastPassNanos = start;
            lastPassAfterBytes = Math.max(0L, usedBytes - freed);
            snapshotHits();
        }
        final double after = maxBytes <= 0L ? 0.0D : (double) Math.max(0L, usedBytes - freed) / (double) maxBytes;
        final String detail = scratch.toString();
        scratch.setLength(0);

        final Pass pass = new Pass(passCount, trigger, before, after, freed, visited, trimmed, nanos,
            budgetExhausted, detail);
        if (!dryRun) {
            lastPass = pass;
        }
        return pass;
    }

    /**
     * @return caches ordered by "how much does shedding this one hurt, per byte it returns": the big
     * cold ones first, the hot ones last, the expensive ones only in an aggressive pass
     */
    private List<Candidate> collect(final boolean aggressive) {
        final List<Candidate> out = new ArrayList<>(caches.size());
        for (final Trimmable cache : caches) {
            if (cache.size() <= 0) {
                continue;
            }
            if (cache.expensiveToRefill() && !aggressive) {
                continue;
            }
            final Candidate candidate = new Candidate();
            candidate.cache = cache;
            candidate.bytes = Math.max(cache.retainedBytes(), (long) cache.size() * 64L);
            candidate.recentHits = recentHits(cache);
            final double perEntry = candidate.recentHits / (double) Math.max(1, cache.size());
            final double coldness = 1.0D - Math.min(1.0D, perEntry);
            if (!aggressive && perEntry >= 1.0D) {
                // "second chance": at least one hit per entry since the last pass means this cache is
                // doing its job and is skipped unless the situation is serious.
                continue;
            }
            candidate.score = coldness * (double) candidate.bytes;
            out.add(candidate);
        }
        out.sort((left, right) -> {
            final int byScore = Double.compare(right.score, left.score);
            return byScore != 0 ? byScore : Long.compare(right.bytes, left.bytes);
        });
        return out;
    }

    private long recentHits(final Trimmable cache) {
        final Long previous = hitsAtLastPass.get(cache);
        final long hits = cache.hits();
        if (previous == null) {
            return hits;
        }
        return Math.max(0L, hits - previous);
    }

    private void snapshotHits() {
        for (final Trimmable cache : caches) {
            hitsAtLastPass.put(cache, cache.hits());
        }
    }

    private long rearmBytes(final long maxBytes) {
        return Math.max(policy.rearmBytesMin(), (long) (policy.rearmFraction() * (double) maxBytes));
    }

    private void appendDetail(final String id, final long released) {
        if (scratch.indexOf(id) >= 0) {
            return;
        }
        if (scratch.length() > 0) {
            scratch.append(", ");
        }
        if (scratch.length() < 72) {
            scratch.append(id).append(' ').append(released / (1024L * 1024L)).append("MiB");
        }
    }

    private static double clamp(final double value, final double min, final double max) {
        return value < min ? min : Math.min(value, max);
    }
}
