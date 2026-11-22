package dev.leafcraft.goldleaf.diagnostics;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1 boot-time plugin-archive preflight discovery hook ({@code goldleaf-ADR-007} §1.1).
 *
 * <p>Invoked during Paper/Folia plugin archive discovery <em>before</em> plugin classloaders are
 * created or {@code onEnable()} executes. Executes archive inspection on a dedicated off-thread
 * bootstrap executor ({@code S-005}) with zero plugin classloading, isolating corrupt or unreadable
 * archives ({@code S-004}) and aborting boot when
 * {@code diagnostics.plugin-safety-preflight-fail-closed} is enabled.</p>
 */
public final class PluginPreflightDiscoveryHook {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginPreflightDiscoveryHook.class);

    public enum FindingSeverity {
        INFO,
        WARNING,
        CRITICAL
    }

    public record PreflightFinding(
        Path archivePath,
        String code,
        FindingSeverity severity,
        String message
    ) {
        public PreflightFinding {
            Objects.requireNonNull(archivePath, "archivePath");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(message, "message");
        }
    }

    public enum ArchiveVerdict {
        SAFE,
        WARN,
        HAZARD
    }

    public record PreflightReport(
        List<Path> scannedArchives,
        List<PreflightFinding> findings
    ) {
        public PreflightReport {
            scannedArchives = List.copyOf(scannedArchives);
            findings = List.copyOf(findings);
        }

        public boolean hasCriticalFindings() {
            for (final PreflightFinding finding : this.findings) {
                if (finding.severity() == FindingSeverity.CRITICAL) {
                    return true;
                }
            }
            return false;
        }

        public Map<Path, ArchiveVerdict> verdictsByArchive() {
            final Map<Path, ArchiveVerdict> verdicts = new LinkedHashMap<>();
            for (final Path archive : this.scannedArchives) {
                verdicts.put(archive, ArchiveVerdict.SAFE);
            }
            for (final PreflightFinding finding : this.findings) {
                final ArchiveVerdict current = verdicts.getOrDefault(finding.archivePath(), ArchiveVerdict.SAFE);
                final ArchiveVerdict candidate = switch (finding.severity()) {
                    case CRITICAL -> ArchiveVerdict.HAZARD;
                    case WARNING -> ArchiveVerdict.WARN;
                    case INFO -> ArchiveVerdict.SAFE;
                };
                if (candidate.ordinal() > current.ordinal()) {
                    verdicts.put(finding.archivePath(), candidate);
                }
            }
            return Collections.unmodifiableMap(verdicts);
        }

        public int safeCount() {
            int count = 0;
            for (final ArchiveVerdict v : verdictsByArchive().values()) {
                if (v == ArchiveVerdict.SAFE) {
                    count++;
                }
            }
            return count;
        }

        public int warnCount() {
            int count = 0;
            for (final ArchiveVerdict v : verdictsByArchive().values()) {
                if (v == ArchiveVerdict.WARN) {
                    count++;
                }
            }
            return count;
        }

        public int hazardCount() {
            int count = 0;
            for (final ArchiveVerdict v : verdictsByArchive().values()) {
                if (v == ArchiveVerdict.HAZARD) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * Layer 2 provider SPI (e.g., {@code :bytecode-api} zero-classloading scanner).
     * Providers inspect raw archive bytes only and must never load plugin classes.
     */
    @FunctionalInterface
    public interface PreflightProvider {
        List<PreflightFinding> inspectArchive(Path archivePath) throws Exception;
    }

    private static final CopyOnWriteArrayList<PreflightProvider> PROVIDERS = new CopyOnWriteArrayList<>();
    private static volatile PreflightReport lastReport = new PreflightReport(List.of(), List.of());
    private static volatile Path reportOutputDirectory = Path.of("logs", "goldleaf-preflight");

    private PluginPreflightDiscoveryHook() {
    }

    public static void registerProvider(final PreflightProvider provider) {
        Objects.requireNonNull(provider, "provider");
        PROVIDERS.addIfAbsent(provider);
    }

    public static boolean unregisterProvider(final PreflightProvider provider) {
        return provider != null && PROVIDERS.remove(provider);
    }

    public static void clearProviders() {
        PROVIDERS.clear();
    }

    public static PreflightReport lastReport() {
        return lastReport;
    }

    public static Path reportOutputDirectory() {
        return reportOutputDirectory;
    }

    public static void setReportOutputDirectory(final Path directory) {
        reportOutputDirectory = Objects.requireNonNull(directory, "directory");
    }

    /**
     * Runs zero-classloading preflight over {@code archives} using current {@link GoldLeafConfig}
     * settings. Safe to call directly from {@code DirectoryProviderSource#prepareContext} and
     * {@code PluginFlagProviderSource#prepareContext}.
     */
    public static PreflightReport runPreflight(final List<Path> archives) {
        final GoldLeafConfig.Diagnostics diag = GoldLeafConfig.get().diagnostics;
        if (!diag.pluginSafetyPreflight) {
            return new PreflightReport(List.of(), List.of());
        }
        return runPreflight(archives, diag.pluginSafetyPreflightFailClosed, true);
    }

    /**
     * Runs zero-classloading preflight on a dedicated off-thread bootstrap executor and awaits
     * completion before plugin classloading proceeds ({@code S-005}).
     *
     * @throws IllegalStateException if {@code failClosed} is {@code true} and any corrupt archive
     *                               or {@link FindingSeverity#CRITICAL} finding was recorded
     */
    public static PreflightReport runPreflight(final List<Path> archives, final boolean failClosed) {
        return runPreflight(archives, failClosed, false);
    }

    public static PreflightReport runPreflight(
        final List<Path> archives,
        final boolean failClosed,
        final boolean archiveReports
    ) {
        Objects.requireNonNull(archives, "archives");
        if (archives.isEmpty()) {
            final PreflightReport empty = new PreflightReport(List.of(), List.of());
            lastReport = empty;
            return empty;
        }

        final List<Path> immutableArchives = List.copyOf(archives);
        final ExecutorService bootstrapExecutor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "GoldLeaf-Plugin-Preflight-Worker");
            t.setDaemon(true);
            return t;
        });

        try {
            final CompletableFuture<PreflightReport> future = CompletableFuture.supplyAsync(
                () -> {
                    final PreflightReport report = executeScanOffThread(immutableArchives);
                    if (archiveReports) {
                        writeReports(report, reportOutputDirectory);
                    }
                    return report;
                },
                bootstrapExecutor
            );
            final PreflightReport report = future.join();
            lastReport = report;
            LOGGER.info(formatSummaryTable(report, failClosed));
            if (failClosed && report.hasCriticalFindings()) {
                throw new IllegalStateException(
                    "GoldLeaf plugin-safety-preflight-fail-closed aborted startup: "
                        + report.findings().size() + " finding(s) including CRITICAL hazard(s)"
                );
            }
            return report;
        } finally {
            bootstrapExecutor.shutdownNow();
        }
    }

    public static String formatSummaryTable(final PreflightReport report, final boolean failClosed) {
        Objects.requireNonNull(report, "report");
        final StringBuilder sb = new StringBuilder();
        sb.append("[GoldLeaf Plugin Safety Preflight (ADR-007)] ");
        sb.append("scanned=").append(report.scannedArchives().size())
            .append(" SAFE=").append(report.safeCount())
            .append(" WARN=").append(report.warnCount())
            .append(" HAZARD=").append(report.hazardCount())
            .append(" findings=").append(report.findings().size())
            .append(" fail-closed=").append(failClosed);
        for (final Map.Entry<Path, ArchiveVerdict> entry : report.verdictsByArchive().entrySet()) {
            final Path fileName = entry.getKey().getFileName();
            sb.append("\n  [").append(entry.getValue().name()).append("] ")
                .append(fileName != null ? fileName : entry.getKey());
        }
        return sb.toString();
    }

    public static void writeReports(final PreflightReport report, final Path outputDir) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(outputDir, "outputDir");
        try {
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve("latest-preflight.json"), toJson(report), StandardCharsets.UTF_8);
            Files.writeString(outputDir.resolve("latest-preflight.md"), toMarkdown(report), StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            LOGGER.warn("GoldLeaf plugin preflight failed to write report artifacts to {}", outputDir, ex);
        }
    }

    public static String toJson(final PreflightReport report) {
        Objects.requireNonNull(report, "report");
        final StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"generatedAt\": \"").append(Instant.now().toString()).append("\",\n");
        sb.append("  \"scannedCount\": ").append(report.scannedArchives().size()).append(",\n");
        sb.append("  \"safeCount\": ").append(report.safeCount()).append(",\n");
        sb.append("  \"warnCount\": ").append(report.warnCount()).append(",\n");
        sb.append("  \"hazardCount\": ").append(report.hazardCount()).append(",\n");
        sb.append("  \"archives\": [\n");
        int archiveIdx = 0;
        final Map<Path, ArchiveVerdict> verdicts = report.verdictsByArchive();
        for (final Map.Entry<Path, ArchiveVerdict> entry : verdicts.entrySet()) {
            if (archiveIdx++ > 0) {
                sb.append(",\n");
            }
            final Path fileName = entry.getKey().getFileName();
            sb.append("    {\"archive\": \"").append(jsonEscape(fileName != null ? fileName.toString() : entry.getKey().toString()))
                .append("\", \"verdict\": \"").append(entry.getValue().name()).append("\"}");
        }
        sb.append("\n  ],\n");
        sb.append("  \"findings\": [\n");
        for (int i = 0; i < report.findings().size(); i++) {
            if (i > 0) {
                sb.append(",\n");
            }
            final PreflightFinding f = report.findings().get(i);
            final Path fileName = f.archivePath().getFileName();
            sb.append("    {\"archive\": \"").append(jsonEscape(fileName != null ? fileName.toString() : f.archivePath().toString()))
                .append("\", \"code\": \"").append(jsonEscape(f.code()))
                .append("\", \"severity\": \"").append(f.severity().name())
                .append("\", \"message\": \"").append(jsonEscape(f.message())).append("\"}");
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    public static String toMarkdown(final PreflightReport report) {
        Objects.requireNonNull(report, "report");
        final StringBuilder sb = new StringBuilder();
        sb.append("# GoldLeaf Plugin Safety Preflight Report\n\n");
        sb.append("- Scanned archives: ").append(report.scannedArchives().size()).append('\n');
        sb.append("- SAFE: ").append(report.safeCount())
            .append(" | WARN: ").append(report.warnCount())
            .append(" | HAZARD: ").append(report.hazardCount()).append("\n\n");
        sb.append("| Archive | Verdict |\n|---|---|\n");
        for (final Map.Entry<Path, ArchiveVerdict> entry : report.verdictsByArchive().entrySet()) {
            final Path fileName = entry.getKey().getFileName();
            sb.append("| `").append(fileName != null ? fileName : entry.getKey())
                .append("` | **").append(entry.getValue().name()).append("** |\n");
        }
        if (!report.findings().isEmpty()) {
            sb.append("\n## Findings\n\n");
            for (final PreflightFinding f : report.findings()) {
                final Path fileName = f.archivePath().getFileName();
                sb.append("- `[").append(f.severity().name()).append("]` `")
                    .append(fileName != null ? fileName : f.archivePath()).append("` `")
                    .append(f.code()).append("`: ").append(f.message()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String jsonEscape(final String value) {
        final StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static PreflightReport executeScanOffThread(final List<Path> archives) {
        final List<Path> scanned = new ArrayList<>(archives.size());
        final List<PreflightFinding> findings = new ArrayList<>();

        for (final Path archive : archives) {
            if (archive == null) {
                continue;
            }
            scanned.add(archive);
            if (!validateReadableArchive(archive, findings)) {
                // Fault isolated: continue analyzing remaining archives (S-004)
                continue;
            }
            for (final PreflightProvider provider : PROVIDERS) {
                try {
                    final List<PreflightFinding> providerFindings = provider.inspectArchive(archive);
                    if (providerFindings != null && !providerFindings.isEmpty()) {
                        findings.addAll(providerFindings);
                    }
                } catch (final Throwable t) {
                    LOGGER.warn(
                        "GoldLeaf plugin preflight provider {} failed on archive {}",
                        provider.getClass().getName(),
                        archive,
                        t
                    );
                    findings.add(new PreflightFinding(
                        archive,
                        "PROVIDER_ERROR",
                        FindingSeverity.WARNING,
                        "Preflight provider failed: " + t.getClass().getSimpleName() + ": " + t.getMessage()
                    ));
                }
            }
        }

        final PreflightReport report = new PreflightReport(Collections.unmodifiableList(scanned), Collections.unmodifiableList(findings));
        lastReport = report;
        for (final PreflightFinding finding : findings) {
            if (finding.severity() == FindingSeverity.WARNING || finding.severity() == FindingSeverity.CRITICAL) {
                final Path fileName = finding.archivePath().getFileName();
                final String pluginName = fileName != null ? fileName.toString() : finding.archivePath().toString();
                final String pkgPrefix = extractPackagePrefix(finding.message(), pluginName);
                WatchdogDiagnosticsRegistry.global().registerPluginHazard(
                    new WatchdogDiagnosticsRegistry.PluginHazardAttribution(pluginName, pkgPrefix, finding.code())
                );
            }
        }
        return report;
    }

    private static String extractPackagePrefix(final String message, final String fallbackPluginName) {
        if (message != null) {
            final int classIdx = message.indexOf(".class");
            if (classIdx > 0) {
                int start = classIdx;
                while (start > 0 && !Character.isWhitespace(message.charAt(start - 1)) && message.charAt(start - 1) != '(') {
                    start--;
                }
                final String raw = message.substring(start, classIdx).replace('/', '.');
                final int lastDot = raw.lastIndexOf('.');
                if (lastDot > 0) {
                    return raw.substring(0, lastDot);
                }
            }
        }
        final int dot = fallbackPluginName.indexOf('.');
        return (dot > 0 ? fallbackPluginName.substring(0, dot) : fallbackPluginName).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean validateReadableArchive(final Path archive, final List<PreflightFinding> findings) {
        if (!Files.isRegularFile(archive) || !Files.isReadable(archive)) {
            final String msg = "Plugin archive is missing or unreadable: " + archive;
            LOGGER.warn("GoldLeaf plugin preflight [UNREADABLE_ARCHIVE]: {}", msg);
            findings.add(new PreflightFinding(archive, "UNREADABLE_ARCHIVE", FindingSeverity.CRITICAL, msg));
            return false;
        }
        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            // Structural central-directory verification only; zero classloading.
            zipFile.size();
            return true;
        } catch (final IOException ex) {
            final String msg = "Corrupt or truncated plugin archive: " + archive + " (" + ex.getMessage() + ")";
            LOGGER.warn("GoldLeaf plugin preflight [UNREADABLE_ARCHIVE]: {}", msg, ex);
            findings.add(new PreflightFinding(archive, "UNREADABLE_ARCHIVE", FindingSeverity.CRITICAL, msg));
            return false;
        }
    }
}
