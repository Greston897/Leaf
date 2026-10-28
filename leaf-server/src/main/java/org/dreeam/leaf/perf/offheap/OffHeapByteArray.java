package org.dreeam.leaf.perf.offheap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Leaf - an immutable {@code byte[]} in native memory, with a {@link ByteBuffer} view that can be
 * handed to Netty without a copy.
 * <p>
 * This is the building block for the off-heap chunk payload cache: instead of keeping a serialised
 * chunk as a heap {@code byte[]} - which the collector has to scan, and which Netty then copies into a
 * direct buffer before it can reach the socket - the payload already <b>is</b> a direct buffer.
 * {@code Unpooled.wrappedBuffer(view)} wraps it, so the write path performs no copy at all.
 * <p>
 * The memory is freed by the JVM when the buffer becomes unreachable (same mechanism as any other
 * direct buffer); the cache owns the budget accounting.
 */
public final class OffHeapByteArray {

    private final ByteBuffer buffer;
    private final int length;

    private OffHeapByteArray(final ByteBuffer buffer, final int length) {
        this.buffer = buffer;
        this.length = length;
    }

    /**
     * Copies the payload into native memory.
     *
     * @throws OutOfMemoryError if the direct memory pool is exhausted (the cache catches it)
     */
    public static OffHeapByteArray copyOf(final byte[] payload) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(payload.length).order(ByteOrder.nativeOrder());
        buffer.put(payload);
        buffer.flip();
        return new OffHeapByteArray(buffer.asReadOnlyBuffer(), payload.length);
    }

    public int length() {
        return this.length;
    }

    public long byteSize() {
        return this.length;
    }

    /**
     * @return a fresh read-only view with {@code position == 0}. Every caller gets its own position
     * state, so the same cached payload can be written to many sockets concurrently.
     */
    public ByteBuffer view() {
        final ByteBuffer view = this.buffer.duplicate();
        view.position(0);
        view.limit(this.length);
        return view;
    }

    /**
     * @return a heap copy - only for code paths that cannot accept a {@link ByteBuffer}
     */
    public byte[] toByteArray() {
        final byte[] copy = new byte[this.length];
        final ByteBuffer view = this.view();
        view.get(copy);
        return copy;
    }
}
