# goldleaf-ADR-020 — Velocity and Facing-Direction Player Chunk Prioritization

**Status:** Accepted
**Date:** 2026-11-23

## Context

Moonrise's `RegionizedPlayerChunkLoader.PlayerChunkLoaderData` manages six per-player chunk priority heaps (`loadQueue`, `loadingQueue`, `genQueue`, `generatingQueue`, `tickingQueue`, `sendQueue`). In stock Paper/Folia:

1. All six queues share an isotropic Euclidean squared-distance comparator: `(diff1X * diff1X) + (diff1Z * diff1Z)` centered strictly on `lastChunkX, lastChunkZ`.
2. `update()` only runs when the player crosses a chunk boundary, changes view distance, or changes chunk-generation eligibility.
3. At large view distances (e.g., `view-distance=32`, roughly `4,489` chunks in the radial footprint), a player flying or travelling in one direction spends roughly half of their per-tick load, generation, and send budget on chunks behind them or outside their field of view. Distant chunks behind the player often finish generating just as they exit view distance, while chunks directly ahead wait behind closer rear chunks in `genQueue`, `generatingQueue`, and `sendQueue`.

## Decision

Introduce a thin Layer 1 SPI (`ca.spottedleaf.moonrise.spi.PlayerChunkPrioritySpi`) wired from `GoldLeafConfig.Worldgen.DirectionalChunkPriority` into `RegionizedPlayerChunkLoader.PlayerChunkLoaderData` using **elliptical focal priority**:

1. **Elliptical Focal Priority Metric:**
   For a chunk at offset `(dx, dz)` from the player's chunk center `(centerX, centerZ)` with unit heading `(hx, hz)` and eccentricity `e in [0, 0.90)`:
   - **Inner radial safety ring (`dx^2 + dz^2 <= innerRadialRadius^2`, default `r <= 2`):** Always sorts strictly before outer chunks by pure Euclidean `distSq`, guaranteeing the immediate neighborhood around the player loads and sends first regardless of yaw or velocity.
   - **Outer elliptical field (`distSq > innerRadialRadius^2`):**
     `score(dx, dz) = sqrt(dx^2 + dz^2) - e * (dx * hx + dz * hz) = r * (1 - e * cos(theta))`
     Because `0 <= e < 0.90`, `1 - e * cos(theta) >= 0.10 > 0`:
     - Level sets `score = C` are exact ellipses with the player at one focus and major axis along `(hx, hz)`.
     - Along every ray from the player, `score(r)` is **strictly linear and increasing in `r`**. Closer chunks along any heading therefore always precede farther chunks, preserving `sendQueue`'s `areNeighboursGenerated(x, z, 1)` readiness invariant with zero holes.
     - Straight ahead (`cos(theta) = +1`) receives up to `(1 + e) / (1 - e)` higher priority than straight behind (`cos(theta) = -1`): `2.64x` at standing `e = 0.45`, and `7.0x` at flight `e = 0.75`.
     - When disabled (`e == 0`), the comparator reduces identically to stock Moonrise `Integer.compare(distSq1, distSq2)`.
     - Deterministic tie-breakers (`Integer.compare(distSq1, distSq2)`, then `Long.compare(c1, c2)`) guarantee a strict total order for `LongHeapPriorityQueue`.

2. **EMA Position-Delta Velocity + Facing-Yaw Blending:**
   - Server-side `ServerPlayer#getDeltaMovement()` is frequently zeroed during creative flight and client-driven movement. `PlayerChunkLoaderData` therefore tracks horizontal displacement `(player.getX() - prevPosX, player.getZ() - prevPosZ)` per tick with an exponential moving average (`EMA`), which accurately reflects walking, sprinting, elytra, boats, and high-speed creative flight (`/flyspeed`).
   - Facing direction `(lookX, lookZ)` is derived from `player.getYRot()` (`-sin(yawRad), cos(yawRad)`).
   - At low speed (`speed <= 0.15` blocks/tick), the heading uses the facing vector with eccentricity `lookBias` (default `0.45`). As speed rises toward `1.0` blocks/tick (~`20` blocks/sec), the heading smoothly blends toward the normalized velocity vector and eccentricity scales linearly from `lookBias` to `velocityBias` (default `0.75`), ensuring sideways or backward flight still prioritizes the actual travel trajectory.

3. **Strict `LongHeapPriorityQueue` Invariant & Throttled Yaw Re-Sort (`S-005`):**
   - `PlayerChunkLoaderData` snapshots `(snapDirX, snapDirZ, snapBias, snapInnerRadiusSq)` **only** immediately before clearing and re-enqueueing all six heaps inside `update()`. `queueComparator` reads exclusively from these snapshot fields, preventing mid-heap ordering mutations.
   - When the player stays in the same chunk and rotates past `yawReprioritizeDegrees` (default `25.0°`) while at least one of `loadQueue`, `loadingQueue`, `genQueue`, `generatingQueue`, or `sendQueue` is non-empty, a throttled lightweight re-sort (at most once every `10` ticks) updates the snapshot and re-enqueues the heaps without repeating distance-map or client-radius packet updates. Once all queues are empty, stationary head movement incurs zero re-sorting cost.

4. **Fail-Closed Configuration Surface (`GoldLeafConfig` / `S-004`):**
   Under `worldgen.directional-chunk-priority`:
   - `enabled` (default `true`)
   - `look-bias` (default `0.45`, range `[0.0, 0.85]`)
   - `velocity-bias` (default `0.75`, range `[0.0, 0.90]`)
   - `inner-radial-radius` (default `2`, range `[0, 8]`)
   - `yaw-reprioritize-degrees` (default `25.0`, range `[5.0, 180.0]`)

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Shifted Euclidean center `(centerX + shiftX, centerZ + shiftZ)` | Prioritizes chunks far ahead *before* chunks between the player and the shifted center, causing `sendQueue` (`areNeighboursGenerated(..., 1)`) head-of-line stalls and visible terrain holes right in front of the player. |
| Restricting the loaded footprint to a forward cone / frustum | Changes vanilla chunk-loading semantics (breaks farms, redstone, and entity visibility behind the player) and violates `goldleaf-ADR-001` vanilla-faithful view-distance expectations. |
| Using `player.getDeltaMovement()` directly | Unreliable on `ServerPlayer` during creative flight and client-authoritative movement packets, where server-side `deltaMovement` frequently reads `(0, 0, 0)`. |
| Re-sorting heaps on every tick of yaw movement | Unnecessary tick-thread CPU work (`S-005`) when spinning in place or when all chunks in view distance are already sent. |

## Consequences

- **Positive:**
  - High-speed flight (elytra, `/flyspeed`, teleport exploration) at large view distances (`16–32`) directs `loadQueue`, `genQueue`, `generatingQueue`, and `sendQueue` along the player's forward trajectory first, reducing perceived terrain pop-in without shrinking the view-distance footprint.
  - GPU worldgen waves receive forward-cone chunks first instead of wasting wave slots on chunks exiting behind the player.
  - 100% vanilla-faithful chunk contents and identical steady-state radial view distance.
- **Negative / Trade-offs:**
  - Adds one `Math.sqrt` + fused dot-product per heap comparison (~5 ns) during `update()` re-enqueue and queue pops, negligible on the region tick thread.
  - Turning 180° rapidly while standing still incurs a single throttled re-sort (up to once every 10 ticks) only while chunks remain queued.

## References

- `GoldLeaf/folia-server/src/main/java/ca/spottedleaf/moonrise/spi/PlayerChunkPrioritySpi.java`
- `GoldLeaf/folia-server/src/minecraft/java/ca/spottedleaf/moonrise/patches/chunk_system/player/RegionizedPlayerChunkLoader.java`
- `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/config/GoldLeafConfig.java`
- `GoldLeaf/docs/adr/goldleaf-ADR-001-folia-26x-thin-spi-fork-and-world-engine-plan.md`
- `GoldLeaf/docs/adr/goldleaf-ADR-012-per-player-chunk-gen-backpressure-and-decode-time-exploit-hardening.md`
