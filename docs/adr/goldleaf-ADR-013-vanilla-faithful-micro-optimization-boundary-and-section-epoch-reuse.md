# goldleaf-ADR-013 — Vanilla-Faithful Micro-Optimization Boundary, `sectionEpoch` Reuse, and Non-Goal Guardrails

**Status:** In Progress
**Date:** 2026-11-21

## Context

### 1. Why Admins Look to `Gale` / `Lithium` / `SparklyPaper` — and Where Forks Go Wrong

Administrators evaluating Paper and Folia forks routinely want "free" MSPT reductions that carry **zero gameplay side effects** (`Lithium` / `Gale` / `SparklyPaper` class optimizations). At the same time, the Paper fork ecosystem suffers from two recurring failure modes that erode technical-community trust and bloat rebase cost across Mojang's `26.x` drop cadence:

1. **Redundant or Unprofiled Micro-Patches:**
   - Inspecting `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/world/phys/shapes/VoxelShape.java` (lines 21–111) confirms that Paper/Moonrise `26.3.x` **already ships** `CollisionVoxelShape` (`singleAABBRepresentation`, `cachedShapeData`, `mergedORCache`, raw `double[]` coordinate arrays). Porting older `Lithium`/`Gale` collision patches blindly into `folia-server` creates merge conflicts with zero net win.
   - Similarly, `HopperBlockEntity.java` (lines 155–208) already includes Paper's `getFullState` (`HOPPER_EMPTY` / `HOPPER_HAS_ITEMS` / `HOPPER_IS_FULL`) fast path, whereas remaining hopper CPU overhead lies in repeated neighbor container/block-state lookups and unchanged-container scans when neither source nor destination inventory mutated.
2. **Scope Creep into Gameplay-Altering Knobs & Unsafe Async Hacks:**
   - Forks such as Purpur bundle hundreds of custom gameplay novelty toggles into `paper-server`, while other forks enable heuristic AI/activation throttling or unsafe async simulation over live shared world state—breaking iron/raid/trading farms, causing subtle redstone timing drift, and violating Folia region ownership (`F-001`..`F-003`).

### 2. Cross-Subsystem Reuse Opportunity (`LevelChunkSection#sectionEpoch`)

Both `goldleaf-ADR-005` (`.lca` dirty-section delta encoding and cold-section residency) and `goldleaf-ADR-008` (deferred pathfinding `PathType` cache invalidation) already require a monotonic per-section mutation counter (`LevelChunkSection#sectionEpoch`). Reusing that single primitive `int` epoch across storage, off-tick chunk/Anti-Xray snapshotting (`goldleaf-ADR-009`), and strictly vanilla-exact neighbor/container caches yields measurable MSPT wins with near-zero patch surface.

## Decision

GoldLeaf establishes a **Strict Vanilla-Faithful Micro-Optimization Boundary** for `folia-server` (Layer 1) and explicitly codifies **Rejected Fork Anti-Patterns** so `26.x` rebases remain fast and technical-server parity stays guaranteed.

### 1. Admission Criteria for Layer 1 Micro-Optimizations

Every micro-optimization patch accepted into `folia-server` shall satisfy **all four** gates:

1. **Not Already Superseded by Paper / Moonrise `26.3.x`:** Verified against materialized `folia-server` sources (e.g., `VoxelShape`, `HopperBlockEntity`, Moonrise chunk/collision patches).
2. **Profile-Proven Win (`Gate 10`):** Demonstrates measurable MSPT, allocation-rate, or GC-pause improvement on a reproducible headless/devstack benchmark before merge.
3. **100% Observable Vanilla Equivalence:** Preserves exact vanilla tick order, RNG consumption sequence, redstone/comparator signal timing, and entity/item transfer semantics (never tick-skipping or probability-approximating).
4. **Upstreamable & Low-Conflict:** Touching minimal NMS lines so the patch can be proposed upstream to Paper/Folia or cleanly rebased across `26.x` drops.

### 2. Targeted High-Value Micro-Optimizations in Scope

1. **Unified `LevelChunkSection#sectionEpoch` Primitives:**
   - Maintain a single monotonic `int sectionEpoch` incremented on `LevelChunkSection#setBlockState` (shared by `goldleaf-ADR-005` `.lca` persistence, `goldleaf-ADR-008` pathfinding caches, and `goldleaf-ADR-009` off-tick chunk/Anti-Xray snapshot validation).
2. **Exact-Order Hopper & Container Notification Caching (`HopperBlockEntity`):**
   - Extend Paper's existing `getFullState` check (`HopperBlockEntity.java:155-208`) with an exact-invalidation container mutation epoch (`containerChangeCount`) and cached neighbor `BlockState` / `Container` lookup invalidated immediately on `setChanged()`, block updates, or neighbor chunk unload—eliminating redundant slot scans on idle storage arrays without ever altering vanilla transfer order, comparator updates, or `hopperTransfer` timing.
3. **Hot-Loop Allocation Elimination:**
   - Eliminate avoidable per-tick temporary `List`/`ArrayList`/`BlockPos` allocations in verified hot loops (such as `ChunkMap.TrackedEntity` purge passes in `goldleaf-ADR-009` and fluid/entity collection loops) where reuse is strictly single-region-thread confined (`F-001`..`F-003`).

### 3. Explicit Rejection Boundary (Permanent Non-Goals)

To protect rebase velocity and technical-community trust, GoldLeaf explicitly **rejects**:

| Rejected Fork Pattern | Rationale |
|---|---|
| **Purpur-style gameplay novelty knobs** (hundreds of custom mob/block/item behavior toggles in `folia-server`) | Massive `26.x` rebase debt; gameplay customization belongs in Layer 2 plugins/datapacks, not server patches. |
| **Default-on heuristic entity/AI throttling** (DAB, villager lobotomizing, aggressive activation-range crushing) | Silently breaks iron, raid, and trading farms; violates `goldleaf-ADR-008` parity contract. |
| **Unsafe async simulation hacks** (async pathfinding, async mob spawning, async entity ticking on live world state) | Introduces data races, item dupes, and violates Folia's region-ownership guarantees (`F-001`..`F-003`, `S-001`). |
| **Global-lock Bukkit plugin emulation** | Emulating single-threaded Bukkit via global region locks destroys Folia parallelism; `goldleaf-ADR-007` boot-time preflight is the honest alternative. |

### 4. Mandatory Verification Gates

1. **Exact Vanilla Equivalence Suite:** Redstone sorter, hopper-chain, comparator-clock, and item-elevator tests verifying identical item counts, tick-for-tick transfer order, and comparator pulse widths vs stock Folia.
2. **Differential Allocation & MSPT Benchmark (`Gate 10`):** JFR / async-profiler allocation and MSPT comparison on dense hopper/storage-hall and crowded-entity stress worlds proving non-regression and measurable gain.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| **Port entire `Gale` / `LeafMC` / `Purpur` patch stacks wholesale** | Duplicates optimizations already merged into Paper/Moonrise `26.3.x`, multiplies rebase conflicts, and introduces gameplay-altering regressions. |
| **Reject all micro-optimizations in `folia-server`** | Leaves clean, strictly vanilla-exact wins (such as `sectionEpoch` sharing and idle-hopper cache invalidation) on the table for survival SMPs with large storage halls. |
| **Throttle hopper tick rates (`hopperCheck` / skip ticks) by default** | Breaks vanilla item sorters, super-smelters, and comparator timing circuits. |

## Consequences

- **Positive:**
  - Gives survival and technical SMPs measurable MSPT and GC-allocation reductions with zero gameplay regressions.
  - Prevents fork bloat and keeps `folia-server` patch counts small and upstreamable across `26.x` drops.
  - Consolidates `sectionEpoch` as a single cross-cutting primitive shared by storage, networking, and AI caches.
- **Negative / Trade-offs:**
  - Contributors proposing new micro-optimizations must supply a `26.3.x` source check and `Gate 10` benchmark proof rather than porting legacy patch files on faith.

## References

- Materialized Folia `26.3.x` seams in `GoldLeaf`:
  - `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/world/phys/shapes/VoxelShape.java` (`CollisionVoxelShape`, lines 21–111)
  - `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/world/level/block/entity/HopperBlockEntity.java` (`pushItemsTick`, `getFullState`, `tryMoveItems`, lines 141–218)
- Related `GoldLeaf` ADRs:
  - `GoldLeaf/docs/adr/goldleaf-ADR-005-lca-format-adoption-across-disk-memory-and-network.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-007-plugin-safety-preflight-and-runtime-hazard-spi.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-008-mob-ai-optimization-boundary-and-farm-parity-gate.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-009-entity-tracker-scaling-off-tick-packet-encoding-and-lca-anti-xray-spi.md`
  - `docs/dev/RULES.md` (`F-001..F-003`, `S-001`, `S-004`)
