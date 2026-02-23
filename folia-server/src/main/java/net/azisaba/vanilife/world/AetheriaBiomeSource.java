package net.azisaba.vanilife.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.jspecify.annotations.NullMarked;

import java.util.stream.Stream;

@NullMarked
public class AetheriaBiomeSource extends BiomeSource {
    private final AetheriaLayout layout;

    public AetheriaBiomeSource(final AetheriaLayout layout) {
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
        final AetheriaLayer.Type layerType = this.layout.getLayerTypeAt(blockY);
        if (layerType == null) {
            throw new IllegalStateException("Y is outside the bounds of the layout: " + y);
        }

        final BiomeSource layerBiomeSource = layerType.generator().getBiomeSource();
        final int layerY = this.layout.toLayerY(layerType, blockY);
        return layerBiomeSource.getNoiseBiome(x, QuartPos.fromBlock(layerY), z, sampler);
    }
}
