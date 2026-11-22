package dev.leafcraft.goldleaf.diagnostics;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.config.HardwareConfigAdvisor;
import dev.leafcraft.goldleaf.config.HardwareEnvelope;
import java.util.List;
import java.util.Objects;

/**
 * Formats the concise, honest GoldLeaf startup summary ({@code goldleaf-ADR-011} §2.2),
 * surfacing hardware auto-tuner decisions ({@code ADR-003}), active region storage format plus
 * reversible MIT {@code .lca <-> .mca} converter status ({@code ADR-005}), and boot-time plugin
 * safety preflight counts ({@code ADR-007}).
 */
public final class GoldLeafStartupSummary {

    public record SummaryInput(
        HardwareEnvelope hardware,
        int configuredTickThreads,
        int resolvedTickThreads,
        String activeStorageFormat,
        boolean lcaModeBEnabled,
        int preflightSafeCount,
        int preflightWarnCount,
        int preflightHazardCount
    ) {
    }

    private GoldLeafStartupSummary() {
    }

    public static String format(final SummaryInput input) {
        Objects.requireNonNull(input, "input");
        final StringBuilder sb = new StringBuilder();
        sb.append("[GoldLeaf Startup Summary]\n");
        sb.append(String.format(
            "  Threading (ADR-003): tier=%s logicalCores=%d maxHeapMiB=%d resolvedTickThreads=%d (configured=%d)",
            input.hardware().tier(),
            input.hardware().logicalCores(),
            input.hardware().maxHeapMiB(),
            input.resolvedTickThreads(),
            input.configuredTickThreads()
        ));
        if (input.configuredTickThreads() > 0 && input.configuredTickThreads() > input.hardware().logicalCores()) {
            sb.append(" [WARN: configured threads exceed host core sweet spot]");
        }
        sb.append('\n');

        final boolean isLca = "lca".equalsIgnoreCase(input.activeStorageFormat());
        sb.append(String.format(
            "  Storage   (ADR-005): format=%s%s (reversible MIT .lca <-> .mca CLI converter available)\n",
            input.activeStorageFormat(),
            isLca ? (input.lcaModeBEnabled() ? " [v3-Columnar Mode B LCD2]" : " [v3-Columnar]") : " [Vanilla Anvil]"
        ));

        sb.append(String.format(
            "  Preflight (ADR-007): SAFE=%d WARN=%d HAZARD=%d (fail-closed=%s)",
            input.preflightSafeCount(),
            input.preflightWarnCount(),
            input.preflightHazardCount(),
            GoldLeafConfig.get().diagnostics.pluginSafetyPreflightFailClosed
        ));
        return sb.toString();
    }

    public static String formatWithAdvisories(
        final SummaryInput input,
        final List<HardwareConfigAdvisor.Advisory> advisories
    ) {
        final String base = format(input);
        if (advisories == null || advisories.isEmpty()) {
            return base;
        }
        final StringBuilder sb = new StringBuilder(base);
        sb.append("\n  Advisories (ADR-014):");
        for (final HardwareConfigAdvisor.Advisory advisory : advisories) {
            sb.append("\n    [").append(advisory.severity()).append(' ').append(advisory.code()).append("] ")
                .append(advisory.message());
        }
        return sb.toString();
    }
}
