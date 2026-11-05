package org.dreeam.leaf.perf.ram;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Leaf - everything the server owns about a player dies with the player's session.
 * <p>
 * <b>Why this exists.</b> Vanilla releases a {@code ServerPlayer} when it disconnects - and then the
 * fork, the libraries and the protocols put it back: a map keyed by {@code ServerPlayer}, a
 * {@code Map<UUID, ...>} that is filled on join and forgotten on leave only in the happy path, a delayed
 * task that still holds the connection. None of these show up as a leak in a profiler, they show up as a
 * slow climb of the heap on a server that has been up for a week with 300 daily players. The class gives
 * every such cache one lifecycle instead of one hand written {@code remove()} call per map:
 * <ul>
 *     <li><b>Immediate purge.</b> The moment a player leaves, {@link #purge} calls every registered
 *     {@link PlayerScoped} holder with the player's UUID and the player object (so holders keyed by the
 *     object itself can find their entries too).</li>
 *     <li><b>Stale sweep.</b> Every {@link #sweep} drops entries whose UUID belongs to nobody who is
 *     online. This is the part that cannot be forgotten by a holder: even a cache that never got a
 *     leave callback, or one filled by a player who timed out mid-disconnect, is reclaimed on the next
 *     sweep. A holder that cannot enumerate its keys simply reports no keys and is only reachable by the
 *     immediate purge.</li>
 *     <li><b>Retention watchdog.</b> After a quit the object is watched through a {@link WeakReference}.
 *     If it is still strongly reachable a few seconds later, the server logs which holders still know
 *     the UUID and purges them again - a leak that would otherwise need a heap dump to notice.</li>
 * </ul>
 * The class itself is free of Minecraft types (the retained object is an {@code Object}), which is what
 * makes its behaviour testable with plain JUnit and impossible to break with a refactor of the server.
 */
public final class PlayerDataCleaner {

    /**
     * One cache that knows things about players and can let go of them.
     */
    public interface PlayerScoped {

        /**
         * @return a short identifier used in reports
         */
        String id();

        /**
         * Drops everything this holder knows about the player.
         *
         * @param playerId the player's UUID
         * @param handle   the player object on the quit path, or {@code null} during a sweep - a holder
         *                 keyed by the object itself must return {@code 0} when the handle is missing
         * @return the number of entries that were dropped
         */
        int forget(UUID playerId, Object handle);

        /**
         * @return the UUIDs this holder currently knows about, or an empty collection when it cannot
         * enumerate them. Used by the sweep only.
         */
        default Collection<UUID> tracked() {
            return List.of();
        }
    }

    /**
     * @param holders number of holders that dropped at least one entry
     * @param entries total number of dropped entries
     * @param detail  a short "holder 3, holder 1" list for the log
     */
    public record Purge(int holders, int entries, String detail) {

        public boolean isEmpty() {
            return entries == 0;
        }

        public String summary() {
            return entries + " entr" + (entries == 1 ? "y" : "ies") + " from " + holders + " holder"
                + (holders == 1 ? "" : "s") + (detail.isEmpty() ? "" : " (" + detail + ')');
        }
    }

    /**
     * A player whose object should be gone, and is being watched to make sure it is.
     */
    public static final class Watch {

        private final UUID playerId;
        private final String playerName;
        private final WeakReference<Object> reference;
        private long dueTick;
        private int attempts;

        private Watch(final UUID playerId, final String playerName, final Object handle, final long dueTick) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.reference = new WeakReference<>(handle);
            this.dueTick = dueTick;
        }

        public UUID playerId() {
            return playerId;
        }

        public String playerName() {
            return playerName;
        }

        public int attempts() {
            return attempts;
        }

        /**
         * @return {@code true} once the object has been collected - the normal, expected outcome
         */
        public boolean released() {
            return reference.get() == null;
        }

        /**
         * @return the retained object, or {@code null} when it is already gone
         */
        public Object retained() {
            return reference.get();
        }
    }

    /** maximum number of watched players; a busy server must not accumulate watches itself */
    private static final int MAX_WATCHES = 64;

    private final List<PlayerScoped> holders = new ArrayList<>(6);
    private final List<Watch> watches = new ArrayList<>(4);
    private final StringBuilder scratch = new StringBuilder(64);

    private long purgedEntries;
    private long purgedPlayers;
    private long sweptEntries;
    private String lastReport = "";

    public void register(final PlayerScoped holder) {
        if (holder == null || holders.contains(holder)) {
            return;
        }
        holders.add(holder);
    }

    public List<PlayerScoped> holders() {
        return holders;
    }

    public long purgedEntries() {
        return purgedEntries;
    }

    public long purgedPlayers() {
        return purgedPlayers;
    }

    public long sweptEntries() {
        return sweptEntries;
    }

    public String lastReport() {
        return lastReport;
    }

    /**
     * Drops everything every registered holder knows about one player.
     *
     * @param playerId the player's UUID
     * @param handle   the player object when the caller has it, otherwise {@code null}
     */
    public Purge purge(final UUID playerId, final Object handle) {
        if (playerId == null || holders.isEmpty()) {
            return new Purge(0, 0, "");
        }
        scratch.setLength(0);
        int total = 0;
        int dropped = 0;
        for (final PlayerScoped holder : holders) {
            final int removed;
            try {
                removed = holder.forget(playerId, handle);
            } catch (final Throwable throwable) {
                continue;
            }
            if (removed > 0) {
                total += removed;
                dropped++;
                appendDetail(holder.id(), removed);
            }
        }
        if (total > 0) {
            purgedPlayers++;
            purgedEntries += total;
        }
        final Purge result = new Purge(dropped, total, scratch.toString());
        lastReport = result.summary();
        return result;
    }

    /**
     * Drops entries of players that are not online any more.
     *
     * @param online the UUIDs of the players currently online; entries for anyone else are stale
     * @return what was dropped
     */
    public Purge sweep(final Collection<UUID> online) {
        if (holders.isEmpty()) {
            return new Purge(0, 0, "");
        }
        final Set<UUID> connected = online == null ? Set.of() : new HashSet<>(online);
        scratch.setLength(0);
        int total = 0;
        int dropped = 0;
        for (final PlayerScoped holder : holders) {
            Collection<UUID> tracked;
            try {
                tracked = holder.tracked();
            } catch (final Throwable throwable) {
                continue;
            }
            int removedHere = 0;
            for (final UUID playerId : tracked) {
                if (playerId == null || connected.contains(playerId)) {
                    continue;
                }
                try {
                    removedHere += holder.forget(playerId, null);
                } catch (final Throwable throwable) {
                    // a holder that cannot forget one entry must not stop the sweep
                }
            }
            if (removedHere > 0) {
                total += removedHere;
                dropped++;
                appendDetail(holder.id(), removedHere);
            }
        }
        sweptEntries += total;
        if (total > 0) {
            lastReport = "sweep dropped " + new Purge(dropped, total, scratch.toString()).summary();
        }
        return new Purge(dropped, total, scratch.toString());
    }

    /**
     * Starts watching a player object that has just left.
     *
     * @param handle     the object that should be collected
     * @param graceTicks how many ticks to give the collector
     */
    public Watch watch(final UUID playerId, final String playerName, final Object handle,
                       final long currentTick, final long graceTicks) {
        if (playerId == null || handle == null) {
            return null;
        }
        for (int i = 0; i < watches.size(); i++) {
            if (watches.get(i).playerId.equals(playerId)) {
                watches.remove(i);
                break;
            }
        }
        if (watches.size() >= MAX_WATCHES) {
            watches.remove(0);
        }
        final Watch watch = new Watch(playerId, playerName, handle, currentTick + Math.max(1L, graceTicks));
        watches.add(watch);
        return watch;
    }

    /**
     * @return watches whose grace period has expired; the caller inspects {@link Watch#released()} and
     * calls {@link #release} or {@link #reschedule}
     */
    public List<Watch> due(final long currentTick) {
        if (watches.isEmpty()) {
            return List.of();
        }
        List<Watch> due = null;
        for (int i = 0; i < watches.size(); i++) {
            final Watch watch = watches.get(i);
            if (watch.dueTick <= currentTick) {
                if (due == null) {
                    due = new ArrayList<>(2);
                }
                due.add(watch);
            }
        }
        return due == null ? List.of() : due;
    }

    public void release(final Watch watch) {
        watches.remove(watch);
    }

    /**
     * Keeps the watch for another period, used when the object is still retained after a purge.
     */
    public void reschedule(final Watch watch, final long currentTick, final long backoffTicks) {
        if (watch == null) {
            return;
        }
        watch.attempts++;
        watch.dueTick = currentTick + Math.max(1L, backoffTicks);
        if (watch.attempts > 3) {
            watches.remove(watch);
        }
    }

    public int watchCount() {
        return watches.size();
    }

    /**
     * @return the UUIDs that at least one holder still knows about, for {@code /leaf ram players}
     */
    public Set<UUID> knownPlayerIds() {
        final Set<UUID> ids = new HashSet<>();
        for (final PlayerScoped holder : holders) {
            try {
                ids.addAll(holder.tracked());
            } catch (final Throwable throwable) {
                // enumeration is optional
            }
        }
        return ids;
    }

    public String describe() {
        final StringBuilder out = new StringBuilder(160);
        out.append("purged ").append(purgedEntries).append(" entries / ").append(purgedPlayers)
            .append(" players, sweep ").append(sweptEntries).append(" entries, holders: ");
        for (int i = 0; i < holders.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(holders.get(i).id());
        }
        return out.toString();
    }

    private void appendDetail(final String id, final int removed) {
        if (scratch.length() > 0) {
            scratch.append(", ");
        }
        if (scratch.length() < 64) {
            scratch.append(id).append(' ').append(removed);
        }
    }
}
