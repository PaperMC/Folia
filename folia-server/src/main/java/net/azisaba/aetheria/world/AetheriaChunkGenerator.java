package net.azisaba.aetheria.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@NullMarked
public class AetheriaChunkGenerator extends ChunkGenerator {
    private final AetheriaLayout layout;

    private final AetheriaRandomStateSource randomStateSource = new AetheriaRandomStateSource();

    public AetheriaChunkGenerator(final AetheriaLayout layout) {
        super(new AetheriaBiomeSource(layout));
        this.layout = layout;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return MapCodec.unit(null);
    }

    @Override
    public int getMinY() {
        return this.layout.minY();
    }

    @Override
    public int getSeaLevel() {
        return 63;
    }

    @Override
    public int getGenDepth() {
        return this.layout.height();
    }

    @Override
    public int getBaseHeight(final int x, final int z, final Heightmap.Types type, final LevelHeightAccessor level, final RandomState random) {
        return 0;
    }

    @Override
    public NoiseColumn getBaseColumn(final int x, final int z, final LevelHeightAccessor height, final RandomState random) {
        final int minY = height.getMinY();
        final int maxY = height.getMaxY();

        final BlockState[] column = new BlockState[height.getHeight()];
        for (int i = 0; i < column.length; i++) {
            column[i] = Blocks.AIR.defaultBlockState();
        }

        for (final AetheriaLayer.Type layerType : this.layout) {
            final LevelHeightAccessor layerHeight = layerType.createHeightAccessor();
            final NoiseColumn layerColumn = layerType.generator().getBaseColumn(x, z, layerHeight, random);

            for (int layerY = layerType.minY(); layerY <= layerType.maxY(); layerY++) {
                final int blockY = this.layout.toBlockY(layerType, layerY);
                if (blockY < minY || blockY > maxY) {
                    continue;
                }
                column[blockY - minY] = layerColumn.getBlock(layerY);
            }
        }

        return new NoiseColumn(minY, column);
    }

    @Override
    public void applyCarvers(final WorldGenRegion region, final long seed, final RandomState random, final BiomeManager biomeManager, final StructureManager structureManager, final ChunkAccess chunk) {
        final HolderGetter<NormalNoise.NoiseParameters> noiseParametersGetter = region.registryAccess().lookupOrThrow(Registries.NOISE);

        for (final AetheriaLayer.Type layerType : this.layout) {
            final ChunkGenerator layerGenerator = layerType.generator();
            final AetheriaLayer layerChunk = AetheriaLayer.copy(chunk, region.getMinecraftWorld(), this.layout, layerType);
            layerGenerator.applyCarvers(
                    region,
                    seed,
                    Objects.requireNonNullElse(
                            this.randomStateSource.getOrCreate(seed, layerType, noiseParametersGetter),
                            random
                    ),
                    biomeManager.withLayeredSource(this.layout, layerType),
                    structureManager,
                    layerChunk
            );
            layerChunk.mergeInto(chunk, false);
        }
    }

    @Override
    public void buildSurface(final WorldGenRegion region, final StructureManager structureManager, final RandomState random, final ChunkAccess chunk) {
        final Registry<Biome> biomes = region.registryAccess().lookupOrThrow(Registries.BIOME);
        final Registry<NormalNoise.NoiseParameters> noiseParameters = region.registryAccess().lookupOrThrow(Registries.NOISE);

        final Blender blender = Blender.of(region);

        for (final AetheriaLayer.Type layerType : this.layout) {
            final ChunkGenerator layerGenerator = layerType.generator();

            final AetheriaLayer layerChunk = AetheriaLayer.copy(chunk, region.getMinecraftWorld(), this.layout, layerType);
            final BiomeManager layerBiomeManager = region.getBiomeManager().withLayeredSource(this.layout, layerType);
            final RandomState layerRandomState = Objects.requireNonNullElse(
                    this.randomStateSource.getOrCreate(region.getSeed(), layerType, noiseParameters),
                    random
            );

            if (layerGenerator instanceof NoiseBasedChunkGenerator noiseBasedGenerator) {
                noiseBasedGenerator.buildSurface(
                        layerChunk,
                        new WorldGenerationContext(noiseBasedGenerator, region, region.getMinecraftWorld()),
                        layerRandomState,
                        structureManager,
                        layerBiomeManager,
                        biomes,
                        blender
                );
                layerChunk.mergeInto(chunk, true);
            } else {
                layerGenerator.buildSurface(region, structureManager, layerRandomState, layerChunk);
            }
        }
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(final Blender blender, final RandomState randomState, final StructureManager structureManager, final ChunkAccess chunk) {
        return CompletableFuture.supplyAsync(() -> {
            final ServerLevel level = structureManager.level.getMinecraftWorld();
            final long seed = level.getMinecraftWorld().getSeed();
            final HolderGetter<NormalNoise.NoiseParameters> noiseParametersGetter = structureManager.registryAccess().lookupOrThrow(Registries.NOISE);

            for (final AetheriaLayer.Type layerType : this.layout) {
                final ChunkGenerator layerGenerator = layerType.generator();
                final AetheriaLayer layerChunk = AetheriaLayer.empty(chunk.getPos(), level, this.layout, layerType);

                layerGenerator.fillFromNoise(
                        blender,
                        Objects.requireNonNullElse(
                                this.randomStateSource.getOrCreate(seed, layerType, noiseParametersGetter),
                                randomState
                        ),
                        structureManager,
                        layerChunk
                ).join();
                layerChunk.mergeInto(chunk, false);
            }
            return chunk;
        });
    }

    @Override
    public void spawnOriginalMobs(final WorldGenRegion region) {
        for (final AetheriaLayer.Type layerType : this.layout) {
            final ChunkGenerator layerGenerator = layerType.generator();
            layerGenerator.spawnOriginalMobs(region);
        }
    }

    @Override
    public void addDebugScreenInfo(final List<String> info, final RandomState random, final BlockPos pos) {
    }
}
