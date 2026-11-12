package org.dreeam.leaf.util.mem;

import java.nio.ByteBuffer;

/**
 * Growable {@code long} list living entirely off-heap inside a {@link TickArena}.
 *
 * <p>Rust analog: {@code Vec<u64>} allocated in a bump arena — destructured
 * packed positions (Minecraft packs BlockPos into one long), entity ids and
 * similar tick-scratch data never touch the Java heap, never box, and the
 * whole list dies with the arena scope instead of becoming GC garbage.</p>
 *
 * <p>Growth re-bumps a larger slice inside the arena; the abandoned slice is
 * reclaimed when the scope closes (classic arena trade-off, same as
 * {@code bumpalo::Vec}).</p>
 */
public final class OffHeapLongList {

    private final TickArena arena;
    private ByteBuffer buf;
    private int capacity; // in elements
    private int size;

    public OffHeapLongList(TickArena arena, int capacity) {
        this.arena = arena;
        this.capacity = Math.max(1, capacity);
        this.buf = arena.alloc(this.capacity * Long.BYTES);
    }

    public void add(long value) {
        if (size == capacity) {
            grow();
        }
        buf.putLong(size * Long.BYTES, value);
        size++;
    }

    /** Packs and stores two ints (e.g. x/z) in a single long. */
    public void addPacked(int high, int low) {
        add(((long) high << 32) | (low & 0xffffffffL));
    }

    public long getLong(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index + " / " + size);
        }
        return buf.getLong(index * Long.BYTES);
    }

    public long last() {
        if (size == 0) {
            throw new IndexOutOfBoundsException("empty");
        }
        return buf.getLong((size - 1) * Long.BYTES);
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Keeps the memory, forgets the contents — free cleanup. */
    public void clear() {
        size = 0;
    }

    private void grow() {
        int newCap = Math.max(capacity * 2, 8);
        ByteBuffer nb = arena.alloc(newCap * Long.BYTES);
        nb.put(0, buf, 0, size * Long.BYTES); // bulk copy, no per-element calls
        buf = nb;
        capacity = newCap;
    }
}
