# goldleaf-ADR-017 — Native Typed-AST-to-IR OpenCL Worldgen Pipeline, Kernel Caching, and Chunk-Gen vs. Load Throughput Envelope

**Status:** Accepted (Phase A Implemented)
**Date:** 2026-11-21

## Context

Following the decision in `goldleaf-ADR-004` to retire the standalone `LeafGen` plugin (`resource/LeafGen/*` unwired from `settings.gradle`) and integrate GPU terrain acceleration natively into GoldLeaf via `TerrainGenerationAccelerator`, `IrOpenClTerrainAccelerator`, `OpenClKernelCache`, and `GoldLeafOpenClBridge` (`goldleaf_opencl.c`), two core architectural questions govern the next stage of work:

1. **Compiler Frontend Choice (Typed Java AST vs. ASM Bytecode Scanning):**
   - `LeafGen` originally built `:bytecode-api`'s ASM bytecode lifter (`BytecodeSsaLifter` / `ClosedWorldGraphLifter`) to reverse-engineer arbitrary third-party Fabric/NeoForge Java worldgen mods at runtime.
   - GoldLeaf (`Folia 26.3.x`) does **not** run Fabric/NeoForge Java worldgen mods. Vanilla and all Minecraft datapacks (`Terralith`, `Tectonic`, `Incendium`, `Nullscape`, etc.) compose worldgen exclusively from Mojang's unobfuscated, closed set of ~25 typed `DensityFunction` / `NoiseRouter` / `BiomeSource` record types, while Bukkit plugin generators (`CustomChunkGenerator`) explicitly bypass GPU acceleration (`goldleaf-ADR-004`).
   - Scanning `.class` bytecode via ASM for Mojang's own records adds unnecessary fragility (synthetic bridges, lambda shapes, object-graph reflection, `ClassLoader` byte extraction, and Leyden AOT incompatibility per `goldleaf-ADR-016`) and hides high-level semantic invariants (`Cache2D` column hoisting, `FlatCache` quart resolution, `Interpolated` `5×49×5` coarse grid boundaries) that are directly visible on the live Java AST.
2. **Empirical Throughput Ceiling (Can Chunk Generation Approach or Exceed Chunk Load Throughput?):**
   - Operators want to know whether GPU-accelerated chunk generation can match or exceed chunk load throughput, and where Amdahl's Law limits end-to-end `EMPTY -> FULL` generation.

### Measured RTX 3090 Baseline (`IrOpenClTerrainAccelerator`, 2026-11-21)

Benchmarking GoldLeaf's native `IrOpenClTerrainAccelerator` + `libgoldleaf_opencl.so` (`DENSITY_VOLUME_BATCH_F32`) against a 16-octave synthetic `NoiseRouter`-class workload on an **NVIDIA GeForce RTX 3090 (`sm_86`)** vs. host CPU establishes the concrete GPU/PCIe/cache envelope:

| Metric / Workload | CPU 1-Thread | CPU 8-Thread | RTX 3090 `batch=16` (Before -> After) | RTX 3090 `batch=32` (Before -> After) | RTX 3090 `batch=128` (Before -> After) |
|---|---|---|---|---|---|
| **IR Lift + Emit (cold ASM) / Warm `.clbin` Load** | `57.53 ms` (ASM) | — | Cold `clBuildProgram`: `146.48 ms` | Warm `.clbin` load: **`0.61 ms`** (`240×` faster) | — |
| **Coarse `5×49×5` (`1,225` cells/ch) `FP64` (`VANILLA_EXACT_FP64`, diagnostic)** | `1,499.6 ch/s` | `7,421.3 ch/s` | `91,081` -> **`97,150 ch/s`** (`0.165 ms`) | `103,072` -> **`100,292 ch/s`** (`0.319 ms`) | `112,124` -> **`124,565 ch/s`** (`1.028 ms`, `+11%`) |
| **Coarse `5×49×5` (`1,225` cells/ch) `FP32` (`FAST` + SFU)** | `1,603.1 ch/s` | `10,003.6 ch/s` | `253,450` -> **`346,847 ch/s`** (`0.046 ms`, `+37%`) | `438,487` -> **`643,410 ch/s`** (`0.050 ms`, `+47%`) | `679,228` -> **`1,002,678 ch/s`** (`0.128 ms`, `+48%`) |
| **Dense `16×384×16` (`98,304` vox/ch) `FP64` (`VANILLA_EXACT_FP64`, diagnostic)** | `20.7 ch/s` | `152.1 ch/s` | `1,453` -> **`1,780 ch/s`** (`8.99 ms`, `+22.5%`) | `1,471` -> **`1,787 ch/s`** (`17.91 ms`, `+21.5%`) | — |
| **Dense `16×384×16` (`98,304` vox/ch) `FP32` (`FAST` + SFU + Pinned DMA)** | `20.7 ch/s` | `147.3 ch/s` | `6,437` -> **`15,486 ch/s`** (`1.033 ms`, **`2.41×`**) | `7,030` -> **`14,635 ch/s`** (`2.186 ms`, **`2.08×`**, kernel `345k ch/s`) | — |

Three engineering facts follow immediately from these measurements:
1. **Batching (`WorldgenWaveCoalescer`) + 1D Full-Warp Coalescing are mandatory:** At `batch=1`, JNI/PCIe launch latency caps `5×49×5` `FP64` (`VANILLA_EXACT_FP64`) at `6.6k ch/s`; at `batch=16–128` with flat 1D `local_work_size=128`, throughput reaches **`97k–125k ch/s`** (`FP64` diagnostic) and **`347k–1,002k ch/s`** (`FP32` + hardware SFU).
2. **Disk-cached `.clbin` binaries eliminate startup stalls:** Warm reload takes **`0.61 ms`** vs. `146.48 ms` cold OpenCL compilation (and direct `DensityFunctionIrLowerer` AST lowering avoids ASM scanning completely).
3. **Pinned DMA staging (`CL_MEM_ALLOC_HOST_PTR`) + Compact 2-bit Section Writeback (`SECTION_PALETTE_FILL_U2`) break the PCIe wall:**
   - Moving from dense `16×384×16` `float[]` (`384 KiB/chunk`) to fused 2-bit section-palette fill + per-section `ushort4(nonEmpty, fluid, codeMask, 0)` headers (`24.19 KiB/chunk`, `15.9×` smaller) written directly into reusable off-heap `ByteBuffer`s (`CompactSectionWritebackBuffer`) on a 16-octave MC `26.3` `NoiseFunction` + `GradientFunction` Overworld workload on RTX 3090:
     - **`VANILLA_EXACT_FP64` (`FP64`, diagnostic):** `3,175–3,574 ch/s` -> **`4,213–5,289 ch/s`** (**`1.33–1.62×`**); pure PCIe readback for 128 chunks dropped from **`7.31 ms` -> `0.43 ms`** (**`17×`**, `3.4 µs/chunk`, `< 2%` of wall time).
     - **`FAST` (`FP32` + SFU):** `9,357–10,280 ch/s` -> **`12,246–15,871 ch/s`** (**`1.32–1.64×`**); pure PCIe readback for 128 chunks dropped from **`7.36 ms` -> `0.41 ms`** (**`18×`**, `3.2 µs/chunk`), while eliminating CPU float classification and enabling O(1) skips of uniform sky `AIR` sections (`CompactSectionWritebackApplier`).
4. **GPU Compute Headroom for Neighbor Surface / Structure Probes:**
   - Because GPU compute remains substantially faster than PCIe bandwidth on compact outputs, the same GPU wave can cheaply evaluate compact neighbor-chunk heightmap / preliminary-surface / biome probes (`<= 512 B/chunk`) so CPU `STRUCTURE_STARTS` and `FEATURES` (trees, villages, Jigsaw footprints) can resolve cross-chunk placement bounds without stalling on full neighbor chunk generation.

### Chunk Generation vs. Chunk Load Throughput Envelope (Amdahl's Law & Storage Tier)

Comparing against GoldLeaf's `.lca` / `.mca` disk decode benchmarks (`goldleaf-ADR-005`), chunk load throughput depends strongly on the underlying storage tier (**NVMe SSD** vs. **SATA SSD** vs. **HDD**, where `.mca` random 4–16 KiB seeks hit IOPS ceilings):

| Pipeline / Regime | Typical Sustained Throughput (8–16 cores + dGPU) | Dominant Bottleneck |
|---|---|---|
| **Raw `.lca` Mode A (`0-LZ`) disk decode** (`goldleaf-ADR-005`, cached/NVMe) | `~23,200 ch/s` per core (`43.06 µs/ch`) | Memory bandwidth / syscall |
| **Raw `.mca` Zlib disk decode** (cached/NVMe) | `~8,200 ch/s` per core (`~122 µs/ch`) | Zlib inflate |
| **End-to-end Server Chunk Load (`EMPTY -> FULL`, `.lca` warm / NVMe)** | `~4,000–10,000+ ch/s` | NMS `LevelChunk` / Moonrise region attach |
| **End-to-end Server Chunk Load (`EMPTY -> FULL`, `.mca` cold on NVMe)** | `~1,000–2,500 ch/s` | Zlib + `CompoundTag`/`NbtIo` + `PalettedContainer` hydration |
| **End-to-end Server Chunk Load (`EMPTY -> FULL`, `.mca` cold on SATA SSD)** | `~400–1,200 ch/s` | SATA queue depth / 4–16 KiB random IOPS (`~40–90k`) + Zlib/NBT |
| **End-to-end Server Chunk Load (`EMPTY -> FULL`, `.mca` cold on HDD)** | `~50–400 ch/s` | Mechanical seek latency (`~100–200` random IOPS) |
| **Stock Vanilla / Folia Chunk Gen (`.mca`, CPU only)** | `~80–300 ch/s` (Vanilla), `~30–120 ch/s` (Terralith/Tectonic) | `BIOMES` + `TERRAIN` density & `doFill` + `.mca` NBT/Zlib |
| **GoldLeaf Stage 1–3 (`BIOMES` + `TERRAIN` `DensityVolume` GPU + `SectionBatchedTerrainFill` + `.lca`)** | **`~400–1,200 ch/s`** (100% vanilla-exact; `6–15×` on heavy datapacks) | CPU `Aquifer` + `buildSurface` + `generateCarvers` + `3×3` `FEATURES` + `STRUCTURE_STARTS` |
| **GoldLeaf Stage 4 (Fused GPU `Density` + `Interp` + `Aquifer`/`Surface` -> compact section indices + Jigsaw pruning)** | **`~1,500–2,500 ch/s`** (100% vanilla-exact ceiling) | `3×3` cross-chunk `FEATURES` (`2–5 ms/ch/core`) & `STRUCTURE_STARTS` |
| **Terrain-Only / Headless `.lca` Pregen (no `FEATURES`)** | **`5,000–15,000+ ch/s`** (exceeds `.mca` load; `leafgen-ADR-003` hit `5,389 ch/s`) | PCIe / `.lca` encode (`~0.38 ms/ch`) |

**Honest conclusion (storage-tier dependent):**
- **On HDD and SATA SSD:** GoldLeaf's GPU-accelerated `FULL` chunk generation (`400–1,200 ch/s` in Stage 1–3, up to `1,500–2,500 ch/s` in Stage 4) can **exceed cold `.mca` HDD load throughput (`50–400 ch/s`)** and **match or exceed cold `.mca` SATA SSD load throughput (`400–1,200 ch/s`)**, because GPU compute + `.lca` avoids random `.mca` seeks and Zlib/NBT inflation.
- **On NVMe SSD:** Vanilla-exact `FULL` chunk generation can approach **cold `.mca` NVMe load throughput (`~1,000–2,500 ch/s`)**—especially on heavy datapacks where noise evaluation dominates—once `BIOMES`, `DensityVolume`, `Aquifer`/`buildSurface`, `.lca` zero-NBT saves, and Jigsaw bounding-box pruning are combined, but **cannot exceed warm `.lca` NVMe load throughput (`4,000–10,000+ ch/s`)** on 8–16 CPU cores because `ChunkStatus.FEATURES` (`blockStateWriteRadius(1)`, `3×3` `WorldGenRegion`, stateful RNG, trees/ores/structures) remains inherently CPU-bound (`~2–5 ms/chunk/core`).
- **Terrain-only / custom worlds without vanilla `FEATURES`** *can* exceed `.mca` load throughput on all storage tiers (`5,000+ ch/s`) by emitting compact section palette buffers directly from GPU to `.lca`.

## Decision

1. **Simplify GoldLeaf's Primary Compiler Frontend to Direct Typed Java AST -> `:bytecode-api` `ConceptModule` IR -> OpenCL:**
   - Keep `:bytecode-api`'s pure Tier-1 IR (`BytecodeConceptIr.ConceptModule`), optimizer (`ConceptOptimizerPass` + `IrIdiomRewriteRegistry`), and OpenCL emitter (`OpenClBackendEmitter#emitDensityVolumeBatchProgram` with `DENSITY_VOLUME_BATCH_F32`, SHA-256 digests, `__constant` LUT compaction, and `gl_lut_spill`) as the **shared backend**.
   - Replace ASM `.class` bytecode scanning on GoldLeaf's primary Minecraft worldgen path with a **direct typed Java AST lowering pass** (`DensityFunctionIrLowerer`) that walks Mojang's `26.3` `NoiseRouter` / `DensityFunction` / `BiomeSource` record trees directly into `ConceptModule` SSA IR:
     - Preserves semantic annotations natively (`Cache2D` hoisted out of Y loops, `FlatCache` quart sampling, `Interpolated` `5×49×5` coarse grid dispatch, direct `NormalNoise`/`Spline` constant-table extraction without reflective field scraping).
     - Works identically for Vanilla and all datapacks (`Terralith`, `Tectonic`, `Incendium`, `Nullscape`) with zero ASM/reflection overhead and full Leyden AOT compatibility (`goldleaf-ADR-016`).
     - Fails closed cleanly to stock Mojang CPU evaluation on any unrecognized custom `DensityFunction` subclass (`S-004`).
   - Retain `:bytecode-api`'s ASM `BytecodeSsaLifter` / `ClosedWorldGraphLifter` as an **optional, non-hot-path** capability in `:bytecode-api` (for static/standalone tools or future opt-in custom Java density evaluators), never required for Vanilla or datapack worlds.
2. **Hybrid JAR-Packed + Single-Flight Atomic Disk Kernel Cache (`OpenClKernelCache`):**
   - Pack build-verified vanilla Overworld/Nether/End `.cl` kernels under `META-INF/goldleaf/kernels/<digest>.cl` for zero first-boot lowering cost.
   - Cache both lifted datapack `.cl` sources and GPU-compiled `CL_PROGRAM_BINARIES` (`.clbin`, keyed by `(vendor, deviceName, driverVersion, buildOpts, routerIrSha256)`) on disk via single-flight `ConcurrentHashMap` coordination and temp-file + `ATOMIC_MOVE` writes, recovering cleanly from `CL_INVALID_BINARY` with a `WARNING` (`S-004`).
   - Run all lowering, OpenCL source emission, and `clBuildProgram` work strictly on a dedicated bounded daemon executor (`goldleaf-ir-opencl-compiler`), never on main or Folia region tick threads (`S-005`).
3. **Staged Throughput Roadmap (Profile-Gated Against Amdahl's Law):**
   - **Phase A (Wire `BIOMES` + `TERRAIN` `DensityVolume` SPI):** Wire `IrOpenClTerrainAccelerator` + `WorldgenWaveCoalescer` (`16–32` chunks/wave, `1–2 ms` deadline) + `SectionBatchedTerrainFill` into `ChunkStatusTasks` / `NoiseBasedChunkGenerator`, backed by direct JUnit 1:1 CPU/GPU parity tests against real MC `26.3` `NoiseRouter` instances.
   - **Phase B (Compact GPU Readback — Fuse `Interp` + `Aquifer` Fast-Path + `buildSurface`):** Only after Phase A profiling confirms CPU `doFill`/`Aquifer`/`buildSurface` is the new bottleneck, fuse coarse-to-voxel interpolation and surface/aquifer classification into the GPU kernel so readback stays `<= 4 KiB/chunk` (avoiding the `384 KiB/chunk` dense-float PCIe wall).
   - **Phase C (CPU `STRUCTURE_STARTS` & `FEATURES` Relief):** Profile `STRUCTURE_STARTS` and `FEATURES` after Phases A–B; apply upstream-clean Jigsaw bounding-box pruning and `WorldGenRegion` section caching if they dominate remaining wall time. Never reintroduce hand-authored GPU `FN_*` feature stampers (`goldleaf-ADR-004`).
   - **Phase D (Zero-Decode GPU DMA → Unified Mode A `.lca` Format, `goldleaf-ADR-005`):** Align GPU DMA readback directly to GoldLeaf's unified Mode A `.lca` `SectionFrame` (or direct native section layout with precomputed `LevelChunkSection` counts) so the host never runs a secondary `hostApply` decode/unpack pass (`1.82 s` measured across `4,482` chunks on RTX 3090 in live 26.3 profiling) between GPU DMA and live chunk memory / disk persistence.
4. **Zero-`FP64` Production Kernel Invariant & `<= 0.05%` Density-Boundary Tolerance Rule:**
   - Default `ParityMode.VANILLA_EXACT` **shall** execute with `INT64_Q32_FP32` (`int64` / Q32 coordinate math + IEEE `FP32` arithmetic under `-cl-std=CL1.2`, **zero `FP64`**) across all production GPU worldgen kernels (`NoiseRouter`, `Aquifer`, `Carver`, `Surface`, and feature pre-passes). Large-coordinate precision drift is eliminated by `INT64_Q32` coordinate reduction without `double`.
   - `ParityMode.VANILLA_EXACT_FP64` (`double` / `FP64`) is strictly an **opt-in diagnostic reference mode** and **shall not** be made default or reintroduced into production GPU kernels. Consumer dGPUs carry a `1:64` `FP64:FP32` throughput ratio, costing **`~4×–8.7×`** throughput in the RTX 3090 baseline above (`125k` vs. `1,002k ch/s` coarse; `1,780` vs. `15,486 ch/s` dense).
   - **Vanilla-parity tolerance:** Single-block `0.0` density-threshold rounding differences of **`<= 0.05%`** of evaluated blocks/voxels between `INT64_Q32_FP32` GPU output and CPU `double` reference **shall** be accepted as within vanilla-exact terrain parity and **shall not** trigger a regression to `FP64`. (Strict bit-exactness remains mandatory for `.lca` <-> `.mca` storage and tick/redstone/contraption semantics per `goldleaf-ADR-005`, `008`, `013`, and `014`.)

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| **Defaulting production GPU worldgen kernels to `FP64` (`double`) to eliminate `<= 0.05%` density-boundary rounding** | Consumer GPUs execute `FP64` at `1:64` of `FP32` rate (`~4×–8.7×` end-to-end throughput loss on RTX 3090). `INT64_Q32_FP32` eliminates large-coordinate drift with zero `FP64`; `<= 0.05%` single-block threshold differences do not break gameplay, structures, or farms. `VANILLA_EXACT_FP64` remains available as an opt-in diagnostic mode only. |
| **Keep ASM bytecode scanning (`BytecodeSsaLifter` / `ClosedWorldGraphLifter`) as the primary Minecraft `NoiseRouter` frontend** | Solves a Fabric/NeoForge Java-mod problem that does not exist on Folia; adds javac/bytecode-shape fragility, reflective object-graph scraping, and Leyden AOT friction while losing direct `Cache2D`/`Interpolated` AST semantics. |
| **Discard `:bytecode-api`'s `ConceptModule` IR & `OpenClBackendEmitter` and write a brand-new OpenCL string emitter inside GoldLeaf** | Duplicates an already-tested, pure Tier-1 SSA optimizer (`ConceptOptimizerPass`), `__constant` LUT compactor + `gl_lut_spill` allocator, and deterministic SHA-256 OpenCL C99 emitter (`BytecodeConceptTranspilerTest`). Keeping the shared IR backend while simplifying the frontend gets the best of both worlds. |
| **Read back full dense `16×384×16` `float[98304]` arrays (`384 KiB/chunk`) from GPU** | Measured on RTX 3090: PCIe readback caps `FP32` end-to-end throughput at `7,030 ch/s` even when the GPU kernel runs at `98,083 ch/s`. Dispatching the `5×49×5` coarse grid (`4.9 KiB/chunk`, `103k–438k ch/s`) or fused compact section indices (`<= 4 KiB/chunk`) avoids PCIe saturation. |
| **Move `FEATURES` (`3×3` `WorldGenRegion`) onto GPU to try to beat `.lca` load throughput** | Reintroduces `LeafGen`'s `FN_*` stamper regression loop (broken trees, waterlogging, structures, cross-chunk nondeterminism) explicitly rejected in `goldleaf-ADR-004`. |

## Consequences

- **Positive:**
  - Eliminates ASM bytecode-lifting fragility on GoldLeaf's Minecraft worldgen hot path while reusing `:bytecode-api`'s proven IR optimizer, LUT spill allocator, and OpenCL C99 emitter.
  - Warm server restarts load compiled GPU binaries in `~0.61 ms` (`240×` faster than cold `clBuildProgram`) and support JAR-packed vanilla `.cl` kernels plus automatic disk caching for datapacks.
  - Sets an honest, empirically measured throughput target: `400–1,200 ch/s` in Phase A (`6–15×` on heavy datapacks) scaling toward `1,500–2,500 ch/s` (cold `.mca` load parity) with fused `Aquifer`/`Surface` + Jigsaw pruning, without making false claims about beating warm `.lca` load on full vanilla `FEATURES`.
- **Negative / Trade-offs:**
  - Requires maintaining a ~25-node `DensityFunctionIrLowerer` visitor in GoldLeaf across Mojang `26.x` updates (substantially smaller and simpler than maintaining ASM bytecode lifting against Mojang internals).
  - Custom Java `DensityFunction` subclasses injected by third-party NMS plugins fall back to CPU unless explicitly opted into `:bytecode-api`'s secondary bytecode lifter.

## References

- GoldLeaf implementation & tests:
  - `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/worldgen/IrOpenClTerrainAccelerator.java`
  - `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/worldgen/OpenClKernelCache.java`
  - `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/worldgen/GoldLeafOpenClBridge.java`
  - `GoldLeaf/folia-server/native/goldleaf_opencl.c`
  - `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/worldgen/SectionBatchedTerrainFill.java`
  - `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/worldgen/CompactSectionWritebackBuffer.java`
  - `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/worldgen/CompactSectionWritebackApplier.java`
  - `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/worldgen/DensityVectorKernels.java`
  - `GoldLeaf/folia-server/src/test/java/dev/leafcraft/goldleaf/worldgen/IrOpenClTerrainAcceleratorTest.java`
- Shared Tier-1 IR backend (`:bytecode-api`):
  - `api/bytecode-api/src/main/java/io/github/dailystruggle/bytecode/transpile/OpenClBackendEmitter.java`
  - `api/bytecode-api/src/main/java/io/github/dailystruggle/bytecode/transpile/ConceptOptimizerPass.java`
- Related ADRs:
  - `GoldLeaf/docs/adr/goldleaf-ADR-004-worldgen-patching-boundary-and-gpu-offload-spi.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-005-lca-format-adoption-across-disk-memory-and-network.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-016-boot-shutdown-telemetry-leyden-aot-and-plugin-warmup.md`
  - `resource/LeafGen/docs/adr/leafgen-ADR-003-gpu-structure-grid-beardifier-and-lca-template-stamps.md`
  - `resource/LeafGen/docs/adr/leafgen-ADR-008-ir-idiom-recognition-unified-gpu-lowering-and-anti-regression-gates.md`
