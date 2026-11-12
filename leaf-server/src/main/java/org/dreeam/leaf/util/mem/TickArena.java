package org.dreeam.leaf.util.mem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * Bump allocator over off-heap (direct) memory with Rust-style scoped cleanup.
 *
 * <p>Rust analog: {@code bumpalo::Bump} / arena allocation. Allocations are
 * pointer-bumps into pre-reserved chunks; a whole scope of allocations is
 * released at once by resetting the bump pointer — deterministic, O(1), and
 * completely invisible to the GC because the backing memory never lives on
 * the Java heap.</p>
 *
 * <p>Usage pattern (one arena per hot path, reset once per tick):</p>
 * <pre>{@code
 * try (TickArena.Scope ignored = arena.scope()) {
 *     OffHeapDoubleList scratch = new OffHeapDoubleList(arena, 64);
 *     ...
 * } // everything allocated inside is invalidated here, like a Rust lifetime ending
 * }</pre>
 *
 * <p>The arena retains its chunks across scopes, so after warmup (peak tick
 * usage reached) the steady-state allocation rate is zero: no GC pressure,
 * no malloc/free churn, memory bounded by the peak tick.</p>
 *
 * <p><b>Thread confinement:</b> like Rust's ownership rules, one arena must be
 * owned by exactly one thread at a time. Create one arena per worker thread
 * (e.g. per async pathfinding worker) instead of sharing.</p>
 *
 * <p><b>Safety contract (mirrors unsafe Rust):</b> buffers handed out by this
 * arena must not be used after their scope closes. The Java compiler cannot
 * enforce lifetimes, so keep arena slices out of long-lived fields.</p>
 */
public final class TickArena implements AutoCloseable {

    /** Default chunk size: 4 MiB. */
    public static final int DEFAULT_CHUNK_SIZE = 4 << 20;

    private static final int ALIGN = 8;

    private final int chunkSize;
    private final ArrayList<ByteBuffer> chunks = new ArrayList<>(4);

    /** Index of the chunk currently being bumped; -1 = nothing allocated yet. */
    private int current = -1;
    /** Bytes already bumped inside the current chunk. */
    private int used;
    private boolean closed;

    public TickArena() {
        this(DEFAULT_CHUNK_SIZE);
    }

    public TickArena(int chunkSize) {
        if (chunkSize < 64) {
            throw new IllegalArgumentException("chunkSize too small: " + chunkSize);
        }
        this.chunkSize = chunkSize;
    }

    private static int align(int bytes) {
        return (bytes + (ALIGN - 1)) & -ALIGN;
    }

    /**
     * Allocates {@code bytes} of off-heap memory. Returns a native-order
     * {@link ByteBuffer} view with position 0 and limit = requested size.
     * The view is valid until the matching {@link Scope} closes.
     */
    public ByteBuffer alloc(int bytes) {
        if (closed) {
            throw new IllegalStateException("arena is closed");
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("negative size");
        }
        int n = align(bytes);

        ByteBuffer chunk = ensureChunkWithRoom(n);
        ByteBuffer view = chunk.slice(used, n);
        view.order(ByteOrder.nativeOrder());
        used += n;
        return view;
    }

    private ByteBuffer ensureChunkWithRoom(int n) {
        ByteBuffer chunk = current >= 0 && current < chunks.size() ? chunks.get(current) : null;
        while (chunk == null || n > chunk.capacity() - used) {
            current++;
            used = 0;
            if (current == chunks.size()) {
                // Dedicated oversized chunk if a single allocation exceeds chunkSize.
                int cap = Math.max(chunkSize, n);
                ByteBuffer fresh = ByteBuffer.allocateDirect(cap);
                fresh.order(ByteOrder.nativeOrder());
                chunks.add(fresh);
            }
            chunk = chunks.get(current);
            // Loop again if a reused older chunk is still too small for a huge request.
        }
        return chunk;
    }

    /** Encodes the current bump position so a scope can roll back to it. */
    private long mark() {
        return ((long) current << 32) | (used & 0xffffffffL);
    }

    private void reset(long mark) {
        current = (int) (mark >>> 32);
        used = (int) mark;
    }

    /**
     * Opens a scope. Closing it frees every allocation made since this call,
     * in O(1). This is the Drop/RAII equivalent for the arena.
     */
    public Scope scope() {
        return new Scope(mark());
    }

    /**
     * Resets the whole arena (end-of-tick cleanup). Keeps peak capacity
     * reserved so the next tick allocates nothing.
     */
    public void reset() {
        current = 0;
        used = 0;
    }

    /**
     * Drops empty chunks beyond {@code keepChunks}, freeing reserved
     * capacity after an atypical spike — the Rust {@code shrink_to_fit} habit.
     */
    public void trim(int keepChunks) {
        while (chunks.size() > Math.max(1, keepChunks) && chunks.size() - 1 > current) {
            chunks.remove(chunks.size() - 1);
        }
    }

    public long capacityBytes() {
        long total = 0;
        for (ByteBuffer b : chunks) {
            total += b.capacity();
        }
        return total;
    }

    public long liveBytes() {
        long total = 0;
        for (int i = 0; i < current && i < chunks.size(); i++) {
            total += chunks.get(i).capacity();
        }
        return total + used;
    }

    public int chunkCount() {
        return chunks.size();
    }

    /**
     * Releases the backing direct buffers. After close() the JVM cleaner
     * reclaims the native memory promptly; the arena must not be used again.
     */
    @Override
    public void close() {
        closed = true;
        chunks.clear();
        current = 0;
        used = 0;
    }

    public final class Scope implements AutoCloseable {
        private final long mark;
        private boolean done;

        private Scope(long mark) {
            this.mark = mark;
        }

        @Override
        public void close() {
            if (!done) {
                done = true;
                reset(mark);
            }
        }
    }
}
