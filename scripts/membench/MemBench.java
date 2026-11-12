import org.dreeam.leaf.util.mem.OffHeapDoubleList;
import org.dreeam.leaf.util.mem.RustyPool;
import org.dreeam.leaf.util.mem.TickArena;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;

/**
 * Standalone benchmark: classic per-tick heap churn vs. Rust-style arena
 * cleanup, simulating the entity-collision scratch data produced every tick
 * (AABB candidate lists — six doubles per box — plus a few vector triples,
 * roughly what Entity.collide / voxel-shape gathering allocates per entity).
 *
 * Usage:
 *   MemBench classic|arena|pooled|all [ticks] [entities]
 *
 * The process prints wall time, GC collection count delta, and bytes
 * allocated on the Java heap (via ThreadMXBean), per mode.
 */
public final class MemBench {

    private static final int BOXES_PER_ENTITY = 4;
    private static final int VECS_PER_ENTITY = 3;

    private MemBench() {
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "all";
        int ticks = args.length > 1 ? Integer.parseInt(args[1]) : 20_000;
        int entities = args.length > 2 ? Integer.parseInt(args[2]) : 500;

        System.out.printf("ticks=%d entities=%d (simulated collision scratch per tick)%n%n", ticks, entities);

        if (mode.equals("classic") || mode.equals("all")) {
            run("classic", ticks, entities, MemBench::classicTick);
        }
        if (mode.equals("arena") || mode.equals("all")) {
            TickArena arena = new TickArena();
            run("arena ", ticks, entities, (t, e) -> arenaTick(arena, t, e));
            System.out.printf("         arena reserved off-heap: %.1f MiB in %d chunk(s)%n",
                arena.capacityBytes() / 1048576.0, arena.chunkCount());
            arena.close();
        }
        if (mode.equals("pooled") || mode.equals("all")) {
            RustyPool<double[][]> pool = new RustyPool<>(
                () -> new double[BOXES_PER_ENTITY][6],
                arr -> {
                },
                8);
            run("pooled", ticks, entities, (t, e) -> pooledTick(pool, t, e));
            System.out.printf("         pool size at end: %d%n", pool.pooled());
        }
    }

    private interface Tick {
        void run(int tick, int entities);
    }

    // Sink so the JIT cannot eliminate the work.
    private static double sink;

    /** The way vanilla/Leaf code allocates today: scratch objects per entity per tick. */
    private static void classicTick(int tick, int entities) {
        for (int e = 0; e < entities; e++) {
            ArrayList<double[]> boxes = new ArrayList<>(BOXES_PER_ENTITY);
            ArrayList<double[]> vecs = new ArrayList<>(VECS_PER_ENTITY);
            for (int i = 0; i < BOXES_PER_ENTITY; i++) {
                boxes.add(new double[]{e, tick, i, e + 1, tick + 1, i + 1});
            }
            for (int i = 0; i < VECS_PER_ENTITY; i++) {
                vecs.add(new double[]{e * 0.5, tick * 0.5, i * 0.5});
            }
            double acc = 0;
            for (double[] b : boxes) {
                acc += b[0] + b[5];
            }
            for (double[] v : vecs) {
                acc += v[2];
            }
            sink = acc;
        }
    }

    /** Rust-style: one bump arena per thread; the whole tick's scratch dies in O(1). */
    private static void arenaTick(TickArena arena, int tick, int entities) {
        try (TickArena.Scope ignored = arena.scope()) {
            for (int e = 0; e < entities; e++) {
                OffHeapDoubleList scratch = new OffHeapDoubleList(arena, 42);
                for (int i = 0; i < BOXES_PER_ENTITY; i++) {
                    scratch.addBox(e, tick, i, e + 1, tick + 1, i + 1);
                }
                for (int i = 0; i < VECS_PER_ENTITY; i++) {
                    scratch.addVec(e * 0.5, tick * 0.5, i * 0.5);
                }
                double acc = 0;
                for (int i = 0; i < scratch.size(); i += 6) {
                    acc += scratch.getDouble(i);
                }
                sink = acc;
            }
        }
    }

    /** Rust-style ownership for on-heap objects: lease, use, deterministically release. */
    private static void pooledTick(RustyPool<double[][]> pool, int tick, int entities) {
        for (int e = 0; e < entities; e++) {
            double acc = 0;
            try (RustyPool<double[][]>.Lease lease = pool.lease()) {
                double[][] boxes = lease.value();
                for (int i = 0; i < BOXES_PER_ENTITY; i++) {
                    double[] b = boxes[i];
                    b[0] = e;
                    b[5] = i + 1;
                    acc += b[0] + b[5];
                }
            }
            sink = acc;
        }
    }

    private static void run(String label, int ticks, int entities, Tick body) {
        // Warmup so JIT effects don't drown the signal.
        for (int t = 0; t < Math.min(2_000, ticks / 4); t++) {
            body.run(t, entities);
        }

        long gcBefore = gcCount();
        long allocBefore = allocatedBytes();
        long start = System.nanoTime();

        for (int t = 0; t < ticks; t++) {
            body.run(t, entities);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long gcDelta = gcCount() - gcBefore;
        double allocMb = (allocatedBytes() - allocBefore) / 1048576.0;

        System.out.printf("%s  wall=%6d ms   gc=%4d collections   heap-alloc=%10.1f MiB%n",
            label, elapsedMs, gcDelta, allocMb);
    }

    private static long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = bean.getCollectionCount();
            if (c > 0) {
                total += c;
            }
        }
        return total;
    }

    private static long allocatedBytes() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean hotSpot) {
            if (hotSpot.isThreadAllocatedMemorySupported() && !hotSpot.isThreadAllocatedMemoryEnabled()) {
                hotSpot.setThreadAllocatedMemoryEnabled(true);
            }
            return hotSpot.getThreadAllocatedBytes();
        }
        return -1; // non-HotSpot JVM: allocation counter unavailable
    }

    static {
        // Escape the sink so nothing is optimized away.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (sink == Double.MIN_VALUE) {
                System.out.print(".");
            }
        }));
    }
}
