package dev.leafcraft.goldleaf.threading;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.config.HardwareEnvelope;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic Folia tick-thread resolver for GoldLeaf.
 * Preserves explicit {@code paper-global.yml} overrides and upstream Folia behavior
 * when disabled, while unlocking multi-region parallelism on 4–8+ core workstations.
 */
public final class TickThreadAutoTuner {

    private TickThreadAutoTuner() {
    }

    public static int resolveTickThreads(
        final int configuredThreads,
        final @Nullable GoldLeafConfig config,
        final HardwareEnvelope envelope
    ) {
        if (configuredThreads > 0) {
            return configuredThreads;
        }

        final int totalCores = envelope.logicalCores();
        final GoldLeafConfig effectiveConfig = config != null ? config : GoldLeafConfig.defaults();
        final GoldLeafConfig.Threading threading = effectiveConfig.threading != null
            ? effectiveConfig.threading
            : new GoldLeafConfig.Threading();

        if (!threading.autoTuneWorkstation) {
            return resolveUpstreamFoliaThreads(totalCores);
        }

        int resolved = computeWorkstationAwareThreads(totalCores);
        final int minThreads = Math.max(1, threading.minAutoThreads);
        resolved = Math.max(minThreads, resolved);
        if (threading.maxAutoThreads > 0) {
            resolved = Math.min(threading.maxAutoThreads, resolved);
        }
        return Math.max(1, resolved);
    }

    public static int resolveUpstreamFoliaThreads(final int totalCores) {
        final int halfCores = Math.max(1, totalCores) / 2;
        if (halfCores <= 4) {
            return 1;
        }
        return halfCores / 4;
    }

    static int computeWorkstationAwareThreads(final int totalCores) {
        if (totalCores <= 3) {
            return 1;
        }
        if (totalCores <= 8) {
            return Math.max(2, totalCores / 2);
        }
        if (totalCores <= 16) {
            return 4;
        }
        return Math.max(4, totalCores / 4);
    }
}
