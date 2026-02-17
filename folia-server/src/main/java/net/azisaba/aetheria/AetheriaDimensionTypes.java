package net.azisaba.aetheria;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.biome.OverworldBiomes;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TimelineTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.attribute.*;
import net.minecraft.world.level.dimension.DimensionDefaults;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.timeline.Timeline;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class AetheriaDimensionTypes {
    public static final ResourceKey<DimensionType> AETHERIA = ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.withAetheriaNamespace("aetheria"));

    public static void bootstrap(final WritableRegistry<DimensionType> writable, final RegistryOps.RegistryInfoLookup lookup) {
        final HolderGetter<Timeline> holderGetter = lookup.lookup(Registries.TIMELINE)
                .orElseThrow()
                .getter();
        final EnvironmentAttributeMap environmentAttributeMap = EnvironmentAttributeMap.builder()
                .set(EnvironmentAttributes.FOG_COLOR, -4138753)
                .set(EnvironmentAttributes.SKY_COLOR, OverworldBiomes.calculateSkyColor(0.8F))
                .set(EnvironmentAttributes.CLOUD_COLOR, ARGB.white(0.2F))
                .set(EnvironmentAttributes.CLOUD_HEIGHT, 192.33F)
                .set(EnvironmentAttributes.BACKGROUND_MUSIC, BackgroundMusic.OVERWORLD)
                .set(EnvironmentAttributes.BED_RULE, BedRule.CAN_SLEEP_WHEN_DARK)
                .set(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, false)
                .set(EnvironmentAttributes.NETHER_PORTAL_SPAWNS_PIGLINS, true)
                .set(EnvironmentAttributes.AMBIENT_SOUNDS, AmbientSounds.LEGACY_CAVE_SETTINGS)
                .build();
        writable.register(
                AetheriaDimensionTypes.AETHERIA,
                new DimensionType(
                        false,
                        true,
                        false,
                        1.0,
                        DimensionDefaults.OVERWORLD_MIN_Y - DimensionDefaults.NETHER_GENERATION_HEIGHT,
                        DimensionDefaults.NETHER_GENERATION_HEIGHT + DimensionDefaults.OVERWORLD_GENERATION_HEIGHT + DimensionDefaults.END_GENERATION_HEIGHT,
                        DimensionDefaults.NETHER_GENERATION_HEIGHT + DimensionDefaults.OVERWORLD_GENERATION_HEIGHT + DimensionDefaults.END_GENERATION_HEIGHT,
                        BlockTags.INFINIBURN_OVERWORLD,
                        0.0F,
                        new DimensionType.MonsterSettings(UniformInt.of(0, 7), 0),
                        DimensionType.Skybox.OVERWORLD,
                        DimensionType.CardinalLightType.DEFAULT,
                        environmentAttributeMap,
                        holderGetter.getOrThrow(TimelineTags.IN_OVERWORLD)
                ),
                RegistrationInfo.BUILT_IN
        );
    }
}
