package net.azisaba.vanilife.server.world.resource;

import net.azisaba.vanilife.server.VanilifeBiomes;
import net.azisaba.vanilife.server.world.height.HeightmapSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.DimensionDefaults;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class ResourceLayer extends ProtoChunk {
    public static ResourceLayer empty(final ChunkPos pos, final ServerLevel level, final ResourceLayout layout, final ResourceLayer.Type type) {
        return new ResourceLayer(pos, level, layout, type);
    }

    public static ResourceLayer copy(final ChunkAccess chunk, final ServerLevel level, final ResourceLayout layout, final ResourceLayer.Type type) {
        final ResourceLayer copy = new ResourceLayer(chunk.getPos(), level, layout, type);

        final BlockPos.MutableBlockPos fromPos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos toPos = new BlockPos.MutableBlockPos();

        final int minBlockX = chunk.getPos().getMinBlockX();
        final int minBlockZ = chunk.getPos().getMinBlockZ();

        for (int chunkX = 0; chunkX < 16; chunkX++) {
            final int blockX = minBlockX + chunkX;
            for (int chunkZ = 0; chunkZ < 16; chunkZ++) {
                final int blockZ = minBlockZ + chunkZ;
                for (int layerY = type.minY(); layerY < type.minY() + type.height(); layerY++) {
                    final int blockY = layout.toBlockY(type, layerY);
                    fromPos.set(blockX, blockY, blockZ);

                    toPos.set(blockX, layerY, blockZ);

                    final BlockState blockState = chunk.getBlockState(fromPos);
                    copy.setBlockState(toPos, blockState, Block.UPDATE_NONE);
                }
            }
        }

        return copy;
    }

    private final ResourceLayout layout;
    private final ResourceLayer.Type layerType;

    private ResourceLayer(final ChunkPos pos, final ServerLevel level, final ResourceLayout layout, final ResourceLayer.Type type) {
        super(pos, UpgradeData.EMPTY, type.createHeightAccessor(), level.palettedContainerFactory(), null);
        this.layout = layout;
        this.layerType = type;
    }

    public void mergeInto(final ChunkAccess chunk, final boolean skipAir) {
        final Heightmap[] heightmaps = this.getOrCreateHeightmaps(chunk, this.layerType.heightmapSet());

        final BlockPos.MutableBlockPos fromPos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos toPos = new BlockPos.MutableBlockPos();

        final int minBlockX = chunk.getPos().getMinBlockX();
        final int minBlockZ = chunk.getPos().getMinBlockZ();

        for (int chunkX = 0; chunkX < 16; chunkX++) {
            final int blockX = minBlockX + chunkX;
            for (int chunkZ = 0; chunkZ < 16; chunkZ++) {
                final int blockZ = minBlockZ + chunkZ;
                for (int layerY = this.layerType.minY(); layerY <= this.layerType.maxY(); layerY++) {
                    fromPos.set(blockX, layerY, blockZ);

                    final BlockState blockState = this.getBlockState(fromPos);
                    if (skipAir && blockState.isAir()) {
                        continue;
                    }

                    final int blockY = this.layout.toBlockY(this.layerType, layerY);
                    toPos.set(blockX, blockY, blockZ);

                    chunk.setBlockState(toPos, blockState, Block.UPDATE_NONE);

                    if (!blockState.isAir()) {
                        for (final Heightmap heightmap : heightmaps) {
                            heightmap.update(chunkX, blockY, chunkZ, blockState);
                        }
                    }
                }
            }
        }
    }

    private Heightmap[] getOrCreateHeightmaps(final ChunkAccess chunk, final HeightmapSet heightmapSet) {
        return new Heightmap[]{
                chunk.getOrCreateHeightmapUnprimed(heightmapSet.worldSurfaceWg()),
                chunk.getOrCreateHeightmapUnprimed(heightmapSet.worldSurface()),
                chunk.getOrCreateHeightmapUnprimed(heightmapSet.oceanFloorWg()),
                chunk.getOrCreateHeightmapUnprimed(heightmapSet.oceanFloor()),
                chunk.getOrCreateHeightmapUnprimed(heightmapSet.motionBlocking()),
                chunk.getOrCreateHeightmapUnprimed(heightmapSet.motionBlockingNoLeaves())
        };
    }

    @NullMarked
    public record Type(int height, HeightmapSet heightmapSet, ChunkGenerator generator) {
        public static ResourceLayer.Type overworld(final RegistryOps.RegistryInfoLookup lookup) {
            final Holder<NoiseGeneratorSettings> noiseGeneratorSettings = Holder.direct(ResourceNoiseGeneratorSettings.overworld(lookup));
            return new ResourceLayer.Type(
                    DimensionDefaults.OVERWORLD_GENERATION_HEIGHT,
                    HeightmapSet.RESOURCE_OVERWORLD,
                    new NoiseBasedChunkGenerator(new OverworldLayerBiomeSourceBuilder().build(lookup), noiseGeneratorSettings)
            );
        }

        public static ResourceLayer.Type nether(final RegistryOps.RegistryInfoLookup lookup) {
            final Holder<MultiNoiseBiomeSourceParameterList> parameterList = lookup.lookup(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .orElseThrow()
                    .getter()
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER);
            final Holder<NoiseGeneratorSettings> noiseGeneratorSettings = Holder.direct(ResourceNoiseGeneratorSettings.nether(lookup));
            return new ResourceLayer.Type(
                    DimensionDefaults.NETHER_GENERATION_HEIGHT,
                    HeightmapSet.RESOURCE_NETHER,
                    new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(parameterList), noiseGeneratorSettings)
            );
        }

        public static ResourceLayer.Type end(final RegistryOps.RegistryInfoLookup lookup) {
            final HolderGetter<Biome> biomes = lookup.lookup(Registries.BIOME)
                    .orElseThrow()
                    .getter();
            final Holder.Reference<NoiseGeneratorSettings> noiseGeneratorSettings = lookup.lookup(Registries.NOISE_SETTINGS)
                    .orElseThrow()
                    .getter()
                    .getOrThrow(NoiseGeneratorSettings.END);
            return new ResourceLayer.Type(
                    DimensionDefaults.END_GENERATION_HEIGHT,
                    HeightmapSet.RESOURCE_END,
                    new NoiseBasedChunkGenerator(
                            new TheEndBiomeSource(
                                    biomes.getOrThrow(VanilifeBiomes.THE_END),
                                    biomes.getOrThrow(VanilifeBiomes.END_HIGHLANDS),
                                    biomes.getOrThrow(VanilifeBiomes.END_MIDLANDS),
                                    biomes.getOrThrow(VanilifeBiomes.SMALL_END_ISLANDS),
                                    biomes.getOrThrow(VanilifeBiomes.END_BARRENS)
                            ),
                            noiseGeneratorSettings
                    )
            );
        }

        public int minY() {
            return this.generator.getMinY();
        }

        public int maxY() {
            return this.minY() + this.height - 1;
        }

        public LevelHeightAccessor createHeightAccessor() {
            return LevelHeightAccessor.create(this.minY(), this.height);
        }
    }
}
