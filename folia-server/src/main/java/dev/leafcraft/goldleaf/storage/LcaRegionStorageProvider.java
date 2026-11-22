package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.nbt_rw.FastNBTParser;
import ca.spottedleaf.moonrise.spi.ChunkResidencySpi;
import ca.spottedleaf.moonrise.spi.DirectSectionSerializationSpi;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
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
        final boolean useLcd2 = !"mode-a".equalsIgnoreCase(storageConfig.lcaMode)
            && (storageConfig.lcaModeBLcd2 || "archival".equalsIgnoreCase(storageConfig.lcaMode));
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

        final ByteArrayOutputStream rawOut = new ByteArrayOutputStream(8192);
        try (DataOutputStream dataOut = new DataOutputStream(rawOut)) {
            NbtIo.write(compound, dataOut);
        }
        final byte[] nbtBytes = rawOut.toByteArray();

        return new MoonriseRegionFileIO.RegionDataController.WriteData(
            compound,
            MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
            null,
            ignoredRegionFile -> {
                this.storage.writeChunkNbtBytes(chunkX, chunkZ, nbtBytes, 0, nbtBytes.length);
                installColdFrameIfResidencyActive(chunkX, chunkZ);
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
            LcaResidencyManager.global().onChunkUnloaded(worldId(), chunkX, chunkZ);
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
            final LcaRegionReader.RawChunkFrameWithMetadata paired = this.storage.readRawChunkFrameWithMetadata(
                chunkX,
                chunkZ,
                LcaDirectSectionSerializer.canonicalToRuntimeStateLookup()
            );
            if (paired == null) {
                return new MoonriseRegionFileIO.RegionDataController.ReadData(
                    MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.NO_DATA,
                    null,
                    null,
                    0
                );
            }
            final RawChunkFrameSnapshot frame = paired.frame();
            if (frame != null) {
                LcaResidencyManager.global().installBaseFrame(this.worldId, chunkX, chunkZ, frame);
                final byte[] metadataNbt = paired.metadataNbtBytes();
                if (metadataNbt != null
                    && isFrameDirectDeserializeReady(frame)
                    && LcaResidencyManager.global().getCacheIfPresent(this.worldId) != null) {
                    return new MoonriseRegionFileIO.RegionDataController.ReadData(
                        MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.HAS_DATA,
                        new PreInstalledFrameDataInputStream(new ByteArrayInputStream(metadataNbt)),
                        null,
                        0
                    );
                }
            }
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
        final boolean alreadyInstalled = readData.input() instanceof PreInstalledFrameDataInputStream;
        try (DataInputStream input = readData.input()) {
            final CompoundTag tag = FastNBTParser.nbtIoRead(input);
            if (tag != null && !alreadyInstalled) {
                installColdFrameIfResidencyActive(chunkX, chunkZ);
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
                installColdFrameIfResidencyActive(chunkX, chunkZ);
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
            LcaResidencyManager.global().onChunkUnloaded(this.worldId, chunkX, chunkZ);
            return;
        }
        if (compound.getBooleanOr(LcaRegionFileStorage.DIRECT_COW_COMMITTED_TAG, false)) {
            this.directCoWWriteBypasses.incrementAndGet();
            return;
        }
        final ByteArrayOutputStream rawOut = new ByteArrayOutputStream(8192);
        try (DataOutputStream dataOut = new DataOutputStream(rawOut)) {
            NbtIo.write(compound, dataOut);
        }
        final byte[] nbtBytes = rawOut.toByteArray();
        this.storage.writeChunkNbtBytes(chunkX, chunkZ, nbtBytes, 0, nbtBytes.length);
        installColdFrameIfResidencyActive(chunkX, chunkZ);
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
