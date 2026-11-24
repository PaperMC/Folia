package dev.leafcraft.goldleaf.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldLeafConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void createsDefaultsFileOnFirstLoad() throws Exception {
        final Path configFile = this.tempDir.resolve("config").resolve(GoldLeafConfig.FILE_NAME);
        final GoldLeafConfig config = GoldLeafConfig.load(configFile);

        assertTrue(Files.exists(configFile));
        assertEquals(GoldLeafConfig.CURRENT_VERSION, config.configVersion);
        assertTrue(config.threading.autoTuneWorkstation);
        assertEquals(1, config.threading.minAutoThreads);
        assertEquals(-1, config.threading.maxAutoThreads);
        assertTrue(config.diagnostics.logHardwareEnvelopeOnStartup);
        assertTrue(config.network.lcaNetV1Enabled);
        assertTrue(config.network.lcaNetBatchLcd2);
        assertTrue(config.network.lcaNetProxySafeStoredZlib);
        assertEquals(4096, config.network.lcaNetMaxSessionCacheChunks);
        assertFalse(config.network.leafShardTier4RpcEnabled);
        assertEquals(50, config.network.leafShardTier4RpcTimeoutMs);
        assertEquals(256, config.network.leafShardTier4MaxInFlightRpcs);
        assertTrue(config.worldgen.sectionBatchedDoFill);
        assertEquals("vanilla-exact", config.worldgen.parityMode);
        assertFalse(config.worldgen.requireAccelerator);
        assertEquals(24, config.worldgen.waveMaxSize);
        assertEquals(2, config.worldgen.waveFlushDeadlineMs);
        assertTrue(config.worldgen.autoPaceGenerationWorkers);
        assertEquals(0, config.worldgen.applyPoolThreads);
        assertFalse(config.worldgen.gpuSurfaceEnabled);
        assertFalse(config.worldgen.hilbertOreVeins);
        assertFalse(config.worldgen.gpuLightEnabled);
        assertFalse(config.storage.skipSaveUnmodifiedColdChunks);
        assertEquals(200L, config.storage.coldChunkMinInhabitedTicks);
        assertEquals(2, config.storage.coldChunkMinRevisits);
        assertTrue(config.diagnostics.bootTelemetryEnabled);
        assertEquals(250, config.diagnostics.bootTelemetrySlowPluginMs);
        assertTrue(config.diagnostics.shutdownNonDaemonThreadAudit);
        assertTrue(config.diagnostics.pluginIoPrefetchEnabled);
        assertEquals(4, config.diagnostics.pluginIoPrefetchThreads);
        assertEquals(64L * 1024L * 1024L, config.diagnostics.pluginIoPrefetchMaxFileBytes);
        assertEquals(512L * 1024L * 1024L, config.diagnostics.pluginIoPrefetchMaxTotalBytes);
    }

    @Test
    void loadsAndPreservesCustomValues() throws Exception {
        final Path configFile = this.tempDir.resolve(GoldLeafConfig.FILE_NAME);
        Files.writeString(
            configFile,
            """
                config-version: 1
                threading:
                  auto-tune-workstation: false
                  min-auto-threads: 2
                  max-auto-threads: 6
                diagnostics:
                  log-hardware-envelope-on-startup: false
                """,
            StandardCharsets.UTF_8
        );

        final GoldLeafConfig config = GoldLeafConfig.load(configFile);
        assertFalse(config.threading.autoTuneWorkstation);
        assertEquals(2, config.threading.minAutoThreads);
        assertEquals(6, config.threading.maxAutoThreads);
        assertFalse(config.diagnostics.logHardwareEnvelopeOnStartup);
    }

    @Test
    void failsClosedOnInvalidBounds() throws Exception {
        final Path configFile = this.tempDir.resolve(GoldLeafConfig.FILE_NAME);
        Files.writeString(
            configFile,
            """
                threading:
                  min-auto-threads: 4
                  max-auto-threads: 2
                """,
            StandardCharsets.UTF_8
        );

        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(configFile));
    }

    @Test
    void failsClosedOnMalformedYaml() throws Exception {
        final Path configFile = this.tempDir.resolve(GoldLeafConfig.FILE_NAME);
        Files.writeString(configFile, "threading:\n  auto-tune-workstation: [unterminated\n", StandardCharsets.UTF_8);

        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(configFile));
    }

    @Test
    void failsClosedOnInvalidNetworkBounds() throws Exception {
        final Path configFile = this.tempDir.resolve(GoldLeafConfig.FILE_NAME);
        Files.writeString(
            configFile,
            """
                network:
                  leafshard-tier4-rpc-timeout-ms: 1
                """,
            StandardCharsets.UTF_8
        );

        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(configFile));
    }

    @Test
    void failsClosedOnInvalidWorldgenBounds() throws Exception {
        final Path badParity = this.tempDir.resolve("bad-parity.yml");
        Files.writeString(
            badParity,
            """
                worldgen:
                  parity-mode: invalid-mode
                """,
            StandardCharsets.UTF_8
        );
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(badParity));

        final Path badWaveSize = this.tempDir.resolve("bad-wave-size.yml");
        Files.writeString(
            badWaveSize,
            """
                worldgen:
                  wave-max-size: 0
                """,
            StandardCharsets.UTF_8
        );
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(badWaveSize));

        final Path badDeadline = this.tempDir.resolve("bad-deadline.yml");
        Files.writeString(
            badDeadline,
            """
                worldgen:
                  wave-flush-deadline-ms: 25
                """,
            StandardCharsets.UTF_8
        );
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(badDeadline));
    }

    @Test
    void loadsSupportedWorldgenParityModesIncludingOptInFp64() throws Exception {
        for (final String mode : new String[]{"vanilla-exact", "vanilla-exact-fp64", "fast"}) {
            final Path cfg = this.tempDir.resolve("parity-" + mode + ".yml");
            Files.writeString(
                cfg,
                "worldgen:\n  parity-mode: " + mode + "\n",
                StandardCharsets.UTF_8
            );
            final GoldLeafConfig loaded = GoldLeafConfig.load(cfg);
            assertEquals(mode, loaded.worldgen.parityMode);
        }
        assertEquals(
            dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT,
            dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.ParityMode.fromConfig("vanilla-exact")
        );
        assertEquals(
            dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT_FP64,
            dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.ParityMode.fromConfig("vanilla-exact-fp64")
        );
        assertEquals(
            dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.ParityMode.FAST,
            dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.ParityMode.fromConfig("fast")
        );
    }

    @Test
    void validatesApplyPoolThreadsBounds() throws Exception {
        final Path valid = this.tempDir.resolve("valid-apply-threads.yml");
        Files.writeString(valid, "worldgen:\n  apply-pool-threads: 6\n", StandardCharsets.UTF_8);
        assertEquals(6, GoldLeafConfig.load(valid).worldgen.applyPoolThreads);

        final Path badNegative = this.tempDir.resolve("bad-apply-neg.yml");
        Files.writeString(badNegative, "worldgen:\n  apply-pool-threads: -1\n", StandardCharsets.UTF_8);
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(badNegative));

        final Path badTooLarge = this.tempDir.resolve("bad-apply-max.yml");
        Files.writeString(badTooLarge, "worldgen:\n  apply-pool-threads: 65\n", StandardCharsets.UTF_8);
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(badTooLarge));
    }

    @Test
    void validatesColdChunkSkipSavePolicyAndBounds() throws Exception {
        final Path badInhabited = this.tempDir.resolve("bad-cold-inhabited.yml");
        Files.writeString(badInhabited, "storage:\n  cold-chunk-min-inhabited-ticks: -1\n", StandardCharsets.UTF_8);
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(badInhabited));

        final Path badRevisits = this.tempDir.resolve("bad-cold-revisits.yml");
        Files.writeString(badRevisits, "storage:\n  cold-chunk-min-revisits: -1\n", StandardCharsets.UTF_8);
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(badRevisits));

        final var policy = new dev.leafcraft.goldleaf.storage.ColdChunkSavePolicy();
        final long key = 0x0000_000A_0000_0014L;
        // Disabled -> never skips
        assertFalse(policy.shouldSkipSave("minecraft:overworld", true, true, false, false, 0L, key, false, 200L, 2));
        // Disk-loaded chunk -> never skips
        assertFalse(policy.shouldSkipSave("minecraft:overworld", true, false, false, false, 0L, key, true, 200L, 2));
        // Fresh unmodified cold ProtoChunk -> skips
        assertTrue(policy.shouldSkipSave("minecraft:overworld", false, true, false, false, 0L, key, true, 200L, 2));
        assertEquals(1L, policy.skippedProtoChunkSaves());
        // Fresh unmodified cold LevelChunk (visit 1 < minRevisits=2) -> skips
        assertEquals(1, policy.recordFreshGeneration("minecraft:overworld", key));
        assertTrue(policy.shouldSkipSave("minecraft:overworld", true, true, false, false, 0L, key, true, 200L, 2));
        assertEquals(1L, policy.skippedLevelChunkSaves());
        // Post-gen modified -> persists
        assertFalse(policy.shouldSkipSave("minecraft:overworld", true, true, true, false, 0L, key, true, 200L, 2));
        assertEquals(1L, policy.persistedModified());
        // PDC dirty -> persists
        assertFalse(policy.shouldSkipSave("minecraft:overworld", true, true, false, true, 0L, key, true, 200L, 2));
        assertEquals(2L, policy.persistedModified());
        // Warm (inhabitedTime >= threshold) -> persists
        assertFalse(policy.shouldSkipSave("minecraft:overworld", true, true, false, false, 200L, key, true, 200L, 2));
        assertEquals(1L, policy.persistedWarm());
        // Revisited (visit 2 >= minRevisits=2) -> graduates to persistent
        assertEquals(2, policy.recordFreshGeneration("minecraft:overworld", key));
        assertFalse(policy.shouldSkipSave("minecraft:overworld", true, true, false, false, 0L, key, true, 200L, 2));
        assertEquals(1L, policy.persistedRevisited());

        // Verify explicit opt-in defaults
        final GoldLeafConfig defaults = new GoldLeafConfig();
        assertTrue(defaults.worldgen.gpuCarverMask);
        assertFalse(defaults.worldgen.macroNoiseOctaves);
        assertFalse(defaults.worldgen.gpuInVramFeatures);
        assertFalse(defaults.storage.coldChunkIgnoreNaturalTicks);
        assertTrue(defaults.worldgen.directionalChunkPriority.enabled);
        assertEquals(0.45D, defaults.worldgen.directionalChunkPriority.lookBias);
        assertEquals(0.75D, defaults.worldgen.directionalChunkPriority.velocityBias);
        assertEquals(2, defaults.worldgen.directionalChunkPriority.innerRadialRadius);

        final Path badDirBias = this.tempDir.resolve("bad-dir-bias.yml");
        Files.writeString(
            badDirBias,
            "worldgen:\n  directional-chunk-priority:\n    velocity-bias: 0.95\n",
            StandardCharsets.UTF_8
        );
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(badDirBias));
    }

    @Test
    void initReusesOpenClAcceleratorWhenConfigUnchangedAndRecreatesOnChange() throws Exception {
        if (!dev.leafcraft.goldleaf.worldgen.GoldLeafOpenClBridge.isAvailable()) {
            return;
        }
        final Path configDir = this.tempDir.resolve("config");
        Files.createDirectories(configDir);
        final Path configFile = configDir.resolve(GoldLeafConfig.FILE_NAME);
        try {
            Files.writeString(
                configFile,
                """
                    worldgen:
                      opencl-gpu-enabled: true
                      wave-max-size: 16
                    """,
                StandardCharsets.UTF_8
            );
            GoldLeafConfig.init(configDir);
            final var first = dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.get();
            assertTrue(first instanceof dev.leafcraft.goldleaf.worldgen.IrOpenClTerrainAccelerator);

            // Second init with identical config (Paper ServerMain + Server thread) must reuse instance
            GoldLeafConfig.init(configDir);
            final var second = dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.get();
            org.junit.jupiter.api.Assertions.assertSame(first, second);

            // Changing accelerator construction parameter must recreate cleanly
            Files.writeString(
                configFile,
                """
                    worldgen:
                      opencl-gpu-enabled: true
                      wave-max-size: 24
                    """,
                StandardCharsets.UTF_8
            );
            GoldLeafConfig.init(configDir);
            final var third = dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.get();
            assertTrue(third instanceof dev.leafcraft.goldleaf.worldgen.IrOpenClTerrainAccelerator);
            org.junit.jupiter.api.Assertions.assertNotSame(first, third);
        } finally {
            final var active = dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.get();
            dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.resetDefault();
            if (active instanceof dev.leafcraft.goldleaf.worldgen.IrOpenClTerrainAccelerator openCl) {
                openCl.close();
            }
        }
    }
}
