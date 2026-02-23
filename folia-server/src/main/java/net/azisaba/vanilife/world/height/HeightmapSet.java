package net.azisaba.vanilife.world.height;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record HeightmapSet(
        Heightmap.Types worldSurfaceWg,
        Heightmap.Types worldSurface,
        Heightmap.Types oceanFloorWg,
        Heightmap.Types oceanFloor,
        Heightmap.Types motionBlocking,
        Heightmap.Types motionBlockingNoLeaves
) implements HeightmapContext {
    public static final Codec<HeightmapSet> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                            Heightmap.Types.CODEC.fieldOf("worldSurfaceWg").forGetter(HeightmapSet::worldSurfaceWg),
                            Heightmap.Types.CODEC.fieldOf("worldSurface").forGetter(HeightmapSet::worldSurface),
                            Heightmap.Types.CODEC.fieldOf("oceanFloorWg").forGetter(HeightmapSet::oceanFloorWg),
                            Heightmap.Types.CODEC.fieldOf("oceanFloor").forGetter(HeightmapSet::oceanFloor),
                            Heightmap.Types.CODEC.fieldOf("motionBlocking").forGetter(HeightmapSet::motionBlocking),
                            Heightmap.Types.CODEC.fieldOf("motionBlockingNoLeaves").forGetter(HeightmapSet::motionBlockingNoLeaves)
                    )
                    .apply(instance, HeightmapSet::new)
    );

    public static final HeightmapSet VANILLA = new HeightmapSet(
            Heightmap.Types.WORLD_SURFACE_WG,
            Heightmap.Types.WORLD_SURFACE,
            Heightmap.Types.OCEAN_FLOOR_WG,
            Heightmap.Types.OCEAN_FLOOR,
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
    );
    public static final HeightmapSet AETHERIA_OVERWORLD = new HeightmapSet(
            Heightmap.Types.AETHERIA_OVERWORLD_WORLD_SURFACE_WG,
            Heightmap.Types.AETHERIA_OVERWORLD_WORLD_SURFACE,
            Heightmap.Types.AETHERIA_OVERWORLD_OCEAN_FLOOR_WG,
            Heightmap.Types.AETHERIA_OVERWORLD_OCEAN_FLOOR,
            Heightmap.Types.AETHERIA_OVERWORLD_MOTION_BLOCKING,
            Heightmap.Types.AETHERIA_OVERWORLD_MOTION_BLOCKING_NO_LEAVES
    );
    public static final HeightmapSet AETHERIA_NETHER = new HeightmapSet(
            Heightmap.Types.AETHERIA_NETHER_WORLD_SURFACE_WG,
            Heightmap.Types.AETHERIA_NETHER_WORLD_SURFACE,
            Heightmap.Types.AETHERIA_NETHER_OCEAN_FLOOR_WG,
            Heightmap.Types.AETHERIA_NETHER_OCEAN_FLOOR,
            Heightmap.Types.AETHERIA_NETHER_MOTION_BLOCKING,
            Heightmap.Types.AETHERIA_NETHER_MOTION_BLOCKING_NO_LEAVES
    );
    public static final HeightmapSet AETHERIA_END = new HeightmapSet(
            Heightmap.Types.AETHERIA_END_WORLD_SURFACE_WG,
            Heightmap.Types.AETHERIA_END_WORLD_SURFACE,
            Heightmap.Types.AETHERIA_END_OCEAN_FLOOR_WG,
            Heightmap.Types.AETHERIA_END_OCEAN_FLOOR,
            Heightmap.Types.AETHERIA_END_MOTION_BLOCKING,
            Heightmap.Types.AETHERIA_END_MOTION_BLOCKING_NO_LEAVES
    );
}
