package dev.leafcraft.goldleaf.telemetry;

import dev.leafcraft.goldleaf.bot.FoliaSyntheticPlayerBridge;
import dev.leafcraft.goldleaf.bot.SyntheticPlayerService;
import dev.leafcraft.goldleaf.compute.GoldLeafComputeServiceImpl;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.diagnostics.RegionHealthSnapshot;
import dev.leafcraft.goldleaf.scheduler.GoldLeafPredictPacer;
import io.papermc.paper.compute.ComputeService;
import io.papermc.paper.spi.PaperTelemetrySpi;
import io.papermc.paper.telemetry.ChunkTelemetryService;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 bootstrap registrar for chunk tick telemetry, execution masks, region HUD actionbar
 * dispatch, {@link ComputeService}, synthetic player Folia bridging, and pacer/metrics singletons
 * ({@code goldleaf-ADR-011}, {@code goldleaf-ADR-015}).
 */
public final class TelemetryAndServicesBootstrapRegistrar {

    private TelemetryAndServicesBootstrapRegistrar() {
    }

    public static void register(final @Nullable GoldLeafConfig config) {
        PaperTelemetrySpi.register(new PaperTelemetrySpi.Provider() {
            @Override
            public boolean isTelemetryActive() {
                return ChunkTickTelemetry.isActive();
            }

            @Override
            public boolean isExecutionSuppressed(final @Nullable LevelChunk chunk, final int maskBit) {
                return chunk != null && ChunkExecutionMask.isSuppressed(chunk, maskBit);
            }

            @Override
            public void dispatchPhaseSample(
                final long regionId,
                final String worldName,
                final int chunkX,
                final int chunkZ,
                final int phaseOrdinal,
                final long durationNanos
            ) {
                final ChunkTickPhase[] phases = ChunkTickPhase.values();
                if (phaseOrdinal >= 0 && phaseOrdinal < phases.length) {
                    ChunkTickTelemetry.dispatchPhaseSample(regionId, worldName, chunkX, chunkZ, phases[phaseOrdinal], durationNanos);
                }
            }

            @Override
            public long currentMaskEpoch() {
                return ChunkTickTelemetry.currentMaskEpoch();
            }

            @Override
            public void onRegionTickEnd(
                final long regionId,
                final String worldId,
                final long durationNanos,
                final int playerCount,
                final int chunkCount
            ) {
                final double msptMs = Math.max(0.0, durationNanos / 1_000_000.0);
                final double tps = durationNanos <= 50_000_000L
                    ? 20.0
                    : Math.min(20.0, 1_000_000_000.0 / Math.max(1L, durationNanos));
                GoldLeafMetricsCollector.global().recordRegionSample(
                    worldId + "@" + regionId,
                    tps,
                    msptMs,
                    playerCount,
                    chunkCount,
                    1L,
                    0L
                );
                // Live /goldleaf hud actionbar dispatch (ADR-011): zero allocations when no subscribers
                final RegionHealthSnapshot hud = RegionHealthSnapshot.global();
                if (hud.hasActiveHudSubscribers()) {
                    final RegionizedWorldData worldData = TickRegionScheduler.getCurrentRegionizedWorldData();
                    if (worldData != null && !worldData.getLocalPlayers().isEmpty()) {
                        final long tickCount = worldData.world != null ? worldData.world.getGameTime() : 0L;
                        RegionHealthSnapshot.RegionMetrics metrics = null;
                        for (final ServerPlayer player : worldData.getLocalPlayers()) {
                            if (player == null || !hud.isHudSubscribed(player.getUUID())) {
                                continue;
                            }
                            if (metrics == null) {
                                final ChunkPos center = player.chunkPosition();
                                final double utilisation = Math.min(1.0, msptMs / 50.0);
                                metrics = new RegionHealthSnapshot.RegionMetrics(
                                    worldId,
                                    center.x(),
                                    center.z(),
                                    tps,
                                    msptMs,
                                    msptMs,
                                    msptMs,
                                    utilisation,
                                    chunkCount,
                                    worldData.getEntityCount(),
                                    playerCount
                                );
                            }
                            final String line = hud.maybeFormatHudForTick(player.getUUID(), tickCount, metrics);
                            if (line != null) {
                                player.sendSystemMessage(Component.literal(line), true);
                            }
                        }
                    }
                }
                final long nowNanos = System.nanoTime();
                final GoldLeafPredictPacer pacer = GoldLeafPredictPacer.global();
                pacer.evaluateLaneBudgetNanos(GoldLeafPredictPacer.WorkLane.OFF_TICK_LCA_IO, msptMs, 40.0, 0L, false, nowNanos);
                pacer.evaluateLaneBudgetNanos(GoldLeafPredictPacer.WorkLane.CHUNK_GEN_DISPATCH, msptMs, 40.0, 0L, false, nowNanos);
                pacer.evaluateLaneBudgetNanos(GoldLeafPredictPacer.WorkLane.PACKET_FLUSH, msptMs, 40.0, 0L, false, nowNanos);
                pacer.evaluateLaneBudgetNanos(GoldLeafPredictPacer.WorkLane.SHARD_MIGRATION, msptMs, 40.0, 0L, false, nowNanos);
            }
        });
        ChunkTelemetryService.register(GoldLeafChunkTelemetryService.instance());
        ComputeService.register(GoldLeafComputeServiceImpl.global());

        // Synthetic players / bots Folia region & lifecycle bridge (ADR-015)
        FoliaSyntheticPlayerBridge.install(SyntheticPlayerService.global());

        // Ensure predict-api pacer and metrics-api collector singletons are initialized
        GoldLeafPredictPacer.global();
        GoldLeafMetricsCollector.global();
    }
}
