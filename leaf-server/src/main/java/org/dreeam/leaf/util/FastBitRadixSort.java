package org.dreeam.leaf.util;

import net.minecraft.world.entity.Entity;

import java.lang.reflect.Array; // Required for Array.newInstance
import java.util.Arrays;
import java.util.List;

public class FastBitRadixSort {

    private static final int SMALL_ARRAY_THRESHOLD = 2;
    private Entity[] entityBuffer = new Entity[0];
    private long[] bitsBuffer = new long[0];

    @SuppressWarnings("unchecked")
    public <T extends Entity, T_REF extends Entity> T[] sort(List<T> entities, T_REF referenceEntity, Class<T> entityClass) {
        int size = entities.size();
        if (size <= 1) {
            T[] resultArray = (T[]) Array.newInstance(entityClass, size);
            return entities.toArray(resultArray);
        }

        if (this.entityBuffer.length < size) {
            // Leaf - grow with slack instead of allocating the exact size on every increase. The
            // list length of a sensor changes all the time (one mob walks in, another dies), so exact
            // sizing reallocated both buffers again and again.
            final int newCapacity = Math.max(size, this.entityBuffer.length + (this.entityBuffer.length >> 1));
            this.entityBuffer = new Entity[newCapacity];
            this.bitsBuffer = new long[newCapacity];
        }
        for (int i = 0; i < size; i++) {
            final Entity entity = entities.get(i);
            this.entityBuffer[i] = entity;
            // Leaf - one list read and one distance computation per element instead of two reads
            this.bitsBuffer[i] = Double.doubleToRawLongBits(
                referenceEntity.distanceToSqr(entity)
            );
        }

        fastRadixSort(this.entityBuffer, this.bitsBuffer, 0, size - 1, 62);

        T[] resultArray = (T[]) Array.newInstance(entityClass, size);
        for (int i = 0; i < size; i++) {
            // Leaf - the buffer only ever holds elements taken from a List<T>, so an unchecked
            // array store is enough; Class#cast added a per element type check here.
            resultArray[i] = (T) this.entityBuffer[i];
        }
        // Leaf - do not keep the sorted entities alive until the next call: a sensor that just looked
        // at 40 mobs would otherwise pin all 40 objects (and their chunks' entities) for a whole tick
        // after they died or unloaded.
        Arrays.fill(this.entityBuffer, 0, size, null);
        return resultArray;
    }

    private void fastRadixSort(
        Entity[] ents,
        long[] bits,
        int low,
        int high,
        int bit
    ) {
        if (bit < 0 || low >= high) {
            return;
        }

        if (high - low <= SMALL_ARRAY_THRESHOLD) {
            insertionSort(ents, bits, low, high);
            return;
        }

        int i = low;
        int j = high;
        final long mask = 1L << bit;

        while (i <= j) {
            while (i <= j && (bits[i] & mask) == 0) {
                i++;
            }
            while (i <= j && (bits[j] & mask) != 0) {
                j--;
            }
            if (i < j) {
                swap(ents, bits, i++, j--);
            }
        }

        if (low < j) {
            fastRadixSort(ents, bits, low, j, bit - 1);
        }
        if (i < high) {
            fastRadixSort(ents, bits, i, high, bit - 1);
        }
    }

    private void insertionSort(
        Entity[] ents,
        long[] bits,
        int low,
        int high
    ) {
        for (int i = low + 1; i <= high; i++) {
            int j = i;
            Entity currentEntity = ents[j];
            long currentBits = bits[j];

            while (j > low && bits[j - 1] > currentBits) {
                ents[j] = ents[j - 1];
                bits[j] = bits[j - 1];
                j--;
            }
            ents[j] = currentEntity;
            bits[j] = currentBits;
        }
    }

    private void swap(Entity[] ents, long[] bits, int a, int b) {
        Entity tempEntity = ents[a];
        ents[a] = ents[b];
        ents[b] = tempEntity;

        long tempBits = bits[a];
        bits[a] = bits[b];
        bits[b] = tempBits;
    }
}
