package org.dreeam.leaf.util.mem;

import java.nio.ByteBuffer;

/**
 * Growable {@code double} list living entirely off-heap inside a {@link TickArena}.
 *
 * <p>Purpose: replace the tick-loop churn of {@code new Vec3(...)},
 * {@code new AABB(...)} and {@code ArrayList}-of-shapes that vanilla collision
 * and pathfinding code produces by the thousands per tick. A AABB is just six
 * consecutive doubles here — struct-of-flat-arrays style, like Rust code would
 * lay it out in a {@code bumpalo} arena.</p>
 */
public final class OffHeapDoubleList {

    private final TickArena arena;
    private ByteBuffer buf;
    private int capacity; // in elements
    private int size;

    public OffHeapDoubleList(TickArena arena, int capacity) {
        this.arena = arena;
        this.capacity = Math.max(1, capacity);
        this.buf = arena.alloc(this.capacity * Double.BYTES);
    }

    public void add(double value) {
        if (size == capacity) {
            grow();
        }
        buf.putDouble(size * Double.BYTES, value);
        size++;
    }

    /** Appends a vector triple (x, y, z). */
    public void addVec(double x, double y, double z) {
        ensure(3);
        int base = size * Double.BYTES;
        buf.putDouble(base, x);
        buf.putDouble(base + 8, y);
        buf.putDouble(base + 16, z);
        size += 3;
    }

    /** Appends a box sextuple (minX, minY, minZ, maxX, maxY, maxZ). */
    public void addBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        ensure(6);
        int base = size * Double.BYTES;
        buf.putDouble(base, minX);
        buf.putDouble(base + 8, minY);
        buf.putDouble(base + 16, minZ);
        buf.putDouble(base + 24, maxX);
        buf.putDouble(base + 32, maxY);
        buf.putDouble(base + 40, maxZ);
        size += 6;
    }

    public double getDouble(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index + " / " + size);
        }
        return buf.getDouble(index * Double.BYTES);
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        size = 0;
    }

    private void ensure(int extra) {
        while (size + extra > capacity) {
            grow();
        }
    }

    private void grow() {
        int newCap = Math.max(capacity * 2, 16);
        ByteBuffer nb = arena.alloc(newCap * Double.BYTES);
        nb.put(0, buf, 0, size * Double.BYTES);
        buf = nb;
        capacity = newCap;
    }
}
