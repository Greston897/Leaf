package org.dreeam.leaf.util.mem;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bounded object pool with Rust-style deterministic release.
 *
 * <p>Objects that <i>must</i> stay ordinary Java objects (packets, pathfinding
 * nodes, anything crossing an API boundary) still churn the GC when created
 * per-tick. This pool gives them Rust-like ownership semantics instead:</p>
 * <ul>
 *   <li>{@link #lease()} borrows an object. Dropping the lease (try-with-resources)
 *       deterministically returns it — the {@code Drop} trait, enforced by
 *       {@code AutoCloseable}.</li>
 *   <li>A recycler callback runs on release, like Rust's drop glue, to detach
 *       the object from its previous owner before reuse.</li>
 *   <li>The pool is bounded: excess objects are left to the GC, so a leak in
 *       one component cannot grow memory without limit.</li>
 * </ul>
 *
 * <p>Thread-safe (the tracker and pathfinding paths in Leaf hand objects
 * between the main thread and worker threads).</p>
 */
public final class RustyPool<T> {

    private final Supplier<T> factory;
    private final Consumer<T> recycler;
    private final int maxSize;
    private final ConcurrentLinkedDeque<T> free = new ConcurrentLinkedDeque<>();
    private final AtomicInteger freeCount = new AtomicInteger();

    /**
     * @param factory  creates a fresh instance when the pool is empty
     * @param recycler "drop glue": clears/reset the instance on release
     *                 (may be {@code null} for stateless objects)
     * @param maxSize  pool bound; releases beyond this are dropped for the GC
     */
    public RustyPool(Supplier<T> factory, Consumer<T> recycler, int maxSize) {
        this.factory = factory;
        this.recycler = recycler;
        this.maxSize = Math.max(0, maxSize);
    }

    public T acquire() {
        T value = free.poll();
        if (value != null) {
            freeCount.decrementAndGet();
            return value;
        }
        return factory.get();
    }

    public void release(T value) {
        if (value == null) {
            return;
        }
        if (recycler != null) {
            recycler.accept(value);
        }
        if (freeCount.get() < maxSize) {
            free.offer(value);
            freeCount.incrementAndGet();
        }
        // else: drop the reference — GC reclaims it, pool stays bounded
    }

    /**
     * Borrows an object with RAII semantics:
     * <pre>{@code
     * try (RustyPool<Path>.Lease l = pathPool.lease()) {
     *     Path p = l.value();
     *     ...
     * } // p deterministically returns to the pool here
     * }</pre>
     */
    public Lease lease() {
        return new Lease(acquire());
    }

    public int pooled() {
        return freeCount.get();
    }

    public final class Lease implements AutoCloseable {
        private T value;

        private Lease(T value) {
            this.value = value;
        }

        public T value() {
            T v = value;
            if (v == null) {
                throw new IllegalStateException("lease already released (use-after-drop)");
            }
            return v;
        }

        /** Extra use-after-drop tripwire: reading after close() blows up instead of corrupting shared state. */
        @Override
        public void close() {
            T v = value;
            if (v != null) {
                value = null;
                release(v);
            }
        }
    }
}
