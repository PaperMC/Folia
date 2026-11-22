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

    public record Options(
        Path pluginsDir,
        @Nullable Path jsonOut,
        @Nullable Path markdownOut,
        boolean failOnWarn
    ) {
        public Options {
            Objects.requireNonNull(pluginsDir, "pluginsDir");
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
        if (result.exitCode() != 0) {
            System.exit(result.exitCode());
        }
    }

    public static Options parseArgs(final String[] args) {
        Path pluginsDir = Path.of("plugins");
        Path jsonOut = null;
        Path markdownOut = null;
        boolean failOnWarn = false;

        for (int i = 0; i < args.length; i++) {
            final String arg = args[i];
            switch (arg) {
                case "--dir", "--plugins" -> pluginsDir = Path.of(requireArg(args, ++i, arg));
                case "--json" -> jsonOut = Path.of(requireArg(args, ++i, arg));
                case "--markdown", "--md" -> markdownOut = Path.of(requireArg(args, ++i, arg));
                case "--fail-on-warn" -> failOnWarn = true;
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown --audit-plugins option: " + arg);
                    }
                    pluginsDir = Path.of(arg);
                }
            }
        }
        return new Options(pluginsDir, jsonOut, markdownOut, failOnWarn);
    }

    public static AuditResult run(final Options options) {
        Objects.requireNonNull(options, "options");
        PluginPreflightDiscoveryHook.registerProvider(BytecodeFoliaPreflightProvider.instance());
        final List<Path> jars = collectPluginJars(options.pluginsDir());
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

    private static List<Path> collectPluginJars(final Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        final List<Path> jars = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(jars::add);
        } catch (final IOException ex) {
            System.err.println("Failed to scan plugin directory " + dir + ": " + ex.getMessage());
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
