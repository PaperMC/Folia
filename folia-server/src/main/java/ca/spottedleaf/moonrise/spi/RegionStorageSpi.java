package ca.spottedleaf.moonrise.spi;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate pluggable region-storage backend SPI behind
 * {@code ChunkSystemRegionFileStorage}.
 *
 * <p>Zero external-engine or product-config coupling: external storage engines register a
 * {@link Factory} at boot. When no factory is registered, {@link #createFor} returns {@code null}
 * and vanilla {@code .mca} storage executes untouched.</p>
 */
public interface RegionStorageSpi extends Closeable {

    @FunctionalInterface
    interface Factory {
        @Nullable RegionStorageSpi create(RegionStorageInfo info, Path folder);
    }

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

    static void registerFactory(final @Nullable Factory factory) {
        Holder.factory = factory;
    }

    static @Nullable Factory getFactory() {
        return Holder.factory;
    }

    static void resetDefault() {
        Holder.factory = null;
    }

    static @Nullable RegionStorageSpi createFor(final RegionStorageInfo info, final Path folder) {
        final Factory factory = Holder.factory;
        return factory == null ? null : factory.create(info, folder);
    }

    final class Holder {
        private static volatile @Nullable Factory factory;

        private Holder() {
        }
    }
}
