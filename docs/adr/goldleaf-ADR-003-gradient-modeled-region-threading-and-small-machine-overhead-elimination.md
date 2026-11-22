# goldleaf-ADR-003 — Small-Machine Tick-Thread Scaling Curve & Ephemeral Region Allocation Reuse

**Status:** Proposed
**Date:** 2026-11-21

## Context

### 1. Empirical Test of the "Folia Overhead on Small Machines" Premise

A widespread operational claim in the Minecraft server community is that Folia carries significantly higher CPU, RAM, and thread overhead than Paper and should therefore be avoided on smaller machines (2–8 cores, 2–8 GB RAM).

Before designing mitigations for regionizer or scheduler overhead, we ran controlled A/B benchmarks (`scripts/bench_paper_vs_folia.py`) comparing stock Paper `26.2` against stock Folia `26.2` on pinned 2-core and 4-core envelopes (`taskset` + `-XX:ActiveProcessorCount`, `-Xms512M -Xmx2G`, `-Xlog:gc`, zero plugins, identical pre-generated world) across both **steady-state** multi-region workloads (clusters spaced 3,072 blocks apart) and **dynamic region churn** workloads:

#### Steady-State Workloads (4-Core Envelope)

| Scenario (4-core envelope) | Paper `26.2` (tick CPU / RSS) | Folia `26.2` default (`1` thr) | Folia `26.2` (`threads=2`) |
|---|---|---|---|
| **`idle`** (spawn only) | `1.60%` (`2.79%` proc) / `879 MiB` | `0.30%` (`0.70%` proc) / `854 MiB` | — |
| **`single_region`** (64 chunks, 80 villagers) | `9.97%` / `909 MiB` | `8.88%` / `898 MiB` | `8.88%` / `896 MiB` |
| **`spread_4reg`** (4 regions, 256 chunks, 4×20) | `9.58%` / `932 MiB` | `9.18%` / `924 MiB` | `9.08%` / `922 MiB` |
| **`spread_8reg`** (8 regions, 512 chunks, 8×15) | `14.26%` / `985 MiB` | `14.07%` / `979 MiB` | `14.56%` / `1004 MiB` |
| **`heavy_spike_8reg`** (R0: 300 + 7×15) | `40.80%` (`20.5 ms` global) / `1022 MiB` | `40.21%` / `1008 MiB` | `42.00%` (R0 `16.1 ms`, R1 `1.25 ms`) / `1009 MiB` |

On the 2-core envelope, steady-state tick-thread CPU was identical on 4 regions (`8.98%` vs. `8.98%`) and within `+0.89%` (`~56 µs`/region) on 8 regions (`13.87%` vs. `14.76%`), with `-25` to `+19 MiB` RSS delta and ~50% fewer voluntary context switches on Folia.

#### Dynamic Region Churn Workloads (15 s Active Sample)

| Scenario (envelope) | Paper `26.2` (steps / CPU/step / GC/step / RSS) | Folia `26.2` default (`1` thr) | Folia `26.2` (`threads=2`) |
|---|---|---|---|
| **`region_thrash`** (4-core, 4-chunk `0 ↔ 1` churn across 24 isolated sections) | `52` / `598.3 ms` / `17.54 ms` / `1655 MiB` | `34` (**-35%**) / `1024.7 ms` (**+71%**) / `42.52 ms` (**2.42×**) / `1773 MiB` | `36` (**-31%**) / `908.1 ms` (**+52%**) / `26.88 ms` (**+53%**) / `1763 MiB` |
| **`region_thrash`** (2-core) | `49` / `555.1 ms` / `20.59 ms` / `1210 MiB` | `36` (**-27%**) / `737.8 ms` (**+33%**) / `43.95 ms` (**2.13×**) / `1544 MiB` | — |
| **`bridge_oscillation`** (4-core, 2-section merge/split between 2×40-entity regions) | `22` / `490.0 ms` / `4.20 ms` / `1462 MiB` | `22` / `474.1 ms` / `4.31 ms` / `1476 MiB` | `22` / `469.2 ms` / `3.90 ms` / `1470 MiB` |
| **`bridge_oscillation`** (2-core) | `22` / `656.4 ms` / `4.70 ms` / `1214 MiB` | `22` / `620.0 ms` / `4.55 ms` / `1228 MiB` | — |

### 2. What the Benchmark Disproved vs. What It Confirmed

- **Disproved (not worth fixing):**
  1. **Steady-state region overhead:** Stock Folia does **not** carry a meaningful steady-state CPU, memory, thread-count, or context-switch penalty compared to Paper on 2–4 core machines—even when fragmented into 8 separate regions. Adaptive `ThreadedRegionizer` coalescing/subdivision, a single-active-region scheduler fast-path, and small-heap `RegionizedWorldData` collection pre-sizing are therefore **out of scope**.
  2. **Moderate periodic bridge merge/split (`bridge_oscillation`):** Pulsing a 2-section bridge between two active 40-entity regions (~11 merge/split cycles in 15 s) matched Paper on both 2-core and 4-core envelopes.
- **Confirmed real:**
  1. **`TickRegions#getTickThreads` default cliff (`<= 8` cores):** When `GlobalConfiguration.ThreadedRegions.threads <= 0`, Folia (`0004-Region-Threading-Base.patch`) computes `tickThreads = OSNuma.getNativeInstance().getTotalCores() / 2` and clamps `tickThreads = 1` whenever `tickThreads <= 4` (every `<= 8` core host). In the 4-core benchmark, `folia_4c_default` initialized only `1` tick thread despite 8 active regions and a 300-villager spike, forfeiting blast-radius isolation—whereas `threads=2` doubled tick capacity (`200%`) and kept uninvolved regions at `~1.25 ms` MSPT for `< 1.2%` extra tick CPU and `+1 MiB` RSS.
  2. **Ephemeral `0 ↔ 1` region allocation/teardown churn (`region_thrash`):** Rapid scattered single-chunk loads/unloads (triggered by elytra exploration, portals, `/tp`, preloaders, `LeafShard` handoffs, or scatter-probing plugins) repeatedly cross empty sections (`0 → 1`) and immediately hit `region.hasNoAliveSections()` (`1 → 0`), bypassing `ThreadedRegionizer`'s lock-free fast path and dead-section hysteresis. On every `0 → 1` creation, `TickRegions#onRegionCreate()` eagerly constructs `RegionizedWorldData` (including `LevelTicks` ×2, `CollectingNeighborUpdater`, `NearbyPlayers`, `PositionCountingAreaMap`, `WireHandler`, `RedstoneWireTurbo`, a 4,096-entry `PathTypeCache`, and `explosionDensityCache`), and `TickRegions#onRegionDestroy()` (`// nothing for now`) immediately discards them to GC—driving `+33%` to `+71%` CPU/step and `2.1×–2.4×` GC pause time.

Small-machine and dynamic Folia pain points thus decompose cleanly into four concrete, orthogonal issues:
1. The `<= 8` core default tick-thread cliff (this ADR §1).
2. Ephemeral `0 ↔ 1` `RegionizedWorldData` / tick-handle allocation churn (this ADR §2).
3. Sub-tick async→sync scheduling latency and zero-copy `.lca` read routing (`goldleaf-ADR-002`).
4. Uncompressed chunk heap baseline on 2–8 GB hosts (`goldleaf-ADR-001` Stage 2 `.lca` cold-section backing).

## Decision

GoldLeaf fixes only the empirically confirmed threading and lifecycle bottlenecks—Folia's `<= 8` core default tick-thread cliff and eager `0 ↔ 1` region-state allocation churn—without adding speculative `ThreadedRegionizer` coalescing or scheduler fast-path complexity.

### 1. Smooth Hardware-Envelope Tick-Thread Default Curve (`TickRegions#getTickThreads`)

When `threaded-regions.threads <= 0` (auto mode), replace the `tickThreads <= 4 -> 1` step cliff with a headroom-preserving default curve that reserves CPU budget for Netty, Moonrise chunk I/O, and GC while unlocking parallel blast-radius isolation on 4–8 core hosts:

| Available Cores (`ActiveProcessorCount` / `OSNuma`) | Default Tick Threads | Rationale |
|---|---|---|
| `1–3` cores | `1` | Avoid oversubscribing tick threads against Netty, chunk I/O, and GC. |
| `4–5` cores | `2` | Empirically doubles tick capacity (`200%`) and isolates localized spikes at `< 1.2%` CPU cost. |
| `6–8` cores | `3` (or `4` on `8` cores) | Scales region isolation smoothly on surplus workstations without starving I/O/GC. |
| `9+` cores | Smooth continuation toward existing Folia scaling cap | Preserves mega-server headroom and avoids sudden jumps. |

Explicit operator overrides (`threaded-regions.threads > 0`) continue to take precedence unchanged.

### 2. Lazy Region State Allocation & Bounded Shell Reuse (`TickRegions` / `RegionizedWorldData`)

To eliminate the `2.1×–2.4×` GC pause amplification under legitimate `0 ↔ 1` region churn without perturbing vanilla simulation semantics or `ThreadedRegionizer` invariants:

- **Adjust allocation conditions (lazy + threshold-gated):**
  - Remove the eager `data.getOrCreateRegionizedData(data.world.worldRegionData)` call from `TickRegions#onRegionCreate()` (a newly created empty region already starts at `0/0/0` entities/players/chunks).
  - Lazily initialize heavy simulation subsystems inside `RegionizedWorldData` (`WireHandler`, `RedstoneWireTurbo`, `PathTypeCache`, `explosionDensityCache`, `spawnChunkTracker`, and block/tile capture maps) on first actual redstone update, pathfind, explosion, or player join rather than on chunk attachment.
  - Gate `ConcreteRegionTickHandle` scheduler registration (`onRegionActive`) so non-ticking / border-only loads do not enter the active tick-scheduler loop until a ticking chunk or queued task requires it.
- **Bounded object reuse:**
  - Pool destroyed `RegionizedWorldData` shells in a small bounded lock-free per-`ServerLevel` queue (e.g., `16–32` entries) inside `TickRegions#onRegionDestroy()`, clearing collections/counters and rebinding `regionData` via an explicit `reset(TickRegionData)` lifecycle method on checkout.
  - Reset and reuse `ConcreteRegionTickHandle` instances rather than allocating a fresh handle in `TickRegions#onRegionInactive()`.
- **Sequencing:** Keep this as a secondary Layer 1 optimization behind `goldleaf-ADR-002` (sub-tick interrupt routing and `.lca` zero-copy reads) and §1 above. Server-side region shell reuse improves legitimate exploration/portal/teleport churn and builds upstream Folia PR credibility without closing the chunk-I/O moat of `.lca` Tier 0/3 reads (`leafanvil-ADR-002`), since scatter-probing plugins still pay full chunk decompression and NBT deserialization regardless of region pooling.

### 3. Evidence-Gated Idle Worker Parking (Deferred Unless Measured)

Folia already provides `TickRegionScheduler#setThreads(int)` and parks idle `TickThreadRunner` threads with minimal wakeup overhead (`70` VolCS/s idle vs. Paper's `225` VolCS/s). Dynamic hysteresis-gated worker parking shall **not** be implemented unless future 4–8 core multi-thread benchmarks demonstrate measurable idle CPU or wakeup regression from keeping `2–4` parked tick threads allocated.

### 4. Upstream-Mergeability Boundary

- **Layer 1 (Upstream-candidate Folia PRs):**
  1. Smooth default core-scaling curve in `TickRegions#getTickThreads` with unit tests covering `1..64` core counts and explicit override precedence.
  2. Lazy `RegionizedWorldData` subsystem initialization and bounded per-`ServerLevel` shell pooling (`TickRegions#onRegionCreate` / `onRegionDestroy` / `onRegionInactive`), verified against `scripts/bench_paper_vs_folia.py --suite thrash`.
  - Both patches live cleanly in standard Folia packages with zero GoldLeaf branding or external engine dependencies.
- **Layer 2 (GoldLeaf):** Optional `goldleaf.yml` auto-profile and region-pool telemetry; zero modifications to `ThreadedRegionizer` merge/split constants or `TickRegionScheduler` single-region arbitration.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| **Adaptive `ThreadedRegionizer` coalescing & load-triggered split hysteresis** | Disproved by `bench_paper_vs_folia.py`: 8 independent steady-state regions added `< 0.5 ms` total CPU (`~50–100 µs`/region) and negligible RSS (`-25` to `+19 MiB`). Adds oscillation and tuning complexity for no meaningful win. |
| **Single-active-region fast-path in `TickRegionScheduler`** | Disproved by benchmark: stock Folia already matched or beat Paper on `idle` (`0.30%` vs. `1.60%` tick CPU) and `single_region` (`8.88%` vs. `9.97%`). |
| **Small-heap `RegionizedWorldData` collection pre-sizing** | Disproved by benchmark: steady-state RSS across 1–8 regions was within noise of Paper. Heap reduction belongs to `.lca` chunk compression (`goldleaf-ADR-001` Stage 2). |
| **Time-based `STATE_DEAD` grace TTL / bridge-merge delay in `ThreadedRegionizer`** | Deferred: `bridge_oscillation` already matched Paper, and modifying `ThreadedRegionizer`'s state machine carries higher merge/split correctness risk than lazy initialization + bounded shell reuse. Revisit only if `regionLock` write-lock contention remains material after §2. |
| **Keep Folia's `<= 8` core clamp to `1` tick thread** | Leaves 4–8 core servers single-threaded by default, losing Folia's primary blast-radius isolation benefit when `threads=2` costs `< 1.2%` CPU. |

## Consequences

- **Positive:**
  - Keeps GoldLeaf's Folia patch stack minimal, clean, and directly upstreamable to `PaperMC/Folia` (localized changes in `TickRegions` and `RegionizedWorldData`).
  - Unlocks multi-region spike isolation out of the box on 4–8 core hosts where stock Folia silently collapsed to `1` tick thread.
  - Targets the empirically confirmed `2.1×–2.4×` GC pause penalty under `0 ↔ 1` ephemeral region churn without destabilizing `ThreadedRegionizer`.
  - Eliminates maintenance and correctness risk from unneeded steady-state region coalescing or scheduler fast-path forks.
- **Negative / Trade-offs:**
  - On heavily CPU-constrained shared 4-vCPU VPS hosts with noisy neighbors, defaulting to `2` tick threads may slightly increase contention under 100% saturation; operators can still pin `threaded-regions.threads: 1` explicitly.
  - Pooled `RegionizedWorldData` shells require strict `reset()` discipline (and unit tests) to guarantee zero stale entity, player, or neighbor-updater references leak between recycled regions.

## References

- `scripts/bench_paper_vs_folia.py` (controlled Paper `26.2` vs. Folia `26.2` steady-state and `--suite thrash` 2-core / 4-core A/B benchmark harness)
- `GoldLeaf/docs/adr/goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md`
- `GoldLeaf/docs/adr/goldleaf-ADR-002-sub-tick-interrupt-scheduling-and-latency-tiered-routing.md`
- `GoldLeaf/folia-server/minecraft-patches/features/0004-Region-Threading-Base.patch` (`TickRegions#getTickThreads`, `TickRegions#onRegionCreate`, `TickRegions#onRegionDestroy`, `TickRegions#onRegionInactive`, `RegionizedWorldData`, `TickRegionScheduler#setThreads`)
- `GoldLeaf/folia-server/paper-patches/features/0002-Region-Threading-Base.patch` (`GlobalConfiguration.ThreadedRegions`)
