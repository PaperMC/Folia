package net.azisaba.aetheria;

import net.azisaba.aetheria.world.AetheriaChunkGenerator;
import net.azisaba.aetheria.world.AetheriaLayer;
import net.azisaba.aetheria.world.AetheriaLayout;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.DimensionDefaults;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import org.jspecify.annotations.NullMarked;

import java.util.List;

@NullMarked
public final class AetheriaLevelStems {
    public static final ResourceKey<LevelStem> MAIN = ResourceKey.create(Registries.LEVEL_STEM, Identifier.withAetheriaNamespace("2026/spring"));

    public static void bootstrap(final WritableRegistry<LevelStem> writable, final RegistryOps.RegistryInfoLookup lookup) {
        final Holder<DimensionType> dimensionType = lookup.lookup(Registries.DIMENSION_TYPE)
                .orElseThrow()
                .getter()
                .getOrThrow(AetheriaDimensionTypes.AETHERIA);

        final AetheriaChunkGenerator generator = new AetheriaChunkGenerator(
                new AetheriaLayout(
                        DimensionDefaults.OVERWORLD_MIN_Y - DimensionDefaults.NETHER_GENERATION_HEIGHT,
                        List.of(
                                AetheriaLayer.Type.nether(lookup),
                                AetheriaLayer.Type.overworld(lookup),
                                AetheriaLayer.Type.end(lookup)
                        )
                )
        );

        writable.register(AetheriaLevelStems.MAIN, new LevelStem(dimensionType, generator), RegistrationInfo.BUILT_IN);
    }
}
