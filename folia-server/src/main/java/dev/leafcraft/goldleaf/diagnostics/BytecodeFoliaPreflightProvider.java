package dev.leafcraft.goldleaf.diagnostics;

import dev.leafcraft.goldleaf.diagnostics.PluginPreflightDiscoveryHook.FindingSeverity;
import dev.leafcraft.goldleaf.diagnostics.PluginPreflightDiscoveryHook.PreflightFinding;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.CausalFinding;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.CausalGraphReport;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.ConfidenceTier;
import io.github.dailystruggle.bytecode.BytecodeCausalGraphScanner.FindingKind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 2 zero-classloading Folia compatibility preflight scanner ({@code goldleaf-ADR-001} Stage 5,
 * {@code goldleaf-ADR-007} §2.1).
 *
 * <p>Inspects plugin archive descriptors ({@code plugin.yml} / {@code paper-plugin.yml}) and raw
 * {@code .class} bytecode on the off-thread bootstrap executor ({@code S-005}) without creating
 * classloaders or triggering static initializers. Combines Folia descriptor/API checks with
 * {@code :bytecode-api}'s {@link BytecodeCausalGraphScanner#scanJar(Path)} (covering
 * {@code BytecodeParityAnalyzers} sync tick-thread I/O contagion, unmanaged thread/executor spawns,
 * unclosed resource leaks, shared-state races, and {@code BytecodeCweAnalyzers} checks).</p>
 */
public final class BytecodeFoliaPreflightProvider implements PluginPreflightDiscoveryHook.PreflightProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(BytecodeFoliaPreflightProvider.class);

    public static final String CODE_FOLIA_UNDECLARED = "FOLIA_UNDECLARED";
    public static final String CODE_BUKKIT_SCHEDULER_CALL = "BUKKIT_SCHEDULER_CALL";
    public static final String CODE_SYNC_CHUNK_LOAD = "SYNC_CHUNK_LOAD_CALL";
    public static final String CODE_ASYNC_MAIN_THREAD_SINK = "ASYNC_MAIN_THREAD_SINK";
    public static final String CODE_SYNC_MAIN_THREAD_IO = "SYNC_MAIN_THREAD_IO";
    public static final String CODE_UNMANAGED_THREAD_SPAWN = "UNMANAGED_THREAD_SPAWN";
    public static final String CODE_UNCLOSED_RESOURCE_LEAK = "UNCLOSED_RESOURCE_LEAK";
    public static final String CODE_SHARED_STATE_RACE = "SHARED_STATE_RACE";

    private static final int MAX_CLASS_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CAUSAL_FINDINGS_PER_JAR = 32;
    private static final BytecodeFoliaPreflightProvider INSTANCE = new BytecodeFoliaPreflightProvider();

    public static BytecodeFoliaPreflightProvider instance() {
        return INSTANCE;
    }

    @Override
    public List<PreflightFinding> inspectArchive(final Path archivePath) throws IOException {
        final List<PreflightFinding> findings = new ArrayList<>();
        boolean declaredFoliaSupported = false;
        boolean hasPluginDescriptor = false;
        boolean foundBukkitScheduler = false;
        boolean foundSyncChunkLoad = false;
        String schedulerCaller = null;
        String syncChunkCaller = null;

        try (ZipFile zip = new ZipFile(archivePath.toFile())) {
            final ZipEntry paperPluginYml = zip.getEntry("paper-plugin.yml");
            final ZipEntry pluginYml = zip.getEntry("plugin.yml");
            if (paperPluginYml != null) {
                hasPluginDescriptor = true;
                declaredFoliaSupported |= descriptorDeclaresFoliaSupported(zip, paperPluginYml);
            }
            if (pluginYml != null) {
                hasPluginDescriptor = true;
                declaredFoliaSupported |= descriptorDeclaresFoliaSupported(zip, pluginYml);
            }

            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements() && !(foundBukkitScheduler && foundSyncChunkLoad)) {
                final ZipEntry entry = entries.nextElement();
                final String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/")) {
                    continue;
                }
                if (entry.getSize() > MAX_CLASS_BYTES) {
                    continue;
                }
                final byte[] classBytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    classBytes = in.readNBytes(MAX_CLASS_BYTES);
                }
                final BytecodeScanResult scan = scanClassBytes(classBytes);
                if (!foundBukkitScheduler && scan.usesBukkitScheduler) {
                    foundBukkitScheduler = true;
                    schedulerCaller = name;
                }
                if (!foundSyncChunkLoad && scan.usesSyncChunkLoad) {
                    foundSyncChunkLoad = true;
                    syncChunkCaller = name;
                }
            }
        }

        if (hasPluginDescriptor && !declaredFoliaSupported) {
            findings.add(new PreflightFinding(
                archivePath,
                CODE_FOLIA_UNDECLARED,
                FindingSeverity.WARNING,
                "Plugin descriptor does not declare 'folia-supported: true'"
            ));
        }
        if (foundBukkitScheduler) {
            findings.add(new PreflightFinding(
                archivePath,
                CODE_BUKKIT_SCHEDULER_CALL,
                FindingSeverity.WARNING,
                "References legacy BukkitScheduler in " + schedulerCaller + " (incompatible with Folia region threading)"
            ));
        }
        if (foundSyncChunkLoad) {
            findings.add(new PreflightFinding(
                archivePath,
                CODE_SYNC_CHUNK_LOAD,
                FindingSeverity.INFO,
                "References synchronous World#getChunkAt in " + syncChunkCaller + " (prefer async chunk APIs)"
            ));
        }

        appendBytecodeApiFindings(archivePath, findings);
        return findings;
    }

    private static void appendBytecodeApiFindings(
        final Path archivePath,
        final List<PreflightFinding> findings
    ) {
        final CausalGraphReport report;
        try {
            report = BytecodeCausalGraphScanner.scanJar(archivePath);
        } catch (final Throwable t) {
            LOGGER.warn(
                "GoldLeaf :bytecode-api causal scan failed on archive {} (fault isolated)",
                archivePath,
                t
            );
            findings.add(new PreflightFinding(
                archivePath,
                "BYTECODE_API_SCAN_ERROR",
                FindingSeverity.WARNING,
                ":bytecode-api scan error: " + t.getClass().getSimpleName() + ": " + t.getMessage()
            ));
            return;
        }
        if (report == null || report.findings() == null || report.findings().isEmpty()) {
            return;
        }
        final Set<String> dedupe = new LinkedHashSet<>();
        int added = 0;
        for (final CausalFinding cf : report.findings()) {
            if (cf == null || added >= MAX_CAUSAL_FINDINGS_PER_JAR) {
                break;
            }
            final PreflightFinding mapped = mapCausalFinding(archivePath, cf);
            if (mapped == null) {
                continue;
            }
            final String key = mapped.code() + "|" + cf.entryPointSymbol();
            if (dedupe.add(key)) {
                findings.add(mapped);
                added++;
            }
        }
    }

    static PreflightFinding mapCausalFinding(final Path archivePath, final CausalFinding cf) {
        final FindingKind kind = cf.kind();
        final String code;
        final FindingSeverity severity;
        switch (kind) {
            case CAUSAL_ASYNC_MAIN_THREAD_SINK -> {
                code = CODE_ASYNC_MAIN_THREAD_SINK;
                severity = cf.confidenceTier() == ConfidenceTier.CONFIRMED
                    ? FindingSeverity.CRITICAL
                    : FindingSeverity.WARNING;
            }
            case CAUSAL_SYNC_MAIN_THREAD_IO -> {
                code = CODE_SYNC_MAIN_THREAD_IO;
                severity = FindingSeverity.WARNING;
            }
            case CAUSAL_UNMANAGED_THREAD_SPAWN -> {
                code = CODE_UNMANAGED_THREAD_SPAWN;
                severity = FindingSeverity.WARNING;
            }
            case CAUSAL_UNCLOSED_RESOURCE_LEAK -> {
                code = CODE_UNCLOSED_RESOURCE_LEAK;
                severity = FindingSeverity.WARNING;
            }
            case CAUSAL_SHARED_STATE_RACE -> {
                code = CODE_SHARED_STATE_RACE;
                severity = FindingSeverity.WARNING;
            }
            case CAUSAL_SWALLOWED_FAILURE -> {
                code = "SWALLOWED_FAILURE";
                severity = FindingSeverity.INFO;
            }
            default -> {
                return null;
            }
        }
        final String msg = "[" + cf.confidenceTier().badge() + "] "
            + cf.entryPointSymbol() + " -> " + cf.causalDefect();
        return new PreflightFinding(archivePath, code, severity, msg);
    }

    private static boolean descriptorDeclaresFoliaSupported(final ZipFile zip, final ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            final String text = new String(in.readNBytes(65536), StandardCharsets.UTF_8);
            for (final String rawLine : text.split("\\R")) {
                final String line = rawLine.trim();
                if (line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("folia-supported:")) {
                    final String value = line.substring("folia-supported:".length()).trim();
                    if ("true".equalsIgnoreCase(value) || "'true'".equalsIgnoreCase(value) || "\"true\"".equalsIgnoreCase(value)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static BytecodeScanResult scanClassBytes(final byte[] classBytes) {
        final BytecodeScanResult result = new BytecodeScanResult();
        final ClassReader reader = new ClassReader(classBytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String methodName,
                        final String methodDescriptor,
                        final boolean isInterface
                    ) {
                        if (("org/bukkit/Bukkit".equals(owner) || "org/bukkit/Server".equals(owner))
                            && "getScheduler".equals(methodName)) {
                            result.usesBukkitScheduler = true;
                        } else if ("org/bukkit/World".equals(owner) && "getChunkAt".equals(methodName)) {
                            result.usesSyncChunkLoad = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }

    private static final class BytecodeScanResult {
        boolean usesBukkitScheduler;
        boolean usesSyncChunkLoad;
    }
}
