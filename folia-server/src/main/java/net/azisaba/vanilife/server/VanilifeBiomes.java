package net.azisaba.vanilife.server;

import net.azisaba.vanilife.Vanilife;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BiomeDefaultFeatures;
import net.minecraft.data.worldgen.placement.EndPlacements;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class VanilifeBiomes {
    public static final ResourceKey<Biome> GLACIAL_CAVE = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "glacial_cave"));
    public static final ResourceKey<Biome> THE_END = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "the_end"));
    public static final ResourceKey<Biome> END_BARRENS = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "end_barrens"));
    public static final ResourceKey<Biome> END_MIDLANDS = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "end_midlands"));
    public static final ResourceKey<Biome> END_HIGHLANDS = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "end_highlands"));
    public static final ResourceKey<Biome> SMALL_END_ISLANDS = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "small_end_islands"));

    public static void bootstrap(final WritableRegistry<Biome> writable, final RegistryOps.RegistryInfoLookup lookup) {
        final HolderGetter<PlacedFeature> placedFeature = lookup.lookup(Registries.PLACED_FEATURE)
                .orElseThrow()
                .getter();
        final HolderGetter<ConfiguredWorldCarver<?>> worldCarvers = lookup.lookup(Registries.CONFIGURED_CARVER)
                .orElseThrow()
                .getter();

        writable.register(
                VanilifeBiomes.GLACIAL_CAVE,
                VanilifeBiomes.glacialCave(placedFeature, worldCarvers),
                RegistrationInfo.BUILT_IN
        );
        writable.register(
                VanilifeBiomes.THE_END,
                VanilifeBiomes.theEnd(placedFeature, worldCarvers),
                RegistrationInfo.BUILT_IN
        );
        writable.register(
                VanilifeBiomes.END_BARRENS,
                VanilifeBiomes.endBarrens(placedFeature, worldCarvers),
                RegistrationInfo.BUILT_IN
        );
        writable.register(
                VanilifeBiomes.END_MIDLANDS,
                VanilifeBiomes.endMidlands(placedFeature, worldCarvers),
                RegistrationInfo.BUILT_IN
        );
        writable.register(
                VanilifeBiomes.END_HIGHLANDS,
                VanilifeBiomes.endHighlands(placedFeature, worldCarvers),
                RegistrationInfo.BUILT_IN
        );
        writable.register(
                VanilifeBiomes.SMALL_END_ISLANDS,
                VanilifeBiomes.smallEndIslands(placedFeature, worldCarvers),
                RegistrationInfo.BUILT_IN
        );
    }

    private static Biome glacialCave(final HolderGetter<PlacedFeature> placedFeatures, final HolderGetter<ConfiguredWorldCarver<?>> worldCarvers) {
        final MobSpawnSettings.Builder mobSpawnSettings = new MobSpawnSettings.Builder();
        BiomeDefaultFeatures.caveSpawns(mobSpawnSettings);

        final BiomeGenerationSettings generationSettings = new BiomeGenerationSettings.Builder(placedFeatures, worldCarvers)
                .build();

        return new Biome.BiomeBuilder()
                .hasPrecipitation(true)
                .temperature(0.0F)
                .downfall(0.4F)
                .specialEffects(new BiomeSpecialEffects.Builder().waterColor(1332635).grassColorOverride(8434839).foliageColorOverride(6332795).build())
                .mobSpawnSettings(mobSpawnSettings.build())
                .generationSettings(generationSettings)
                .build();
    }

    private static Biome baseEndBiome(final BiomeGenerationSettings.Builder generationSettings) {
        final MobSpawnSettings.Builder mobSpawnSettings = new MobSpawnSettings.Builder();
        BiomeDefaultFeatures.endSpawns(mobSpawnSettings);
        return new Biome.BiomeBuilder()
                .hasPrecipitation(false)
                .temperature(0.5F)
                .downfall(0.5F)
                .specialEffects(new BiomeSpecialEffects.Builder().waterColor(4159204).build())
                .mobSpawnSettings(mobSpawnSettings.build())
                .generationSettings(generationSettings.build())
                .setAttribute(EnvironmentAttributes.SKY_COLOR, 1250067)
                .setAttribute(EnvironmentAttributes.FOG_COLOR, 9538492)
                .build();
    }

    private static Biome theEnd(final HolderGetter<PlacedFeature> placedFeatures, final HolderGetter<ConfiguredWorldCarver<?>> worldCarvers) {
        BiomeGenerationSettings.Builder builder = new BiomeGenerationSettings.Builder(placedFeatures, worldCarvers)
                .addFeature(GenerationStep.Decoration.SURFACE_STRUCTURES, EndPlacements.END_SPIKE)
                .addFeature(GenerationStep.Decoration.TOP_LAYER_MODIFICATION, EndPlacements.END_PLATFORM);
        return baseEndBiome(builder);
    }

    private static Biome endBarrens(final HolderGetter<PlacedFeature> placedFeatures, final HolderGetter<ConfiguredWorldCarver<?>> worldCarvers) {
        BiomeGenerationSettings.Builder builder = new BiomeGenerationSettings.Builder(placedFeatures, worldCarvers);
        return baseEndBiome(builder);
    }

    private static Biome endMidlands(final HolderGetter<PlacedFeature> placedFeatures, final HolderGetter<ConfiguredWorldCarver<?>> worldCarvers) {
        BiomeGenerationSettings.Builder builder = new BiomeGenerationSettings.Builder(placedFeatures, worldCarvers);
        return baseEndBiome(builder);
    }

    private static Biome endHighlands(final HolderGetter<PlacedFeature> placedFeatures, final HolderGetter<ConfiguredWorldCarver<?>> worldCarvers) {
        BiomeGenerationSettings.Builder builder = new BiomeGenerationSettings.Builder(placedFeatures, worldCarvers)
                .addFeature(GenerationStep.Decoration.SURFACE_STRUCTURES, EndPlacements.END_GATEWAY_RETURN)
                .addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, EndPlacements.CHORUS_PLANT);
        return baseEndBiome(builder);
    }

    private static Biome smallEndIslands(final HolderGetter<PlacedFeature> placedFeatures, final HolderGetter<ConfiguredWorldCarver<?>> worldCarvers) {
        BiomeGenerationSettings.Builder builder = new BiomeGenerationSettings.Builder(placedFeatures, worldCarvers)
                .addFeature(GenerationStep.Decoration.RAW_GENERATION, EndPlacements.END_ISLAND_DECORATED);
        return baseEndBiome(builder);
    }
}
