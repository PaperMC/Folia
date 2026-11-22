package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 pluggable region-storage provider SPI behind {@code ChunkSystemRegionFileStorage}
 * ({@code goldleaf-ADR-005} Stage 1).
 *
 * <p>Implementations must remain completely decoupled from {@code ServerLevel} and tick-thread
 * state, executing all disk I/O strictly on Moonrise I/O worker threads ({@code S-005}) and
 * guaranteeing idempotent {@link #close()} semantics ({@code S-002}).</p>
 */
public interface RegionStorageProvider extends Closeable {

    boolean doesRegionFileNotExistNoIO(int chunkX, int chunkZ);

    MoonriseRegionFileIO.RegionDataController.WriteData startWrite(
        int chunkX,
        int chunkZ,
        @Nullable CompoundTag compound
    ) throws IOException;

    void finishWrite(
        int chunkX,
        int chunkZ,
        MoonriseRegionFileIO.RegionDataController.WriteData writeData
    ) throws IOException;

    MoonriseRegionFileIO.RegionDataController.ReadData readData(int chunkX, int chunkZ) throws IOException;

    @Nullable CompoundTag finishRead(
        int chunkX,
        int chunkZ,
        MoonriseRegionFileIO.RegionDataController.ReadData readData
    ) throws IOException;

    @Nullable CompoundTag read(int chunkX, int chunkZ) throws IOException;

    void scanChunk(int chunkX, int chunkZ, StreamTagVisitor scanner) throws IOException;

    void write(int chunkX, int chunkZ, @Nullable CompoundTag compound) throws IOException;

    void flush() throws IOException;

    @Override
    void close() throws IOException;

    /**
     * Resolves the active {@link RegionStorageProvider} for {@code folder} based on
     * {@link GoldLeafConfig}, returning {@code null} when vanilla {@code .mca} storage is active.
     */
    static @Nullable RegionStorageProvider createFor(
        final RegionStorageInfo info,
        final Path folder
    ) {
        final GoldLeafConfig config = GoldLeafConfig.get();
        if (config == null || config.storage == null) {
            return null;
        }
        if ("lca".equalsIgnoreCase(config.storage.format)) {
            return LcaRegionStorageProvider.open(folder, config.storage);
        }
        return null;
    }
}
