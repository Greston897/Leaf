package org.dreeam.leaf.util.cache;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaf - unit tests for the thread local {@link BitSet} cache used during chunk serialisation.
 * <p>
 * The cache hands out the same mutable instance per size, so the only thing that matters for
 * correctness is that it is always cleared before it is returned.
 */
class CachedOrNewBitsGetterTest {

    @Test
    void returnsAReusedClearedInstance() {
        final BitSet first = CachedOrNewBitsGetter.getCachedOrNewBitSet(64);
        first.set(3);
        first.set(63);
        assertFalse(first.isEmpty());

        final BitSet second = CachedOrNewBitsGetter.getCachedOrNewBitSet(64);
        assertSame(first, second, "same size must reuse the instance");
        assertTrue(second.isEmpty(), "a reused instance must be cleared");
    }

    @Test
    void differentSizesDoNotShareState() {
        final BitSet small = CachedOrNewBitsGetter.getCachedOrNewBitSet(64);
        final BitSet large = CachedOrNewBitsGetter.getCachedOrNewBitSet(4096);
        assertNotSame(small, large);

        small.set(10);
        assertTrue(large.isEmpty(), "sizes must be independent");
    }
}
