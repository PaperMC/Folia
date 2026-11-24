# goldleaf-ADR-011 — Folia Operational Ergonomics, Region-Context Watchdog Diagnostics, and Region-Safe Admin Commands

**Status:** Accepted (v0.1 Scope Implemented)
**Date:** 2026-11-21

## Context

### 1. Operational Friction in Stock Folia

Stock Folia is deliberately minimal in day-to-day server administration tooling. Across real-world deployments, operators routinely switch from stock Folia to downstream forks (`Luminol`, `Canvas`, `Kaiiju`) not for exotic gameplay features, but for **basic operational observability and admin ergonomics**:

1. **Opaque Watchdog Dumps (`FoliaWatchdogThread`):**
   - Inspecting `/home/leaf26/GoldLeaf/folia-server/src/minecraft/java/io/papermc/paper/threadedregions/FoliaWatchdogThread.java` (lines 66–89) confirms that when a tick region stalls for `> 5s`, `FoliaWatchdogThread` logs only:
     - the world name and `region.region.region.getCenterChunk()`, and
     - a raw `WatchdogThread.dumpThread(...)` stack trace.
   - It omits **which players are inside the stalled region**, **which chunks hold the highest entity/block-entity/redstone activity** (`goldleaf-ADR-006`), and **whether any plugin on the call stack was flagged by boot-time safety preflight** (`goldleaf-ADR-007`). Admins on 200+ player servers must manually correlate chunk coordinates to players and lag sources while the region is stalled.
2. **Sparse Live Region Telemetry (`CommandServerHealth`):**
   - `CommandServerHealth.java` (`/tps`) prints a static text snapshot, but admins monitoring multi-region servers lack a lightweight, opt-in live region HUD (actionbar/bossbar/tab summary showing current region MSPT, tick-thread utilization, and active region count) without installing heavy polling plugins.
3. **Startup & Migration Opacity:**
   - Operators evaluating a new fork fear silent misconfiguration (hardware thread-count mismatches from `goldleaf-ADR-003`, unsafe Bukkit plugins from `goldleaf-ADR-007`, or world-format lock-in from `goldleaf-ADR-005`).
4. **Cross-Region Command & World-Management Ergonomics:**
   - Vanilla/Paper admin workflows (`/tp`, `/locate`, `/fill`, `/clone`, dynamic world load/unload) have rough edges or partial disablement on stock Folia that surprise administrators migrating from Paper.

## Decision

GoldLeaf adopts a **Region-Context Observability & Operational Ergonomics Layer** built directly on top of `goldleaf-ADR-003`, `005`, `006`, and `007`—keeping Layer 1 hooks minimal and read-only (`S-005`) while making GoldLeaf the clearest, easiest-to-diagnose Folia distribution to operate in production.

### 1. Layer 1 — Region-Context Watchdog & Health Snapshot SPI (`folia-server`)

1. **Enriched `FoliaWatchdogThread` Diagnostic Context (`FoliaWatchdogThread.java`):**
   - Add a non-blocking, read-only `WatchdogContextContributor` SPI invoked in `FoliaWatchdogThread#run` (lines 75–89) immediately before `WatchdogThread.dumpThread(...)`.
   - Every contributor must be **lock-free / try-lock-only** (`S-005`) and wrapped in fail-closed exception isolation (`S-004`) so a diagnostic fault can never suppress or deadlock the watchdog stack dump.
   - Default built-in context appended to stalled-region reports:
     - **Region Identity:** World, center chunk, bounding chunk box, loaded chunk count, and tick-thread name.
     - **Nearby Players (Snapshot):** Up to $K$ (default `8`) players currently owned by that region (name + block coordinates), read from a lock-free snapshot without mutating region state.
     - **Hot Chunks (`goldleaf-ADR-006`):** Top-5 chunks in the stalled region ranked by primitive `ChunkActivityCounters` (entity count, block-entity ticks, redstone/fluid updates).
     - **Plugin Attribution (`goldleaf-ADR-007`):** Matches stack frames against `PluginSafetyPreflight` metadata, highlighting any plugin previously flagged with `UNSAFE_SYNC_IO`, `CROSS_REGION_ACCESS`, or `GLOBAL_LOCK` hazards.
2. **Read-Only Per-Region Telemetry View (`CommandServerHealth` / `TickRegions`):**
   - Expose a clean Layer 1 read-only snapshot API for per-region `TPS`, `MSPT` (`p50`/`p95`/`p99`), region count, entity/chunk counts, and hardware thread-curve status (`goldleaf-ADR-003`), consumable by `/tps`, live admin HUDs, and external dashboards (`LeafConfig` / `LeafSleuth`) without polling NMS internals.

### 2. Layer 2 — Admin UX, Live HUDs & Honest Startup Summary

1. **Opt-In Live Region HUD (`/goldleaf hud`):**
   - Permission-gated (`goldleaf.command.hud`) toggle for administrators displaying their current region's `MSPT`, `TPS`, chunk-entity count, and region ID in the actionbar or bossbar, updated from the read-only telemetry snapshot on a configurable interval (default `20` ticks, zero overhead when no admin has HUD enabled).
2. **Honest Startup Hardware, Storage & Plugin Summary:**
   - On server startup completion, emit one concise, structured diagnostic block summarizing:
     - Detected physical cores / RAM and selected `goldleaf-ADR-003` region tick-thread count (with explicit warning if overridden beyond the hardware sweet spot).
     - Active storage format per world (`.lca v3` vs `.mca`, `SEC-DED` status, and reminder of the reversible MIT `.lca <-> .mca` CLI converter from `goldleaf-ADR-005`).
     - `goldleaf-ADR-007` plugin preflight verdict counts (`SAFE`, `WARN`, `HAZARD`) and pointer to the detailed report.
3. **Region-Safe Admin Command & Dynamic World Ergonomics:**
   - Ensure cross-region `/tp`, `/locate` (async-bounded radius via `goldleaf-ADR-002`), and bounded `/fill` / `/clone` dispatch cleanly across region boundaries using Folia's `RegionScheduler` / `Entity#teleportAsync` without stalling tick threads (`S-005`) or failing silently (`S-004`).
   - Document and preserve a clean, region-safe dynamic world load/unload lifecycle (verifying region drain and `FileChannel` release per `S-002`) so multi-world plugins (`Multiverse`-class workflows) operate predictably.

### 3. Mandatory Verification Gates

1. **Synthetic Region-Stall Watchdog Test:** Trigger a controlled `> 5s` stall in a test region and assert that `FoliaWatchdogThread` emits the world/center chunk, player snapshot, top `ChunkActivityCounters` chunk, and stack-frame plugin attribution without deadlocking or throwing.
2. **Watchdog Fault-Isolation Test (`S-004`):** Inject a throwing `WatchdogContextContributor` and assert that the base `WatchdogThread.dumpThread(...)` output still prints cleanly.
3. **Zero-Overhead Idle Telemetry Check:** Verify that when no admin HUD is active, region tick loops perform zero HUD formatting or packet allocations.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Leave watchdog diagnostics to stock `FoliaWatchdogThread` + external Spark profiler** | Spark requires manual start or background sampling overhead and does not correlate `goldleaf-ADR-006` primitive chunk counters or `goldleaf-ADR-007` preflight hazard flags directly inside crash/stall logs. |
| **Build a heavy web panel or Purpur-style command suite directly into `folia-server`** | Bloats Layer 1 with UI/HTTP dependencies; rich web dashboards belong in Layer 2 / external tools (`LeafConfig`, `LeafSleuth`), while GoldLeaf supplies the lean read-only telemetry SPI and CLI/HUD essentials. |
| **Take synchronized region locks inside `FoliaWatchdogThread` to inspect live world state** | Guaranteed deadlock risk when the target region thread is already stuck holding a lock (`S-005`). Watchdog contributors must be strictly lock-free / snapshot-only. |

## Consequences

- **Positive:**
  - Turns opaque Folia stall stack traces into immediately actionable incident reports (region, players, hot chunks, flagged plugins).
  - Closes the day-to-day operational ergonomics gap that drives Folia administrators to `Luminol` / `Canvas`.
  - Connects `goldleaf-ADR-003` (hardware sizing), `ADR-005` (`.lca` reversibility), `ADR-006` (`ChunkActivityCounters`), and `ADR-007` (plugin preflight) into a cohesive operator experience.
- **Negative / Trade-offs:**
  - Lock-free player/region snapshots in watchdog dumps are eventually consistent by design (a player mid-transfer during a stall may reflect their previous tick's position).
  - Maintaining cross-region `/fill` / `/clone` / dynamic-world ergonomics requires targeted regression tests across `26.x` upgrades.

## References

- Materialized Folia `26.3.x` seams in `/home/leaf26/GoldLeaf`:
  - `folia-server/src/minecraft/java/io/papermc/paper/threadedregions/FoliaWatchdogThread.java` (lines 45–91)
  - `folia-server/src/minecraft/java/io/papermc/paper/threadedregions/commands/CommandServerHealth.java`
  - `folia-server/src/minecraft/java/io/papermc/paper/threadedregions/TickRegions.java`
- Related `GoldLeaf` ADRs:
  - `GoldLeaf/docs/adr/goldleaf-ADR-002-sub-tick-interrupt-scheduling-and-latency-tiered-routing.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-003-gradient-modeled-region-threading-and-small-machine-overhead-elimination.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-005-lca-format-adoption-across-disk-memory-and-network.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-006-native-chunk-telemetry-and-execution-mask-spi.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-007-plugin-safety-preflight-and-runtime-hazard-spi.md`
  - `docs/dev/RULES.md` (`F-001..F-003`, `S-002`, `S-004`, `S-005`)
