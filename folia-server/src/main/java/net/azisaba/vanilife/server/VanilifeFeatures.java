package net.azisaba.vanilife.server;

import net.azisaba.vanilife.Vanilife;
import net.azisaba.vanilife.server.world.feature.CaveIceClusterFeature;
import net.azisaba.vanilife.server.world.feature.CaveIcePillarFeature;
import net.azisaba.vanilife.server.world.feature.CaveSnowCoverFeature;
import net.azisaba.vanilife.server.world.feature.CaveWallFrostFeature;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class VanilifeFeatures {
    public static final ResourceKey<Feature<CaveIceClusterFeature.Configuration>> CAVE_ICE_CLUSTER = create(Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "cave_ice_cluster"));
    public static final ResourceKey<Feature<NoneFeatureConfiguration>> CAVE_ICE_PILLAR = create(Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "cave_ice_pillar"));
    public static final ResourceKey<Feature<CaveSnowCoverFeature.Configuration>> CAVE_SNOW_COVER = create(Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "cave_snow_cover"));
    public static final ResourceKey<Feature<CaveWallFrostFeature.Configuration>> CAVE_WALL_FROST = create(Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "cave_wall_frost"));

    private VanilifeFeatures() {
    }

    public static void bootstrap(final WritableRegistry<Feature<?>> writable) {
        register(writable, CAVE_ICE_CLUSTER, caveIceCluster());
        register(writable, CAVE_ICE_PILLAR, caveIcePillar());
        register(writable, CAVE_SNOW_COVER, caveSnowCover());
        register(writable, CAVE_WALL_FROST, caveWallFrost());
    }

    private static Feature<CaveIceClusterFeature.Configuration> caveIceCluster() {
        return new CaveIceClusterFeature(CaveIceClusterFeature.Configuration.CODEC);
    }

    private static Feature<NoneFeatureConfiguration> caveIcePillar() {
        return new CaveIcePillarFeature(NoneFeatureConfiguration.CODEC);
    }

    private static Feature<CaveSnowCoverFeature.Configuration> caveSnowCover() {
        return new CaveSnowCoverFeature(CaveSnowCoverFeature.Configuration.CODEC);
    }

    private static Feature<CaveWallFrostFeature.Configuration> caveWallFrost() {
        return new CaveWallFrostFeature(CaveWallFrostFeature.Configuration.CODEC);
    }

    @SuppressWarnings("unchecked")
    private static <C extends FeatureConfiguration> ResourceKey<Feature<C>> create(final Identifier identifier) {
        return (ResourceKey<Feature<C>>) (ResourceKey<?>) ResourceKey.create(Registries.FEATURE, identifier);
    }

    @SuppressWarnings("unchecked")
    private static <C extends FeatureConfiguration> void register(final WritableRegistry<Feature<?>> writable, final ResourceKey<Feature<C>> key, final Feature<C> feature) {
        writable.register((ResourceKey<Feature<?>>) (ResourceKey<?>) key, feature, RegistrationInfo.BUILT_IN);
    }
}
