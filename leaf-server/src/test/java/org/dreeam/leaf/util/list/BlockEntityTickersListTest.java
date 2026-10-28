package org.dreeam.leaf.util.list;

import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Leaf - unit tests for {@link BlockEntityTickersList}.
 * <p>
 * The class removes block entity tickers by index at the end of a level tick. Leaf added a fast path
 * for the single-removal case (by far the most common one), so these tests check that the fast path
 * and the general path agree with a plain list model, including the boundaries (first, last, empty).
 */
class BlockEntityTickersListTest {

    private record Ticker(int id) implements TickingBlockEntity {
        @Override
        public void tick() {
        }
    }

    private static List<Integer> ids(final BlockEntityTickersList list) {
        final List<Integer> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            out.add(((Ticker) list.get(i)).id());
        }
        return out;
    }

    @Test
    void singleRemovalFastPathMatchesTheModel() {
        final BlockEntityTickersList list = new BlockEntityTickersList();
        for (int i = 0; i < 10; i++) {
            list.add(new Ticker(i));
        }
        final List<Integer> model = new ArrayList<>(ids(list));

        // middle
        list.markAsRemoved(3);
        model.remove(3);
        list.removeMarkedEntries();
        assertEquals(model, ids(list));
        assertEquals(9, list.size());

        // last index
        list.markAsRemoved(8);
        model.remove(8);
        list.removeMarkedEntries();
        assertEquals(model, ids(list));

        // first index
        list.markAsRemoved(0);
        model.remove(0);
        list.removeMarkedEntries();
        assertEquals(model, ids(list));

        // no removals is a no-op
        list.removeMarkedEntries();
        assertEquals(model, ids(list));
    }

    @Test
    void multipleRemovalsUseTheGeneralPath() {
        final BlockEntityTickersList list = new BlockEntityTickersList();
        for (int i = 0; i < 10; i++) {
            list.add(new Ticker(i));
        }
        final List<Integer> model = new ArrayList<>(ids(list));

        list.markAsRemoved(1);
        list.markAsRemoved(3);
        list.markAsRemoved(5);
        model.remove(5);
        model.remove(3);
        model.remove(1);
        list.removeMarkedEntries();

        assertEquals(model, ids(list));
    }

    @Test
    void randomRemovalsAlwaysMatchTheModel() {
        final BlockEntityTickersList list = new BlockEntityTickersList();
        final List<Integer> model = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            list.add(new Ticker(i));
            model.add(i);
        }

        final Random random = new Random(7);
        for (int round = 0; round < 1_000 && !model.isEmpty(); round++) {
            final int index = random.nextInt(model.size());
            list.markAsRemoved(index);
            model.remove(index);
            list.removeMarkedEntries();
            assertEquals(model, ids(list), "round " + round);
        }
        assertEquals(model.size(), list.size());
    }
}
