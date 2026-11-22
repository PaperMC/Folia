# goldleaf-ADR-010 — Region-Safe Technical Client Protocol SPI (`Servux`, `Syncmatica`, `Jade`, `AppleSkin`, Map World-ID)

**Status:** In Progress
**Date:** 2026-11-21

## Context

### 1. Why Technical SMPs Leave Stock Paper/Folia for `Leaves` / `LeafMC` / `Luminol`

Technical survival communities are GoldLeaf's core credibility audience (`goldleaf-ADR-001`, `goldleaf-ADR-008`). In practice, the single most common reason technical SMP administrators choose forks like `Leaves`, `LeafMC`, or `Luminol` over stock Paper/Folia is **out-of-the-box support for client-side technical utility protocols**:

- **Servux (`servux:*`):** Structure bounding boxes, entity/block-entity metadata, and HUD data for MiniHUD and Litematica.
- **Syncmatica (`syncmatica:*`):** Shared schematic placement synchronization across technical build teams.
- **Jade / WTHIT (`jade:*`, `wthit:*`):** Server-authoritative block-entity / entity inspection tooltips (furnace progress, bee count, villager trades, hopper contents).
- **AppleSkin (`appleskin:*`):** Saturation and exhaustion synchronization packets.
- **XaeroMap / JourneyMap World-ID (`xaerominimap:*`, `xaeroworldmap:*`, `journeymap:*`):** Stable multi-world / multi-shard map disambiguation.

Running 4–6 separate third-party NMS bridge plugins to provide these protocols across Mojang's `26.x` drop cadence is fragile—and on **Folia**, most existing Bukkit protocol plugins fail or throw `IllegalStateException` because they read cross-region chunks, structures, or block entities from the wrong tick thread (`F-001`..`F-003`).

### 2. Verified Materialized Folia `26.3.x` Seam

Inspecting `/home/leaf26/GoldLeaf/folia-server/src/minecraft/java/net/minecraft/server/network/ServerCommonPacketListenerImpl.java` (`handleCustomPayload`, lines 145–184) shows that Folia already routes incoming `ServerboundCustomPayloadPacket` frames onto the player's owning region thread via `PacketUtils.ensureRunningOnSameThread(...)`, parses `minecraft:register` / `minecraft:unregister` channels into `PluginMessageBridgeImpl`, and dispatches incoming payloads.

However, Folia provides **no region-safe native protocol registry** for structured client-mod protocols, nor a unified read-only world/structure query adapter backed by `goldleaf-ADR-002` async-first read routing.

## Decision

GoldLeaf introduces a **thin, opt-in Layer 1 Technical Protocol SPI (`TechnicalProtocolRegistry`)** and **Layer 2 Protocol Adapters**—disabled by default per protocol in `goldleaf.yml`—so technical SMPs get maintainable, Folia-safe client mod integration without bloating `folia-server` patches or shipping fragile NMS plugins.

### 1. Layer 1 — Thin Protocol Hook & Registry SPI (`folia-server`)

1. **`TechnicalProtocolRegistry` & `ClientProtocolHandler` SPI:**
   - Expose a zero-dependency Layer 1 SPI in `folia-server` allowing protocol handlers to register channel namespaces (`Identifier`), lifecycle hooks (`onPlayerJoin`, `onChannelRegister`, `onCustomPayload`, `onPlayerTick`, `onPlayerQuit`), and permission predicates.
   - Hook cleanly into `ServerCommonPacketListenerImpl#handleCustomPayload` (`ServerCommonPacketListenerImpl.java:145-184`) and player join/world-change/quit lifecycle events without breaking standard Bukkit `PluginMessenger` channel delivery.
2. **Region-Safe Data Access Contract (`F-001`..`F-003`, `S-005`):**
   - Every protocol handler callback executes on the **player's owning region thread** for player-local state (`AppleSkin` saturation/exhaustion, `XaeroMap` world-ID) or delegates cross-region/unloaded read-only queries (e.g., `Servux` structure bounding-box lookups) through `goldleaf-ADR-002`'s async-first read router / immutable chunk snapshots—**never** calling synchronous `Level#getChunk` across region boundaries (`S-005`).
3. **Fail-Closed Decode & Rate-Limit Guard (`S-004`):**
   - Enforce per-channel payload byte caps, per-player token-bucket rate limits, and strict `FriendlyByteBuf` bounds checks before dispatching to any handler. Malformed or oversized protocol payloads log a `WARNING` and drop/disconnect cleanly rather than throwing uncaught exceptions or exhausting heap.

### 2. Layer 2 — First-Party Protocol Modules (`goldleaf.yml`, Opt-In)

Layer 2 implements protocol adapters cleanly outside core Folia patches, each individually toggleable and permission-gated in `goldleaf.yml`:

| Protocol Module | Config Key (Default) | Threading & Safety Model |
|---|---|---|
| **Servux** (MiniHUD / Litematica structures & metadata) | `protocols.servux.enabled: false` | Bounded radius cap (`max-structure-radius`), permission-gated per feature (`structures`, `entity-data`, `block-entity-data`), region-owned or snapshot-backed reads only. |
| **Syncmatica** (Shared schematics) | `protocols.syncmatica.enabled: false` | Async disk I/O for schematic `.litematic` storage (`S-005`), strict file-size and quota caps, permission-gated upload/delete. |
| **Jade / WTHIT** (Tooltip inspection) | `protocols.jade.enabled: false` | Owning-region-thread raycast/entity lookup within player's interaction reach; respects visibility/vanish and container-lock permissions. |
| **AppleSkin** (Saturation / exhaustion) | `protocols.appleskin.enabled: false` | Dirty-checked delta packets on player tick; zero allocations when unchanged. |
| **Map World-ID** (Xaero / JourneyMap) | `protocols.map-world-id.enabled: false` | Emits deterministic world/shard UUID on join and world/shard transfer (`LeafShard` compatible). |

### 3. Explicit Non-Goals (Scope Discipline)

- **No default-enabled protocols:** All protocol modules default to `false` so public survival/anarchy servers never expose structure bounding boxes, container contents, or schematic upload endpoints unintentionally.
- **No Carpet-style gameplay-altering rules inside this SPI:** Fake-player / bot spawning (if added post-`v0.1`) shall use a separate explicit bot SPI aligned with `LeafFuzz` (`goldleaf-ADR-007`), not the passive client-protocol registry.

### 4. Mandatory Verification Gates

1. **Protocol Round-Trip & Wire-Compatibility Tests:** Unit/headless tests verifying exact wire packet encoding/decoding for each enabled protocol (`Servux`, `Syncmatica`, `Jade`, `AppleSkin`, `Map World-ID`) against captured client frames.
2. **Folia Cross-Region & Fuzzing Gate (`ADR-102`):** Verify zero cross-region thread-check violations when a player queries structures/blocks near a Folia region seam, and fuzz `handleCustomPayload` with truncated, oversized, and malformed buffers to prove fail-closed handling (`S-004`).
3. **Default-Off Zero-Overhead Check:** Verify that when all `protocols.*.enabled` flags are `false`, `ServerCommonPacketListenerImpl#handleCustomPayload` incurs a single fast-path empty-registry check with zero allocations.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Leave all technical client protocols to third-party Bukkit/Paper plugins** | Existing plugins rely on brittle NMS reflection that breaks on every `26.x` drop and frequently crashes on Folia region-thread assertions. |
| **Port `Leaves` / `LeafMC` protocol patches directly into `folia-server` monolithically** | Creates heavy rebase friction on `26.x` upgrades and couples protocol wire logic directly to NMS classes instead of a clean Layer 1 SPI + Layer 2 adapter boundary. |
| **Enable `Servux` / `Jade` by default** | Leaks structure coordinates, chest/furnace contents, and villager data to players on competitive/anarchy servers where admins expect vanilla information boundaries. |

## Consequences

- **Positive:**
  - Directly removes the `#1` adoption barrier for technical survival SMPs moving to Folia `26.3+`.
  - Keeps `folia-server` patches tiny (`TechnicalProtocolRegistry` + lifecycle hooks) so `26.x` rebases remain fast and low-conflict.
  - Guarantees Folia region-thread safety (`F-001`..`F-003`), async schematic I/O (`S-005`), and permission/rate-limit hardening by construction.
- **Negative / Trade-offs:**
  - Protocol wire formats (`Servux`, `Jade`, `Syncmatica`) evolve independently upstream and require versioned codec maintenance in Layer 2.
  - Operators upgrading from standalone plugins must opt into each desired protocol explicitly in `goldleaf.yml`.

## References

- Materialized Folia `26.3.x` seams in `/home/leaf26/GoldLeaf`:
  - `folia-server/src/minecraft/java/net/minecraft/server/network/ServerCommonPacketListenerImpl.java` (`handleCustomPayload`, lines 145–184)
  - `folia-server/src/minecraft/java/net/minecraft/server/network/ServerGamePacketListenerImpl.java`
- Related `GoldLeaf` & repository ADRs:
  - `GoldLeaf/docs/adr/goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md` (Stage 5)
  - `GoldLeaf/docs/adr/goldleaf-ADR-002-sub-tick-interrupt-scheduling-and-latency-tiered-routing.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-007-plugin-safety-preflight-and-runtime-hazard-spi.md`
  - `docs/adr/ADR-102-coverage-guided-fuzz-testing-and-parser-input-hardening.md`
  - `docs/dev/RULES.md` (`F-001..F-003`, `S-004`, `S-005`)
