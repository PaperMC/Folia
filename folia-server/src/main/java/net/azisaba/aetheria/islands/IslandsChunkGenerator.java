package net.azisaba.aetheria.islands;

import com.mojang.serialization.MapCodec;
import de.articdive.jnoise.core.api.functions.Interpolation;
import de.articdive.jnoise.generators.noise_parameters.fade_functions.FadeFunction;
import de.articdive.jnoise.modules.octavation.fractal_functions.FractalFunction;
import de.articdive.jnoise.pipeline.JNoise;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionDefaults;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@NullMarked
public class IslandsChunkGenerator extends ChunkGenerator {
    private final IslandsGeneratorSettings settings;

    private final Map<Long, IslandBoundary> boundaryCache = new ConcurrentHashMap<>();
    private final Map<Long, JNoise> seabedNoiseCache = new ConcurrentHashMap<>();

    public IslandsChunkGenerator(final IslandsGeneratorSettings settings, final BiomeSource biomeSource) {
        super(biomeSource);
        this.settings = settings;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return MapCodec.unit(this);
    }

    @Override
    public int getMinY() {
        return DimensionDefaults.OVERWORLD_MIN_Y;
    }

    @Override
    public int getSeaLevel() {
        return this.settings.seaLevel();
    }

    @Override
    public int getGenDepth() {
        return DimensionDefaults.OVERWORLD_GENERATION_HEIGHT;
    }

    @Override
    public int getBaseHeight(final int x, final int z, final Heightmap.Types type, final LevelHeightAccessor level, final RandomState random) {
        final double signedDistance = this.computeSignedDistance(0L, x, z);
        final boolean isLand = signedDistance <= 0;
        if (isLand) {
            final boolean isBeach = -signedDistance < this.settings.beachWidth();
            return (isBeach ? this.settings.seaLevel() : this.settings.landTopY()) + 1;
        }

        final int oceanFloorY = this.computeOceanFloorY(0L, x, z, signedDistance);
        return switch (type) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> oceanFloorY + 1;
            default -> this.settings.seaLevel() + 1;
        };
    }

    @Override
    public NoiseColumn getBaseColumn(final int x, final int z, final LevelHeightAccessor height, final RandomState random) {
        final double signedDistance = this.computeSignedDistance(0L, x, z);
        final boolean isLand = signedDistance <= 0;
        final boolean isBeach = isLand && -signedDistance < this.settings.beachWidth();
        final int minY = height.getMinY();
        final int maxY = height.getMaxY();
        final BlockState[] column = new BlockState[height.getHeight()];
        final int oceanFloorY = this.computeOceanFloorY(0L, x, z, signedDistance);

        for (int y = minY; y < maxY; y++) {
            final BlockState blockState;
            if (isLand) {
                final int surfaceY = isBeach ? this.settings.seaLevel() : this.settings.landTopY() - 1;
                if (y < surfaceY - 3) {
                    blockState = Blocks.STONE.defaultBlockState();
                } else if (y < surfaceY) {
                    blockState = isBeach ? Blocks.SAND.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                } else if (y == surfaceY) {
                    blockState = isBeach ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                } else {
                    blockState = Blocks.AIR.defaultBlockState();
                }
            } else {
                if (y < oceanFloorY - 3) {
                    blockState = Blocks.STONE.defaultBlockState();
                } else if (y <= oceanFloorY) {
                    blockState = Blocks.SAND.defaultBlockState();
                } else if (y <= this.settings.seaLevel()) {
                    blockState = Blocks.WATER.defaultBlockState();
                } else {
                    blockState = Blocks.AIR.defaultBlockState();
                }
            }
            column[y - minY] = blockState;
        }

        return new NoiseColumn(minY, column);
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk) {

    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState random, ChunkAccess chunk) {

    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(final Blender blender, final RandomState randomState, final StructureManager structureManager, final ChunkAccess chunk) {
        final long levelSeed = structureManager.level.getMinecraftWorld().getSeed();
        final ChunkPos chunkPos = chunk.getPos();

        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dx = 0; dx < 16; dx++) {
            final int blockX = chunkPos.getMinBlockX() + dx;
            for (int dz = 0; dz < 16; dz++) {
                final int blockZ = chunkPos.getMinBlockZ() + dz;

                final double signedDistance = this.computeSignedDistance(levelSeed, blockX, blockZ);

                pos.set(blockX, 0, blockZ);

                if (signedDistance <= 0) {
                    final boolean isBeach = -signedDistance < this.settings.beachWidth();
                    this.writeLandColumn(chunk, pos, isBeach);
                } else {
                    this.writeOceanColumn(chunk, pos, this.computeOceanFloorY(levelSeed, blockX, blockZ, signedDistance));
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {

    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {

    }

    private double computeSignedDistance(final long levelSeed, final int blockX, final int blockZ) {
        final IslandPos islandPos = IslandPos.fromBlockPos(blockX, blockZ);

        final long boundaryKey = this.computeBoundaryKey(levelSeed, islandPos.gridX(), islandPos.gridZ());
        final IslandBoundary boundary = this.boundaryCache.computeIfAbsent(
                boundaryKey,
                k -> IslandBoundary.createDefault(islandPos.computeSeed(levelSeed))
        );

        final double localX = blockX - islandPos.centerBlockX();
        final double localZ = blockZ - islandPos.centerBlockZ();

        return boundary.signedDistance(localX, localZ);
    }

    private long computeBoundaryKey(final long levelSeed, final int gridX, final int gridZ) {
        long h = levelSeed;

        h ^= ((long) gridX * 0x9E3779B97F4A7C15L);
        h = Long.rotateLeft(h, 27);

        h ^= ((long) gridZ * 0xC2B2AE3D27D4EB4FL);
        h = Long.rotateLeft(h, 31);

        return h;
    }

    private void writeLandColumn(final ChunkAccess chunk, final BlockPos.MutableBlockPos pos, final boolean isBeach) {
        final int minY = chunk.getMinY();
        final int surfaceY = isBeach ? this.settings.seaLevel() : this.settings.landTopY() - 1;

        for (int y = minY; y < surfaceY - 3; y++) {
            pos.setY(y);
            chunk.setBlockState(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_NONE);
        }
        for (int y = surfaceY - 3; y < surfaceY; y++) {
            pos.setY(y);
            chunk.setBlockState(
                    pos,
                    isBeach ? Blocks.SAND.defaultBlockState() : Blocks.DIRT.defaultBlockState(),
                    Block.UPDATE_NONE
            );
        }

        pos.setY(surfaceY);
        chunk.setBlockState(
                pos,
                isBeach ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(),
                Block.UPDATE_NONE
        );

        for (int y = surfaceY + 1; y < this.settings.airTopY(); y++) {
            pos.setY(y);
            chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_NONE);
        }
    }

    private int computeOceanFloorY(final long levelSeed, final int blockX, final int blockZ, final double signedDistance) {
        final double distanceFromShore = Math.max(0.0, signedDistance);
        final int gradualDepth = Math.min(this.settings.seaDepth(), (int) Math.floor(distanceFromShore / 8.0));
        final double noiseSample = this.getSeabedNoise(levelSeed).evaluateNoise(blockX, blockZ);

        final int depth;
        if (gradualDepth < this.settings.seaDepth()) {
            final double progress = this.settings.seaDepth() == 0 ? 1.0 : (double) gradualDepth / this.settings.seaDepth();
            final int bump = (int) Math.round(noiseSample * (0.5 + progress));
            depth = Math.max(0, Math.min(this.settings.seaDepth(), gradualDepth + bump));
        } else {
            final int bump = (int) Math.round(noiseSample * 2.0);
            depth = Math.max(this.settings.seaDepth() - 2, Math.min(this.settings.seaDepth() + 2, this.settings.seaDepth() + bump));
        }

        return this.settings.seaLevel() - depth;
    }

    private JNoise getSeabedNoise(final long levelSeed) {
        return this.seabedNoiseCache.computeIfAbsent(levelSeed, seed -> JNoise.newBuilder()
                .perlin(seed ^ 0xD1342543DE82EF95L, Interpolation.COSINE, FadeFunction.CUBIC_POLY)
                .scale(1.0 / 16.0)
                .octavate(3, 0.55, 2.1, FractalFunction.FBM, false)
                .build());
    }

    private void writeOceanColumn(final ChunkAccess chunk, final BlockPos.MutableBlockPos pos, final int oceanFloorY) {
        for (int y = chunk.getMinY(); y < oceanFloorY - 3; y++) {
            pos.setY(y);
            chunk.setBlockState(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_NONE);
        }
        for (int y = oceanFloorY - 3; y <= oceanFloorY; y++) {
            pos.setY(y);
            chunk.setBlockState(pos, Blocks.SAND.defaultBlockState(), Block.UPDATE_NONE);
        }
        for (int y = oceanFloorY + 1; y <= this.settings.seaLevel(); y++) {
            pos.setY(y);
            chunk.setBlockState(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_NONE);
        }
        for (int y = this.settings.seaLevel() + 1; y < this.settings.airTopY(); y++) {
            pos.setY(y);
            chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_NONE);
        }
    }
}
