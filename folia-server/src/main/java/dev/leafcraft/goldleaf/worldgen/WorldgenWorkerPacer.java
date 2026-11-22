package dev.leafcraft.goldleaf.worldgen;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.config.HardwareEnvelope;
import dev.leafcraft.goldleaf.threading.TickThreadAutoTuner;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic boot-time Moonrise generation worker thread resolver coordinated with
 * {@link TickThreadAutoTuner} ({@code goldleaf-ADR-003}, {@code goldleaf-ADR-004} §2.1).
 *
 * <p>Ensures background worldgen workers do not starve active Folia region tick threads on
 * 4–8 core floor machines while preserving explicit {@code configWorkerThreads > 0} overrides
 * and scaling cleanly on workstation/ceiling hardware. Sizing is purely boot-time and
 * deterministic (never driven by live telemetry).</p>
 */
public final class WorldgenWorkerPacer {

    private WorldgenWorkerPacer() {
    }

    public static int resolveWorkerThreads(
        final int configuredWorkerThreads,
        final @Nullable GoldLeafConfig config,
        final HardwareEnvelope envelope
    ) {
        if (configuredWorkerThreads > 0) {
            return configuredWorkerThreads;
        }

        final int totalCores = Math.max(1, envelope.logicalCores());
        final GoldLeafConfig effectiveConfig = config != null ? config : GoldLeafConfig.defaults();
        final GoldLeafConfig.Worldgen worldgen = effectiveConfig.worldgen != null
            ? effectiveConfig.worldgen
            : new GoldLeafConfig.Worldgen();

        if (!worldgen.autoPaceGenerationWorkers) {
            return resolveUpstreamMoonriseWorkers(totalCores);
        }

        final int tickThreads = TickThreadAutoTuner.resolveTickThreads(-1, effectiveConfig, envelope);
        return computePacedWorkerThreads(totalCores, tickThreads);
    }

    public static int resolveUpstreamMoonriseWorkers(final int totalCores) {
        final int halfCores = Math.max(1, totalCores) / 2;
        if (halfCores <= 4) {
            return halfCores <= 3 ? 1 : 2;
        }
        return halfCores / 2;
    }

    static int computePacedWorkerThreads(final int totalCores, final int tickThreads) {
        if (totalCores <= 4) {
            return 1;
        }
        final int remainingForWorkers = Math.max(1, totalCores - Math.max(1, tickThreads) - 1);
        return Math.max(2, Math.min(remainingForWorkers, totalCores / 2));
    }
}
