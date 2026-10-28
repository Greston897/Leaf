package org.dreeam.leaf.util.cache;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.BitSet;

/**
 * Thread local cache of reusable {@link BitSet}s, used by {@code 0095-C2ME-Reduce-Allocations.patch}.
 * <p>
 * The lookup is a plain fastutil get/put instead of {@code computeIfAbsent(int, IntFunction)}: the
 * latter has to route through a functional interface on every call, and this method is called once
 * per chunk section during chunk serialisation.
 */
public class CachedOrNewBitsGetter {

    private static final ThreadLocal<Int2ObjectOpenHashMap<BitSet>> BITSETS =
        ThreadLocal.withInitial(() -> new Int2ObjectOpenHashMap<>(4));

    private CachedOrNewBitsGetter() {
    }

    public static BitSet getCachedOrNewBitSet(final int bits) {
        final Int2ObjectOpenHashMap<BitSet> cache = BITSETS.get();

        BitSet bitSet = cache.get(bits);
        if (bitSet == null) {
            bitSet = new BitSet(bits);
            cache.put(bits, bitSet);
        }

        bitSet.clear();

        return bitSet;
    }
}
