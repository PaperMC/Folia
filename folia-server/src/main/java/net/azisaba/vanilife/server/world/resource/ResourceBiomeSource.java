package net.azisaba.vanilife.server.world.resource;

import com.mojang.serialization.MapCodec;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@NullMarked
public class ResourceBiomeSource extends BiomeSource {
    private final ResourceLayout layout;
    private final ResourceRandomStateProvider randomStateSource = new ResourceRandomStateProvider();

    public ResourceBiomeSource(final ResourceLayout layout) {
        this.layout = layout;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return MapCodec.unit(this);
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return this.layout.stream().flatMap(t -> t.generator().getBiomeSource().possibleBiomes().stream());
    }

    @Override
    public Holder<Biome> getNoiseBiome(final int x, final int y, final int z, final Climate.Sampler sampler) {
        final int blockY = QuartPos.toBlock(y);
        final ResourceLayer.Type layerType = this.layout.getLayerTypeAt(blockY);
        if (layerType == null) {
            throw new IllegalStateException("Y is outside the bounds of the layout: " + y);
        }

        final BiomeSource layerBiomeSource = layerType.generator().getBiomeSource();
        final int layerY = this.layout.toLayerY(layerType, blockY);
        return layerBiomeSource.getNoiseBiome(x, QuartPos.fromBlock(layerY), z, sampler);
    }

    @Override
    public @Nullable Pair<BlockPos, Holder<Biome>> findClosestBiome3d(
            final BlockPos pos, final int radius, final int horizontalStep, final int verticalStep, final Predicate<Holder<Biome>> biomePredicate, final Climate.Sampler sampler, final LevelReader level
    ) {
        final Set<Holder<Biome>> targetBiomes = this.possibleBiomes().stream().filter(biomePredicate).collect(Collectors.toUnmodifiableSet());
        if (targetBiomes.isEmpty()) {
            return null;
        }

        final ServerLevel serverLevel = level instanceof ServerLevel casted ? casted : null;
        final long seed = serverLevel != null ? serverLevel.getSeed() : 0L;
        final HolderGetter<NormalNoise.NoiseParameters> noiseParameters = serverLevel != null ? serverLevel.registryAccess().lookupOrThrow(Registries.NOISE) : null;
        final int[] ys = Mth.outFromOrigin(pos.getY(), level.getMinY() + 1, level.getMaxY() + 1, verticalStep).toArray();
        final int radiusSteps = Math.floorDiv(radius, horizontalStep);

        for (final BlockPos.MutableBlockPos offset : BlockPos.spiralAround(BlockPos.ZERO, radiusSteps, Direction.EAST, Direction.SOUTH)) {
            final int blockX = pos.getX() + offset.getX() * horizontalStep;
            final int blockZ = pos.getZ() + offset.getZ() * horizontalStep;
            final int quartX = QuartPos.fromBlock(blockX);
            final int quartZ = QuartPos.fromBlock(blockZ);

            for (final int blockY : ys) {
                final ResourceLayer.Type layerType = this.layout.getLayerTypeAt(blockY);
                if (layerType == null) {
                    continue;
                }

                final int layerY = this.layout.toLayerY(layerType, blockY);
                final int quartY = QuartPos.fromBlock(layerY);
                final Climate.Sampler layerSampler;
                if (noiseParameters != null) {
                    final var layerRandomState = this.randomStateSource.getOrCreate(seed, layerType, noiseParameters);
                    layerSampler = layerRandomState != null ? layerRandomState.sampler() : sampler;
                } else {
                    layerSampler = sampler;
                }
                final Holder<Biome> biome = layerType.generator().getBiomeSource().getNoiseBiome(quartX, quartY, quartZ, layerSampler);
                if (targetBiomes.contains(biome)) {
                    return Pair.of(new BlockPos(blockX, blockY, blockZ), biome);
                }
            }
        }

        return null;
    }
}
