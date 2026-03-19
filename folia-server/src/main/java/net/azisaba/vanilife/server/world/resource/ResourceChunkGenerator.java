package net.azisaba.vanilife.server.world.resource;

import com.mojang.serialization.MapCodec;
import net.azisaba.vanilife.server.world.height.HeightContext;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.*;
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
public class ResourceChunkGenerator extends ChunkGenerator {
    private final ResourceLayout layout;

    private final ResourceRandomStateProvider randomStateSource = new ResourceRandomStateProvider();

    public ResourceChunkGenerator(final ResourceLayout layout) {
        super(new ResourceBiomeSource(layout));
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

        for (final ResourceLayer.Type layerType : this.layout) {
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

        for (final ResourceLayer.Type layerType : this.layout) {
            final ChunkGenerator layerGenerator = layerType.generator();
            final ResourceLayer layerChunk = ResourceLayer.copy(chunk, region.getMinecraftWorld(), this.layout, layerType);
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
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        for (final ResourceLayer.Type layerType : this.layout) {
            final ChunkGenerator layerGenerator = layerType.generator();
            final HeightContext heightmapSet = this.layout.createHeightContext(layerType);
            layerGenerator.applyBiomeDecoration(level, chunk, structureManager, true, heightmapSet);
        }
    }

    @Override
    public void buildSurface(final WorldGenRegion region, final StructureManager structureManager, final RandomState random, final ChunkAccess chunk) {
        final Registry<Biome> biomes = region.registryAccess().lookupOrThrow(Registries.BIOME);
        final Registry<NormalNoise.NoiseParameters> noiseParameters = region.registryAccess().lookupOrThrow(Registries.NOISE);

        final Blender blender = Blender.of(region);

        for (final ResourceLayer.Type layerType : this.layout) {
            final ChunkGenerator layerGenerator = layerType.generator();

            final ResourceLayer layerChunk = ResourceLayer.copy(chunk, region.getMinecraftWorld(), this.layout, layerType);
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

            for (final ResourceLayer.Type layerType : this.layout) {
                final ChunkGenerator layerGenerator = layerType.generator();
                final ResourceLayer layerChunk = ResourceLayer.empty(chunk.getPos(), level, this.layout, layerType);

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
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
        return CompletableFuture.supplyAsync(() -> {
            final ChunkPos pos = chunk.getPos();
            final int quartMinX = QuartPos.fromBlock(pos.getMinBlockX());
            final int quartMinY = QuartPos.fromBlock(chunk.getMinY());
            final int quartMinZ = QuartPos.fromBlock(pos.getMinBlockZ());
            final int quartHeight = QuartPos.fromBlock(chunk.getHeight());

            for (int qx = 0; qx < 4; qx++) {
                for (int qz = 0; qz < 4; qz++) {
                    final int quartX = quartMinX + qx;
                    final int quartZ = quartMinZ + qz;
                    for (int qyOffset = 0; qyOffset < quartHeight; qyOffset++) {
                        final int quartY = quartMinY + qyOffset;
                        final int blockY = QuartPos.toBlock(quartY);

                        final ResourceLayer.Type layerType = this.layout.getLayerTypeAt(blockY);
                        if (layerType == null) {
                            continue;
                        }

                        final int layerY = this.layout.toLayerY(layerType, blockY);
                        final int layerQuartY = QuartPos.fromBlock(layerY);
                        final RandomState layerRandomState = Objects.requireNonNullElse(
                                this.randomStateSource.getOrCreate(structureManager.level.getMinecraftWorld().getSeed(), layerType, structureManager.registryAccess().lookupOrThrow(Registries.NOISE)),
                                randomState
                        );
                        final Holder<Biome> biome = layerType.generator().getBiomeSource().getNoiseBiome(quartX, layerQuartY, quartZ, layerRandomState.sampler());
                        chunk.setBiome(quartX, quartY, quartZ, biome);
                    }
                }
            }

            return chunk;
        }, Runnable::run);
    }

    @Override
    public void spawnOriginalMobs(final WorldGenRegion region) {
        for (final ResourceLayer.Type layerType : this.layout) {
            final ChunkGenerator layerGenerator = layerType.generator();
            layerGenerator.spawnOriginalMobs(region);
        }
    }

    @Override
    public void addDebugScreenInfo(final List<String> info, final RandomState random, final BlockPos pos) {
    }
}
