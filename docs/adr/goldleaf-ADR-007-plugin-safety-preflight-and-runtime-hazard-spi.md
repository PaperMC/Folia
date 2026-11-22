# goldleaf-ADR-007 — Off-Thread Plugin Safety Preflight and Opt-In Runtime Hazard SPI

**Status:** In Progress
**Date:** 2026-11-21

## Context

### 1. Folia Plugin Adoption Friction & Runtime Hazard Blind Spots

The primary operational barrier to adopting Folia (`26.3.x+`) is plugin incompatibility: server operators frequently install Bukkit/Paper plugins that silently perform synchronous chunk or disk I/O on region tick threads (`S-005`), spawn unmanaged raw `Thread` or `ExecutorService` workers outside Folia's `RegionScheduler` (`F-001`..`F-003`), leak per-player session references across disconnects (`S-002`), or trigger wrong-region state mutations. On stock Folia, operators discover these defects only after a production region thread crashes or stalls.

At the same time, continuous verification of GoldLeaf's own Layer 1/Layer 2 innovations—`.lca` CoW storage (`goldleaf-ADR-005`), `RegionizedWorldData` shell reuse (`goldleaf-ADR-003`), and sub-tick interrupt scheduling (`goldleaf-ADR-002`)—requires an automated, deterministic regression and memory-retention gate.

### 2. Proven Foundations from `LeafFuzz` and `:bytecode-api`

`LeafFuzz` (`leaffuzz-ADR-001`, `leafsleuth-ADR-006`) and the standalone `:bytecode-api` SDK already solve both halves of this problem in production-tested modules:

1. **Zero-Classloading Static Analysis (`:bytecode-api`):**
   - `BytecodeCausalGraphScanner#scanJar` parses `plugins/*.jar` directly from `.class` bytecode without loading classes into the JVM.
   - `BytecodeParityAnalyzers` detects synchronous main/region-thread I/O contagion (`detectSyncMainThreadIoContagion`), unmanaged thread spawns (`detectUnmanagedThreadSpawns`), per-player/entity collection leaks (`detectSessionEntityCollectionLeaks`), and unclosed resources (`detectUnclosedResourceLeaks`).
   - `BytecodeCweAnalyzers#analyzeParsedClasses` evaluates 18 deterministic CWE and reliability bytecode patterns, while `JarClassOwnershipIndex` maps classes back to owning archives.
2. **External Runtime Fuzzing & Retention Screening (`LeafFuzz`):**
   - `NmsEmbeddedPlayerSession`, `PersonaPolicyBotActor`, `LeafFuzzRuntime`, and `ClassRetentionScreener` drive deterministic multi-bot workloads (`chunk_border_thrash`, `quit_lt1s`, command fuzzing) and surface scorecards via `FuzzDiagnosticSnapshotSource` (`:config-bit-api`).
   - However, on stock Paper/Folia, `LeafFuzz` had to infer `hazard:sync-chunk:` events, tick stalls (`> 100 ms`), and wrong-region access indirectly via external log scraping, stack polling, or reflective hooks.

## Decision

GoldLeaf introduces a **two-layer Plugin Safety Preflight & Runtime Hazard SPI** (`goldleaf-ADR-001` compliant): **Layer 1** adds a thin boot-time plugin-archive discovery hook and an opt-in $O(1)$ tick-thread hazard counter/callback SPI in dedicated post-Folia patches (without editing `0004-Region-Threading-Base.patch`), and **Layer 2** integrates `:bytecode-api` and `LeafFuzz` for startup reporting and automated CI gating.

### 1. Layer 1 — Thin Folia Core SPIs (`folia-server`, zero external dependencies)

All Layer 1 hooks reside in `dev.leafcraft.goldleaf.diagnostics.*` behind 1–3 line call sites and carry zero compile-time dependency on `:bytecode-api` or `LeafFuzz`.

1. **Boot-Time Plugin-Archive Discovery Hook (`PluginPreflightDiscoveryHook`):**
   - Invoked during Paper/Folia plugin archive discovery *before* plugin classes are loaded or `onEnable()` executes.
   - Supplies an immutable list of discovered archive `Path` entries (`plugins/*.jar`) to registered preflight providers on a dedicated off-thread bootstrap executor (`S-005` compliant; zero tick-thread I/O and zero runtime overhead once boot finishes).
   - **Zero-Classloading Guarantee:** Providers inspect archive bytes only; Layer 1 never hands out live `PluginClassLoader` instances during preflight.
   - **Fail-Closed Archive Handling (`S-004`):** If an archive is corrupt, truncated, unreadable (`IOException` / `ZipException`), or exceeds configured parse bounds, the preflight pipeline shall not silently skip it: it logs a structured `WARNING` with the archive path and exception, records an explicit `UNREADABLE_ARCHIVE` finding in the preflight report, isolates the fault so remaining archives still complete analysis, and aborts server boot when `diagnostics.plugin-safety-preflight-fail-closed: true` (CI strict mode).
2. **Opt-In $O(1)$ Tick-Thread Runtime Hazard SPI (`TickHazardMonitor` / `TickHazardListener`):**
   - Exposes atomic $O(1)$ counters and an optional listener callback gated by a single `volatile boolean hazardHooksActive` flag (`false` by default in production; `~0 ns` idle cost):
     - **Synchronous Chunk Load Hazard (`SYNC_CHUNK_LOAD`):** Incremented when a region or global tick thread triggers a blocking chunk load (`S-005`), capturing `(worldName, chunkX, chunkZ, callerClass)` when a listener is active.
     - **Tick-Stall Budget Breach (`TICK_STALL`):** Fired when a region or global tick exceeds `diagnostics.runtime-hazard-stall-threshold-ms` (default `100 ms`), reporting `(regionId, durationNanos, tickPhase)`.
     - **Region-Ownership Violation (`WRONG_REGION_ACCESS`):** Incremented when a guard in `TickThread#ensureTickThread` rejects a cross-region entity, block, or chunk mutation (`F-001`..`F-003`).
   - Listener callbacks execute non-blockingly and must never perform I/O (`S-005`); exceptions thrown by a listener are logged once at `WARNING` and automatically quarantine the faulty listener rather than crashing the tick thread (`S-004`).

### 2. Layer 2 — `:bytecode-api` Preflight Report & `LeafFuzz` CI Gate

1. **Startup Folia-Safety Preflight Report (`:bytecode-api`):**
   - A lightweight Layer 2 preflight module attaches to `PluginPreflightDiscoveryHook` and runs `BytecodeCausalGraphScanner#scanJar`, `BytecodeParityAnalyzers`, `BytecodeCweAnalyzers`, and `JarClassOwnershipIndex` off-thread.
   - Emits a concise startup summary table (per plugin JAR: sync tick-thread I/O contagion, unmanaged thread spawns, session collection leaks, unclosed resources, and CWE findings) so operators see Folia safety risks *before* a region thread faults.
   - Writes an optional machine-readable JSON/Markdown report under `logs/goldleaf-preflight/` for CI archival and `LeafConfig` inspection.
2. **Exact `LeafFuzz` Regression & Leak Gate (`LeafFuzz` + `:config-bit-api`):**
   - When `LeafFuzz` (`PaperLeafFuzzPlugin` / `LeafFuzzRuntime`) runs on GoldLeaf with `diagnostics.runtime-hazard-hooks: true`, it subscribes to `TickHazardMonitor` instead of scraping logs or injecting brittle hooks.
   - Exact `SYNC_CHUNK_LOAD`, `TICK_STALL`, and `WRONG_REGION_ACCESS` signals feed directly into `LeafFuzzScorecardReport` and `FuzzDiagnosticSnapshotSource` (`diagnosticFuzzRuntimeHazards()`).
   - GoldLeaf CI runs `LeafFuzz` (`NmsEmbeddedPlayerSession`, `PersonaPolicyBotActor`, `chunk_border_thrash`, `quit_lt1s`, `ClassRetentionScreener`) against every release candidate to prove zero `.lca` CoW handle leaks (`S-002`), zero `RegionizedWorldData` reuse retention leaks (`goldleaf-ADR-003`), and zero tick-thread sync chunk loads (`S-005`).
   - On stock Paper/Folia, `LeafFuzz` seamlessly retains its existing external-observation path (`leaffuzz-ADR-001`).

### 3. Minimal `goldleaf.yml` Configuration Surface

Extends `GoldLeafConfig.Diagnostics` (`GoldLeafConfig.java`) with four minimal keys:

```yaml
diagnostics:
  log-hardware-envelope-on-startup: true
  plugin-safety-preflight: true             # Off-thread zero-classloading boot report (0 runtime cost after boot)
  plugin-safety-preflight-fail-closed: false # If true, abort startup on corrupt JARs or critical Folia hazards (CI mode)
  runtime-hazard-hooks: false               # Opt-in O(1) tick-thread hazard SPI for LeafFuzz / QA (false in prod)
  runtime-hazard-stall-threshold-ms: 100    # Tick duration threshold for TICK_STALL hazard events (>= 20)
```

`GoldLeafConfig#validate()` fails closed (`ConfigurateException`) if `runtime-hazard-stall-threshold-ms < 20`.

### 4. Mandatory Verification & Non-Regression Gates

1. **Zero-Classloading & Boot-Time Budget Gate:** Verify `PluginPreflightDiscoveryHook` completes off-thread without loading any scanned plugin classes (`Instrumentation` / classloader assertion) and adds zero post-boot tick overhead.
2. **Fail-Closed Corrupt-JAR Gate (`S-004`):** Feed a truncated/corrupt `.jar` alongside valid plugins and verify structured `WARNING` logging, an `UNREADABLE_ARCHIVE` finding, continued scanning of valid JARs when `fail-closed: false`, and deterministic boot abort when `fail-closed: true`.
3. **Idle & Active Hazard Gate:** Verify `runtime-hazard-hooks: false` incurs `~0 ns` overhead on hot tick loops, and verify `LeafFuzz` receives exact `SYNC_CHUNK_LOAD`, `TICK_STALL`, and `WRONG_REGION_ACCESS` counts when enabled.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Run bytecode scanning synchronously on the main bootstrap thread or during `onEnable()`** | Blocks server startup on large plugin directories and risks interfering with plugin classloading order. Off-thread archive discovery before classloading is faster and `S-005` clean. |
| **Reflectively inspect loaded plugin classes after `onEnable()`** | Triggers static initializers, cannot catch hazards before a plugin starts mutating world state, and misses unloaded classes in plugin JARs. `:bytecode-api` already performs zero-classloading `.class` analysis. |
| **Keep `LeafFuzz` strictly external without a Layer 1 hazard SPI** | Forces brittle log scraping, safepoint polling, and imprecise stall/sync-chunk attribution; a 3-counter opt-in Layer 1 SPI makes `LeafFuzz` exact and zero-hack on GoldLeaf. |
| **Enable `runtime-hazard-hooks: true` unconditionally in production** | Unnecessary stack-capture or callback overhead for standard production servers; keeping it opt-in (`false` by default) preserves zero runtime cost while giving QA and CI exact signals. |

## Consequences

- **Positive:**
  - Gives Folia server operators an immediate, zero-classloading **Folia-Safety Preflight Report** at startup identifying sync I/O contagion, unmanaged threads, session leaks, and CWE patterns before region threads fault.
  - Turns `LeafFuzz` into an exact, zero-hack automated regression and retention-leak CI gate for GoldLeaf's storage, threading, and scheduling patches.
  - Fails closed (`S-004`) on corrupt archives and invalid diagnostic thresholds while keeping production runtime overhead at `~0 ns`.
- **Negative / Trade-offs:**
  - Adds a small boot-time I/O pass over `plugins/*.jar` (mitigated by running off-thread and allowing operators to disable `diagnostics.plugin-safety-preflight` if desired).
  - Static bytecode analysis can occasionally flag benign patterns (e.g., guarded off-thread helpers), so the default mode reports warnings without blocking startup unless an operator or CI job opts into `plugin-safety-preflight-fail-closed: true`.

## References

- GoldLeaf core & config:
  - `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/config/GoldLeafConfig.java`
  - `GoldLeaf/docs/adr/goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-003-gradient-modeled-region-threading-and-small-machine-overhead-elimination.md`
- `:bytecode-api`, `LeafFuzz`, and `:config-bit-api`:
  - `resource/LeafFuzz/docs/adr/leaffuzz-ADR-001-cross-platform-qc-and-discovery-fuzzer.md`
  - `resource/LeafSleuth/docs/adr/leafsleuth-ADR-006-in-jvm-forensics-and-discovery-fuzzing.md`
  - `api/bytecode-api/src/main/java/io/github/dailystruggle/bytecode/BytecodeCausalGraphScanner.java`
  - `api/bytecode-api/src/main/java/io/github/dailystruggle/bytecode/BytecodeParityAnalyzers.java`
  - `api/bytecode-api/src/main/java/io/github/dailystruggle/bytecode/BytecodeCweAnalyzers.java`
  - `helpers/stresstest-core/src/main/java/net/leaf/sleuth/common/fuzz/JarClassOwnershipIndex.java`
  - `helpers/stresstest-core/src/main/java/net/leaf/sleuth/common/resource/ClassRetentionScreener.java`
  - `resource/LeafFuzz/leaffuzz-common/src/main/java/net/leaf/fuzz/common/LeafFuzzRuntime.java`
  - `resource/LeafFuzz/leaffuzz-common/src/main/java/net/leaf/fuzz/common/NmsEmbeddedPlayerSession.java`
  - `resource/LeafFuzz/leaffuzz-common/src/main/java/net/leaf/sleuth/common/fuzz/PersonaPolicyBotActor.java`
  - `resource/LeafFuzz/leaffuzz-paper/src/main/java/net/leaf/fuzz/paper/PaperLeafFuzzPlugin.java`
  - `api/config-bit-api/src/main/java/io/github/dailystruggle/bit/config/FuzzDiagnosticSnapshotSource.java`
