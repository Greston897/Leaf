package org.dreeam.leaf.perf.offheap;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leaf - an immutable {@code long[]} that lives outside the Java heap.
 * <p>
 * The array is backed by a direct {@link ByteBuffer}, so:
 * <ul>
 *     <li>the garbage collector never scans it (a 20 000 element table costs the GC nothing),</li>
 *     <li>it does not count towards {@code -Xmx}, which is exactly what makes a fixed, pre-touched
 *     heap (see {@code scripts/leaf-perf/leaf-flags.sh}) predictable,</li>
 *     <li>it is released by the {@link Cleaner} as soon as the array becomes unreachable - the same
 *     mechanism the JDK uses for direct buffers, no {@code sun.misc.Unsafe}, no
 *     {@code --enable-preview}, no JNI.</li>
 * </ul>
 * Reads go through {@link LongBuffer#get(int)}, which the JIT intrinsifies into a plain load
 * (ByteBuffer access is a documented HotSpot intrinsic). There is no per-access object allocation.
 * <p>
 * Deliberately <b>not</b> used for anything the server mutates per tick: off-heap memory is a win for
 * long lived, read-mostly data and a loss for hot mutable state (writes through a buffer are not
 * hoisted as well as array writes). See {@code docs/performance/OPTIMIZATION_RU.md}.
 * <p>
 * Everything is opt-out: {@code -Dleaf.offHeap=false} makes every {@code tryCopyOf} return
 * {@code null} and callers fall back to the heap.
 */
public final class OffHeapLongArray {

    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("leaf.offHeap", "true"));
    private static final long MAX_BUDGET_BYTES = readLongProperty("leaf.offHeap.maxMB", 64L) * 1024L * 1024L;

    private static final AtomicLong RESERVED_BYTES = new AtomicLong();
    private static final Cleaner CLEANER = Cleaner.create();

    private final LongBuffer view;
    private final int length;

    private OffHeapLongArray(final LongBuffer view, final int length) {
        this.view = view;
        this.length = length;
    }

    /**
     * Copies the given values into off-heap memory.
     *
     * @return the off-heap array, or {@code null} when off-heap storage is disabled, the request is
     * larger than the budget, or the allocation failed. <b>Callers must handle {@code null}.</b>
     */
    public static OffHeapLongArray tryCopyOf(final long[] values) {
        if (!ENABLED || values.length == 0) {
            return null;
        }

        final long bytes = (long) values.length << 3;
        if (MAX_BUDGET_BYTES > 0 && RESERVED_BYTES.get() + bytes > MAX_BUDGET_BYTES) {
            return null;
        }

        try {
            final LongBuffer view = ByteBuffer.allocateDirect((int) bytes)
                .order(ByteOrder.nativeOrder())
                .asLongBuffer();
            view.put(values);
            view.position(0);

            RESERVED_BYTES.addAndGet(bytes);
            final OffHeapLongArray array = new OffHeapLongArray(view.asReadOnlyBuffer(), values.length);
            // The release action must not capture the array itself, otherwise the array can never
            // become unreachable and the cleaner would never run.
            CLEANER.register(array, new Release(bytes));
            return array;
        } catch (OutOfMemoryError | RuntimeException failure) {
            // Direct memory is a separate pool; falling back to the heap must never be fatal.
            return null;
        }
    }

    public int length() {
        return this.length;
    }

    public long get(final int index) {
        return this.view.get(index);
    }

    public long byteSize() {
        return (long) this.length << 3;
    }

    /**
     * @return bytes currently reserved for off-heap long arrays, for {@code /leaf} style diagnostics.
     */
    public static long reservedBytes() {
        return RESERVED_BYTES.get();
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    private static long readLongProperty(final String key, final long fallback) {
        final String raw = System.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static final class Release implements Runnable {

        private final long bytes;

        private Release(final long bytes) {
            this.bytes = bytes;
        }

        @Override
        public void run() {
            RESERVED_BYTES.addAndGet(-this.bytes);
        }
    }
}
