package net.azisaba.vanilife.server;

import io.papermc.paper.adventure.PaperAdventure;
import net.azisaba.vanilife.Vanilife;
import net.azisaba.vanilife.islands.IslandDefaults;
import net.azisaba.vanilife.server.islands.IslandsChunkGenerator;
import net.azisaba.vanilife.server.islands.IslandsGeneratorSettings;
import net.azisaba.vanilife.server.world.ResourceChunkGenerator;
import net.azisaba.vanilife.server.world.ResourceLayer;
import net.azisaba.vanilife.server.world.ResourceLayout;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionDefaults;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import org.jspecify.annotations.NullMarked;

import java.util.List;

@NullMarked
public final class VanilifeLevelStems {
    public static final ResourceKey<LevelStem> MAIN = ResourceKey.create(Registries.LEVEL_STEM, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "2026/spring"));
    public static final ResourceKey<LevelStem> ISLANDS = ResourceKey.create(Registries.LEVEL_STEM, PaperAdventure.asVanilla(IslandDefaults.WORLD_KEY));

    public static void bootstrap(final WritableRegistry<LevelStem> writable, final RegistryOps.RegistryInfoLookup lookup) {
        writable.register(VanilifeLevelStems.MAIN, VanilifeLevelStems.aetheria(lookup), RegistrationInfo.BUILT_IN);
        writable.register(VanilifeLevelStems.ISLANDS, VanilifeLevelStems.islands(lookup), RegistrationInfo.BUILT_IN);
    }

    private static LevelStem aetheria(final RegistryOps.RegistryInfoLookup lookup) {
        final Holder<DimensionType> dimensionType = lookup.lookup(Registries.DIMENSION_TYPE)
                .orElseThrow()
                .getter()
                .getOrThrow(VanilifeDimensionTypes.AETHERIA);
        final ResourceChunkGenerator generator = new ResourceChunkGenerator(
                new ResourceLayout(
                        DimensionDefaults.OVERWORLD_MIN_Y - DimensionDefaults.NETHER_GENERATION_HEIGHT,
                        List.of(
                                ResourceLayer.Type.nether(lookup),
                                ResourceLayer.Type.overworld(lookup),
                                ResourceLayer.Type.end(lookup)
                        )
                )
        );
        return new LevelStem(dimensionType, generator);
    }

    private static LevelStem islands(final RegistryOps.RegistryInfoLookup lookup) {
        final Holder<DimensionType> dimensionType = lookup.lookup(Registries.DIMENSION_TYPE)
                .orElseThrow()
                .getter()
                .getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        final Holder<Biome> biome = lookup.lookup(Registries.BIOME)
                .orElseThrow()
                .getter()
                .getOrThrow(Biomes.FLOWER_FOREST);
        final BiomeSource biomeSource = new FixedBiomeSource(biome);
        final IslandsChunkGenerator generator = new IslandsChunkGenerator(
                new IslandsGeneratorSettings(
                        IslandDefaults.SEA_LEVEL,
                        36,
                        IslandDefaults.SEA_LEVEL + 1,
                        IslandDefaults.HEIGHT,
                        12
                ),
                biomeSource
        );
        return new LevelStem(dimensionType, generator);
    }
}
