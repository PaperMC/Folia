# goldleaf-ADR-009 — Entity Tracker Scaling, Off-Tick Packet Encoding, and `.lca`-Aware Anti-Xray SPI

**Status:** In Progress
**Date:** 2026-11-21

## Context

### 1. The Post-Regionization Ceiling Bottleneck (200–2,000+ Players)

Once Folia parallelizes world simulation across independent regions and `goldleaf-ADR-003`..`005` remove small-machine scheduling overhead, worldgen stalls, and `.mca` storage I/O from tick threads, the next dominant CPU and memory-bandwidth ceiling on dense hubs and high-population SMPs is **observation and network fan-out**:

1. **Entity Tracking (`ChunkMap.TrackedEntity` & `ServerEntity`):**
   - Inspecting `/home/leaf26/GoldLeaf/folia-server/src/minecraft/java/net/minecraft/server/level/ChunkMap.java` (`ChunkMap.TrackedEntity#moonrise$tick`, lines 1259–1335) shows that every tracked entity iterates all players in `chunk.getPlayers(NearbyMapType.VIEW_DISTANCE)` on every tick and invokes `updatePlayer(player)` even when neither entity nor nearby players crossed a visibility or range boundary.
   - `moonrise$tick`, `moonrise$removeNonTickThreadPlayers`, and `moonrise$clearPlayers` allocate `new ArrayList<>(this.seenBy)` defensive copies during purge/clear paths, adding Young-Gen allocation churn in crowded hubs.
   - `sendToTrackingPlayers` (`ChunkMap.java:1355-1359`) dispatches the same `Packet` object individually to every `ServerPlayerConnection` in `seenBy`, causing Netty to re-serialize and re-compress identical entity-movement and metadata payloads $N$ times for $N$ viewers in a hub or event arena.
2. **Chunk Packet Encoding & Anti-Xray (`PlayerChunkSender`):**
   - As documented in `goldleaf-ADR-005`, `PlayerChunkSender#sendChunk` invokes `level.chunkPacketBlockController.shouldModify(connection.player, chunk)` and builds `ClientboundLevelChunkWithLightPacket` on the region tick thread. Paper's Anti-Xray engine-mode `2` is a well-known CPU sink during high-speed elytra exploration and login bursts, yet survival SMPs rely on it to deter x-ray clients.

### 2. Why This Belongs in GoldLeaf

Unlike mob AI throttling or entity activation hacks (`goldleaf-ADR-008`), **entity tracking, packet serialization, and Anti-Xray obfuscation are observation/network work, not simulation**. Optimizing them preserves `100%` vanilla redstone, mob, and technical-farm behavior while addressing one of the primary reasons large networks adopt forks such as Pufferfish, SparklyPaper, and LeafMC.

## Decision

GoldLeaf introduces a **vanilla-faithful Layer 1 Network & Tracker SPI** paired with **Layer 2 off-tick packet encoding, shared-buffer broadcast, and `.lca`-aware Anti-Xray**—strictly observing Folia region ownership (`F-001`..`F-003`) and zero-blocking-I/O tick-thread invariants (`S-005`).

### 1. Layer 1 — Thin Folia Core Seams (`folia-server`)

1. **Allocation-Free `TrackedEntity` Dirty-State & Snapshot Fast Path (`ChunkMap.TrackedEntity`):**
   - Replace per-purge `new ArrayList<>(this.seenBy)` allocations in `moonrise$tick`, `moonrise$removeNonTickThreadPlayers`, and `moonrise$clearPlayers` with a reusable scratch array or in-place indexed pass when safe, eliminating hub Young-Gen churn.
   - Add a lightweight per-entity spatial/visibility dirty epoch (`lastBroadcastPos` / `lastChunkUpdate`) so stationary entities whose chunk player-list `updateCount` has not changed skip redundant per-player `updatePlayer(player)` range checks while preserving immediate updates on entity movement, passenger/equipment/metadata mutation, or player visibility changes.
2. **Pre-Encoded Broadcast SPI (`TrackedEntity#sendToTrackingPlayers` & `ServerEntity`):**
   - Expose a thin `BroadcastPacketEncoder` SPI hook on `TrackedEntity#sendToTrackingPlayers` so high-fan-out entity packets (`ClientboundMoveEntityPacket`, `ClientboundSetEntityDataPacket`, `ClientboundRotateHeadPacket`) with `seenBy.size() >= fanoutThreshold` can be serialized once into an immutable reference-counted `ByteBuf` (or shared compressed frame per protocol/compression bucket) and written zero-copy to Netty channels without mutating world state off-thread.
   - Maintain strict fallback to standard `connection.send(packet)` whenever a per-player packet listener/interceptor (e.g., ProtocolLib, PacketEvents, per-player visibility rewrites) is registered on the connection (`S-004`).
3. **Off-Tick Chunk Packet & Anti-Xray Controller SPI (`PlayerChunkSender`):**
   - Extend the `PlayerChunkSender` SPI (`goldleaf-ADR-005`) so chunk packet assembly and Anti-Xray section obfuscation can execute on a dedicated non-tick Netty/worker pool over an **immutable section snapshot** (`ChunkFrame` / `PalettedContainer` read-only copy or `LevelChunkSection#sectionEpoch`-guarded slice) rather than blocking the owning region tick thread (`S-005`).
   - Preserve exact `ClientboundChunkBatchStartPacket` / `ClientboundChunkBatchFinishedPacket` pacing, `PlayerChunkLoadEvent` ordering, and immediate invalidation if a block mutation increments `sectionEpoch` before transmission.

### 2. Layer 2 — GoldLeaf Network Engine (`goldleaf.yml`)

1. **`.lca`-Aware Anti-Xray Fast Path:**
   - When `.lca` cold `ChunkFrame` residency (`goldleaf-ADR-005`) is active, run Anti-Xray engine-mode `1`/`2` palette masking directly against section palette tables and `octreeOccupancyMask64` / exposed-face bitmasks on worker threads, avoiding full `4,096`-block scalar scans on uniform or occluded sections.
   - When `LeafAnvil-Net` (`leafanvil-ADR-007`) progressive 3D subterranean occlusion streaming is negotiated with a modded client, bypass redundant Paper Anti-Xray work for occluded sub-sections as already permitted by `goldleaf-ADR-005`.
2. **Native Transport & `io_uring` Alignment:**
   - Prefer Netty native `io_uring` / `epoll` transports on Linux hosts (with clean fallback to NIO) and expose explicit `network.entity-tracker.shared-broadcast-threshold` and `network.anti-xray.off-tick-encoding` toggles in `goldleaf.yml`.
3. **Strict Vanilla-Fidelity & Safety Guardrails:**
   - GoldLeaf shall **not** reduce entity tracking ranges, drop movement packets, or skip `ServerEntity#sendChanges` ticks by default (no heuristic "packet throttling" that causes desynced PvP hitboxes or redstone/minecart jitter).
   - All off-tick snapshots shall be immutable and explicitly released on every completion and error path (`S-002`, `S-004`).

### 3. Mandatory Verification Gates

1. **Zero Desync & Visibility Parity Test:** Verify identical `seenBy` membership, spawn/despawn packet sequences, and entity movement/metadata packets across moving players, dimension teleports, and cross-region boundary crossings compared to stock Folia.
2. **Mixed-Interceptor Safety Test:** Verify that connections with per-player packet interceptors cleanly bypass shared-buffer broadcast without leaking another player's rewritten packet data.
3. **Anti-Xray Equivalence & Leak Gate:** Verify zero ore-exposure leaks on block-break neighbor updates, zero `ByteBuf` reference-count leaks under Netty `PARANOID` leak detection (`S-002`), and differential MSPT + Netty CPU benchmarks (`Gate 10`) on 200+ simulated viewers.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Run `ChunkMap.TrackedEntity#moonrise$tick` asynchronously on worker threads over live `Entity` / `ServerLevel` state** | Violates Folia region ownership (`F-001`..`F-003`) and races live entity positions, `seenBy` sets, and Bukkit visibility plugins (`S-001`, `S-005`). |
| **Throttle entity tracker update intervals by distance (Pufferfish/LeafMC-style heuristic skipping)** | Introduces visible minecart, projectile, and PvP hitbox jitter and breaks technical player expectations; belongs only in opt-in plugins if desired. |
| **Leave Anti-Xray and chunk packet serialization synchronous on region tick threads** | Preserves a major MSPT spike during login storms and fast elytra travel, especially on 4–8 core floor machines and large survival SMPs. |
| **Require a custom client mod for entity/packet scaling** | Excludes vanilla clients on public hubs and SMPs; shared-buffer broadcast and off-tick Anti-Xray deliver full wins to 100% unmodified vanilla clients. |

## Consequences

- **Positive:**
  - Directly raises the practical player ceiling on crowded hubs, minigame lobbies, and high-population SMPs for 100% unmodified vanilla clients.
  - Moves Anti-Xray engine-mode `2` and chunk packet serialization off region tick threads (`S-005`), eliminating exploration/login MSPT spikes.
  - Reuses `LevelChunkSection#sectionEpoch` and `.lca` immutable snapshots (`goldleaf-ADR-005`, `goldleaf-ADR-008`) without duplicating chunk memory structures.
- **Negative / Trade-offs:**
  - Reference-counted shared `ByteBuf` broadcast requires disciplined lifecycle management and Netty leak-detection CI coverage (`S-002`).
  - Connections with third-party per-player packet mutators (ProtocolLib/PacketEvents) must automatically drop back to per-player encoding for mutated packet types.

## References

- Materialized Folia `26.3.x` seams in `/home/leaf26/GoldLeaf`:
  - `folia-server/src/minecraft/java/net/minecraft/server/level/ChunkMap.java` (`TrackedEntity#moonrise$tick`, `sendToTrackingPlayers`)
  - `folia-server/src/minecraft/java/net/minecraft/server/level/ServerEntity.java`
  - `folia-server/src/minecraft/java/net/minecraft/server/network/PlayerChunkSender.java`
- Related `GoldLeaf` ADRs:
  - `GoldLeaf/docs/adr/goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-005-lca-format-adoption-across-disk-memory-and-network.md`
  - `GoldLeaf/docs/adr/goldleaf-ADR-008-mob-ai-optimization-boundary-and-farm-parity-gate.md`
  - `docs/dev/RULES.md` (`F-001..F-003`, `S-001..S-005`)
