package net.azisaba.aetheria;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class AetheriaLevelStems {
    public static final ResourceKey<LevelStem> MAIN = ResourceKey.create(Registries.LEVEL_STEM, Identifier.withAetheriaNamespace("2026/spring"));

    public static void bootstrap(final WritableRegistry<LevelStem> writable, final RegistryOps.RegistryInfoLookup lookup) {
        final Holder<DimensionType> dimensionType = lookup.lookup(Registries.DIMENSION_TYPE)
                .orElseThrow()
                .getter()
                .getOrThrow(AetheriaDimensionTypes.AETHERIA);
        final Holder.Reference<Biome> biome = lookup.lookup(Registries.BIOME)
                .orElseThrow()
                .getter()
                .getOrThrow(Biomes.PLAINS);
        writable.register(
                AetheriaLevelStems.MAIN,
                new LevelStem(
                        dimensionType,
                        new DebugLevelSource(biome)
                ),
                RegistrationInfo.BUILT_IN
        );
    }
}
