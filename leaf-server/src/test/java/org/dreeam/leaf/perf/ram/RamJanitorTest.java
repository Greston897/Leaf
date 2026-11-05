package org.dreeam.leaf.perf.ram;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaf - the RAM janitor policy, driven with fake memory readings and a fake clock.
 * <p>
 * The whole point of keeping the policy free of Minecraft types is these tests: every rule that keeps
 * the janitor from being harmful - watermarks, the second chance for hot caches, the time budget, the
 * pause while a GC has just run, the pause while the server is late - is pinned here without a server.
 */
class RamJanitorTest {

    private static final long MIB = 1024L * 1024L;
    private static final long MAX = 1000L * MIB;

    private long now;
    private final LongSupplier clock = () -> now;

    private static final class FakeCache implements Trimmable {

        private final String id;
        private final boolean expensive;
        private long bytes;
        private int entries;
        private long hits;

        private int trimCalls;
        private double lastRatio;
        private boolean lastDryRun;
        private long nanosPerTrim;

        private FakeCache(final String id, final long bytes, final int entries, final boolean expensive) {
            this.id = id;
            this.bytes = bytes;
            this.entries = entries;
            this.expensive = expensive;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public long retainedBytes() {
            return bytes;
        }

        @Override
        public int size() {
            return entries;
        }

        @Override
        public long hits() {
            return hits;
        }

        @Override
        public long trim(final double ratio, final boolean dryRun) {
            trimCalls++;
            lastRatio = ratio;
            lastDryRun = dryRun;
            if (nanosPerTrim > 0L) {
                now += nanosPerTrim;
            }
            final long released = (long) (bytes * Math.min(1.0D, ratio));
            if (!dryRun) {
                bytes -= released;
                entries = Math.max(0, entries - Math.max(1, (int) Math.round(entries * ratio)));
            }
            return released;
        }

        @Override
        public boolean expensiveToRefill() {
            return expensive;
        }
    }

    private RamJanitor janitor(final RamJanitor.Policy policy) {
        return new RamJanitor(policy, clock);
    }

    @Test
    void doesNothingBelowTheSoftWatermark() {
        final RamJanitor janitor = janitor(RamJanitor.Policy.defaults());
        final FakeCache cache = new FakeCache("cold", 400L * MIB, 100, false);
        janitor.register(cache);

        assertNull(janitor.tick(1L, 500L * MIB, MAX, 5.0D, 0L));
        assertEquals(0, cache.trimCalls);
        assertNull(janitor.lastPass());
    }

    @Test
    void shedsOnlyWhatItNeeds() {
        final RamJanitor janitor = janitor(RamJanitor.Policy.defaults());
        final FakeCache cache = new FakeCache("cold", 400L * MIB, 100, false);
        janitor.register(cache);

        // 78 % used, soft target 70 % -> 80 MiB have to go, and only 20 % of that cache
        final RamJanitor.Pass pass = janitor.tick(1L, 780L * MIB, MAX, 5.0D, 0L);

        assertNotNull(pass);
        assertEquals(1, cache.trimCalls);
        assertEquals(0.2D, cache.lastRatio, 0.01D);
        assertEquals(0.70D, pass.afterFraction(), 0.01D);
        assertEquals(80L * MIB, pass.freedBytes(), MIB);
        assertEquals("soft", pass.trigger());
    }

    @Test
    void takesTheColdCacheAndGivesTheHotOneASecondChance() {
        final RamJanitor janitor = janitor(RamJanitor.Policy.defaults());
        final FakeCache hot = new FakeCache("hot", 200L * MIB, 10, false);
        final FakeCache cold = new FakeCache("cold", 400L * MIB, 10, false);
        hot.hits = 500L; // 50 hits per entry since the last pass - this cache is earning its keep
        janitor.register(hot);
        janitor.register(cold);

        final RamJanitor.Pass pass = janitor.tick(1L, 800L * MIB, MAX, 5.0D, 0L);

        assertNotNull(pass);
        assertEquals(0, hot.trimCalls, "a cache with at least one hit per entry must be spared");
        assertEquals(1, cold.trimCalls);
        assertTrue(pass.freedBytes() > 0L);
    }

    @Test
    void stopsWhenTheTimeBudgetIsSpent() {
        final RamJanitor janitor = janitor(RamJanitor.Policy.defaults());
        for (int i = 0; i < 4; i++) {
            final FakeCache cache = new FakeCache("cache-" + i, 100L * MIB, 10, false);
            cache.nanosPerTrim = 5_000_000L; // 5 ms per trim, budget is 2 ms
            janitor.register(cache);
        }

        final RamJanitor.Pass pass = janitor.tick(1L, 900L * MIB, MAX, 5.0D, 0L);

        assertNotNull(pass);
        assertEquals(1, pass.visitedCaches());
        assertTrue(pass.budgetExhausted());
        assertTrue(pass.nanos() > 0L);
    }

    @Test
    void doesNotFightTheCollector() {
        final RamJanitor janitor = janitor(RamJanitor.Policy.defaults());
        final FakeCache cache = new FakeCache("cold", 400L * MIB, 100, false);
        janitor.register(cache);

        // below the watermark: only the GC counter is recorded
        assertNull(janitor.tick(1L, 500L * MIB, MAX, 5.0D, 5L));
        // over the watermark, but a collection happened since: the collector did the work already
        assertNull(janitor.tick(2L, 800L * MIB, MAX, 5.0D, 6L));
        assertEquals(0, cache.trimCalls);
        // no further collection: now a pass is due
        assertNotNull(janitor.tick(3L, 800L * MIB, MAX, 5.0D, 6L));
        assertEquals(1, cache.trimCalls);
    }

    @Test
    void postponesWhileTheServerIsLate() {
        final RamJanitor janitor = janitor(RamJanitor.Policy.defaults());
        final FakeCache cache = new FakeCache("cold", 400L * MIB, 100, false);
        janitor.register(cache);

        assertNull(janitor.tick(1L, 800L * MIB, MAX, 60.0D, 0L));
        assertEquals(0, cache.trimCalls);
    }

    @Test
    void reArmsOnlyAfterTheHeapGrewOrTheCooldownExpired() {
        final RamJanitor janitor = janitor(RamJanitor.Policy.defaults());
        final FakeCache cache = new FakeCache("cold", 900L * MIB, 100, false);
        janitor.register(cache);

        assertNotNull(janitor.tick(1L, 800L * MIB, MAX, 5.0D, 0L));
        final int callsAfterFirstPass = cache.trimCalls;

        now += 30_000_000_000L; // the cooldown is over
        // the pass left the heap at 70 %; 5 MiB above that is not a reason to trim again
        assertNull(janitor.tick(2L, 705L * MIB, MAX, 5.0D, 0L));
        assertEquals(callsAfterFirstPass, cache.trimCalls);

        // 200 MiB above the post-pass heap is
        assertNotNull(janitor.tick(3L, 900L * MIB, MAX, 5.0D, 0L));
        assertTrue(cache.trimCalls > callsAfterFirstPass);
    }

    @Test
    void emergencyIgnoresCooldownAndTakesExpensiveCaches() {
        final RamJanitor janitor = janitor(RamJanitor.Policy.defaults());
        final FakeCache cheap = new FakeCache("cheap", 200L * MIB, 100, false);
        final FakeCache expensive = new FakeCache("expensive", 200L * MIB, 100, true);
        janitor.register(cheap);
        janitor.register(expensive);

        assertNotNull(janitor.tick(1L, 800L * MIB, MAX, 5.0D, 0L));
        assertEquals(0, expensive.trimCalls, "below the hard watermark an expensive cache is left alone");

        final RamJanitor.Pass emergency = janitor.tick(2L, 960L * MIB, MAX, 5.0D, 0L);
        assertNotNull(emergency, "the cooldown must not block an emergency pass");
        assertEquals("pressure", emergency.trigger());
        assertTrue(expensive.trimCalls > 0);
    }

    @Test
    void dryRunReportsWithoutTouchingAnything() {
        final RamJanitor janitor = janitor(RamJanitor.Policy.defaults());
        final FakeCache cache = new FakeCache("cold", 400L * MIB, 100, false);
        janitor.register(cache);

        final RamJanitor.Pass pass = janitor.force(780L * MIB, MAX, true);

        assertEquals("dry-run", pass.trigger());
        assertTrue(cache.trimCalls > 0);
        assertTrue(cache.lastDryRun);
        assertEquals(400L * MIB, cache.retainedBytes(), "a dry run must not shrink the cache");
        assertNull(janitor.lastPass(), "a dry run is not a pass of the janitor");
        assertEquals(0, janitor.passCount());
    }

    @Test
    void forcedPassActuallyTrims() {
        final RamJanitor janitor = janitor(RamJanitor.Policy.defaults());
        final FakeCache cache = new FakeCache("cold", 400L * MIB, 100, false);
        janitor.register(cache);

        final RamJanitor.Pass pass = janitor.force(780L * MIB, MAX, false);

        assertEquals("manual", pass.trigger());
        assertTrue(pass.freedBytes() > 0L);
        assertTrue(cache.retainedBytes() < 400L * MIB);
        assertNotNull(janitor.lastPass());
        assertEquals(1, janitor.passCount());
        assertFalse(pass.budgetExhausted());
    }

    @Test
    void emptyOrBrokenCachesAreIgnored() {
        final RamJanitor janitor = janitor(RamJanitor.Policy.defaults());
        final FakeCache empty = new FakeCache("empty", 0L, 0, false);
        janitor.register(empty);
        janitor.register(new Trimmable() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public long retainedBytes() {
                return 100L * MIB;
            }

            @Override
            public int size() {
                return 10;
            }

            @Override
            public long hits() {
                return 0L;
            }

            @Override
            public long trim(final double ratio, final boolean dryRun) {
                throw new IllegalStateException("boom");
            }
        });

        final RamJanitor.Pass pass = janitor.force(900L * MIB, MAX, false);
        assertNotNull(pass, "a throwing cache must not abort the pass");
        assertEquals(0, pass.trimmedCaches());
        assertEquals(0, empty.trimCalls);
    }

    @Test
    void policyRejectsInconsistentWatermarks() {
        assertThrows(IllegalArgumentException.class,
            () -> new RamJanitor.Policy(0.8D, 0.7D, 0.9D, 45.0D, 1L, 1L, 1, 1L, 0.01D));
        assertThrows(IllegalArgumentException.class,
            () -> new RamJanitor.Policy(0.7D, 0.9D, 1.1D, 45.0D, 1L, 1L, 1, 1L, 0.01D));
        assertNotNull(RamJanitor.Policy.defaults());
    }
}
