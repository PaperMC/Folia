package dev.leafcraft.goldleaf;

import ca.spottedleaf.moonrise.spi.ChunkLoadBackpressureSpi;
import ca.spottedleaf.moonrise.spi.ChunkResidencySpi;
import ca.spottedleaf.moonrise.spi.DirectSectionSerializationSpi;
import ca.spottedleaf.moonrise.spi.RegionStorageSpi;
import dev.leafcraft.goldleaf.bot.FoliaSyntheticPlayerBridge;
import dev.leafcraft.goldleaf.bot.SyntheticPlayerService;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.config.HardwareConfigAdvisor;
import dev.leafcraft.goldleaf.config.HardwareEnvelope;
import dev.leafcraft.goldleaf.diagnostics.BootShutdownTelemetry;
import dev.leafcraft.goldleaf.diagnostics.BytecodeFoliaPreflightProvider;
import dev.leafcraft.goldleaf.diagnostics.GoldLeafStartupSummary;
import dev.leafcraft.goldleaf.diagnostics.PluginIoPrefetcher;
import dev.leafcraft.goldleaf.diagnostics.PluginPreflightDiscoveryHook;
import dev.leafcraft.goldleaf.diagnostics.RegionHealthSnapshot;
import dev.leafcraft.goldleaf.diagnostics.TickHazardMonitor;
import dev.leafcraft.goldleaf.diagnostics.WatchdogContextContributor;
import dev.leafcraft.goldleaf.diagnostics.WatchdogDiagnosticsRegistry;
import dev.leafcraft.goldleaf.network.BroadcastPacketEncoder;
import dev.leafcraft.goldleaf.network.ChunkStreamInterceptor;
import dev.leafcraft.goldleaf.network.DefaultOffTickChunkPacketController;
import dev.leafcraft.goldleaf.network.LcaNetWireEngine;
import dev.leafcraft.goldleaf.network.OffTickChunkPacketController;
import dev.leafcraft.goldleaf.network.SharedBroadcastPacketEncoder;
import dev.leafcraft.goldleaf.protocol.BuiltInTechnicalProtocolHandlers;
import dev.leafcraft.goldleaf.protocol.TechnicalProtocolRegistry;
import dev.leafcraft.goldleaf.scheduler.GoldLeafPredictPacer;
import dev.leafcraft.goldleaf.scheduler.LshdRemoteShardRpcClient;
import dev.leafcraft.goldleaf.scheduler.RemoteShardChunkRpcClient;
import dev.leafcraft.goldleaf.scheduler.TieredChunkReadRouter;
import dev.leafcraft.goldleaf.security.ChunkLoadBackpressurePolicy;
import dev.leafcraft.goldleaf.security.DecodeTimePacketGuard;
import dev.leafcraft.goldleaf.storage.LcaChunkResidencyCache;
import dev.leafcraft.goldleaf.storage.LcaDirectSectionSerializer;
import dev.leafcraft.goldleaf.storage.LcaRegionStorageProvider;
import dev.leafcraft.goldleaf.storage.LcaResidencyManager;
import dev.leafcraft.goldleaf.storage.RegionStorageProvider;
import dev.leafcraft.goldleaf.telemetry.ChunkExecutionMask;
import dev.leafcraft.goldleaf.telemetry.ChunkTickTelemetry;
import dev.leafcraft.goldleaf.telemetry.GoldLeafMetricsCollector;
import dev.leafcraft.goldleaf.threading.RegionShellPool;
import dev.leafcraft.goldleaf.threading.TickThreadAutoTuner;
import dev.leafcraft.goldleaf.worldgen.SectionBatchedTerrainFill;
import dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator;
import dev.leafcraft.goldleaf.worldgen.WorldgenWaveTelemetry;
import dev.leafcraft.goldleaf.worldgen.WorldgenWorkerPacer;
import io.papermc.paper.spi.PaperDiagnosticsSpi;
import io.papermc.paper.spi.PaperNetworkSpi;
import io.papermc.paper.spi.PaperTelemetrySpi;
import io.papermc.paper.spi.PaperThreadingSpi;
import io.papermc.paper.spi.PaperWorldgenSpi;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegions;
import io.papermc.paper.threadedregions.scheduler.AsyncReadRouter;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Layer 2 GoldLeaf bootstrap that loads {@code config/goldleaf.yml} and wires all GoldLeaf
 * engines and policies into the upstream-clean Layer 1 {@code io.papermc.paper.spi.*} and
 * {@code ca.spottedleaf.moonrise.spi.*} registries ({@code goldleaf-ADR-001} Decision §1).
 */
public final class GoldLeafBootstrap {

    private static volatile boolean registered = false;
    private static volatile @Nullable TieredChunkReadRouter tieredChunkReadRouter;
    private static volatile @Nullable LcaChunkResidencyCache haloResidencyCache;
    private static volatile @Nullable RemoteShardChunkRpcClient remoteShardRpcClient;
    private static volatile LshdRemoteShardRpcClient.@Nullable RemoteShardLocator remoteShardLocator;
    private static volatile @Nullable BiConsumer<String, byte[]> remoteShardFrameSender;
    private static volatile @Nullable RegionStorageProvider coldStorageProvider;

    private GoldLeafBootstrap() {
    }

    /**
     * Initializes {@link GoldLeafConfig} from {@code configDir} and binds all Layer 2 engines
     * into Layer 1 SPIs. Safe to invoke on reload as well as initial boot.
     */
    public static GoldLeafConfig init(final Path configDir) throws org.spongepowered.configurate.ConfigurateException {
        final GoldLeafConfig config = GoldLeafConfig.init(configDir);
        registerAllLayer2Providers(config);
        return config;
    }

    /**
     * Registers all Layer 2 providers into Layer 1 SPIs using the currently loaded
     * {@link GoldLeafConfig} (idempotent).
     */
    public static void registerAllLayer2Providers(final GoldLeafConfig config) {
        // Stage 1 & 3: Threading, workstation auto-tuner, worldgen worker pacing, shell pooling
        PaperThreadingSpi.register(new PaperThreadingSpi.Provider() {
            @Override
            public int resolveTickThreads(final int configuredThreads) {
                return TickThreadAutoTuner.resolveTickThreads(
                    configuredThreads,
                    GoldLeafConfig.get(),
                    HardwareEnvelope.detect()
                );
            }

            @Override
            public int resolveWorldgenWorkerThreads(final int configuredWorkerThreads) {
                return WorldgenWorkerPacer.resolveWorkerThreads(
                    configuredWorkerThreads,
                    GoldLeafConfig.get(),
                    HardwareEnvelope.detect()
                );
            }

            @Override
            public RegionizedWorldData acquireRegionShell(
                final ServerLevel world,
                final TickRegions.TickRegionData regionData
            ) {
                return RegionShellPool.acquire(world, regionData);
            }

            @Override
            public void releaseRegionShell(final RegionizedWorldData worldData) {
                RegionShellPool.release(worldData);
            }
        });

        // Stage 2 Tier 1 & Tier 2: Native .lca storage, DirectSectionSerializer, LcaResidencyManager
        RegionStorageSpi.registerFactory((info, folder) -> {
            final GoldLeafConfig cfg = GoldLeafConfig.get();
            if (cfg == null || cfg.storage == null) {
                return null;
            }
            if (dev.leafcraft.goldleaf.storage.RegionStorageProvider.shouldUseLcaForFolder(folder, cfg.storage)) {
                final String worldId = (info != null && info.dimension() != null)
                    ? info.dimension().identifier().toString()
                    : null;
                return LcaRegionStorageProvider.open(folder, cfg.storage, worldId);
            }
            return null;
        });
        if (config != null && config.storage != null && "lca".equalsIgnoreCase(config.storage.format)) {
            DirectSectionSerializationSpi.register(LcaDirectSectionSerializer.createDefault());
            ChunkResidencySpi.register(LcaResidencyManager.global());
            ca.spottedleaf.moonrise.spi.SectionLifecycleSpi.register(
                dev.leafcraft.goldleaf.storage.GoldLeafSectionLifecycleManager.global()
            );
        } else {
            DirectSectionSerializationSpi.resetDefault();
            ChunkResidencySpi.resetDefault();
            ca.spottedleaf.moonrise.spi.SectionLifecycleSpi.resetDefault();
        }
        ca.spottedleaf.moonrise.spi.LazyEntityHydrationSpi.register((world, chunkX, chunkZ) -> {
            final GoldLeafConfig cfg = GoldLeafConfig.get();
            return cfg != null && cfg.storage != null && cfg.storage.lazyEntityHydration;
        });

        // Stage 3: Worldgen acceleration & section-batched doFill
        PaperWorldgenSpi.register(new PaperWorldgenSpi.Provider() {
            @Override
            public boolean tryAccelerateBiomes(
                final WorldGenContext context,
                final ServerLevel level,
                final ChunkGenerator generator,
                final RandomState randomState,
                final Blender blender,
                final StructureManager structureManager,
                final ChunkAccess chunk
            ) {
                final GoldLeafConfig glConfig = GoldLeafConfig.get();
                final boolean requireAccel = glConfig != null && glConfig.worldgen != null && glConfig.worldgen.requireAccelerator;
                if (!requireAccel && !TerrainGenerationAccelerator.isActive()) {
                    return false;
                }
                final TerrainGenerationAccelerator.BiomeTask biomeTask = new TerrainGenerationAccelerator.BiomeTask(
                    level, generator, randomState, blender, structureManager, chunk
                );
                return TerrainGenerationAccelerator.dispatchSingleBiome(
                    context, biomeTask, requireAccel, WorldgenWaveTelemetry.GLOBAL
                );
            }

            @Override
            public boolean tryAccelerateTerrain(
                final ServerLevel level,
                final NoiseBasedChunkGenerator generator,
                final DensityVolume volume,
                final NoiseGeneratorSettings settings,
                final RandomState randomState,
                final StructureManager structureManager,
                final Blender blender,
                final ChunkAccess chunk
            ) {
                final GoldLeafConfig glConfig = GoldLeafConfig.get();
                final boolean requireAccel = glConfig != null && glConfig.worldgen != null && glConfig.worldgen.requireAccelerator;
                if (!requireAccel && !TerrainGenerationAccelerator.isActive()) {
                    return false;
                }
                final TerrainGenerationAccelerator.TerrainTask terrainTask = new TerrainGenerationAccelerator.TerrainTask(
                    level,
                    generator,
                    volume,
                    settings.noiseRouter(),
                    settings,
                    randomState,
                    Beardifier.forStructuresInChunk(structureManager, chunk.getPos()),
                    blender,
                    chunk
                );
                return TerrainGenerationAccelerator.dispatchSingleTerrain(
                    null, terrainTask, requireAccel, WorldgenWaveTelemetry.GLOBAL
                );
            }

            @Override
            public boolean tryFillChunkBatched(
                final NoiseChunk noiseChunk,
                final ChunkAccess chunk,
                final ScopedDensityBuffer densityBuffer,
                final BlockState defaultBlock,
                final BlockState airState,
                final PaperWorldgenSpi.DebugStateTransform debugTransform
            ) {
                final GoldLeafConfig glConfig = GoldLeafConfig.get();
                if (glConfig != null && glConfig.worldgen != null && !glConfig.worldgen.sectionBatchedDoFill) {
                    return false;
                }
                SectionBatchedTerrainFill.fillChunk(
                    noiseChunk,
                    chunk,
                    densityBuffer,
                    defaultBlock,
                    airState,
                    debugTransform::apply
                );
                return true;
            }
        });

        // Stage 5: Diagnostics, bytecode-api preflight, boot telemetry, watchdog, hazards
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
                final WatchdogContextContributor.StalledTickContext ctx = new WatchdogContextContributor.StalledTickContext(
                    worldName, centerChunkX, centerChunkZ, totalElapsedSeconds, threadName, isGlobalRegion, stackTrace
                );
                final List<WatchdogDiagnosticsRegistry.PlayerLocationSnapshot> nearbyPlayers = collectWatchdogPlayersLockFree(worldName);
                final List<WatchdogDiagnosticsRegistry.HotChunkSnapshot> hotChunks = collectWatchdogHotChunksLockFree(worldName, centerChunkX, centerChunkZ);
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

        // Stage 5: Network, LCA_NET_V1 wire engine, backpressure, protocols, decode-time NBT guard
        if (BroadcastPacketEncoder.get() == null) {
            BroadcastPacketEncoder.register(new SharedBroadcastPacketEncoder());
        }
        if (OffTickChunkPacketController.get() == null) {
            OffTickChunkPacketController.register(new DefaultOffTickChunkPacketController());
        }
        if (config != null && config.network != null && config.network.lcaNetV1Enabled) {
            if (ChunkStreamInterceptor.get() == null) {
                ChunkStreamInterceptor.register(LcaNetWireEngine.fromConfig(
                    LcaResidencyManager.global().cacheForWorld(LcaResidencyManager.DEFAULT_WORLD_ID),
                    LcaResidencyManager.global(),
                    config.network,
                    0L,
                    null,
                    null
                ));
            }
        }
        BuiltInTechnicalProtocolHandlers.syncFromConfig(
            TechnicalProtocolRegistry.global(),
            config != null ? config.protocols : null
        );
        ChunkLoadBackpressureSpi.register(new ChunkLoadBackpressureSpi() {
            @Override
            public long evaluate(
                final UUID playerId,
                final int pending,
                final long baseMaxGens,
                final int sendDistance,
                final long nowNanos
            ) {
                return ChunkLoadBackpressurePolicy.get()
                    .evaluate(playerId, pending, baseMaxGens, sendDistance, nowNanos)
                    .maxGenerationsThisTick();
            }

            @Override
            public int evaluateSendDistance(
                final UUID playerId,
                final int pending,
                final long baseMaxGens,
                final int sendDistance,
                final long nowNanos
            ) {
                return ChunkLoadBackpressurePolicy.get()
                    .evaluate(playerId, pending, baseMaxGens, sendDistance, nowNanos)
                    .effectiveSendDistance();
            }

            @Override
            public void recordNewChunkGenerations(final UUID playerId, final int generatedCount, final long nowNanos) {
                ChunkLoadBackpressurePolicy.get().recordNewChunkGenerations(playerId, generatedCount, nowNanos);
            }
        });
        PaperNetworkSpi.register(new PaperNetworkSpi.Provider() {
            @Override
            public NbtAccounter createServerboundNbtAccounter() {
                return DecodeTimePacketGuard.createServerboundNbtAccounter();
            }

            @Override
            public boolean isEntityTrackerAllocationFreePurgeEnabled() {
                return GoldLeafConfig.get().network.entityTrackerAllocationFreePurge;
            }

            @Override
            public boolean tryBroadcast(
                final Entity entity,
                final Set<ServerPlayerConnection> seenBy,
                final Packet<? super ClientGamePacketListener> packet
            ) {
                final BroadcastPacketEncoder encoder = BroadcastPacketEncoder.get();
                return encoder != null && encoder.tryBroadcast(entity, seenBy, packet);
            }

            @Override
            public boolean trySendChunk(
                final ServerGamePacketListenerImpl connection,
                final ServerLevel level,
                final LevelChunk chunk,
                final boolean shouldModify
            ) {
                final ChunkStreamInterceptor streamInterceptor = ChunkStreamInterceptor.get();
                if (streamInterceptor != null
                    && streamInterceptor.supportsConnection(connection)
                    && streamInterceptor.trySendChunk(connection, level, chunk, shouldModify)) {
                    return true;
                }
                final OffTickChunkPacketController offTickController = OffTickChunkPacketController.get();
                return offTickController != null
                    && GoldLeafConfig.get().network.antiXrayOffTickEncoding
                    && offTickController.trySendOffTick(connection, level, chunk, shouldModify);
            }

            @Override
            public void dispatchTechnicalProtocol(
                final UUID playerId,
                final String channel,
                final byte[] data,
                final BiPredicate<UUID, String> permissionCheck
            ) {
                final TechnicalProtocolRegistry registry = TechnicalProtocolRegistry.global();
                if (!registry.isEmpty()) {
                    registry.dispatchIncoming(playerId, channel, data, permissionCheck);
                }
            }

            @Override
            public void onPlayerJoin(final UUID playerId) {
                final TechnicalProtocolRegistry registry = TechnicalProtocolRegistry.global();
                if (!registry.isEmpty()) {
                    registry.onPlayerJoin(playerId);
                }
            }

            @Override
            public void onChannelRegister(final UUID playerId, final Set<String> channels) {
                final TechnicalProtocolRegistry registry = TechnicalProtocolRegistry.global();
                if (!registry.isEmpty() && channels != null) {
                    for (final String channel : channels) {
                        registry.onChannelRegister(playerId, channel);
                    }
                }
            }

            @Override
            public void onPlayerTick(final UUID playerId, final long tickCount) {
                final TechnicalProtocolRegistry registry = TechnicalProtocolRegistry.global();
                if (!registry.isEmpty()) {
                    registry.onPlayerTick(playerId);
                }
            }

            @Override
            public void onPlayerQuit(final UUID playerId) {
                TechnicalProtocolRegistry.global().onPlayerQuit(playerId);
                ChunkLoadBackpressurePolicy.get().onPlayerQuit(playerId);
                DecodeTimePacketGuard.global().onPlayerQuit(playerId);
                RegionHealthSnapshot.global().onPlayerQuit(playerId);
                SyntheticPlayerService.global().onOwnerQuit(playerId);
            }

            @Override
            public int validateBookEdit(final UUID playerId, final List<String> pages) {
                final DecodeTimePacketGuard.ValidationDecision decision = DecodeTimePacketGuard.global().validateBookPayload(
                    playerId,
                    pages,
                    System.currentTimeMillis() / 1000L
                );
                if (decision.accepted()) {
                    return 0;
                }
                return decision.shouldDisconnect() ? 2 : 1;
            }

            @Override
            public boolean isChatSigningDisabled() {
                final GoldLeafConfig cfg = GoldLeafConfig.get();
                return cfg != null && cfg.security != null && cfg.security.disableChatSigning;
            }
        });

        // Stage 5: Telemetry & execution masks
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
                final dev.leafcraft.goldleaf.telemetry.ChunkTickPhase[] phases = dev.leafcraft.goldleaf.telemetry.ChunkTickPhase.values();
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
                final long nowNanos = System.nanoTime();
                final GoldLeafPredictPacer pacer = GoldLeafPredictPacer.global();
                pacer.evaluateLaneBudgetNanos(GoldLeafPredictPacer.WorkLane.OFF_TICK_LCA_IO, msptMs, 40.0, 0L, false, nowNanos);
                pacer.evaluateLaneBudgetNanos(GoldLeafPredictPacer.WorkLane.CHUNK_GEN_DISPATCH, msptMs, 40.0, 0L, false, nowNanos);
                pacer.evaluateLaneBudgetNanos(GoldLeafPredictPacer.WorkLane.PACKET_FLUSH, msptMs, 40.0, 0L, false, nowNanos);
                pacer.evaluateLaneBudgetNanos(GoldLeafPredictPacer.WorkLane.SHARD_MIGRATION, msptMs, 40.0, 0L, false, nowNanos);
            }
        });
        io.papermc.paper.telemetry.ChunkTelemetryService.register(
            dev.leafcraft.goldleaf.telemetry.GoldLeafChunkTelemetryService.instance()
        );

        // Stage 5: Synthetic players / bots Folia region & lifecycle bridge (ADR-015)
        FoliaSyntheticPlayerBridge.install(SyntheticPlayerService.global());

        // Stage 2: Latency-tiered AsyncReadRouter wiring (goldleaf-ADR-002 §2)
        rewireTieredReadRouter(config);

        // Ensure predict-api pacer and metrics-api collector singletons are initialized
        GoldLeafPredictPacer.global();
        GoldLeafMetricsCollector.global();
        registered = true;
    }

    /**
     * Attaches or replaces the Tier 4 {@code LSHD} remote shard locator/transport and rewires
     * {@link TieredChunkReadRouter} using the current {@link GoldLeafConfig.Network} settings.
     */
    public static void configureTier4RemoteShardTransport(
        final LshdRemoteShardRpcClient.@Nullable RemoteShardLocator locator,
        final @Nullable BiConsumer<String, byte[]> frameSender
    ) {
        remoteShardLocator = locator;
        remoteShardFrameSender = frameSender;
        rewireTieredReadRouter(GoldLeafConfig.get());
    }

    /**
     * Attaches an optional local {@code LCAS} halo cache (Tier 0) or cold storage provider (Tier 3)
     * and rewires {@link TieredChunkReadRouter}.
     */
    public static void configureReadRouterBackings(
        final @Nullable LcaChunkResidencyCache haloCache,
        final @Nullable RegionStorageProvider coldStorage
    ) {
        haloResidencyCache = haloCache;
        coldStorageProvider = coldStorage;
        rewireTieredReadRouter(GoldLeafConfig.get());
    }

    private static void rewireTieredReadRouter(final @Nullable GoldLeafConfig config) {
        final RemoteShardChunkRpcClient previousRpc = remoteShardRpcClient;
        RemoteShardChunkRpcClient nextRpc = null;
        if (config != null && config.network != null
            && remoteShardLocator != null && remoteShardFrameSender != null) {
            nextRpc = LshdRemoteShardRpcClient.fromConfig(
                config.network,
                remoteShardLocator,
                remoteShardFrameSender
            );
        }
        remoteShardRpcClient = nextRpc;
        if (previousRpc instanceof LshdRemoteShardRpcClient oldClient && oldClient != nextRpc) {
            oldClient.close();
        }
        final LcaChunkResidencyCache defaultCache = LcaResidencyManager.global().cacheForWorld(LcaResidencyManager.DEFAULT_WORLD_ID);
        final TieredChunkReadRouter router = new TieredChunkReadRouter(
            defaultCache,
            haloResidencyCache,
            coldStorageProvider,
            nextRpc
        );
        tieredChunkReadRouter = router;
        AsyncReadRouter.register(router);
    }

    public static @Nullable TieredChunkReadRouter getTieredChunkReadRouter() {
        return tieredChunkReadRouter;
    }

    public static @Nullable RemoteShardChunkRpcClient getRemoteShardRpcClient() {
        return remoteShardRpcClient;
    }

    public static boolean isRegistered() {
        return registered;
    }

    private static void emitStartupSummaryWithAdvisories() {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        if (cfg == null || cfg.diagnostics == null || !cfg.diagnostics.startupSummaryEnabled) {
            return;
        }
        try {
            final HardwareEnvelope hw = HardwareEnvelope.detect();
            final int configuredThreads = -1;
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
                "mode-b".equalsIgnoreCase(cfg.storage.lcaMode),
                safeCount,
                warnCount,
                hazardCount
            );
            final List<HardwareConfigAdvisor.Advisory> advisories = HardwareConfigAdvisor.evaluate(
                new HardwareConfigAdvisor.EvaluationInput(
                    hw,
                    cfg,
                    configuredThreads,
                    1,
                    false,
                    false
                )
            );
            org.slf4j.LoggerFactory.getLogger(GoldLeafBootstrap.class).info(
                "\n{}",
                GoldLeafStartupSummary.formatWithAdvisories(summaryInput, advisories)
            );
        } catch (final Throwable t) {
            org.slf4j.LoggerFactory.getLogger(GoldLeafBootstrap.class).warn(
                "Failed to emit GoldLeaf startup summary",
                t
            );
        }
    }

    private static @Nullable List<WatchdogDiagnosticsRegistry.PlayerLocationSnapshot> collectWatchdogPlayersLockFree(
        final @Nullable String worldName
    ) {
        try {
            final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            if (server == null || server.getPlayerList() == null) {
                return null;
            }
            final int maxPlayers = Math.max(1, GoldLeafConfig.get().diagnostics.watchdogMaxPlayers);
            final List<WatchdogDiagnosticsRegistry.PlayerLocationSnapshot> out = new java.util.ArrayList<>(Math.min(maxPlayers, 16));
            for (final net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (out.size() >= maxPlayers) {
                    break;
                }
                if (player == null) {
                    continue;
                }
                final ServerLevel level = player.level();
                if (worldName != null && level != null && level.getWorld() != null
                    && !worldName.equals(level.getWorld().getName())) {
                    continue;
                }
                out.add(new WatchdogDiagnosticsRegistry.PlayerLocationSnapshot(
                    player.getScoreboardName(),
                    player.getBlockX(),
                    player.getBlockY(),
                    player.getBlockZ()
                ));
            }
            return out.isEmpty() ? null : out;
        } catch (final Throwable t) {
            return null;
        }
    }

    private static @Nullable List<WatchdogDiagnosticsRegistry.HotChunkSnapshot> collectWatchdogHotChunksLockFree(
        final @Nullable String worldName,
        final int centerChunkX,
        final int centerChunkZ
    ) {
        try {
            final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
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
            final List<WatchdogDiagnosticsRegistry.HotChunkSnapshot> out = new java.util.ArrayList<>();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    final ChunkAccess rawChunk = targetLevel.getChunkIfLoadedImmediately(centerChunkX + dx, centerChunkZ + dz);
                    if (!(rawChunk instanceof LevelChunk chunk)) {
                        continue;
                    }
                    final io.papermc.paper.spi.ChunkActivityCounters counters = chunk.paper$getActivityCountersIfPresent();
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
            return out.isEmpty() ? null : out;
        } catch (final Throwable t) {
            return null;
        }
    }
}
