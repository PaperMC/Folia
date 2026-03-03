package net.azisaba.vanilife.server.world.height;

import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

@NullMarked
public interface HeightmapContext {
    Heightmap.Types worldSurfaceWg();

    Heightmap.Types worldSurface();

    Heightmap.Types oceanFloorWg();

    Heightmap.Types oceanFloor();

    Heightmap.Types motionBlocking();

    Heightmap.Types motionBlockingNoLeaves();

    default Heightmap.@Nullable Types resolveHeightmap(final Heightmap.Types vanillaType) {
        return switch (vanillaType) {
            case Heightmap.Types.WORLD_SURFACE_WG -> this.worldSurfaceWg();
            case Heightmap.Types.WORLD_SURFACE -> this.worldSurface();
            case Heightmap.Types.OCEAN_FLOOR_WG -> this.oceanFloorWg();
            case Heightmap.Types.OCEAN_FLOOR -> this.oceanFloor();
            case Heightmap.Types.MOTION_BLOCKING -> this.motionBlocking();
            case Heightmap.Types.MOTION_BLOCKING_NO_LEAVES -> this.motionBlockingNoLeaves();
            default -> null;
        };
    }

    default Heightmap.Types resolveHeightmapOrSelf(final Heightmap.Types vanillaType) {
        return Objects.requireNonNullElse(this.resolveHeightmap(vanillaType), vanillaType);
    }
}
