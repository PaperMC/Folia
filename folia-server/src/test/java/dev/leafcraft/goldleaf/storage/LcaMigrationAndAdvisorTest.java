package dev.leafcraft.goldleaf.storage;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.config.HardwareConfigAdvisor;
import dev.leafcraft.goldleaf.config.HardwareEnvelope;
import dev.leafcraft.goldleaf.diagnostics.GoldLeafStartupSummary;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and round-trip parity tests for {@link LcaMigrationCli}, {@link HardwareConfigAdvisor},
 * and Paper {@code GlobalConfiguration.UnsupportedSettings} preservation ({@code goldleaf-ADR-014}).
 */
class LcaMigrationAndAdvisorTest {

    private static byte[] sampleChunkNbt(final int chunkX, final int chunkZ, final String marker) throws Exception {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", 4189);
        tag.putInt("xPos", chunkX);
        tag.putInt("zPos", chunkZ);
        tag.putString("Status", "minecraft:full");
        tag.putString("GoldLeafMarker", marker);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(out)) {
            NbtIo.write(tag, dos);
        }
        return out.toByteArray();
    }

    @Test
    void bidirectionalMcaLcaMcaRoundTripWithVerifyAndDryRun(@TempDir final Path tempDir) throws Exception {
        final Path srcWorld = tempDir.resolve("world-mca");
        final Path lcaWorld = tempDir.resolve("world-lca");
        final Path restoredMcaWorld = tempDir.resolve("world-restored");

        final Map<Integer, byte[]> regionChunks = new LinkedHashMap<>();
        regionChunks.put(0, sampleChunkNbt(0, 0, "origin"));
        regionChunks.put(1 + (2 << 5), sampleChunkNbt(1, 2, "sorter-hall"));
        final Path srcMca = srcWorld.resolve("region").resolve("r.0.0.mca");
        LcaMigrationCli.writeMcaRegionAtomic(srcMca, regionChunks);
        final byte[] originalMcaBytes = Files.readAllBytes(srcMca);

        // 1. --dry-run must report counts without creating output or mutating source
        final LcaMigrationCli.Report dryRun = LcaMigrationCli.run(new LcaMigrationCli.Options(
            LcaMigrationCli.Format.MCA, LcaMigrationCli.Format.LCA, srcWorld, null, true, false, true
        ));
        assertEquals(0, dryRun.exitCode());
        assertEquals(1, dryRun.regionsScanned());
        assertEquals(2, dryRun.chunksConverted());
        assertTrue(dryRun.estimatedWallTimeMs() >= 1L, "Dry-run must report projected conversion wall time");
        assertFalse(Files.exists(lcaWorld));
        assertEquals(originalMcaBytes.length, Files.readAllBytes(srcMca).length);

        // 2. .mca -> .lca with --verify
        final LcaMigrationCli.Report toLca = LcaMigrationCli.run(new LcaMigrationCli.Options(
            LcaMigrationCli.Format.MCA, LcaMigrationCli.Format.LCA, srcWorld, lcaWorld, false, true, true
        ));
        assertEquals(0, toLca.exitCode(), () -> "Errors: " + toLca.errors());
        assertEquals(2, toLca.chunksConverted());
        assertEquals(2, toLca.chunksVerified());
        assertEquals(0, toLca.verifyMismatches());
        assertTrue(Files.exists(srcMca), "Source .mca must never be deleted");
        assertTrue(Files.exists(lcaWorld.resolve("region").resolve("r.0.0.lca")));

        // 3. .lca -> .mca reverse migration with --verify
        final LcaMigrationCli.Report backToMca = LcaMigrationCli.run(new LcaMigrationCli.Options(
            LcaMigrationCli.Format.LCA, LcaMigrationCli.Format.MCA, lcaWorld, restoredMcaWorld, false, true, true
        ));
        assertEquals(0, backToMca.exitCode(), () -> "Errors: " + backToMca.errors());
        assertEquals(2, backToMca.chunksConverted());
        assertEquals(2, backToMca.chunksVerified());
        assertEquals(0, backToMca.verifyMismatches());
        assertTrue(Files.exists(restoredMcaWorld.resolve("region").resolve("r.0.0.mca")));
    }

    @Test
    void corruptMcaTriggersNonZeroExitCode(@TempDir final Path tempDir) throws Exception {
        final Path corruptWorld = tempDir.resolve("corrupt-world");
        final Path badMca = corruptWorld.resolve("region").resolve("r.0.0.mca");
        Files.createDirectories(badMca.getParent());
        Files.write(badMca, new byte[128]); // truncated header (< 8192 bytes)

        final LcaMigrationCli.Report report = LcaMigrationCli.run(new LcaMigrationCli.Options(
            LcaMigrationCli.Format.MCA,
            LcaMigrationCli.Format.LCA,
            corruptWorld,
            tempDir.resolve("out"),
            true,
            false,
            true
        ));
        assertEquals(1, report.exitCode());
        assertTrue(report.corruptChunks() >= 1);
        assertFalse(report.errors().isEmpty());
    }

    @Test
    void hardwareAdvisorEmitsActionableAdvisoriesWithoutMutatingConfig() {
        final GoldLeafConfig cfg = new GoldLeafConfig();
        cfg.protocols.servuxEnabled = true;
        cfg.storage.format = "lca";

        final HardwareEnvelope hw = HardwareEnvelope.of(4, 4096L * 1024L * 1024L);
        final List<HardwareConfigAdvisor.Advisory> advisories = HardwareConfigAdvisor.evaluate(
            new HardwareConfigAdvisor.EvaluationInput(hw, cfg, 12, 8, true, false)
        );
        assertEquals(4, advisories.size());
        assertTrue(advisories.stream().anyMatch(a -> "THREAD_OVERPROVISION".equals(a.code())));
        assertTrue(advisories.stream().anyMatch(a -> "HOPPER_CHECK_PARITY".equals(a.code())));
        assertTrue(advisories.stream().anyMatch(a -> "ACTIVATION_RANGE_PARITY".equals(a.code())));
        assertTrue(advisories.stream().anyMatch(a -> "LCA_BACKUP_REMINDER".equals(a.code())));

        final String summary = GoldLeafStartupSummary.formatWithAdvisories(
            new GoldLeafStartupSummary.SummaryInput(hw, 12, 4, "lca", true, 5, 0, 0),
            advisories
        );
        assertTrue(summary.contains("Advisories (ADR-014):"));
        assertTrue(summary.contains("THREAD_OVERPROVISION"));
    }

    @Test
    void paperUnsupportedSettingsDefaultsRemainIntact() throws Exception {
        // Verify Paper's technical-vanilla UnsupportedSettings fields exist on GlobalConfiguration
        // and default to false with zero hidden overrides (ADR-014 §3).
        final GlobalConfiguration globalConfig = new GlobalConfiguration();
        final GlobalConfiguration.UnsupportedSettings unsupportedInstance = globalConfig.new UnsupportedSettings();
        final Class<?> unsupportedClass = GlobalConfiguration.UnsupportedSettings.class;
        final List<String> requiredFields = List.of(
            "allowPistonDuplication",
            "allowPermanentBlockBreakExploits",
            "allowHeadlessPistons",
            "allowUnsafeEndPortalTeleportation",
            "skipTripwireHookPlacementValidation"
        );
        for (final String fieldName : requiredFields) {
            final Field field = unsupportedClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            assertEquals(boolean.class, field.getType(), "Expected boolean field " + fieldName);
            assertFalse(field.getBoolean(unsupportedInstance), "Expected default false for " + fieldName);
        }
        final GoldLeafConfig defaults = new GoldLeafConfig();
        assertEquals("lca", defaults.storage.format);
        assertEquals("mode-a", defaults.storage.lcaMode);
        assertFalse(defaults.storage.lcaModeBLcd2);
        assertFalse(defaults.storage.autoMigrateMca);
        assertTrue(defaults.storage.backupMigratedMca);
    }

    @Test
    void dropInExistingMcaWorldReadsWithoutMutationWhenAutoMigrateDisabled(@TempDir final Path tempDir) throws Exception {
        final Path regionDir = tempDir.resolve("world").resolve("region");
        final Path srcMca = regionDir.resolve("r.0.0.mca");
        final Map<Integer, byte[]> regionChunks = new LinkedHashMap<>();
        regionChunks.put(0, sampleChunkNbt(0, 0, "dropin-parity"));
        LcaMigrationCli.writeMcaRegionAtomic(srcMca, regionChunks);
        final byte[] beforeBytes = Files.readAllBytes(srcMca);

        final GoldLeafConfig.Storage storageCfg = new GoldLeafConfig.Storage();
        storageCfg.autoMigrateMca = false;
        try (LcaRegionStorageProvider provider = LcaRegionStorageProvider.open(regionDir, storageCfg)) {
            // With autoMigrateMca=false, LcaRegionStorageProvider must not silently migrate or mutate .mca on read
            assertEquals(null, provider.read(0, 0));
        }
        assertFalse(Files.exists(regionDir.resolve("r.0.0.lca")), "Read-only drop-in must not mutate world into .lca");
        assertEquals(beforeBytes.length, Files.readAllBytes(srcMca).length, "Original .mca bytes must remain untouched");
    }
}
