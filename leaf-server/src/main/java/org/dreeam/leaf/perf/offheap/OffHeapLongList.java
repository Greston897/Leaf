package org.dreeam.leaf.perf.offheap;

import it.unimi.dsi.fastutil.longs.AbstractLongList;

/**
 * Leaf - a read-only {@link it.unimi.dsi.fastutil.longs.LongList} backed by
 * {@link OffHeapLongArray}.
 * <p>
 * Only {@code size()} and {@code getLong(int)} are implemented: fastutil's {@code AbstractLongList}
 * derives everything else (iteration, streams, sub-lists) from those two methods, so existing code
 * that was written against {@code LongList} keeps working unchanged - including the
 * {@code BlockPos.withinManhattan} redirect, which declares
 * {@code LongList HOGLIN_PIGLIN_CACHE = ITERATE_OUTWARDS_CACHE.getOrCompute(8, 4, 8)}.
 * <p>
 * Mutating operations are intentionally unsupported: these lists are snapshots of immutable
 * precomputed positions.
 */
public final class OffHeapLongList extends AbstractLongList {

    private final OffHeapLongArray data;

    public OffHeapLongList(final OffHeapLongArray data) {
        this.data = data;
    }

    @Override
    public int size() {
        return this.data.length();
    }

    @Override
    public long getLong(final int index) {
        if (index < 0 || index >= this.data.length()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + this.data.length());
        }
        return this.data.get(index);
    }

    /**
     * @return the heap size this list would have occupied, for diagnostics.
     */
    public long offHeapBytes() {
        return this.data.byteSize();
    }
}
