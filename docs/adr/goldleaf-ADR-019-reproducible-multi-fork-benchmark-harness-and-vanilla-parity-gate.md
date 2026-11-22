# goldleaf-ADR-019 — Reproducible Multi-Fork Benchmark Harness and Automated Vanilla-Parity Verification Gate

**Status:** In Progress
**Date:** 2026-11-22

## Context

Server administrators in the Minecraft ecosystem are rightly skeptical of fork performance claims: many Paper/Folia forks advertise large TPS or chunk-speed gains using cherry-picked hardware, silent gameplay nerfs (DAB, crushed activation ranges, skipped hopper ticks), or unrepeatable manual tests.

GoldLeaf already specifies the building blocks for objective measurement across its ADR suite:
- `goldleaf-ADR-003` (4–8 core workstation barbell scaling curve),
- `goldleaf-ADR-005` & `014` (`.lca` vs `.mca` storage throughput, heap residency, and bit-exact reversibility),
- `goldleaf-ADR-007` (`TickHazardMonitor` exact `SYNC_CHUNK_LOAD`, `TICK_STALL`, and `WRONG_REGION_ACCESS` counters),
- `goldleaf-ADR-008` & `013` (`P1..P4` technical-farm parity and exact-vanilla redstone/hopper equivalence),
- `goldleaf-ADR-015` (`SyntheticPlayerService` region-safe deterministic bot workloads),
- `goldleaf-ADR-016` (cold vs. warm Leyden AOT direct-classpath boot waterfall).

However, there is not yet a single architectural specification binding these primitives into a **reproducible, self-verifying, side-by-side multi-fork benchmark and vanilla-parity harness** that both CI and external operators can execute on identical seeds and hardware.

## Decision

GoldLeaf establishes a **Reproducible Multi-Fork Benchmark & Vanilla-Parity Harness** under `devstack/` (reusing `SyntheticPlayerService` and `TickHazardMonitor` where available) with two mandatory suites and strict provenance/fail-closed rules (`S-004`).

### 1. Suite A — 4-Way Headless Comparison Matrix (`Paper` vs `LeafMC` vs Stock `Folia` vs `GoldLeaf`)

A single parameterized runner script (e.g., `devstack/run-goldleaf-benchmark.sh`) shall execute identical worlds, seeds, JVM flags, and deterministic bot schedules across all four server JARs and emit structured JSON + Markdown scorecards covering four proof axes:

| Proof Axis | Standardized Workload | Required Metrics |
|---|---|---|
| **1. Barbell Floor (4–8 Core Workstation)** | `20` deterministic exploring bots (`SyntheticPlayerService` / headless protocol clients) + `1` active technical redstone/farm district on a `4–8` core CPU budget (`taskset` / cgroup pinned) | Per-region `p50` / `p95` / `p99` MSPT, isolated redstone-region vs. explorer-region TPS, physical core utilization |
| **2. Storage & Worldgen Throughput** | Fixed-radius chunk pre-generation + steady-state exploration + `/stop` full save drain across `.mca`, `.linear` (LeafMC), and `.lca` (`LCA-ICD-3.0`) | Sustained chunks/sec, `/stop` drain duration (ms), total world disk bytes, steady-state live heap MB (post-GC), `SEC-DED` verify status |
| **3. Operational & Boot Polish** | Cold first boot vs. warm restart (stock bundler vs. direct-classpath Leyden AOT per `goldleaf-ADR-016`) + static patch-surface census | Wall-clock to `Done!`, server `Done (...)`, `/stop` drain, Layer 1 `.patch` file count and net NMS diff lines |
| **4. Runtime Hazard & Leak Audit** | `goldleaf-ADR-007` `TickHazardMonitor` counters (`SYNC_CHUNK_LOAD`, `TICK_STALL`, `WRONG_REGION_ACCESS`) + chunk-ticket/file-handle delta across load/unload cycles | Zero `SYNC_CHUNK_LOAD`, zero `WRONG_REGION_ACCESS`, zero handle leaks (`S-002`, `S-005`) |

### 2. Suite B — Automated Vanilla-Parity CI Gate (Default Configs)

Every GoldLeaf release candidate shall pass an automated behavioral-parity suite executed under **default out-of-the-box configs** (zero custom tuning) and compared against a stock Folia/Vanilla baseline:

1. **Redstone & Storage-Hall Timing (`goldleaf-ADR-013`):**
   - Deterministic fixture chunks containing comparator clocks, multi-stage hopper chains, item sorters, and piston/flying-machine circuits verifying identical item transfer counts, tick-for-tick ordering, and comparator pulse widths.
2. **Technical Farm Mechanics (`goldleaf-ADR-008` `P1..P4`):**
   - Headless/synthetic-player verification that iron-golem spawning, villager trading/restocking, mob-farm spawning rates, and raid/portal mechanics exhibit zero silent throttling under default configs.
3. **Reversible Storage Equivalence (`goldleaf-ADR-014`):**
   - Round-trip `.mca -> .lca -> .mca` `--verify` parity check on the benchmark world fixture confirming `100%` chunk and NBT semantic equivalence.

### 3. Provenance, Freshness & Fail-Closed Guardrails (`S-004`)

To prevent stale or misleading benchmark artifacts:

1. **Full Provenance Header:** Every JSON/Markdown scorecard shall record the exact server JAR SHA-256, Git commit hash, JDK version (`java -version`), JVM flags, `HardwareEnvelope` (physical cores, RAM, OS kernel), world seed, and active config digest.
2. **Stale-Artifact Rejection (`S-004`):** The harness and report aggregator shall verify that every parsed log/metric file timestamp is strictly newer than the tested server JAR mtime and fail closed (non-zero exit) on missing metrics, server crashes, watchdog stalls, or incomplete `/stop` drains.
3. **Zero Silent Fallback:** If a requested fork JAR or required workload fixture is missing or fails to boot, the runner shall abort with an explicit diagnostic rather than emitting a partial comparison table.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| **Publish ad-hoc one-off Spark screenshots and manual timings** | Unreproducible, easily dismissed by skeptical server operators, and prone to stale-JAR or hardware-mismatch errors. |
| **Benchmark only GoldLeaf vs. Stock Folia** | Fails to address the real migration decision for Paper/LeafMC administrators (single-threaded Paper + async/DAB presets vs. regionized GoldLeaf with vanilla fidelity). |
| **Run parity tests only with custom "technical" config overrides** | Hides default-config regressions; GoldLeaf's core promise is vanilla fidelity **by default** (`goldleaf-ADR-013`, `014`). |

## Consequences

- **Positive:**
  - Provides verifiable, operator-reproducible proof across performance, storage, boot time, patch maintainability, and vanilla fidelity.
  - Catches MSPT, allocation, `.lca` round-trip, and redstone/farm regressions automatically before release tags.
  - Consolidates all multi-fork acceptance/benchmark orchestration into versioned `devstack/` scripts rather than ad-hoc commands.
- **Negative / Trade-offs:**
  - Running the full 4-way matrix requires downloading pinned Paper/LeafMC/Folia reference JARs and takes several minutes on CI or a local workstation (mitigated by separating the fast Vanilla-Parity CI Gate from the full 4-way release benchmark).

## References

- Related `GoldLeaf` ADRs:
  - `GoldLeaf/docs/adr/goldleaf-ADR-003-gradient-modeled-region-threading-and-small-machine-overhead-elimination.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-005-lca-format-adoption-across-disk-memory-and-network.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-007-plugin-safety-preflight-and-runtime-hazard-spi.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-008-mob-ai-optimization-boundary-and-farm-parity-gate.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-013-vanilla-faithful-micro-optimization-boundary-and-section-epoch-reuse.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-014-zero-friction-migration-cli-honest-auto-tuner-and-technical-parity-surface.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-015-region-safe-fake-player-and-bot-spi.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-016-boot-shutdown-telemetry-leyden-aot-and-plugin-warmup.md`
- Repo verification & devstack conventions:
  - `docs/dev/RULES.md` (`S-002`, `S-004`, `S-005`)
  - `docs/dev/VERIFICATION_GATES.md`
