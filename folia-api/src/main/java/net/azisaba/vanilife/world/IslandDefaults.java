package net.azisaba.vanilife.world;

import net.azisaba.vanilife.Vanilife;
import net.kyori.adventure.key.Key;
import org.jspecify.annotations.NullMarked;

// TODO: Let's think about what to do...
@NullMarked
public final class IslandDefaults {
    public static final int MIN_Y = 0;
    public static final int HEIGHT = 16 * 8; // 128
    public static final int MAX_Y = MIN_Y + HEIGHT - 1;
    public static final int SEA_LEVEL = 63;

    public static final int GRID_SIZE = 32;

    public static final int ISLAND_SIZE_X_GRIDS = 8;
    public static final int ISLAND_SIZE_Z_GRIDS = 8;
    public static final int SPACING_GRIDS = 56;

    public static final int ISLAND_SIZE_X_BLOCKS = ISLAND_SIZE_X_GRIDS * GRID_SIZE;
    public static final int ISLAND_SIZE_Z_BLOCKS = ISLAND_SIZE_Z_GRIDS * GRID_SIZE;
    public static final int SPACING_BLOCKS = SPACING_GRIDS * GRID_SIZE;

    public static final Key WORLD_KEY = Key.key(Vanilife.NAMESPACE, "islands");
}
