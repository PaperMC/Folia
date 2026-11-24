# goldleaf-ADR-005 — Native `.lca` Format (`LCA-ICD-3.0`) Adoption Across Disk, Memory, and Network

**Status:** In Progress
**Date:** 2026-11-21

## Context

### 1. `.lca` (`LCA-ICD-3.0`, `formatVersion = 0x0003`) Fit for GoldLeaf

`goldleaf-ADR-001` established that both ends of GoldLeaf's hardware barbell—4–8 core surplus workstations on the floor and 500–2,000+ player `LeafShard` clusters on the ceiling—hit the same four structural bottlenecks: region storage I/O and save stalls, chunk-serialization and heap pressure, multi-node state replication, and client chunk-streaming bandwidth.

The **`.lca` (LeafCraft Anvil)** binary format (`LCA-ICD-3.0`, `formatVersion = 0x0003`, MIT-licensed specification and codec in `api/anvil-api`) was engineered specifically to address all three layers—**disk**, **memory**, and **network**—with a single self-describing, `Zero Zstd`, lossless (`L_inf = 0`) chunk representation:

1. **Layer 1 — Fixed Header, Lock-Free Pointer Directory & `SEC-DED` Parity (`0x0000..0x283F`, `~10.1 KiB` raw / `~25.6 KiB` total per open region):**
   - `64 B` Superblock (`"LCA_V001"`, `formatVersion = 0x0003`, `storageKind` $\in \{0\text{ REGION\_VOXEL}, 1\text{ ENTITIES}, 2\text{ POI}, 3\text{ MAP\_TILE}\}$, `XXH64`/`CRC64` checksum `CBIT T2`), `8,192 B` Atomic Chunk Pointer Directory (`uint64[1024]`: `slabOffset35 | codec1 | exactByteLen20 | generation8`), and `1,024 B` Extended Hamming `(72, 64)` `SEC-DED` pointer syndrome table, closed by an `8 B` `"LCA1_END"` footer canary.
   - Open live archives (`LcaLiveArchive`) retain only the header prefix in heap (`~12.8 MiB` across `512` open regions) and serve positional `FileChannel` reads/writes without `.linear`'s whole-region RAM retention or Windows `FileChannel#map` handle-lock leaks.
2. **Layer 2 — Chained, Content-Agnostic Factored Region Shared Dictionary (`0x2040`, `anvil-api-ADR-006`):**
   - Append-only `256 B`-aligned segments with pre-allocated slack, relative continuation trailers, downward-growing `SEC-DED` syndromes, and an optional `384 B` Canonical Huffman + cross-chunk LZ `dictPatternArena`.
   - `0x0003` factors states into four tables (`BaseBlock`, `PropTuple`, `BlockState`, `Biome`) and removes all hardcoded `STATIC_CORE_*` vanilla tables (quarantined in `LcaV2LegacyCodec` for non-mutating `0x0002` reads and atomic `.v3upgrade.tmp` upgrades), giving vanilla, future `26.x+`, and datapack/modded blocks identical `1-byte` `VarInt` local IDs and $O(1)$ pre-baked NBT caches.
3. **Layer 3 — `256 B` CoW `ChunkFrame` Slabs, Fast-Greedy `LCD2` & Columnar Residuals (`anvil-api-ADR-004`, `005`, `006`):**
   - Live saves write to best-fit unpinned `256 B` sub-slabs via Copy-on-Write (`CoW`) guarded by a 4-epoch grace ring and 64-slot lock-free per-thread hazard pointers (`95.0%–100.0%` slot reuse across `10,000` saves, eliminating `.mca`'s `4 KiB` sector padding and `4.20 MB -> 18.69 MB` EOF spill growth).
   - Supports **Mode A** (`codec1 = 0`, `0-LZ`, zero decompression) and **Mode B** (`codec1 = 1`, `LCD2` `0x4C44` with region-frozen `384 B` 12-bit Canonical Huffman 4K LUT). Under `anvil-api-ADR-005` Study E, Fast-Greedy `LCD2` (`lcd2Level <= 2`) cuts body encode `6.90×` (`300.86 -> 43.62 µs/chunk`) and speeds decode `-14.3%` (`23.26 -> 19.94 µs/chunk`), making single-pass `v3-Columnar Mode B` saves faster than `.mca` Zlib (`400.86 µs` vs. `440.46 µs`) at `50.98%` of `.mca` size (`1.96×` faster View Rd).
   - Pure-ID Slab-Local Micro-Palettes work past `8,192` states; `boilerplateFlags16` bit `14` (`0x4000`) carries `ChunkPatchTail` for $O(1)$ delta patching; bit `15` (`0x8000`) carries `v3` length-prefixed columnar `block_ticks`, `fluid_ticks`, and template-deduplicated `block_entities` (strict vanilla-schema fallback to `residualNbtSlice` on custom keys).
4. **Layer 4 — Self-Describing $16 \times 16 \times 16$ `SectionFrame`s & 4-Tier Bit-Rot Recovery:**
   - Uniform sections (`66.4%` of natural sections) fold into **`2 B`** inside `sectionOffsets[i]` (`0` body bytes).
   - Non-uniform sections omit dominant `palette[0]` (`~90.8%` of voxels), track active $4 \times 4 \times 4$ octants in `octreeOccupancyMask64`, pick the best of four 3D space-filling curves (`XZY`, `H_3`, `Y+H_2`, `YZX`), and encode minority states via adaptive Delta-Roaring containers bounded by a flat bit-packed ceiling (`leafrtp-ADR-072`), with opt-in `SEC_EXT_GRAY_BITPLANES = 0x02` (`-40.71%` Mode A on stratified structures).
   - Lighting folds uniform sections to **`1 B`** (`LIGHT_MODE_CONST`) and encodes gradients via 4-bitplane Reflected Gray code (`~89–210 B`), with optional `v3` Eikonal + `B1` (`64-bit` octant-bitmask + packed 4-bit residuals, `13.9×` faster decode than Gray residuals).
   - Every chunk slab is independently self-describing and protected by 4-tier bit-rot recovery (`SEC-DED` pointer/dict/header repair, footer canary, reverse-scan slab recovery, and Tier-3 dominant-stratum quarantine).

### 2. Verified Materialized Folia `26.3.x` Seams Across Disk, Memory, and Network

Inspecting `/home/leaf26/GoldLeaf` (`ver/26.3.x` materialized `folia-server`) confirms why native fork SPIs strictly outclass external plugin/bytecode interception (`LeafAnvil` `-javaagent` / `Unsafe.allocateInstance`):

1. **Disk Seam (`ChunkSystemRegionFileStorage` — 7 methods):**
   - `ChunkDataController`, `EntityDataController`, and `PoiDataController` (`ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.*`) all delegate cleanly to `ChunkSystemRegionFileStorage` (`moonrise$doesRegionFileNotExistNoIO`, `moonrise$getRegionFileIfLoaded`, `moonrise$getRegionFileIfExists`, `moonrise$startWrite`, `moonrise$finishWrite`, `moonrise$readData`, `moonrise$finishRead`), implemented by `RegionFileStorage.java`.
   - `RegionFileStorage#moonrise$startWrite` (`RegionFileStorage.java:81-103`) currently serializes a full `CompoundTag` via `NbtIo.write` into 4 KiB-sectored `.mca` files with per-chunk `Zlib`.
2. **Memory & Serialization Seam (`SerializableChunkData`, `LevelChunkSection`, `PalettedContainer`):**
   - `SerializableChunkData#fromChunk` (`SerializableChunkData.java:471-524`) captures `chunk.getTicksForSerialization(level.getRedstoneGameTime())` (Folia's sole edit in this file), `blockEntities`, `heightmaps`, `structureData`, and `persistentDataContainer`.
   - `SerializableChunkData#write()` (`SerializableChunkData.java:526-590`) converts every `LevelChunkSection`'s `PalettedContainer<BlockState>` and `PalettedContainerRO<Holder<Biome>>` into boxed `CompoundTag`/`ListTag` trees and writes Starlight's `BLOCKLIGHT_STATE_TAG` / `SKYLIGHT_STATE_TAG` (`StarlightSectionData`).
   - `LevelChunkSection` (`LevelChunkSection.java:11-42`) implements Moonrise's `BlockCountingChunkSection` (`specialCollidingBlocks`, `ShortList tickingBlocks`), and `PalettedContainer` (`PalettedContainer.java:25-92`) exposes `public volatile Data<T> data`, `FastHashMapPalette`, `SimpleBitStorage` (`>= 4` bits/voxel = `2,048 B` minimum floor), and Paper's `presetValues` for anti-xray.
   - **Critical Empirical Lesson (`leafanvil-ADR-003` §6, `leafanvil-ADR-006`):** Maintaining a *shadow* `.lca` `LcaSectionBacking` cache alongside already-hydrated NMS `PalettedContainer` objects does **not** reduce JVM heap—it trades a small cold-frame footprint (`~2–4 KiB`/chunk) for zero-NBT saves, zero-copy wire slicing, and `~10–500 ns` lock-free async reads (`goldleaf-ADR-002`). True heap reduction requires either avoiding `CompoundTag` churn or natively backing cold `PalettedContainer`s without breaking `BlockCountingChunkSection`.
3. **Network Seam (`PlayerChunkSender` & Netty):**
   - `PlayerChunkSender#sendNextChunks` / `sendChunk` (`PlayerChunkSender.java:55-97`) paces batches with `ClientboundChunkBatchStartPacket` / `ClientboundChunkBatchFinishedPacket`, invokes `level.chunkPacketBlockController.shouldModify(connection.player, chunk)` (Paper's region-thread anti-xray tax), and sends `ClientboundLevelChunkWithLightPacket` (`~45–85 KiB` raw / `~6–14 KiB` stateless per-packet `Zlib`, resent in full on every revisit).
   - Multi-node `LeafShard` clusters also require live `10-chunk` cross-border Halo synchronization; NBT-based replication saturates links and tick threads, whereas `.lca`'s `LCAS` (`0x4C434153`) frames reuse the exact `4-byte` `EntryPatchDelta` and `SectionWireSlice` primitives.

## Decision

GoldLeaf adopts **`.lca` (`LCA-ICD-3.0`, `formatVersion = 0x0003`)** as its native world storage, zero-copy cold-chunk interchange, and negotiated/inter-shard wire format—scoped strictly to the **empirically proven best use cases** in each domain (disk, memory, and network) and partitioned into **Layer 1 (upstream-candidate Folia/Paper SPIs)** and **Layer 2 (GoldLeaf + MIT `api/anvil-api` / `LeafAnvil` / `LeafShard` wiring)** per `goldleaf-ADR-001`.

### 1. Domain-by-Domain Adoption Matrix (Best Use Cases vs. Non-Goals)

| Domain | Best Use Cases (Adopt in GoldLeaf) | Explicit Non-Goals / Guardrails |
|---|---|---|
| **Disk** | **1. Default Live World Storage (`region/`, `entities/`, `poi/`):** `v3-Columnar Mode B` (`FastGreedy`, `lcd2Level = 1`) cuts disk footprint `~49%–53%` (`~50.98%` of `.mca`), beats `.mca` Zlib on warmed saves (`400.86 µs` vs. `440.46 µs`), cuts View Rd `1.96×`, eliminates `4 KiB` sector spill bloat (`95%–100%` `256 B` CoW reuse), caps open-region RAM at `~25.6 KiB`/region, and adds 4-tier `SEC-DED` bit-rot healing.<br>**2. High-IOPS Pre-Gen / Scratch (`Mode A`, `0-LZ`):** Lowest encode/decode latency (`377.26 µs` save / `43.06 µs` View Rd = `2.84×` faster than `.mca`) at `58.68%` of `.mca` size during `Chunky`/`LeafGen` bursts.<br>**3. Cold Archival / Backups / Schematics (`lcd2Level = 3` + Eikonal `B1` + opt-in `SEC_EXT_GRAY_BITPLANES`):** `47.16%` of `.mca` on natural terrain and `-40.71%` Mode A on stratified structures.<br>**4. Off-Thread Cold Probes (`goldleaf-ADR-002` Tier 3):** Direct `AnvilChunkView` / column/terrain probes for `LeafRTP`, `LeafMap`, and auditors with zero regionizer activation and zero `TASK_QUEUE_TICKET` churn. | - **Do not** enable Eikonal BFS light (`65.76 µs/chunk` BFS cost per `anvil-api-ADR-005`) or `SEC_EXT_GRAY_BITPLANES` / `FEATURE_RANK_EXCL` by default on hot live-tick saves; gate them to archival/compaction passes.<br>- **Do not** emit Eikonal light residuals in `storageKind = 3` (`MAP_TILE`) WebGL2/WebGPU tiles.<br>- **Do not** introduce adaptive binary range coding (`anvil-api-ADR-005` rejected `1.7×–13.9×` decode regressions).<br>- **Never** lock operators in: ship the MIT `.lca <-> .mca` round-trip converter (`LcaRegionWriter.transcodeToMca` / `transcodeFromMca`) as a first-class workflow. |
| **Memory** | **1. Zero-NBT Save/Load Bypass (`SerializableChunkData` <-> `.lca` `ChunkFrame`):** Eliminate `~100–210 KiB`/chunk of short-lived `CompoundTag`, `LinkedHashMap`, and `byte[]` churn on autosaves and pre-generation by encoding `PalettedContainer` + Starlight light + `redstoneGameTime` ticks directly into `ScratchArena`.<br>**2. Unified Cold `.lca` `ChunkFrame` Residency + Bounded AIMD `SparseDirtyCellMap` LRU (`leafanvil-ADR-006`):** Keep clean loaded chunks at `0` slots in `DirtyChunkLruCache` (immutable `~2–4 KiB` `ChunkFrame` + cached `dictIdToRuntimeId`), absorb edits in $O(1)$ (`~2 ns`) via `4-byte` packed `((pos12 + 1) << 19) | (runtimeStateId & 0x7FFFF)` cells (`64 B` for `1..16` edits), and flush via section-spliced CoW (verbatim `System.arraycopy` of untouched sections).<br>**3. Cache-Line-Isolated Read Snapshots (`goldleaf-ADR-002` Tiers 0–1):** Serve async read-only queries in `~10–500 ns` from `@Contended` immutable `baseFrame` / tick-end `frozenOverlay` without `30–80 KiB` `CraftChunk#getChunkSnapshot` allocations or MESI `M <-> S` cache-line bouncing.<br>**4. Profile-Gated Native Cold `PalettedContainer` Backing (Stage 4):** Reclaim `2–2.7 KiB`/section on 2–8 GB floor hosts by backing unmodified non-uniform sections directly with `.lca` slices while preserving `BlockCountingChunkSection` and `PalettedContainer#data` contracts. | - **Do not** use Delta-Roaring `SectionFrame`s as a mutable tick-thread voxel container; active redstone/farm edits always land in native `PalettedContainer` or `SparseDirtyCellMap` (`O(1)` writes, zero curve permutation on tick threads).<br>- **Do not** claim JVM heap savings from a shadow `.lca` buffer coexisting with hydrated `PalettedContainer`s; Stages 1–3 target GC/allocation-rate elimination, save speed, and async read latency, while Stage 4 targets steady-state old-gen heap.<br>- **Do not** hydrate `CompactBaseIndex` on write or promote `> 48` edits to `16 KiB` `int[4096]` slabs (`leafanvil-ADR-006`). |
| **Network** | **1. `LeafShard` Inter-Node Halo & Migration Data Plane (`LCAS` `0x4C434153` over `LSHD`):** Primary server-side network win (zero client mod needed). Streams `4-byte` `EntryPatchDelta` updates across the `10-chunk` cross-border Halo ring and `1.5–3.2 KiB` `EntryFullChunk` refills with `SEC-DED-64` syndrome verification and zero tick-thread NBT serialization.<br>**2. Negotiated Client Chunk Streaming (`LCA_NET_V1` + MIT `LeafAnvil-Net`):** `65%–78%` initial bandwidth reduction (`~1.5–3.2 KiB` vs. `6–14 KiB` vanilla), `95%–99%` revisit reduction (`2 B` clean-ack / `0.1–0.3 KiB` `ChunkPatchTail` via `lastUpdateTick` + `SEC-DED-64` client LRU), zero-copy server RAM -> wire slicing, `CAP_LCA_V3_SECTIONS` (`1 << 6`) negotiation, and full `PlayerChunkSender` batch pacing.<br>**3. Low-Upload Floor & Mega-Server Hub Fan-Out (`ODCT` + `MTCF`):** Content-addressed `4,096`-slot `LcaOrderlessDictTable` (`2 B` `refWord16` section deduplication) + `MTCF` spatial multicast (`MultiTargetTcpFanOutRelay` / Velocity fan-out) proven at `1,000` concurrent sockets under a `350 kbps` origin cap.<br>**4. Progressive 3D Subterranean Occlusion & Zero-Bloat Exposure Culling (`leafanvil-ADR-007`):** Compressed-domain `transmissiveOctantMask64` + monotone-sightline BFS + negative-byte-cost `wireSanitizedBaseFrame` ore stripping, replacing Paper's CPU-heavy, `2×–3×` bandwidth-bloating `anti-xray engine-mode: 2` for negotiated clients. | - **Never** require a client mod by default (`network.require-lca-client: false`): unmodified vanilla clients stay on `ClientboundLevelChunkWithLightPacket` with full mixed-client coexistence.<br>- **Never** emit raw `VarInt = 0` uncompressed bypass frames behind Velocity/BungeeCord proxies; negotiate `CAP_PROXY_SAFE_STORED_ZLIB` (`Deflater.NO_COMPRESSION`, `+11 B`/batch, zero LZ77/Huffman CPU).<br>- **Never** bypass Paper's anti-xray silently on negotiated clients: when anti-xray is enabled, `LCA_NET_V1` streams must pass through `wireSanitizedBaseFrame` / Layer 1 occlusion or fall back to vanilla packets. |

### 2. Thin-SPI Patch Layering (`Layer 1` Upstream-Candidate vs. `Layer 2` GoldLeaf)

#### Layer 1 — Upstream-Candidate Patches (`folia-server`, zero `.lca` / `goldleaf.yml` imports)

1. **`RegionStorageProvider` SPI (`ChunkSystemRegionFileStorage` / `RegionFileStorage`):**
   - Introduce a small provider factory SPI in `ca.spottedleaf.moonrise.patches.chunk_system.io` so `ChunkDataController`, `EntityDataController`, and `PoiDataController` can instantiate a pluggable `ChunkSystemRegionFileStorage` implementation (stock `.mca` `RegionFileStorage` by default).
   - Eliminates `Unsafe.allocateInstance`, reflective `RegionFileStorage` field surgery, and `-javaagent` bootstrap fragility.
2. **Direct Section Serialization SPI (`SerializableChunkData` / `MoonriseRegionFileIO`):**
   - Expose an optional binary chunk-payload fast path alongside `CompoundTag` in `RegionDataController.WriteData` / `ReadData` so a storage provider can serialize/deserialize `LevelChunkSection` (`PalettedContainer` + Starlight `DataLayer` + `BLOCKLIGHT_STATE_TAG`/`SKYLIGHT_STATE_TAG`) directly while delegating entities, block entities, structures, PDC, and `DataFixerUpper` version upgrades (`chunkDataVersion != currentDataVersion`) to the existing NBT path.
   - Preserves Folia's `level.getRedstoneGameTime()` tick serialization invariant (`SerializableChunkData.java:493`).
3. **Negotiated Chunk-Batch Stream SPI (`PlayerChunkSender`):**
   - Add a per-connection `ChunkStreamInterceptor` hook inside `PlayerChunkSender#sendNextChunks` / `sendChunk` so a registered network engine can emit a single paced batch payload for negotiated clients (preserving `ClientboundChunkBatchStartPacket` / `ClientboundChunkBatchFinishedPacket`, `PlayerChunkLoadEvent`, and `debugSynchronizers`) and skip Paper's per-packet `chunkPacketBlockController.shouldModify` work when the stream engine supplies its own sanitized wire frame.
4. **Async Read-Only Chunk Probe SPI (`goldleaf-ADR-002`):**
   - Wire into `goldleaf-ADR-002`'s async-first read router so `LeafAnvil` supplies Tier 0/1 (`baseFrame` / `frozenOverlay`) and Tier 3 (off-thread `AnvilChunkView`) reads with stock Paper/Folia fallback.

#### Layer 2 — GoldLeaf Engine Wiring (`goldleaf.yml` + MIT `api/anvil-api` + `LeafAnvil` / `LeafShard`)

1. **Default `.lca` v3 Profile (`goldleaf.yml`):**
   - `storage.format: lca` (with `mca` opt-in fallback and automatic non-destructive detection of existing `.mca` / `0x0002` `.lca` worlds).
   - Default live write profile: `formatVersion = 0x0003` (`LCA-ICD-3.0`), **Mode A (`codec1 = 0`, unified disk = memory columnar format with cached Layer 2 region dictionaries and zero outer LZ/Huffman decompression)**, **columnar ticks & block entities (`v3-Columnar`)**, `SEC-DED` enabled, Eikonal light + `SEC_EXT_GRAY_BITPLANES` + `FEATURE_RANK_EXCL` disabled on live writes.
   - `storage.lca-mode: mode-b` / `archival` (`LCD2`) remains supported as an explicit opt-in archival/back-compat profile, and existing Mode B `.lca` archives read transparently without migration.
   - Atomic `0x0002 -> 0x0003` upgrade on writable open via `LcaRegionWriter.upgradeLegacyArchiveAtomically` (`.v3upgrade.tmp` + `force(true)` + `ATOMIC_MOVE`).
2. **Open Interoperability & Tooling:**
   - Expose MIT `api/anvil-api` conversion and self-healing utilities (`transcodeFromMca`, `transcodeToMca`, `healRegionBitRot`, `AnvilRegionInspectorCli`) directly via GoldLeaf CLI/admin commands so operators can convert worlds in either direction at any time.
3. **Network & Sharding Wiring:**
   - Register `LCA_NET_V1` (`LeafAnvil-Net`, MIT) on the Layer 1 `PlayerChunkSender` SPI with `CAP_LCA_V3_SECTIONS`, `CAP_PROXY_SAFE_STORED_ZLIB`, optional `CAP_ORDERLESS_DICT_AND_MULTICAST` (`ODCT`/`MTCF`), and optional `leafanvil-ADR-007` progressive 3D occlusion streaming.
   - Expose `LcaInMemoryChunkCoordinator`'s `LCAS` (`0x4C434153`) delta/full-chunk export to `LeafShard` (`goldleaf-ADR-001` Stage 4).

### 3. Hardware Barbell Mapping & Rollout Sequence

| Hardware Target | Primary `.lca` Wins |
|---|---|
| **Floor (4–8 core surplus PC, 8–16 GB RAM, NVMe/HDD, residential uplink)** | Disk: `~50%` smaller footprint, `256 B` CoW eliminates HDD/SATA 4 KiB spill fragmentation, `~25.6 KiB`/open-region RAM. Memory: zero-NBT save GC relief + Stage 4 cold-section heap savings. Network: `500 kbps` `ODCT`/`MTCF` + `LCA_NET_V1` revisit caching (`95%–99%` savings) makes home-hosted servers viable. |
| **Ceiling (Multi-node `LeafShard` cluster, 500–2,000+ players)** | Disk: `15.5×` faster shutdown/migration flushes, `SEC-DED` bit-rot resilience across large world volumes. Memory: `~10–500 ns` Tier 0/1 lock-free reads + zero-copy disk-to-wire slicing. Network: `4-byte` `LCAS` `10-chunk` cross-border Halo deltas + `MTCF` Velocity edge fan-out for dense hubs. |

**Phased Rollout:**
1. **Stage 1 (Disk Storage SPI + `.lca` v3):** Ship Layer 1 `RegionStorageProvider` SPI covering `region/`, `entities/`, and `poi/` with `v3-Columnar Mode B (FastGreedy)` default, `SEC-DED`, and MIT `.lca <-> .mca` converter.
2. **Stage 2 (Zero-NBT Serialization + Cold `ChunkFrame` Residency + `goldleaf-ADR-002` Tiers 0/1/3):** Ship Layer 1 `SerializableChunkData` direct-section SPI, unified cold `ChunkFrame` + bounded AIMD `SparseDirtyCellMap` CoW splicing (`leafanvil-ADR-006`), and cache-line-isolated async read routing.
3. **Stage 3 (Network SPI — `LCAS` Halo, `LCA_NET_V1`, `ODCT`/`MTCF` & 3D Occlusion):** Ship Layer 1 `PlayerChunkSender` SPI, wire `LeafShard` `LCAS` halo deltas and MIT `LeafAnvil-Net` negotiated client streaming (with proxy-safe stored-Zlib and optional `leafanvil-ADR-007` occlusion/culling).
4. **Stage 4 (Profile-Gated Native Cold `PalettedContainer` Backing):** Evaluate steady-state old-gen heap on 2–8 GB floor machines after Stages 1–3; if `PalettedContainer` heap remains a top bottleneck, back unmodified non-uniform sections natively with `.lca` slices while proving zero regression on `BlockCountingChunkSection` and redstone/mob tick MSPT.

### 4. Mandatory Verification & Non-Regression Gates

1. **Bit-Exact Lossless Round-Trip (`L_inf = 0`):** Every `.mca -> .lca -> .mca` and live-save round-trip must preserve 100% of block states, biomes, Starlight `BLOCKLIGHT_STATE_TAG`/`SKYLIGHT_STATE_TAG`, Folia `redstoneGameTime` scheduled ticks, block entities, entities, structures, and CraftBukkit `persistentDataContainer` (including `DataVersion` upgrades and custom datapack namespaces).
2. **Safety & Concurrency Invariants (`S-002`, `S-004`, `S-005`):** Zero synchronous chunk/disk I/O on Folia region tick threads; deterministic unload fencing (`UNLOADED_BIT` + `EpochCommitFence`) with zero leaked `FileChannel` handles or `DirtyChunkLruCache` entries; fail-closed `CBIT T1–T8` validation with `SEC-DED` recovery tests.
3. **Performance & Mixed-Client Gates:** Verify single-pass `Mode A` saves stay faster than `.mca` Zlib (`anvil-api-ADR-005`), verify zero vanilla redstone/farm timing regressions, and verify simultaneous `LCA_NET_V1` + unmodified vanilla clients (both direct and behind Velocity) with zero protocol errors or anti-xray bypasses.

### 5. Future Improvements (Post-Mode-A Centralization)

1. **Zero-Decode GPU DMA → Unified Mode A `.lca` Format (`goldleaf-ADR-017` Phase D):** Emit Mode A `SectionFrame`s (plus precomputed section summary counts) directly from OpenCL kernels into pinned DMA buffers so the host skips any secondary `hostApply` decode/unpack pass between GPU DMA, live memory, and disk.
2. **`8 B`-Aligned `SectionFrame`s + `4 KiB`-Aligned Spatial Chunk Clustering (`2×2` / `4×4`):** Word-aligning `SectionFrame`s inside `ChunkFrame` enables zero-copy `VarHandle`/`MemorySegment` slicing, while clustering adjacent chunks into `4 KiB`-aligned extents aligns NVMe/OS page-cache reads without paying `.mca`'s `4 KiB` per-chunk internal padding waste.
3. **Precomputed `16 B` Per-Section Summary Counts (`nonEmpty`, `fluid`, `tickingCount`, `specialColliding`):** Storing precomputed `LevelChunkSection` counts in `.lca` eliminates the remaining inner 4,096-voxel decode pass on warm reload so outer-ring `FULL` chunks stay pure `COLD_LCA` (`0` voxel decode on load) until promoted to `ENTITY_TICKING`.
4. **Optional Per-Section `dictPatternArena`-Only Framing (Zero LZ Backreferences):** If an extra `~10%` disk/cold-RAM reduction is ever desired without breaking $O(1)$ per-section random access or introducing LZ backreferences, frame dictionary pattern references strictly per-section against the cached Layer 2 region dictionary.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| **Stay on Vanilla `.mca` (`Zlib` + `4 KiB` sectors)** | Retains `~31%` sector padding, `4.20 MB -> 18.69 MB` EOF spill bloat, `CompoundTag`/`NbtIo` GC churn during pre-gen, zero bit-rot recovery, and no unified disk-memory-wire representation for `LeafShard`. |
| **Adopt `.linear` (Kaiiju / Luminol)** | Whole-region `Zstd` streams impose `26.4 ms` cold single-chunk read amplification, `49–206 ms` (`405×`) single-chunk write amplification, and high per-region RAM residency—directly hurting 4–8 core / 8 GB floor hosts and region-threaded saves. |
| **Keep `LeafAnvil` as an external `-javaagent` / `Unsafe` bytecode-interceptor plugin on GoldLeaf** | Unnecessary fragility when GoldLeaf owns the `26.3.x` fork; thin Layer 1 SPIs in `ChunkSystemRegionFileStorage`, `SerializableChunkData`, and `PlayerChunkSender` are cleaner, faster, and upstream-mergeable. |
| **Enable Eikonal light residuals, `SEC_EXT_GRAY_BITPLANES`, or range coding by default on live saves** | Empirically refuted by `anvil-api-ADR-005` and `anvil-api-ADR-006`: range coding causes `1.7×–13.9×` decode regressions, Eikonal BFS adds `65.76 µs/chunk` on decode, and Gray bitplanes add `+48.9%` trial-encode overhead on natural terrain. Keep them opt-in for archival/schematic compaction. |
| **Require `LeafAnvil-Net` on all connecting clients (`require-lca-client: true` default)** | Creates an adoption barrier for public servers; keeping `LCA_NET_V1` strictly negotiated with seamless vanilla fallback maximizes server adoption while still delivering immediate disk, memory, and `LeafShard` wins with zero client mod. |

## Consequences

- **Positive:**
  - Unifies disk (`r.X.Z.lca`), cold server memory (`ChunkFrame` + `SparseDirtyCellMap`), async read shortcuts (`goldleaf-ADR-002`), inter-node `LeafShard` halos (`LCAS`), and negotiated client streaming (`LCA_NET_V1`) around one MIT-specified, `Zero Zstd`, `SEC-DED`-protected binary format.
  - Eliminates `.mca` I/O stalls and `CompoundTag`/`NbtIo` GC churn during chunk generation (`goldleaf-ADR-004` Stage 1 prerequisite) and autosaves while staying faster than `.mca` Zlib on warmed single-pass saves.
  - Preserves zero lock-in via lossless MIT `.lca <-> .mca` conversion and full vanilla client compatibility.
  - Keeps `folia-server` patches razor-thin (`3–4` generic Layer 1 SPIs) and upstream-mergeable to `PaperMC/Folia` and `PaperMC/Paper`.
- **Negative / Trade-offs:**
  - Stage 2's zero-NBT bypass must track Mojang's `SerializableChunkData` and Starlight light-state metadata across `26.x` drops (mitigated by falling back cleanly to NBT whenever `chunkDataVersion != currentDataVersion`).
  - Stages 1–3 do not by themselves shrink live `PalettedContainer` old-gen heap (only allocation/GC rate and open-region file-header RAM); steady-state heap reduction depends on Stage 4's profile-gated cold `PalettedContainer` backing.
  - Negotiated client bandwidth (`65%–78%` / `95%–99%` revisit) and 3D occlusion anti-xray wins require clients to install the MIT `LeafAnvil-Net` mod (though disk, zero-NBT, Tier 0/1/3 reads, and `LeafShard` `LCAS` halos work with 100% unmodified vanilla clients).

## References

- Materialized Folia `26.3.x` seams in `/home/leaf26/GoldLeaf`:
  - `folia-server/src/minecraft/java/ca/spottedleaf/moonrise/patches/chunk_system/io/ChunkSystemRegionFileStorage.java`
  - `folia-server/src/minecraft/java/ca/spottedleaf/moonrise/patches/chunk_system/io/datacontroller/{ChunkDataController,EntityDataController,PoiDataController}.java`
  - `folia-server/src/minecraft/java/net/minecraft/world/level/chunk/storage/RegionFileStorage.java`
  - `folia-server/src/minecraft/java/net/minecraft/world/level/chunk/storage/SerializableChunkData.java`
  - `folia-server/src/minecraft/java/net/minecraft/world/level/chunk/LevelChunkSection.java`
  - `folia-server/src/minecraft/java/net/minecraft/world/level/chunk/PalettedContainer.java`
  - `folia-server/src/minecraft/java/net/minecraft/server/network/PlayerChunkSender.java`
- `.lca` format specifications & empirical benchmarks (`api/anvil-api`, MIT):
  - `api/anvil-api/docs/ICD.md` (`LCA-ICD-3.0`, `formatVersion = 0x0003`)
  - `api/anvil-api/docs/adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md`
  - `api/anvil-api/docs/adr/anvil-api-ADR-004-overlaid-eikonal-light-columnar-residuals-and-adaptive-rank-exclusion.md`
  - `api/anvil-api/docs/adr/anvil-api-ADR-005-performance-gated-entropy-and-prediction-carryovers.md`
  - `api/anvil-api/docs/adr/anvil-api-ADR-006-factored-layer2-pure-id-palettes-and-local-gray-bitplanes.md`
- Memory, network, and sharding carrier ADRs (`resource/LeafAnvil`, `resource/LeafShard`):
  - `resource/LeafAnvil/docs/adr/leafanvil-ADR-001-Multi-Loader-lca-world-storage-and-bytecode-interceptor.md`
  - `resource/LeafAnvil/docs/adr/leafanvil-ADR-002-in-memory-lca-chunk-representation-and-dirty-overlay-compaction.md`
  - `resource/LeafAnvil/docs/adr/leafanvil-ADR-003-negotiated-lca-chunk-network-streaming-and-delta-sync.md`
  - `resource/LeafAnvil/docs/adr/leafanvil-ADR-005-dual-mode-javaagent-direct-section-bridge-and-netty-wire-pipeline.md`
  - `resource/LeafAnvil/docs/adr/leafanvil-ADR-006-aimd-edit-scored-3d-chunk-slab-lru-cache.md`
  - `resource/LeafAnvil/docs/adr/leafanvil-ADR-007-progressive-3d-subterranean-occlusion-streaming-and-exposure-culling.md`
- Related `GoldLeaf` ADRs:
  - `GoldLeaf/docs/adr/goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-002-sub-tick-interrupt-scheduling-and-latency-tiered-routing.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-003-gradient-modeled-region-threading-and-small-machine-overhead-elimination.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-004-worldgen-patching-boundary-and-gpu-offload-spi.md`
