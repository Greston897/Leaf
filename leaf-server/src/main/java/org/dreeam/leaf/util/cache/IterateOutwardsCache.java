package org.dreeam.leaf.util.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import org.dreeam.leaf.perf.offheap.OffHeapLongLists;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Cache of "all block positions within a Manhattan distance", used by the lithium style
 * {@code BlockPos.withinManhattan} redirect (see {@code 0098-Lithium-cached-iterate-outwards.patch}).
 * <p>
 * The original implementation used {@code ConcurrentHashMap<Long, LongArrayList>}. That map is hit
 * from the random-tick / mob-spawn hot paths on every request and it boxes the key twice per lookup
 * (once to call {@code get}, once more when the {@code Long} is not in the {@code Long} cache, i.e.
 * for every coordinate triple outside -128..127). It also allocates a {@code Long} for every
 * {@code entrySet()}/{@code keySet()} traversal during eviction.
 * <p>
 * This version uses fastutil's {@code Long2ObjectOpenHashMap} with <b>copy-on-write</b> publication:
 * <ul>
 *     <li>reads are lock free, allocation free and box free - the map is never mutated after it has
 *     been published through the volatile field,</li>
 *     <li>writes (cache misses only) build a small copy under a monitor and swap it in.</li>
 * </ul>
 * The cache holds a few dozen entries at most, so the copy is a handful of array copies that happen
 * once per distinct (xRange, yRange, zRange) triple - not once per call.
 * <p>
 * The position tables themselves are immutable snapshots that live for the whole uptime of the
 * server, so they are <b>moved off the Java heap</b> ({@link OffHeapLongLists}): the collector stops
 * scanning them and they do not count towards {@code -Xmx}. Small tables stay on the heap on
 * purpose - see the comment in {@code OffHeapLongLists}.
 *
 * @author 2No2Name, original implementation by SuperCoder7979 and Gegy1000
 */
public class IterateOutwardsCache {

    //POS_ZERO must not be replaced with BlockPos.ORIGIN, otherwise iterateOutwards at BlockPos.ORIGIN will not use the cache
    public static final BlockPos POS_ZERO = new BlockPos(0, 0, 0);

    /**
     * Immutable-after-publication map. Never mutated in place: writers copy, fill and then publish.
     */
    private volatile Long2ObjectOpenHashMap<LongList> table;
    private final int capacity;

    public IterateOutwardsCache(final int capacity) {
        this.capacity = Math.max(8, capacity);
        this.table = new Long2ObjectOpenHashMap<>(31);
    }

    private static LongList fillPositionsWithIterateOutwards(final int xRange, final int yRange, final int zRange) {
        // Add all positions to the cached list
        final LongArrayList entry = new LongArrayList(128);
        for (final BlockPos pos : BlockPos.withinManhattan(POS_ZERO, xRange, yRange, zRange)) {
            entry.add(pos.asLong());
        }
        //decrease the array size, as of now it won't be modified anymore anyways
        entry.trim();
        // Leaf - keep long lived, read only position tables outside the Java heap
        return OffHeapLongLists.offHeapIfWorthwhile(entry);
    }

    public LongList getOrCompute(final int xRange, final int yRange, final int zRange) {
        final long key = BlockPos.asLong(xRange, yRange, zRange);

        // Fast path: plain array lookup on an immutable snapshot, no boxing, no locking.
        final LongList cached = this.table.get(key);
        if (cached != null) {
            return cached;
        }

        final LongList computed = fillPositionsWithIterateOutwards(xRange, yRange, zRange);

        synchronized (this) {
            final Long2ObjectOpenHashMap<LongList> current = this.table;
            // Another thread may have computed the very same entry while we were building ours.
            // Both lists hold exactly the same positions, so reusing theirs is safe.
            final LongList existing = current.get(key);
            if (existing != null) {
                return existing;
            }

            final Long2ObjectOpenHashMap<LongList> copy =
                new Long2ObjectOpenHashMap<>(Math.max(16, current.size() + (current.size() >> 1) + 2));
            copy.putAll(current);
            copy.put(key, computed);

            if (copy.size() > this.capacity) {
                //prevent a memory leak by randomly removing about 1/8th of the elements when the desired capacity is exceeded
                evictSome(copy, key);
            }

            // Publish the new snapshot. Readers see either the old or the new map, both immutable.
            this.table = copy;
            return computed;
        }
    }

    /**
     * @return number of cached radius triples, for {@code /leaf} style diagnostics
     */
    public int size() {
        return this.table.size();
    }

    private void evictSome(final Long2ObjectOpenHashMap<LongList> map, final long keepKey) {
        // ThreadLocalRandom is cheaper than a shared Random instance and needs no synchronisation.
        // Quality does not matter here, we just want to thin out the cache.
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final LongArrayList toRemove = new LongArrayList(8);
        final LongIterator iterator = map.keySet().iterator();
        // The bound prevents an unlikely infinite loop caused by concurrent fills.
        for (int i = -this.capacity; iterator.hasNext() && i < 5; i++) {
            final long candidate = iterator.nextLong();
            if (random.nextInt(8) == 0 && candidate != keepKey) {
                toRemove.add(candidate);
            }
        }
        for (int i = 0, size = toRemove.size(); i < size; i++) {
            map.remove(toRemove.getLong(i));
        }
    }
}
