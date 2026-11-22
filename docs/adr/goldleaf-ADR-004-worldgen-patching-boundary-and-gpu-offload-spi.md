# goldleaf-ADR-004 — Worldgen Patching Boundary, Ecosystem Build-vs-Adopt Matrix, and Native 26.3+ GPU Offload SPI

**Status:** Proposed
**Date:** 2026-11-21

## Context

### 1. Worldgen Bottlenecks & GPU Offloading Motivation

Chunk generation bursts—initial pre-generation (`Chunky`), elytra exploration, and random teleportation (`LeafRTP`)—are one of the top reasons server operators adopt third-party performance mods or Paper/Folia forks. Across the Minecraft ecosystem, operators currently assemble worldgen acceleration from two largely separate worlds:

1. **Fabric / NeoForge Modded Stack (`C2ME` + `c2me-ocl` + companions):**
   - **`C2ME` (`Concurrent Chunk Management Engine`):** Replaces Vanilla's chunk scheduler with `FlowSched` for high-core-count CPU worldgen scaling, adds allocation reductions (`c2me-opts-allocs`), aquifer micro-optimizations, an experimental GC-free chunk serializer, and `CheckedThreadLocalRandom` undefined-behavior sanitization.
   - **`c2me-ocl` (`C2ME OpenCL Acceleration Module`, Java 25+):** Compiles Mojang's typed `DensityFunction` / `NoiseRouter` graph via C2ME's revamped Density Function Compiler (DFC) to an OpenCL C backend, accelerating the **`BIOMES` and `NOISE`/`TERRAIN` stages** (~80%+ uplift on CPU-bound vanilla overworld; works with Vanilla and datapacks such as Terralith and Tectonic). Explicitly scopes out custom Java-mod `DensityFunction` classes, notes rare 1–2 block biome-border shifts from order-dependent vanilla evaluation, blacklists PoCL CPU drivers due to instability, and warns that lighting (`ScalableLux`) immediately becomes the next bottleneck.
   - **Companion mods operators pair with C2ME:** `ScalableLux` (lighting), `Noisium` / `NoisiumForked` (direct palette writes in `populateNoise`/terrain fill and faster sampling), `Structure Layout Optimizer` (jigsaw/structure bounding-box speedups), `Lithium` (general hot-loop optimizations), and `FerriteCore` (heap reduction).
2. **Paper / Folia Fork Ecosystem (`Moonrise`, `Canvas`, `Luminol`, `Kaiiju`, `LeafMC`, `Purpur`):**
   - **Moonrise (`ChunkSystem` + Starlight):** Already ships inside Paper and Folia, providing multithreaded chunk generation and the Starlight light engine (removing the lighting bottleneck that forces Fabric servers to install `ScalableLux`).
   - **Storage forks (`Kaiiju`, `Luminol` -> `.linear`):** Replace `.mca` with whole-region zstd `.linear` files to cut disk footprint and IOPS stalls during pre-generation, at the cost of holding full regions in RAM.
   - **Aggressive Paper forks (`LeafMC`, `Pufferfish`, `Purpur`):** Layer dozens to hundreds of patches (async entity/mob tweaks, DAB, experimental async worldgen/lighting toggles, gameplay knobs) that create heavy rebase friction across `26.x` drops and risk vanilla technical parity.

### 2. Lessons from `LeafGen` as an External Plugin (`leafgen-ADR-007`..`009`)

Previous work in `resource/LeafGen` attempted to deliver GPU-accelerated worldgen as an external multi-loader plugin. That architecture suffered recurring progress-and-regression cycles from two compounding sources:

1. **Host-interception fragility:** Reflectively hooking `ChunkStatusTasks` (`LeafGenChunkStatusInterceptor`) and scraping live Mojang `NoiseRouter`, `SurfaceRules`, `BiomeSource`, `WorldCarver`, `TagKey`, and `FeatureConfiguration` records (`RuntimeDimensionCapabilityLifter`) caused silent config-field drops, dual execution of replaced stages, missing fluid post-processing marks, and `ChunkStatus.NOISE` worker starvation (`1.3–2.4` chunks/batch on an RTX 3090).
2. **Compiler & stamper over-scope:** Maintaining a general-purpose JVM bytecode lifter (`ClosedWorldGraphLifter` + `OpenClBackendEmitter`) plus hand-authored `FN_*` feature/carver stampers was effectively maintaining a custom JIT compiler and parallel vanilla worldgen re-implementation. On Folia/`GoldLeaf`, servers do **not** run Fabric/NeoForge Java worldgen mods—they run Vanilla + **datapacks** composed entirely of Mojang's typed `DensityFunction`, `SurfaceRules`, `BiomeSource`, and `PlacedFeature` trees.

### 3. Verified Materialized Folia `26.3` Worldgen Seams

Inspecting the materialized `ver/26.3.x` `folia-server` source reveals a major Mojang architectural shift that makes native thin-SPI worldgen acceleration dramatically cleaner than on `1.20–1.21`:

1. **Collapsed, chunk-local `ChunkStatus.TERRAIN` (`ChunkPyramid.java:11-34`):**
   - Mojang's `26.3` `GENERATION_PYRAMID` is now `STRUCTURE_STARTS` -> `STRUCTURE_REFERENCES` -> `BIOMES` -> **`TERRAIN`** -> `FEATURES` -> `INITIALIZE_LIGHT` -> `LIGHT` -> `SPAWN` -> `FULL`.
   - `NOISE`, `SURFACE`, and `CARVERS` have been consolidated into a single `ChunkStatus.TERRAIN` step (`ChunkStatusTasks::buildTerrain`) with **`blockStateWriteRadius(0)`**—100% chunk-local with zero cross-chunk block writes.
2. **Volume-oriented density evaluation (`NoiseBasedChunkGenerator.java:100-113, 360-477`):**
   - `NoiseBasedChunkGenerator#buildTerrain` creates one `NoiseChunk` covering a `DensityVolume(16, noiseSettings.height(), 16, minBlockX, minY, minBlockZ)` and runs `doFill` -> `buildSurface` -> `generateCarvers` within that single `NoiseChunk` lifecycle (sharing `cachingSamplers()` and `aquifer()`).
   - `doFill` (`NoiseBasedChunkGenerator.java:439-477`) already evaluates `finalDensity.sampleVolume(volume)` into a flat `ScopedDensityBuffer` (`float` values indexed by `volume.indexUnchecked(x, y, z)`).
3. **Concrete CPU hot-loop waste still present in `26.3` `doFill` (`NoiseBasedChunkGenerator.java:448-475`):**
   - Inside the `(z, x, y)` loop, vanilla calls `chunk.getSection(chunk.getSectionIndex(blockY))` on **every single Y iteration** and writes `section.setBlockState(x, SectionPos.sectionRelative(blockY), z, state, false)` one voxel at a time, despite having already acquired all sections (`NoiseBasedChunkGenerator.java:404-408`) and materialized the entire `ScopedDensityBuffer`.
4. **Upstream `buildTerrain` preserves Bukkit `CustomChunkGenerator` hooks (`NoiseBasedChunkGenerator.java:386-433`):**
   - Any GoldLeaf acceleration seam must preserve `customGenerator` bypass semantics so plugin world generators (`TerraformGenerator`, `Iris`, void/skyblock generators) continue to run unmodified.

## Decision

GoldLeaf adopts a **thin-SPI, typed-AST worldgen acceleration architecture** tailored to MC `26.3+` (`ChunkStatus.BIOMES` and `ChunkStatus.TERRAIN`), strictly separated into **Layer 1 (upstream-mergeable Folia/Paper patches)** and **Layer 2 (external `LeafAnvil` + `GoldLeaf`/`LeafGen` accelerator modules)** per `goldleaf-ADR-001`.

### 1. Ecosystem Build-vs-Adopt-vs-Reject Matrix

Every major capability operators seek from C2ME/`c2me-ocl`, companion Fabric mods, and Paper/Folia forks is explicitly classified below:

| Capability / Ecosystem Reference | Disposition | Rationale & Placement |
|---|---|---|
| **Multithreaded chunk scheduler & lighting** (`C2ME FlowSched`, `ScalableLux`) | **Inherited (Keep Moonrise + Starlight)** | Paper/Folia already ship Moonrise `ChunkSystem` and Starlight. Replacing Moonrise with `FlowSched` would break Folia regionizer integration and double maintenance. |
| **Section-batched `doFill` palette & heightmap writes** (`Noisium` / `NoisiumForked` class) | **Implement Ourselves (Layer 1 — Upstream-Candidate)** | Directly fixes `NoiseBasedChunkGenerator#doFill`'s per-voxel `getSection()` + `setBlockState()` overhead. 100% vanilla-exact, zero external dependencies, benefits every CPU-only server (including 4–8 core floor hardware). |
| **Typed `DensityVolume` & `BIOMES`/`TERRAIN` Batch-Dispatch SPI + Moonrise wave coalescer** | **Implement Ourselves (Layer 1 — Upstream-Candidate)** | Clean, generic SPI seam in `ChunkStatusTasks` / `NoiseBasedChunkGenerator` + optional bounded wave coalescer (`16–32` chunks/wave with `1–2 ms` flush deadline) so GPU/SIMD engines never starve at `1–2` chunks/batch and never use reflection. |
| **Zero-NBT chunk persistence & cold-section RAM savings** (`Linear`, `FerriteCore`, `c2me gcFreeChunkSerializer`) | **Implement Ourselves (Layer 2 — `LeafAnvil` `.lca`, `goldleaf-ADR-001` Stage 2)** | Eliminates `.mca` + `CompoundTag`/`NbtIo` + zlib backpressure during generation without `.linear`'s full-region RAM penalty, while adding `SEC-DED` integrity and lossless `.lca <-> .mca` conversion. |
| **Typed `DensityFunction` / `NoiseRouter` Compiler -> OpenCL GPU & JDK 25 Vector API CPU** (`c2me-ocl` / C2ME DFC class) | **Implement Ourselves (Layer 2 — External Engine Module)** | Walks Mojang's unobfuscated `26.3` typed `DensityFunction` / `NoiseRouter` / `SurfaceRules` ASTs directly (Vanilla + all datapacks: Terralith, Tectonic, Incendium, Nullscape). Targets `BIOMES` + `TERRAIN` (`DensityVolume` + `doFill`, then `buildSurface` if profiled hot). Ships a flat JDK 25 Vector API CPU backend for no-GPU hosts alongside OpenCL for dGPU/iGPU hosts. |
| **Jigsaw / structure bounding-box pruning** (`Structure Layout Optimizer` class) | **Adopt / Re-implement Selectively (Layer 1 — Profile-Gated)** | Implement as a small, MIT/upstream-clean Layer 1 patch *only* if profiling after `.lca` + `TERRAIN` acceleration shows `STRUCTURE_STARTS` or `FEATURES` is the dominant remaining bottleneck. |
| **World-random concurrency guard** (`C2ME CheckedThreadLocalRandom` class) | **Adopt Pattern Selectively (Layer 1 — Opt-in Debug)** | Useful opt-in diagnostic for catching unsafe concurrent `RandomSource` access in plugins or custom generators without affecting production hot paths. |
| **Bundling `C2ME` / `c2me-ocl` directly** | **Do NOT Include** | Fabric/NeoForge Mixin architecture conflicts with Moonrise; `c2me-ocl` is separately licensed and targets `FlowSched`. Adopt its proven architectural scoping (typed DFC, `BIOMES` + `TERRAIN` focus, PoCL blacklist, honest parity disclosure), not its code. |
| **JVM bytecode lifting (`ClosedWorldGraphLifter`) & hand-authored `FN_*` carver/feature stampers** (`leafgen-ADR-009`) | **Do NOT Include in GoldLeaf** | Folia servers do not run Fabric/NeoForge Java worldgen mods. Keeping `generateCarvers` (chunk-local inside `TERRAIN`) and `generateFeatures` (native `3×3` `WorldGenRegion`) on Mojang Java eliminates `LeafGen`'s primary regression source. |
| **`.linear` region format** (`Kaiiju`, `Luminol`) | **Do NOT Include** | Superseded by `.lca` (`goldleaf-ADR-001` Stage 2). |
| **Gameplay-altering / unsafe fork patches** (`LeafMC`/`Purpur` DAB, async unsafe mutations, chunk-error auto-regeneration) | **Do NOT Include** | Violates `goldleaf-ADR-001`'s vanilla technical fidelity invariant and creates data-loss or rebase debt. |

### 2. Native `26.3+` Worldgen & GPU Offload Architecture

#### Layer 1 — Thin Upstream-Candidate Patches (`folia-server`)

1. **Section-Batched `doFill` (`NoiseBasedChunkGenerator#doFill`):**
   - Hoist `LevelChunkSection` lookup out of the inner Y loop (iterate section-by-section, `16` Y levels per section) and write non-air states via direct section palette fast-path while preserving exact `OCEAN_FLOOR_WG` / `WORLD_SURFACE_WG` heightmap updates and `aquifer.shouldScheduleFluidUpdate()` fluid post-processing marks.
   - Zero behavioral drift; 100% vanilla-exact.
2. **Typed Batch-Dispatch SPI (`ChunkStatusTasks` / `NoiseBasedChunkGenerator`):**
   - Introduce a small, generic `TerrainGenerationAccelerator` SPI (in `io.papermc.paper.*` / `ca.spottedleaf.moonrise.*`) invoked only when `customGenerator == null` and `!SharedConstants.debugVoidTerrain(chunk.getPos())`.
   - Exposes two clean, typed hooks matching `26.3`'s pyramid:
     - `accelerateBiomes(WorldGenContext, List<BiomeTask>)` for `ChunkStatus.BIOMES`.
     - `accelerateTerrain(WorldGenContext, List<TerrainTask>)` for `ChunkStatus.TERRAIN` (supplying `DensityVolume`, `NoiseRouter`, `NoiseGeneratorSettings`, `RandomState`, `Beardifier`, `Blender`, and target `ProtoChunk` sections).
   - Default no-op implementation delegates directly to stock `NoiseBasedChunkGenerator` with zero overhead.
3. **Bounded Moonrise Wave Coalescer & Worker Pacing:**
   - Coalesce ready `BIOMES` and `TERRAIN` generation tasks sharing `(ServerLevel, RandomState)` into bounded waves (`16–32` chunks) with a strict low-latency flush deadline (`1–2 ms`) so single-player exploration never waits on a full batch while pre-gen (`Chunky`) and `LeafRTP` bursts saturate the GPU.
   - Coordinate Moonrise generation worker count with `goldleaf-ADR-003`'s `4–8` core hardware-envelope curve so background generation does not starve active region tick threads on small machines.

#### Layer 2 — External Accelerator Engine (`LeafGen` GoldLeaf Backend + `LeafAnvil`)

1. **Typed `26.3` AST-to-IR Compiler (No Bytecode Lifter):**
   - Lower Mojang's typed `NoiseRouter` / `DensityFunction` / `BiomeSource` (and optionally `SurfaceRules.MaterialRule` if profiled hot) directly to a compact SSA/DAG IR compiled to:
     - **OpenCL C backend** (discrete GPUs & capable iGPUs; disk-cached compiled binaries keyed by `(deviceSignature, routerHash)` so server restarts avoid shader-compile stalls; explicit **PoCL CPU-driver blacklist**).
     - **JDK 25 Vector API / flat CPU backend** (zero-allocation SIMD/scalar evaluation for surplus 4–8 core hosts and headless VPSes without OpenCL).
   - **Unsupported / Custom Constructs Fail Closed Cleanly:** If a dimension uses a `CustomChunkGenerator`, an unrecognized `DensityFunction` type, or `Blender` old-chunk blending, route that chunk cleanly to stock Mojang Java *before* GPU dispatch—never half-execute on GPU and fall back mid-stage.
2. **Scoped Stage Boundary (`BIOMES` + `TERRAIN` Fill; Native `CARVERS` & `FEATURES`):**
   - Accelerate `BIOMES` and `TERRAIN`'s `DensityVolume` + `doFill` (plus `buildSurface` once `doFill` is proven and profiled).
   - Keep `generateCarvers` (already chunk-local inside `TERRAIN`) and `generateFeatures` (native `3×3` `WorldGenRegion` with native `markPosForPostProcessing`) on Mojang Java.
3. **Two-Mode Parity Contract (`goldleaf.yml`):**
   - **Default (`vanilla-exact`):** Full `FP64`-faithful density evaluation matching vanilla terrain; explicitly documents the rare 1–2 block biome-border floating-point/order nuance (mirroring `c2me-ocl`'s parity contract) and normal vanilla cross-chunk feature non-determinism.
   - **Opt-in `fast` mode (disabled by default):** Explicit `FP32` / relaxed density evaluation and optional `beardifier` fast-path for servers prioritizing raw pre-gen throughput over bit-identical seed parity.
4. **Mandatory Anti-Regression & Provenance Gates (Retained from `leafgen-ADR-008`):**
   - Every build and benchmark run must assert:
     1. **Zero dual execution:** Accelerated `BIOMES`/`TERRAIN` chunks never also execute stock `doFill`.
     2. **Zero silent fallback:** When `mode=gpu` is explicitly required in tests, any host-CPU fallback fails the test loudly instead of logging `INFO` and passing.
     3. **Wave occupancy telemetry:** Report average chunks/wave, GPU dispatch time, readback time, and host-CPU time separately so GPU starvation regressions are caught immediately.

### 3. Hardware Barbell Mapping & Implementation Sequencing

| Hardware Target | Primary Worldgen Wins |
|---|---|
| **Floor (4–8 core surplus PC / VPS, no dGPU)** | `.lca` zero-NBT saves (`goldleaf-ADR-001` Stage 2) + Layer 1 section-batched `doFill` + Layer 2 JDK 25 Vector API CPU evaluator + bounded worker pacing. Zero OpenCL dependency. |
| **Mid / Ceiling (Workstation or mega-server with dGPU / dedicated pre-gen node)** | All Floor wins + Layer 1 Moonrise wave coalescer (`16–32` chunks/wave) + Layer 2 OpenCL `BIOMES`/`TERRAIN` kernels + disk-cached shaders. |

**Sequencing:**
1. **Stage 1 (Baseline CPU + Storage):** Ship `goldleaf-ADR-001` Stage 2 (`.lca` + zero-NBT sections) and Layer 1 section-batched `doFill`. Measure sustained `ch/s`, GC pauses, and MSPT on Vanilla + Terralith/Tectonic.
2. **Stage 2 (Thin SPI + Wave Coalescer + Vector CPU):** Add the Layer 1 `TerrainGenerationAccelerator` SPI, Moonrise wave coalescer, and Layer 2 typed `DensityFunction` -> JDK 25 Vector API backend.
3. **Stage 3 (OpenCL GPU Backend):** Add the Layer 2 OpenCL C backend with disk shader cache, PoCL blacklist, and anti-regression gates (`BIOMES` + `TERRAIN` `doFill`, then `buildSurface` if profiling justifies it).
4. **Stage 4 (Profile-Gated `STRUCTURE_STARTS` / `FEATURES`):** Re-profile after Stages 1–3; only add Layer 1 structure-layout pruning if `STRUCTURE_STARTS` or `FEATURES` is measurably the new bottleneck.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| **Move `LeafGen`'s full JVM bytecode lifter (`ClosedWorldGraphLifter`) and `FN_*` stampers into `GoldLeaf`** | Imports `LeafGen`'s highest-churn compiler and stamper regressions into `GoldLeaf` to solve a problem (arbitrary Fabric/NeoForge Java worldgen mods) that does not exist on Folia servers. |
| **Port or bundle `C2ME` + `c2me-ocl` into `GoldLeaf`** | `C2ME` is built around Fabric/NeoForge Mixins and its own `FlowSched` scheduler, which conflicts with Paper/Folia's Moonrise `ChunkSystem`; `c2me-ocl` is also separately licensed. |
| **Embed OpenCL kernels and the `DensityFunction` compiler directly inside `folia-server` `.patch` files** | Violates `goldleaf-ADR-001`'s thin-SPI rule, bloats `26.x` rebase surfaces, and pulls native OpenCL/JNI runtime dependencies directly into the core GPLv3 server patch stack. |
| **Accelerate `FEATURES` and `CARVERS` on GPU from Day 1** | `FEATURES` has `blockStateWriteRadius(1)` (cross-chunk `3×3` `WorldGenRegion` dependencies, stateful RNG, tags, waterlogging, structures) and was the #1 source of geometry and waterlogging regressions in `LeafGen`. |
| **Skip GPU offloading entirely and rely only on CPU + `.lca`** | Leaves significant pre-generation and datapack (`Terralith`/`Tectonic`) throughput on the table on machines equipped with surplus workstation or server GPUs; keeping the Layer 1 SPI thin makes GPU offload an opt-in Layer 2 win with zero cost to CPU-only servers. |

## Consequences

- **Positive:**
  - Eliminates both halves of `LeafGen`'s regression loop: zero reflection/host-interception hacks and zero JVM bytecode lifter / `FN_*` feature stampers.
  - Exploits Mojang `26.3`'s native `ChunkStatus.TERRAIN` (`blockStateWriteRadius(0)`) and `DensityVolume` / `ScopedDensityBuffer` design instead of fighting `1.20–1.21` chunk internals.
  - Delivers immediate, 100% vanilla-exact worldgen speedups on CPU-only 4–8 core hardware (`.lca` + section-batched `doFill` + Vector API) before GPU offloading is even enabled.
  - Keeps `folia-server` patches razor-thin and upstream-mergeable to `PaperMC/Folia` and `PaperMC/Paper`.
- **Negative / Trade-offs:**
  - Does not accelerate custom Java-mod `DensityFunction` classes (acceptable on Folia/`GoldLeaf`, where worldgen customization is datapack-driven or via Bukkit `CustomChunkGenerator`).
  - OpenCL driver quirks (shader compilation warm-up, VRAM usage, PoCL incompatibility) must be managed cleanly in the Layer 2 engine with disk shader caching and fail-closed startup probes.
  - Wave coalescing (`16–32` chunks) must carefully honor its `1–2 ms` flush deadline so low-concurrency exploration never perceives extra chunk-load latency.

## References

- Materialized Folia `26.3` seams in `/home/leaf26/GoldLeaf`:
  - `folia-server/src/minecraft/java/net/minecraft/world/level/chunk/status/ChunkPyramid.java` (`GENERATION_PYRAMID`, `ChunkStatus.TERRAIN`, `blockStateWriteRadius(0)`)
  - `folia-server/src/minecraft/java/net/minecraft/world/level/chunk/status/ChunkStatusTasks.java` (`generateBiomes`, `buildTerrain`, `generateFeatures`)
  - `folia-server/src/minecraft/java/net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java` (`buildTerrain`, `doFill`, `buildSurface`, `generateCarvers`, `DensityVolume`, `ScopedDensityBuffer`)
- Related `GoldLeaf` and sibling engine ADRs:
  - `GoldLeaf/docs/adr/goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-003-gradient-modeled-region-threading-and-small-machine-overhead-elimination.md`
  - `resource/LeafGen/docs/adr/leafgen-ADR-007-dynamic-request-to-response-worldgen-coverage-and-auto-gate.md`
  - `resource/LeafGen/docs/adr/leafgen-ADR-008-ir-idiom-recognition-unified-gpu-lowering-and-anti-regression-gates.md`
  - `resource/LeafGen/docs/adr/leafgen-ADR-009-bytecode-lifted-stateful-voxel-effect-ir-for-carvers-placements-and-features.md`
- External ecosystem prior art:
  - `RelativityMC/C2ME-fabric` & `c2me-ocl` (Density Function Compiler + OpenCL `BIOMES`/`NOISE` acceleration, Java 25+, datapack compatibility scope)
  - `Steveplays28/noisium` (direct palette writes in terrain fill)
  - `PaperMC/Folia` & `PaperMC/Moonrise` (Starlight light engine & `ChunkSystem`)
