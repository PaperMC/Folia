package dev.leafcraft.goldleaf.config;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.objectmapping.meta.Setting;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Global configuration loader and holder for {@code config/goldleaf.yml}.
 * Fails closed on malformed YAML or I/O faults rather than silently discarding errors.
 */
@ConfigSerializable
public final class GoldLeafConfig {

    public static final String FILE_NAME = "goldleaf.yml";
    public static final int CURRENT_VERSION = 1;

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final String HEADER = """
        GoldLeaf global configuration.
        GoldLeaf preserves vanilla redstone and technical farm mechanics by default.
        Settings in this file control hardware-envelope auto-tuning and diagnostics.""";

    private static volatile GoldLeafConfig instance = defaults();

    @Setting("config-version")
    @Comment("Internal configuration schema version. Do not modify manually.")
    public int configVersion = CURRENT_VERSION;

    @Setting("threading")
    @Comment("Mechanics-neutral Folia region tick-thread auto-tuning settings.")
    public Threading threading = new Threading();

    @Setting("diagnostics")
    @Comment("Startup diagnostics and hardware-envelope logging.")
    public Diagnostics diagnostics = new Diagnostics();

    @Setting("storage")
    @Comment("Region disk storage format selection and LCA-ICD-3.0 codec settings (ADR-005).")
    public Storage storage = new Storage();

    @ConfigSerializable
    public static final class Threading {

        @Setting("auto-tune-workstation")
        @Comment(
            "When true and paper-global.yml threaded-regions.threads <= 0, unlocks multi-region\n"
                + "tick parallelism on 4-8+ core machines instead of collapsing to 1 tick thread."
        )
        public boolean autoTuneWorkstation = true;

        @Setting("min-auto-threads")
        @Comment("Lower bound applied when auto-tuning tick threads (must be >= 1).")
        public int minAutoThreads = 1;

        @Setting("max-auto-threads")
        @Comment("Optional upper bound applied when auto-tuning tick threads (-1 disables the cap).")
        public int maxAutoThreads = -1;
    }

    @ConfigSerializable
    public static final class Diagnostics {

        @Setting("log-hardware-envelope-on-startup")
        @Comment("Logs the detected hardware envelope and resolved tick-thread count on initialization.")
        public boolean logHardwareEnvelopeOnStartup = true;
    }

    @ConfigSerializable
    public static final class Storage {

        @Setting("format")
        @Comment("Region disk format: 'mca' (vanilla Anvil default) or 'lca' (native LCA-ICD-3.0).")
        public String format = "mca";

        @Setting("lca-mode-b-lcd2")
        @Comment("Enables Mode B LCD2 fast-greedy compression for .lca regions (v3-Columnar default).")
        public boolean lcaModeBLcd2 = true;

        @Setting("auto-migrate-mca")
        @Comment("When format is 'lca', transparently reads and migrates legacy .mca region files on demand.")
        public boolean autoMigrateMca = false;

        @Setting("backup-migrated-mca")
        @Comment("Retains a backup of migrated legacy .mca files when auto-migrate-mca is enabled.")
        public boolean backupMigratedMca = true;

        @Setting("max-open-regions")
        @Comment("Maximum number of open .lca region archives cached per storage folder (must be >= 1).")
        public int maxOpenRegions = 256;
    }

    public static GoldLeafConfig defaults() {
        return new GoldLeafConfig();
    }

    public static GoldLeafConfig get() {
        return instance;
    }

    public static GoldLeafConfig init(final Path configDirectory) throws ConfigurateException {
        final GoldLeafConfig loaded = load(configDirectory.resolve(FILE_NAME));
        instance = loaded;
        if (loaded.diagnostics.logHardwareEnvelopeOnStartup) {
            LOGGER.info("GoldLeaf hardware envelope detected: {}", HardwareEnvelope.detect().describe());
        }
        return loaded;
    }

    public static GoldLeafConfig load(final Path configFile) throws ConfigurateException {
        try {
            final Path parent = configFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (final IOException ex) {
            LOGGER.error("Failed to create GoldLeaf config directory for {}", configFile, ex);
            throw new ConfigurateException(ex);
        }

        final YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
            .path(configFile)
            .nodeStyle(NodeStyle.BLOCK)
            .indent(2)
            .defaultOptions(opts -> opts.header(HEADER).shouldCopyDefaults(true))
            .build();

        try {
            final CommentedConfigurationNode root = loader.load();
            final GoldLeafConfig config = root.get(GoldLeafConfig.class, defaults());
            config.validate();
            root.set(GoldLeafConfig.class, config);
            loader.save(root);
            return config;
        } catch (final ConfigurateException ex) {
            LOGGER.error("Failed to load or save GoldLeaf configuration at {}", configFile, ex);
            throw ex;
        }
    }

    public void validate() throws ConfigurateException {
        if (this.threading == null) {
            this.threading = new Threading();
        }
        if (this.diagnostics == null) {
            this.diagnostics = new Diagnostics();
        }
        if (this.storage == null) {
            this.storage = new Storage();
        }
        if (this.storage.format == null) {
            throw new ConfigurateException("goldleaf.yml storage.format must not be null (expected 'mca' or 'lca')");
        }
        final String normalizedFormat = this.storage.format.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"mca".equals(normalizedFormat) && !"lca".equals(normalizedFormat)) {
            throw new ConfigurateException(
                "goldleaf.yml storage.format must be 'mca' or 'lca', got '" + this.storage.format + "'"
            );
        }
        this.storage.format = normalizedFormat;
        if (this.storage.maxOpenRegions < 1) {
            throw new ConfigurateException(
                "goldleaf.yml storage.max-open-regions must be >= 1, got " + this.storage.maxOpenRegions
            );
        }
        if (this.threading.minAutoThreads < 1) {
            throw new ConfigurateException(
                "goldleaf.yml threading.min-auto-threads must be >= 1, got " + this.threading.minAutoThreads
            );
        }
        if (this.threading.maxAutoThreads != -1 && this.threading.maxAutoThreads < this.threading.minAutoThreads) {
            throw new ConfigurateException(
                "goldleaf.yml threading.max-auto-threads (" + this.threading.maxAutoThreads
                    + ") must be -1 or >= threading.min-auto-threads (" + this.threading.minAutoThreads + ")"
            );
        }
        this.configVersion = CURRENT_VERSION;
    }
}
