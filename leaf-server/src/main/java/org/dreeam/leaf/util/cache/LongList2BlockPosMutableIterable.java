package org.dreeam.leaf.util.cache;

import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * The iterable returned by the {@code BlockPos.withinManhattan} redirect
 * ({@code 0098-Lithium-cached-iterate-outwards.patch}).
 * <p>
 * Leaf: iteration is index based instead of going through {@link it.unimi.dsi.fastutil.longs.LongIterator}.
 * The position table is immutable, so an index is all the state we need, and this keeps working
 * unchanged for both the heap backed {@code LongArrayList} and the off-heap
 * {@code OffHeapLongList} (see {@link IterateOutwardsCache}).
 * <p>
 * The returned {@link BlockPos} is still a single mutable instance per iteration, exactly as before:
 * callers that need to keep a position already copy it, and callers that do not copy would have been
 * broken by the previous implementation in the same way.
 *
 * @author 2No2Name
 */
public class LongList2BlockPosMutableIterable implements Iterable<BlockPos> {

    private final LongList positions;
    private final int xOffset, yOffset, zOffset;

    public LongList2BlockPosMutableIterable(BlockPos offset, LongList posList) {
        this.xOffset = offset.getX();
        this.yOffset = offset.getY();
        this.zOffset = offset.getZ();
        this.positions = posList;
    }

    @Override
    public Iterator<BlockPos> iterator() {
        final LongList positions = this.positions;
        final int size = positions.size();
        final int xOffset = this.xOffset;
        final int yOffset = this.yOffset;
        final int zOffset = this.zOffset;

        return new Iterator<>() {

            private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            private int index;

            @Override
            public boolean hasNext() {
                return this.index < size;
            }

            @Override
            public BlockPos next() {
                if (this.index >= size) {
                    throw new NoSuchElementException();
                }
                final long nextPos = positions.getLong(this.index++);
                return this.pos.set(
                    xOffset + BlockPos.getX(nextPos),
                    yOffset + BlockPos.getY(nextPos),
                    zOffset + BlockPos.getZ(nextPos));
            }
        };
    }
}
