package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

/**
 * Leaf - settings of the RAM janitor and of the player data cleaner.
 * <p>
 * All defaults are conservative: the janitor only works above 70 % heap usage, spends at most 2 ms per
 * pass and never runs more than once every 15 seconds. Nothing here changes gameplay - the janitor only
 * ever drops caches that can be rebuilt, and the player cleaner only drops session state of players that
 * are no longer connected.
 */
public class RamJanitorConfig extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.MISC.getBaseKeyName() + ".ram-janitor";
    }

    /** master switch for the pressure driven cache trimming */
    public static boolean enabled = true;
    /** drop per-player state when a player leaves, and sweep leftovers for players that are gone */
    public static boolean purgePlayerData = true;
    /** log one line per trimming pass */
    public static boolean logPasses = true;
    /** watch quit players through a weak reference and report the ones the server still holds */
    public static boolean traceRetainedPlayers = true;

    /** heap usage above which trimming is allowed */
    public static int softWatermarkPercent = 70;
    /** heap usage above which caches that are expensive to refill are trimmed as well */
    public static int hardWatermarkPercent = 90;
    /** heap usage above which the cooldown is ignored */
    public static int emergencyWatermarkPercent = 95;

    /** minimum time between two passes, in seconds */
    public static int minIntervalSeconds = 15;
    /** time budget of a single pass, in milliseconds */
    public static int maxPassMillis = 2;
    /** upper bound on how many caches one pass may visit */
    public static int maxCachesPerPass = 6;
    /** tick time above which a pass is postponed, in milliseconds */
    public static int maxMspt = 45;

    /** how often leftover per-player data is swept, in seconds */
    public static int sweepIntervalSeconds = 30;
    /** how long a quit player object may stay reachable before it is reported, in seconds */
    public static int retentionGraceSeconds = 10;

    /** when true the janitor only reports what it would do */
    public static boolean dryRun = false;

    private static int clamp(final int value, final int min, final int max) {
        return value < min ? min : Math.min(value, max);
    }

    @Override
    public void onLoaded() {
        config.addCommentRegionBased(getBasePath(), """
                RAM janitor: the server trims its own caches when the heap is under pressure.
                It never touches anything it cannot rebuild, it never runs below the soft watermark,
                and it stops after a small time budget so it cannot make a tick late.
                The player section drops the session state of players that left the server.""",
            """
                内存清理: 当堆内存压力过大时, 服务器会清理自己的缓存.
                它不会触碰任何无法重建的数据, 不会在低于软水位线时运行,
                并且会在很小的耗时预算内停止, 因此不会导致 tick 变慢.
                玩家部分会清理已离开服务器的玩家的会话数据.""");

        enabled = config.getBoolean(getBasePath() + ".enabled", enabled);
        purgePlayerData = config.getBoolean(getBasePath() + ".purge-player-data", purgePlayerData);
        logPasses = config.getBoolean(getBasePath() + ".log-passes", logPasses);
        traceRetainedPlayers = config.getBoolean(getBasePath() + ".trace-retained-players", traceRetainedPlayers);
        dryRun = config.getBoolean(getBasePath() + ".dry-run", dryRun);

        softWatermarkPercent = clamp(config.getInt(getBasePath() + ".soft-watermark-percent",
            softWatermarkPercent), 40, 95);
        hardWatermarkPercent = clamp(config.getInt(getBasePath() + ".hard-watermark-percent",
            hardWatermarkPercent), 50, 99);
        emergencyWatermarkPercent = clamp(config.getInt(getBasePath() + ".emergency-watermark-percent",
            emergencyWatermarkPercent), 60, 100);
        if (hardWatermarkPercent <= softWatermarkPercent) {
            hardWatermarkPercent = Math.min(99, softWatermarkPercent + 10);
        }
        if (emergencyWatermarkPercent <= hardWatermarkPercent) {
            emergencyWatermarkPercent = Math.min(100, hardWatermarkPercent + 5);
        }

        minIntervalSeconds = clamp(config.getInt(getBasePath() + ".min-interval-seconds", minIntervalSeconds), 1, 3600);
        maxPassMillis = clamp(config.getInt(getBasePath() + ".max-pass-millis", maxPassMillis), 1, 50);
        maxCachesPerPass = clamp(config.getInt(getBasePath() + ".max-caches-per-pass", maxCachesPerPass), 1, 64);
        maxMspt = clamp(config.getInt(getBasePath() + ".max-mspt", maxMspt), 1, 1000);

        sweepIntervalSeconds = clamp(config.getInt(getBasePath() + ".sweep-interval-seconds", sweepIntervalSeconds), 5, 3600);
        retentionGraceSeconds = clamp(config.getInt(getBasePath() + ".retention-grace-seconds",
            retentionGraceSeconds), 1, 300);
    }
}
