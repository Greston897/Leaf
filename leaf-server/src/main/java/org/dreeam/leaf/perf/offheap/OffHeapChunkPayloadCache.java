package org.dreeam.leaf.perf.offheap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

import java.util.concurrent.TimeUnit;
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
 * <b>Eviction and lifetime.</b> The cache is a Caffeine cache bounded by <i>weight</i>, where the weight
 * of an entry is its exact native size in bytes, so {@code -Dleaf.offHeap.chunkCacheMB} is a real cap on
 * native memory instead of a rough hint. Entries also expire after a period without access
 * ({@code -Dleaf.offHeap.chunkCacheExpireMinutes}, default 10), which matters more than it looks:
 * the key of a payload contains the chunk revision and a coarse epoch, so a chunk that keeps changing
 * produces a new key every time, and the old keys are dead the moment they are written. A pure
 * budget-based cache keeps those dead entries until something else evicts them, i.e. it can fill up
 * completely with payloads that will never be requested again. Expiry reclaims them.
 */
public final class OffHeapChunkPayloadCache {

    private static final long BUDGET_BYTES = readLongProperty("leaf.offHeap.chunkCacheMB", 0L) * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES = readLongProperty("leaf.offHeap.chunkCacheMaxEntryKB", 512L) * 1024L;

    private static final OffHeapChunkPayloadCache INSTANCE = new OffHeapChunkPayloadCache();

    private static final long EXPIRE_MINUTES = readLongProperty("leaf.offHeap.chunkCacheExpireMinutes", 10L);

    private final Cache<Long, OffHeapByteArray> entries;
    private final long budgetBytes;
    private final AtomicLong usedBytes = new AtomicLong();
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
        // Maintenance runs on the calling thread: the accounting below must be exact right after a
        // write (a test, a report), and a chunk packet is never written from a latency critical loop
        // in a way that a synchronous eviction pass would break.
        final Caffeine<Long, OffHeapByteArray> builder = Caffeine.<Long, OffHeapByteArray>newBuilder()
            .maximumWeight(Math.max(1L, budgetBytes))
            .weigher((Long key, OffHeapByteArray value) -> (int) Math.min(Integer.MAX_VALUE, value.byteSize()))
            .executor(Runnable::run)
            .removalListener((Long key, OffHeapByteArray value, RemovalCause cause) -> {
                if (value != null) {
                    this.usedBytes.addAndGet(-value.byteSize());
                }
            });
        if (EXPIRE_MINUTES > 0L) {
            builder.expireAfterAccess(EXPIRE_MINUTES, TimeUnit.MINUTES);
        }
        this.entries = builder.build();
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
    public OffHeapByteArray get(final long key) {
        final OffHeapByteArray cached = this.entries.getIfPresent(key);
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
    public void put(final long key, final byte[] payload) {
        if (this.budgetBytes <= 0L || payload == null || payload.length == 0 || payload.length > MAX_ENTRY_BYTES) {
            return;
        }

        final OffHeapByteArray copy;
        try {
            copy = OffHeapByteArray.copyOf(payload);
        } catch (OutOfMemoryError | RuntimeException allocationFailure) {
            // direct memory is a separate, capped pool: a failed allocation simply means a miss later
            return;
        }

        // A replaced value fires the removal listener with REPLACED, which takes the old size off the
        // counter; adding the new size afterwards keeps the accounting balanced.
        this.usedBytes.addAndGet(copy.byteSize());
        this.entries.asMap().put(key, copy);
        if (this.usedBytes.get() > this.budgetBytes) {
            this.entries.cleanUp();
        }
    }

    public void invalidate(final long key) {
        this.entries.invalidate(key);
        this.entries.cleanUp();
    }

    public void clear() {
        this.entries.invalidateAll();
        this.entries.cleanUp();
        this.usedBytes.set(0L);
    }

    /**
     * Leaf - shed a share of the payloads, used by the RAM janitor when the heap or the native pools are
     * under pressure.
     * <p>
     * The cache is weight bounded, so the cheapest way to force Caffeine to evict its least recently
     * used tail is to lower {@code maximumWeight} for the duration of one maintenance pass and raise it
     * back afterwards. Evicted entries are <b>not</b> reloaded when the limit is restored - the point of
     * a cache is that it refills on demand.
     *
     * @param ratio  share of the current budget to give up, {@code (0, 1]}
     * @param dryRun when {@code true} only the effect is estimated
     * @return the estimated number of native bytes released
     */
    public long trim(final double ratio, final boolean dryRun) {
        final long before = this.usedBytes();
        if (before <= 0L || ratio <= 0.0D) {
            return 0L;
        }
        final double share = Math.min(1.0D, ratio);
        if (dryRun) {
            return (long) (before * share);
        }
        try {
            final long target = Math.max(1L, this.budgetBytes - (long) (this.budgetBytes * share));
            this.entries.policy().eviction().ifPresent(eviction -> {
                final long configured = eviction.getMaximum();
                eviction.setMaximum(Math.min(configured, target));
                this.entries.cleanUp();
                // restore the configured ceiling: the evicted entries stay evicted, the cache refills
                // on demand, and the next pass can shave another slice
                eviction.setMaximum(configured);
            });
            this.entries.cleanUp();
        } catch (Throwable ignored) {
            // a weight bound that cannot be lowered is not worth a broken pass
            return 0L;
        }
        return Math.max(0L, before - this.usedBytes());
    }

    public long usedBytes() {
        return Math.max(0L, this.usedBytes.get());
    }

    /**
     * @return number of cached payloads, for diagnostics
     */
    public long size() {
        return this.entries.estimatedSize();
    }

    /**
     * @return the lifetime of an unused entry in minutes, 0 when entries never expire
     */
    public static long expireMinutes() {
        return EXPIRE_MINUTES;
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
                    + " KiB of native memory in " + cache.size() + " entries, unused entries expire after "
                    + expireMinutes() + " min");
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
