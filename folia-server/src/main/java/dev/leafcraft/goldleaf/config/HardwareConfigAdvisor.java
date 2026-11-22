package dev.leafcraft.goldleaf.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Strictly informational hardware-envelope drift and configuration conflict advisor
 * ({@code goldleaf-ADR-014} §2.2).
 *
 * <p>Compares {@link HardwareEnvelope} against active {@link GoldLeafConfig} and Paper/Spigot
 * settings to emit plain-language, actionable startup advisories without ever mutating
 * operator-edited YAML files.</p>
 */
public final class HardwareConfigAdvisor {

    public enum Severity {
        INFO,
        WARN
    }

    public record Advisory(
        String code,
        Severity severity,
        String message
    ) {
    }

    public record EvaluationInput(
        HardwareEnvelope hardware,
        GoldLeafConfig config,
        int configuredTickThreads,
        int spigotHopperCheck,
        boolean aggressiveEntityActivation,
        boolean hasRecentVerifiedBackup
    ) {
    }

    private HardwareConfigAdvisor() {
    }

    public static List<Advisory> evaluate(final EvaluationInput input) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(input.hardware(), "hardware");
        Objects.requireNonNull(input.config(), "config");

        final List<Advisory> advisories = new ArrayList<>();
        final HardwareEnvelope hw = input.hardware();
        final GoldLeafConfig cfg = input.config();

        if (input.configuredTickThreads() > 0 && input.configuredTickThreads() > hw.logicalCores()) {
            advisories.add(new Advisory(
                "THREAD_OVERPROVISION",
                Severity.WARN,
                "paper-global.yml threaded-regions.threads (" + input.configuredTickThreads()
                    + ") exceeds host logical cores (" + hw.logicalCores()
                    + "); risk of region-scheduler context-switch thrashing (ADR-003). Set to -1 for auto-tuning."
            ));
        }

        final boolean anyTechnicalProtocolEnabled = cfg.protocols.servuxEnabled
            || cfg.protocols.syncmaticaEnabled
            || cfg.protocols.jadeEnabled
            || cfg.protocols.appleskinEnabled
            || cfg.protocols.mapWorldIdEnabled;

        if (anyTechnicalProtocolEnabled && input.spigotHopperCheck() > 1) {
            advisories.add(new Advisory(
                "HOPPER_CHECK_PARITY",
                Severity.WARN,
                "spigot.yml hopper-check=" + input.spigotHopperCheck()
                    + " while technical client protocols are enabled; hopper-check > 1 alters vanilla"
                    + " item-sorter/comparator timing (set hopper-check: 1 for technical-vanilla parity)."
            ));
        }

        if (anyTechnicalProtocolEnabled && input.aggressiveEntityActivation()) {
            advisories.add(new Advisory(
                "ACTIVATION_RANGE_PARITY",
                Severity.WARN,
                "Aggressive spigot.yml entity-activation-range detected with technical protocols enabled;"
                    + " reduced activation ranges can disrupt iron, raid, and trading farms (ADR-008/ADR-014)."
            ));
        }

        if ("lca".equalsIgnoreCase(cfg.storage.format) && !input.hasRecentVerifiedBackup()) {
            advisories.add(new Advisory(
                "LCA_BACKUP_REMINDER",
                Severity.INFO,
                "storage.format=lca is active without a recent verified backup marker; run"
                    + " LcaMigrationCli --verify periodically to confirm lossless .lca <-> .mca parity (ADR-014)."
            ));
        }

        return Collections.unmodifiableList(advisories);
    }
}
