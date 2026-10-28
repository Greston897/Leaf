package org.dreeam.leaf.util.cache;

import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import org.dreeam.leaf.perf.offheap.OffHeapLongList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaf - unit tests for {@link IterateOutwardsCache} and the iterable it feeds.
 */
class IterateOutwardsCacheTest {

    /**
     * Reference model: a line by line copy of {@code BlockPos#withinManhattan}'s iterator, written with
     * plain ints.
     * <p>
     * The real {@code BlockPos.withinManhattan} is not usable as the reference here: the lithium patch
     * (0098) makes {@code BlockPos} initialise this cache from its own static initialiser, and a
     * reference walk that runs while {@code BlockPos} is still initialising produces positions with a
     * garbage {@code z} offset. Keeping the model free of Minecraft types also means these tests run in
     * a plain JVM, which is exactly the property the production code now has.
     */
    private static List<long[]> vanillaWalk(final int xSize, final int ySize, final int zSize) {
        final List<long[]> out = new ArrayList<>();
        final int maxDepth = xSize + ySize + zSize;
        int currentDepth = 0;
        int maxX = 0;
        int x = 0;
        int maxY = 0;
        int y = 0;
        boolean mirrorPending = false;
        int mirrorX = 0;
        int mirrorY = 0;
        int mirrorZ = 0;

        while (true) {
            if (mirrorPending) {
                // the vanilla iterator hands out the same cursor again, with only z mirrored
                mirrorPending = false;
                out.add(new long[]{mirrorX, mirrorY, mirrorZ});
                continue;
            }

            boolean accepted = false;
            while (!accepted) {
                if (y > maxY) {
                    x++;
                    if (x > maxX) {
                        currentDepth++;
                        if (currentDepth > maxDepth) {
                            return out;
                        }
                        maxX = Math.min(xSize, currentDepth);
                        x = -maxX;
                    }
                    maxY = Math.min(ySize, currentDepth - Math.abs(x));
                    y = -maxY;
                }

                final int z = currentDepth - Math.abs(x) - Math.abs(y);
                if (z <= zSize) {
                    out.add(new long[]{x, y, z});
                    if (z != 0) {
                        mirrorPending = true;
                        mirrorX = x;
                        mirrorY = y;
                        mirrorZ = -z;
                    }
                    accepted = true;
                }
                y++;
            }
        }
    }

    private static Set<Long> expected(final int x, final int y, final int z) {
        final Set<Long> out = new HashSet<>();
        for (final long[] pos : vanillaWalk(x, y, z)) {
            out.add(IterateOutwardsCache.asLong((int) pos[0], (int) pos[1], (int) pos[2]));
        }
        return out;
    }

    private static List<Long> modelOrder(final int x, final int y, final int z) {
        final List<Long> out = new ArrayList<>();
        for (final long[] pos : vanillaWalk(x, y, z)) {
            out.add(IterateOutwardsCache.asLong((int) pos[0], (int) pos[1], (int) pos[2]));
        }
        return out;
    }

    /**
     * The exact order, not just the content: the cache feeds {@code BlockPos.withinManhattan}, and the
     * order in which that iterator hands out positions is observable behaviour (mob spawning picks the
     * first free position, random ticking walks the list front to back).
     */
    private static List<Long> cachedOrder(final LongList list) {
        final List<Long> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getLong(i));
        }
        return out;
    }

    private static Set<Long> content(final LongList list) {
        final Set<Long> out = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getLong(i));
        }
        return out;
    }

    /**
     * The cached tables are unpacked with {@code BlockPos.getX(long)}/{@code getY}/{@code getZ}, so the
     * packing has to be bit for bit the one {@code BlockPos.asLong} uses. This test exists because a
     * hand written version of that packing first shipped with y at bit 26 (the layout of older
     * versions) instead of in the low 12 bits, and every cached position was off by a mile.
     */
    @Test
    void packingMatchesBlockPos() {
        for (int x = -60; x <= 60; x += 7) {
            for (int y = -60; y <= 60; y += 13) {
                for (int z = -60; z <= 60; z += 11) {
                    final long expected = BlockPos.asLong(x, y, z);
                    final long actual = IterateOutwardsCache.asLong(x, y, z);
                    assertEquals(expected, actual, "packing differs for " + x + "/" + y + "/" + z);
                    assertEquals(x, BlockPos.getX(actual), "unpacked x for " + x + "/" + y + "/" + z);
                    assertEquals(y, BlockPos.getY(actual), "unpacked y for " + x + "/" + y + "/" + z);
                    assertEquals(z, BlockPos.getZ(actual), "unpacked z for " + x + "/" + y + "/" + z);
                }
            }
        }
    }

    @Test
    void tableOrderMatchesTheVanillaIterator() {
        final IterateOutwardsCache cache = new IterateOutwardsCache(50);

        for (final int[] radius : new int[][]{{1, 1, 1}, {3, 3, 3}, {8, 4, 8}, {5, 2, 7}, {0, 0, 0}}) {
            assertEquals(modelOrder(radius[0], radius[1], radius[2]),
                cachedOrder(cache.getOrCompute(radius[0], radius[1], radius[2])),
                "position order differs for " + radius[0] + "x" + radius[1] + "x" + radius[2]);
        }
    }

    @Test
    void smallTableStaysOnTheHeap() {
        final IterateOutwardsCache cache = new IterateOutwardsCache(50);
        final LongList small = cache.getOrCompute(3, 3, 3);

        assertEquals(expected(3, 3, 3), content(small));
        assertFalse(small instanceof OffHeapLongList, "a 343 element table is not worth moving off heap");
        assertSame(small, cache.getOrCompute(3, 3, 3), "cache hit must return the very same instance");
    }

    @Test
    void largeTableMovesOffHeap() {
        final IterateOutwardsCache cache = new IterateOutwardsCache(50);
        final LongList large = cache.getOrCompute(16, 16, 16);

        assertTrue(large instanceof OffHeapLongList, "an ~6k element table should move off heap");
        assertEquals(expected(16, 16, 16), content(large));
        assertEquals(large.size() * 8L, ((OffHeapLongList) large).offHeapBytes());
    }

    @Test
    void iterableMatchesTheCachedTable() {
        final IterateOutwardsCache cache = new IterateOutwardsCache(50);
        final LongList small = cache.getOrCompute(3, 3, 3);

        final List<BlockPos> visited = new ArrayList<>();
        for (final BlockPos pos : new LongList2BlockPosMutableIterable(IterateOutwardsCache.POS_ZERO, small)) {
            // the iterator hands out a mutable cursor, exactly like before
            visited.add(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
        }

        assertEquals(small.size(), visited.size());
        final Set<Long> iterated = new HashSet<>();
        for (final BlockPos pos : visited) {
            iterated.add(pos.asLong());
        }
        assertEquals(expected(3, 3, 3), iterated);
    }

    @Test
    void cacheStaysBoundedUnderLoad() {
        final IterateOutwardsCache cache = new IterateOutwardsCache(50);
        final LongList small = cache.getOrCompute(3, 3, 3);

        for (int range = 1; range <= 40; range++) {
            cache.getOrCompute(range, range, range);
        }

        assertTrue(cache.size() <= 200, "eviction must keep the cache bounded, was " + cache.size());
        assertNotNull(cache.getOrCompute(3, 3, 3), "a radius must remain resolvable after eviction");
        assertEquals(small.size(), cache.getOrCompute(3, 3, 3).size());
    }

    @Test
    void concurrentAccessIsConsistent() throws Exception {
        final IterateOutwardsCache cache = new IterateOutwardsCache(50);
        final AtomicInteger mismatches = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        final Thread[] threads = new Thread[8];

        for (int t = 0; t < threads.length; t++) {
            final int id = t;
            threads[t] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < 100; i++) {
                    final int range = 1 + ((id * 7 + i) % 24);
                    final LongList list = cache.getOrCompute(range, range, range);
                    final Set<Long> model = expected(range, range, range);
                    if (list.size() != model.size()) {
                        mismatches.incrementAndGet();
                        continue;
                    }
                    for (int j = 0; j < list.size(); j++) {
                        if (!model.contains(list.getLong(j))) {
                            mismatches.incrementAndGet();
                            break;
                        }
                    }
                }
            }, "leaf-cache-test-" + t);
            threads[t].start();
        }

        start.countDown();
        for (final Thread thread : threads) {
            thread.join();
        }

        assertEquals(0, mismatches.get(), "concurrent readers saw a corrupt table");
    }
}
