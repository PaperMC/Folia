package net.azisaba.vanilife.server.world;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import net.azisaba.vanilife.server.VanilifeBiomes;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class VanilifeBiomeSources {
    private VanilifeBiomeSources() {
    }

    public static BiomeSource aetheriaOverworld(final RegistryOps.RegistryInfoLookup lookup) {
        final HolderGetter<Biome> biomes = lookup.lookup(Registries.BIOME)
                .orElseThrow()
                .getter();
        final ImmutableList.Builder<Pair<Climate.ParameterPoint, Holder<Biome>>> builder = ImmutableList.builder();

        for (final Pair<Climate.ParameterPoint, ResourceKey<Biome>> pair : MultiNoiseBiomeSourceParameterList.knownPresets()
                .get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD)
                .values()) {
            builder.add(pair.mapSecond(biomes::getOrThrow));
        }
        builder.add(
                Pair.of(
                        Climate.parameters(
                                Climate.Parameter.span(-1.0F, -0.45F),
                                Climate.Parameter.span(-1.0F, 0.3F),
                                Climate.Parameter.span(-1.0F, 1.0F),
                                Climate.Parameter.span(-1.0F, 1.0F),
                                Climate.Parameter.span(0.2F, 0.9F),
                                Climate.Parameter.span(-1.0F, 1.0F),
                                0.0F
                        ),
                        biomes.getOrThrow(VanilifeBiomes.GLACIAL_CAVE)
                )
        );

        return MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(builder.build()));
    }
}
