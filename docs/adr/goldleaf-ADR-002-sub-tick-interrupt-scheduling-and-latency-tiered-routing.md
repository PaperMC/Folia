# goldleaf-ADR-002 — Sub-Tick Interrupt Scheduling and Latency-Tiered Read/Dispatch Routing

**Status:** Accepted (Implemented)
**Date:** 2026-11-21

## Context

### 1. The Async-to-Sync Hop Latency Gap on Folia

Plugins that rely on `sync -> async -> sync` pipelines—such as asynchronous teleportation (`LeafRTP`), cross-server handoffs, interactive menus, claim checks, and spatial scanners—experience noticeably higher round-trip latency on Folia than on Paper, even when the target region's tick thread is idle for 80%–95% of each 50 ms tick window.

Inspection of Folia `26.3.x` (`0004-Region-Threading-Base.patch` and `0002-Region-Threading-Base.patch`) shows that Folia **already possesses an internal inter-tick wakeup mechanism**, but exposes it unevenly across its public scheduler and chunk-read APIs:

1. **Internal interrupt path exists (`TickRegionScheduler#setHasTasks` -> `notifyTasks`):**
   - `RegionizedTaskQueue#queueTickTaskQueue(...)` marks `regionData.setHasTasks()`, which calls `TickRegionScheduler#setHasTasks(tickHandle)` -> `notifyTasks(region)` to unpark an idle `TickThreadRunner`.
   - The woken runner acquires the idle region (`tryMarkTicking()`) and drains intermediate tasks via `ConcreteRegionTickHandle#runRegionTasks(BooleanSupplier)` **between ticks** (sub-millisecond latency).
2. **Public scheduler APIs default to tick-gated queues:**
   - `FoliaRegionScheduler#run(...)` delegates to `runDelayed(..., 1)`, enqueueing into tick-counted `SCHEDULER_DATA` that only drains on the next `runTick()` (~25 ms average, up to 50 ms). Only `RegionScheduler#execute(...)` enters `queueTickTaskQueue(...)` directly.
   - `FoliaEntityScheduler` and `FoliaGlobalRegionScheduler` drain exclusively inside `tickRegion()` / `globalTick()`, with no opt-in inter-tick interrupt path.
3. **Synchronous `ChunkSnapshot` creation copy and hop tax:**
   - While reading an already-created `ChunkSnapshot` is async-safe on both Paper and Folia, *creating* one (`CraftChunk#getChunkSnapshot(...)`) must execute on the owning region thread (`TickThread.ensureTickThread`) and allocates/copies palettes, bit-storage arrays, heightmaps, and biomes (~30–80 KiB per chunk).
4. **Cold-region activation overhead:**
   - Queueing a task against an unloaded chunk attaches `RegionizedTaskQueue.TASK_QUEUE_TICKET` and forces regionizer activation even when the caller only needs a read-only block/biome probe.
5. **CPU cache isolation and future cross-machine sharding (`LeafShard`) constraints:**
   - Allowing arbitrary async threads to lock or read a live mutating chunk's `dirtyOverlay` causes MESI `Modified <-> Shared` cache-line bouncing against the owning region thread's L1 writes.
   - Exposing synchronous direct-pointer read APIs breaks transparency once chunks are distributed across remote `LeafShard` nodes.

## Decision

GoldLeaf adopts a **two-part latency architecture**—opt-in sub-tick interrupt scheduling paired with a cache-line-isolated, async-first latency-tiered router—strictly split into **Layer 1 (upstream-mergeable into `PaperMC/Folia` and `PaperMC/Paper`)** and **Layer 2 (GoldLeaf / `LeafAnvil` / `LeafShard` SPI wiring)** per `goldleaf-ADR-001`.

### 1. Opt-In Interrupt-Driven Inter-Tick Activation (Layer 1 — Upstream-Candidate)

1. **Immediate inter-tick dispatch for `EntityScheduler` and `GlobalRegionScheduler`:**
   - Add opt-in immediate inter-tick execution methods (e.g., `EntityScheduler#executeImmediate(...)` / `GlobalRegionScheduler#executeImmediate(...)`) alongside existing `RegionScheduler#execute(...)`.
   - Existing `run(...)` / `runDelayed(...)` methods shall retain strict next-tick semantics so plugins relying on tick-boundary deferral remain 100% compatible.
   - On `EntityScheduler`, the inter-tick runner shall re-verify entity ownership and non-retirement on the owning region thread before invocation (re-routing or running the retired callback if the entity transferred regions or logged out while queued).
2. **Wakeup coalescing and short-slice scheduling priority:**
   - Coalesce `TickRegionScheduler#notifyTasks(...)` wakeups on `0 -> 1` queue transitions (or a bounded micro-batch window, e.g., `~50–100 µs` under burst load) to avoid `LockSupport.unpark` / futex thrashing during concurrent bursts.
   - Under `EDF` and `WORK_STEALING`, allow bounded short-slice `runTasks()` drains (e.g., `<= 500 µs`) to preempt starting a fresh full tick when tick threads are contended, preventing `p99` hop starvation without starving simulation.
3. **Optional read-only budgeted mid-tick safe-point drain:**
   - For busy regions mid-tick (e.g., 20 ms into a heavy redstone tick), expose an opt-in **strictly read-only** high-priority query lane drained at clean vanilla phase boundaries (between block ticks, entity ticks, and packet flush) under a hard micro-budget (`<= 250–500 µs`).
   - Mutating tasks shall never execute mid-tick; they remain fenced to inter-tick or tick boundaries so vanilla redstone, piston, and entity update order is never perturbed.

### 2. Cache-Line-Isolated Read-Only Snapshots & Latency-Tiered Routing

Introduce an **async-first read/dispatch router SPI** (`CompletableFuture` / continuation callback) in Layer 1 (`io.papermc.paper.*` / `ca.spottedleaf.moonrise.*`) that defaults to standard Paper/Folia snapshots and region hops when no custom backing is registered, and enables Layer 2 engines (`LeafAnvil`, `LeafShard`) to route every request through the lowest-latency physically valid tier:

| Tier | Path Condition | Mechanism | Target Latency | Cache & Shard Properties |
|---|---|---|---|---|
| **Tier 0 (Clean Local / Halo Shortcut)** | Local loaded chunk with `dirtyOctantMask64 == 0`, or local `LCAS` halo | Acquire-read of immutable `.lca` `baseFrame` (Layer 2) with 64-byte cache-line isolation (`@Contended`) separating immutable references from mutable tick-thread fields | **~10–50 ns** | Zero copy, zero lock; lines stay MESI `Shared (S)` with zero `M <-> S` invalidation |
| **Tier 1 (Dirty Local Shortcut)** | Local loaded chunk with active edits (`dirtyOctantMask64 != 0`) | Read tick-end / compaction-published immutable `(baseFrame, frozenOverlay)` snapshot reference; opt-in live mid-tick copy only when explicitly requested | **~50–500 ns** | Zero contention with the region thread's L1-resident live `dirtyOverlay` |
| **Tier 2 (Local Interrupt Path)** | Requires world/entity mutation, claim hook, or non-voxel state | Opt-in interrupt-driven inter-tick wakeup (`notifyTasks()` -> `runTasks()`) on `Region` / `Entity` / `Global` schedulers | **~0.1–1 ms** | Region-owned execution; eliminates 25–50 ms tick-boundary wait when idle |
| **Tier 3 (Cold Disk Shortcut)** | Unloaded chunk on local storage | Direct off-thread flyweight `.lca` `AnvilChunkView` read (Layer 2) | **~0.5–5 ms** | Zero regionizer activation, zero `TASK_QUEUE_TICKET`, zero tick-thread load |
| **Tier 4 (Remote Shard Long Path)** | Chunk owned by remote `LeafShard` node outside local halo | Non-blocking async RPC over `LSHD` / Netty control plane (Layer 2) | **~0.5–3 ms** (LAN) | Fully non-blocking (`S-005`); identical caller API across Tiers 0–4 |

### 3. Upstream-Mergeability Boundary

- **Layer 1 (Upstream-candidate PRs):** `EntityScheduler` / `GlobalRegionScheduler` opt-in inter-tick interrupt dispatch, coalesced `notifyTasks()` wakeups, bounded `runTasks()` scheduling fairness, optional read-only mid-tick safe-point hook, and the generic async-first read/dispatch router SPI with stock Paper/Folia fallback. Zero GoldLeaf branding, zero `goldleaf.yml` dependency, zero `.lca` / `LeafShard` imports.
- **Layer 2 (GoldLeaf + Engines):** `goldleaf.yml` tuning knobs, `LeafAnvil` `.lca` `baseFrame` + `frozenOverlay` Tier 0/1/3 provider (`leafanvil-ADR-002`), and `LeafShard` `LCAS` halo + `LSHD` Tier 0/4 provider (`leafshard-ADR-004`).

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| **Make existing `run()` / `runDelayed(..., 1)` execute inter-tick by default** | Breaks Bukkit/Folia plugins that rely on `run()` deferring work until after the current tick or event completes. |
| **Read live `dirtyOverlay` directly from async threads via seqlock during active ticks** | Induces MESI `Modified <-> Shared` cache-line bouncing on hot redstone/farm chunks, degrading owning region-thread L1 write throughput. |
| **Expose synchronous direct-memory chunk read pointers in the public API** | Breaks API portability when chunks are unloaded or owned by a remote `LeafShard` node; an async-first API can still complete inline in nanoseconds on Tier 0. |
| **Run mutating plugin subtasks mid-tick** | Violates `goldleaf-ADR-001`'s vanilla technical fidelity invariant by perturbing redstone, piston, and entity update ordering. |

## Consequences

- **Positive:**
  - Collapses typical `sync -> async -> sync` plugin round trips on idle regions from ~25–50 ms down to `< 1 ms` (Tier 2), and eliminates the sync hop altogether (`~10–500 ns`) for read-only voxel/biome queries (Tiers 0–1).
  - Eliminates cold-region `TASK_QUEUE_TICKET` churn for off-thread scanning workloads like `LeafRTP` (Tier 3).
  - Preserves strict L1 cache isolation for ticking regions and seamless forward compatibility with multi-node `LeafShard` deployments (Tier 4).
  - Layer 1 patches remain clean, standalone upstream PR candidates for `PaperMC/Folia` and `PaperMC/Paper`.
- **Negative / Trade-offs:**
  - Tier 1 dirty-chunk reads observe tick-end-committed state by default (up to 1 tick stale during an active tick) unless the caller explicitly requests a mid-tick live read.
  - Inter-tick `EntityScheduler` execution requires careful ownership/retirement re-checks and thorough concurrency tests against concurrent region splits, merges, and player disconnects.

## References

- `GoldLeaf/docs/adr/goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md`
- `GoldLeaf/folia-server/minecraft-patches/features/0004-Region-Threading-Base.patch` (`TickRegionScheduler`, `RegionizedTaskQueue`, `TickRegions`)
- `GoldLeaf/folia-server/paper-patches/features/0002-Region-Threading-Base.patch` (`FoliaRegionScheduler`, `FoliaEntityScheduler`, `FoliaGlobalRegionScheduler`)
- `resource/LeafAnvil/docs/adr/leafanvil-ADR-002-in-memory-lca-chunk-representation-and-dirty-overlay-compaction.md`
- `resource/LeafShard/docs/adr/leafshard-ADR-004-brokerless-lshd-control-ring-proxy-direct-kinematics-and-dictionary-lcas-sync.md`
