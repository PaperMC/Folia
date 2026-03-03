package net.azisaba.vanilife.server.islands;

import com.mojang.serialization.MapCodec;
import de.articdive.jnoise.core.api.functions.Interpolation;
import de.articdive.jnoise.generators.noise_parameters.fade_functions.FadeFunction;
import de.articdive.jnoise.modules.octavation.fractal_functions.FractalFunction;
import de.articdive.jnoise.pipeline.JNoise;
import net.azisaba.vanilife.islands.IslandPos;
import net.azisaba.vanilife.server.islands.noise.IslandNoise;
import net.azisaba.vanilife.server.islands.river.RiverMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.dimension.DimensionDefaults;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@NullMarked
public class IslandsChunkGenerator extends ChunkGenerator {
    private static final int DEEP_OCEAN_VARIATION = 2;
    private static final int SURFACE_LAYER_THICKNESS = 3;
    private static final int WATERFALL_DROP_THRESHOLD = 2;
    private static final int WATERFALL_CONTINUATION_DEPTH = 1;
    private static final int FALLING_WATER_LEVEL = 8;

    private final IslandsGeneratorSettings settings;

    private final Map<Long, IslandNoise> islandNoiseCache = new ConcurrentHashMap<>();
    private final Map<Long, RiverMap> riverMapCache = new ConcurrentHashMap<>();
    private final Map<Long, JNoise> deepOceanNoiseCache = new ConcurrentHashMap<>();

    private record TerrainSample(double signedDistance, int baseHighestY, int riverSurfaceBaseY, int highestY, int riverDepth) {
        private boolean hasRiver() {
            return this.riverDepth > 0;
        }
    }

    private record BaseTerrainSample(double signedDistance, int baseHighestY, int riverSurfaceBaseY, int riverDepth) {
    }

    private record WaterProtrusion(int x, int z, int fromY, int toY) {
    }

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
        final TerrainSample sample = this.sampleIslandPoint(0L, x, z);
        final int highestY = this.resolveHighestY(0L, x, z, sample.highestY());
        if (highestY > this.settings.seaLevel()) {
            return highestY + 1;
        }
        return switch (type) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> highestY + 1;
            default -> this.settings.seaLevel() + 1;
        };
    }

    @Override
    public NoiseColumn getBaseColumn(final int x, final int z, final LevelHeightAccessor height, final RandomState random) {
        final TerrainSample sample = this.sampleIslandPoint(0L, x, z);
        final int highestY = this.resolveHighestY(0L, x, z, sample.highestY());
        final int riverSurfaceY = this.riverSurfaceY(sample, highestY);
        final int riverWallSurfaceY = !sample.hasRiver() && highestY > this.settings.seaLevel()
                ? this.resolveRiverWallSurfaceY(0L, x, z, highestY)
                : Integer.MIN_VALUE;
        final int minY = height.getMinY();
        final int maxY = height.getMaxY();
        final BlockState[] column = new BlockState[height.getHeight()];

        for (int y = minY; y < maxY; y++) {
            column[y - minY] = this.blockStateAtY(highestY, riverSurfaceY, riverWallSurfaceY, y);
        }

        return new NoiseColumn(minY, column);
    }

    @Override
    public void applyCarvers(final WorldGenRegion region, final long seed, final RandomState random, final BiomeManager biomeManager, final StructureManager structureManager, final ChunkAccess chunk) {

    }

    @Override
    public void buildSurface(final WorldGenRegion region, final StructureManager structureManager, final RandomState random, final ChunkAccess chunk) {

    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(final Blender blender, final RandomState randomState, final StructureManager structureManager, final ChunkAccess chunk) {
        final long levelSeed = structureManager.level.getMinecraftWorld().getSeed();
        final ChunkPos chunkPos = chunk.getPos();

        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final List<WaterProtrusion> protrusions = new ArrayList<>();

        for (int dx = 0; dx < 16; dx++) {
            final int blockX = chunkPos.getMinBlockX() + dx;
            for (int dz = 0; dz < 16; dz++) {
                final int blockZ = chunkPos.getMinBlockZ() + dz;
                final TerrainSample sample = this.sampleIslandPoint(levelSeed, blockX, blockZ);
                final int highestY = this.resolveHighestY(levelSeed, blockX, blockZ, sample.highestY());
                final int riverSurfaceY = this.riverSurfaceY(sample, highestY);
                final int riverWallSurfaceY = !sample.hasRiver() && highestY > this.settings.seaLevel()
                        ? this.resolveRiverWallSurfaceY(levelSeed, blockX, blockZ, highestY)
                        : Integer.MIN_VALUE;

                pos.set(blockX, 0, blockZ);
                for (int y = chunk.getMinY(); y < this.settings.airTopY(); y++) {
                    pos.setY(y);
                    chunk.setBlockState(pos, this.blockStateAtY(highestY, riverSurfaceY, riverWallSurfaceY, y), Block.UPDATE_NONE);
                }

                if (sample.hasRiver() && this.isWaterfallCell(levelSeed, blockX, blockZ, highestY)) {
                    final int upstreamSurfaceY = this.resolveUpstreamRiverSurfaceY(levelSeed, blockX, blockZ, highestY);
                    if (upstreamSurfaceY > riverSurfaceY && upstreamSurfaceY < this.settings.airTopY()) {
                        final int fromY = Math.max(highestY + 1, riverSurfaceY + 1);
                        protrusions.add(new WaterProtrusion(blockX, blockZ, fromY, upstreamSurfaceY));
                    }
                }
            }
        }

        for (final WaterProtrusion protrusion : protrusions) {
            for (int y = protrusion.fromY(); y <= protrusion.toY(); y++) {
                pos.set(protrusion.x(), y, protrusion.z());
                final BlockState current = chunk.getBlockState(pos);
                if (!current.isAir() && !current.getFluidState().isSource()) {
                    continue;
                }
                final BlockState water = y == protrusion.toY()
                        ? Blocks.WATER.defaultBlockState()
                        : Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, FALLING_WATER_LEVEL);
                chunk.setBlockState(pos, water, Block.UPDATE_NONE);
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void createStructures(final RegistryAccess registryAccess, final ChunkGeneratorStructureState structureState, final StructureManager structureManager, final ChunkAccess chunk, final StructureTemplateManager structureTemplateManager, final ResourceKey<Level> level) {
    }

    @Override
    public void spawnOriginalMobs(final WorldGenRegion region) {
    }

    @Override
    public void addDebugScreenInfo(final List<String> info, final RandomState random, final BlockPos pos) {
    }

    private IslandNoise getIslandNoise(final long levelSeed, final IslandPos islandPos) {
        long noiseKey = levelSeed;

        noiseKey ^= ((long) islandPos.x() * 0x9E3779B97F4A7C15L);
        noiseKey = Long.rotateLeft(noiseKey, 27);

        noiseKey ^= ((long) islandPos.z() * 0xC2B2AE3D27D4EB4FL);
        noiseKey = Long.rotateLeft(noiseKey, 31);

        return this.islandNoiseCache.computeIfAbsent(noiseKey, k -> IslandNoise.createDefault(islandPos.computeSeed(levelSeed)));
    }

    private RiverMap getRiverMap(final long levelSeed, final IslandPos islandPos, final IslandNoise islandNoise) {
        long noiseKey = levelSeed;

        noiseKey ^= ((long) islandPos.x() * 0x9E3779B97F4A7C15L);
        noiseKey = Long.rotateLeft(noiseKey, 27);

        noiseKey ^= ((long) islandPos.z() * 0xC2B2AE3D27D4EB4FL);
        noiseKey = Long.rotateLeft(noiseKey, 31);

        return this.riverMapCache.computeIfAbsent(noiseKey, k -> RiverMap.createDefault(islandNoise, this.settings));
    }

    private JNoise getDeepOceanNoise(final long levelSeed) {
        return this.deepOceanNoiseCache.computeIfAbsent(levelSeed, seed -> JNoise.newBuilder()
                .perlin(seed ^ 0xD1342543DE82EF95L, Interpolation.COSINE, FadeFunction.CUBIC_POLY)
                .scale(1.0 / 16.0)
                .octavate(3, 0.55, 2.1, FractalFunction.FBM, false)
                .build());
    }

    private TerrainSample sampleIslandPoint(final long levelSeed, final int blockX, final int blockZ) {
        final BaseTerrainSample baseSample = this.sampleBaseTerrainPoint(levelSeed, blockX, blockZ);
        int riverDepth = Math.max(0, baseSample.riverDepth());
        if (riverDepth == 0 && this.shouldContinueRiverAfterWaterfall(levelSeed, blockX, blockZ, baseSample.baseHighestY())) {
            riverDepth = WATERFALL_CONTINUATION_DEPTH;
        }
        int carvedHighestY = baseSample.baseHighestY() - riverDepth;
        if (riverDepth > 0) {
            carvedHighestY = Math.min(carvedHighestY, baseSample.riverSurfaceBaseY() - 1);
        }
        final int highestY = Math.max(this.settings.seaLevel() - 1, carvedHighestY);
        return new TerrainSample(
                baseSample.signedDistance(),
                baseSample.baseHighestY(),
                baseSample.riverSurfaceBaseY(),
                highestY,
                riverDepth
        );
    }

    private BaseTerrainSample sampleBaseTerrainPoint(final long levelSeed, final int blockX, final int blockZ) {
        final IslandPos islandPos = IslandPos.fromBlockPos(blockX, blockZ);
        final IslandNoise islandNoise = this.getIslandNoise(levelSeed, islandPos);
        final RiverMap riverMap = this.getRiverMap(levelSeed, islandPos, islandNoise);
        final double localX = blockX - islandPos.centerBlockX();
        final double localZ = blockZ - islandPos.centerBlockZ();
        final double signedDistance = islandNoise.computeSignedDistance(localX, localZ);
        final int baseHighestY = islandNoise.computeBaseHighestY(localX, localZ, signedDistance, this.settings);
        final int riverSurfaceBaseY = islandNoise.computeTerracedHighestY(localX, localZ, signedDistance, this.settings);
        final int riverDepth = riverMap.sampleDepth(localX, localZ, signedDistance, baseHighestY);
        return new BaseTerrainSample(signedDistance, baseHighestY, riverSurfaceBaseY, Math.max(0, riverDepth));
    }

    private boolean shouldContinueRiverAfterWaterfall(final long levelSeed, final int blockX, final int blockZ, final int baseHighestY) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (Math.abs(ox) + Math.abs(oz) != 1) {
                    continue;
                }
                final BaseTerrainSample neighbor = this.sampleBaseTerrainPoint(levelSeed, blockX + ox, blockZ + oz);
                if (neighbor.riverDepth() <= 0) {
                    continue;
                }
                if (neighbor.baseHighestY() - baseHighestY >= WATERFALL_DROP_THRESHOLD) {
                    return true;
                }
            }
        }
        return false;
    }

    private int resolveHighestY(final long levelSeed, final int blockX, final int blockZ, final int baseHighestY) {
        final int deepBaseFloorY = this.settings.seaLevel() - this.settings.seaDepth();
        if (baseHighestY > deepBaseFloorY) {
            return baseHighestY;
        }

        final int bump = (int) Math.round(this.getDeepOceanNoise(levelSeed).evaluateNoise(blockX, blockZ) * DEEP_OCEAN_VARIATION);
        return deepBaseFloorY + bump;
    }

    private boolean isWaterfallCell(final long levelSeed, final int blockX, final int blockZ, final int highestY) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) {
                    continue;
                }
                if (Math.abs(ox) + Math.abs(oz) != 1) {
                    continue;
                }
                final int neighborX = blockX + ox;
                final int neighborZ = blockZ + oz;
                final TerrainSample neighborSample = this.sampleIslandPoint(levelSeed, neighborX, neighborZ);
                if (!neighborSample.hasRiver()) {
                    continue;
                }
                final int neighborHighestY = this.resolveHighestY(levelSeed, neighborX, neighborZ, neighborSample.highestY());
                if (neighborHighestY - highestY >= WATERFALL_DROP_THRESHOLD) {
                    return true;
                }
            }
        }
        return false;
    }

    private int resolveRiverWallSurfaceY(final long levelSeed, final int blockX, final int blockZ, final int highestY) {
        int wallSurfaceY = Integer.MIN_VALUE;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (Math.abs(ox) + Math.abs(oz) != 1) {
                    continue;
                }
                final int neighborX = blockX + ox;
                final int neighborZ = blockZ + oz;
                final TerrainSample neighborSample = this.sampleIslandPoint(levelSeed, neighborX, neighborZ);
                if (!neighborSample.hasRiver()) {
                    continue;
                }
                final int neighborHighestY = this.resolveHighestY(levelSeed, neighborX, neighborZ, neighborSample.highestY());
                if (neighborHighestY <= this.settings.seaLevel()) {
                    continue;
                }
                final int neighborSurfaceY = this.riverSurfaceY(neighborSample, neighborHighestY);
                if (neighborSurfaceY <= highestY) {
                    continue;
                }
                wallSurfaceY = Math.max(wallSurfaceY, neighborSurfaceY);
            }
        }
        return wallSurfaceY;
    }

    private int resolveUpstreamRiverSurfaceY(final long levelSeed, final int blockX, final int blockZ, final int highestY) {
        int bestUpstreamSurfaceY = Integer.MIN_VALUE;

        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (Math.abs(ox) + Math.abs(oz) != 1) {
                    continue;
                }
                final int neighborX = blockX + ox;
                final int neighborZ = blockZ + oz;
                final TerrainSample neighborSample = this.sampleIslandPoint(levelSeed, neighborX, neighborZ);
                if (!neighborSample.hasRiver()) {
                    continue;
                }
                final int neighborHighestY = this.resolveHighestY(levelSeed, neighborX, neighborZ, neighborSample.highestY());
                final int rise = neighborHighestY - highestY;
                if (rise >= WATERFALL_DROP_THRESHOLD) {
                    bestUpstreamSurfaceY = Math.max(bestUpstreamSurfaceY, this.riverSurfaceY(neighborSample, neighborHighestY));
                }
            }
        }

        return bestUpstreamSurfaceY;
    }

    private int riverSurfaceY(final TerrainSample sample, final int highestY) {
        if (!sample.hasRiver() || highestY <= this.settings.seaLevel()) {
            return Integer.MIN_VALUE;
        }
        return Math.max(sample.riverSurfaceBaseY(), highestY + 1);
    }

    private BlockState blockStateAtY(final int highestY, final int riverSurfaceY, final int riverWallSurfaceY, final int y) {
        return highestY > this.settings.seaLevel()
                ? this.landBlockStateAtY(highestY, riverSurfaceY, riverWallSurfaceY, y)
                : this.oceanBlockStateAtY(highestY, y);
    }

    private BlockState landBlockStateAtY(final int highestY, final int riverSurfaceY, final int riverWallSurfaceY, final int y) {
        final boolean hasRiverWater = riverSurfaceY > highestY;
        final boolean hasRiverWall = !hasRiverWater && riverWallSurfaceY > highestY;
        if (y < highestY - SURFACE_LAYER_THICKNESS) {
            return Blocks.STONE.defaultBlockState();
        } else if (y < highestY) {
            return hasRiverWater ? Blocks.SAND.defaultBlockState() : Blocks.STONE.defaultBlockState();
        } else if (y == highestY) {
            return (hasRiverWater || hasRiverWall) ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
        } else if (hasRiverWater && y <= riverSurfaceY) {
            if (riverSurfaceY - highestY >= WATERFALL_DROP_THRESHOLD && y < riverSurfaceY) {
                return Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, FALLING_WATER_LEVEL);
            }
            return Blocks.WATER.defaultBlockState();
        } else if (hasRiverWall && y < riverWallSurfaceY) {
            return Blocks.SAND.defaultBlockState();
        } else {
            return Blocks.AIR.defaultBlockState();
        }
    }

    private BlockState oceanBlockStateAtY(final int highestY, final int y) {
        if (y < highestY - SURFACE_LAYER_THICKNESS) {
            return Blocks.STONE.defaultBlockState();
        } else if (y <= highestY) {
            return Blocks.SAND.defaultBlockState();
        } else if (y <= this.settings.seaLevel()) {
            return Blocks.WATER.defaultBlockState();
        } else {
            return Blocks.AIR.defaultBlockState();
        }
    }
}
