# membench — classic GC churn vs. Rust-style arena cleanup

Standalone microbenchmark for `org.dreeam.leaf.util.mem` (no Minecraft
dependencies: compiles with plain `javac`, JDK 21+).

## Build & run

```bash
cd scripts/membench
mkdir -p out
javac -d out ../../leaf-server/src/main/java/org/dreeam/leaf/util/mem/*.java MemBench.java
java -Xmx256m -cp out MemBench all
# or with custom workload: MemBench all <ticks> <entities>
```

What to look at in the output:

* `gc=... collections` — with the arena/pool modes this stays at ~0; classic
  mode racks up young-GC runs (each one is a potential MSPT spike).
* `heap-alloc=...` — arena mode's Java-heap allocation approaches zero after
  warmup; the scratch lives off-heap and dies in O(1) per tick.

## The "no GC at all" stress test (Epsilon GC)

```bash
java -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xmx96m -cp out MemBench arena
java -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xmx96m -cp out MemBench classic
```

Epsilon GC never reclaims anything. The arena run completes: its steady-state
heap allocation is zero. The classic run dies with OOM — that contrast is the
whole point: if the tick loop allocates nothing, GC frequency and its pauses
stop mattering.

## Recommended JVM flags for the real server (JDK 21)

```bash
-Xms8G -Xmx8G                          # fix heap size, avoid resize pauses
-XX:+UseZGC -XX:+ZGenerational         # sub-millisecond pauses
# or, stay on G1 but make youth cheap:
# -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1NewSizePercent=20
-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags   # always watch the GC log
```

The flags are seatbelts; the arena/pool discipline in the tick loop is the
actual fix.

## Where to wire this in Leaf (integration points)

* `org.dreeam.leaf.world.EntityCollisionCache` — per-entity collision scratch
  (`ObjectArrayList<VoxelShape>`/`AABB`) → arena-backed `OffHeapDoubleList`.
* `org.dreeam.leaf.async.tracker.TrackerCtx` — per-connection packet lists and
  per-entity `StartSeen`/`StopSeen` records per tick → `RustyPool` leases.
* `org.dreeam.leaf.async.path` — vanilla `PathFinder` allocates thousands of
  `Node` objects per search on workers → one `TickArena` per worker thread.
* `me.earthme.luminol.data.BufferedLinearRegionFile` (`writeChunk`,
  `acquireWriteLock` path) — `ByteBuffer.allocate(...)` per chunk save →
  `BufferPool.lease`; removes humongous on-heap allocations during saves.
