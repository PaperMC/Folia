package net.azisaba.vanilife.server;

import net.azisaba.vanilife.Vanilife;
import net.azisaba.vanilife.server.world.feature.CaveIceClusterFeature;
import net.azisaba.vanilife.server.world.feature.CaveSnowCoverFeature;
import net.azisaba.vanilife.server.world.feature.CaveWallFrostFeature;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.WeightedStateProvider;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class VanilifeConfiguredFeatures {
    public static final ResourceKey<ConfiguredFeature<?, ?>> CAVE_ICE_CLUSTER = ResourceKey.create(Registries.CONFIGURED_FEATURE, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "cave_ice_cluster"));
    public static final ResourceKey<ConfiguredFeature<?, ?>> CAVE_ICE_PILLAR = ResourceKey.create(Registries.CONFIGURED_FEATURE, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "cave_ice_pillar"));
    public static final ResourceKey<ConfiguredFeature<?, ?>> CAVE_SNOW_COVER = ResourceKey.create(Registries.CONFIGURED_FEATURE, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "cave_snow_cover"));
    public static final ResourceKey<ConfiguredFeature<?, ?>> CAVE_WALL_FROST = ResourceKey.create(Registries.CONFIGURED_FEATURE, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "cave_wall_frost"));

    private VanilifeConfiguredFeatures() {
    }

    public static void bootstrap(final WritableRegistry<ConfiguredFeature<?, ?>> writable, final RegistryOps.RegistryInfoLookup lookup) {
        final HolderGetter<Feature<?>> features = lookup.lookup(Registries.FEATURE)
                .orElseThrow()
                .getter();

        writable.register(CAVE_ICE_CLUSTER, caveIceCluster(features), RegistrationInfo.BUILT_IN);
        writable.register(CAVE_ICE_PILLAR, caveIcePillar(features), RegistrationInfo.BUILT_IN);
        writable.register(CAVE_SNOW_COVER, caveSnowCover(features), RegistrationInfo.BUILT_IN);
        writable.register(CAVE_WALL_FROST, caveWallFrost(features), RegistrationInfo.BUILT_IN);
    }

    @SuppressWarnings("unchecked")
    private static ConfiguredFeature<?, ?> caveIceCluster(final HolderGetter<Feature<?>> features) {
        final Feature<CaveIceClusterFeature.Configuration> feature = (Feature<CaveIceClusterFeature.Configuration>) features.getOrThrow((ResourceKey) VanilifeFeatures.CAVE_ICE_CLUSTER).value();
        return new ConfiguredFeature<>(
                feature,
                new CaveIceClusterFeature.Configuration(
                        new WeightedStateProvider(
                                WeightedList.<BlockState>builder()
                                        .add(Blocks.PACKED_ICE.defaultBlockState(), 6)
                                        .add(Blocks.ICE.defaultBlockState(), 2)
                        ),
                        new WeightedStateProvider(
                                WeightedList.<BlockState>builder()
                                        .add(Blocks.BLUE_ICE.defaultBlockState(), 5)
                                        .add(Blocks.PACKED_ICE.defaultBlockState(), 1)
                        )
                )
        );
    }

    @SuppressWarnings("unchecked")
    private static ConfiguredFeature<?, ?> caveIcePillar(final HolderGetter<Feature<?>> features) {
        final Feature<NoneFeatureConfiguration> feature = (Feature<NoneFeatureConfiguration>) features.getOrThrow((ResourceKey) VanilifeFeatures.CAVE_ICE_PILLAR).value();
        return new ConfiguredFeature<>(feature, NoneFeatureConfiguration.INSTANCE);
    }

    @SuppressWarnings("unchecked")
    private static ConfiguredFeature<?, ?> caveSnowCover(final HolderGetter<Feature<?>> features) {
        final Feature<CaveSnowCoverFeature.Configuration> feature = (Feature<CaveSnowCoverFeature.Configuration>) features.getOrThrow((ResourceKey) VanilifeFeatures.CAVE_SNOW_COVER).value();
        return new ConfiguredFeature<>(
                feature,
                new CaveSnowCoverFeature.Configuration(
                        8,
                        8,
                        3,
                        0.125F,
                        8.0F,
                        2.0F,
                        1.0F,
                        2,
                        new WeightedStateProvider(
                                WeightedList.<BlockState>builder()
                                        .add(Blocks.PACKED_ICE.defaultBlockState(), 5)
                                        .add(Blocks.ICE.defaultBlockState(), 2)
                                        .add(Blocks.BLUE_ICE.defaultBlockState(), 1)
                        )
                )
        );
    }

    @SuppressWarnings("unchecked")
    private static ConfiguredFeature<?, ?> caveWallFrost(final HolderGetter<Feature<?>> features) {
        final Feature<CaveWallFrostFeature.Configuration> feature = (Feature<CaveWallFrostFeature.Configuration>) features.getOrThrow((ResourceKey) VanilifeFeatures.CAVE_WALL_FROST).value();
        return new ConfiguredFeature<>(
                feature,
                new CaveWallFrostFeature.Configuration(
                        new WeightedStateProvider(
                                WeightedList.<BlockState>builder()
                                        .add(Blocks.PACKED_ICE.defaultBlockState(), 5)
                                        .add(Blocks.SNOW_BLOCK.defaultBlockState(), 3)
                                        .add(Blocks.ICE.defaultBlockState(), 2)
                        ),
                        new WeightedStateProvider(
                                WeightedList.<BlockState>builder()
                                        .add(Blocks.BLUE_ICE.defaultBlockState(), 3)
                                        .add(Blocks.PACKED_ICE.defaultBlockState(), 2)
                                        .add(Blocks.SNOW_BLOCK.defaultBlockState(), 1)
                        )
                )
        );
    }
}
