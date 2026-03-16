package net.azisaba.vanilife.server.world.resource;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import java.util.List;
import net.azisaba.vanilife.server.VanilifeBiomes;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.*;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class OverworldLayerBiomeSourceBuilder {
    private final Climate.Parameter FULL_RANGE = Climate.Parameter.span(-1.0F, 1.0F);
    private static final Climate.Parameter COLD_TEMPERATURE = Climate.Parameter.span(-1.0F, -0.8F);
    private static final Climate.Parameter SNOWY_HUMIDITY = Climate.Parameter.span(0.2F, 1.0F);

    public BiomeSource build(final RegistryOps.RegistryInfoLookup lookup) {
        final HolderGetter<Biome> biomes = lookup.lookup(Registries.BIOME).orElseThrow().getter();
        final ImmutableList.Builder<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> builder = ImmutableList.builder();

        addVanillaOverworldBiomes(builder);
        addUndergroundBiomes(builder);

        return MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(builder.build().stream().<Pair<Climate.ParameterPoint, Holder<Biome>>>map(pair -> pair.mapSecond(biomes::getOrThrow)).toList()));
    }

    private void addVanillaOverworldBiomes(final ImmutableList.Builder<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> builder) {
        final List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> vanillaBiomes = MultiNoiseBiomeSourceParameterList.knownPresets()
                .get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD)
                .values();
        for (final Pair<Climate.ParameterPoint, ResourceKey<Biome>> pair : vanillaBiomes) {
            builder.add(pair);
        }
    }

    private void addUndergroundBiomes(final ImmutableList.Builder<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> builder) {
        addUndergroundBiome(builder, COLD_TEMPERATURE, SNOWY_HUMIDITY, FULL_RANGE, FULL_RANGE, FULL_RANGE, 0.0F, VanilifeBiomes.GLACIAL_CAVE);
    }

    private void addUndergroundBiome(final ImmutableList.Builder<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> builder, final Climate.Parameter temperature, final Climate.Parameter humidity, final Climate.Parameter continentalness, final Climate.Parameter erosion, final Climate.Parameter depth, final float weirdness, final ResourceKey<Biome> biome) {
        builder.add(Pair.of(Climate.parameters(temperature, humidity, continentalness, erosion, Climate.Parameter.span(0.2F, 0.9F), depth, weirdness), biome));
    }
}
