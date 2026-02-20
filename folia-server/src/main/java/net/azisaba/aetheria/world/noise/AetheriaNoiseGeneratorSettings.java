package net.azisaba.aetheria.world.noise;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.SurfaceRuleData;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.NullMarked;

import java.util.List;

@NullMarked
public final class AetheriaNoiseGeneratorSettings {
    public static NoiseGeneratorSettings overworld(final RegistryOps.RegistryInfoLookup lookup) {
        final HolderGetter<DensityFunction> densityFunctions = lookup.lookup(Registries.DENSITY_FUNCTION)
                .orElseThrow()
                .getter();
        final HolderGetter<NormalNoise.NoiseParameters> noiseParameters = lookup.lookup(Registries.NOISE)
                .orElseThrow()
                .getter();
        return new NoiseGeneratorSettings(
                NoiseSettings.OVERWORLD_NOISE_SETTINGS,
                Blocks.STONE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
                NoiseRouterData.overworld(densityFunctions, noiseParameters, false, false),
                SurfaceRuleData.overworldLike(true, false, false),
                new OverworldBiomeBuilder().spawnTarget(),
                63,
                false,
                true,
                true,
                false
        );
    }

    public static NoiseGeneratorSettings nether(final RegistryOps.RegistryInfoLookup lookup) {
        final HolderGetter<DensityFunction> densityFunctions = lookup.lookup(Registries.DENSITY_FUNCTION)
                .orElseThrow()
                .getter();
        final HolderGetter<NormalNoise.NoiseParameters> noiseParameters = lookup.lookup(Registries.NOISE)
                .orElseThrow()
                .getter();
        return new NoiseGeneratorSettings(
                NoiseSettings.NETHER_NOISE_SETTINGS,
                Blocks.NETHERRACK.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(),
                NoiseRouterData.nether(densityFunctions, noiseParameters),
                SurfaceRuleData.nether(false, true),
                List.of(),
                32,
                false,
                false,
                false,
                true
        );
    }
}
