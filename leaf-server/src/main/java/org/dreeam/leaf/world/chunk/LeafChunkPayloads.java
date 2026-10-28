package org.dreeam.leaf.world.chunk;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.dreeam.leaf.perf.offheap.OffHeapByteArray;
import org.dreeam.leaf.perf.offheap.OffHeapChunkPayloadCache;

/**
 * Leaf - glue between the patched chunk packet code and the off-heap payload cache.
 * <p>
 * The engine patches only add two calls: one where the chunk payload is encoded
 * ({@code ClientboundLevelChunkPacketData}) and one revision counter in {@code LevelChunk}. All the
 * decision logic lives here, so the patched code stays a one liner and the behaviour can be reasoned
 * about (and tested) in one place.
 * <p>
 * When the cache is used:
 * <ul>
 *     <li>the off-heap chunk cache is enabled ({@code -Dleaf.offHeap.chunkCacheMB}),</li>
 *     <li>anti-xray does not rewrite this player's payload ({@code chunkPacketInfo == null}), because
 *     the obfuscation is per player,</li>
 *     <li>the chunk has no block entities: their serialised form changes without a block change
 *     (sign text, spawner data, ...), and skipping the cache there keeps correctness trivial.</li>
 * </ul>
 * Every cached payload is keyed by dimension, chunk coordinates, the chunk's block revision and a
 * coarse epoch, so anything that is not hooked cannot stay cached for longer than
 * {@link #EPOCH_TICKS} ticks.
 */
public final class LeafChunkPayloads {

    /**
     * 12 000 ticks = 10 minutes at 20 TPS. Only matters for changes no hook sees (biome edits,
     * plugin writes into the chunk); normal block changes bump the revision immediately.
     */
    private static final int EPOCH_TICKS = 12000;

    private static final int EPOCH_MIX = 0x9E3779B1;

    private LeafChunkPayloads() {
    }

    /**
     * @param chunkPacketInfo Paper's anti-xray per player packet info, {@code null} when anti-xray is
     *                        not modifying this packet
     * @return whether the payload of this chunk may be read from / written to the cache
     */
    public static boolean isCacheable(final LevelChunk chunk, final Object chunkPacketInfo) {
        return OffHeapChunkPayloadCache.enabled()
            && chunkPacketInfo == null
            && chunk.getBlockEntities().isEmpty();
    }

    /**
     * @return the cached payload for this chunk, or {@code null} when the caller has to encode it.
     * The array is a fresh copy, so the caller may keep it.
     */
    public static byte[] cachedOrNull(final LevelChunk chunk, final Object chunkPacketInfo) {
        if (!isCacheable(chunk, chunkPacketInfo)) {
            return null;
        }
        final OffHeapByteArray cached = OffHeapChunkPayloadCache.instance().get(key(chunk));
        return cached == null ? null : cached.toByteArray();
    }

    /**
     * Stores the freshly encoded payload for later re-use. Never throws and never fails the caller:
     * a rejected or failed store only costs one re-encoding next time.
     */
    public static void store(final LevelChunk chunk, final Object chunkPacketInfo, final byte[] payload) {
        if (isCacheable(chunk, chunkPacketInfo)) {
            OffHeapChunkPayloadCache.instance().put(key(chunk), payload);
        }
    }

    private static long key(final LevelChunk chunk) {
        final Level level = chunk.getLevel();
        final int revision = chunk.leafPayloadRevision() ^ (int) (level.getGameTime() / EPOCH_TICKS) * EPOCH_MIX;
        return OffHeapChunkPayloadCache.key(
            level.dimension().location().hashCode(),
            chunk.getPos().x,
            chunk.getPos().z,
            revision,
            false // anti-xray payloads are never cached, see isCacheable
        );
    }
}
