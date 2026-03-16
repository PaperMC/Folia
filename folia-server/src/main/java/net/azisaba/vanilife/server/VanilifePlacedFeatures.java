package net.azisaba.vanilife.server;

import net.azisaba.vanilife.Vanilife;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.jspecify.annotations.NullMarked;

import java.util.List;

@NullMarked
public final class VanilifePlacedFeatures {
    public static final ResourceKey<PlacedFeature> CAVE_ICE_CLUSTER = ResourceKey.create(Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "cave_ice_cluster"));
    public static final ResourceKey<PlacedFeature> CAVE_ICE_PILLAR = ResourceKey.create(Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "cave_ice_pillar"));
    public static final ResourceKey<PlacedFeature> CAVE_SNOW_COVER = ResourceKey.create(Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "cave_snow_cover"));
    public static final ResourceKey<PlacedFeature> CAVE_WALL_FROST = ResourceKey.create(Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "cave_wall_frost"));

    private VanilifePlacedFeatures() {
    }

    public static void bootstrap(final WritableRegistry<PlacedFeature> writable, final RegistryOps.RegistryInfoLookup lookup) {
        final HolderGetter<ConfiguredFeature<?, ?>> configuredFeatures = lookup.lookup(Registries.CONFIGURED_FEATURE)
                .orElseThrow()
                .getter();

        writable.register(CAVE_ICE_CLUSTER, caveIceCluster(configuredFeatures), RegistrationInfo.BUILT_IN);
        writable.register(CAVE_ICE_PILLAR, caveIcePillar(configuredFeatures), RegistrationInfo.BUILT_IN);
        writable.register(CAVE_SNOW_COVER, caveSnowCover(configuredFeatures), RegistrationInfo.BUILT_IN);
        writable.register(CAVE_WALL_FROST, caveWallFrost(configuredFeatures), RegistrationInfo.BUILT_IN);
    }

    private static PlacedFeature caveIcePillar(final HolderGetter<ConfiguredFeature<?, ?>> configuredFeatures) {
        return new PlacedFeature(
                configuredFeatures.getOrThrow(VanilifeConfiguredFeatures.CAVE_ICE_PILLAR),
                List.of(
                        CountPlacement.of(18),
                        InSquarePlacement.spread(),
                        HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.top()),
                        BiomeFilter.biome()
                )
        );
    }

    private static PlacedFeature caveIceCluster(final HolderGetter<ConfiguredFeature<?, ?>> configuredFeatures) {
        return new PlacedFeature(
                configuredFeatures.getOrThrow(VanilifeConfiguredFeatures.CAVE_ICE_CLUSTER),
                List.of(
                        CountPlacement.of(14),
                        InSquarePlacement.spread(),
                        HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.top()),
                        BiomeFilter.biome()
                )
        );
    }

    private static PlacedFeature caveSnowCover(final HolderGetter<ConfiguredFeature<?, ?>> configuredFeatures) {
        return new PlacedFeature(
                configuredFeatures.getOrThrow(VanilifeConfiguredFeatures.CAVE_SNOW_COVER),
                List.of(
                        CountPlacement.of(96),
                        InSquarePlacement.spread(),
                        HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.top()),
                        BiomeFilter.biome()
                )
        );
    }

    private static PlacedFeature caveWallFrost(final HolderGetter<ConfiguredFeature<?, ?>> configuredFeatures) {
        return new PlacedFeature(
                configuredFeatures.getOrThrow(VanilifeConfiguredFeatures.CAVE_WALL_FROST),
                List.of(
                        CountPlacement.of(32),
                        InSquarePlacement.spread(),
                        HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.top()),
                        BiomeFilter.biome()
                )
        );
    }
}
