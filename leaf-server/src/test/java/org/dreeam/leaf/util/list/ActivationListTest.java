package org.dreeam.leaf.util.list;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaf - unit tests for {@link ActivationList}.
 * <p>
 * The point of these tests is the visibility counter Leaf added: {@code size()}, {@code isEmpty()},
 * {@code get(int)}, iteration and {@code remove} all have to agree with each other at every step,
 * because {@code Brain} reads {@code size()} on every tick of every active mob.
 */
class ActivationListTest {

    private static <T> List<T> snapshot(final ActivationList<T> list) {
        final List<T> out = new ArrayList<>();
        for (final Iterator<T> iterator = list.iterator(); iterator.hasNext(); ) {
            out.add(iterator.next());
        }
        return out;
    }

    @Test
    void visibilityTogglesKeepSizeExact() {
        final ActivationList<String> list = new ActivationList<>(false);
        for (final String value : List.of("a", "b", "c", "d", "e")) {
            list.add(value);
        }
        assertTrue(list.isEmpty(), "nothing is visible by default");
        assertEquals(0, list.size());

        list.addOrUpdate("b", true);
        list.addOrUpdate("d", true);
        assertEquals(2, list.size());
        assertEquals(List.of("b", "d"), snapshot(list));

        list.addOrUpdate("d", true);
        assertEquals(2, list.size(), "re-applying the same visibility must be idempotent");

        list.setVisibility("b", false);
        assertEquals(1, list.size());
        assertEquals(List.of("d"), snapshot(list));

        list.setVisibility("a", true);
        list.setVisibility("c", true);
        list.setVisibility("e", true);
        assertEquals(4, list.size());
        assertEquals(List.of("a", "c", "d", "e"), snapshot(list));
    }

    @Test
    void removalKeepsSizeAndOrder() {
        final ActivationList<String> list = new ActivationList<>(true);
        for (final String value : List.of("a", "b", "c", "d")) {
            list.add(value);
        }

        assertTrue(list.remove("b"));
        assertEquals(3, list.size());
        assertEquals(List.of("a", "c", "d"), snapshot(list));

        list.setVisibility("c", false);
        assertTrue(list.remove("c"));
        assertEquals(2, list.size());
        assertEquals(List.of("a", "d"), snapshot(list));

        assertFalse(list.remove("missing"));
        assertEquals(2, list.size());
    }

    @Test
    void compactionPreservesContentAndCounter() {
        final ActivationList<Integer> list = new ActivationList<>(true);
        for (int i = 0; i < 20; i++) {
            list.add(i);
        }
        assertEquals(20, list.size());

        for (int i = 0; i < 12; i++) {
            list.remove(Integer.valueOf(i));
        }

        final List<Integer> expected = new ArrayList<>();
        for (int i = 12; i < 20; i++) {
            expected.add(i);
        }
        assertEquals(expected, snapshot(list));
        assertEquals(8, list.size());
        assertEquals(Integer.valueOf(12), list.get(0));
        assertEquals(Integer.valueOf(19), list.get(7));
    }

    @Test
    void randomTogglesMatchAModel() {
        final ActivationList<Integer> list = new ActivationList<>(false);
        final boolean[] visible = new boolean[64];
        for (int i = 0; i < visible.length; i++) {
            list.add(i);
        }

        final Random random = new Random(42);
        for (int step = 0; step < 20_000; step++) {
            final int key = random.nextInt(visible.length);
            final boolean value = random.nextBoolean();
            list.setVisibility(key, value);
            visible[key] = value;
        }

        final List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < visible.length; i++) {
            if (visible[i]) {
                expected.add(i);
            }
        }
        assertEquals(expected.size(), list.size());
        assertEquals(expected, snapshot(list));
    }

    @Test
    void duplicateAddIsRejected() {
        final ActivationList<Integer> list = new ActivationList<>(true);
        list.add(1);
        assertThrows(IllegalArgumentException.class, () -> list.add(1));
    }
}
