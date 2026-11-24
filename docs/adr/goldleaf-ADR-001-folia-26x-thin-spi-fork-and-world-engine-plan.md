# goldleaf-ADR-001 — Folia `26.3.x` Thin-SPI Fork, Barbell Performance Model, and `.lca` / `LeafShard` World-Engine Plan

**Status:** In Progress
**Date:** 2026-11-20

## Context

### 1. Core Thesis & Target Audience

GoldLeaf is built first for **technical Minecraft players and server operators** running Folia, anchored on a single architectural invariant:

> **Worst case for a technical redstoner is not worst case for everyone else.**

Single-tick-loop Paper forks (Paper, Purpur, LeafMC) handle localized load spikes—large storage halls, perimeter farms, quarry districts—either by letting one contraption drag the entire server's TPS down, or by shipping static global degradation toggles (activation ranges, Dynamic Activation of Brain, async hacks on shared state) that silently break vanilla redstone timings and farm mechanics. Clean-room rewrites (Rust/C++ clones) lower idle RAM but lack Bukkit/Paper/Folia plugin ecosystems and years of vanilla technical parity.

GoldLeaf targets the **barbell ends of the hardware spectrum**, leaving the mid-range single-VPS Paper market to existing forks:

- **Floor (surplus / secondhand hardware):** 4–8 core ex-office PCs and surplus engineering-school workstations (e.g., `i5-8500T` 6C/6T, `i7-9700` 8C/8T, Xeon/Precision/ThinkStation towers; 8–16 GB DDR4; NVMe/SATA or spinning HDD; iGPU-only or surplus workstation GPU). Weak single-core IPC makes single-threaded Paper choke while leaving 80%+ of CPU cores idle; Folia + multi-box sharding turns these machines into capable servers.
- **Ceiling (mega-servers):** 500–2,000+ players on one seamless world across multiple physical nodes, competing directly with closed-source horizontal Folia sharding stacks (such as 6b6t's Fox).

Both ends of the barbell share the same four bottlenecks—storage/save stalls, heap density, multi-core/multi-node scaling, and worldgen bursts—parameterized only by hardware envelope rather than by gameplay-breaking YAML toggles.

### 2. What Admins Actually Adopt Forks For

To earn organic adoption as an open-source Folia fork (and establish `GoldLeaf` independently on technical merit), GoldLeaf must directly supply—or structurally outclass—the concrete capabilities admins seek from existing performance forks:

1. **Lower MSPT under heavy mobs and farms:** Supplied structurally via Folia region blast-radius isolation, fixed 4–8 core auto-threading, and multi-node `LeafShard` scaling—with zero vanilla mechanics breakage.
2. **Region storage compression and fast saves:** Supplied via native `.lca` (`LeafAnvil`) storage (~50% smaller disk, ~15.5× faster shutdown saves, 4-tier `SEC-DED` self-healing, cold-section RAM savings, and an open MIT `.lca <-> .mca` round-trip converter to eliminate lock-in fear).
3. **Higher chunk generation throughput:** Supplied first by eliminating `.mca` save/load and `CompoundTag`/`NbtIo` stalls that starve generator threads during pre-gen and exploration, and second by `LeafGen` (developed separately) with an opt-in fast mode (>100% throughput with explicit minor vanilla deviation, disabled by default).
4. **Fast updates across `26.x` drops:** Enabled by baselining at unobfuscated `26.3+` (JDK 25) and keeping the fork patch stack razor-thin (~4–5 SPI seams instead of 200+ Purpur/Gale/Leaf patches).
5. **Technical client protocol support & wire efficiency:** Built-in or clean-SPI support for technical SMP protocols (Servux, Syncmatica, Jade, AppleSkin), native Netty transport (`io_uring`), and `LCAS` wire-delta compression (65%–78% bandwidth reduction for negotiated clients).
6. **Honest Folia plugin diagnostics:** Boot-time plugin safety/compatibility reporting (via `bytecode-api`) so admins immediately see which plugins are Folia-safe vs. hazardous.

### 3. Materialized Folia `26.3.x` Source Findings

Cloning `LeafCraftDev/GoldLeaf` (forked from `PaperMC/Folia` on `ver/26.3.x`, HEAD `eca40f9`, MC `26.3` `STABLE`, JDK 25, `paperweight-patcher v2.0.0-beta.24`) and materializing `folia-server` via `./gradlew applyAllPatches` established the following facts:

1. **Folia's patch surface is thin and leaves storage untouched:**
   - `folia-server/paper-patches` contains `8` files; `folia-server/minecraft-patches` contains `14` files (core engine in `0002-Region-Threading-Base.patch` and `0004-Region-Threading-Base.patch`, plus targeted guards such as `0007-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch`).
   - Folia applies **zero** patches to `RegionFileStorage`, `RegionFile`, `LevelChunkSection`, `PalettedContainer`, `ChunkSystemRegionFileStorage`, or `MoonriseRegionFileIO`.
   - The only Folia edit in `SerializableChunkData.java` is changing `level.getGameTime()` to `level.getRedstoneGameTime()` for `chunk.getTicksForSerialization(...)` (`SerializableChunkData.java:493`).

2. **Exact `.mca` I/O and NBT serialization bottleneck during chunk generation and saves:**
   - `ChunkSystemRegionFileStorage` (`ca.spottedleaf.moonrise.patches.chunk_system.io.ChunkSystemRegionFileStorage`) is a compact **7-method interface** (`moonrise$doesRegionFileNotExistNoIO`, `moonrise$getRegionFileIfLoaded`, `moonrise$getRegionFileIfExists`, `moonrise$startWrite`, `moonrise$finishWrite`, `moonrise$readData`, `moonrise$finishRead`).
   - `MoonriseRegionFileIO.ChunkIOTask` orchestrates async write compression (`performWriteCompress` -> `regionDataController.startWrite`) and region I/O (`runWriteIO` -> `regionDataController.finishWrite`), passing `CompoundTag` payloads between serialization and disk.
   - `SerializableChunkData#write()` (`SerializableChunkData.java:526-590`) converts every `LevelChunkSection`'s `PalettedContainer<BlockState>` and `PalettedContainerRO<Holder<Biome>>` into boxed `CompoundTag`/`ListTag` trees before `RegionFileStorage#moonrise$startWrite()` (`RegionFileStorage.java:81-103`) runs `NbtIo.write()` + per-chunk zlib compression into 4 KiB sector-tabled `.mca` files.
   - Under heavy chunk generation, this NBT allocation churn, zlib compression, and 4 KiB sector fragmentation backpressure Moonrise's I/O queue and steal CPU/GC cycles that should be spent generating chunks.

3. **Folia's regionizer aligns directly with `LeafShard`:**
   - `GlobalConfiguration.ThreadedRegions.gridExponent` (consumed by `TickRegions.init`, `TickRegions.java:69-72`) defaults to `4` (`16×16` chunks = `256×256` blocks, so `2×2` Folia sections tile one `32×32` `.lca` region cleanly). Setting `gridExponent = 5` maps 1 Folia grid section 1:1 to one `32×32` `.lca` region (`512×512` blocks).
   - `ThreadedRegionizer.RegionCallbacks` already exposes `onRegionCreate`, `onRegionDestroy`, `onRegionActive`, `onRegionInactive`, `preMerge`, and `preSplit`.
   - `RegionizedWorldData` already isolates per-region `chunks`, `entities`, `localPlayers`, `connections`, `LevelTicks<Block>`, `LevelTicks<Fluid>`, `blockEventQueue`, `tileEntities`, and `redstoneTime`.
   - Combined with `LeafShard`'s `4-byte` `LCAS` halo deltas, `.lca` delta handoffs, and brokerless `LSHD` control ring, the cluster data plane is significantly smaller than full-chunk NBT replication stacks.

4. **Two concrete Folia quirks requiring fork-level fixes:**
   - **Workstation thread collapse (`TickRegions.java:44-58`):** When `config.threads <= 0`, `TickRegions.getTickThreads` computes `tickThreads = OSNuma.getNativeInstance().getTotalCores() / 2` and sets `tickThreads = 1` whenever `tickThreads <= 4` (otherwise `tickThreads / 4`). Consequently, **every 4–8 core secondhand PC or workstation collapses to `1` tick thread** by default unless manually overridden.
   - **Unfenced region merging (`ThreadedRegionizer`):** Folia automatically merges adjacent active sections into one `ThreadedRegion`. Multi-node `LeafShard` clusters require a shard-fence hook preventing a local region from merging across a `RegionLeaseRecord` boundary owned by a remote shard, keeping the remote side as a read-only `LCAS`-fed halo.

## Decision

### 1. Fork Structure, Baseline, and Licensing Boundary

- **Repository & Baseline:** `LeafCraftDev/GoldLeaf` is a direct open-source fork of `PaperMC/Folia` baselined at unobfuscated **`ver/26.3.x` (MC `26.3+`, JDK 25)**. Legacy obfuscated versions (`< 26.x`) are out of scope.
- **Thin-Hook Patch Discipline:** Do not modify Folia's existing `0002-Region-Threading-Base.patch` or `0004-Region-Threading-Base.patch`. Layer all GoldLeaf changes as small, dedicated patches after Folia's patch set (~4–5 clean SPI seams + config/auto-tuner) so rebasing across `26.x` drops remains low-friction.
- **Upstream-Mergeable Patch Layering (Folia & Paper):** Author every generic improvement (scheduler latency fixes, workstation thread auto-tuning, storage/regionizer SPIs, and Moonrise/vanilla hooks) so it can be submitted and merged cleanly upstream into `PaperMC/Folia` and `PaperMC/Paper`. Separate patches strictly into two layers:
  1. **Layer 1 (Upstream-candidate patches):** Self-contained, single-purpose patches using standard `io.papermc.paper.*` / `ca.spottedleaf.moonrise.*` packages, idiomatic Paper/Folia conventions, zero GoldLeaf branding, zero `goldleaf.yml` coupling, zero external engine imports, and full vanilla technical fidelity—cherry-pickable directly into upstream PRs.
  2. **Layer 2 (GoldLeaf product & SPI wiring):** Branding, `goldleaf.yml` config wiring, and registrations connecting external engines (`LeafAnvil`, `LeafShard`, `LeafGen`, `bytecode-api`, `predict-api`) to the Layer 1 SPIs.
- **Patcher Distribution & Module Separation:** Distribute GoldLeaf as an open-source GPLv3 patcher (`paperclip`-style binary diff; never redistributing Mojang's JAR). Keep the fork's patches strictly generic SPI hooks; keep `LeafAnvil`, `LeafShard`, and `LeafGen` engines in their own modules/artifacts and keep the `.lca` format specification (`api/anvil-api`) and `.lca <-> .mca` round-trip converter MIT-licensed.
- **Vanilla Technical Fidelity Invariant:** GoldLeaf shall never ship default-on optimizations that alter redstone update order, hopper/piston timings, or vanilla farm mechanics. Any mechanics-relaxing option (such as opt-in fast chunk generation) must be explicitly documented and disabled by default in `goldleaf.yml`.

### 2. Staged Implementation Plan

1. **Stage 1 — Identity, `goldleaf.yml` Scaffold & Workstation Auto-Tuner**
   - Add minimal GoldLeaf branding/version plumbing and a single `goldleaf.yml` config surface with hardware-envelope auto-detection.
   - Fix `TickRegions.getTickThreads` so 4–8 core machines receive multi-region tick parallelism out of the box (e.g., reserving headroom for I/O, Netty, and GC instead of collapsing `totalCores <= 8` to `1` tick thread).

2. **Stage 2 — Native `.lca` Storage & Zero-NBT Section Seam (`LeafAnvil`)**
   - **Tier 1 (Storage Backend SPI):** Introduce a pluggable storage provider seam behind `ChunkSystemRegionFileStorage` / `RegionFileStorage` and `MoonriseRegionFileIO` so `.lca` (`LcaRegionFileStorage`) operates natively with zero `Unsafe.allocateInstance` or runtime bytecode-bridge hacks. Eliminates `.mca` 4 KiB sector fragmentation, cuts disk footprint ~50%, accelerates saves ~15.5×, and preserves full `.lca <-> .mca` round-trip convertibility.
   - **Tier 2 (Zero-NBT Section Bypass & Cold-Section Backing):** Add a fast-path seam between `SerializableChunkData` / `MoonriseRegionFileIO` and `LevelChunkSection` so block-state and biome palettes serialize directly to/from `.lca` slices (reserving NBT strictly for entities, block entities, structures, and PDC), and allow cold/unmodified `LevelChunkSection`s to stay `.lca`-backed in memory to reduce live heap and remove save/load backpressure during chunk generation.

3. **Stage 3 — Two-Mode Chunk Generation Throughput**
   - **Default (100% vanilla-exact):** Sustained generation throughput gains from Tier 1/2 `.lca` I/O + zero-NBT section saves, plus a clean `ChunkStatusTasks` (`NOISE`/`SURFACE`) batch-dispatch SPI for `LeafGen` (GPU OpenCL / C2 hidden-class CPU) once ready.
   - **Opt-in Fast Mode (`goldleaf.yml`, off by default):** Documented >100% generation acceleration accepting minor vanilla seed/noise deviations (`FP32` / relaxed density evaluation) for servers prioritizing raw exploration/pre-gen speed.

4. **Stage 4 — First-Class `LeafShard` Multi-Node Seams**
   - Add a `ThreadedRegionizer` shard-fence hook preventing local regions from merging across remote `RegionLeaseRecord` boundaries.
   - Expose per-region dirty-voxel tracking on `RegionizedWorldData` and `RegionCallbacks` hooks for `LCAS` `4-byte` halo delta streaming, `.lca` region migration flushes, and `PSBD` Velocity proxy switching.

5. **Stage 5 — Technical SMP Protocols, Adaptive Pacing & Diagnostics**
   - Add clean protocol-hook support for technical SMP client mods (Servux, Syncmatica, Jade, AppleSkin), `io_uring` native Netty transport, and `LCAS` negotiated client wire streaming.
   - Wire `api/predict-api` closed-loop pacing strictly for mechanics-neutral work (chunk-gen dispatch, off-tick `.lca` I/O, packet flushes, shard-migration triggers), `api/metrics-api` per-region telemetry, native per-chunk telemetry and `ChunkExecutionMask` gating ([`goldleaf-ADR-006`](goldleaf-ADR-006-native-chunk-telemetry-and-execution-mask-spi.md)), and `api/bytecode-api` off-thread boot-time Folia plugin safety preflight with opt-in `LeafFuzz` runtime hazard hooks ([`goldleaf-ADR-007`](goldleaf-ADR-007-plugin-safety-preflight-and-runtime-hazard-spi.md)).
   - Defer Mob AI optimizations (`Brain<E>`, `WalkNodeEvaluator`, `ADR-131`) strictly past `v0.1` behind mandatory `P1..P4` technical-farm parity proofs ([`goldleaf-ADR-008`](goldleaf-ADR-008-mob-ai-optimization-boundary-and-farm-parity-gate.md)).

### 3. Benchmark & Verification Envelope

All stages must be validated against two concrete hardware targets plus strict vanilla regression checks:

- **Floor Target:** 4–8 core surplus workstation (`i5-8500T` / `i7-9700` class), 8–16 GB DDR4, NVMe and HDD storage test cases, running a live technical redstone district alongside normal exploration.
- **Ceiling Target:** Multi-node `LeafShard` cluster (4+ nodes) scaling one seamless world with active border crossings and redstone districts.
- **Non-Regression Gates:** Bit-exact `.lca <-> .mca` round-trip verification; zero vanilla redstone/farm timing regressions in default mode; measurable reduction in save/load stalls and sustained chunks/sec improvement during pre-generation.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Paper-only fork (LeafMC-style)** | Single main tick thread leaves 80%+ of 4–8 core secondhand CPUs idle and cannot isolate a technical redstoner's worst-case load from other players without gameplay-breaking global throttling. |
| **Dual Paper + Folia patch stacks (`Paper -> GoldLeafPaper -> GoldLeafFolia`)** | Doubles rebase and CI burden across every `26.x` Mojang drop before the core storage and sharding seams are proven on Folia. |
| **Embedding `LeafAnvil` / `LeafShard` directly inside `folia-server` `.patch` files** | Bloats the fork patch stack (increasing rebase friction) and forces GPLv3 copyleft onto separately licensed engine modules. Keeping the fork patches as thin generic SPIs keeps both maintenance and licensing clean. |
| **Clean-room Rust server rewrite** | Loses the Bukkit/Paper/Folia plugin ecosystem and years of subtle vanilla redstone/AI/mechanics parity required by technical players. |
| **Purpur/LeafMC-style gameplay and DAB toggles** | Violates the vanilla technical fidelity invariant and creates long-term patch-soup maintenance debt. |

## Consequences

- **Positive:**
  - Single, razor-thin Folia `26.3.x` patch stack that stays fast to rebase across `26.x` releases.
  - Directly attacks the real chunk-generation bottleneck (`.mca` + `CompoundTag`/`NbtIo` save/load stalls) before even adding custom worldgen acceleration.
  - Unlocks multi-core performance on cheap 4–8 core surplus hardware while scaling via the same architecture to multi-node mega-servers.
  - Open-source GPLv3 fork + MIT `.lca` format/converter maximizes admin trust and eliminates lock-in concerns.
- **Negative / Trade-offs:**
  - Non-Folia Paper plugins will not run on GoldLeaf (mitigated via boot-time `bytecode-api` compatibility diagnostics rather than unsafe single-thread emulation).
  - Tier 2 zero-NBT section bypass requires careful synchronization with Moonrise's `ChunkIOTask` write/read lifecycle and Starlight light-state metadata (`BLOCKLIGHT_STATE_TAG`, `SKYLIGHT_STATE_TAG`) so chunk upgrades, DataFixer, and `.mca` export remain 100% lossless.
  - Maintaining clean SPI boundaries between the GPLv3 fork and external engine modules requires disciplined API design upfront.

## References

- Materialized Folia `26.3` seams in `/home/leaf26/GoldLeaf`:
  - `folia-server/src/minecraft/java/net/minecraft/world/level/chunk/storage/RegionFileStorage.java`
  - `folia-server/src/minecraft/java/net/minecraft/world/level/chunk/storage/SerializableChunkData.java`
  - `folia-server/src/minecraft/java/ca/spottedleaf/moonrise/patches/chunk_system/io/ChunkSystemRegionFileStorage.java`
  - `folia-server/src/minecraft/java/ca/spottedleaf/moonrise/patches/chunk_system/io/MoonriseRegionFileIO.java`
  - `folia-server/src/minecraft/java/io/papermc/paper/threadedregions/TickRegions.java`
  - `folia-server/src/minecraft/java/io/papermc/paper/threadedregions/ThreadedRegionizer.java`
  - `folia-server/minecraft-patches/features/0007-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch`
- Related sibling engine ADRs in `RTP`:
  - `api/anvil-api/docs/adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md`
  - `resource/LeafAnvil/docs/adr/leafanvil-ADR-001-Multi-Loader-lca-world-storage-and-bytecode-interceptor.md`
  - `resource/LeafAnvil/docs/adr/leafanvil-ADR-002-in-memory-lca-chunk-representation-and-dirty-overlay-compaction.md`
  - `resource/LeafShard/docs/adr/leafshard-ADR-001-hybrid-32x32-lca-region-leases-and-split-brain-fencing.md`
  - `resource/LeafShard/docs/adr/leafshard-ADR-004-brokerless-lshd-control-ring-proxy-direct-kinematics-and-dictionary-lcas-sync.md`

## Implementation & Verification Status

- **Decision §1 (Patch Discipline & Layer 1/2 Separation):** Complete — upstream Folia `0002` and `0004` patches are untouched; all generic SPI seams (`io.papermc.paper.spi.*`, `io.papermc.paper.threadedregions.spi.*`, `ca.spottedleaf.moonrise.spi.*`) in Layer 1 patches (`paper-patches/features/0008..0011-*`, `minecraft-patches/features/0015..0019-*`) contain zero `dev.leafcraft.goldleaf` imports and zero `goldleaf$`/`goldleaf*` code identifiers. Layer 2 bootstrap wiring lives in `PaperConfigurations.java` (`0010-*`) -> `dev.leafcraft.goldleaf.GoldLeafBootstrap` on both initial boot and config reload.
- **Stages 1–5 (Code & Unit Verification):** Complete for local verification —
  - Stage 1 workstation auto-tuner (`TickThreadAutoTuner`) and `goldleaf.yml` validation (`GoldLeafConfig`).
  - Stage 2 Tier 1 (`LcaRegionStorageProvider` with unified dimension `worldId`) & Tier 2 (`LcaDirectSectionSerializer` zero-NBT CoW section splicing via `commitAndGetSplicedChunkFrameCoW` + `DIRECT_COW_COMMITTED_TAG` write bypass, off-tick `installBaseFrame` residency population, `LevelChunk` unload eviction, and region tick-end `flushResidencyTickEnd`).
  - Stage 3 two-mode worldgen SPIs (`PaperWorldgenSpi`, `vanilla-exact` default, opt-in `fast` mode).
  - Stage 4 `LeafShard` seams (`RegionShardFence`, `RegionMigrationHook`, `RegionizedWorldData#flushDirtyVoxelHalo` wired at region tick end and cleared in `resetForPool()`).
  - Stage 5 (`io_uring` Netty 4.2 transport, `BytecodeFoliaPreflightProvider`, `LcaNetWireEngine` per-world residency resolution, `PaperTelemetrySpi#onRegionTickEnd` driving `GoldLeafMetricsCollector` and `GoldLeafPredictPacer`, and config-gated default-off `BuiltInTechnicalProtocolHandlers` for Servux/Syncmatica/Jade/AppleSkin/Map World-ID; Mob AI deferred past `v0.1`).
- **Decision §3 (Hardware Benchmark Envelope):** Multi-section `.lca` round-trip, bit-exact `.lca <-> .mca` migration (`LcaMigrationCli --verify`), zero-NBT CoW write bypass, workstation auto-tuner, and Stage 5 telemetry/pacer/protocol gates are verified by `Adr001Decision3VerificationTest` under `GoldLeafTestSuite` and `PaperSpiTestSuite`. Physical Floor (4–8 core NVMe/HDD + live vanilla redstone farms) and Ceiling (4+ node `LeafShard` cluster) hardware benchmarks remain environment-gated before transitioning status from `In Progress` to `Accepted`.
