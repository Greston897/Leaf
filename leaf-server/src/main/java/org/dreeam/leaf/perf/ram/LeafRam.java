package org.dreeam.leaf.perf.ram;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.dreeam.leaf.async.path.NodeEvaluatorCache;
import org.dreeam.leaf.config.modules.opt.RamJanitorConfig;
import org.dreeam.leaf.misc.LagCompensation;
import org.dreeam.leaf.perf.offheap.OffHeapChunkPayloadCache;
import org.dreeam.leaf.protocol.DoABarrelRollProtocol;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Leaf - the server side of the RAM janitor: it wires the policy to the caches the fork actually owns,
 * and to the two moments that matter - the server tick and a player leaving.
 * <p>
 * Everything above this class is pure Java with no Minecraft types, which is why the interesting logic
 * ({@link RamJanitor}, {@link PlayerDataCleaner}) is unit tested without a running server. This class is
 * deliberately thin: it reads the heap, asks the policy, logs, and registers one {@link Trimmable} per
 * cache. Adding a cache to the janitor is three lines here and nothing else.
 * <p>
 * <b>Why the hooks are where they are.</b> {@code Protocols.tickServer} is already called once per tick
 * from {@code MinecraftServer.tickChildren}, and {@code Protocols.disconnected} is called from
 * {@code PlayerList.remove} right after the Leaves protocol leave callbacks - both are existing patch
 * points, so this feature needs no new patch in the server classes.
 */
public final class LeafRam {

    private static final String PREFIX = "[Leaf] ";

    private static final PlayerDataCleaner CLEANER = new PlayerDataCleaner();

    private static volatile RamJanitor janitor;
    private static volatile boolean installed;

    private LeafRam() {
    }

    /**
     * Registers every cache and holder once. Runs on the first tick, i.e. after the configuration has
     * been loaded, and is safe to call from any thread.
     */
    public static void install() {
        if (installed) {
            return;
        }
        synchronized (LeafRam.class) {
            if (installed) {
                return;
            }

            final RamJanitor.Policy policy = new RamJanitor.Policy(
                RamJanitorConfig.softWatermarkPercent / 100.0D,
                RamJanitorConfig.hardWatermarkPercent / 100.0D,
                RamJanitorConfig.emergencyWatermarkPercent / 100.0D,
                RamJanitorConfig.maxMspt,
                RamJanitorConfig.minIntervalSeconds * 1_000_000_000L,
                RamJanitorConfig.maxPassMillis * 1_000_000L,
                RamJanitorConfig.maxCachesPerPass,
                48L * 1024L * 1024L,
                0.03D);
            final RamJanitor created = new RamJanitor(policy, System::nanoTime);

            // --- caches the janitor may trim ---------------------------------------------------
            created.register(new Trimmable() {
                @Override
                public String id() {
                    return "pathfinding-evaluators";
                }

                @Override
                public long retainedBytes() {
                    return NodeEvaluatorCache.estimatedBytes();
                }

                @Override
                public int size() {
                    return NodeEvaluatorCache.parkedEvaluators();
                }

                @Override
                public long hits() {
                    return NodeEvaluatorCache.reuseHits();
                }

                @Override
                public long trim(final double ratio, final boolean dryRun) {
                    return NodeEvaluatorCache.trim(ratio, dryRun);
                }

                // Not marked expensive on purpose: a dropped evaluator is simply allocated again by the
                // next search, which is what a pool miss always does. This is the cache to shave first.
            });

            if (OffHeapChunkPayloadCache.enabled()) {
                final OffHeapChunkPayloadCache payloads = OffHeapChunkPayloadCache.instance();
                created.register(new Trimmable() {
                    @Override
                    public String id() {
                        return "offheap-chunk-payloads";
                    }

                    @Override
                    public long retainedBytes() {
                        return payloads.usedBytes();
                    }

                    @Override
                    public int size() {
                        return (int) Math.min(Integer.MAX_VALUE, payloads.size());
                    }

                    @Override
                    public long hits() {
                        return payloads.hits();
                    }

                    @Override
                    public long trim(final double ratio, final boolean dryRun) {
                        return payloads.trim(ratio, dryRun);
                    }

                    @Override
                    public boolean expensiveToRefill() {
                        // Re-serialising a chunk is real work (and it happens on the join path), so this
                        // cache is only trimmed above the hard watermark.
                        return true;
                    }
                });
            }

            // --- per-player session state ------------------------------------------------------
            CLEANER.register(new PlayerDataCleaner.PlayerScoped() {
                @Override
                public String id() {
                    return "do-a-barrel-roll";
                }

                @Override
                public int forget(final UUID playerId, final Object handle) {
                    return handle instanceof ServerPlayer player ? DoABarrelRollProtocol.forget(player) : 0;
                }
            });
            CLEANER.register(new PlayerDataCleaner.PlayerScoped() {
                @Override
                public String id() {
                    return "leaves-appleskin";
                }

                @Override
                public int forget(final UUID playerId, final Object handle) {
                    return handle instanceof ServerPlayer player
                        ? org.leavesmc.leaves.protocol.AppleSkinProtocol.forget(player)
                        : org.leavesmc.leaves.protocol.AppleSkinProtocol.forget(playerId);
                }

                @Override
                public Collection<UUID> tracked() {
                    return org.leavesmc.leaves.protocol.AppleSkinProtocol.trackedPlayers();
                }
            });
            CLEANER.register(new PlayerDataCleaner.PlayerScoped() {
                @Override
                public String id() {
                    return "leaves-asteorbar";
                }

                @Override
                public int forget(final UUID playerId, final Object handle) {
                    return handle instanceof ServerPlayer player
                        ? org.leavesmc.leaves.protocol.AsteorBarProtocol.forget(player)
                        : org.leavesmc.leaves.protocol.AsteorBarProtocol.forget(playerId);
                }

                @Override
                public Collection<UUID> tracked() {
                    return org.leavesmc.leaves.protocol.AsteorBarProtocol.trackedPlayers();
                }
            });
            CLEANER.register(new PlayerDataCleaner.PlayerScoped() {
                @Override
                public String id() {
                    return "leaves-jade";
                }

                @Override
                public int forget(final UUID playerId, final Object handle) {
                    return handle instanceof ServerPlayer player
                        ? org.leavesmc.leaves.protocol.jade.JadeProtocol.forget(player)
                        : org.leavesmc.leaves.protocol.jade.JadeProtocol.forget(playerId);
                }

                @Override
                public Collection<UUID> tracked() {
                    return org.leavesmc.leaves.protocol.jade.JadeProtocol.trackedPlayers();
                }
            });

            janitor = created;
            installed = true;
            System.out.println(PREFIX + "RAM janitor: soft " + RamJanitorConfig.softWatermarkPercent
                + "% / hard " + RamJanitorConfig.hardWatermarkPercent + "% / emergency "
                + RamJanitorConfig.emergencyWatermarkPercent + "%, cooldown "
                + RamJanitorConfig.minIntervalSeconds + " s, budget " + RamJanitorConfig.maxPassMillis
                + " ms, " + created.caches().size() + " cache(s), " + CLEANER.holders().size()
                + " per-player holder(s)"
                + (RamJanitorConfig.dryRun ? " [dry run]" : ""));
        }
    }

    /**
     * Called once per server tick from {@code Protocols.tickServer}.
     */
    public static void tickServer(final MinecraftServer server) {
        if (!RamJanitorConfig.enabled && !RamJanitorConfig.purgePlayerData) {
            return;
        }
        install();
        final long tick = server.getTickCount();

        if (RamJanitorConfig.enabled) {
            final Runtime runtime = Runtime.getRuntime();
            final long max = runtime.maxMemory();
            final long used = runtime.totalMemory() - runtime.freeMemory();
            final long gc = gcCount();
            final RamJanitor.Pass pass = janitor.tick(tick, used, max,
                LagCompensation.TPSCalculator.getMSPT(), gc);
            if (pass != null && RamJanitorConfig.logPasses) {
                System.out.println(PREFIX + "RAM janitor " + pass.summary());
            }
        }

        if (RamJanitorConfig.purgePlayerData) {
            final long interval = Math.max(1L, RamJanitorConfig.sweepIntervalSeconds * 20L);
            if (tick % interval == 0L) {
                sweep(server);
            }
            checkWatches(tick);
        }
    }

    /**
     * Called from {@code Protocols.disconnected}, i.e. inside {@code PlayerList.remove}, for every player
     * that leaves - including kicks and shutdowns.
     */
    public static void playerLeft(final ServerPlayer player) {
        if (player == null || !RamJanitorConfig.purgePlayerData) {
            return;
        }
        install();

        final UUID playerId = player.getUUID();
        final String name = player.getScoreboardName();
        final PlayerDataCleaner.Purge purge = CLEANER.purge(playerId, player);
        if (!purge.isEmpty() && RamJanitorConfig.logPasses) {
            System.out.println(PREFIX + "Player data: dropped " + purge.summary() + " for " + name);
        }

        if (RamJanitorConfig.traceRetainedPlayers) {
            final MinecraftServer server = MinecraftServer.getServer();
            final long tick = server == null ? 0L : server.getTickCount();
            CLEANER.watch(playerId, name, player, tick,
                Math.max(1L, RamJanitorConfig.retentionGraceSeconds * 20L));
        }
    }

    private static void sweep(final MinecraftServer server) {
        final List<ServerPlayer> players = server.getPlayerList().getPlayers();
        final List<UUID> online = new ArrayList<>(players.size());
        for (int i = 0; i < players.size(); i++) {
            online.add(players.get(i).getUUID());
        }
        final PlayerDataCleaner.Purge purge = CLEANER.sweep(online);
        if (!purge.isEmpty() && RamJanitorConfig.logPasses) {
            System.out.println(PREFIX + "Player data sweep: dropped " + purge.summary()
                + " that outlived its session");
        }
    }

    private static void checkWatches(final long tick) {
        final List<PlayerDataCleaner.Watch> due = CLEANER.due(tick);
        if (due.isEmpty()) {
            return;
        }
        for (int i = 0; i < due.size(); i++) {
            final PlayerDataCleaner.Watch watch = due.get(i);
            if (watch.released()) {
                CLEANER.release(watch);
                continue;
            }
            // The object survived the grace period. Purge again - a holder may have re-added it during
            // the disconnect - and say so, because that is the only symptom a real leak ever has.
            final PlayerDataCleaner.Purge again = CLEANER.purge(watch.playerId(), null);
            System.out.println(PREFIX + "Player data: " + watch.playerName()
                + " is still retained " + RamJanitorConfig.retentionGraceSeconds
                + " s after quitting; re-purged " + again.summary()
                + (watch.attempts() >= 2 ? " - this is a leak, please report it with the cache list" : ""));
            CLEANER.reschedule(watch, tick, 400L);
        }
    }

    /**
     * @return total collections of every collector, or {@code -1} when the VM does not report them
     */
    private static long gcCount() {
        long total = -1L;
        for (final GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            final long count = bean.getCollectionCount();
            if (count >= 0L) {
                total = Math.max(0L, total) + count;
            }
        }
        return total;
    }

    /**
     * @return the report printed by {@code /leaf ram}
     */
    public static List<String> describe() {
        install();
        final List<String> lines = new ArrayList<>(12);

        final Runtime runtime = Runtime.getRuntime();
        final long max = runtime.maxMemory();
        final long used = runtime.totalMemory() - runtime.freeMemory();
        lines.add(String.format(java.util.Locale.ROOT,
            "heap %.1f%% (%d MiB used of %d MiB max)",
            100.0D * used / Math.max(1L, max), used / (1024L * 1024L), max / (1024L * 1024L)));
        lines.add("watermarks soft " + RamJanitorConfig.softWatermarkPercent + "% / hard "
            + RamJanitorConfig.hardWatermarkPercent + "% / emergency "
            + RamJanitorConfig.emergencyWatermarkPercent + "%, cooldown "
            + RamJanitorConfig.minIntervalSeconds + " s, budget " + RamJanitorConfig.maxPassMillis + " ms"
            + (RamJanitorConfig.dryRun ? ", dry run" : ""));

        final RamJanitor.Pass last = janitor.lastPass();
        lines.add(last == null ? "janitor: no pass yet" : "janitor: " + last.summary());
        lines.add("janitor totals: " + janitor.passCount() + " passes, released "
            + String.format(java.util.Locale.ROOT, "%.1f MiB", janitor.totalFreedBytes() / (1024.0D * 1024.0D)));

        for (final Trimmable cache : janitor.caches()) {
            lines.add("  " + cache.id() + ": " + (cache.retainedBytes() / (1024L * 1024L)) + " MiB, "
                + cache.size() + " entries, " + cache.hits() + " hits"
                + (cache.expensiveToRefill() ? ", refill is expensive" : ""));
        }

        lines.add("players: " + CLEANER.describe());
        lines.add("  watched quit players: " + CLEANER.watchCount());
        if (!CLEANER.lastReport().isEmpty()) {
            lines.add("  last: " + CLEANER.lastReport());
        }
        return lines;
    }

    /**
     * @return one line per subsystem, printed at shutdown next to the other Leaf cache reports
     */
    public static String shutdownReport() {
        if (!installed) {
            return "idle";
        }
        return janitor.passCount() + " passes, " + (janitor.totalFreedBytes() / (1024L * 1024L))
            + " MiB released; " + CLEANER.describe();
    }

    /**
     * @return the janitor, installed on first use, so the command can force a pass
     */
    public static RamJanitor janitor() {
        install();
        return janitor;
    }

    /**
     * @return the cleaner, so the command can run a sweep on demand
     */
    public static PlayerDataCleaner cleaner() {
        return CLEANER;
    }
}
