package org.dreeam.leaf.perf.offheap;

import it.unimi.dsi.fastutil.longs.LongList;

/**
 * Leaf - factory that decides whether a {@link LongList} is worth moving out of the Java heap.
 * <p>
 * The decision is intentionally conservative, because off-heap memory is <b>not</b> faster than the
 * heap for every access pattern:
 * <ul>
 *     <li>short lists stay on the heap - the pointer chase through a {@code LongBuffer} costs more
 *     than the GC ever saves on sixty-four bytes,</li>
 *     <li>large read-mostly tables move out of the heap - this is where the collector actually pays
 *     for a {@code long[]} that is live forever,</li>
 *     <li>if the allocation fails (direct memory is a separate, size limited pool) the caller simply
 *     keeps the heap instance.</li>
 * </ul>
 * Tunables (all optional):
 * {@code -Dleaf.offHeap=false}, {@code -Dleaf.offHeap.maxMB=64},
 * {@code -Dleaf.offHeap.minEntries=1024}.
 */
public final class OffHeapLongLists {

    private static final int MIN_ENTRIES = readIntProperty("leaf.offHeap.minEntries", 1024);

    private OffHeapLongLists() {
    }

    /**
     * @param heapList an immutable list that will be kept alive for a long time
     * @return either an off-heap view of the same data, or {@code heapList} itself
     */
    public static LongList offHeapIfWorthwhile(final LongList heapList) {
        if (heapList.size() < MIN_ENTRIES) {
            return heapList;
        }

        final OffHeapLongArray array = OffHeapLongArray.tryCopyOf(heapList.toLongArray());
        if (array == null) {
            return heapList;
        }
        return new OffHeapLongList(array);
    }

    private static int readIntProperty(final String key, final int fallback) {
        final String raw = System.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
