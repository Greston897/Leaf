/**
 * Rust-style deterministic memory management for Leaf's hottest tick paths.
 *
 * <p>The JVM cannot give us Rust's ownership system, but the two things that
 * actually make Rust servers cheap to run are reproducible:</p>
 * <ol>
 *   <li><b>Arena (bump) allocation for tick-scoped temporaries</b> —
 *       {@link org.dreeam.leaf.util.mem.TickArena} with
 *       {@link org.dreeam.leaf.util.mem.OffHeapLongList} /
 *       {@link org.dreeam.leaf.util.mem.OffHeapDoubleList}: everything a tick
 *       allocates lives off-heap and is released in O(1) at scope end.
 *       Young-gen GC for these paths drops to ~zero.</li>
 *   <li><b>Deterministic release for objects that must stay on-heap</b> —
 *       {@link org.dreeam.leaf.util.mem.RustyPool} and
 *       {@link org.dreeam.leaf.util.mem.BufferPool}: RAII leases return
 *       objects/buffers to bounded pools instead of leaving them for the GC.</li>
 * </ol>
 *
 * <p>Steady-state result: the tick loop allocates nothing, so the collector
 * has nothing to collect — the practical way to "switch the GC off" without
 * leaving the JVM.</p>
 */
package org.dreeam.leaf.util.mem;
