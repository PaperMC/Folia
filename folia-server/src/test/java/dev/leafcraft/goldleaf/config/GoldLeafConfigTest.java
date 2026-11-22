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
        assertFalse(config.network.lcaNetV1Enabled);
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
}
