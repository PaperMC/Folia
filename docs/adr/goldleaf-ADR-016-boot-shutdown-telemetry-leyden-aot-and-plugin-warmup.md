# goldleaf-ADR-016 — Boot/Shutdown Telemetry, Leyden AOT Direct-Classpath Launch, and Bounded Plugin I/O Warmup

**Status:** Accepted
**Date:** 2026-11-21

## Context

Cold-start, warm-restart, and `/stop` drain times directly govern server downtime during deployments, plugin updates, and crash recovery. Earlier exploratory design work lived in a standalone, unimplemented `:resource:LeafBoot` concept (`leafboot-ADR-001` through `leafboot-ADR-004`) targeting a `-javaagent` across Paper, Folia, Fabric, and NeoForge.

That standalone product has been retired and removed (`resource/LeafBoot/` deleted) in favor of folding the high-ROI pieces directly into GoldLeaf for three architectural reasons:

1. **Zero `-javaagent` / bytecode fragility:** GoldLeaf already owns `Main.java`, `DedicatedServer.java`, `MinecraftServer.java`, and `Commands.java`. Thin 1–5 line delegation hooks into `dev.leafcraft.goldleaf.*` are simpler, safer, and far easier to maintain than bytecode interception across four loaders.
2. **Unobfuscated `26.3+` + JDK 25 baseline:** Minecraft `26.3+` eliminates Mojang-to-Spigot remapping for modern Paper plugins, and GoldLeaf's JDK 25 runtime natively supports Project Leyden `-XX:AOTCacheOutput` / `-XX:AOTCache` (`JEP 483` / `JEP 514` / `JEP 515`) without a custom agent.
3. **Profile-first rebase discipline (`goldleaf-ADR-001`, `goldleaf-ADR-013`):** Only optimizations backed by measured wall-clock evidence should justify `folia-server` patches.

### Verified Baseline Measurements (`GoldLeaf 26.3`, OpenJDK `25.0.4.1`)

Measured on `folia-bundler-26.3.local-SNAPSHOT.jar` (`dc072e8`, `-Xms2G -Xmx2G`, 24 logical cores, `online-mode=false`, `view-distance=6`, `simulation-distance=6`, `seed=42`, empty `plugins/`):

| Configuration | Wall-clock to `Done!` | Server `Done (...)` | `0 -> [bootstrap]` | `[bootstrap] -> Environment` | `Environment -> Loaded 1866 advancements` | `Starting MC -> Preparing level` | `Preparing level -> Done!` | `/stop` drain | AOT Cache Size |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Cold fresh world (bundler, no AOT) | `9,046.5 ms` | `8.101 s` | `1,575 ms` | `3,249 ms` | `1,223 ms` | `616 ms` | `2,293 ms` | `841 ms` | — |
| Warm world (bundler, no AOT, run 1 + JFR) | `6,740.4 ms` | `6.102 s` | `1,241 ms` | `3,131 ms` | `1,222 ms` | `691 ms` | `362 ms` | `1,052 ms` | — |
| Warm world (bundler, no AOT, run 2) | `6,924.3 ms` | `6.422 s` | `1,234 ms` | `3,324 ms` | `1,244 ms` | `663 ms` | `370 ms` | `938 ms` | — |
| Warm world (bundler + `-XX:AOTCache`, run 1) | `4,175.9 ms` | `3.746 s` | `851 ms` | `1,784 ms` | `874 ms` | `403 ms` | `208 ms` | `~700 ms` | `149.9 MiB` |
| Warm world (bundler + `-XX:AOTCache`, run 2) | `3,995.2 ms` | `3.567 s` | `855 ms` | `1,694 ms` | `821 ms` | `382 ms` | `190 ms` | `~700 ms` | `149.9 MiB` |
| Warm world (direct Paperclip-order `-cp`, no AOT) | `6,128.3 ms` | `6.001 s` | `749 ms` | `3,122 ms` | `1,118 ms` | `614 ms` | `441 ms` | `1,018 ms` | — |
| Warm world (direct Paperclip-order `-cp` + `-XX:AOTCache`, run 1) | `2,381.4 ms` | `2.054 s` | `578 ms` | `700 ms` | `622 ms` | `298 ms` | `142 ms` | `740 ms` | `181.4 MiB` |
| Warm world (direct Paperclip-order `-cp` + `-XX:AOTCache`, run 2) | `2,356.0 ms` | `2.037 s` | `571 ms` | `687 ms` | `572 ms` | `321 ms` | `165 ms` | `657 ms` | `181.4 MiB` |

*(Training overhead: one-time `-XX:AOTCacheOutput` run took `8.60 s` to `Done!` + `11.18 s` AOT assembly on bundler, or `7.35 s` to `Done!` + `12.03 s` AOT assembly on direct `-cp`.)*

### Key Empirical Findings

1. **Direct-classpath Leyden AOTCache delivers a `2.88×` warm-boot speedup (`6.83 s` -> `2.36 s`, `-65.5%`) with zero NMS patches.** Launching the bundler JAR directly with `-XX:AOTCache` only achieves `4.00–4.18 s` (`-40%`) because Paperclip's custom `URLClassLoader` prevents JDK 25 Leyden from AOT-linking the unpacked `versions/` and `107` `libraries/` JARs on the system classpath, and Paperclip re-hashes bundled JARs on every launch (`~490 ms` before `[bootstrap]`). Launching `org.bukkit.craftbukkit.Main` with the exact `META-INF/versions.list` + `META-INF/libraries.list` classpath order unlocks full AOT class linking and drops `[bootstrap] -> Environment` (`Bootstrap` + `DataFixers` + Moonrise `CollisionUtil`) from **`3.23 s` to `0.69 s` (`4.70×` faster)**.
2. **JFR (`warm_no_aot.jfr`) confirms where CPU actually goes on warm boot:** `DirectMethodHandle$Holder.invokeStatic` (`12.5%`) + `DirectMethodHandle.allocateInstance` (`4.6%`) from `DataFixerUpper` / `Codec` / lambda linking, `NoiseStack$Perlin` / `Climate$RTree` / `CubicSpline` (`~19%` combined during spawn/level prep), `CollisionUtil.sliceShapeOptimised` (`2.2%`), and `paperclip.Util.readFully` + `DigestBase.implCompressMultiBlock0` (`1.6%`).
3. **Vanilla datapack compilation drops to `~0.57 s` under direct-CP AOTCache.** Writing fragile NMS binary-snapshot caches for `ReloadableServerResources` (`Codec` / `RegistryOps` / tags / recipes / loot tables) is not justified on vanilla datapacks given its high `26.x` rebase surface.

## Decision

Consolidate GoldLeaf's boot, shutdown, and reconnect-storm strategy into four strictly scoped, profile-gated layers while keeping `folia-server` `.patch` hooks razor-thin (`<= 5` lines per hook site):

1. **Layer A — Zero-Patch Leyden AOT Direct-Classpath Launcher Workflow (Primary Win):**
   - Document and ship a thin launcher helper/script (and optional `-Dpaperclip.patchonly=true` unpack flow) that extracts `versions/` + `libraries/` once per `folia-bundler` SHA-256, constructs the exact `versions.list` + `libraries.list` classpath order, and manages a content-addressed `-XX:AOTCache=<cache-dir>/goldleaf-<sha256>.aot` file.
   - Automatically invalidates and retrains the `.aot` archive only when the bundler JAR digest, JVM version, or major JVM flags change. Never mutates or skips `DataFixerUpper` (`S-004`).

2. **Layer B — Thin-Hook Boot/Shutdown Waterfall & Non-Daemon Thread Watchdog:**
   - Implement `dev.leafcraft.goldleaf.diagnostics.BootShutdownTelemetry` in GoldLeaf with minimal 1–3 line delegation hooks in `Main.java`, `DedicatedServer.java`, `MinecraftServer.java`, and `CraftServer.java`.
   - Record monotonic nanosecond spans for `Bootstrap`, `WorldLoader/DataPacks`, `PluginDiscovery`, per-plugin `onLoad` / `onEnable` / `onDisable`, `LevelLoad`, and `ShutdownDrain`.
   - Emit a structured one-line boot summary and `/stop` drain attribution table highlighting slow plugins (`> 250 ms`) and lingering non-daemon threads that delay JVM exit (`S-004`, `goldleaf-ADR-011`).

3. **Layer C — Bounded Off-Thread Plugin JAR & YAML Page-Cache Prefetch:**
   - Add a single hook before `CraftServer#loadPlugins` / `enablePlugins` (gated by `GoldLeafConfig`, default `true`) that pre-faults `plugins/*.jar` and `plugins/*/*.yml` into the OS page cache on bounded background worker threads (`S-005`).
   - Strictly preserve Bukkit/Folia single-threaded `Plugin#onLoad()` and `Plugin#onEnable()` load-order and registration semantics (`F-001`). Never parallelize plugin lifecycle callbacks.

4. **Layer D — Permission-Bucketed `Commands#sendCommands` Cache (Deferred Until Profiled Under Reconnect Load):**
   - Localized to `Commands.java` only when real multi-player reconnect-storm traces show `Commands#sendCommands` dominating post-`Done!` CPU. Deduplicates serialized `ClientboundCommandsPacket` buffers per effective command-permission bitmask with deterministic invalidation on permission/command-tree mutations.

### Explicitly Deferred / Out of Scope

- **Unconditionally disabling `DataFixerUpper`:** Forbidden — risks silent corruption of legacy chunks, schematics, or offline player `.dat` files (`S-004`).
- **Parallelizing `Plugin#onEnable()`:** Forbidden — violates Bukkit/Folia thread-confinement and dependency-order invariants (`F-001`).
- **Datapack `ReloadableServerResources` binary snapshot cache:** Deferred — direct-CP AOTCache already reduces vanilla datapack loading to `~572 ms`; revisit only if production traces with heavy custom datapacks prove multi-second stalls.
- **`.lbs` CoW slab persistence for `scoreboard.dat` & `playerdata`:** Deferred — if large technical SMPs exhibit `scoreboard.dat` / `playerdata` save stalls, evaluate it under GoldLeaf's storage layer (`goldleaf-ADR-005`) rather than as a separate boot subsystem.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep standalone `:resource:LeafBoot` (`-javaagent` + 4 loaders) | High maintenance and bytecode fragility across Paper/Folia/Fabric/NeoForge, zero sunk implementation code, and half of `leafboot-ADR-001` is obsolete on unobfuscated `26.3+` + JDK 25. |
| Use `-XX:AOTCache` only via `java -jar folia-bundler.jar` | Leaves `~1.7 s` (`4.09 s` vs `2.36 s`) on the table because Paperclip's `URLClassLoader` blocks full Leyden AOT class linking and re-hashes bundled JARs every boot. |
| Port `ReloadableServerResources` binary cache into `folia-server` now | Only `~0.57 s` of vanilla headroom remains after direct-CP AOTCache, while Mojang's `Codec` / registry internals churn heavily across `26.x` drops. |
| Disable `DataFixerUpper` or parallelize `Plugin#onEnable()` | Violates `S-004` data integrity and `F-001` Bukkit/Folia lifecycle threading contracts. |

## Consequences

- **Positive:**
  - Immediate **`2.88×` warm-boot speedup** (`6.83 s` -> `2.36 s`, `Done 2.04 s`) on JDK 25 with zero NMS patch risk.
  - Eliminates an entire unimplemented product surface (`resource/LeafBoot/`) and consolidates boot/shutdown observability into GoldLeaf's existing `dev.leafcraft.goldleaf.diagnostics.*` package.
  - Keeps `folia-server` `.patch` hooks minimal (`<= 5` lines per site) so `26.x` rebases stay cheap (`goldleaf-ADR-001`).
- **Negative / Trade-offs:**
  - Non-GoldLeaf Paper/Fabric/NeoForge servers do not get a standalone `LeafBoot.jar` drop-in.
  - Direct-classpath AOTCache adds `~181 MiB` of disk cache per bundler digest and a one-time `~19.5 s` training boot whenever the server JAR or JVM updates.
  - Server hosts using managed Pterodactyl/Docker startup scripts must opt into the direct-classpath launcher wrapper to get the full `2.36 s` boot instead of the `4.09 s` bundler-AOT boot.

## References

- `GoldLeaf/docs/adr/goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md`
- `GoldLeaf/docs/adr/goldleaf-ADR-005-lca-format-adoption-across-disk-memory-and-network.md`
- `GoldLeaf/docs/adr/goldleaf-ADR-007-plugin-safety-preflight-and-runtime-hazard-spi.md`
- `GoldLeaf/docs/adr/goldleaf-ADR-011-folia-operational-ergonomics-and-region-context-watchdog-diagnostics.md`
- `GoldLeaf/docs/adr/goldleaf-ADR-013-vanilla-faithful-micro-optimization-boundary-and-section-epoch-reuse.md`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/server/Main.java`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/server/dedicated/DedicatedServer.java`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/server/MinecraftServer.java`
