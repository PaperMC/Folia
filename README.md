<div align="center">
  <img src="./docs/assets/GoldLeaf_icon.svg" alt="GoldLeaf Emblem" width="128" height="128">
  <h1>GoldLeaf</h1>
  <p><strong>A vanilla-faithful Folia <code>26.3+</code> (JDK 25) server fork engineered for technical survival worlds — from 4-core surplus workstations to multi-node clusters.</strong></p>
  <p>
    <a href="https://leafcraft.net/mc/goldleaf/">Website</a> &middot;
    <a href="./docs/OVERVIEW.md">Overview &amp; Design Guide</a> &middot;
    <a href="./docs/adr/README.md">Architecture ADRs (001&ndash;017)</a> &middot;
    <a href="./REGION_LOGIC.md">Folia Region Logic</a>
  </p>
</div>

---

## What Is GoldLeaf?

**GoldLeaf** is an open-source fork of [PaperMC/Folia](https://github.com/PaperMC/Folia) baselined on unobfuscated **Minecraft `26.3+`** and **Java 25**. It keeps Folia's regionized multithreading intact while adding a razor-thin Service Provider Interface (SPI) layer for modern world storage (`.lca`), workstation-aware thread auto-tuning, fast Project Leyden AOT restarts, operational diagnostics, and multi-node sharding seams (`LeafShard`).

> **Core Invariant:** *Worst case for a technical redstoner is not worst case for everyone else.*

---

## Why GoldLeaf Exists

1. **Single-threaded Paper forks force an unfair trade.** Either one heavy storage hall or perimeter farm drags down TPS for the entire server, or admins turn on global degradation toggles (activation ranges, DAB, async hacks) that silently break vanilla redstone and farm mechanics.
2. **Stock Folia leaves smaller hardware behind.** By default, Folia collapses any machine with `<= 8` cores down to `1` tick thread—leaving 80%+ of a secondhand 4–8 core workstation idle—and still pays full vanilla `.mca` 4 KiB sector fragmentation and `CompoundTag`/`NbtIo` save stalls.
3. **Worlds shouldn't be locked into proprietary formats.** Admins rightly distrust custom region formats that are hard to audit or impossible to convert back to vanilla `.mca`.

GoldLeaf solves all three structurally—targeting both ends of the hardware barbell (**4–8 core surplus workstations** and **500–2,000+ player multi-node worlds**) with **zero gameplay-breaking defaults**.

---

## What GoldLeaf Does

| Capability | Summary |
| :--- | :--- |
| **Workstation-Aware Region Threading** | Detects the host's physical core envelope and unlocks multi-region tick parallelism on 4–8+ core machines automatically while reserving headroom for Netty, chunk I/O, and GC. |
| **Drop-In `.mca` Default + Opt-In `.lca` Storage** | Defaults to stock `.mca` (`auto-migrate-mca: false`) so dropping the JAR in place never mutates existing worlds. Opting into `LCA-ICD-3.0` delivers `~50%` smaller disk footprint, `~15.5x` faster shutdown saves, and 4-tier `SEC-DED` self-healing—backed by an MIT bidirectional `.lca <-> .mca` CLI with `--dry-run` and `--verify`. |
| **2.88x Faster Warm Boots (Leyden AOT)** | Verified on OpenJDK 25 (`GoldLeaf 26.3`): direct Paperclip-order classpath launch with `-XX:AOTCache` reduces warm startup from `6.83 s` to `2.36 s` (`Done 2.04 s`) with zero NMS patches, paired with per-phase boot/shutdown waterfall telemetry and bounded off-thread plugin JAR/YAML prefetch. |
| **Vanilla Technical Parity** | Zero default-on mechanics tweaks. Preserves Paper's `unsupported-settings` verbatim and provides opt-in, region-safe technical client protocol SPIs (`Servux`, `Syncmatica`, `Jade`, `AppleSkin`) and fake-player/bot guardrails. |
| **Honest Folia Diagnostics & Hardening** | Off-thread, zero-classloading plugin safety preflight flags Folia-incompatible plugins at boot; startup hardware advisor warns of config conflicts without silently editing your YAML; adaptive per-player chunk-gen backpressure and decode-time packet/NBT guards harden public servers. |
| **Thin-SPI, Upstream-First Patch Stack** | Layer 1 generic SPIs use standard Paper/Folia/Moonrise packages so generic fixes can be submitted upstream; Layer 2 wires `config/goldleaf.yml` and external engines (`LeafAnvil`, `LeafShard`, `LeafGen`). |

For a full plain-English walkthrough and comparison against Paper, Purpur/LeafMC, and stock Folia, read **[`docs/OVERVIEW.md`](./docs/OVERVIEW.md)**.

---

## Configuration (`config/goldleaf.yml`)

`config/goldleaf.yml` governs **hardware envelope, storage, diagnostics, network offload, and opt-in SPIs**—never gameplay mechanics. Invalid settings fail closed on startup rather than silently falling back.

- **`threading`** — Workstation-aware tick-thread auto-tuning (`auto-tune-workstation: true`, optional min/max bounds).
- **`diagnostics`** — Startup hardware summary, boot/shutdown telemetry, chunk telemetry SPI, and off-thread plugin safety preflight.
- **`storage`** — Region storage format (`mca` default, opt-in `lca`), migration backup guards, and `LCA-ICD-3.0` codec options.
- **`network`** — Negotiated `LCA_NET_V1` wire-delta chunk streaming and `LeafShard` Tier 4 RPC hooks.
- **`worldgen`** — Layer 1 batch-dispatch SPI, wave coalescer, and worker pacing (vanilla-exact by default).
- **`protocols`** — Opt-in region-safe `Servux`, `Syncmatica`, `Jade`, and `AppleSkin` SPIs (all `false` by default).
- **`anti-exploit`** — Per-player chunk-gen backpressure and decode-time packet/NBT hardening.
- **`bots`** — Opt-in region-safe fake player / bot SPI guardrails (`false` by default).

---

## Plugin Compatibility

GoldLeaf inherits Folia's regionized execution model: **there is no single global main thread**. Plugins must explicitly support Folia (`folia-supported: true` and region/entity-safe scheduling). Rather than pretending incompatible plugins are safe, GoldLeaf's boot-time preflight inspects plugin archives off-thread and reports threading hazards clearly.

---

## Building from Source

Requires **JDK 25**.

```bash
# Apply upstream Paper/Folia and GoldLeaf patches
./gradlew applyAllPatches

# Build the runnable Paperclip bundler JAR
./gradlew createMojmapBundlerJar
```

For architecture decisions, benchmark tables, and SPI contracts, browse **[`docs/adr/README.md`](./docs/adr/README.md)**.

---

## Upstream Heritage & Licensing

GoldLeaf stands on the shoulders of [**PaperMC/Folia**](https://github.com/PaperMC/Folia) and [**PaperMC/Paper**](https://github.com/PaperMC/Paper).

- **GoldLeaf server patches & patcher:** Licensed under **GPLv3** (see [`PATCHES-LICENSE`](./PATCHES-LICENSE)).
- **`.lca` binary specification & `.lca <-> .mca` migration CLI:** Licensed under **MIT** (`api/anvil-api`) for permanent world portability.
