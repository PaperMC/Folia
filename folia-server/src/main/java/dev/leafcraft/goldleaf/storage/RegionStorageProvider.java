package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.spi.RegionStorageSpi;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.IntFunction;
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
public interface RegionStorageProvider extends RegionStorageSpi {

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

    /**
     * Reads the cold uncompressed {@code .lca} {@link RawChunkFrameSnapshot} directly from disk
     * without synthesizing Mojang NBT ({@code goldleaf-ADR-005} Stage 2, {@code goldleaf-ADR-002}
     * Tier 3). Must execute strictly on an async I/O thread ({@code S-005}).
     */
    default @Nullable RawChunkFrameSnapshot readRawChunkFrame(
        final int chunkX,
        final int chunkZ,
        final Function<String, Integer> runtimeStateLookup
    ) throws IOException {
        return null;
    }

    /**
     * Splices dirty sections into a cold {@code .lca} {@code ChunkFrame} and commits via
     * Copy-on-Write, returning the new uncompressed frame bytes for atomic baseline swap
     * ({@code goldleaf-ADR-005} Stage 2).
     */
    default byte @Nullable [] commitAndGetSplicedChunkFrameCoW(
        final int chunkX,
        final int chunkZ,
        final byte[] coldChunkFrame,
        final int dirtySectionMask,
        final IntFunction<int[]> dirtySectionVoxels,
        final byte @Nullable [] updatedResidualNbtOrNull
    ) throws IOException {
        return null;
    }

    @Override
    void close() throws IOException;

    /**
     * Returns {@code true} if {@code folder} should be backed by native {@code .lca} storage.
     *
     * <p>Implements non-destructive legacy {@code .mca} auto-detection ({@code goldleaf-ADR-005}):
     * when {@code storage.format} is {@code "lca"} and {@code auto-migrate-mca} is {@code false},
     * an existing folder containing {@code .mca} files and no {@code .lca} files falls back cleanly
     * to vanilla {@code .mca} storage instead of opening an empty {@code .lca} archive over live data.</p>
     */
    static boolean shouldUseLcaForFolder(
        final Path folder,
        final GoldLeafConfig.Storage storageConfig
    ) {
        if (storageConfig == null || !"lca".equalsIgnoreCase(storageConfig.format)) {
            return false;
        }
        if (storageConfig.autoMigrateMca || folder == null || !java.nio.file.Files.isDirectory(folder)) {
            return true;
        }
        boolean hasMca = false;
        boolean hasLca = false;
        try (java.util.stream.Stream<Path> entries = java.nio.file.Files.list(folder)) {
            final java.util.Iterator<Path> it = entries.iterator();
            while (it.hasNext()) {
                final Path entry = it.next();
                final Path fileName = entry.getFileName();
                if (fileName == null) {
                    continue;
                }
                final String name = fileName.toString();
                if (name.endsWith(".lca")) {
                    hasLca = true;
                    break;
                }
                if (name.endsWith(".mca")) {
                    hasMca = true;
                }
            }
        } catch (final IOException ex) {
            org.slf4j.LoggerFactory.getLogger(RegionStorageProvider.class)
                .warn("Failed to inspect region folder {} for legacy .mca files; defaulting to vanilla .mca", folder, ex);
            return false;
        }
        if (hasMca && !hasLca) {
            org.slf4j.LoggerFactory.getLogger(RegionStorageProvider.class).info(
                "GoldLeaf detected existing vanilla .mca files in {} with storage.auto-migrate-mca=false; "
                    + "preserving vanilla .mca non-destructively (enable auto-migrate-mca or run LcaMigrationCli to convert).",
                folder
            );
            return false;
        }
        return true;
    }

    /**
     * Resolves the active {@link RegionStorageProvider} for {@code folder} based on
     * {@link GoldLeafConfig}, returning {@code null} when vanilla {@code .mca} storage is active
     * or preserved via non-destructive legacy {@code .mca} auto-detection.
     */
    static @Nullable RegionStorageProvider createFor(
        final RegionStorageInfo info,
        final Path folder
    ) {
        final GoldLeafConfig config = GoldLeafConfig.get();
        if (config == null || config.storage == null) {
            return null;
        }
        if (shouldUseLcaForFolder(folder, config.storage)) {
            return LcaRegionStorageProvider.open(folder, config.storage);
        }
        return null;
    }
}
