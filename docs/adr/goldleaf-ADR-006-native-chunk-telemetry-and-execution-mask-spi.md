# goldleaf-ADR-006 — Native Chunk Telemetry, Primitive Activity Counters, and `ChunkExecutionMask` SPI

**Status:** Accepted (v0.1 Scope Implemented)
**Date:** 2026-11-21

## Context

### 1. The Plugin-Layer Profiling & Governance Tax on Stock Paper/Folia

Stock Paper and Folia expose no programmatic per-chunk tick timings, no inline mechanism activity counters, and no pre-tick chunk execution gate. Folia's `0010-Region-profiler.patch` (`LProfilerRegistry`) provides manual `/profiler` region-level timers and counters with split/merge journal semantics, but it is operator-CLI-scoped rather than a per-chunk Java SPI and cannot gate chunk execution.

Because of this gap, external diagnostic and farm-governance plugins such as `LeafSleuth` (`leafsleuth-ADR-001`, `leafsleuth-ADR-007`) must pay four compounding plugin-layer costs on stock Paper/Folia:

1. **Global Bukkit Event Allocation & `HandlerList` Dispatch:** `SleuthChunkEventCollector` and `SleuthMitigationActuator` must attach listeners to hot-path events (`BlockRedstoneEvent`, `InventoryMoveItemEvent`, `BlockGrowEvent`, `BlockPistonExtendEvent`, `ItemSpawnEvent`, `InventoryPickupItemEvent`, `BlockDispenseEvent`, `CreatureSpawnEvent`) across every loaded chunk. Inside dense technical farms, Bukkit allocates and dispatches thousands of event objects per tick *before* the listener can inspect or drop the chunk.
2. **Repeated World Topology Lookups & Global Scans:** Verifying specialist or piston exemptions (`REQ-SLEUTH-GOV-SPECIALIST`, `REQ-SLEUTH-GOV-PISTON`) or evaluating `EmpiricalFarmBlackBox` wake-radius proximity (`28.0` blocks) from plugin space forces repeated `world.getBlockAt` / `getBlockData` lookups and `world.getPlayers()` / `world.getLivingEntities()` sweeps.
3. **JVM Safepoint Stack Sampling:** Inferring hot subsystems from outside the tick loop via `tickThread.getStackTrace()` induces stop-the-world safepoint pauses on busy regions.
4. **Indirect Statistical Inference:** Because the tick loop does not report per-chunk phase durations, `LagAttributionEngine` and `CausalAutoTuner` must rely on global regression cold-start and `DIAGNOSTIC_MICRO_DITHER` probes to infer which chunk caused an MSPT spike—delaying attribution on low-variance steady-state farms and conflating passive bystanders with active spatial load in `LeafShard` (`LeafShardSleuthBridge`).

As documented in `leafsleuth-ADR-007`, this observer tax can push a busy server over the `50 ms/tick` budget faster than an unmonitored baseline.

### 2. GoldLeaf's Region-Loop Seam

Inside Folia's region tick loop (`ServerLevel`, `LevelChunk`, `RegionizedWorldData`), the server already iterates region-by-region and chunk-by-chunk on the owning region thread (`F-001`..`F-003`). Exposing a razor-thin, zero-external-dependency Layer 1 SPI layered cleanly after `0010-Region-profiler.patch`—without modifying `0004-Region-Threading-Base.patch`—lets `LeafSleuth` and `LeafShard` read exact timings and gate paused or virtualized farms while preserving vanilla mechanics and `~0 ns` overhead when no consumer is attached.

## Decision

GoldLeaf introduces a **two-layer Chunk Telemetry and Execution-Mask SPI** (`goldleaf-ADR-001` compliant): **Layer 1** adds four zero-dependency, upstream-clean SPIs in dedicated post-Folia patches (`0016+`), and **Layer 2** wires optional companion consumers (`LeafSleuth`, `LeafShard`, and `:config-bit-api` `SleuthDiagnosticSnapshotSource`) with graceful fallback on stock Paper/Folia.

### 1. Layer 1 — Thin Folia Core SPIs (`folia-server`, zero external dependencies)

All Layer 1 patches layer after `0010-Region-profiler.patch` and shall not edit `0004-Region-Threading-Base.patch`. Each hook delegates via a 1–3 line call site to `dev.leafcraft.goldleaf.telemetry.*` helpers.

1. **Per-Chunk & Per-Region Phase Telemetry (`ChunkTickTelemetry` / `RegionTickTelemetryListener`):**
   - Exposes exact `(RegionId, ChunkPos, TickPhase) -> nanos` attribution across `ENTITY`, `BLOCK_ENTITY`, `SCHEDULED_TICK`, `RANDOM_TICK`, `REDSTONE`, and `HOPPER` phases directly from the owning region thread.
   - **Zero-Cost Idle Guard:** All `System.nanoTime()` calls and listener dispatches are guarded behind a single `volatile boolean telemetryActive` check (`false` when no listener is registered). When idle, the hot loop pays one predicted branch (`~0 ns` measurable delta) and zero object allocations.
   - **Folia Split/Merge Semantics:** Region telemetry accumulators attach to `RegionizedWorldData` and follow the same region split/merge lifecycle boundaries as `0010-Region-profiler.patch` (`LProfilerRegistry`), flushing or transferring per-chunk buckets cleanly on region splits, merges, and unloads with zero cross-region contention (`S-005`).
2. **Lazily Allocated `LevelChunk` Primitive Activity Counters (`ChunkActivityCounters`):**
   - Adds a nullable `@Nullable ChunkActivityCounters activityCounters` sidecar field on `LevelChunk`, allocated lazily only when `telemetryActive` is true and a chunk records activity (cleared on chunk unload).
   - Tracks primitive `int` counters per tick window: `redstoneUpdates`, `hopperTransfers`, `pistonMoves`, `blockGrowths`, and `entityTicks`, incremented inline on the owning region thread with zero boxing and zero Bukkit event allocation.
   - Consumers drain and reset counters via `ChunkTickTelemetry#drainChunkActivity(LevelChunk)` on the owning region thread or via immutable tick-end snapshots published off-thread.
3. **Pre-Tick, Pre-Event `ChunkExecutionMask` Bitmask Gate (`LevelChunk#executionMask`):**
   - Adds a single primitive `int executionMask` field on `LevelChunk` (default `0`, vanilla behavior) with explicit bit flags:
     - `SUPPRESS_REDSTONE` (`1 << 0`)
     - `SUPPRESS_HOPPERS` (`1 << 1`)
     - `SUPPRESS_PISTONS` (`1 << 2`)
     - `DAMPEN_ENTITY_AI` (`1 << 3`)
     - `VIRTUALIZED_BLACKBOX` (`1 << 4`)
   - **Pre-Tick & Pre-Event Check:** The mask is tested with a single bitwise AND *before* vanilla block/hopper/piston/entity tick work and *before* Bukkit constructs or dispatches `BlockRedstoneEvent`, `InventoryMoveItemEvent`, `BlockPistonExtendEvent`, or `BlockGrowEvent`. When `executionMask == 0`, vanilla execution proceeds identically.
   - **Specialist & Piston Exemption Preservation:** To uphold `REQ-SLEUTH-GOV-SPECIALIST` (hoppers interacting with storage/furnace/crafter/brewing/rail-cart specialist topologies) and `REQ-SLEUTH-GOV-PISTON` (moving/slime/honey piston contraptions), `LevelChunk` caches a lightweight `byte exemptionFlags` summary updated on block-state placement/removal rather than issuing 6+ `world.getBlockAt` calls per transfer. Mask bits marked exempt on a chunk or block entity bypass suppression automatically.
   - **Region Ownership & Cleanup (`S-002`, `S-005`):** Mutating `executionMask` requires owning-region execution (`Bukkit.isOwnedByCurrentRegion`); masks reset automatically to `0` on chunk unload and when the registering consumer unregisters or fails closed (`S-004`).
4. **Region-Local Proximity & Entity-Count View (`RegionProximityView`):**
   - Exposes a region-local read-only query backed by Moonrise's existing chunk/player tracking maps: nearest player distance squared to a chunk center and per-chunk entity counts by category (`ITEM`, `LIVING`, `PROJECTILE`).
   - Strictly `S-005` compliant: zero synchronous chunk loads, zero `world.getLivingEntities()` sweeps, and zero cross-region world locks.

### 2. Layer 2 — Companion Engine Integration (`LeafSleuth`, `LeafShard`, `:config-bit-api`)

1. **`LeafSleuth` Native Acceleration with Stock Fallback:**
   - On startup, `LeafSleuthPaperPlugin` probes for GoldLeaf's `ChunkTickTelemetry` service.
   - **On GoldLeaf:** `SleuthChunkEventCollector` skips registering global hot-path `BlockRedstoneEvent`, `InventoryMoveItemEvent`, `BlockGrowEvent`, and `BlockPistonExtendEvent` listeners for activity counting and reads `ChunkActivityCounters` + exact phase timings directly into `LagAttributionEngine` and `LagPatternRecognizer`. `SleuthTickSampler` drops `tickThread.getStackTrace()` safepoint sampling. `SleuthMitigationActuator`, `CausalAutoTuner`, and `EmpiricalFarmBlackBox` flip `ChunkExecutionMask` bits for Tier 3 black-box virtualization and rotating probe pauses, and use `RegionProximityView` for `wakeRadius` (`28.0` blocks) checks.
   - **On Stock Paper/Folia:** `LeafSleuth` transparently retains its existing Bukkit-listener and statistical regression pipeline (`leafsleuth-ADR-001`, `leafsleuth-ADR-007`) with zero behavioral break.
2. **`LeafShard` Spatial Load Attribution:**
   - `LeafShardSleuthBridge` consumes GoldLeaf's per-region / per-chunk phase timings directly to compute spatial location load without conflating passive bystanders with active farms, feeding region-split and shard-migration decisions cleanly.
3. **Unified Diagnostics (`:config-bit-api`):**
   - `SleuthDiagnosticSnapshotSource` continues to surface hotspot and mitigation state to `LeafConfig` and admin reports using the exact native counters when present.

### 3. Minimal `goldleaf.yml` Configuration Surface

Consistent with `GoldLeafConfig.java` minimalism, telemetry and execution gating are SPI-driven and zero-overhead when no consumer is registered:

```yaml
diagnostics:
  log-hardware-envelope-on-startup: true
  chunk-telemetry-spi: true        # Exposes Layer 1 telemetry & ChunkExecutionMask SPI (idle cost ~0 ns)
  chunk-telemetry-max-listeners: 4 # Bounded listener cap to prevent runaway plugin fan-out
```

Disabling `diagnostics.chunk-telemetry-spi` prevents any plugin from activating `telemetryActive` or mutating `executionMask`, guaranteeing strict vanilla execution even if a third-party plugin requests the SPI.

### 4. Mandatory Verification & Non-Regression Gates

1. **Idle Overhead Gate:** Benchmark `diagnostics.chunk-telemetry-spi: true` with zero listeners attached against unpatched Folia `26.3.x` and verify `< 0.5%` MSPT delta and `0 B/tick` extra allocation.
2. **Vanilla Parity & Exemption Gate:** Verify `executionMask == 0` produces bit-identical redstone, hopper, piston, and entity tick outcomes; verify `REQ-SLEUTH-GOV-SPECIALIST` and `REQ-SLEUTH-GOV-PISTON` exemptions prevent suppression of specialist hoppers and flying/slime contraptions.
3. **Region Split/Merge & Unload Hygiene (`S-002`, `S-004`, `S-005`):** Verify region splits, merges, and chunk unloads release `ChunkActivityCounters` sidecars and reset `executionMask` to `0` with zero leaked references or wrong-region mutations.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Rewrite Folia's `0010-Region-profiler.patch` (`LProfilerRegistry`) to carry per-chunk masks and counters** | Expands rebase conflict surface against upstream Folia's profiler patch and mixes interactive `/profiler` UX with programmatic execution gating. Layering dedicated `0016+` patches keeps both clean and independently upstreamable. |
| **Keep all farm profiling and governance purely in Bukkit event listeners (`LeafSleuth` plugin layer)** | Forces per-event object allocation and `HandlerList` dispatch across every chunk before filtering, requires safepoint stack sampling, and degrades MSPT on busy servers (`leafsleuth-ADR-007`). |
| **Use bytecode instrumentation (`-javaagent` / ASM) from `LeafSleuth` to inject counters into `LevelChunk`** | Fragile across `26.x` Minecraft/Moonrise drops and unnecessary when GoldLeaf owns the fork and can expose a clean, typed, upstream-candidate Layer 1 SPI. |
| **Enable `ChunkExecutionMask` heuristics directly inside GoldLeaf core (`goldleaf.yml` auto-throttling)** | Violates GoldLeaf's two-layer boundary (`goldleaf-ADR-001`) and Purpur non-goal: GoldLeaf core stays vanilla-identical by default, while policy engines like `LeafSleuth` opt in via Layer 2. |

## Consequences

- **Positive:**
  - Eliminates hot-path Bukkit event allocation (`BlockRedstoneEvent`, `InventoryMoveItemEvent`, `BlockGrowEvent`, `BlockPistonExtendEvent`) and JVM safepoint stack sampling when `LeafSleuth` runs on GoldLeaf.
  - Skips both vanilla tick compute and Bukkit event dispatch in a single bit-test for paused or black-box-virtualized farms (`EmpiricalFarmBlackBox`).
  - Delivers exact `(RegionId, ChunkPos, TickPhase) -> nanos` attribution to `LeafSleuth` and `LeafShard` (`LeafShardSleuthBridge`) with `~0 ns` idle overhead.
  - Preserves 100% vanilla mechanics out of the box and keeps `LeafSleuth` fully compatible with stock Paper/Folia via graceful fallback.
- **Negative / Trade-offs:**
  - Adds small 1–3 line call-site hooks in hot `ServerLevel` / `LevelChunk` / hopper / redstone tick methods that must be maintained across `26.x` upstream rebases.
  - Cached specialist/piston `exemptionFlags` on `LevelChunk` must stay synchronized with block-state changes and block-entity placement/removal to avoid stale exemptions.

## References

- Folia & GoldLeaf core files:
  - `GoldLeaf/folia-server/minecraft-patches/features/0010-Region-profiler.patch` (`LProfilerRegistry`)
  - `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/config/GoldLeafConfig.java`
  - `GoldLeaf/docs/adr/goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md`
- `LeafSleuth`, `LeafShard`, and `:config-bit-api` consumers:
  - `resource/LeafSleuth/docs/adr/leafsleuth-ADR-001-confounder-resistant-lag-attribution-and-clock-periodicity.md`
  - `resource/LeafSleuth/docs/adr/leafsleuth-ADR-007-tiered-stochastic-farm-discovery-and-lazy-blackbox-ingestion.md`
  - `resource/LeafSleuth/sleuth-paper/src/main/java/net/leaf/sleuth/paper/SleuthChunkEventCollector.java`
  - `resource/LeafSleuth/sleuth-paper/src/main/java/net/leaf/sleuth/paper/SleuthTickSampler.java`
  - `resource/LeafSleuth/sleuth-paper/src/main/java/net/leaf/sleuth/paper/governor/SleuthMitigationActuator.java`
  - `resource/LeafSleuth/sleuth-common/src/main/java/net/leaf/sleuth/common/governor/EmpiricalFarmBlackBox.java`
  - `resource/LeafSleuth/sleuth-common/src/main/java/net/leaf/sleuth/common/governor/CausalAutoTuner.java`
  - `resource/LeafSleuth/sleuth-common/src/main/java/net/leaf/sleuth/common/lag/LagPatternRecognizer.java`
  - `resource/LeafShard/leafshard-paper/src/main/java/net/leaf/shard/paper/LeafShardSleuthBridge.java`
  - `api/config-bit-api/src/main/java/io/github/dailystruggle/rtp/common/bit/config/SleuthDiagnosticSnapshotSource.java`
