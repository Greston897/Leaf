package org.dreeam.leaf.util.mem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Size-classed pool of direct {@link ByteBuffer}s.
 *
 * <p>Built for I/O paths such as {@code BufferedLinearRegionFile}, which today
 * does {@code ByteBuffer.allocate(data.remaining() + 16)} per chunk write —
 * a humongous on-heap allocation per save, one of the worst G1 pressure
 * sources when the server writes regions. Direct buffers from this pool cost
 * the GC nothing at all.</p>
 *
 * <p>Rust analog: a slab/buddy-style reuse of owned buffers; {@link #lease(int)}
 * is the RAII guard that returns the buffer on scope end.</p>
 */
public final class BufferPool {

    private static final int MIN_SHIFT = 12; // 4 KiB
    private static final int MAX_SHIFT = 24; // 16 MiB (largest sane compressed chunk)

    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedDeque<ByteBuffer>[] buckets =
        new ConcurrentLinkedDeque[MAX_SHIFT - MIN_SHIFT + 1];

    public BufferPool() {
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new ConcurrentLinkedDeque<>();
        }
    }

    private static int shiftFor(int minBytes) {
        if (minBytes <= 0) {
            return MIN_SHIFT;
        }
        int shift = 32 - Integer.numberOfLeadingZeros(minBytes - 1);
        if (shift > MAX_SHIFT) {
            throw new IllegalArgumentException("buffer too large for pool: " + minBytes);
        }
        return Math.max(shift, MIN_SHIFT);
    }

    /** Returns a cleared, native-order direct buffer with capacity &ge; {@code minBytes}. */
    public ByteBuffer acquire(int minBytes) {
        int shift = shiftFor(minBytes);
        ByteBuffer buf = buckets[shift - MIN_SHIFT].poll();
        if (buf == null) {
            buf = ByteBuffer.allocateDirect(1 << shift);
            buf.order(ByteOrder.nativeOrder());
        } else {
            buf.clear();
        }
        return buf;
    }

    /** Returns a buffer to the pool. Buffers allocated too large to pool are left to the cleaner. */
    public void release(ByteBuffer buf) {
        if (buf == null) {
            return;
        }
        int cap = buf.capacity();
        if ((cap & (cap - 1)) != 0) {
            return; // not one of ours
        }
        int shift = Integer.numberOfTrailingZeros(cap);
        if (shift < MIN_SHIFT || shift > MAX_SHIFT) {
            return;
        }
        buf.clear();
        buckets[shift - MIN_SHIFT].offer(buf);
    }

    /** RAII guard: the buffer goes back to the pool exactly once, at scope end. */
    public Lease lease(int minBytes) {
        return new Lease(acquire(minBytes));
    }

    public final class Lease implements AutoCloseable {
        private ByteBuffer buf;

        private Lease(ByteBuffer buf) {
            this.buf = buf;
        }

        public ByteBuffer buffer() {
            ByteBuffer b = buf;
            if (b == null) {
                throw new IllegalStateException("lease already released");
            }
            return b;
        }

        @Override
        public void close() {
            ByteBuffer b = buf;
            if (b != null) {
                buf = null;
                release(b);
            }
        }
    }
}
