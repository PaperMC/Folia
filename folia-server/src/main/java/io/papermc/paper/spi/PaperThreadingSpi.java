package io.papermc.paper.spi;

import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate threading, workstation auto-tuner, and region-shell pooling SPI.
 *
 * <p>Zero product or config coupling: provides safe upstream defaults (including workstation-aware
 * 4–8 core tick-thread auto-tuning) while allowing Layer 2 providers to override policies at boot.</p>
 */
public final class PaperThreadingSpi {

    public interface Provider {
        default int resolveTickThreads(final int configuredThreads) {
            return defaultResolveTickThreads(configuredThreads);
        }

        default int resolveWorldgenWorkerThreads(final int configuredWorkerThreads) {
            return defaultResolveWorldgenWorkerThreads(configuredWorkerThreads);
        }

        default RegionizedWorldData acquireRegionShell(
            final ServerLevel world,
            final TickRegions.TickRegionData regionData
        ) {
            return new RegionizedWorldData(world, regionData);
        }

        default void releaseRegionShell(final RegionizedWorldData worldData) {
        }
    }

    private static volatile @Nullable Provider provider;

    private PaperThreadingSpi() {
    }

    public static void register(final @Nullable Provider p) {
        provider = p;
    }

    public static @Nullable Provider get() {
        return provider;
    }

    public static void resetDefault() {
        provider = null;
    }

    public static int resolveTickThreads(final int configuredThreads) {
        final Provider p = provider;
        return p != null ? p.resolveTickThreads(configuredThreads) : defaultResolveTickThreads(configuredThreads);
    }

    public static int resolveWorldgenWorkerThreads(final int configuredWorkerThreads) {
        final Provider p = provider;
        return p != null ? p.resolveWorldgenWorkerThreads(configuredWorkerThreads) : defaultResolveWorldgenWorkerThreads(configuredWorkerThreads);
    }

    public static RegionizedWorldData acquireRegionShell(
        final ServerLevel world,
        final TickRegions.TickRegionData regionData
    ) {
        final Provider p = provider;
        return p != null ? p.acquireRegionShell(world, regionData) : new RegionizedWorldData(world, regionData);
    }

    public static void releaseRegionShell(final RegionizedWorldData worldData) {
        final Provider p = provider;
        if (p != null) {
            p.releaseRegionShell(worldData);
        }
    }

    public static int defaultResolveTickThreads(final int configuredThreads) {
        if (configuredThreads > 0) {
            return configuredThreads;
        }
        final int totalCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (totalCores <= 2) {
            return 1;
        }
        if (totalCores <= 8) {
            return Math.max(2, totalCores / 2);
        }
        return Math.max(2, (totalCores / 2) / 4);
    }

    public static int defaultResolveWorldgenWorkerThreads(final int configuredWorkerThreads) {
        if (configuredWorkerThreads > 0) {
            return configuredWorkerThreads;
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }
}
