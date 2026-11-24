package dev.leafcraft.goldleaf.diagnostics;

import dev.leafcraft.goldleaf.bot.SyntheticPlayerService;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.config.HardwareConfigAdvisor;
import dev.leafcraft.goldleaf.config.HardwareEnvelope;
import dev.leafcraft.goldleaf.network.ChunkStreamInterceptor;
import dev.leafcraft.goldleaf.network.LcaNetWireEngine;
import dev.leafcraft.goldleaf.storage.ColdChunkSavePolicy;
import dev.leafcraft.goldleaf.threading.TickThreadAutoTuner;
import dev.leafcraft.goldleaf.worldgen.IrOpenClTerrainAccelerator;
import dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator;
import dev.leafcraft.goldleaf.worldgen.WorldgenStageProfiler;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.spi.ChunkActivityCounters;
import io.papermc.paper.spi.PaperDiagnosticsSpi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 2 bootstrap registrar for diagnostics, bytecode Folia plugin preflight, boot/shutdown
 * telemetry, startup advisories, tick hazard monitoring, and lock-free watchdog snapshots
 * ({@code goldleaf-ADR-016}).
 */
public final class DiagnosticsBootstrapRegistrar {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticsBootstrapRegistrar.class);

    private DiagnosticsBootstrapRegistrar() {
    }

    public static void register(final @Nullable GoldLeafConfig config) {
        PluginPreflightDiscoveryHook.registerProvider(BytecodeFoliaPreflightProvider.instance());
        PaperDiagnosticsSpi.register(new PaperDiagnosticsSpi.Provider() {
            @Override
            public boolean isHazardMonitorActive() {
                return TickHazardMonitor.isActive();
            }

            @Override
            public void recordTickIfStalled(final long regionId, final long durationNanos, final String label) {
                TickHazardMonitor.recordTickIfStalled(regionId, durationNanos, label);
            }

            @Override
            public void recordSyncChunkLoad(
                final String worldName,
                final int chunkX,
                final int chunkZ,
                final @Nullable String caller
            ) {
                TickHazardMonitor.recordSyncChunkLoad(worldName, chunkX, chunkZ, caller);
            }

            @Override
            public void recordWrongRegionAccess(final String reason, final String context) {
                TickHazardMonitor.recordWrongRegionAccess(reason, context);
            }

            @Override
            public void emitWatchdogContext(
                final Logger logger,
                final String worldName,
                final int centerChunkX,
                final int centerChunkZ,
                final double totalElapsedSeconds,
                final String threadName,
                final boolean isGlobalRegion,
                final StackTraceElement @Nullable [] stackTrace
            ) {
                final int[] boundsAndCount = collectWatchdogRegionBoundsLockFree(worldName, centerChunkX, centerChunkZ);
                final WatchdogContextContributor.StalledTickContext ctx = new WatchdogContextContributor.StalledTickContext(
                    worldName,
                    centerChunkX,
                    centerChunkZ,
                    totalElapsedSeconds,
                    threadName,
                    isGlobalRegion,
                    stackTrace,
                    boundsAndCount[0],
                    boundsAndCount[1],
                    boundsAndCount[2],
                    boundsAndCount[3],
                    boundsAndCount[4]
                );
                final List<WatchdogDiagnosticsRegistry.PlayerLocationSnapshot> nearbyPlayers =
                    collectWatchdogPlayersLockFree(worldName, centerChunkX, centerChunkZ);
                final List<WatchdogDiagnosticsRegistry.HotChunkSnapshot> hotChunks =
                    collectWatchdogHotChunksLockFree(worldName, centerChunkX, centerChunkZ);
                WatchdogDiagnosticsRegistry.global().emitToLogger(logger, ctx, nearbyPlayers, hotChunks);
            }

            @Override
            public void markBootStart() {
                BootShutdownTelemetry.global().markBootStart();
            }

            @Override
            public void startBootPhase(final String phase) {
                BootShutdownTelemetry.global().startPhase(phase);
            }

            @Override
            public void endBootPhase(final String phase) {
                BootShutdownTelemetry.global().endPhase(phase);
            }

            @Override
            public void recordPluginEnable(final String pluginName, final long durationNanos) {
                BootShutdownTelemetry.global().recordPluginEnable(pluginName, durationNanos);
            }

            @Override
            public void logBootSummary() {
                BootShutdownTelemetry.global().logBootSummary();
                emitStartupSummaryWithAdvisories();
            }

            @Override
            public void markShutdownStart() {
                BootShutdownTelemetry.global().markShutdownStart();
            }

            @Override
            public void logShutdownSummaryAndAudit() {
                final TerrainGenerationAccelerator accel = TerrainGenerationAccelerator.get();
                if (accel instanceof IrOpenClTerrainAccelerator openCl) {
                    openCl.logTelemetrySummary("shutdown");
                }
                WorldgenStageProfiler.GLOBAL.logSummary("shutdown");
                if (ColdChunkSavePolicy.global().isEnabled()) {
                    LOGGER.info("{}", ColdChunkSavePolicy.global().formatSummary());
                }
                final ChunkStreamInterceptor streamInterceptor = ChunkStreamInterceptor.get();
                if (streamInterceptor instanceof LcaNetWireEngine lcaNetEngine) {
                    LOGGER.info("{}", lcaNetEngine.formatTelemetrySummary());
                    lcaNetEngine.close();
                }
                SyntheticPlayerService.global().shutdownAll();
                BootShutdownTelemetry.global().logShutdownSummaryAndAudit();
            }

            @Override
            public void startPluginIoPrefetch(final Path pluginsDir) {
                PluginIoPrefetcher.startGlobalAsync(pluginsDir);
            }

            @Override
            public void drainPluginIoPrefetch() {
                PluginIoPrefetcher.drainGlobalBeforePluginLoad();
            }

            @Override
            public void runPluginPreflight(final List<Path> archives) {
                PluginPreflightDiscoveryHook.runPreflight(archives);
            }
        });
    }

    private static void emitStartupSummaryWithAdvisories() {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        if (cfg == null || cfg.diagnostics == null || !cfg.diagnostics.startupSummaryEnabled) {
            return;
        }
        try {
            final HardwareEnvelope hw = HardwareEnvelope.detect();
            int configuredThreads = -1;
            final GlobalConfiguration globalCfg = GlobalConfiguration.get();
            if (globalCfg != null && globalCfg.threadedRegions != null) {
                configuredThreads = globalCfg.threadedRegions.threads;
            }
            int hopperCheck = 1;
            boolean aggressiveEntityActivation = false;
            boolean hasRecentVerifiedBackup = false;
            final MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                for (final ServerLevel level : server.getAllLevels()) {
                    if (level == null || level.spigotConfig == null) {
                        continue;
                    }
                    hopperCheck = Math.max(hopperCheck, level.spigotConfig.hopperCheck);
                    if (level.spigotConfig.monsterActivationRange < 24
                        || level.spigotConfig.animalActivationRange < 24) {
                        aggressiveEntityActivation = true;
                    }
                }
                final Path serverRoot = server.getServerDirectory();
                if (serverRoot != null) {
                    hasRecentVerifiedBackup = Files.isDirectory(serverRoot.resolve("backups"))
                        || Files.isDirectory(serverRoot.resolve("world-backup"));
                }
            }
            final int resolvedThreads = TickThreadAutoTuner.resolveTickThreads(configuredThreads, cfg, hw);
            final PluginPreflightDiscoveryHook.PreflightReport report = PluginPreflightDiscoveryHook.lastReport();
            final int safeCount = report != null ? report.safeCount() : 0;
            final int warnCount = report != null ? report.warnCount() : 0;
            final int hazardCount = report != null ? report.hazardCount() : 0;
            final GoldLeafStartupSummary.SummaryInput summaryInput = new GoldLeafStartupSummary.SummaryInput(
                hw,
                configuredThreads,
                resolvedThreads,
                cfg.storage.format,
                "mode-b".equalsIgnoreCase(cfg.storage.lcaMode)
                    || "archival".equalsIgnoreCase(cfg.storage.lcaMode)
                    || cfg.storage.lcaModeBLcd2,
                safeCount,
                warnCount,
                hazardCount
            );
            final List<HardwareConfigAdvisor.Advisory> advisories = HardwareConfigAdvisor.evaluate(
                new HardwareConfigAdvisor.EvaluationInput(
                    hw,
                    cfg,
                    configuredThreads,
                    hopperCheck,
                    aggressiveEntityActivation,
                    hasRecentVerifiedBackup
                )
            );
            LOGGER.info("\n{}", GoldLeafStartupSummary.formatWithAdvisories(summaryInput, advisories));
        } catch (final Throwable t) {
            LOGGER.warn("Failed to emit GoldLeaf startup summary", t);
        }
    }

    private static @Nullable List<WatchdogDiagnosticsRegistry.PlayerLocationSnapshot> collectWatchdogPlayersLockFree(
        final @Nullable String worldName,
        final int centerChunkX,
        final int centerChunkZ
    ) {
        try {
            final MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.getPlayerList() == null) {
                return null;
            }
            final int maxPlayers = Math.max(1, GoldLeafConfig.get().diagnostics.watchdogMaxPlayers);
            final long centerBlockX = ((long) centerChunkX << 4) + 8L;
            final long centerBlockZ = ((long) centerChunkZ << 4) + 8L;
            final List<WatchdogDiagnosticsRegistry.PlayerLocationSnapshot> candidates = new ArrayList<>();
            for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player == null) {
                    continue;
                }
                final ServerLevel level = player.level();
                if (worldName != null && level != null && level.getWorld() != null
                    && !worldName.equals(level.getWorld().getName())) {
                    continue;
                }
                candidates.add(new WatchdogDiagnosticsRegistry.PlayerLocationSnapshot(
                    player.getScoreboardName(),
                    player.getBlockX(),
                    player.getBlockY(),
                    player.getBlockZ()
                ));
            }
            if (candidates.isEmpty()) {
                return null;
            }
            candidates.sort(Comparator.comparingLong(p -> {
                final long dx = p.blockX() - centerBlockX;
                final long dz = p.blockZ() - centerBlockZ;
                return dx * dx + dz * dz;
            }));
            return candidates.size() <= maxPlayers ? candidates : candidates.subList(0, maxPlayers);
        } catch (final Throwable t) {
            return null;
        }
    }

    private static int[] collectWatchdogRegionBoundsLockFree(
        final @Nullable String worldName,
        final int centerChunkX,
        final int centerChunkZ
    ) {
        final int[] fallback = new int[] {centerChunkX, centerChunkX, centerChunkZ, centerChunkZ, -1};
        try {
            final MinecraftServer server = MinecraftServer.getServer();
            if (server == null || worldName == null) {
                return fallback;
            }
            ServerLevel targetLevel = null;
            for (final ServerLevel level : server.getAllLevels()) {
                if (level.getWorld() != null && worldName.equals(level.getWorld().getName())) {
                    targetLevel = level;
                    break;
                }
            }
            if (targetLevel == null) {
                return fallback;
            }
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            int loaded = 0;
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    final int cx = centerChunkX + dx;
                    final int cz = centerChunkZ + dz;
                    if (targetLevel.getChunkIfLoadedImmediately(cx, cz) != null) {
                        loaded++;
                        if (cx < minX) minX = cx;
                        if (cx > maxX) maxX = cx;
                        if (cz < minZ) minZ = cz;
                        if (cz > maxZ) maxZ = cz;
                    }
                }
            }
            if (loaded == 0) {
                return new int[] {centerChunkX, centerChunkX, centerChunkZ, centerChunkZ, 0};
            }
            return new int[] {minX, maxX, minZ, maxZ, loaded};
        } catch (final Throwable t) {
            return fallback;
        }
    }

    private static @Nullable List<WatchdogDiagnosticsRegistry.HotChunkSnapshot> collectWatchdogHotChunksLockFree(
        final @Nullable String worldName,
        final int centerChunkX,
        final int centerChunkZ
    ) {
        try {
            final MinecraftServer server = MinecraftServer.getServer();
            if (server == null || worldName == null) {
                return null;
            }
            ServerLevel targetLevel = null;
            for (final ServerLevel level : server.getAllLevels()) {
                if (level.getWorld() != null && worldName.equals(level.getWorld().getName())) {
                    targetLevel = level;
                    break;
                }
            }
            if (targetLevel == null) {
                return null;
            }
            final List<WatchdogDiagnosticsRegistry.HotChunkSnapshot> out = new ArrayList<>();
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    final ChunkAccess rawChunk = targetLevel.getChunkIfLoadedImmediately(centerChunkX + dx, centerChunkZ + dz);
                    if (!(rawChunk instanceof LevelChunk chunk)) {
                        continue;
                    }
                    final ChunkActivityCounters counters = chunk.paper$getActivityCountersIfPresent();
                    if (counters == null) {
                        continue;
                    }
                    final long entityTicks = counters.entityTicks();
                    final long blockEntityTicks = counters.hopperTransfers() + counters.pistonMoves();
                    final long redstone = counters.redstoneUpdates();
                    final long fluid = counters.blockGrowths();
                    if (entityTicks + blockEntityTicks + redstone + fluid > 0L) {
                        out.add(new WatchdogDiagnosticsRegistry.HotChunkSnapshot(
                            centerChunkX + dx,
                            centerChunkZ + dz,
                            entityTicks,
                            blockEntityTicks,
                            redstone,
                            fluid
                        ));
                    }
                }
            }
            if (out.isEmpty()) {
                return null;
            }
            out.sort(Comparator.comparingLong(
                WatchdogDiagnosticsRegistry.HotChunkSnapshot::totalScore
            ).reversed());
            return out.size() <= 10 ? out : out.subList(0, 10);
        } catch (final Throwable t) {
            return null;
        }
    }
}
