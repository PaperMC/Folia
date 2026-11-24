package dev.leafcraft.goldleaf;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.diagnostics.DiagnosticsBootstrapRegistrar;
import dev.leafcraft.goldleaf.network.NetworkBootstrapRegistrar;
import dev.leafcraft.goldleaf.scheduler.LshdRemoteShardRpcClient;
import dev.leafcraft.goldleaf.scheduler.RemoteShardChunkRpcClient;
import dev.leafcraft.goldleaf.scheduler.TieredChunkReadRouter;
import dev.leafcraft.goldleaf.storage.LcaChunkResidencyCache;
import dev.leafcraft.goldleaf.storage.RegionStorageProvider;
import dev.leafcraft.goldleaf.storage.StorageBootstrapRegistrar;
import dev.leafcraft.goldleaf.telemetry.TelemetryAndServicesBootstrapRegistrar;
import dev.leafcraft.goldleaf.threading.ThreadingBootstrapRegistrar;
import dev.leafcraft.goldleaf.worldgen.WorldgenBootstrapRegistrar;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 GoldLeaf bootstrap that loads {@code config/goldleaf.yml} and wires all GoldLeaf
 * engines and policies into the upstream-clean Layer 1 {@code io.papermc.paper.spi.*} and
 * {@code ca.spottedleaf.moonrise.spi.*} registries ({@code goldleaf-ADR-001} Decision §1).
 */
public final class GoldLeafBootstrap {

    private static volatile boolean registered = false;

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
        ThreadingBootstrapRegistrar.register(config);
        StorageBootstrapRegistrar.registerStorageProviders(config);
        WorldgenBootstrapRegistrar.register(config);
        DiagnosticsBootstrapRegistrar.register(config);
        NetworkBootstrapRegistrar.register(config);
        TelemetryAndServicesBootstrapRegistrar.register(config);
        StorageBootstrapRegistrar.rewireTieredReadRouter(config);
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
        StorageBootstrapRegistrar.configureTier4RemoteShardTransport(locator, frameSender);
    }

    /**
     * Attaches an optional local {@code LCAS} halo cache (Tier 0) or cold storage provider (Tier 3)
     * and rewires {@link TieredChunkReadRouter}.
     */
    public static void configureReadRouterBackings(
        final @Nullable LcaChunkResidencyCache haloCache,
        final @Nullable RegionStorageProvider coldStorage
    ) {
        StorageBootstrapRegistrar.configureReadRouterBackings(haloCache, coldStorage);
    }

    public static @Nullable TieredChunkReadRouter getTieredChunkReadRouter() {
        return StorageBootstrapRegistrar.getTieredChunkReadRouter();
    }

    public static @Nullable RemoteShardChunkRpcClient getRemoteShardRpcClient() {
        return StorageBootstrapRegistrar.getRemoteShardRpcClient();
    }

    public static boolean isRegistered() {
        return registered;
    }
}
