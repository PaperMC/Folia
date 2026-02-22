package net.azisaba.aetheria.islands;

import com.mojang.serialization.MapCodec;
import de.articdive.jnoise.core.api.functions.Interpolation;
import de.articdive.jnoise.generators.noise_parameters.fade_functions.FadeFunction;
import de.articdive.jnoise.modules.octavation.fractal_functions.FractalFunction;
import de.articdive.jnoise.pipeline.JNoise;
import net.azisaba.aetheria.islands.noise.IslandNoise;
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
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.dimension.DimensionDefaults;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@NullMarked
public class IslandsChunkGenerator extends ChunkGenerator {
    private static final int DEEP_OCEAN_VARIATION = 2;
    private static final int SURFACE_LAYER_THICKNESS = 3;

    private final IslandsGeneratorSettings settings;

    private final Map<Long, IslandNoise> islandNoiseCache = new ConcurrentHashMap<>();
    private final Map<Long, JNoise> deepOceanNoiseCache = new ConcurrentHashMap<>();

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
        final int highestY = this.resolveHighestY(0L, x, z);
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
        final int highestY = this.resolveHighestY(0L, x, z);
        final int minY = height.getMinY();
        final int maxY = height.getMaxY();
        final BlockState[] column = new BlockState[height.getHeight()];

        for (int y = minY; y < maxY; y++) {
            column[y - minY] = this.blockStateAtY(highestY, y);
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

        for (int dx = 0; dx < 16; dx++) {
            final int blockX = chunkPos.getMinBlockX() + dx;
            for (int dz = 0; dz < 16; dz++) {
                final int blockZ = chunkPos.getMinBlockZ() + dz;
                final int highestY = this.resolveHighestY(levelSeed, blockX, blockZ);

                pos.set(blockX, 0, blockZ);
                for (int y = chunk.getMinY(); y < this.settings.airTopY(); y++) {
                    pos.setY(y);
                    chunk.setBlockState(pos, this.blockStateAtY(highestY, y), Block.UPDATE_NONE);
                }
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

        noiseKey ^= ((long) islandPos.gridX() * 0x9E3779B97F4A7C15L);
        noiseKey = Long.rotateLeft(noiseKey, 27);

        noiseKey ^= ((long) islandPos.gridZ() * 0xC2B2AE3D27D4EB4FL);
        noiseKey = Long.rotateLeft(noiseKey, 31);

        return this.islandNoiseCache.computeIfAbsent(noiseKey, k -> IslandNoise.createDefault(islandPos.computeSeed(levelSeed)));
    }

    private JNoise getDeepOceanNoise(final long levelSeed) {
        return this.deepOceanNoiseCache.computeIfAbsent(levelSeed, seed -> JNoise.newBuilder()
                .perlin(seed ^ 0xD1342543DE82EF95L, Interpolation.COSINE, FadeFunction.CUBIC_POLY)
                .scale(1.0 / 16.0)
                .octavate(3, 0.55, 2.1, FractalFunction.FBM, false)
                .build());
    }

    private int resolveHighestY(final long levelSeed, final int blockX, final int blockZ) {
        final IslandPos islandPos = IslandPos.fromBlockPos(blockX, blockZ);
        final IslandNoise islandNoise = this.getIslandNoise(levelSeed, islandPos);
        final double localX = blockX - islandPos.centerBlockX();
        final double localZ = blockZ - islandPos.centerBlockZ();
        final IslandNoise.Sample islandSample = islandNoise.sample(localX, localZ, this.settings);
        final int baseHighestY = islandSample.highestY();

        final int deepBaseFloorY = this.settings.seaLevel() - this.settings.seaDepth();
        if (baseHighestY > deepBaseFloorY) {
            return baseHighestY;
        }

        final int bump = (int) Math.round(this.getDeepOceanNoise(levelSeed).evaluateNoise(blockX, blockZ) * DEEP_OCEAN_VARIATION);
        return deepBaseFloorY + bump;
    }

    private BlockState blockStateAtY(final int highestY, final int y) {
        return highestY > this.settings.seaLevel() ? this.landBlockStateAtY(highestY, y) : this.oceanBlockStateAtY(highestY, y);
    }

    private BlockState landBlockStateAtY(final int highestY, final int y) {
        if (y < highestY - SURFACE_LAYER_THICKNESS) {
            return Blocks.STONE.defaultBlockState();
        } else if (y < highestY) {
            return Blocks.STONE.defaultBlockState();
        } else if (y == highestY) {
            return Blocks.GRASS_BLOCK.defaultBlockState();
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
