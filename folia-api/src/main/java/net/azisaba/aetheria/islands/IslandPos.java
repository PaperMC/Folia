package net.azisaba.aetheria.islands;

import org.jspecify.annotations.NullMarked;

@NullMarked
public record IslandPos(int gridX, int gridZ) {
    private static final int SPACING_BLOCKS = (Islands.ISLAND_SPACING_GRIDS + Islands.ISLAND_WIDTH_GRIDS) * Islands.GRID_SIZE;

    public static IslandPos fromBlockPos(final int blockX, final int blockZ) {
        return new IslandPos(gridOf(blockX), gridOf(blockZ));
    }

    private static int gridOf(final int levelCoord) {
        final int half = IslandPos.SPACING_BLOCKS / 2;
        return Math.floorDiv(levelCoord + half, IslandPos.SPACING_BLOCKS);
    }

    public int centerBlockX() {
        return this.gridX * IslandPos.SPACING_BLOCKS;
    }

    public int centerBlockZ() {
        return this.gridZ * IslandPos.SPACING_BLOCKS;
    }

    public long computeSeed(final long levelSeed) {
        long s = (((long) this.gridX << 32) ^ ((long) this.gridZ & 0xffffffffL)) ^ levelSeed;
        s ^= (s >>> 30);
        s *= 0xBF58476D1CE4E5B9L;
        s ^= (s >>> 27);
        s *= 0x94D049BB133111EBL;
        s ^= (s >>> 31);
        return s;
    }
}
