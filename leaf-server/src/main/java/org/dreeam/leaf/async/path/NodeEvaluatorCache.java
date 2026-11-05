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

    private static final Map<NodeEvaluatorFeatures, MultiThreadedQueue<NodeEvaluator>> threadLocalNodeEvaluators = new ConcurrentHashMap<>();
    private static final Map<NodeEvaluator, NodeEvaluatorGenerator> nodeEvaluatorToGenerator = new ConcurrentHashMap<>();

    /** idle evaluators currently parked in the pool */
    private static final AtomicLong cachedEvaluators = new AtomicLong();
    /** evaluators dropped because their queue was full */
    private static final AtomicLong droppedEvaluators = new AtomicLong();

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
     * Leaf - pool statistics for the shutdown report: parked evaluators, drops and feature sets.
     */
    public static String stats() {
        return cachedEvaluators.get() + " idle node evaluators, " + droppedEvaluators.get()
            + " dropped, " + threadLocalNodeEvaluators.size() + " feature sets (cap "
            + MAX_CACHED_PER_FEATURE_SET + "/set, " + MAX_FEATURE_SETS + " sets)";
    }
}
