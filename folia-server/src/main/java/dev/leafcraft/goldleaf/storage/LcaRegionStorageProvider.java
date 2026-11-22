package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.nbt_rw.FastNBTParser;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 {@code .lca} ({@code LCA-ICD-3.0}) disk storage provider backed by
 * {@link LcaRegionFileStorage} ({@code goldleaf-ADR-005} Stage 1).
 *
 * <p>Handles {@code region/}, {@code entities/}, and {@code poi/} folders transparently by
 * inferring the {@code storageKind} from the target directory path, with zero references to
 * {@code ServerLevel} or tick-thread state ({@code DSN-3}, {@code S-005}).</p>
 */
public final class LcaRegionStorageProvider implements RegionStorageProvider {

    private static final int DEFAULT_MIN_SECTION_Y = -4;
    private static final int DEFAULT_SECTION_COUNT = 24;
    private static final int DEFAULT_DATA_VERSION = 4189;
    private static final double DEFAULT_COMPACT_THRESHOLD = 0.35;

    private final Path folder;
    private final LcaRegionFileStorage storage;

    public LcaRegionStorageProvider(final Path folder, final LcaRegionFileStorage storage) {
        this.folder = Objects.requireNonNull(folder, "folder");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public static LcaRegionStorageProvider open(
        final Path folder,
        final GoldLeafConfig.Storage storageConfig
    ) {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(storageConfig, "storageConfig");
        final int storageKind = LcaRegionFileStorage.inferStorageKindFromPath(folder);
        final LcaRegionFileStorage lca = new LcaRegionFileStorage(
            folder,
            storageKind,
            DEFAULT_MIN_SECTION_Y,
            DEFAULT_SECTION_COUNT,
            DEFAULT_DATA_VERSION,
            storageConfig.lcaModeBLcd2,
            storageConfig.autoMigrateMca,
            storageConfig.backupMigratedMca,
            Math.max(1, storageConfig.maxOpenRegions),
            DEFAULT_COMPACT_THRESHOLD
        );
        return new LcaRegionStorageProvider(folder, lca);
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

        final ByteArrayOutputStream rawOut = new ByteArrayOutputStream(8192);
        try (DataOutputStream dataOut = new DataOutputStream(rawOut)) {
            NbtIo.write(compound, dataOut);
        }
        final byte[] nbtBytes = rawOut.toByteArray();

        return new MoonriseRegionFileIO.RegionDataController.WriteData(
            compound,
            MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
            null,
            ignoredRegionFile -> this.storage.writeChunkNbtBytes(chunkX, chunkZ, nbtBytes, 0, nbtBytes.length)
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
            return;
        }
        writeData.write().run(null);
    }

    @Override
    public MoonriseRegionFileIO.RegionDataController.ReadData readData(
        final int chunkX,
        final int chunkZ
    ) throws IOException {
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
        try (DataInputStream input = readData.input()) {
            return FastNBTParser.nbtIoRead(input);
        }
    }

    @Override
    public @Nullable CompoundTag read(final int chunkX, final int chunkZ) throws IOException {
        try (DataInputStream input = this.storage.getChunkDataInputStream(chunkX, chunkZ)) {
            return input == null ? null : FastNBTParser.nbtIoRead(input);
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
            return;
        }
        final ByteArrayOutputStream rawOut = new ByteArrayOutputStream(8192);
        try (DataOutputStream dataOut = new DataOutputStream(rawOut)) {
            NbtIo.write(compound, dataOut);
        }
        final byte[] nbtBytes = rawOut.toByteArray();
        this.storage.writeChunkNbtBytes(chunkX, chunkZ, nbtBytes, 0, nbtBytes.length);
    }

    @Override
    public void flush() throws IOException {
        this.storage.flush();
    }

    @Override
    public void close() throws IOException {
        this.storage.close();
    }
}
