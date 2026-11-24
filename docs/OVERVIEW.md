# GoldLeaf — What It Is, What It Does, and Why It Exists

**GoldLeaf** is an open-source **Folia `26.3+` (JDK 25)** multithreaded Minecraft server fork maintained by LeafCraft. It is built for technical survival servers and high-concurrency worlds that want **regionized multi-core scaling, modern world storage, and fast restarts without sacrificing vanilla redstone or farm mechanics**.

---

## 1. The Problem GoldLeaf Solves

Most Minecraft server forks ask administrators to make an uncomfortable trade:

- **Single-threaded Paper forks** (Paper, Purpur, LeafMC) run the entire world on one main tick loop. When a technical player builds a large storage hall, quarry, or perimeter farm, that one district drags down TPS for every other player on the server. To hide the lag, many forks ship global degradation toggles—aggressive entity activation ranges, Dynamic Activation of Brain (DAB), or async hacks on shared state—that silently break vanilla redstone timings, hopper chains, and farm mechanics.
- **Stock Folia** solves the single-thread bottleneck by splitting loaded chunks into independent regions that tick in parallel. However, stock Folia assumes a large datacenter CPU: by default, any machine with `<= 8` cores collapses to **1 tick thread** unless manually tuned. It also inherits vanilla `.mca` 4 KiB sector storage overhead, slow `CompoundTag`/`NbtIo` save stalls during chunk generation, and sparse operational diagnostics when plugins misbehave across region threads.
- **Clean-room rewrites** (Rust/C++ server clones) reduce idle RAM, but they abandon the Bukkit/Paper/Folia plugin ecosystem and years of subtle vanilla technical mechanics that survival communities depend on.

GoldLeaf is built around one architectural rule:

> **Worst case for a technical redstoner is not worst case for everyone else.**

Instead of throttling gameplay to protect a single tick thread, GoldLeaf uses Folia's regionized execution to isolate heavy contraptions to their own region—and removes the storage, threading, boot, and diagnostic bottlenecks that hold Folia back.

---

## 2. The Barbell Hardware Model

Rather than competing for the middle-of-the-road single-VPS Paper market, GoldLeaf targets both ends of the hardware spectrum with one unified architecture:

| Tier | Typical Hardware | What Breaks Today | How GoldLeaf Fixes It |
| :--- | :--- | :--- | :--- |
| **Floor: Surplus & Secondhand Hardware** | 4–8 core ex-office PCs and workstations (`i5-8500T`, `i7-9700`, Xeon/Precision towers, 8–16 GB RAM, NVMe or HDD) | Weak single-core IPC chokes single-threaded Paper while leaving 80%+ of CPU cores idle; stock Folia collapses `<= 8` cores to `1` tick thread by default. | Hardware-envelope auto-tuning unlocks multi-region parallelism out of the box while reserving headroom for Netty, chunk I/O, and GC. |
| **Ceiling: Multi-Node Mega-Servers** | 500–2,000+ players across one seamless world on multiple physical nodes | Single-machine ceiling forces servers into hard-walled proxy lobbies or closed-source sharding stacks. | First-class `LeafShard` region-lease fencing, 4-byte `LCAS` halo deltas, and `.lca` region handoffs let one world span multiple machines. |

Both ends of the barbell suffer from the exact same four bottlenecks: **storage/save stalls**, **heap density**, **multi-core/multi-node scaling**, and **worldgen bursts**. GoldLeaf addresses all four structurally—never by weakening vanilla mechanics.

---

## 3. How GoldLeaf Compares

| Capability | Paper | Purpur / LeafMC | Stock Folia | GoldLeaf |
| :--- | :--- | :--- | :--- | :--- |
| **Regionized multi-core ticking** | No (1 main thread) | No (1 main thread) | Yes (`<= 8` cores defaults to 1 thread) | **Yes (auto-tuned for 4–8+ cores)** |
| **Vanilla redstone & farm parity by default** | Yes | Often relaxed by popular tuning presets | Yes | **Strict invariant (zero gameplay-breaking defaults)** |
| **Native `.lca` world storage (opt-in)** | No (`.mca` only) | No (`.mca` / third-party `.linear`) | No (`.mca` only) | **Yes (`~50%` smaller, `~15.5x` faster saves, MIT `.mca` round-trip)** |
| **Warm boot via JDK 25 Leyden AOT** | Manual | Manual | Manual | **First-class workflow (`6.83 s` -> `2.36 s`, `2.88x` faster)** |
| **Boot-time Folia plugin safety preflight** | N/A | N/A | Manual (`folia-supported` flag only) | **Zero-classload bytecode preflight + runtime hazard SPI** |
| **Patch stack footprint on MC `26.3+`** | Baseline | Hundreds of gameplay & tweak patches | Thin (`22` patches) | **Thin SPIs layered cleanly over Folia** |

---

## 4. The Six Pillars of GoldLeaf

### Pillar 1: Vanilla-Faithful Performance & Auto-Tuned Threading
- **Zero gameplay-breaking defaults:** GoldLeaf never ships default-on optimizations that alter redstone update order, hopper/piston timings, or mob farm behavior (`goldleaf-ADR-008`, `goldleaf-ADR-013`).
- **Paper `unsupported-settings` preserved:** Technical SMP toggles in `paper-global.yml` (`allow-piston-duplication`, `allow-permanent-block-break-exploits`, `allow-headless-pistons`, etc.) remain untouched with identical semantics (`goldleaf-ADR-014`).
- **Workstation-aware threading (`goldleaf-ADR-003`):** When `threaded-regions.threads <= 0`, `TickThreadAutoTuner` inspects the host's logical core envelope and allocates multi-region tick threads safely instead of collapsing 4–8 core machines to a single thread.

### Pillar 2: Native `.lca` World Storage with Zero Lock-In
- **Native `.lca` default with non-destructive `.mca` auto-detection:** `storage.format` defaults to `"lca"` (`storage.lca-mode = "mode-b"`) for new worlds while automatically detecting and preserving existing `.mca` region folders non-destructively when `storage.auto-migrate-mca = false` (`goldleaf-ADR-005`, `goldleaf-ADR-014`). Dropping the GoldLeaf JAR onto an existing server makes **zero** unsolicited changes to your world files.
- **Opt-in `LCA-ICD-3.0` storage (`goldleaf-ADR-005`):** When enabled, `.lca` eliminates `.mca` 4 KiB sector fragmentation and `CompoundTag`/`NbtIo` section churn—cutting disk usage by `~50%`, accelerating shutdown saves by `~15.5x`, adding 4-tier `SEC-DED` self-healing against bit rot, and freeing generator threads from save-queue backpressure.
- **Reversible MIT Migration CLI (`goldleaf-ADR-014`):** A standalone, MIT-licensed `.lca <-> .mca` tool supports `--dry-run` (exact savings and health report with zero writes) and `--verify` (full chunk-by-chunk semantic round-trip verification) so you can return to vanilla `.mca` at any time.

### Pillar 3: Fast Restarts & Honest Operational Diagnostics
- **2.88x faster warm boots (`goldleaf-ADR-016`):** Measured on OpenJDK 25 (`GoldLeaf 26.3`), launching via the direct Paperclip-order classpath with Project Leyden `-XX:AOTCache` cuts warm startup from **`6.83 s` to `2.36 s`** (`-65.5%`, `Done 2.04 s`) with zero NMS patches.
- **Boot/shutdown waterfall telemetry:** `BootShutdownTelemetry` tracks per-phase startup and `/stop` drain durations, highlighting slow plugins (`> 250 ms`) and lingering non-daemon threads that stall JVM exit.
- **Bounded plugin I/O prefetch:** Pre-faults `plugins/*.jar` and YAML files into the OS page cache off-thread before single-threaded plugin enablement—preserving exact Bukkit/Folia lifecycle semantics.
- **Region-context watchdog & startup advisor (`goldleaf-ADR-011`, `goldleaf-ADR-014`):** Emits plain-language startup advisories when `paper-global.yml` / `spigot.yml` conflict with hardware limits or farm parity, without ever silently rewriting your configs.

### Pillar 4: Thin-SPI Architecture & Upstream-First Discipline
- GoldLeaf targets **unobfuscated Minecraft `26.3+` on JDK 25**, eliminating legacy remapping overhead.
- Patches are strictly separated into **Layer 1** (upstream-mergeable, generic SPIs using standard Paper/Folia/Moonrise packages with zero GoldLeaf branding) and **Layer 2** (GoldLeaf branding, `goldleaf.yml`, and engine wiring).
- Keeping the patch stack razor-thin (~4–5 clean SPI seams rather than 200+ invasive patches) keeps rebasing across Mojang `26.x` drops fast and predictable (`goldleaf-ADR-001`).

### Pillar 5: Technical SMP Protocols, Anti-Exploit & Bot SPI
- **Opt-in technical client protocols (`goldleaf-ADR-010`):** Clean, region-safe SPIs for `Servux`, `Syncmatica`, `Jade`, `AppleSkin`, and map world-ID—all defaulting to `false` until enabled in `config/goldleaf.yml`.
- **Region-safe fake player / bot SPI (`goldleaf-ADR-015`):** Opt-in guardrails for Carpet-class technical SMP workflows and automated testing (`LeafFuzz`).
- **Adaptive anti-exploit hardening (`goldleaf-ADR-012`):** Per-player chunk-generation backpressure and decode-time packet/NBT guards protect tick regions from elytra chunk-flooding and malformed payloads without throttling legitimate builders.
- **Boot-time plugin safety preflight (`goldleaf-ADR-007`):** Scans plugin archives off-thread without classloading them, reporting Folia threading hazards before they corrupt live worlds.

### Pillar 6: Multi-Node Scaling (`LeafShard` Seams)
- Folia's default region grid (`gridExponent = 4`, `16x16` chunks) tiles cleanly into `32x32` `.lca` regions (`gridExponent = 5` maps 1:1).
- GoldLeaf exposes region-lease fencing hooks and dirty-voxel / `LCAS` delta seams so a single continuous survival world can scale horizontally across multiple physical machines via `LeafShard` (`goldleaf-ADR-001`, `goldleaf-ADR-002`).

---

## 5. Configuration Surface (`config/goldleaf.yml`)

Every option in `config/goldleaf.yml` governs **infrastructure, hardware envelope, storage, network offload, or opt-in SPIs**—never gameplay mechanics. Invalid values fail closed at startup rather than silently falling back (`S-004`).

| Section | Purpose | Default Posture |
| :--- | :--- | :--- |
| `threading` | Workstation-aware Folia tick-thread auto-tuning (`auto-tune-workstation`, min/max bounds) | Auto-tune enabled (`true`) |
| `diagnostics` | Hardware-envelope startup summary, boot/shutdown telemetry, chunk telemetry SPI, off-thread plugin safety preflight | Enabled (`fail-closed` off by default) |
| `storage` | Region disk format (`lca` or `mca`), `lca-mode` (`mode-a`/`mode-b`/`archival`), backup guards, and `LCA-ICD-3.0` codec settings | **`lca`** (non-destructive `.mca` auto-detect), `auto-migrate-mca: false` |
| `network` | Negotiated `LCA_NET_V1` wire-delta chunk streaming and `LeafShard` Tier 4 RPC hooks | Opt-in / negotiated |
| `worldgen` | Layer 1 worldgen batch-dispatch SPI, wave coalescer, and worker pacing | Vanilla-exact default |
| `protocols` | Region-safe technical client protocols (`Servux`, `Syncmatica`, `Jade`, `AppleSkin`) | All `false` (opt-in) |
| `anti-exploit` | Per-player chunk-gen backpressure and decode-time packet/NBT hardening | Protective defaults |
| `bots` | Region-safe fake player / bot SPI guardrails | Disabled (`false`, opt-in) |

---

## 6. Who Should (and Shouldn't) Use GoldLeaf

**GoldLeaf is a strong fit if you:**
- Run a **technical survival SMP**, skyblock, or open-world server where players spread out and build heavy redstone/farms.
- Host on a **4–8 core surplus workstation or mini-PC** and want real multi-core scaling without buying a high-frequency single-core flagship CPU.
- Plan to scale a **single continuous world** across multiple machines or want **`.lca` disk/save performance** with provable `.mca` reversibility.
- Want **fast `26.x` updates** and **honest diagnostics** instead of opaque "magic performance" YAML tweaks.

**GoldLeaf is probably not the right fit if you:**
- Depend on legacy **Paper/Spigot plugins that do not support Folia** (GoldLeaf will not fake single-thread compatibility; it reports incompatible plugins honestly at boot).
- Need **Minecraft `< 26.3`** (GoldLeaf is baselined exclusively on unobfuscated `26.3+` and JDK 25).
- Prefer a single-threaded server with Purpur-style gameplay customization toggles.

---

## 7. Architecture Decision Records

Every subsystem in GoldLeaf is documented in a numbered Architecture Decision Record under [`docs/adr/`](adr/README.md):

- [`goldleaf-ADR-001`](adr/goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md) — Thin-SPI fork thesis, barbell model, and `.lca` / `LeafShard` world-engine plan
- [`goldleaf-ADR-002`](adr/goldleaf-ADR-002-sub-tick-interrupt-scheduling-and-latency-tiered-routing.md) — Sub-tick interrupt scheduling and latency-tiered routing
- [`goldleaf-ADR-003`](adr/goldleaf-ADR-003-gradient-modeled-region-threading-and-small-machine-overhead-elimination.md) — Small-machine tick-thread scaling curve & region allocation reuse
- [`goldleaf-ADR-004`](adr/goldleaf-ADR-004-worldgen-patching-boundary-and-gpu-offload-spi.md) — Worldgen patching boundary and GPU offload SPI
- [`goldleaf-ADR-005`](adr/goldleaf-ADR-005-lca-format-adoption-across-disk-memory-and-network.md) — Native `.lca` (`LCA-ICD-3.0`) adoption across disk, memory, and network
- [`goldleaf-ADR-006`](adr/goldleaf-ADR-006-native-chunk-telemetry-and-execution-mask-spi.md) — Native chunk telemetry and `ChunkExecutionMask` SPI
- [`goldleaf-ADR-007`](adr/goldleaf-ADR-007-plugin-safety-preflight-and-runtime-hazard-spi.md) — Off-thread plugin safety preflight and runtime hazard SPI
- [`goldleaf-ADR-008`](adr/goldleaf-ADR-008-mob-ai-optimization-boundary-and-farm-parity-gate.md) — Deferred Mob AI boundary and technical-farm parity gate
- [`goldleaf-ADR-009`](adr/goldleaf-ADR-009-entity-tracker-scaling-off-tick-packet-encoding-and-lca-anti-xray-spi.md) — Entity tracker scaling, off-tick packet encoding, and `.lca` anti-xray SPI
- [`goldleaf-ADR-010`](adr/goldleaf-ADR-010-region-safe-technical-client-protocol-spi.md) — Region-safe technical client protocol SPI
- [`goldleaf-ADR-011`](adr/goldleaf-ADR-011-folia-operational-ergonomics-and-region-context-watchdog-diagnostics.md) — Folia operational ergonomics and region-context watchdog diagnostics
- [`goldleaf-ADR-012`](adr/goldleaf-ADR-012-per-player-chunk-gen-backpressure-and-decode-time-exploit-hardening.md) — Per-player chunk-gen backpressure and decode-time exploit hardening
- [`goldleaf-ADR-013`](adr/goldleaf-ADR-013-vanilla-faithful-micro-optimization-boundary-and-section-epoch-reuse.md) — Vanilla-faithful micro-optimization boundary and `sectionEpoch` reuse
- [`goldleaf-ADR-014`](adr/goldleaf-ADR-014-zero-friction-migration-cli-honest-auto-tuner-and-technical-parity-surface.md) — Zero-friction `.lca <-> .mca` migration CLI, honest auto-tuner, and technical parity
- [`goldleaf-ADR-015`](adr/goldleaf-ADR-015-region-safe-fake-player-and-bot-spi.md) — Region-safe fake player / bot SPI
- [`goldleaf-ADR-016`](adr/goldleaf-ADR-016-boot-shutdown-telemetry-leyden-aot-and-plugin-warmup.md) — Boot/shutdown telemetry, Leyden AOT launch, and plugin warmup
- [`goldleaf-ADR-017`](adr/goldleaf-ADR-017-typed-ast-ir-opencl-worldgen-pipeline-and-throughput-envelope.md) — Typed-AST-to-IR OpenCL worldgen pipeline and throughput envelope
- [`goldleaf-ADR-018`](adr/goldleaf-ADR-018-opt-in-chat-signing-disable-secure-seed-and-offline-plugin-audit-cli.md) — Zero-boot plugin audit CLI, opt-in chat signing disable, and 1024-bit secure seed seams
- [`goldleaf-ADR-019`](adr/goldleaf-ADR-019-reproducible-multi-fork-benchmark-harness-and-vanilla-parity-gate.md) — Reproducible multi-fork benchmark harness and automated vanilla-parity gate

---

## 8. Licensing & Upstream Credit

- **GoldLeaf** is a fork of [**PaperMC/Folia**](https://github.com/PaperMC/Folia) (and [**PaperMC/Paper**](https://github.com/PaperMC/Paper)) distributed under **GPLv3** as a binary-diff patcher—never redistributing Mojang's server JAR.
- The **`.lca` binary specification** (`api/anvil-api`) and **`.lca <-> .mca` round-trip converter** are **MIT-licensed** so server worlds always remain portable and lock-in free.
