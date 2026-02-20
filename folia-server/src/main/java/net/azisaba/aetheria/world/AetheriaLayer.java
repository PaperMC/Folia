package net.azisaba.aetheria.world;

import net.azisaba.aetheria.AetheriaBiomes;
import net.azisaba.aetheria.world.height.HeightmapSet;
import net.azisaba.aetheria.world.noise.AetheriaNoiseGeneratorSettings;
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
public class AetheriaLayer extends ProtoChunk {
    public static AetheriaLayer empty(final ChunkPos pos, final ServerLevel level, final AetheriaLayout layout, final AetheriaLayer.Type type) {
        return new AetheriaLayer(pos, level, layout, type);
    }

    public static AetheriaLayer copy(final ChunkAccess chunk, final ServerLevel level, final AetheriaLayout layout, final AetheriaLayer.Type type) {
        final AetheriaLayer copy = new AetheriaLayer(chunk.getPos(), level, layout, type);

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

    private final AetheriaLayout layout;
    private final AetheriaLayer.Type layerType;

    private AetheriaLayer(final ChunkPos pos, final ServerLevel level, final AetheriaLayout layout, final AetheriaLayer.Type type) {
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
    public record Type(
            int height,
            HeightmapSet heightmapSet,
            ChunkGenerator generator
    ) {
        public static AetheriaLayer.Type overworld(final RegistryOps.RegistryInfoLookup lookup) {
            final Holder<MultiNoiseBiomeSourceParameterList> parameterList = lookup.lookup(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .orElseThrow()
                    .getter()
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
            final Holder<NoiseGeneratorSettings> noiseGeneratorSettings = Holder.direct(AetheriaNoiseGeneratorSettings.overworld(lookup));
            return new AetheriaLayer.Type(
                    DimensionDefaults.OVERWORLD_GENERATION_HEIGHT,
                    HeightmapSet.AETHERIA_OVERWORLD,
                    new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(parameterList), noiseGeneratorSettings)
            );
        }

        public static AetheriaLayer.Type nether(final RegistryOps.RegistryInfoLookup lookup) {
            final Holder<MultiNoiseBiomeSourceParameterList> parameterList = lookup.lookup(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .orElseThrow()
                    .getter()
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER);
            final Holder<NoiseGeneratorSettings> noiseGeneratorSettings = Holder.direct(AetheriaNoiseGeneratorSettings.nether(lookup));
            return new AetheriaLayer.Type(
                    DimensionDefaults.NETHER_GENERATION_HEIGHT,
                    HeightmapSet.AETHERIA_NETHER,
                    new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(parameterList), noiseGeneratorSettings)
            );
        }

        public static AetheriaLayer.Type end(final RegistryOps.RegistryInfoLookup lookup) {
            final HolderGetter<Biome> biomes = lookup.lookup(Registries.BIOME)
                    .orElseThrow()
                    .getter();
            final Holder.Reference<NoiseGeneratorSettings> noiseGeneratorSettings = lookup.lookup(Registries.NOISE_SETTINGS)
                    .orElseThrow()
                    .getter()
                    .getOrThrow(NoiseGeneratorSettings.END);
            return new AetheriaLayer.Type(
                    DimensionDefaults.END_GENERATION_HEIGHT,
                    HeightmapSet.AETHERIA_END,
                    new NoiseBasedChunkGenerator(
                            new TheEndBiomeSource(
                                    biomes.getOrThrow(AetheriaBiomes.THE_END),
                                    biomes.getOrThrow(AetheriaBiomes.END_HIGHLANDS),
                                    biomes.getOrThrow(AetheriaBiomes.END_MIDLANDS),
                                    biomes.getOrThrow(AetheriaBiomes.SMALL_END_ISLANDS),
                                    biomes.getOrThrow(AetheriaBiomes.END_BARRENS)
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
