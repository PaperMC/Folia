package net.azisaba.vanilife.server;

import net.azisaba.vanilife.Vanilife;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BiomeDefaultFeatures;
import net.minecraft.data.worldgen.placement.EndPlacements;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.data.worldgen.Carvers;
import net.minecraft.world.attribute.AmbientParticle;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.BackgroundMusic;
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
    public static final ResourceKey<Biome> END_BARRENS = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "end_barrens"));
    public static final ResourceKey<Biome> END_MIDLANDS = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "end_midlands"));
    public static final ResourceKey<Biome> END_HIGHLANDS = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "end_highlands"));
    public static final ResourceKey<Biome> GLACIAL_CAVE = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "glacial_cave"));
    public static final ResourceKey<Biome> SMALL_END_ISLANDS = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "small_end_islands"));
    public static final ResourceKey<Biome> THE_END = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(Vanilife.NAMESPACE, "the_end"));

    public static void bootstrap(final WritableRegistry<Biome> writable, final RegistryOps.RegistryInfoLookup lookup) {
        final HolderGetter<PlacedFeature> placedFeature = lookup.lookup(Registries.PLACED_FEATURE)
                .orElseThrow()
                .getter();

        final HolderGetter<ConfiguredWorldCarver<?>> worldCarvers = lookup.lookup(Registries.CONFIGURED_CARVER)
                .orElseThrow()
                .getter();

        writable.register(VanilifeBiomes.END_BARRENS, VanilifeBiomes.endBarrens(placedFeature, worldCarvers), RegistrationInfo.BUILT_IN);
        writable.register(VanilifeBiomes.END_MIDLANDS, VanilifeBiomes.endMidlands(placedFeature, worldCarvers), RegistrationInfo.BUILT_IN);
        writable.register(VanilifeBiomes.END_HIGHLANDS, VanilifeBiomes.endHighlands(placedFeature, worldCarvers), RegistrationInfo.BUILT_IN);
        writable.register(VanilifeBiomes.GLACIAL_CAVE, VanilifeBiomes.glacialCave(placedFeature, worldCarvers), RegistrationInfo.BUILT_IN);
        writable.register(VanilifeBiomes.SMALL_END_ISLANDS, VanilifeBiomes.smallEndIslands(placedFeature, worldCarvers), RegistrationInfo.BUILT_IN);
        writable.register(VanilifeBiomes.THE_END, VanilifeBiomes.theEnd(placedFeature, worldCarvers), RegistrationInfo.BUILT_IN);
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

    private static Biome glacialCave(final HolderGetter<PlacedFeature> placedFeatures, final HolderGetter<ConfiguredWorldCarver<?>> worldCarvers) {
        final MobSpawnSettings.Builder mobSpawnSettings = new MobSpawnSettings.Builder();
        BiomeDefaultFeatures.caveSpawns(mobSpawnSettings);

        final BiomeGenerationSettings.Builder generationSettings = new BiomeGenerationSettings.Builder(placedFeatures, worldCarvers)
                .addCarver(Carvers.CAVE)
                .addCarver(Carvers.CAVE_EXTRA_UNDERGROUND)
                .addCarver(Carvers.CANYON)
                .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, VanilifePlacedFeatures.CAVE_SNOW_COVER)
                .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, VanilifePlacedFeatures.CAVE_ICE_PILLAR)
                .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, VanilifePlacedFeatures.CAVE_ICE_CLUSTER)
                .addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, VanilifePlacedFeatures.CAVE_WALL_FROST);
        BiomeDefaultFeatures.addDefaultCrystalFormations(generationSettings);
        BiomeDefaultFeatures.addDefaultMonsterRoom(generationSettings);
        BiomeDefaultFeatures.addDefaultUndergroundVariety(generationSettings);
        BiomeDefaultFeatures.addDefaultOres(generationSettings);
        BiomeDefaultFeatures.addDefaultSoftDisks(generationSettings);
        BiomeDefaultFeatures.addSurfaceFreezing(generationSettings);
        BiomeDefaultFeatures.addDripstone(generationSettings);

        return new Biome.BiomeBuilder()
                .hasPrecipitation(true)
                .temperature(-0.9F)
                .temperatureAdjustment(Biome.TemperatureModifier.FROZEN)
                .downfall(1.0F)
                .specialEffects(new BiomeSpecialEffects.Builder()
                        .waterColor(329011)
                        .grassColorOverride(1118719)
                        .foliageColorOverride(855309)
                        .build())
                .mobSpawnSettings(mobSpawnSettings.build())
                .generationSettings(generationSettings.build())
                .setAttribute(EnvironmentAttributes.FOG_COLOR, -1084823)
                .setAttribute(EnvironmentAttributes.WATER_FOG_COLOR, -14606047)
                .setAttribute(EnvironmentAttributes.BACKGROUND_MUSIC, new BackgroundMusic(SoundEvents.MUSIC_BIOME_FROZEN_PEAKS))
                .setAttribute(EnvironmentAttributes.INCREASED_FIRE_BURNOUT, true)
                .setAttribute(EnvironmentAttributes.AMBIENT_PARTICLES, AmbientParticle.of(ParticleTypes.WHITE_ASH, 0.6f))
                .setAttribute(EnvironmentAttributes.FOG_END_DISTANCE, 196.0f)
                .setAttribute(EnvironmentAttributes.FOG_START_DISTANCE, -1.0f)
                .setAttribute(EnvironmentAttributes.SKY_LIGHT_COLOR, 8026879)
                .setAttribute(EnvironmentAttributes.SKY_LIGHT_FACTOR, 0.0f)
                .build();
    }

    private static Biome smallEndIslands(final HolderGetter<PlacedFeature> placedFeatures, final HolderGetter<ConfiguredWorldCarver<?>> worldCarvers) {
        BiomeGenerationSettings.Builder builder = new BiomeGenerationSettings.Builder(placedFeatures, worldCarvers)
                .addFeature(GenerationStep.Decoration.RAW_GENERATION, EndPlacements.END_ISLAND_DECORATED);
        return baseEndBiome(builder);
    }

    private static Biome theEnd(final HolderGetter<PlacedFeature> placedFeatures, final HolderGetter<ConfiguredWorldCarver<?>> worldCarvers) {
        BiomeGenerationSettings.Builder builder = new BiomeGenerationSettings.Builder(placedFeatures, worldCarvers)
                .addFeature(GenerationStep.Decoration.SURFACE_STRUCTURES, EndPlacements.END_SPIKE)
                .addFeature(GenerationStep.Decoration.TOP_LAYER_MODIFICATION, EndPlacements.END_PLATFORM);
        return baseEndBiome(builder);
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
}
