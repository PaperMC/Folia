package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.spi.ChunkResidencySpi;
import ca.spottedleaf.moonrise.spi.DirectSectionSerializationSpi;
import ca.spottedleaf.moonrise.spi.LazyEntityHydrationSpi;
import ca.spottedleaf.moonrise.spi.RegionStorageSpi;
import ca.spottedleaf.moonrise.spi.SectionLifecycleSpi;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.scheduler.LshdRemoteShardRpcClient;
import dev.leafcraft.goldleaf.scheduler.RemoteShardChunkRpcClient;
import dev.leafcraft.goldleaf.scheduler.TieredChunkReadRouter;
import io.papermc.paper.threadedregions.scheduler.AsyncReadRouter;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 bootstrap registrar for native {@code .lca} storage, direct section serialization,
 * residency management, lazy entity hydration, and latency-tiered {@link AsyncReadRouter}
 * wiring ({@code goldleaf-ADR-001}, {@code goldleaf-ADR-002} §2).
 */
public final class StorageBootstrapRegistrar {

    private static volatile @Nullable TieredChunkReadRouter tieredChunkReadRouter;
    private static volatile @Nullable LcaChunkResidencyCache haloResidencyCache;
    private static volatile @Nullable RemoteShardChunkRpcClient remoteShardRpcClient;
    private static volatile LshdRemoteShardRpcClient.@Nullable RemoteShardLocator remoteShardLocator;
    private static volatile @Nullable BiConsumer<String, byte[]> remoteShardFrameSender;
    private static volatile @Nullable RegionStorageProvider coldStorageProvider;

    private StorageBootstrapRegistrar() {
    }

    public static void registerStorageProviders(final @Nullable GoldLeafConfig config) {
        RegionStorageSpi.registerFactory((info, folder) -> {
            final GoldLeafConfig cfg = GoldLeafConfig.get();
            if (cfg == null || cfg.storage == null) {
                return null;
            }
            if (RegionStorageProvider.shouldUseLcaForFolder(folder, cfg.storage)) {
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
            SectionLifecycleSpi.register(GoldLeafSectionLifecycleManager.global());
        } else {
            DirectSectionSerializationSpi.resetDefault();
            ChunkResidencySpi.resetDefault();
            SectionLifecycleSpi.resetDefault();
        }
        LazyEntityHydrationSpi.register((world, chunkX, chunkZ) -> {
            final GoldLeafConfig cfg = GoldLeafConfig.get();
            return cfg != null && cfg.storage != null && cfg.storage.lazyEntityHydration;
        });
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

    public static void rewireTieredReadRouter(final @Nullable GoldLeafConfig config) {
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
}
