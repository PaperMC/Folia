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
}
