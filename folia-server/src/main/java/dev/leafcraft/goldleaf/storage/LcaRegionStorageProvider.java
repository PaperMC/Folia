package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.nbt_rw.FastNBTParser;
import ca.spottedleaf.moonrise.spi.ChunkResidencySpi;
import ca.spottedleaf.moonrise.spi.DirectSectionSerializationSpi;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import io.github.dailystruggle.anvil.LcaDecodedChunkMeta;
import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.LcaRegionReader;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntFunction;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 2 {@code .lca} ({@code LCA-ICD-3.0}) disk storage provider backed by
 * {@link LcaRegionFileStorage} ({@code goldleaf-ADR-005} Stage 1).
 *
 * <p>Handles {@code region/}, {@code entities/}, and {@code poi/} folders transparently by
 * inferring the {@code storageKind} from the target directory path, with zero references to
 * {@code ServerLevel} or tick-thread state ({@code DSN-3}, {@code S-005}).</p>
 */
public final class LcaRegionStorageProvider implements RegionStorageProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(LcaRegionStorageProvider.class);
    private static final int DEFAULT_MIN_SECTION_Y = -4;
    private static final int DEFAULT_SECTION_COUNT = 24;
    private static final int DEFAULT_DATA_VERSION = 4189;
    private static final double DEFAULT_COMPACT_THRESHOLD = 0.35;

    private final Path folder;
    private final LcaRegionFileStorage storage;
    private final String worldId;
    private final boolean isRegionVoxelStorage;
    private final AtomicLong directCoWWriteBypasses = new AtomicLong();

    public LcaRegionStorageProvider(final Path folder, final LcaRegionFileStorage storage) {
        this(folder, storage, inferWorldId(folder), LcaRegionFileStorage.inferStorageKindFromPath(folder) == 0);
    }

    public LcaRegionStorageProvider(
        final Path folder,
        final LcaRegionFileStorage storage,
        final @Nullable String worldId,
        final boolean isRegionVoxelStorage
    ) {
        this.folder = Objects.requireNonNull(folder, "folder");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.worldId = (worldId != null && !worldId.isBlank()) ? worldId : inferWorldId(folder);
        this.isRegionVoxelStorage = isRegionVoxelStorage;
        if (this.isRegionVoxelStorage) {
            LcaResidencyManager.global().registerStorageProvider(this.worldId, this);
        }
    }

    public static LcaRegionStorageProvider open(
        final Path folder,
        final GoldLeafConfig.Storage storageConfig
    ) {
        return open(folder, storageConfig, null);
    }

    public static LcaRegionStorageProvider open(
        final Path folder,
        final GoldLeafConfig.Storage storageConfig,
        final @Nullable String worldId
    ) {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(storageConfig, "storageConfig");
        final int storageKind = LcaRegionFileStorage.inferStorageKindFromPath(folder);
        final boolean useLcd2 = "mode-b".equalsIgnoreCase(storageConfig.lcaMode)
            || "archival".equalsIgnoreCase(storageConfig.lcaMode)
            || storageConfig.lcaModeBLcd2;
        final LcaRegionFileStorage lca = new LcaRegionFileStorage(
            folder,
            storageKind,
            DEFAULT_MIN_SECTION_Y,
            DEFAULT_SECTION_COUNT,
            DEFAULT_DATA_VERSION,
            useLcd2,
            storageConfig.autoMigrateMca,
            storageConfig.backupMigratedMca,
            Math.max(1, storageConfig.maxOpenRegions),
            DEFAULT_COMPACT_THRESHOLD
        );
        try {
            // Commit each chunk immediately to the OS page cache via CoW positional FileChannel.write
            // (matching vanilla .mca durability and preventing data loss when Folia's RegionShutdownThread
            // exits with 0 active regions), while avoiding 6+ MiB G1GC humongous read-ahead arrays.
            lca.configureBufferedIo(false, 1, 4096, false, 4);
        } catch (final IOException ex) {
            LOGGER.warn("Failed to configure buffered I/O on .lca storage {}", folder, ex);
        }
        // Match vanilla Paper/Folia .mca shutdown behavior: flush all pending writes to OS page cache
        // on close, and only issue per-file channel.force(false) fdatasync when explicitly requested.
        lca.setForceSyncOnClose(Boolean.getBoolean("leafanvil.fsync.onClose"));
        return new LcaRegionStorageProvider(folder, lca, worldId, storageKind == 0);
    }

    static String inferWorldId(final Path folder) {
        final Path parent = folder.getParent();
        if (parent != null && parent.getFileName() != null) {
            final String name = parent.getFileName().toString();
            final Path grandParent = parent.getParent();
            final Path greatGrandParent = grandParent != null ? grandParent.getParent() : null;
            if (grandParent != null && grandParent.getFileName() != null
                && greatGrandParent != null && greatGrandParent.getFileName() != null
                && "dimensions".equalsIgnoreCase(greatGrandParent.getFileName().toString())) {
                return grandParent.getFileName().toString() + ":" + name;
            }
            if ("DIM-1".equalsIgnoreCase(name)) {
                return "minecraft:the_nether";
            }
            if ("DIM1".equalsIgnoreCase(name)) {
                return "minecraft:the_end";
            }
            if ("world".equalsIgnoreCase(name)) {
                return LcaResidencyManager.DEFAULT_WORLD_ID;
            }
        }
        return parent != null ? parent.toAbsolutePath().normalize().toString() : folder.toAbsolutePath().normalize().toString();
    }

    public String worldId() {
        return this.worldId;
    }

    public long directCoWWriteBypasses() {
        return this.directCoWWriteBypasses.get();
    }

    public Path folder() {
        return this.folder;
    }

    public LcaRegionFileStorage delegate() {
        return this.storage;
    }

    @Override
    public boolean doesRegionFileNotExistNoIO(final int chunkX, final int chunkZ) {
        // Defer to readData() on Moonrise I/O threads so .lca / legacy .mca lookup stays authoritative
        return false;
    }

    @Override
    public MoonriseRegionFileIO.RegionDataController.WriteData startWrite(
        final int chunkX,
        final int chunkZ,
        final @Nullable CompoundTag compound
    ) throws IOException {
        if (compound == null) {
            return new MoonriseRegionFileIO.RegionDataController.WriteData(
                null,
                MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.DELETE,
                null,
                null
            );
        }

        if (compound.getBooleanOr(LcaRegionFileStorage.DIRECT_COW_COMMITTED_TAG, false)) {
            this.directCoWWriteBypasses.incrementAndGet();
            return new MoonriseRegionFileIO.RegionDataController.WriteData(
                compound,
                MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
                null,
                ignoredRegionFile -> {
                }
            );
        }

        final Object directAttachment = compound.paper$getDirectLoadAttachment();
        if (directAttachment instanceof LcaDirectSectionSerializer.DirectFreshLcaWritePayload freshPayload) {
            final boolean isFullStatus = isFullChunkStatus(freshPayload.statusName());
            return new MoonriseRegionFileIO.RegionDataController.WriteData(
                compound,
                MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
                null,
                ignoredRegionFile -> {
                    try {
                        this.storage.writeFreshChunkDirectModeA(
                            chunkX,
                            chunkZ,
                            freshPayload.dataVersion(),
                            freshPayload.lastUpdate(),
                            freshPayload.inhabitedTime(),
                            freshPayload.statusName(),
                            freshPayload.blockPalettes(),
                            freshPayload.preEncodedModeA(),
                            freshPayload.preEncodedFlatBytes(),
                            freshPayload.preEncodedFlatBits(),
                            freshPayload.preResolvedDictIds(),
                            freshPayload.precomputedOctreeMasks(),
                            freshPayload.hasPrecomputedOctreeMask(),
                            freshPayload.blockData(),
                            freshPayload.biomePalettes(),
                            freshPayload.biomeData(),
                            freshPayload.skyLights(),
                            freshPayload.blockLights(),
                            freshPayload.residualTail()
                        );
                        this.directCoWWriteBypasses.incrementAndGet();
                        final GoldLeafConfig cfg = GoldLeafConfig.get();
                        if (!isFullStatus
                            || (cfg != null && cfg.storage != null && cfg.storage.lcaNativeSectionBacking
                                && (cfg.network == null || !cfg.network.lcaNetV1Enabled))) {
                            LcaResidencyManager.global().evictResidencyCacheOnly(this.worldId, chunkX, chunkZ);
                        } else {
                            installColdFrameIfResidencyActive(chunkX, chunkZ);
                        }
                    } finally {
                        if (freshPayload.waveSlotId() >= 0) {
                            WaveChunkArena.global().completeSave(freshPayload.waveSlotId());
                        }
                    }
                }
            );
        }

        final ByteArrayOutputStream rawOut = new ByteArrayOutputStream(8192);
        try (DataOutputStream dataOut = new DataOutputStream(rawOut)) {
            NbtIo.write(compound, dataOut);
        }
        final byte[] nbtBytes = rawOut.toByteArray();

        final boolean isFullStatus = isFullChunkStatus(compound.getStringOr("Status", ""));
        return new MoonriseRegionFileIO.RegionDataController.WriteData(
            compound,
            MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
            null,
            ignoredRegionFile -> {
                this.storage.writeChunkNbtBytes(chunkX, chunkZ, nbtBytes, 0, nbtBytes.length);
                final GoldLeafConfig cfg = GoldLeafConfig.get();
                if (!isFullStatus
                    || (cfg != null && cfg.storage != null && cfg.storage.lcaNativeSectionBacking
                        && (cfg.network == null || !cfg.network.lcaNetV1Enabled))) {
                    // Never pin incomplete ProtoChunk (EMPTY/STRUCTURE_STARTS/BIOMES) frames into residency,
                    // and evict stale residency on async saves when native cold backing is active without LCA_NET_V1.
                    LcaResidencyManager.global().evictResidencyCacheOnly(this.worldId, chunkX, chunkZ);
                } else {
                    installColdFrameIfResidencyActive(chunkX, chunkZ);
                }
            }
        );
    }

    @Override
    public void finishWrite(
        final int chunkX,
        final int chunkZ,
        final MoonriseRegionFileIO.RegionDataController.WriteData writeData
    ) throws IOException {
        if (writeData.result() == MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.DELETE) {
            this.storage.removeChunk(chunkX, chunkZ);
            LcaResidencyManager.global().evictResidencyCacheOnly(worldId(), chunkX, chunkZ);
            return;
        }
        writeData.write().run(null);
    }

    @Override
    public MoonriseRegionFileIO.RegionDataController.ReadData readData(
        final int chunkX,
        final int chunkZ
    ) throws IOException {
        if (isNativeColdBackingReadEligible()) {
            final LcaRegionReader.DeferredChunkSlab deferred = this.storage.readDeferredChunkSlab(
                chunkX,
                chunkZ,
                LcaDirectSectionSerializer.canonicalToRuntimeStateLookup()
            );
            if (deferred == null) {
                return new MoonriseRegionFileIO.RegionDataController.ReadData(
                    MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.NO_DATA,
                    null,
                    null,
                    0
                );
            }
            return new MoonriseRegionFileIO.RegionDataController.ReadData(
                MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.HAS_DATA,
                new DeferredSlabReadStream(deferred),
                null,
                0
            );
        }
        final DataInputStream input = this.storage.getChunkDataInputStream(chunkX, chunkZ);
        if (input == null) {
            return new MoonriseRegionFileIO.RegionDataController.ReadData(
                MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.NO_DATA,
                null,
                null,
                0
            );
        }
        return new MoonriseRegionFileIO.RegionDataController.ReadData(
            MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.HAS_DATA,
            input,
            null,
            0
        );
    }

    @Override
    public @Nullable CompoundTag finishRead(
        final int chunkX,
        final int chunkZ,
        final MoonriseRegionFileIO.RegionDataController.ReadData readData
    ) throws IOException {
        if (readData.input() == null) {
            return null;
        }
        if (readData.input() instanceof DeferredSlabReadStream deferredStream) {
            LcaRegionReader.RawChunkFrameWithDirectMeta paired;
            try {
                paired = deferredStream.slab.decodeToDirectMeta();
            } catch (final io.github.dailystruggle.anvil.CorruptRegionEntryException raceOrCorrupt) {
                paired = this.storage.readRawChunkFrameWithDirectMeta(
                    chunkX,
                    chunkZ,
                    LcaDirectSectionSerializer.canonicalToRuntimeStateLookup()
                );
            }
            if (paired != null) {
                final RawChunkFrameSnapshot frame = paired.frame();
                final LcaDecodedChunkMeta directMeta = paired.directMeta();
                if (frame != null && directMeta != null && isFrameDirectDeserializeReady(frame)) {
                    final GoldLeafConfig cfg = GoldLeafConfig.get();
                    if (cfg != null && cfg.network != null && cfg.network.lcaNetV1Enabled) {
                        if (isFullChunkStatus(directMeta.statusName())) {
                            LcaResidencyManager.global().installBaseFrame(this.worldId, chunkX, chunkZ, frame);
                        } else {
                            // Never cache incomplete ProtoChunk frames into residency; they would shadow promoted LevelChunks.
                            LcaResidencyManager.global().evictResidencyCacheOnly(this.worldId, chunkX, chunkZ);
                        }
                    }
                    return buildDirectLoadCompoundTag(chunkX, chunkZ, frame, directMeta);
                }
            }
            try (DataInputStream fallbackIn = this.storage.getChunkDataInputStream(chunkX, chunkZ)) {
                if (fallbackIn == null) {
                    return null;
                }
                final CompoundTag tag = FastNBTParser.nbtIoRead(fallbackIn);
                if (tag != null) {
                    installOrEvictColdFrameForStatus(chunkX, chunkZ, tag);
                }
                return tag;
            }
        }
        if (readData.input() instanceof DirectMetaReadStream directStream) {
            return buildDirectLoadCompoundTag(chunkX, chunkZ, directStream.frame, directStream.directMeta);
        }
        final boolean alreadyInstalled = readData.input() instanceof PreInstalledFrameDataInputStream;
        try (DataInputStream input = readData.input()) {
            final CompoundTag tag = FastNBTParser.nbtIoRead(input);
            if (tag != null && !alreadyInstalled) {
                installOrEvictColdFrameForStatus(chunkX, chunkZ, tag);
            }
            return tag;
        }
    }

    @Override
    public @Nullable CompoundTag read(final int chunkX, final int chunkZ) throws IOException {
        try (DataInputStream input = this.storage.getChunkDataInputStream(chunkX, chunkZ)) {
            if (input == null) {
                return null;
            }
            final CompoundTag tag = FastNBTParser.nbtIoRead(input);
            if (tag != null) {
                installOrEvictColdFrameForStatus(chunkX, chunkZ, tag);
            }
            return tag;
        }
    }

    private boolean isNativeColdBackingReadEligible() {
        if (!this.isRegionVoxelStorage
            || !ChunkResidencySpi.isActive()
            || DirectSectionSerializationSpi.get() == null) {
            return false;
        }
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        return cfg != null && cfg.storage != null && cfg.storage.lcaNativeSectionBacking;
    }

    private static boolean isFrameDirectDeserializeReady(final RawChunkFrameSnapshot frame) {
        final DirectSectionSerializationSpi directSpi = DirectSectionSerializationSpi.get();
        if (directSpi == null || !directSpi.supportsDataVersion(frame.dataVersion())) {
            return false;
        }
        final int[] dictToRuntime = frame.dictIdToRuntimeId();
        if (dictToRuntime == null || dictToRuntime.length == 0) {
            return false;
        }
        for (final int runtimeId : dictToRuntime) {
            if (runtimeId < 0) {
                return false;
            }
        }
        return true;
    }

    private static final class PreInstalledFrameDataInputStream extends DataInputStream {
        PreInstalledFrameDataInputStream(final InputStream in) {
            super(in);
        }
    }

    private CompoundTag buildDirectLoadCompoundTag(
        final int chunkX,
        final int chunkZ,
        final RawChunkFrameSnapshot frame,
        final LcaDecodedChunkMeta meta
    ) throws IOException {
        final CompoundTag tag;
        if (meta.hasResidualNbt()) {
            try (DataInputStream residualIn = new DataInputStream(new ByteArrayInputStream(meta.residualNbtBytes()))) {
                final CompoundTag parsed = FastNBTParser.nbtIoRead(residualIn);
                tag = parsed != null ? parsed : new CompoundTag();
            }
        } else {
            tag = new CompoundTag();
        }
        tag.putInt("DataVersion", meta.dataVersion());
        tag.putInt("xPos", chunkX);
        tag.putInt("zPos", chunkZ);
        if (meta.statusName() != null && !meta.statusName().isEmpty()) {
            tag.putString("Status", meta.statusName());
        }
        tag.paper$setDirectLoadAttachment(new DirectLcaLoadPayload(
            frame,
            meta,
            this.storage,
            chunkX,
            chunkZ
        ));
        return tag;
    }

    private static final class DeferredSlabReadStream extends DataInputStream {
        private static final InputStream EMPTY = InputStream.nullInputStream();
        final LcaRegionReader.DeferredChunkSlab slab;

        DeferredSlabReadStream(final LcaRegionReader.DeferredChunkSlab slab) {
            super(EMPTY);
            this.slab = slab;
        }
    }

    private static final class DirectMetaReadStream extends DataInputStream {
        private static final InputStream EMPTY = InputStream.nullInputStream();
        final RawChunkFrameSnapshot frame;
        final LcaDecodedChunkMeta directMeta;

        DirectMetaReadStream(final RawChunkFrameSnapshot frame, final LcaDecodedChunkMeta directMeta) {
            super(EMPTY);
            this.frame = frame;
            this.directMeta = directMeta;
        }
    }

    record DirectLcaLoadPayload(
        RawChunkFrameSnapshot frame,
        LcaDecodedChunkMeta directMeta,
        LcaRegionFileStorage storage,
        int chunkX,
        int chunkZ
    ) {
    }

    @Override
    public void scanChunk(
        final int chunkX,
        final int chunkZ,
        final StreamTagVisitor scanner
    ) throws IOException {
        try (DataInputStream input = this.storage.getChunkDataInputStream(chunkX, chunkZ)) {
            if (input != null) {
                NbtIo.parse(input, scanner, NbtAccounter.unlimitedHeap());
            }
        }
    }

    @Override
    public void write(
        final int chunkX,
        final int chunkZ,
        final @Nullable CompoundTag compound
    ) throws IOException {
        if (SharedConstants.DEBUG_DONT_SAVE_WORLD) {
            return;
        }
        if (compound == null) {
            this.storage.removeChunk(chunkX, chunkZ);
            LcaResidencyManager.global().evictResidencyCacheOnly(this.worldId, chunkX, chunkZ);
            return;
        }
        if (compound.getBooleanOr(LcaRegionFileStorage.DIRECT_COW_COMMITTED_TAG, false)) {
            this.directCoWWriteBypasses.incrementAndGet();
            return;
        }
        final Object directAttachment = compound.paper$getDirectLoadAttachment();
        if (directAttachment instanceof LcaDirectSectionSerializer.DirectFreshLcaWritePayload freshPayload) {
            try {
                this.storage.writeFreshChunkDirectModeA(
                    chunkX,
                    chunkZ,
                    freshPayload.dataVersion(),
                    freshPayload.lastUpdate(),
                    freshPayload.inhabitedTime(),
                    freshPayload.statusName(),
                    freshPayload.blockPalettes(),
                    freshPayload.preEncodedModeA(),
                    freshPayload.preEncodedFlatBytes(),
                    freshPayload.preEncodedFlatBits(),
                    freshPayload.preResolvedDictIds(),
                    freshPayload.precomputedOctreeMasks(),
                    freshPayload.hasPrecomputedOctreeMask(),
                    freshPayload.blockData(),
                    freshPayload.biomePalettes(),
                    freshPayload.biomeData(),
                    freshPayload.skyLights(),
                    freshPayload.blockLights(),
                    freshPayload.residualTail()
                );
                this.directCoWWriteBypasses.incrementAndGet();
                installOrEvictColdFrameForStatus(chunkX, chunkZ, compound);
            } finally {
                if (freshPayload.waveSlotId() >= 0) {
                    WaveChunkArena.global().completeSave(freshPayload.waveSlotId());
                }
            }
            return;
        }
        final ByteArrayOutputStream rawOut = new ByteArrayOutputStream(8192);
        try (DataOutputStream dataOut = new DataOutputStream(rawOut)) {
            NbtIo.write(compound, dataOut);
        }
        final byte[] nbtBytes = rawOut.toByteArray();
        this.storage.writeChunkNbtBytes(chunkX, chunkZ, nbtBytes, 0, nbtBytes.length);
        installOrEvictColdFrameForStatus(chunkX, chunkZ, compound);
    }

    public static boolean isFullChunkStatus(final @Nullable String statusName) {
        return "minecraft:full".equals(statusName) || "full".equals(statusName);
    }

    private void installOrEvictColdFrameForStatus(final int chunkX, final int chunkZ, final CompoundTag tag) {
        if (isFullChunkStatus(tag.getStringOr("Status", ""))) {
            installColdFrameIfResidencyActive(chunkX, chunkZ);
        } else {
            LcaResidencyManager.global().evictResidencyCacheOnly(this.worldId, chunkX, chunkZ);
        }
    }

    private void installColdFrameIfResidencyActive(final int chunkX, final int chunkZ) {
        if (!this.isRegionVoxelStorage || !ChunkResidencySpi.isActive()) {
            return;
        }
        try {
            final RawChunkFrameSnapshot frame = this.storage.readRawChunkFrame(
                chunkX,
                chunkZ,
                LcaDirectSectionSerializer.canonicalToRuntimeStateLookup()
            );
            if (frame != null) {
                LcaResidencyManager.global().installBaseFrame(this.worldId, chunkX, chunkZ, frame);
            }
        } catch (final IOException ex) {
            LOGGER.warn("Failed to install cold .lca base frame for {} ({}, {})", this.worldId, chunkX, chunkZ, ex);
        }
    }

    @Override
    public void flush() throws IOException {
        this.storage.flush();
    }

    @Override
    public @Nullable RawChunkFrameSnapshot readRawChunkFrame(
        final int chunkX,
        final int chunkZ,
        final Function<String, Integer> runtimeStateLookup
    ) throws IOException {
        return this.storage.readRawChunkFrame(chunkX, chunkZ, runtimeStateLookup);
    }

    @Override
    public byte @Nullable [] commitAndGetSplicedChunkFrameCoW(
        final int chunkX,
        final int chunkZ,
        final byte[] coldChunkFrame,
        final int dirtySectionMask,
        final IntFunction<int[]> dirtySectionVoxels,
        final byte @Nullable [] updatedResidualNbtOrNull
    ) throws IOException {
        return this.storage.commitAndGetSplicedChunkFrameCoW(
            chunkX,
            chunkZ,
            coldChunkFrame,
            dirtySectionMask,
            dirtySectionVoxels,
            updatedResidualNbtOrNull
        );
    }

    public byte @Nullable [] commitAndGetSplicedChunkFrameCoW(
        final int chunkX,
        final int chunkZ,
        final byte[] coldChunkFrame,
        final int dirtySectionMask,
        final IntFunction<int[]> dirtySectionVoxels,
        final @Nullable IntFunction<byte[]> updatedSectionSkyLight,
        final @Nullable IntFunction<byte[]> updatedSectionBlockLight,
        final byte @Nullable [] updatedResidualNbtOrNull
    ) throws IOException {
        return this.storage.commitAndGetSplicedChunkFrameCoW(
            chunkX,
            chunkZ,
            coldChunkFrame,
            dirtySectionMask,
            dirtySectionVoxels,
            updatedSectionSkyLight,
            updatedSectionBlockLight,
            updatedResidualNbtOrNull
        );
    }

    @Override
    public void close() throws IOException {
        if (this.isRegionVoxelStorage) {
            LcaResidencyManager.global().unregisterStorageProvider(this.worldId, this);
        }
        this.storage.close();
    }
}
