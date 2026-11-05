package org.dreeam.leaf.async.path;

import ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import org.apache.commons.lang.Validate;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leaf - a pool of reusable {@link NodeEvaluator}s for async pathfinding.
 * <p>
 * <b>Why the bound.</b> A {@code NodeEvaluator} is not a small object: it carries the node arrays of
 * the last search and a path type cache, tens of kilobytes per instance. The pool used to keep
 * <b>every</b> returned evaluator forever, in one queue per feature combination, and nothing ever
 * shrank those queues - a server with many mob types pathfinding in parallel ended up holding hundreds
 * of idle evaluators, each with its own search buffers, for the rest of the uptime. The pool is a cache,
 * not an owner: dropping an evaluator past the cap only means the next request builds a fresh one.
 * <p>
 * Two caps, both configurable: at most {@code leaf.pathfinding.maxCachedEvaluators} idle evaluators per
 * feature combination (default 8) and at most {@code leaf.pathfinding.maxCachedFeatureSets} combinations
 * themselves (default 32). Both are deliberately small - a queue longer than the number of pathfinding
 * threads can never be consumed, and the number of distinct feature sets is bounded by the number of mob
 * types anyway.
 */
public class NodeEvaluatorCache {

    private static final int MAX_CACHED_PER_FEATURE_SET =
        Integer.getInteger("leaf.pathfinding.maxCachedEvaluators", 8);
    private static final int MAX_FEATURE_SETS =
        Integer.getInteger("leaf.pathfinding.maxCachedFeatureSets", 32);

    /** Leaf - a parked evaluator holds the node arrays of its last search, tens of kilobytes */
    private static final long ESTIMATED_BYTES_PER_EVALUATOR = 24L * 1024L;

    private static final Map<NodeEvaluatorFeatures, MultiThreadedQueue<NodeEvaluator>> threadLocalNodeEvaluators = new ConcurrentHashMap<>();
    private static final Map<NodeEvaluator, NodeEvaluatorGenerator> nodeEvaluatorToGenerator = new ConcurrentHashMap<>();

    /** idle evaluators currently parked in the pool */
    private static final AtomicLong cachedEvaluators = new AtomicLong();
    /** evaluators dropped because their queue was full */
    private static final AtomicLong droppedEvaluators = new AtomicLong();
    /** evaluations that were served from the pool instead of building a fresh evaluator */
    private static final AtomicLong reuseHits = new AtomicLong();

    private static @NotNull MultiThreadedQueue<NodeEvaluator> getQueueForFeatures(@NotNull NodeEvaluatorFeatures nodeEvaluatorFeatures) {
        return threadLocalNodeEvaluators.computeIfAbsent(nodeEvaluatorFeatures, key -> new MultiThreadedQueue<>());
    }

    public static @NotNull NodeEvaluator takeNodeEvaluator(@NotNull NodeEvaluatorGenerator generator, @NotNull NodeEvaluator localNodeEvaluator) {
        final NodeEvaluatorFeatures nodeEvaluatorFeatures = NodeEvaluatorFeatures.fromNodeEvaluator(localNodeEvaluator);
        NodeEvaluator nodeEvaluator = getQueueForFeatures(nodeEvaluatorFeatures).poll();

        if (nodeEvaluator == null) {
            nodeEvaluator = generator.generate(nodeEvaluatorFeatures);
        } else {
            cachedEvaluators.decrementAndGet();
            reuseHits.incrementAndGet(); // Leaf - hit accounting for the RAM janitor
        }

        nodeEvaluatorToGenerator.put(nodeEvaluator, generator);

        return nodeEvaluator;
    }

    public static void returnNodeEvaluator(@NotNull NodeEvaluator nodeEvaluator) {
        final NodeEvaluatorGenerator generator = nodeEvaluatorToGenerator.remove(nodeEvaluator);
        Validate.notNull(generator, "NodeEvaluator already returned");

        final NodeEvaluatorFeatures nodeEvaluatorFeatures = NodeEvaluatorFeatures.fromNodeEvaluator(nodeEvaluator);
        // Leaf start - bound the pool: a queue longer than the number of pathfinding threads can never
        // be drained, and an unbounded feature set map keeps evaluators alive forever
        if (threadLocalNodeEvaluators.size() > MAX_FEATURE_SETS) {
            droppedEvaluators.incrementAndGet();
            return;
        }
        final MultiThreadedQueue<NodeEvaluator> queue = getQueueForFeatures(nodeEvaluatorFeatures);
        if (queue.size() >= MAX_CACHED_PER_FEATURE_SET) {
            droppedEvaluators.incrementAndGet();
            return;
        }
        cachedEvaluators.incrementAndGet();
        queue.offer(nodeEvaluator);
        // Leaf end - bound the pool
    }

    public static void removeNodeEvaluator(@NotNull NodeEvaluator nodeEvaluator) {
        nodeEvaluatorToGenerator.remove(nodeEvaluator);
    }

    /**
     * @return the number of idle evaluators currently parked in the pool
     */
    public static int parkedEvaluators() {
        final long parked = cachedEvaluators.get();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, parked));
    }

    /**
     * @return the estimated heap held by the parked evaluators
     */
    public static long estimatedBytes() {
        return (long) parkedEvaluators() * ESTIMATED_BYTES_PER_EVALUATOR;
    }

    /**
     * @return how many path searches were served from the pool, used as the janitor's hit counter
     */
    public static long reuseHits() {
        return reuseHits.get();
    }

    /**
     * Leaf - drop a share of the parked evaluators.
     * <p>
     * An evaluator is a cache entry, not a resource: dropping one means the next search builds a fresh
     * one, which is exactly what would have happened on a pool miss. The pool is a queue per feature
     * combination, so the shed walks those queues and discards the entries at their head.
     *
     * @param ratio  share of the parked evaluators to drop, {@code (0, 1]}
     * @param dryRun when {@code true} only the effect is estimated
     * @return the estimated heap bytes released, assuming the node arrays of the last search
     */
    public static long trim(final double ratio, final boolean dryRun) {
        final int parked = parkedEvaluators();
        if (parked <= 0 || ratio <= 0.0D) {
            return 0L;
        }
        final int target = Math.max(1, (int) Math.round(parked * Math.min(1.0D, ratio)));
        if (dryRun) {
            return (long) target * ESTIMATED_BYTES_PER_EVALUATOR;
        }

        int dropped = 0;
        for (final MultiThreadedQueue<NodeEvaluator> queue : threadLocalNodeEvaluators.values()) {
            if (dropped >= target) {
                break;
            }
            while (dropped < target) {
                final NodeEvaluator evaluator = queue.poll();
                if (evaluator == null) {
                    break;
                }
                cachedEvaluators.decrementAndGet();
                droppedEvaluators.incrementAndGet();
                dropped++;
            }
        }
        return (long) dropped * ESTIMATED_BYTES_PER_EVALUATOR;
    }

    /**
     * Leaf - pool statistics for the shutdown report: parked evaluators, drops and feature sets.
     */
    public static String stats() {
        return cachedEvaluators.get() + " idle node evaluators, " + droppedEvaluators.get()
            + " dropped, " + threadLocalNodeEvaluators.size() + " feature sets (cap "
            + MAX_CACHED_PER_FEATURE_SET + "/set, " + MAX_FEATURE_SETS + " sets)";
    }
}
