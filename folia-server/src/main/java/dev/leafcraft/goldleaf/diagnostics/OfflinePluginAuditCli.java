package dev.leafcraft.goldleaf.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Zero-boot offline plugin Folia-safety audit CLI ({@code goldleaf-ADR-018} §1).
 *
 * <p>Invoked via {@code java -jar goldleaf.jar --audit-plugins [dir] [--json <file>]
 * [--markdown <file>] [--fail-on-warn]} before Minecraft server bootstrap. Scans plugin
 * {@code .jar} archives using {@link BytecodeFoliaPreflightProvider} with zero classloading
 * and exits non-zero if any {@code CRITICAL} hazard is detected (or {@code WARNING} when
 * {@code --fail-on-warn} is set).</p>
 */
public final class OfflinePluginAuditCli {

    public enum OutputFormat {
        TEXT,
        JSON,
        MARKDOWN;

        public static OutputFormat parse(final String raw) {
            final String norm = Objects.requireNonNull(raw, "format").trim().toLowerCase(java.util.Locale.ROOT);
            return switch (norm) {
                case "text", "txt" -> TEXT;
                case "json" -> JSON;
                case "markdown", "md" -> MARKDOWN;
                default -> throw new IllegalArgumentException(
                    "Unsupported --format '" + raw + "' (expected text, json, or markdown)"
                );
            };
        }
    }

    public record Options(
        Path pluginsDir,
        @Nullable Path jsonOut,
        @Nullable Path markdownOut,
        boolean failOnWarn,
        OutputFormat format
    ) {
        public Options {
            Objects.requireNonNull(pluginsDir, "pluginsDir");
            Objects.requireNonNull(format, "format");
        }

        public Options(
            final Path pluginsDir,
            final @Nullable Path jsonOut,
            final @Nullable Path markdownOut,
            final boolean failOnWarn
        ) {
            this(pluginsDir, jsonOut, markdownOut, failOnWarn, OutputFormat.TEXT);
        }
    }

    public record AuditResult(
        int exitCode,
        PluginPreflightDiscoveryHook.PreflightReport report
    ) {
    }

    private OfflinePluginAuditCli() {
    }

    public static void main(final String[] args) {
        final Options options = parseArgs(args);
        final AuditResult result = run(options);
        final PluginPreflightDiscoveryHook.PreflightReport report = result.report();
        switch (options.format()) {
            case JSON -> System.out.println(PluginPreflightDiscoveryHook.toJson(report));
            case MARKDOWN -> System.out.println(PluginPreflightDiscoveryHook.toMarkdown(report));
            case TEXT -> {
                System.out.printf(
                    "GoldLeaf Plugin Audit: scanned=%d SAFE=%d WARN=%d HAZARD=%d exit=%d%n",
                    report.scannedArchives().size(),
                    report.safeCount(),
                    report.warnCount(),
                    report.hazardCount(),
                    result.exitCode()
                );
                for (final PluginPreflightDiscoveryHook.PreflightFinding finding : report.findings()) {
                    final Path fileName = finding.archivePath().getFileName();
                    System.out.printf(
                        "  [%s] %s (%s): %s%n",
                        finding.severity(),
                        fileName != null ? fileName : finding.archivePath(),
                        finding.code(),
                        finding.message()
                    );
                }
            }
        }
        if (result.exitCode() != 0) {
            System.exit(result.exitCode());
        }
    }

    public static Options parseArgs(final String[] args) {
        Path pluginsDir = Path.of("plugins");
        Path jsonOut = null;
        Path markdownOut = null;
        boolean failOnWarn = false;
        OutputFormat format = OutputFormat.TEXT;

        for (int i = 0; i < args.length; i++) {
            final String arg = args[i];
            switch (arg) {
                case "--dir", "--plugins" -> pluginsDir = Path.of(requireArg(args, ++i, arg));
                case "--json" -> jsonOut = Path.of(requireArg(args, ++i, arg));
                case "--markdown", "--md" -> markdownOut = Path.of(requireArg(args, ++i, arg));
                case "--format" -> format = OutputFormat.parse(requireArg(args, ++i, arg));
                case "--fail-on-warn", "--strict" -> failOnWarn = true;
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown --audit-plugins option: " + arg);
                    }
                    pluginsDir = Path.of(arg);
                }
            }
        }
        return new Options(pluginsDir, jsonOut, markdownOut, failOnWarn, format);
    }

    public static AuditResult run(final Options options) {
        Objects.requireNonNull(options, "options");
        PluginPreflightDiscoveryHook.registerProvider(BytecodeFoliaPreflightProvider.instance());
        final Path dir = options.pluginsDir();
        if (!Files.isDirectory(dir) || !Files.isReadable(dir)) {
            final PluginPreflightDiscoveryHook.PreflightFinding dirError =
                new PluginPreflightDiscoveryHook.PreflightFinding(
                    dir,
                    "UNREADABLE_PLUGINS_DIR",
                    PluginPreflightDiscoveryHook.FindingSeverity.CRITICAL,
                    "Target plugins directory does not exist or is not readable: " + dir
                );
            final PluginPreflightDiscoveryHook.PreflightReport errorReport =
                new PluginPreflightDiscoveryHook.PreflightReport(
                    List.of(),
                    List.of(dirError)
                );
            return new AuditResult(2, errorReport);
        }

        final List<Path> jars;
        try {
            jars = collectPluginJars(dir);
        } catch (final IOException ex) {
            final PluginPreflightDiscoveryHook.PreflightFinding listError =
                new PluginPreflightDiscoveryHook.PreflightFinding(
                    dir,
                    "PLUGINS_DIR_IO_ERROR",
                    PluginPreflightDiscoveryHook.FindingSeverity.CRITICAL,
                    "Failed to list plugin directory " + dir + ": " + ex.getMessage()
                );
            final PluginPreflightDiscoveryHook.PreflightReport errorReport =
                new PluginPreflightDiscoveryHook.PreflightReport(
                    List.of(),
                    List.of(listError)
                );
            return new AuditResult(2, errorReport);
        }

        final PluginPreflightDiscoveryHook.PreflightReport report = PluginPreflightDiscoveryHook.runPreflight(jars);

        try {
            if (options.jsonOut() != null) {
                writeUtf8(options.jsonOut(), PluginPreflightDiscoveryHook.toJson(report));
            }
            if (options.markdownOut() != null) {
                writeUtf8(options.markdownOut(), PluginPreflightDiscoveryHook.toMarkdown(report));
            }
        } catch (final IOException ex) {
            System.err.println("Failed to write audit report: " + ex.getMessage());
            return new AuditResult(2, report);
        }

        final int exitCode;
        if (report.hasCriticalFindings()) {
            exitCode = 1;
        } else if (options.failOnWarn() && report.warnCount() > 0) {
            exitCode = 1;
        } else {
            exitCode = 0;
        }
        return new AuditResult(exitCode, report);
    }

    private static List<Path> collectPluginJars(final Path dir) throws IOException {
        final List<Path> jars = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(jars::add);
        }
        return jars;
    }

    private static void writeUtf8(final Path target, final String content) throws IOException {
        final Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static String requireArg(final String[] args, final int index, final String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[index];
    }
}
