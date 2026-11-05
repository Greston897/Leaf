package org.dreeam.leaf.perf.ram;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaf - the per-player cleanup rules: a quit drops everything, a sweep drops what escaped, and the
 * watchdog notices when an object the server should have let go is still reachable.
 */
class PlayerDataCleanerTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    /**
     * A holder that behaves like the real ones: keyed by UUID, size countable, optional enumeration.
     */
    private static final class Holder implements PlayerDataCleaner.PlayerScoped {

        private final String id;
        private final Map<UUID, Integer> entries = new HashMap<>();
        private boolean enumerable = true;
        private boolean throwOnForget;

        private Holder(final String id) {
            this.id = id;
        }

        private Holder put(final UUID playerId, final int count) {
            entries.put(playerId, count);
            return this;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int forget(final UUID playerId, final Object handle) {
            if (throwOnForget) {
                throw new IllegalStateException("this holder is broken");
            }
            final Integer removed = entries.remove(playerId);
            return removed == null ? 0 : removed;
        }

        @Override
        public Collection<UUID> tracked() {
            return enumerable ? new ArrayList<>(entries.keySet()) : List.of();
        }
    }

    /**
     * A holder keyed by the player object, like the protocols that keep a {@code Map<ServerPlayer, ...>}.
     */
    private static final class ObjectKeyedHolder implements PlayerDataCleaner.PlayerScoped {

        private final List<Object> retained = new ArrayList<>();

        @Override
        public String id() {
            return "object-keyed";
        }

        @Override
        public int forget(final UUID playerId, final Object handle) {
            if (handle == null) {
                return 0;
            }
            final boolean removed = retained.remove(handle);
            return removed ? 1 : 0;
        }
    }

    @Test
    void purgeDropsEverythingOnePlayerOwns() {
        final PlayerDataCleaner cleaner = new PlayerDataCleaner();
        final Holder protocols = new Holder("protocols").put(ALICE, 3).put(BOB, 2);
        final Holder stats = new Holder("stats").put(ALICE, 1);
        cleaner.register(protocols);
        cleaner.register(stats);

        final Object handle = new Object();
        final PlayerDataCleaner.Purge purge = cleaner.purge(ALICE, handle);

        assertEquals(2, purge.holders());
        assertEquals(4, purge.entries());
        assertFalse(purge.isEmpty());
        assertTrue(purge.summary().contains("4 entries"));
        // only Alice is gone
        assertEquals(2, protocols.entries.get(BOB));
        assertFalse(protocols.entries.containsKey(ALICE));
        assertEquals(4L, cleaner.purgedEntries());
        assertEquals(1L, cleaner.purgedPlayers());
    }

    @Test
    void purgeAlsoReachesHoldersKeyedByThePlayerObject() {
        final PlayerDataCleaner cleaner = new PlayerDataCleaner();
        final ObjectKeyedHolder holder = new ObjectKeyedHolder();
        final Object playerOne = new Object();
        final Object playerTwo = new Object();
        holder.retained.add(playerOne);
        holder.retained.add(playerTwo);
        cleaner.register(holder);

        assertEquals(1, cleaner.purge(ALICE, playerOne).entries());
        assertEquals(1, holder.retained.size());
        // without the object a holder keyed by it cannot find its entry - that is why the quit path
        // always passes the player, and why the sweep is only a second line of defence
        assertTrue(cleaner.purge(ALICE, null).isEmpty());
        assertEquals(1, holder.retained.size());
    }

    @Test
    void sweepDropsPlayersThatAreNotOnlineAnyMore() {
        final PlayerDataCleaner cleaner = new PlayerDataCleaner();
        final Holder holder = new Holder("protocols").put(ALICE, 2).put(BOB, 5);
        cleaner.register(holder);

        final PlayerDataCleaner.Purge purge = cleaner.sweep(List.of(BOB));

        assertEquals(1, purge.holders());
        assertEquals(2, purge.entries());
        assertTrue(holder.entries.containsKey(BOB), "an online player keeps his state");
        assertFalse(holder.entries.containsKey(ALICE));
        assertEquals(2L, cleaner.sweptEntries());
    }

    @Test
    void sweepIgnoresHoldersThatCannotEnumerate() {
        final PlayerDataCleaner cleaner = new PlayerDataCleaner();
        final Holder holder = new Holder("silent").put(ALICE, 2);
        holder.enumerable = false;
        cleaner.register(holder);

        final PlayerDataCleaner.Purge purge = cleaner.sweep(List.of());
        assertEquals(0, purge.entries());
        assertEquals(2, holder.entries.get(ALICE));
    }

    @Test
    void aBrokenHolderDoesNotStopTheOthers() {
        final PlayerDataCleaner cleaner = new PlayerDataCleaner();
        final Holder broken = new Holder("broken").put(ALICE, 1);
        broken.throwOnForget = true;
        final Holder healthy = new Holder("healthy").put(ALICE, 2);
        cleaner.register(broken);
        cleaner.register(healthy);

        final PlayerDataCleaner.Purge purge = cleaner.purge(ALICE, new Object());
        assertEquals(1, purge.holders());
        assertEquals(2, purge.entries());
    }

    @Test
    void unknownPlayerIsANoOp() {
        final PlayerDataCleaner cleaner = new PlayerDataCleaner();
        cleaner.register(new Holder("protocols").put(BOB, 1));

        final PlayerDataCleaner.Purge purge = cleaner.purge(ALICE, new Object());
        assertTrue(purge.isEmpty());
        assertEquals(0, purge.holders());
        assertTrue(cleaner.purge(null, null).isEmpty());
    }

    @Test
    void watchdogWatchesTheObjectThroughAWeakReference() {
        final PlayerDataCleaner cleaner = new PlayerDataCleaner();
        final Object player = new Object();

        final PlayerDataCleaner.Watch watch = cleaner.watch(ALICE, "alice", player, 100L, 200L);
        assertNotNull(watch);
        assertSame(player, watch.retained());
        assertFalse(watch.released());
        assertEquals(1, cleaner.watchCount());

        assertTrue(cleaner.due(299L).isEmpty(), "the grace period has not expired yet");
        final List<PlayerDataCleaner.Watch> due = cleaner.due(300L);
        assertEquals(1, due.size());

        cleaner.release(watch);
        assertEquals(0, cleaner.watchCount());
        assertTrue(cleaner.due(1000L).isEmpty());
    }

    @Test
    void reschedulingBacksOffAndGivesUpEventually() {
        final PlayerDataCleaner cleaner = new PlayerDataCleaner();
        final Object player = new Object();
        final PlayerDataCleaner.Watch watch = cleaner.watch(ALICE, "alice", player, 0L, 10L);
        assertNotNull(watch);

        cleaner.reschedule(watch, 10L, 10L);
        assertEquals(1, watch.attempts());
        assertTrue(cleaner.due(19L).isEmpty());
        assertEquals(1, cleaner.due(20L).size());

        cleaner.reschedule(watch, 20L, 10L);
        cleaner.reschedule(watch, 30L, 10L);
        cleaner.reschedule(watch, 40L, 10L);
        assertEquals(0, cleaner.watchCount(), "after four attempts the watchdog gives up");
    }

    @Test
    void describeListsTheHolders() {
        final PlayerDataCleaner cleaner = new PlayerDataCleaner();
        cleaner.register(new Holder("protocols").put(ALICE, 1));
        cleaner.purge(ALICE, new Object());

        final String description = cleaner.describe();
        assertTrue(description.contains("protocols"));
        assertTrue(description.contains("1 entries"));
        assertTrue(cleaner.lastReport().contains("1 entry"));
    }
}
