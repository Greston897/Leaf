package org.dreeam.leaf.perf.offheap;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Leaf - off-heap cache of serialised chunk payloads.
 * <p>
 * <b>Why a cache and not off-heap chunks.</b> Moving {@code LevelChunk}/{@code LevelChunkSection}
 * itself out of the Java heap is not possible without breaking every plugin, every anti-xray module
 * and every NMS consumer: these objects are part of the public surface of the server. What <i>can</i>
 * leave the heap is the heavy, read-only artefact the server builds from a chunk on the hot path - the
 * serialised block/heightmap/block-entity payload, tens of kilobytes per chunk, rebuilt per player per
 * join. Caching it:
 * <ul>
 *     <li>removes the serialisation work from the tick and from the join path (a join stops paying
 *     {@code 400 chunks x encode 16 sections}),</li>
 *     <li>removes the heap allocation of that payload entirely - native memory, invisible to the
 *     collector,</li>
 *     <li>lets Netty wrap the direct buffer directly instead of copying a heap {@code byte[]},</li>
 *     <li>keeps {@code LevelChunk} untouched, so plugins see exactly what they saw before.</li>
 * </ul>
 * <b>Correctness contract.</b> The key must contain everything that changes the payload - dimension,
 * chunk coordinates, a chunk content revision and the anti-xray mode - and a chunk's revision must be
 * bumped on every block/block-entity change (see
 * {@code docs/performance/PATCH_PROPOSALS_RU.md}, item 1, for the exact hook). A cache that is fed by
 * a mutable chunk without a revision will serve stale chunks: that is why this class is never wired
 * up automatically and why it is disabled unless {@code -Dleaf.offHeap.chunkCacheMB} is set.
 * <p>
 * The cache is a bounded, approximate LRU: when the budget is exceeded it drops entries in hash order
 * until it is back under 75 % of the budget. That is enough for a workload where a chunk payload is
 * either re-sent within a few seconds (a player moving, a group of players entering the same area) or
 * never.
 */
public final class OffHeapChunkPayloadCache {

    private static final long BUDGET_BYTES = readLongProperty("leaf.offHeap.chunkCacheMB", 0L) * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES = readLongProperty("leaf.offHeap.chunkCacheMaxEntryKB", 512L) * 1024L;

    private static final OffHeapChunkPayloadCache INSTANCE = new OffHeapChunkPayloadCache();

    private final Long2ObjectOpenHashMap<OffHeapByteArray> entries = new Long2ObjectOpenHashMap<>(256);
    private final long budgetBytes;
    private long usedBytes;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    private OffHeapChunkPayloadCache() {
        this(BUDGET_BYTES);
    }

    /**
     * Visible for tests: an instance with an explicit budget instead of the system property.
     */
    OffHeapChunkPayloadCache(final long budgetBytes) {
        this.budgetBytes = budgetBytes;
    }

    public static OffHeapChunkPayloadCache instance() {
        return INSTANCE;
    }

    /**
     * Builds the cache key for a chunk payload.
     * <p>
     * Everything that can change the serialised bytes has to be part of the key: the dimension (the
     * same coordinates exist in every world), the chunk coordinates, the chunk's content revision
     * (bumped by {@code LevelChunk} on every block / block entity change) and the anti-xray mode
     * (the same chunk is serialised differently for a player with anti-xray enabled).
     * <p>
     * Kept free of Minecraft types on purpose, so it is unit tested.
     */
    public static long key(final int dimensionHash, final int chunkX, final int chunkZ,
                           final int revision, final boolean antiXray) {
        long hash = 0x9E3779B97F4A7C15L; // FxHash style seed, mixed per field below
        hash = mix(hash ^ dimensionHash);
        hash = mix(hash ^ chunkX);
        hash = mix(hash ^ chunkZ);
        hash = mix(hash ^ revision);
        hash = mix(hash ^ (antiXray ? 1L : 0L));
        return hash;
    }

    private static long mix(final long value) {
        // 64 bit finaliser (MurmurHash3 fmix64): cheap, and good enough to keep chunk keys in
        // different cache slots instead of walking one collision list.
        long hash = value;
        hash ^= hash >>> 33;
        hash *= 0xFF51AFD7ED558CCDL;
        hash ^= hash >>> 33;
        hash *= 0xC4CEB9FE1A85EC53L;
        hash ^= hash >>> 33;
        return hash;
    }

    public static boolean enabled() {
        return BUDGET_BYTES > 0;
    }

    /**
     * @return the cached payload, or {@code null} on a miss. The returned array is immutable and can
     * be read concurrently by any thread.
     */
    public synchronized OffHeapByteArray get(final long key) {
        final OffHeapByteArray cached = this.entries.get(key);
        if (cached != null) {
            this.hits.incrementAndGet();
            return cached;
        }
        this.misses.incrementAndGet();
        return null;
    }

    /**
     * Stores a payload. Never throws: a failed allocation (direct memory is a separate, capped pool)
     * simply results in a cache miss next time.
     */
    public synchronized void put(final long key, final byte[] payload) {
        if (this.budgetBytes <= 0L || payload == null || payload.length == 0 || payload.length > MAX_ENTRY_BYTES) {
            return;
        }

        final OffHeapByteArray previous = this.entries.get(key);
        if (previous != null) {
            this.usedBytes -= previous.byteSize();
        }

        try {
            final OffHeapByteArray copy = OffHeapByteArray.copyOf(payload);
            this.entries.put(key, copy);
            this.usedBytes += copy.byteSize();
        } catch (OutOfMemoryError | RuntimeException allocationFailure) {
            this.entries.remove(key);
            return;
        }

        if (this.usedBytes > this.budgetBytes) {
            evictUntilBelow(this.budgetBytes - (this.budgetBytes >> 2));
        }
    }

    public synchronized void invalidate(final long key) {
        final OffHeapByteArray removed = this.entries.remove(key);
        if (removed != null) {
            this.usedBytes -= removed.byteSize();
        }
    }

    public synchronized void clear() {
        this.entries.clear();
        this.usedBytes = 0L;
    }

    private void evictUntilBelow(final long targetBytes) {
        // Hash order is good enough: the entries are all equally recent as far as the cache knows,
        // and a wrong choice only costs one re-serialisation.
        final LongArrayList toDrop = new LongArrayList(64);
        final LongIterator iterator = this.entries.keySet().iterator();
        while (this.usedBytes > targetBytes && iterator.hasNext()) {
            final long candidate = iterator.nextLong();
            toDrop.add(candidate);
            final OffHeapByteArray value = this.entries.get(candidate);
            if (value != null) {
                this.usedBytes -= value.byteSize();
            }
        }
        for (int i = 0, size = toDrop.size(); i < size; i++) {
            this.entries.remove(toDrop.getLong(i));
        }
    }

    public synchronized long usedBytes() {
        return this.usedBytes;
    }

    public long hits() {
        return this.hits.get();
    }

    /**
     * Registers a shutdown hook that reports the cache statistics once, so an administrator can see
     * whether the cache actually pays for itself (hit rate) and how much native memory it used.
     * Only installed for the singleton and only when the cache is enabled.
     */
    public static void installShutdownReport() {
        if (!enabled()) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                final OffHeapChunkPayloadCache cache = INSTANCE;
                final long total = cache.hits() + cache.misses();
                final double hitRate = total == 0L ? 0.0D : (100.0D * cache.hits() / total);
                System.out.println("[Leaf] Chunk payload cache: " + cache.hits() + " hits, "
                    + cache.misses() + " misses (" + String.format(java.util.Locale.ROOT, "%.1f", hitRate)
                    + "%), " + (cache.usedBytes() >> 10) + " KiB / " + (cache.budgetBytes() >> 10)
                    + " KiB of native memory");
            }, "Leaf chunk payload cache report"));
        } catch (RuntimeException ignored) {
            // shutting down already, or a security manager - never a reason to fail
        }
    }

    public long misses() {
        return this.misses.get();
    }

    public long budgetBytes() {
        return this.budgetBytes;
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
}
