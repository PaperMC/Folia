# goldleaf-ADR-008 — Deferred Mob AI Optimization Boundary and `P1..P4` Technical-Farm Parity Gate

**Status:** Proposed (Deferred Post-`v0.1`)
**Date:** 2026-11-21

## Context

### 1. Mob AI as the Post-`v0.1` Frontier

Once chunk storage I/O (`goldleaf-ADR-005` / `.lca`), world generation (`goldleaf-ADR-004`), small-machine region threading (`goldleaf-ADR-003`), and chunk-level profiling (`goldleaf-ADR-006`) are off the critical path, mob AI (`Brain<E>`, `GoalSelector`, `Sensor` sweeps, and `WalkNodeEvaluator` A* pathfinding) becomes the next dominant per-tick CPU and Young-Gen allocation bottleneck on high-entity survival servers (`ADR-131`).

However, unlike `.lca` disk serialization or `RegionizedWorldData` shell reuse, **mob AI touches load-bearing technical-survival mechanics**:
- **Iron farms** depend on exact `Villager` `GolemLastSeen` expiry, panic/sleep schedule boundaries, and line-of-sight checks against `Zombie` / `Pillager` triggers.
- **Trading halls, mob grinders, creeper farms, and raid farms** depend on `WalkNodeEvaluator` treating open trapdoors, coral fans, and scaffolding as walkable nodes, deterministic `GoalSelector` tie-breaking, and exact `RandomSource` draw order.

Popular Paper/Folia downstream forks (Purpur, LeafMC, Pufferfish DAB) routinely ship heuristic distance-based tick skipping, entity-activation-range throttling, or villager lobotomization toggles that silently alter farm rates and break technical survival contraptions. Those shortcuts violate GoldLeaf's core invariant (`goldleaf-ADR-001`): vanilla redstone and technical farm mechanics shall be preserved by default.

### 2. Recording the Boundary Before `v0.1`

Recording this ADR now establishes two guardrails before GoldLeaf `v0.1` ships:
1. **`v0.1` Scope Protection:** GoldLeaf `v0.1` shall ship **zero** modifications to vanilla `Brain<E>`, `GoalSelector`, `Sensor`, or `WalkNodeEvaluator` execution. The initial release remains strictly focused on provably vanilla-identical threading, `.lca` storage, worldgen batching, and telemetry/preflight SPIs.
2. **Preventing Ad-Hoc AI Hacks:** Any future Mob AI work in GoldLeaf (`v0.2+`) shall target the exact three-regime architecture and four-tier proof contract already specified in `ADR-131`, rather than introducing heuristic YAML toggles.

## Decision

GoldLeaf **defers all Mob AI optimizations past `v0.1`** and establishes a strict **Layer 1 SPI Seam + Layer 2 `ADR-131` Engine** boundary gated behind mandatory `P1..P4` technical-farm parity proofs.

### 1. Layer 1 — Future Thin Folia Core Seam (Post-`v0.1`, Zero Edits in `v0.1`)

When Mob AI work opens in a post-`v0.1` milestone, Layer 1 patches in `folia-server` shall remain zero-external-dependency, layered after Folia's patch stack (never touching `0004-Region-Threading-Base.patch`), and strictly owning-region-thread-only (`F-001`..`F-003`, `S-005`):

1. **`LevelChunkSection#sectionEpoch` Invalidation Counter:**
   - Exposes a primitive monotonic `int sectionEpoch` on `LevelChunkSection` incremented inline on block-state mutation (shared cleanly with `goldleaf-ADR-005` dirty-section tracking and `leafanvil-ADR-002`).
   - Enables $O(1)$ invalidation of cached static `PathType` nibbles without scanning neighboring blocks.
2. **`Brain<E>` & `GoalSelector` Pre-Filter SPI Seam:**
   - Exposes a minimal hook on `Brain#setMemoryWithExpiry`, `Brain#eraseMemory`, and `Brain#tick` to maintain a lossless `memoryPresentMask` bitmask and delegate behavior pre-filtering to an optional Layer 2 `BrainBitmaskGate` (`ADR-131` Regime 1).
   - Preserves an always-available runtime bypass (`mobAi.shadowVerifyMode` and `mobAi.regime1BitmaskEnabled = false`) so unaccelerated vanilla execution remains directly runnable for differential A/B verification.
3. **`WalkNodeEvaluator` & Confinement Taint Seam:**
   - Exposes an optional `PathType` nibble cache lookup hook keyed by `(sectionKey, sectionEpoch)` (`ChunkEpochPathTypeCache`) while keeping dynamic entity/door/fluid overrides on vanilla logic.
   - Exposes a `0`-tick fail-open stimulus invalidation hook (section-epoch change, entity bounding-box entry, damage/velocity impulse, player trade/leash/ride interaction, or chunk unload) for taint-gated Regime 2 confined-mob folding (`ConfinedMobEpochFolder`).
   - All hooks fail closed (`S-004`): any guard classification fault or shadow-mode mismatch logs a `WARNING` and falls back immediately to unaccelerated vanilla execution (`REQ-MOBAI-004`).

### 2. Layer 2 — External `ADR-131` Mob AI Engine (Opt-In Until `P1..P4` Pass)

Layer 2 implements the three regimes defined in `ADR-131` outside GoldLeaf's core patch stack:
- **Regime 1 (Lossless Bitmask & `byte[16]` Pre-Filter):** `BrainBitmaskGate` + `ChunkEpochPathTypeCache` — `100%` lossless active-set order and `RandomSource` parity (`REQ-MOBAI-001`).
- **Regime 2 (Taint-Gated Confined Folding):** `ConfinedMobEpochFolder` — folds stationary `1x1` confined mobs only after a quiet window with zero movement degree, zero `sectionEpoch` mutation, zero threat/redstone/player taint, and `:bytecode-api` (`BytecodeCausalGraphScanner`) confirmation of zero custom `Goal`/NMS hooks, with `0`-tick fail-open wakeup (`REQ-MOBAI-002`).
- **Regime 3 (Opt-In `Int8[16x16]` Policy Genomes):** `Int8PolicyGenomeEngine` — strictly isolated to explicitly tagged custom NPCs / minigame crowds and structurally prohibited from natural survival or technical-farm species (`REQ-MOBAI-003`).

### 3. Mandatory `P1..P4` Proof Gates (Non-Negotiable Release Prerequisite)

Per `ADR-131` (`REQ-MOBAI-005`) and `docs/dev/VERIFICATION_GATES.md` (Gates 1, 2, 5, 8, and 10), **no Mob AI optimization shall be merged as enabled-by-default in GoldLeaf** until all four automated proof tiers pass in CI:

1. **Proof Tier `P1` — Lockstep Shadow-Tick & `RandomSource` Equivalence (`ShadowBrainEquivalenceTest`):**
   - Cloned deterministic worlds (`>= 10,000` ticks, `>= 3` seeds, all vanilla `Brain`-driven species) verifying `falseNegativeCount == 0`, identical ordered active `BehaviorControl`/`Goal` sets, identical `RandomSource` draw count/state, identical `BlockPos`/`Vec3`, and bit-exact `FNV-1a-64` memory-state digests.
2. **Proof Tier `P2` — Golden Technical-Farm Contraption Suite (`TechnicalFarmParitySuite`):**
   - Headless `>= 24,000`-tick simulations verifying zero yield or timing drift across `IronFarmPanicFixture`, `TradingHallFoldWakeupFixture`, `PathfindingTrapdoorGrinderFixture`, and `RaidAndMobSwitchBoundaryFixture`.
3. **Proof Tier `P3` — `:bytecode-api` Purity & Mutation-Kill Gate (`Gate 2` & `Gate 5`):**
   - Deliberate 1-bit `requiredPresentMask`/`requiredAbsentMask` corruption and suppressed `sectionEpoch` increments shall deterministically fail `P1`/`P2`, and impure third-party `Goal#canUse()` subclasses shall be detected by `BytecodeCausalGraphScanner` and forced onto vanilla execution.
4. **Proof Tier `P4` — Differential MSPT & Eden Allocation Evidence (`Gate 10`):**
   - Same-machine, same-seed differential benchmark ($N \ge 1,000$ ticks after warmup) reporting `p50`/`p95`/`p99` tick time, `Brain` + `GoalSelector` + `PathNavigation` CPU share delta, Young-Gen allocation delta (`bytes/tick`), and `TechnicalFarmParitySuite` yield delta (`0` on deterministic seeds).

### 4. `goldleaf.yml` Surface (`v0.1` vs. Post-`v0.1`)

- In **`v0.1`**, `goldleaf.yml` exposes **zero** Mob AI toggles.
- Post-`v0.1`, any enabled regime shall expose minimal, explicit parity-verification flags (`mob-ai.regime1-bitmask`, `mob-ai.regime2-confined-folding`, `mob-ai.shadow-verify-mode`) defaulting to `false` until `P1..P4` pass in CI.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Adopt Purpur / Pufferfish-style DAB (Dynamic Activation of Brain) or distance-based entity throttling** | Alters villager restock windows, breaks iron/raid farm timing when players stand at AFK spots, and violates GoldLeaf's vanilla-fidelity invariant (`goldleaf-ADR-001`). |
| **Ship Regime 1 / Regime 2 Mob AI patches inside GoldLeaf `v0.1`** | Building and validating `ShadowBrainEquivalenceTest` and `TechnicalFarmParitySuite` (`P1..P4`) would delay `v0.1`'s storage, threading, and telemetry wins or tempt shipping unverified AI patches. |
| **Run `Brain#tick` or `GoalSelector` off-thread asynchronously across live chunks** | Violates Folia region ownership (`F-001`..`F-003`) and races unsynchronized NMS `Level`, `EntityLookup`, and Bukkit plugin listeners (`S-001`, `S-005`). |
| **Leave Mob AI undocumented until post-`v0.1`** | Leaves an architectural vacuum where ad-hoc entity-activation patches could creep into the fork without clear Layer 1/Layer 2 boundaries or proof gates. |

## Consequences

- **Positive:**
  - Keeps GoldLeaf `v0.1` lean, shippable, and 100% vanilla-identical on mob behavior.
  - Locks in `ADR-131`'s `P1..P4` mechanical proof contract (`ShadowBrainEquivalenceTest`, `TechnicalFarmParitySuite`) as a permanent guardrail against Purpur-style gameplay regressions.
  - Aligns future `sectionEpoch` invalidation cleanly with `goldleaf-ADR-005` (`.lca` dirty-section tracking) and `goldleaf-ADR-006` (`ChunkExecutionMask` / `EmpiricalFarmBlackBox` taint semantics).
- **Negative / Trade-offs:**
  - High-entity survival servers will not see `Brain<E>` or `WalkNodeEvaluator` CPU reductions in `v0.1` (they still benefit from Folia region parallelism via `goldleaf-ADR-003` and optional `LeafSleuth` black-box governance via `goldleaf-ADR-006`).
  - Engineering `ShadowBrainEquivalenceTest` and `TechnicalFarmParitySuite` adds upfront test-harness investment before post-`v0.1` AI speedups can be enabled by default.

## References

- Core Mob AI architecture & proof contract:
  - `docs/adr/ADR-131-three-regime-mob-ai-encoding-and-technical-farm-parity-proofs.md` (`REQ-MOBAI-001`..`REQ-MOBAI-005`, `P1..P4`)
  - `docs/dev/VERIFICATION_GATES.md` (Gates 1, 2, 5, 8, and 10)
  - `docs/dev/RULES.md` (`F-001..F-003`, `S-001..S-007`, `Rule D-002`, `Rule D-010`)
- Related `GoldLeaf` ADRs:
  - `GoldLeaf/docs/adr/goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-003-gradient-modeled-region-threading-and-small-machine-overhead-elimination.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-005-lca-format-adoption-across-disk-memory-and-network.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-006-native-chunk-telemetry-and-execution-mask-spi.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-007-plugin-safety-preflight-and-runtime-hazard-spi.md`
