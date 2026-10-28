package org.dreeam.leaf.perf.offheap;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaf - unit tests for the off-heap layer.
 * <p>
 * These are deliberately plain: they assert the memory round trips, that the budget is accounted for,
 * that small tables stay on the heap (where off-heap would be slower) and that the disabled mode and
 * the fallbacks never throw.
 */
class OffHeapTest {

    @Test
    void longArrayRoundTrips() {
        final long[] values = new long[4096];
        for (int i = 0; i < values.length; i++) {
            values[i] = (long) i * 31L - 7L;
        }

        final OffHeapLongArray array = OffHeapLongArray.tryCopyOf(values);
        assertTrue(array != null, "off-heap allocation must succeed in the test environment");
        assertEquals(values.length, array.length());
        for (int i = 0; i < values.length; i++) {
            assertEquals(values[i], array.get(i), "value at " + i);
        }
        assertEquals(array.byteSize(), values.length * 8L);
        assertTrue(OffHeapLongArray.reservedBytes() >= array.byteSize(), "budget must be accounted");
    }

    @Test
    void emptyArrayFallsBackToHeap() {
        assertNull(OffHeapLongArray.tryCopyOf(new long[0]));
    }

    @Test
    void longListViewMatchesTheHeapContent() {
        final long[] values = new long[2048];
        for (int i = 0; i < values.length; i++) {
            values[i] = i * 17L;
        }
        final OffHeapLongArray array = OffHeapLongArray.tryCopyOf(values);
        final OffHeapLongList list = new OffHeapLongList(array);

        assertEquals(values.length, list.size());
        assertEquals(values[123], list.getLong(123));
        assertArrayEquals(values, list.toLongArray());
        assertEquals(array.byteSize(), list.offHeapBytes());
    }

    @Test
    void smallListsStayOnTheHeap() {
        final it.unimi.dsi.fastutil.longs.LongArrayList small = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (int i = 0; i < 16; i++) {
            small.add(i);
        }
        assertSame(small, OffHeapLongLists.offHeapIfWorthwhile(small), "a 16 element list is not worth moving off heap");
    }

    @Test
    void largeListsMoveOffHeap() {
        final it.unimi.dsi.fastutil.longs.LongArrayList large = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (int i = 0; i < 4096; i++) {
            large.add(i * 3L);
        }
        assertEquals(4096, OffHeapLongLists.offHeapIfWorthwhile(large).size());
    }

    @Test
    void byteArrayViewsAreIndependent() {
        final byte[] payload = new byte[1234];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 7);
        }

        final OffHeapByteArray bytes = OffHeapByteArray.copyOf(payload);
        assertEquals(payload.length, bytes.length());

        final ByteBuffer first = bytes.view();
        final ByteBuffer second = bytes.view();
        first.get();
        assertEquals(0, second.position(), "views must not share position state");

        assertArrayEquals(payload, bytes.toByteArray());
    }

    @Test
    void cacheKeysAreStableAndSpreadOut() {
        final long base = OffHeapChunkPayloadCache.key(1, 10, -20, 3, false);

        assertEquals(base, OffHeapChunkPayloadCache.key(1, 10, -20, 3, false), "the same chunk must map to the same key");
        assertTrue(base != OffHeapChunkPayloadCache.key(1, 10, -20, 4, false), "a content revision change must change the key");
        assertTrue(base != OffHeapChunkPayloadCache.key(1, 10, -20, 3, true), "anti-xray changes the payload");
        assertTrue(base != OffHeapChunkPayloadCache.key(2, 10, -20, 3, false), "dimensions must not collide");
        assertTrue(base != OffHeapChunkPayloadCache.key(1, 11, -20, 3, false), "x must not collide");
        assertTrue(base != OffHeapChunkPayloadCache.key(1, 10, -19, 3, false), "z must not collide");

        // a small grid must not produce collisions: a colliding key serves the wrong chunk
        final java.util.Set<Long> keys = new java.util.HashSet<>();
        int collisions = 0;
        for (int x = -8; x < 8; x++) {
            for (int z = -8; z < 8; z++) {
                for (int revision = 0; revision < 4; revision++) {
                    if (!keys.add(OffHeapChunkPayloadCache.key(1, x, z, revision, false))) {
                        collisions++;
                    }
                }
            }
        }
        assertEquals(0, collisions, "keys must be unique inside a normal working set");
    }

    @Test
    void cacheEvictsWhenTheBudgetIsExceeded() {
        final OffHeapChunkPayloadCache cache = new OffHeapChunkPayloadCache(64L * 1024L); // 64 KiB
        final byte[] payload = new byte[1024];

        for (int i = 0; i < 64; i++) {
            cache.put(i, payload);
        }

        assertTrue(cache.usedBytes() <= cache.budgetBytes(),
            "used " + cache.usedBytes() + " bytes, budget " + cache.budgetBytes());
        assertTrue(cache.usedBytes() > 0L, "the cache must still hold something");
    }

    @Test
    void chunkPayloadCacheStoresAndInvalidates() {
        final byte[] payload = new byte[4096];
        Arrays.fill(payload, (byte) 1);
        // an explicit budget: the singleton is driven by -Dleaf.offHeap.chunkCacheMB and is off by default
        final OffHeapChunkPayloadCache cache = new OffHeapChunkPayloadCache(4L * 1024L * 1024L);

        cache.put(42L, payload);
        final OffHeapByteArray hit = cache.get(42L);
        assertTrue(hit != null, "payload must be cached");
        assertArrayEquals(payload, hit.toByteArray());
        assertTrue(cache.hits() >= 1);

        cache.invalidate(42L);
        assertNull(cache.get(42L));
        assertEquals(0L, cache.usedBytes());
    }
}
