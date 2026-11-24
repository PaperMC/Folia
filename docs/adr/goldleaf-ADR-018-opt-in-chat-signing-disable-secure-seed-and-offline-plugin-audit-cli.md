# goldleaf-ADR-018 — Zero-Boot Plugin Audit CLI, Opt-In Chat Signing Disable, and Deferred 1024-Bit Secure Seed Seams

**Status:** Accepted (v0.1 Scope Implemented)
**Date:** 2026-11-22

## Context

`goldleaf-ADR-013` and `goldleaf-ADR-014` codify GoldLeaf's strict boundary against Paper/Purpur/LeafMC feature creep: GoldLeaf permanently rejects Purpur gameplay novelty toggles, heuristic AI throttling (DAB, villager lobotomizing), and unsafe async world-simulation hacks.

Even after wiring `goldleaf-ADR-001` through `017`, three small, high-adoption operational and privacy seams remain where administrators migrating from Paper, LeafMC, Leaves, or Luminol still face real friction:

1. **Pre-Migration Plugin Compatibility Anxiety:**
   - `goldleaf-ADR-007` runs `BytecodeFoliaPreflightProvider` off-thread during server boot, but operators evaluating a switch from Paper/LeafMC want to audit an existing `plugins/` directory **before** booting a world or touching production files—mirroring the zero-fear offline workflow of `goldleaf-ADR-014`'s `--goldleaf-migrate` CLI.
2. **Fragile Third-Party `NoChatReports` Plugins Across `26.x` Drops:**
   - Disabling Mojang chat signing (`no-chat-reports` / unsigned chat relay) is one of the most frequently enabled non-performance toggles on LeafMC and Leaves. Third-party plugins that implement this rely on NMS packet interception and routinely break on every Minecraft `26.x` drop.
3. **Public SMP Seed Cracking (`SeedCrackerX`):**
   - Public survival and anarchy SMPs on LeafMC/Luminol frequently adopt a 1024-bit cryptographic feature seed (`feature-secure-seed`) to prevent automated biome/structure/ore seed cracking without changing vanilla terrain mechanics, redstone, or mob farms.

All three can be delivered as **strictly opt-in, zero-gameplay-drift, thin Layer 1 seams** (`<= 5` lines per NMS hook site) with fail-closed validation (`S-004`) and zero impact on `26.x` rebase velocity (`goldleaf-ADR-001`).

## Decision

GoldLeaf defines three bounded, opt-in seams—two targeted for initial operator polish and one explicitly deferred post-`v0.1`:

### 1. Zero-Boot Offline Plugin Audit CLI (`--audit-plugins <dir>`)

Extend `goldleaf-ADR-007`'s `BytecodeFoliaPreflightProvider` with a pre-boot CLI entry point dispatched from `Main.java` before `DedicatedServer` or world initialization (mirroring `goldleaf-ADR-014`'s `--goldleaf-migrate` pattern):

1. **Zero-Boot, Zero-Classloading Execution:**
   - `java -jar goldleaf.jar --audit-plugins ./plugins [--format text|json] [--strict]` shall scan all `*.jar` archives in the target directory using `BytecodeFoliaPreflightProvider` without booting a server, touching world files, or loading plugin classes (`S-005`).
2. **Actionable Per-Plugin Verdicts:**
   - Emit per-archive verdicts (`SAFE`, `WARN`, `HAZARD`, `UNREADABLE_ARCHIVE`) listing detected Folia hazards (`Bukkit.getScheduler()`, synchronous tick-thread I/O contagion, unmanaged thread spawns, session collection leaks, and `folia-supported` metadata absence).
3. **Fail-Closed Exit Codes (`S-004`):**
   - Exit `0` when all archives are readable and free of critical hazards; exit non-zero on missing/unreadable target directory, corrupt JARs (`UNREADABLE_ARCHIVE`), or any `HAZARD` verdict when `--strict` is set.

### 2. Opt-In Chat Signing Disable (`security.disable-chat-signing`, Default `false`)

Provide a single opt-in network-layer toggle in `GoldLeafConfig.Security` (`config/goldleaf.yml`), disabled by default so stock vanilla/Paper chat-signing behavior is preserved out of the box:

1. **Thin Layer 1 Packet Seam:**
   - When `security.disable-chat-signing: true`:
     - `ClientboundServerDataPacket` shall advertise `enforcesSecureChat() == false`.
     - `ServerGamePacketListenerImpl` shall skip signature-chain rejection for incoming player chat and strip chat signatures when broadcasting via `ClientboundPlayerChatPacket` (converting signed player chat into unsigned system/chat relay packets).
   - When `security.disable-chat-signing: false` (default), all code paths shall execute vanilla/Paper logic verbatim with zero extra allocations.
2. **Thread & Error Discipline:**
   - Operates strictly on existing Netty / packet-dispatch seams with zero blocking I/O (`S-005`) and fail-closed `GoldLeafConfig#validate()` enforcement (`S-004`).

### 3. Opt-In 1024-Bit Secure Seed (`worldgen.secure-seed.enabled`, Default `false`, Implemented)

Provides a full-spectrum opt-in 1024-bit cryptographic seed seam (`dev.leafcraft.goldleaf.security.GoldLeafSecureSeed`) for public SMPs:

1. **Full-Spectrum Domain-Separated SHA-512 Derivation:**
   - Store a per-world 1024-bit (`long[16]`) cryptographic seed alongside `WorldOptions` (`goldleaf_secure_seed`, generated via `SecureRandom` on new world creation when enabled, and shared across Overworld/Nether/End dimensions with the same 64-bit seed).
   - Derive independent 64-bit/128-bit PRNG seeds via `ThreadLocal` SHA-512 across all five seedcracking vectors: `WorldgenRandom` (`setDecorationSeed`, `setFeatureSeed`, `setLargeFeatureSeed`, `setLargeFeatureWithSalt`, `seedSlimeChunk`), `BiomeManager#obfuscateSeed` (login/respawn packets and `IrOpenClTerrainAccelerator`), `EndSpikeFeature#getSpikesForLevel`, `AbstractSpreadingStructurePlacement#legacyPillagerOutpostReducer`, `ChunkGeneratorStructureState#generateRingPositions`, and `RandomState` (terrain/biome/bedrock noise).
   - Shall **not** alter vanilla terrain generation algorithms, biome rules, mob spawning, or redstone/farm mechanics—only the PRNG seed material fed to worldgen.
2. **Fail-Closed World Immutability Guard (`S-004`):**
   - Because toggling secure-seed mid-world causes chunk-border discontinuities, GoldLeaf records secure-seed state in `WorldOptions` (`world_gen_settings.dat`) and **fails closed on startup** (`IllegalStateException`) if `worldgen.secure-seed.enabled` disagrees with an existing world's stored seed mode (unless `worldgen.secure-seed.allow-unsafe-migration: true` is explicitly set).

### 4. Minimal `goldleaf.yml` Surface

```yaml
security:
  disable-chat-signing: false   # Strip Mojang chat signatures / disable enforce-secure-chat (opt-in)

worldgen:
  secure-seed:
    enabled: false              # 1024-bit cryptographic secure seed for new worlds (immutable per world)
    allow-unsafe-migration: false # Explicit override to boot an existing world on mode mismatch (S-004)
```

### 5. Mandatory Verification Gates

1. **Offline Plugin Audit CLI Gate:** Automated test invoking `--audit-plugins` against a fixture directory containing a clean Folia plugin, a legacy Bukkit scheduler plugin, and a truncated/corrupt `.jar`, verifying zero classloading, accurate verdicts, and non-zero exit code under `--strict` (`S-004`).
2. **Chat Signing Parity & Toggle Gate:** Unit/protocol test verifying `disable-chat-signing: false` preserves unmodified `ClientboundServerDataPacket` and `ClientboundPlayerChatPacket` fields, while `disable-chat-signing: true` strips signatures and sets `enforcesSecureChat == false` without throwing.
3. **Secure Seed Immutability & Determinism Gate (Post-`v0.1`):** Verify identical 1024-bit seeds produce deterministic chunk/structure placement across runs, and toggling the flag on an existing world fails closed (`S-004`).

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| **Require operators to boot a full GoldLeaf server to run plugin preflight** | Raises switching anxiety for Paper/LeafMC admins who want a 2-second read-only compatibility check before migrating. |
| **Leave chat-signing removal solely to third-party NMS plugins** | Third-party `NoChatReports` plugins break on nearly every Minecraft `26.x` drop due to NMS packet churn; a 3-point opt-in Layer 1 seam is trivial to maintain and rebase. |
| **Enable `disable-chat-signing` or `secure-seed` by default** | Violates GoldLeaf's drop-in vanilla/Paper parity contract (`goldleaf-ADR-013`, `014`); both features carry operator-visible policy trade-offs and must remain explicitly opt-in. |
| **Ship `secure-seed` in `v0.1` ahead of `.lca` and worldgen batch wiring** | Distracts from core architectural moats (`goldleaf-ADR-004`, `005`, `017`) and touches `WorldOptions` / `WorldgenRandom` before the worldgen pipeline stabilizes. |

## Consequences

- **Positive:**
  - Gives Paper/LeafMC administrators a zero-risk `--audit-plugins` pre-migration check alongside `--goldleaf-migrate --dry-run` (`goldleaf-ADR-014`).
  - Eliminates two of the most common non-performance reasons operators stay on LeafMC/Leaves/Luminol while keeping `folia-server` patch counts minimal.
  - Preserves 100% vanilla/Paper default behavior when unconfigured.
- **Negative / Trade-offs:**
  - Adds a small network-layer patch surface (`ClientboundServerDataPacket`, `ClientboundPlayerChatPacket`, `ServerGamePacketListenerImpl`) to keep aligned across `26.x` protocol updates.
  - `secure-seed` worlds cannot be trivially reverted to standard 64-bit seed placement for newly generated chunks (mitigated by fail-closed immutability checks).

## References

- Verified Folia `26.3.x` seams in `GoldLeaf`:
  - `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/server/Main.java`
  - `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/server/network/ServerGamePacketListenerImpl.java`
  - `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/network/protocol/game/ClientboundServerDataPacket.java`
  - `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/network/protocol/game/ClientboundPlayerChatPacket.java`
  - `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/world/level/levelgen/WorldOptions.java`
  - `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/world/level/levelgen/WorldgenRandom.java`
  - `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/world/level/levelgen/structure/placement/StructurePlacement.java`
  - `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/diagnostics/BytecodeFoliaPreflightProvider.java`
- Related `GoldLeaf` ADRs:
  - `GoldLeaf/docs/adr/goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-007-plugin-safety-preflight-and-runtime-hazard-spi.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-013-vanilla-faithful-micro-optimization-boundary-and-section-epoch-reuse.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-014-zero-friction-migration-cli-honest-auto-tuner-and-technical-parity-surface.md`
  - `docs/dev/RULES.md` (`S-004`, `S-005`)
