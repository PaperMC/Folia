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

    @Setting("network")
    @Comment("Layer 2 LCA_NET_V1 negotiated chunk streaming and LeafShard Tier 4 RPC settings (ADR-002, ADR-005).")
    public Network network = new Network();

    @Setting("worldgen")
    @Comment("Layer 1 worldgen acceleration SPI, section-batched doFill, wave coalescer, and worker pacing (ADR-004).")
    public Worldgen worldgen = new Worldgen();

    @Setting("protocols")
    @Comment("Opt-in region-safe technical client protocol SPI settings (all protocols default false; ADR-010).")
    public Protocols protocols = new Protocols();

    @Setting("anti-exploit")
    @Comment("Adaptive per-player chunk-gen backpressure and decode-time packet/NBT hardening (ADR-012).")
    public AntiExploit antiExploit = new AntiExploit();

    @Setting("bots")
    @Comment("Opt-in region-safe fake player / bot SPI guardrails (default false; ADR-015).")
    public Bots bots = new Bots();

    @Setting("security")
    @Comment("Opt-in chat-signing disable and security settings (default false; ADR-018).")
    public Security security = new Security();

    @Setting("compute")
    @Comment("Public ComputeService off-tick GPU/CPU compute SPI settings (ADR-004, ADR-017).")
    public Compute compute = new Compute();

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

        @Setting("chunk-telemetry-spi")
        @Comment("Exposes Layer 1 chunk telemetry and ChunkExecutionMask SPI (idle cost ~0 ns, ADR-006).")
        public boolean chunkTelemetrySpi = true;

        @Setting("chunk-telemetry-max-listeners")
        @Comment("Bounded listener cap for ChunkTickTelemetry to prevent runaway plugin fan-out (must be >= 1).")
        public int chunkTelemetryMaxListeners = 4;

        @Setting("plugin-safety-preflight")
        @Comment("Runs off-thread zero-classloading plugin archive preflight discovery at boot (ADR-007).")
        public boolean pluginSafetyPreflight = true;

        @Setting("plugin-safety-preflight-fail-closed")
        @Comment("When true, aborts startup on corrupt plugin archives or critical preflight hazards (CI strict mode).")
        public boolean pluginSafetyPreflightFailClosed = false;

        @Setting("runtime-hazard-hooks")
        @Comment("Opt-in O(1) tick-thread runtime hazard counters and callbacks for LeafFuzz / QA (false in production).")
        public boolean runtimeHazardHooks = false;

        @Setting("runtime-hazard-stall-threshold-ms")
        @Comment("Tick duration threshold in milliseconds for TICK_STALL hazard events (must be >= 20).")
        public int runtimeHazardStallThresholdMs = 100;

        @Setting("watchdog-enriched-context")
        @Comment("Appends lock-free region, nearby player, hot-chunk (ADR-006), and preflight-plugin (ADR-007) context to FoliaWatchdogThread dumps (ADR-011).")
        public boolean watchdogEnrichedContext = true;

        @Setting("watchdog-max-players")
        @Comment("Maximum nearby players listed per stalled region in watchdog dumps (must be >= 1; ADR-011).")
        public int watchdogMaxPlayers = 8;

        @Setting("watchdog-top-hot-chunks")
        @Comment("Maximum hot chunks ranked by ChunkActivityCounters in watchdog dumps (must be >= 1; ADR-011).")
        public int watchdogTopHotChunks = 5;

        @Setting("live-region-hud-enabled")
        @Comment("Allows permissioned admins to toggle the zero-idle-overhead live region HUD (/goldleaf hud; ADR-011).")
        public boolean liveRegionHudEnabled = true;

        @Setting("live-region-hud-interval-ticks")
        @Comment("Update interval in ticks for subscribed live region HUDs (must be >= 1; ADR-011).")
        public int liveRegionHudIntervalTicks = 20;

        @Setting("startup-summary-enabled")
        @Comment("Logs one concise startup summary covering hardware threads, active storage format + converter, and plugin preflight counts (ADR-011).")
        public boolean startupSummaryEnabled = true;

        @Setting("boot-telemetry-enabled")
        @Comment("Records monotonic boot/shutdown phase spans and logs slow plugin onEnable attribution (ADR-016).")
        public boolean bootTelemetryEnabled = true;

        @Setting("boot-telemetry-slow-plugin-ms")
        @Comment("Minimum plugin onEnable duration in milliseconds reported in the boot waterfall slow-plugin list (must be >= 1; ADR-016).")
        public int bootTelemetrySlowPluginMs = 250;

        @Setting("shutdown-non-daemon-thread-audit")
        @Comment("Audits lingering non-daemon threads at the end of server shutdown to diagnose /stop hangs (ADR-016).")
        public boolean shutdownNonDaemonThreadAudit = true;

        @Setting("plugin-io-prefetch-enabled")
        @Comment("Pre-faults plugins/*.jar and plugins/*/*.yml into the OS page cache on bounded daemon workers before loadPlugins (ADR-016).")
        public boolean pluginIoPrefetchEnabled = true;

        @Setting("plugin-io-prefetch-threads")
        @Comment("Maximum bounded daemon worker threads for boot-time plugin JAR/YAML page-cache prefetch (must be in [1, 16]; ADR-016).")
        public int pluginIoPrefetchThreads = 4;

        @Setting("plugin-io-prefetch-max-file-bytes")
        @Comment("Maximum bytes prefetched per individual plugin file (must be >= 1048576; ADR-016).")
        public long pluginIoPrefetchMaxFileBytes = 64L * 1024L * 1024L;

        @Setting("plugin-io-prefetch-max-total-bytes")
        @Comment("Maximum aggregate bytes prefetched across all plugin files during boot (must be >= plugin-io-prefetch-max-file-bytes; ADR-016).")
        public long pluginIoPrefetchMaxTotalBytes = 512L * 1024L * 1024L;
    }

    @ConfigSerializable
    public static final class Protocols {

        @Setting("servux-enabled")
        @Comment("Opt-in Servux protocol support for MiniHUD/Litematica structures and metadata (default false; ADR-010).")
        public boolean servuxEnabled = false;

        @Setting("servux-max-structure-radius")
        @Comment("Maximum chunk radius for Servux structure bounding-box queries (must be >= 1; ADR-010).")
        public int servuxMaxStructureRadius = 128;

        @Setting("syncmatica-enabled")
        @Comment("Opt-in Syncmatica shared schematic synchronization with async disk I/O (default false; ADR-010).")
        public boolean syncmaticaEnabled = false;

        @Setting("syncmatica-max-file-bytes")
        @Comment("Maximum allowed schematic file size in bytes for Syncmatica uploads (must be >= 1024; ADR-010).")
        public int syncmaticaMaxFileBytes = 2 * 1024 * 1024;

        @Setting("jade-enabled")
        @Comment("Opt-in Jade/WTHIT server-authoritative tooltip inspection within player reach (default false; ADR-010).")
        public boolean jadeEnabled = false;

        @Setting("appleskin-enabled")
        @Comment("Opt-in AppleSkin saturation/exhaustion delta synchronization (default false; ADR-010).")
        public boolean appleskinEnabled = false;

        @Setting("map-world-id-enabled")
        @Comment("Opt-in XaeroMap/JourneyMap deterministic world/shard UUID announcement (default false; ADR-010).")
        public boolean mapWorldIdEnabled = false;

        @Setting("max-payload-bytes")
        @Comment("Hard per-channel custom payload byte cap enforced before handler dispatch (must be >= 256; ADR-010).")
        public int maxPayloadBytes = 1024 * 1024;

        @Setting("max-packets-per-second")
        @Comment("Per-player token-bucket rate limit for technical protocol payloads per second (must be >= 1; ADR-010).")
        public int maxPacketsPerSecond = 60;
    }

    @ConfigSerializable
    public static final class AntiExploit {

        @Setting("chunk-gen-backpressure-enabled")
        @Comment("Enables adaptive per-player chunk-gen backpressure; zero effect below queue saturation watermark (ADR-012).")
        public boolean chunkGenBackpressureEnabled = true;

        @Setting("chunk-gen-saturation-watermark")
        @Comment("Pending world chunk-generation queue depth above which per-player debt pacing activates (must be >= 1; ADR-012).")
        public int chunkGenSaturationWatermark = 64;

        @Setting("chunk-gen-min-send-distance")
        @Comment("Retained for YAML compatibility (must be >= 2; ADR-012). Backpressure paces chunk generation rate only and does not shrink client view distance.")
        public int chunkGenMinSendDistance = 4;

        @Setting("chunk-gen-debt-decay-seconds")
        @Comment("Half-life / decay window in seconds for per-player generation debt recovery (must be >= 1; ADR-012).")
        public int chunkGenDebtDecaySeconds = 15;

        @Setting("packet-hardening-enabled")
        @Comment("Enforces tighter serverbound NBT depth/byte quotas and structural book/UTF-8 caps at Netty decode time (ADR-012).")
        public boolean packetHardeningEnabled = true;

        @Setting("serverbound-nbt-max-bytes")
        @Comment("Maximum NBT byte quota for untrusted serverbound client packets; does not affect disk saves (must be >= 4096; ADR-012).")
        public int serverboundNbtMaxBytes = 2 * 1024 * 1024;

        @Setting("serverbound-nbt-max-depth")
        @Comment("Maximum NBT nesting depth for untrusted serverbound client packets; disk saves remain 512 (must be in [8, 512]; ADR-012).")
        public int serverboundNbtMaxDepth = 64;

        @Setting("max-book-pages")
        @Comment("Maximum pages accepted in serverbound book-edit payloads (must be >= 1; ADR-012).")
        public int maxBookPages = 100;

        @Setting("max-book-page-utf8-bytes")
        @Comment("Maximum UTF-8 byte length per book page in serverbound book-edit payloads (must be >= 64; ADR-012).")
        public int maxBookPageUtf8Bytes = 1024;

        @Setting("max-malformed-packets-per-second")
        @Comment("Per-connection malformed-packet threshold before fail-closed INVALID_PAYLOAD disconnect (must be >= 1; ADR-012).")
        public int maxMalformedPacketsPerSecond = 5;
    }

    @ConfigSerializable
    public static final class Bots {

        @Setting("enabled")
        @Comment("Opt-in region-safe synthetic player / bot SPI for technical SMP farms and LeafFuzz (default false; ADR-015).")
        public boolean enabled = false;

        @Setting("max-total")
        @Comment("Global hard cap on simultaneous synthetic players across the server (must be >= 1; ADR-015).")
        public int maxTotal = 16;

        @Setting("max-per-player")
        @Comment("Per-owner hard cap on simultaneous synthetic players (must be >= 1; ADR-015).")
        public int maxPerPlayer = 2;

        @Setting("allow-chunk-loading")
        @Comment("Whether synthetic players are permitted to keep surrounding chunks loaded (default false; ADR-015).")
        public boolean allowChunkLoading = false;
    }

    @ConfigSerializable
    public static final class Storage {

        @Setting("format")
        @Comment("Region disk format: 'lca' (native LCA-ICD-3.0 default with non-destructive .mca auto-detect) or 'mca' (vanilla Anvil).")
        public String format = "lca";

        @Setting("lca-mode")
        @Comment("LCA-ICD-3.0 storage profile: 'mode-a' (default unified disk=memory columnar format with cached region dictionaries and zero outer decompression), 'mode-b' (opt-in LCD2 compression), or 'archival' (max compression).")
        public String lcaMode = "mode-a";

        @Setting("lca-mode-b-lcd2")
        @Comment("Opt-in legacy Mode B LCD2 compression for .lca regions (false by default; unified Mode A is the default).")
        public boolean lcaModeBLcd2 = false;

        @Setting("auto-migrate-mca")
        @Comment("When format is 'lca', transparently reads and migrates legacy .mca region files on demand.")
        public boolean autoMigrateMca = false;

        @Setting("backup-migrated-mca")
        @Comment("Retains a backup of migrated legacy .mca files when auto-migrate-mca is enabled.")
        public boolean backupMigratedMca = true;

        @Setting("max-open-regions")
        @Comment("Maximum number of open .lca region archives cached per storage folder (must be >= 1).")
        public int maxOpenRegions = 256;

        @Setting("lca-native-section-backing")
        @Comment("Backs loaded .lca PalettedContainers natively with immutable LcaSectionBacking + sparse dirty overlay, bypassing voxel NBT decode on load (ADR-005 Stage 4).")
        public boolean lcaNativeSectionBacking = true;

        @Setting("lazy-entity-hydration")
        @Comment("Defers entity deserialization for disk-loaded outer-ring exploration chunks until ENTITY_TICKING or direct entity access (ADR-005).")
        public boolean lazyEntityHydration = true;

        @Setting("lazy-entity-refreeze")
        @Comment("Re-freezes passive entities back into dormant pendingEntityChunk after a quiet window when exploration chunks downgrade out of ENTITY_TICKING (ADR-005).")
        public boolean lazyEntityRefreeze = true;

        @Setting("lca-hot-promote-dirty-threshold")
        @Comment("Unique dirty voxels per section before promoting that individual section to native mutable SimpleBitStorage (0 disables; ADR-005 Stage 4).")
        public int lcaHotPromoteDirtyThreshold = 64;

        @Setting("lca-compact-quiet-window-ticks")
        @Comment("Quiet ticks required before off-tick compaction demotes a dirty/HOT_MUTABLE section back to COLD_LCA (ADR-005 Stage 4).")
        public int lcaCompactQuietWindowTicks = 200;

        @Setting("lca-compact-pressure-quiet-window-ticks")
        @Comment("Tightened quiet-window ticks used when JVM heap occupancy exceeds lca-compact-heap-pressure-ratio.")
        public int lcaCompactPressureQuietWindowTicks = 40;

        @Setting("lca-compact-heap-pressure-ratio")
        @Comment("JVM heap occupancy ratio (0.1..0.95) above which section compaction tightens its quiet window.")
        public double lcaCompactHeapPressureRatio = 0.70;

        @Setting("lca-compact-generated-chunks")
        @Comment("When true, eagerly snapshots and compacts freshly generated LevelChunkSections into COLD_LCA at ChunkFullTask handoff (default false: avoids tick-thread voxel snapshotting during active generation and spawn boot; sections still compact on downgrade or disk reload).")
        public boolean lcaCompactGeneratedChunks = false;

        @Setting("skip-save-unmodified-cold-chunks")
        @Comment("Opt-in: skips saving freshly generated, unmodified, consistently unvisited chunks to disk so GPU regeneration replaces cold exploration storage (default false; disk-loaded, modified, warm, or revisited chunks always save normally).")
        public boolean skipSaveUnmodifiedColdChunks = false;

        @Setting("cold-chunk-min-inhabited-ticks")
        @Comment("Minimum player inhabitedTime ticks before a freshly generated unmodified chunk graduates to persistent storage (0 = persist on any visit, default 200 = 10s).")
        public long coldChunkMinInhabitedTicks = 200L;

        @Setting("cold-chunk-min-revisits")
        @Comment("Number of fresh generations of the same coordinates before an unvisited chunk graduates to persistent storage as statistically warm (0 disables revisit promotion, default 2).")
        public int coldChunkMinRevisits = 2;

        @Setting("cold-chunk-ignore-natural-ticks")
        @Comment("Opt-in: ignores natural fluid/block settling ticks on unvisited chunks (inhabitedTime == 0) when skip-save-unmodified-cold-chunks is enabled (default false).")
        public boolean coldChunkIgnoreNaturalTicks = false;
    }

    @ConfigSerializable
    public static final class Network {

        @Setting("lca-net-v1-enabled")
        @Comment("Enables native Layer 2 LCA_NET_V1 negotiated chunk streaming on PlayerChunkSender SPI (default true; unnegotiated vanilla clients fall back with zero overhead; ADR-005 Stage 3).")
        public boolean lcaNetV1Enabled = true;

        @Setting("require-lca-client")
        @Comment("When true, rejects non-LCA_NET_V1 clients instead of falling back to vanilla chunk packets (default false; ADR-005).")
        public boolean requireLcaClient = false;

        @Setting("lca-net-batch-lcd2")
        @Comment("Enables pure-Java LCD2 intra-batch compression for negotiated LCA_NET_V1 clients.")
        public boolean lcaNetBatchLcd2 = true;

        @Setting("lca-net-proxy-safe-stored-zlib")
        @Comment("Wraps LCA_NET_V1 batches in stored-block Zlib for Velocity/BungeeCord compatibility.")
        public boolean lcaNetProxySafeStoredZlib = true;

        @Setting("lca-net-max-session-cache-chunks")
        @Comment("Maximum client-known chunk digests cached per negotiated session (must be >= 64).")
        public int lcaNetMaxSessionCacheChunks = 4096;

        @Setting("leafshard-tier4-rpc-enabled")
        @Comment("Enables Layer 2 LeafShard Tier 4 non-blocking LSHD remote chunk-read RPC (ADR-002).")
        public boolean leafShardTier4RpcEnabled = false;

        @Setting("leafshard-tier4-rpc-timeout-ms")
        @Comment("Per-request timeout in milliseconds for Tier 4 remote shard RPCs (must be >= 5).")
        public int leafShardTier4RpcTimeoutMs = 50;

        @Setting("leafshard-tier4-max-in-flight-rpcs")
        @Comment("Maximum concurrent in-flight Tier 4 remote shard RPCs before failing closed (must be >= 1).")
        public int leafShardTier4MaxInFlightRpcs = 256;

        @Setting("entity-tracker-allocation-free-purge")
        @Comment("Reuses per-TrackedEntity scratch arrays during seenBy purge/clear passes to cut Young-Gen churn (ADR-009).")
        public boolean entityTrackerAllocationFreePurge = true;

        @Setting("entity-tracker-shared-broadcast-threshold")
        @Comment("Minimum seenBy viewer count before BroadcastPacketEncoder pre-encodes eligible entity packets once (0 disables, else >= 2; ADR-009).")
        public int entityTrackerSharedBroadcastThreshold = 8;

        @Setting("anti-xray-off-tick-encoding")
        @Comment("Opt-in off-tick chunk packet assembly and Anti-Xray obfuscation over immutable sectionEpoch snapshots (ADR-009).")
        public boolean antiXrayOffTickEncoding = false;

        @Setting("anti-xray-lca-fast-path")
        @Comment("Uses .lca palette and octreeOccupancyMask64 coarse skips for uniform/occluded sections during Anti-Xray (ADR-009).")
        public boolean antiXrayLcaFastPath = true;
    }

    @ConfigSerializable
    public static final class Worldgen {

        @Setting("opencl-gpu-enabled")
        @Comment("Enables GoldLeaf's native :bytecode-api IR-to-OpenCL GPU terrain accelerator (ADR-004/ADR-017).")
        public boolean openclGpuEnabled = false;

        @Setting("section-batched-do-fill")
        @Comment("Hoists LevelChunkSection lookups out of NoiseBasedChunkGenerator#doFill inner Y loop (100% vanilla-exact).")
        public boolean sectionBatchedDoFill = true;

        @Setting("parity-mode")
        @Comment("Terrain accelerator arithmetic contract: 'vanilla-exact' (INT64_Q32_FP32 default, zero FP64), 'fast' (relaxed FP32), or 'vanilla-exact-fp64' (opt-in diagnostic FP64).")
        public String parityMode = "vanilla-exact";

        @Setting("require-accelerator")
        @Comment("Strict anti-regression gate: fail closed instead of silently falling back to stock CPU when an accelerator declines or is absent.")
        public boolean requireAccelerator = false;

        @Setting("wave-max-size")
        @Comment("Maximum chunks coalesced into a single BIOMES/TERRAIN accelerator wave (1..64; ADR-004 target 16..32).")
        public int waveMaxSize = 24;

        @Setting("wave-flush-deadline-ms")
        @Comment("Maximum wait in milliseconds before flushing a partial accelerator wave (1..10; ADR-004 target 1..2).")
        public int waveFlushDeadlineMs = 2;

        @Setting("auto-pace-generation-workers")
        @Comment("Coordinates Moonrise generation worker count with GoldLeaf's hardware-envelope curve (ADR-003/ADR-004).")
        public boolean autoPaceGenerationWorkers = true;

        @Setting("apply-pool-threads")
        @Comment("Bounded CPU worker count for parallel GPU wave hostApply writeback (0 = auto max(2, cores/2), range 0..64).")
        public int applyPoolThreads = 0;

        @Setting("gpu-surface-enabled")
        @Comment("Opt-in fused GPU 4-bit TERRAIN+SURFACE stratification (auto-active in parity-mode 'fast'; disabled by default in 'vanilla-exact' to preserve vanilla SurfaceRules).")
        public boolean gpuSurfaceEnabled = false;

        @Setting("hilbert-ore-veins")
        @Comment("Opt-in O(size) 3D Hilbert-curve ore-vein placer replacing vanilla multi-sphere ore rasterization with contiguous veiny blobs (default false to preserve vanilla-exact ore geometry).")
        public boolean hilbertOreVeins = false;

        @Setting("gpu-light-enabled")
        @Comment("Opt-in vanilla-exact OpenCL GPU Sky+Block lighting offload (default false on single-GPU servers to avoid OpenCL driver contention with concurrent TERRAIN waves).")
        public boolean gpuLightEnabled = false;

        @Setting("gpu-carver-mask")
        @Comment("Vanilla-exact OpenCL GPU CaveWorldCarver + CanyonWorldCarver 9x9 mask generation (eliminates CPU CARVER_MASK and mask PCIe upload; default true).")
        public boolean gpuCarverMask = true;

        @Setting("macro-noise-octaves")
        @Comment("Opt-in Macro-13 BlendedNoise octave pruning (>98% terrain energy, 97.5%+ same-coordinate parity, ~2.5x faster GPU terrain density; default false).")
        public boolean macroNoiseOctaves = false;

        @Setting("gpu-in-vram-features")
        @Comment("Opt-in in-VRAM GPU Pass 4 features (3D Hilbert ores, FreezeTopLayer snow/ice, springs, and intra-chunk ground-cover vegetation; default false).")
        public boolean gpuInVramFeatures = false;

        @Setting("secure-seed")
        @Comment("Opt-in 1024-bit cryptographic secure-seed protection against seedcrackers (default disabled; immutable per world; ADR-018).")
        public SecureSeed secureSeed = new SecureSeed();

        @Setting("directional-chunk-priority")
        @Comment("Velocity and facing-direction elliptical focal chunk prioritization for RegionizedPlayerChunkLoader (ADR-020).")
        public DirectionalChunkPriority directionalChunkPriority = new DirectionalChunkPriority();
    }

    @ConfigSerializable
    public static final class DirectionalChunkPriority {

        @Setting("enabled")
        @Comment("Prioritizes chunks ahead of player facing direction and movement velocity without changing the radial view-distance footprint (default true; ADR-020).")
        public boolean enabled = true;

        @Setting("look-bias")
        @Comment("Base elliptical eccentricity along player facing yaw when standing or walking slowly (0.0..0.85, default 0.45).")
        public double lookBias = ca.spottedleaf.moonrise.spi.PlayerChunkPrioritySpi.DEFAULT_LOOK_BIAS;

        @Setting("velocity-bias")
        @Comment("Maximum elliptical eccentricity blended toward player horizontal movement velocity during high-speed travel (0.0..0.90, default 0.75).")
        public double velocityBias = ca.spottedleaf.moonrise.spi.PlayerChunkPrioritySpi.DEFAULT_VELOCITY_BIAS;

        @Setting("inner-radial-radius")
        @Comment("Inner isotropic safety ring radius in chunks around the player that always loads first (0..8, default 2).")
        public int innerRadialRadius = ca.spottedleaf.moonrise.spi.PlayerChunkPrioritySpi.DEFAULT_INNER_RADIAL_RADIUS;

        @Setting("yaw-reprioritize-degrees")
        @Comment("Minimum yaw rotation in degrees before re-sorting non-empty queues while stationary in the same chunk (5.0..180.0, default 25.0).")
        public double yawReprioritizeDegrees = ca.spottedleaf.moonrise.spi.PlayerChunkPrioritySpi.DEFAULT_YAW_REPRIORITIZE_DEGREES;
    }

    @ConfigSerializable
    public static final class SecureSeed {

        @Setting("enabled")
        @Comment("Enables 1024-bit SHA-512 domain-separated secure seed derivation across structures, features, slime chunks, End spikes, biomeZoomSeed, and terrain/bedrock noise (default false; immutable per world; ADR-018).")
        public boolean enabled = false;

        @Setting("allow-unsafe-migration")
        @Comment("Dangerous override: allows booting an existing world when worldgen.secure-seed.enabled disagrees with the world's persisted secure-seed mode (may cause chunk-border discontinuities; default false; S-004).")
        public boolean allowUnsafeMigration = false;
    }

    @ConfigSerializable
    public static final class Security {

        @Setting("disable-chat-signing")
        @Comment("Opt-in disables cryptographic player chat signing enforcement and strips client chat signatures (default false; ADR-018).")
        public boolean disableChatSigning = false;
    }

    @ConfigSerializable
    public static final class Compute {

        @Setting("enabled")
        @Comment("Exposes ComputeService for built-in audited kernels and allowlisted plugin compute offload.")
        public boolean enabled = true;

        @Setting("allow-custom-opencl")
        @Comment("Allows plugins listed in custom-kernel-allowlist to submit custom OpenCL C99 kernels (default false).")
        public boolean allowCustomOpenCl = false;

        @Setting("custom-kernel-allowlist")
        @Comment("Explicit owner/plugin IDs permitted to register custom OpenCL C99 kernels when allow-custom-opencl is true.")
        public java.util.List<String> customKernelAllowlist = new java.util.ArrayList<>();

        @Setting("max-vram-bytes-per-owner")
        @Comment("Maximum reserved compute VRAM (input + output + persistent state) per owning plugin in bytes (default 64 MiB).")
        public long maxVramBytesPerOwner = 64L * 1024L * 1024L;

        @Setting("max-total-vram-bytes")
        @Comment("Maximum total reserved compute VRAM across all owners in bytes (default 256 MiB).")
        public long maxTotalVramBytes = 256L * 1024L * 1024L;

        @Setting("max-gpu-budget-ms-per-tick")
        @Comment("Per-tick compute budget in milliseconds before PacedSession automatically steps down cadence (1..40).")
        public int maxGpuBudgetMsPerTick = 6;

        @Setting("worker-threads")
        @Comment("Dedicated off-tick worker threads for ComputeService dispatch (1..16).")
        public int workerThreads = 2;
    }

    public static GoldLeafConfig defaults() {
        return new GoldLeafConfig();
    }

    public static GoldLeafConfig get() {
        return instance;
    }

    private record ActiveOpenClKey(
        Path cacheDir,
        int applyPoolThreads,
        int waveMaxSize,
        int waveFlushDeadlineMs
    ) {
    }

    private static volatile @org.jspecify.annotations.Nullable ActiveOpenClKey activeOpenClKey;
    private static volatile boolean hardwareEnvelopeLogged;

    public static GoldLeafConfig init(final Path configDirectory) throws ConfigurateException {
        final GoldLeafConfig loaded = load(configDirectory.resolve(FILE_NAME));
        instance = loaded;
        dev.leafcraft.goldleaf.diagnostics.TickHazardMonitor.refreshFromConfig();
        if (loaded.diagnostics.logHardwareEnvelopeOnStartup && !hardwareEnvelopeLogged) {
            hardwareEnvelopeLogged = true;
            LOGGER.info("GoldLeaf hardware envelope detected: {}", HardwareEnvelope.detect().describe());
        }
        refreshTerrainAccelerator(configDirectory, loaded);
        return loaded;
    }

    private static void refreshTerrainAccelerator(final Path configDirectory, final GoldLeafConfig loaded) throws ConfigurateException {
        final dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator current =
            dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.get();
        if (!loaded.worldgen.openclGpuEnabled) {
            activeOpenClKey = null;
            if (current instanceof dev.leafcraft.goldleaf.worldgen.IrOpenClTerrainAccelerator existing) {
                dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.resetDefault();
                existing.close();
            }
            return;
        }
        if (!dev.leafcraft.goldleaf.worldgen.GoldLeafOpenClBridge.isAvailable()) {
            activeOpenClKey = null;
            if (loaded.worldgen.requireAccelerator) {
                throw new ConfigurateException(
                    "goldleaf.yml worldgen.opencl-gpu-enabled and worldgen.require-accelerator are true, "
                        + "but no usable OpenCL GPU device is available"
                );
            }
            LOGGER.warn(
                "[GoldLeaf-OpenCL] worldgen.opencl-gpu-enabled is true, but no usable OpenCL GPU device was found; falling back to CPU."
            );
            return;
        }
        final Path cacheDir = configDirectory.toAbsolutePath().normalize().getParent() != null
            ? configDirectory.toAbsolutePath().normalize().getParent().resolve("cache").resolve("goldleaf-opencl")
            : configDirectory.resolve("cache").resolve("goldleaf-opencl");
        final ActiveOpenClKey desiredKey = new ActiveOpenClKey(
            cacheDir,
            loaded.worldgen.applyPoolThreads,
            loaded.worldgen.waveMaxSize,
            loaded.worldgen.waveFlushDeadlineMs
        );
        if (current instanceof dev.leafcraft.goldleaf.worldgen.IrOpenClTerrainAccelerator existing
            && existing.isGpuReady()
            && desiredKey.equals(activeOpenClKey)) {
            return;
        }
        if (current instanceof dev.leafcraft.goldleaf.worldgen.IrOpenClTerrainAccelerator existing) {
            dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.resetDefault();
            existing.close();
        }
        final dev.leafcraft.goldleaf.worldgen.IrOpenClTerrainAccelerator accel =
            new dev.leafcraft.goldleaf.worldgen.IrOpenClTerrainAccelerator(cacheDir);
        dev.leafcraft.goldleaf.worldgen.TerrainGenerationAccelerator.register(accel);
        activeOpenClKey = desiredKey;
        LOGGER.info(
            "[GoldLeaf-OpenCL] Registered native IR-OpenCL TerrainGenerationAccelerator (device='{}', parityMode={}, waveMaxSize={}, waveFlushDeadlineMs={}ms, cacheDir={})",
            dev.leafcraft.goldleaf.worldgen.GoldLeafOpenClBridge.deviceName(0),
            loaded.worldgen.parityMode,
            loaded.worldgen.waveMaxSize,
            loaded.worldgen.waveFlushDeadlineMs,
            cacheDir
        );
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
        if (this.network == null) {
            this.network = new Network();
        }
        if (this.worldgen == null) {
            this.worldgen = new Worldgen();
        }
        if (this.worldgen.secureSeed == null) {
            this.worldgen.secureSeed = new SecureSeed();
        }
        if (this.worldgen.directionalChunkPriority == null) {
            this.worldgen.directionalChunkPriority = new DirectionalChunkPriority();
        }
        if (this.protocols == null) {
            this.protocols = new Protocols();
        }
        if (this.antiExploit == null) {
            this.antiExploit = new AntiExploit();
        }
        if (this.bots == null) {
            this.bots = new Bots();
        }
        if (this.security == null) {
            this.security = new Security();
        }
        if (this.compute == null) {
            this.compute = new Compute();
        }
        if (this.compute.customKernelAllowlist == null) {
            this.compute.customKernelAllowlist = new java.util.ArrayList<>();
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
        if (this.storage.lcaMode == null) {
            throw new ConfigurateException("goldleaf.yml storage.lca-mode must not be null (expected 'mode-a', 'mode-b', or 'archival')");
        }
        final String normalizedLcaMode = this.storage.lcaMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"mode-a".equals(normalizedLcaMode) && !"mode-b".equals(normalizedLcaMode) && !"archival".equals(normalizedLcaMode)) {
            throw new ConfigurateException(
                "goldleaf.yml storage.lca-mode must be 'mode-a', 'mode-b', or 'archival', got '" + this.storage.lcaMode + "'"
            );
        }
        this.storage.lcaMode = normalizedLcaMode;
        if (this.storage.maxOpenRegions < 1) {
            throw new ConfigurateException(
                "goldleaf.yml storage.max-open-regions must be >= 1, got " + this.storage.maxOpenRegions
            );
        }
        if (this.storage.coldChunkMinInhabitedTicks < 0L) {
            throw new ConfigurateException(
                "goldleaf.yml storage.cold-chunk-min-inhabited-ticks must be >= 0, got "
                    + this.storage.coldChunkMinInhabitedTicks
            );
        }
        if (this.storage.coldChunkMinRevisits < 0) {
            throw new ConfigurateException(
                "goldleaf.yml storage.cold-chunk-min-revisits must be >= 0, got "
                    + this.storage.coldChunkMinRevisits
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
        if (this.diagnostics.chunkTelemetryMaxListeners < 1) {
            throw new ConfigurateException(
                "goldleaf.yml diagnostics.chunk-telemetry-max-listeners must be >= 1, got "
                    + this.diagnostics.chunkTelemetryMaxListeners
            );
        }
        if (this.diagnostics.runtimeHazardStallThresholdMs < 20) {
            throw new ConfigurateException(
                "goldleaf.yml diagnostics.runtime-hazard-stall-threshold-ms must be >= 20, got "
                    + this.diagnostics.runtimeHazardStallThresholdMs
            );
        }
        if (this.worldgen.parityMode == null) {
            throw new ConfigurateException(
                "goldleaf.yml worldgen.parity-mode must not be null (expected 'vanilla-exact', 'vanilla-exact-fp64', or 'fast')"
            );
        }
        final String normalizedParity = this.worldgen.parityMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"vanilla-exact".equals(normalizedParity)
            && !"vanilla-exact-fp64".equals(normalizedParity)
            && !"fast".equals(normalizedParity)) {
            throw new ConfigurateException(
                "goldleaf.yml worldgen.parity-mode must be 'vanilla-exact', 'vanilla-exact-fp64', or 'fast', got '"
                    + this.worldgen.parityMode + "'"
            );
        }
        this.worldgen.parityMode = normalizedParity;
        if (this.worldgen.waveMaxSize < 1 || this.worldgen.waveMaxSize > 64) {
            throw new ConfigurateException(
                "goldleaf.yml worldgen.wave-max-size must be in [1, 64], got " + this.worldgen.waveMaxSize
            );
        }
        if (this.worldgen.waveFlushDeadlineMs < 1 || this.worldgen.waveFlushDeadlineMs > 10) {
            throw new ConfigurateException(
                "goldleaf.yml worldgen.wave-flush-deadline-ms must be in [1, 10], got "
                    + this.worldgen.waveFlushDeadlineMs
            );
        }
        if (this.worldgen.applyPoolThreads < 0 || this.worldgen.applyPoolThreads > 64) {
            throw new ConfigurateException(
                "goldleaf.yml worldgen.apply-pool-threads must be in [0, 64], got "
                    + this.worldgen.applyPoolThreads
            );
        }
        final DirectionalChunkPriority dcp = this.worldgen.directionalChunkPriority;
        if (!Double.isFinite(dcp.lookBias) || dcp.lookBias < 0.0D || dcp.lookBias > 0.85D) {
            throw new ConfigurateException(
                "goldleaf.yml worldgen.directional-chunk-priority.look-bias must be in [0.0, 0.85], got "
                    + dcp.lookBias
            );
        }
        if (!Double.isFinite(dcp.velocityBias) || dcp.velocityBias < 0.0D || dcp.velocityBias > 0.90D) {
            throw new ConfigurateException(
                "goldleaf.yml worldgen.directional-chunk-priority.velocity-bias must be in [0.0, 0.90], got "
                    + dcp.velocityBias
            );
        }
        if (dcp.innerRadialRadius < 0 || dcp.innerRadialRadius > 8) {
            throw new ConfigurateException(
                "goldleaf.yml worldgen.directional-chunk-priority.inner-radial-radius must be in [0, 8], got "
                    + dcp.innerRadialRadius
            );
        }
        if (!Double.isFinite(dcp.yawReprioritizeDegrees) || dcp.yawReprioritizeDegrees < 5.0D || dcp.yawReprioritizeDegrees > 180.0D) {
            throw new ConfigurateException(
                "goldleaf.yml worldgen.directional-chunk-priority.yaw-reprioritize-degrees must be in [5.0, 180.0], got "
                    + dcp.yawReprioritizeDegrees
            );
        }
        if (this.network.lcaNetMaxSessionCacheChunks < 64) {
            throw new ConfigurateException(
                "goldleaf.yml network.lca-net-max-session-cache-chunks must be >= 64, got "
                    + this.network.lcaNetMaxSessionCacheChunks
            );
        }
        if (this.network.leafShardTier4RpcTimeoutMs < 5) {
            throw new ConfigurateException(
                "goldleaf.yml network.leafshard-tier4-rpc-timeout-ms must be >= 5, got "
                    + this.network.leafShardTier4RpcTimeoutMs
            );
        }
        if (this.network.leafShardTier4MaxInFlightRpcs < 1) {
            throw new ConfigurateException(
                "goldleaf.yml network.leafshard-tier4-max-in-flight-rpcs must be >= 1, got "
                    + this.network.leafShardTier4MaxInFlightRpcs
            );
        }
        if (this.network.entityTrackerSharedBroadcastThreshold != 0
            && this.network.entityTrackerSharedBroadcastThreshold < 2) {
            throw new ConfigurateException(
                "goldleaf.yml network.entity-tracker-shared-broadcast-threshold must be 0 (disabled) or >= 2, got "
                    + this.network.entityTrackerSharedBroadcastThreshold
            );
        }
        if (this.diagnostics.watchdogMaxPlayers < 1) {
            throw new ConfigurateException(
                "goldleaf.yml diagnostics.watchdog-max-players must be >= 1, got "
                    + this.diagnostics.watchdogMaxPlayers
            );
        }
        if (this.diagnostics.watchdogTopHotChunks < 1) {
            throw new ConfigurateException(
                "goldleaf.yml diagnostics.watchdog-top-hot-chunks must be >= 1, got "
                    + this.diagnostics.watchdogTopHotChunks
            );
        }
        if (this.diagnostics.liveRegionHudIntervalTicks < 1) {
            throw new ConfigurateException(
                "goldleaf.yml diagnostics.live-region-hud-interval-ticks must be >= 1, got "
                    + this.diagnostics.liveRegionHudIntervalTicks
            );
        }
        if (this.diagnostics.bootTelemetrySlowPluginMs < 1) {
            throw new ConfigurateException(
                "goldleaf.yml diagnostics.boot-telemetry-slow-plugin-ms must be >= 1, got "
                    + this.diagnostics.bootTelemetrySlowPluginMs
            );
        }
        if (this.diagnostics.pluginIoPrefetchThreads < 1 || this.diagnostics.pluginIoPrefetchThreads > 16) {
            throw new ConfigurateException(
                "goldleaf.yml diagnostics.plugin-io-prefetch-threads must be in [1, 16], got "
                    + this.diagnostics.pluginIoPrefetchThreads
            );
        }
        if (this.diagnostics.pluginIoPrefetchMaxFileBytes < 1024L * 1024L) {
            throw new ConfigurateException(
                "goldleaf.yml diagnostics.plugin-io-prefetch-max-file-bytes must be >= 1048576, got "
                    + this.diagnostics.pluginIoPrefetchMaxFileBytes
            );
        }
        if (this.diagnostics.pluginIoPrefetchMaxTotalBytes < this.diagnostics.pluginIoPrefetchMaxFileBytes) {
            throw new ConfigurateException(
                "goldleaf.yml diagnostics.plugin-io-prefetch-max-total-bytes ("
                    + this.diagnostics.pluginIoPrefetchMaxTotalBytes
                    + ") must be >= plugin-io-prefetch-max-file-bytes ("
                    + this.diagnostics.pluginIoPrefetchMaxFileBytes + ")"
            );
        }
        if (this.protocols.servuxMaxStructureRadius < 1) {
            throw new ConfigurateException(
                "goldleaf.yml protocols.servux-max-structure-radius must be >= 1, got "
                    + this.protocols.servuxMaxStructureRadius
            );
        }
        if (this.protocols.syncmaticaMaxFileBytes < 1024) {
            throw new ConfigurateException(
                "goldleaf.yml protocols.syncmatica-max-file-bytes must be >= 1024, got "
                    + this.protocols.syncmaticaMaxFileBytes
            );
        }
        if (this.protocols.maxPayloadBytes < 256) {
            throw new ConfigurateException(
                "goldleaf.yml protocols.max-payload-bytes must be >= 256, got "
                    + this.protocols.maxPayloadBytes
            );
        }
        if (this.protocols.maxPacketsPerSecond < 1) {
            throw new ConfigurateException(
                "goldleaf.yml protocols.max-packets-per-second must be >= 1, got "
                    + this.protocols.maxPacketsPerSecond
            );
        }
        if (this.antiExploit.chunkGenSaturationWatermark < 1) {
            throw new ConfigurateException(
                "goldleaf.yml anti-exploit.chunk-gen-saturation-watermark must be >= 1, got "
                    + this.antiExploit.chunkGenSaturationWatermark
            );
        }
        if (this.antiExploit.chunkGenMinSendDistance < 2) {
            throw new ConfigurateException(
                "goldleaf.yml anti-exploit.chunk-gen-min-send-distance must be >= 2, got "
                    + this.antiExploit.chunkGenMinSendDistance
            );
        }
        if (this.antiExploit.chunkGenDebtDecaySeconds < 1) {
            throw new ConfigurateException(
                "goldleaf.yml anti-exploit.chunk-gen-debt-decay-seconds must be >= 1, got "
                    + this.antiExploit.chunkGenDebtDecaySeconds
            );
        }
        if (this.antiExploit.serverboundNbtMaxBytes < 4096) {
            throw new ConfigurateException(
                "goldleaf.yml anti-exploit.serverbound-nbt-max-bytes must be >= 4096, got "
                    + this.antiExploit.serverboundNbtMaxBytes
            );
        }
        if (this.antiExploit.serverboundNbtMaxDepth < 8 || this.antiExploit.serverboundNbtMaxDepth > 512) {
            throw new ConfigurateException(
                "goldleaf.yml anti-exploit.serverbound-nbt-max-depth must be in [8, 512], got "
                    + this.antiExploit.serverboundNbtMaxDepth
            );
        }
        if (this.antiExploit.maxBookPages < 1) {
            throw new ConfigurateException(
                "goldleaf.yml anti-exploit.max-book-pages must be >= 1, got "
                    + this.antiExploit.maxBookPages
            );
        }
        if (this.antiExploit.maxBookPageUtf8Bytes < 64) {
            throw new ConfigurateException(
                "goldleaf.yml anti-exploit.max-book-page-utf8-bytes must be >= 64, got "
                    + this.antiExploit.maxBookPageUtf8Bytes
            );
        }
        if (this.antiExploit.maxMalformedPacketsPerSecond < 1) {
            throw new ConfigurateException(
                "goldleaf.yml anti-exploit.max-malformed-packets-per-second must be >= 1, got "
                    + this.antiExploit.maxMalformedPacketsPerSecond
            );
        }
        if (this.bots.maxTotal < 1) {
            throw new ConfigurateException(
                "goldleaf.yml bots.max-total must be >= 1, got " + this.bots.maxTotal
            );
        }
        if (this.bots.maxPerPlayer < 1) {
            throw new ConfigurateException(
                "goldleaf.yml bots.max-per-player must be >= 1, got " + this.bots.maxPerPlayer
            );
        }
        if (this.bots.maxPerPlayer > this.bots.maxTotal) {
            throw new ConfigurateException(
                "goldleaf.yml bots.max-per-player (" + this.bots.maxPerPlayer
                    + ") must be <= bots.max-total (" + this.bots.maxTotal + ")"
            );
        }
        if (this.compute.maxVramBytesPerOwner < 1024L * 1024L) {
            throw new ConfigurateException(
                "goldleaf.yml compute.max-vram-bytes-per-owner must be >= 1048576, got "
                    + this.compute.maxVramBytesPerOwner
            );
        }
        if (this.compute.maxTotalVramBytes < this.compute.maxVramBytesPerOwner) {
            throw new ConfigurateException(
                "goldleaf.yml compute.max-total-vram-bytes (" + this.compute.maxTotalVramBytes
                    + ") must be >= compute.max-vram-bytes-per-owner (" + this.compute.maxVramBytesPerOwner + ")"
            );
        }
        if (this.compute.maxGpuBudgetMsPerTick < 1 || this.compute.maxGpuBudgetMsPerTick > 40) {
            throw new ConfigurateException(
                "goldleaf.yml compute.max-gpu-budget-ms-per-tick must be in [1, 40], got "
                    + this.compute.maxGpuBudgetMsPerTick
            );
        }
        if (this.compute.workerThreads < 1 || this.compute.workerThreads > 16) {
            throw new ConfigurateException(
                "goldleaf.yml compute.worker-threads must be in [1, 16], got "
                    + this.compute.workerThreads
            );
        }
        this.configVersion = CURRENT_VERSION;
    }
}
