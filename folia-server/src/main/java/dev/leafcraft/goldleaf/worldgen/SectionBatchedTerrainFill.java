package dev.leafcraft.goldleaf.worldgen;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 single-chunk section-batched terrain fill for {@code NoiseBasedChunkGenerator#doFill}
 * ({@code goldleaf-ADR-004} §2.1).
 *
 * <p>Pre-fetches the chunk's already-acquired {@link LevelChunkSection} slice once per chunk and
 * iterates {@code (z, x)} outer with 16-block section bands inner. This eliminates per-voxel
 * {@code chunk.getSection(chunk.getSectionIndex(blockY))} lookups (24 lookups instead of 98,304
 * in Overworld) while preserving stride-1 {@link DensityVolume} Y indexing, exact vanilla
 * {@code (z, x, y)} visit order, heightmap updates, and fluid post-processing marks.</p>
 *
 * <p>Strictly operates on a single chunk's already-acquired sections and thread-local
 * {@link NoiseChunk} state on the owning Moonrise worker thread (zero cross-chunk or cross-thread
 * mutable state).</p>
 */
public final class SectionBatchedTerrainFill {

    @FunctionalInterface
    public interface DebugStateTransform {
        BlockState apply(NoiseChunk noiseChunk, int blockX, int blockY, int blockZ, BlockState state);
    }

    @FunctionalInterface
    public interface SectionLookup<S> {
        S getSection(int sectionIndex);
    }

    @FunctionalInterface
    public interface SubstanceResolver<B> {
        @Nullable B resolve(int blockX, int blockY, int blockZ, float density);
    }

    @FunctionalInterface
    public interface VoxelSink<S, B> {
        void write(S section, int x, int localY, int z, int blockX, int blockY, int blockZ, B state);
    }

    private SectionBatchedTerrainFill() {
    }

    /**
     * Executes section-batched {@code doFill} for a single chunk with 100% vanilla-exact ordering.
     */
    public static void fillChunk(
        final NoiseChunk noiseChunk,
        final ChunkAccess chunk,
        final DensityBuffer densityBuffer,
        final BlockState defaultBlock,
        final BlockState airState,
        final DebugStateTransform debugTransform
    ) {
        final DensityVolume volume = noiseChunk.volume();
        final Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        final Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        final Aquifer aquifer = noiseChunk.aquifer();
        final BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();

        final int bottomSectionIndex = chunk.getSectionIndex(volume.minBlockY());
        final int topSectionIndex = chunk.getSectionIndex(volume.maxBlockY());
        final int sectionCount = topSectionIndex - bottomSectionIndex + 1;
        final LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            sections[i] = chunk.getSection(bottomSectionIndex + i);
        }

        final int sizeX = volume.sizeX();
        final int sizeY = volume.sizeY();
        final int sizeZ = volume.sizeZ();
        final int minBlockY = volume.minBlockY();
        final int bottomSectionCoord = SectionPos.blockToSectionCoord(minBlockY);
        final boolean unitStepY = volume.stepBlockY() == 1;

        for (int z = 0; z < sizeZ; z++) {
            final int blockZ = volume.blockZ(z);
            for (int x = 0; x < sizeX; x++) {
                final int blockX = volume.blockX(x);
                final int columnBase = (x + z * sizeX) * sizeY;

                if (unitStepY) {
                    int y = sizeY - 1;
                    while (y >= 0) {
                        final int bandTopBlockY = minBlockY + y;
                        final int sectionOffset = SectionPos.blockToSectionCoord(bandTopBlockY) - bottomSectionCoord;
                        final LevelChunkSection section = sections[sectionOffset];
                        final int topLocalY = SectionPos.sectionRelative(bandTopBlockY);
                        final int stepsInSection = Math.min(topLocalY + 1, y + 1);

                        for (int step = 0; step < stepsInSection; step++) {
                            final int curY = y - step;
                            final int blockY = bandTopBlockY - step;
                            final int localY = topLocalY - step;
                            final float density = densityBuffer.get(columnBase + curY);
                            BlockState state = aquifer.computeSubstance(blockX, blockY, blockZ, density);
                            if (state == null) {
                                state = defaultBlock;
                            }
                            state = debugTransform.apply(noiseChunk, blockX, blockY, blockZ, state);
                            if (state != airState) {
                                section.setBlockState(x, localY, z, state, false);
                                oceanFloor.update(x, blockY, z, state);
                                worldSurface.update(x, blockY, z, state);
                                if (aquifer.shouldScheduleFluidUpdate() && !state.getFluidState().isEmpty()) {
                                    blockPos.set(blockX, blockY, blockZ);
                                    chunk.markPosForPostProcessing(blockPos);
                                }
                            }
                        }
                        y -= stepsInSection;
                    }
                } else {
                    for (int y = sizeY - 1; y >= 0; y--) {
                        final int blockY = volume.blockY(y);
                        final int sectionOffset = SectionPos.blockToSectionCoord(blockY) - bottomSectionCoord;
                        final LevelChunkSection section = sections[sectionOffset];
                        final float density = densityBuffer.get(columnBase + y);
                        BlockState state = aquifer.computeSubstance(blockX, blockY, blockZ, density);
                        if (state == null) {
                            state = defaultBlock;
                        }
                        state = debugTransform.apply(noiseChunk, blockX, blockY, blockZ, state);
                        if (state != airState) {
                            section.setBlockState(x, SectionPos.sectionRelative(blockY), z, state, false);
                            oceanFloor.update(x, blockY, z, state);
                            worldSurface.update(x, blockY, z, state);
                            if (aquifer.shouldScheduleFluidUpdate() && !state.getFluidState().isEmpty()) {
                                blockPos.set(blockX, blockY, blockZ);
                                chunk.markPosForPostProcessing(blockPos);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Pure, bootstrap-free generic section-batched kernel mirroring {@link #fillChunk} for
     * deterministic vanilla-equivalence and lookup-count unit verification.
     */
    @SuppressWarnings("unchecked")
    public static <S, B> void fillSections(
        final DensityVolume volume,
        final float[] densityValues,
        final int minWorldSectionCoord,
        final SectionLookup<S> sectionLookup,
        final SubstanceResolver<B> substanceResolver,
        final B defaultState,
        final B airState,
        final VoxelSink<S, B> voxelSink
    ) {
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(densityValues, "densityValues");
        Objects.requireNonNull(sectionLookup, "sectionLookup");
        Objects.requireNonNull(substanceResolver, "substanceResolver");
        Objects.requireNonNull(defaultState, "defaultState");
        Objects.requireNonNull(airState, "airState");
        Objects.requireNonNull(voxelSink, "voxelSink");

        if (densityValues.length < volume.size()) {
            throw new IllegalArgumentException(
                "densityValues length (" + densityValues.length + ") < volume.size() (" + volume.size() + ")"
            );
        }

        final int bottomSectionCoord = SectionPos.blockToSectionCoord(volume.minBlockY());
        final int topSectionCoord = SectionPos.blockToSectionCoord(volume.maxBlockY());
        final int bottomSectionIndex = bottomSectionCoord - minWorldSectionCoord;
        final int sectionCount = topSectionCoord - bottomSectionCoord + 1;
        final Object[] sections = new Object[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            sections[i] = sectionLookup.getSection(bottomSectionIndex + i);
        }

        final int sizeX = volume.sizeX();
        final int sizeY = volume.sizeY();
        final int sizeZ = volume.sizeZ();
        final int minBlockY = volume.minBlockY();
        final boolean unitStepY = volume.stepBlockY() == 1;

        for (int z = 0; z < sizeZ; z++) {
            final int blockZ = volume.blockZ(z);
            for (int x = 0; x < sizeX; x++) {
                final int blockX = volume.blockX(x);
                final int columnBase = (x + z * sizeX) * sizeY;

                if (unitStepY) {
                    int y = sizeY - 1;
                    while (y >= 0) {
                        final int bandTopBlockY = minBlockY + y;
                        final int sectionOffset = SectionPos.blockToSectionCoord(bandTopBlockY) - bottomSectionCoord;
                        final S section = (S) sections[sectionOffset];
                        final int topLocalY = SectionPos.sectionRelative(bandTopBlockY);
                        final int stepsInSection = Math.min(topLocalY + 1, y + 1);

                        for (int step = 0; step < stepsInSection; step++) {
                            final int curY = y - step;
                            final int blockY = bandTopBlockY - step;
                            final int localY = topLocalY - step;
                            final float density = densityValues[columnBase + curY];
                            B state = substanceResolver.resolve(blockX, blockY, blockZ, density);
                            if (state == null) {
                                state = defaultState;
                            }
                            if (state != airState) {
                                voxelSink.write(section, x, localY, z, blockX, blockY, blockZ, state);
                            }
                        }
                        y -= stepsInSection;
                    }
                } else {
                    for (int y = sizeY - 1; y >= 0; y--) {
                        final int blockY = volume.blockY(y);
                        final int sectionOffset = SectionPos.blockToSectionCoord(blockY) - bottomSectionCoord;
                        final S section = (S) sections[sectionOffset];
                        final float density = densityValues[columnBase + y];
                        B state = substanceResolver.resolve(blockX, blockY, blockZ, density);
                        if (state == null) {
                            state = defaultState;
                        }
                        if (state != airState) {
                            voxelSink.write(section, x, SectionPos.sectionRelative(blockY), z, blockX, blockY, blockZ, state);
                        }
                    }
                }
            }
        }
    }
}
