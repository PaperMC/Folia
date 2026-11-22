# goldleaf-ADR-015 — Region-Safe Fake Player / Bot SPI (`LeafFuzz` & Technical SMP Carpet-Class Workflows)

**Status:** In Progress
**Date:** 2026-11-21

## Context

### 1. Why Technical SMPs Ask for Carpet/Leaves-Style Bots — and Why Existing Solutions Break on Folia

Across technical survival communities (`Leaves`, `Fabric + Carpet`), **server-side fake players (bots)** are a staple tool for:
- **Farm verification & chunk loading:** Testing mob-cap behavior, iron/raid/witch farm rates, and perimeter AFK spots without requiring secondary paid accounts or headless external clients.
- **Automated stress & regression testing:** `goldleaf-ADR-007` and `goldleaf-ADR-008` already rely on `LeafFuzz` (`NmsEmbeddedPlayerSession`) to drive realistic multi-player workloads across Folia regions for parity and fuzz testing.

However, existing Bukkit/Paper fake-player plugins rely on brittle NMS reflection around `ServerPlayer`, `Connection`, and `PlayerList#placeNewPlayer`—breaking on every Mojang `26.x` drop and frequently tripping Folia region-thread assertions (`F-001`..`F-003`) when spawning, ticking, teleporting, or disconnecting synthetic players across region boundaries. Conversely, hardcoding a full Carpet-style `/bot` command suite directly into `folia-server` (as `Leaves` does) bloats core patches with opinionated permission, inventory, and persistence UX.

### 2. Verified Materialized Folia `26.3.x` Seam

Inspecting `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/server/players/PlayerList.java` (`placeNewPlayer`, lines 225–290) shows the exact invariants Folia requires when attaching a player entity to a region:
- `player.isRealPlayer` (`PlayerList.java:226`) distinguishes real networked clients from synthetic/fake entities where appropriate.
- `ServerGamePacketListenerImpl` is bound to the `Connection` and registered onto the owning region via `level.getCurrentWorldData().addConnection(player)` (`PlayerList.java:240`).
- All subsequent player ticks, movement, inventory actions, and chunk-loader updates (`RegionizedPlayerChunkLoader`) execute strictly on the player's owning region thread.

## Decision

GoldLeaf introduces a **thin, opt-in Layer 1 Fake Player / Bot SPI (`SyntheticPlayerService`)** aligned with `LeafFuzz`'s `NmsEmbeddedPlayerSession` (`goldleaf-ADR-007`), keeping all user-facing `/bot` commands, quotas, and persistence in **Layer 2** (disabled by default in `goldleaf.yml`).

### 1. Layer 1 — Thin Region-Safe `SyntheticPlayerService` SPI (`folia-server`)

1. **Region-Owned Lifecycle Contract (`F-001`..`F-003`):**
   - Expose a narrow Layer 1 SPI (`SyntheticPlayerService` / `SyntheticPlayerHandle`) that spawns, controls, and removes a synthetic `ServerPlayer` backed by a lightweight in-memory loopback `Connection` (zero TCP sockets, zero packet compression overhead).
   - Spawning schedules attachment onto the target chunk's owning region thread via Folia's region scheduler (never mutating `ServerLevel` or `PlayerList` from an unowned thread, `S-005`).
   - Cross-region movement and dimension teleports reuse Folia's native `ServerPlayer` async teleport/region-transfer path without custom global locks.
2. **Explicit Identity & Quota Guardrails:**
   - Synthetic players are explicitly tagged (`isSyntheticPlayer() == true`, deterministic non-colliding UUID namespace) so anti-cheat, economy, and player-count plugins can distinguish them cleanly without reflection.
   - Configurable global and per-owner hard caps (`bots.max-total`, `bots.max-per-player`), chunk-loading opt-in flag (`bots.allow-chunk-loading`), and automatic cleanup on owner disconnect or server shutdown (`S-002` — zero leaked chunk tickets or `RegionizedPlayerChunkLoader` handles).
3. **Action Primitives (Minimal, Composable):**
   - Provide clean region-thread-dispatched action primitives: look/move, attack/use-item (interval or continuous), sneak/sprint, drop-stack, and read-only state inspection—sufficient for both `LeafFuzz` automated testing (`goldleaf-ADR-007`, `goldleaf-ADR-008`) and Layer 2 Carpet-style bot plugins.

### 2. Layer 2 — Opt-In Technical SMP Bot Module (`goldleaf.yml`)

- Disabled by default (`bots.enabled: false` in `goldleaf.yml`) so public survival/anarchy servers have zero synthetic-player attack surface unless explicitly opted in.
- User-facing commands (`/bot spawn`, `/bot action`, `/bot kill`, permission nodes, optional persistence across restarts) live exclusively in Layer 2 (or third-party plugins consuming the SPI), keeping `folia-server` free of command UX and rebase bloat.

### 3. Mandatory Verification Gates

1. **Folia Region-Safety & Teleport Gate:** Spawn synthetic players across multiple independent regions, teleport them across region seams and dimensions, and verify zero region-thread assertion failures (`F-001`..`F-003`).
2. **Zero Resource-Leak Gate (`S-002`):** Repeatedly spawn and despawn 100+ synthetic players and verify zero leaked chunk tickets, `RegionizedPlayerChunkLoader` loaders, or `ServerLevel` entity references after despawn.
3. **`LeafFuzz` Unification Check:** Verify `LeafFuzz` (`NmsEmbeddedPlayerSession`) can run on top of `SyntheticPlayerService` without private NMS reflection hacks.
4. **Default-Off Guard:** Verify `SyntheticPlayerService` rejects spawn requests with a clear fail-closed error (`S-004`) when `bots.enabled` is `false`.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| **Port `Leaves`' full `/bot` command suite and persistence directly into `folia-server`** | Couples opinionated command UX, permissions, and storage directly into NMS patches, increasing `26.x` rebase friction. |
| **Leave fake players entirely to third-party NMS-reflection plugins** | Breaks on every `26.x` drop and routinely crashes Folia servers via cross-region `PlayerList` / `ServerLevel` mutations. |
| **Enable fake players by default** | Unintended chunk-loading and player-slot consumption risk on public survival servers that do not want Carpet-style bots. |

## Consequences

- **Positive:**
  - Unlocks Carpet/Leaves-class technical farm testing and AFK workflows on Folia `26.3+` with guaranteed region-thread safety and zero ticket leaks (`S-002`).
  - Unifies GoldLeaf's internal `LeafFuzz` test harness (`goldleaf-ADR-007`, `goldleaf-ADR-008`) and technical SMP bot support behind a single thin SPI.
  - Keeps `folia-server` patches small and decoupled from user-facing command syntax.
- **Negative / Trade-offs:**
  - Third-party plugins assuming every `Player` has a real remote `InetSocketAddress` or Mojang session may need to honor `isSyntheticPlayer()` (mitigated by clean loopback metadata and opt-in default).

## References

- Materialized Folia `26.3.x` seams in `GoldLeaf`:
  - `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/server/players/PlayerList.java` (`placeNewPlayer`, lines 225–290)
  - `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/server/level/ServerPlayer.java`
  - `GoldLeaf/folia-server/src/minecraft/java/ca/spottedleaf/moonrise/patches/chunk_system/player/RegionizedPlayerChunkLoader.java`
- Related `GoldLeaf` ADRs:
  - `GoldLeaf/docs/adr/goldleaf-ADR-007-plugin-safety-preflight-and-runtime-hazard-spi.md` (`LeafFuzz` / `NmsEmbeddedPlayerSession`)
  - `GoldLeaf/docs/adr/goldleaf-ADR-008-mob-ai-optimization-boundary-and-farm-parity-gate.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-010-region-safe-technical-client-protocol-spi.md`
  - `docs/dev/RULES.md` (`F-001..F-003`, `S-002`, `S-004`, `S-005`)
