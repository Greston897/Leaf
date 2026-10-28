package org.dreeam.leaf.async.chunk;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.minecraft.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AsyncChunkSend {

    /**
     * Leaf - bound of the async chunk send queue.
     * <p>
     * The pool used to be backed by an unbounded {@link LinkedBlockingQueue}. A single join with a
     * large view distance enqueues one task per chunk (and every task holds a fully built chunk
     * packet until it is sent), so the queue could grow by tens of thousands of packets - hundreds of
     * megabytes - whenever the socket was slower than the chunk loader. That is the "join spikes and
     * the RSS jumped" pattern.
     * <p>
     * A bounded queue plus {@link ThreadPoolExecutor.CallerRunsPolicy} turns that into backpressure:
     * when the queue is full the packet is built and sent on the calling thread, i.e. exactly the
     * behaviour of a server with the option disabled. Nothing is dropped, nothing is reordered - the
     * queue is drained by a single thread, so packet order is preserved either way.
     * <p>
     * Tune with {@code -Dleaf.asyncChunkSend.queueSize=<n>} (0 = unbounded, the old behaviour).
     */
    private static final int QUEUE_SIZE = Integer.getInteger("leaf.asyncChunkSend.queueSize", 16384);

    public static final ExecutorService POOL = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        queue(),
        new ThreadFactoryBuilder()
            .setPriority(Thread.NORM_PRIORITY)
            .setNameFormat("Leaf Async Chunk Send Thread")
            .setUncaughtExceptionHandler(Util::onThreadException)
            .setThreadFactory(AsyncChunkSendThread::new)
            .build(),
        new ThreadPoolExecutor.CallerRunsPolicy()
    );
    public static final Logger LOGGER = LogManager.getLogger("Leaf Async Chunk Send");

    private static LinkedBlockingQueue<Runnable> queue() {
        return QUEUE_SIZE > 0 ? new LinkedBlockingQueue<>(QUEUE_SIZE) : new LinkedBlockingQueue<>();
    }
}
