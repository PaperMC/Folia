package dev.leafcraft.goldleaf.threading;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.config.HardwareEnvelope;
import dev.leafcraft.goldleaf.worldgen.WorldgenWorkerPacer;
import io.papermc.paper.spi.PaperThreadingSpi;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 bootstrap registrar for threading, workstation auto-tuning, worldgen worker pacing,
 * and region shell pooling ({@code goldleaf-ADR-001} Decision §1).
 */
public final class ThreadingBootstrapRegistrar {

    private ThreadingBootstrapRegistrar() {
    }

    public static void register(final @Nullable GoldLeafConfig config) {
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
    }
}
