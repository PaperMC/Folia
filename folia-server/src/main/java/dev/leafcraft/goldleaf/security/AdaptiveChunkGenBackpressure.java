package dev.leafcraft.goldleaf.security;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Layer 2 adaptive per-player chunk-generation backpressure implementation
 * ({@code goldleaf-ADR-012} §1.1 &amp; §2.1).
 *
 * <p>Guarantees:
 * <ul>
 *   <li><strong>Zero effect below saturation:</strong> When {@code pendingGlobalGenQueue < watermark},
 *       returns {@code baseMaxGenerations} and {@code baseSendDistance} untouched.</li>
 *   <li><strong>Disk-read fairness:</strong> Only actual ungenerated chunks ({@code status != FULL})
 *       add to a player's rolling debt; players flying over pre-generated terrain keep zero debt
 *       and full send distance even when another account saturates the generator.</li>
 *   <li><strong>Smooth decay &amp; velocity priority:</strong> High-debt players smoothly pace down
 *       chunk-generation rate ({@code maxGenerationsThisTick}) without shrinking client view distance,
 *       rubber-banding, or altering movement physics, and recover automatically over
 *       {@code chunkGenDebtDecaySeconds}.</li>
 * </ul>
 * </p>
 */
public final class AdaptiveChunkGenBackpressure implements ChunkLoadBackpressurePolicy {

    public static final AdaptiveChunkGenBackpressure DEFAULT = new AdaptiveChunkGenBackpressure();

    private static final double DEBT_THRESHOLD_FOR_PACING = 16.0;
    private static final double MAX_DEBT_FOR_FLOOR = 160.0;

    private record DebtSnapshot(double value, long lastUpdateNanos) {
        static final DebtSnapshot ZERO = new DebtSnapshot(0.0, 0L);

        DebtSnapshot decayed(final long nowNanos, final long decayWindowNanos) {
            if (this.lastUpdateNanos == 0L) {
                return new DebtSnapshot(this.value, nowNanos);
            }
            final long elapsed = nowNanos - this.lastUpdateNanos;
            if (elapsed <= 0L) {
                return this;
            }
            double next = this.value;
            if (next > 0.0) {
                final double halfLives = (double) elapsed / (double) Math.max(1L, decayWindowNanos);
                next *= Math.pow(0.5, halfLives);
                if (next < 0.01) {
                    next = 0.0;
                }
            }
            return new DebtSnapshot(next, nowNanos);
        }
    }

    private final ConcurrentHashMap<UUID, AtomicReference<DebtSnapshot>> playerDebt = new ConcurrentHashMap<>();

    @Override
    public void recordNewChunkGenerations(final UUID playerId, final int newlyGeneratedChunks, final long nowNanos) {
        if (newlyGeneratedChunks <= 0) {
            return;
        }
        Objects.requireNonNull(playerId, "playerId");
        final long decayNanos = TimeUnit.SECONDS.toNanos(GoldLeafConfig.get().antiExploit.chunkGenDebtDecaySeconds);
        final AtomicReference<DebtSnapshot> ref =
            this.playerDebt.computeIfAbsent(playerId, ignored -> new AtomicReference<>(DebtSnapshot.ZERO));
        ref.updateAndGet(prev -> {
            final DebtSnapshot decayed = prev.decayed(nowNanos, decayNanos);
            return new DebtSnapshot(decayed.value() + newlyGeneratedChunks, decayed.lastUpdateNanos());
        });
    }

    @Override
    public PacingDecision evaluate(
        final UUID playerId,
        final int pendingGlobalGenQueue,
        final long baseMaxGenerations,
        final int baseSendDistance,
        final long nowNanos
    ) {
        Objects.requireNonNull(playerId, "playerId");
        final GoldLeafConfig.AntiExploit cfg = GoldLeafConfig.get().antiExploit;
        if (!cfg.chunkGenBackpressureEnabled || pendingGlobalGenQueue < cfg.chunkGenSaturationWatermark) {
            return new PacingDecision(baseMaxGenerations, baseSendDistance, false);
        }

        final AtomicReference<DebtSnapshot> ref = this.playerDebt.get(playerId);
        if (ref == null) {
            // Zero generation debt (e.g., player traversing pre-generated terrain): never penalize.
            return new PacingDecision(baseMaxGenerations, baseSendDistance, false);
        }

        final long decayNanos = TimeUnit.SECONDS.toNanos(cfg.chunkGenDebtDecaySeconds);
        final double currentDebt = ref.updateAndGet(prev -> prev.decayed(nowNanos, decayNanos)).value();
        if (currentDebt < DEBT_THRESHOLD_FOR_PACING) {
            return new PacingDecision(baseMaxGenerations, baseSendDistance, false);
        }

        final double pressure = Math.min(1.0, (currentDebt - DEBT_THRESHOLD_FOR_PACING) / (MAX_DEBT_FOR_FLOOR - DEBT_THRESHOLD_FOR_PACING));
        final double queueScale = Math.min(2.0, (double) pendingGlobalGenQueue / (double) cfg.chunkGenSaturationWatermark);
        final double severity = Math.min(1.0, pressure * Math.min(1.0, 0.5 + 0.5 * queueScale));

        final long pacedMaxGens = baseMaxGenerations <= 0L
            ? 0L
            : Math.max(1L, Math.round(baseMaxGenerations * (1.0 - 0.85 * severity)));
        return new PacingDecision(pacedMaxGens, baseSendDistance, true);
    }

    /**
     * Trajectory-aware chunk priority comparator score ({@code goldleaf-ADR-012} §2.1): favors
     * chunks aligned with a moving elytra flyer's velocity vector over chunks behind them when
     * under backpressure. Lower score = higher priority.
     */
    public static double velocityAdjustedChunkPriority(
        final int playerChunkX,
        final int playerChunkZ,
        final double velocityX,
        final double velocityZ,
        final int targetChunkX,
        final int targetChunkZ
    ) {
        final double dx = targetChunkX - playerChunkX;
        final double dz = targetChunkZ - playerChunkZ;
        final double distSq = dx * dx + dz * dz;
        final double speed = Math.hypot(velocityX, velocityZ);
        if (speed < 0.05 || distSq == 0.0) {
            return distSq;
        }
        final double dot = (dx * velocityX + dz * velocityZ) / (Math.sqrt(distSq) * speed);
        // Ahead (dot -> +1): up to 40% priority boost; behind (dot -> -1): 40% penalty
        return distSq * (1.0 - 0.4 * dot);
    }

    @Override
    public void onPlayerQuit(final UUID playerId) {
        if (playerId != null) {
            this.playerDebt.remove(playerId);
        }
    }

    public int trackedPlayers() {
        return this.playerDebt.size();
    }

    public void clear() {
        this.playerDebt.clear();
    }
}
