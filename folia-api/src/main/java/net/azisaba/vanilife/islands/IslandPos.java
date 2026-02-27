package net.azisaba.vanilife.islands;

import io.papermc.paper.math.Position;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record IslandPos(int x, int z) {
    public static IslandPos fromBlockPos(final int blockX, final int blockZ) {
        return new IslandPos(gridOf(blockX), gridOf(blockZ));
    }

    private static int gridOf(final int levelCoord) {
        final int half = IslandDefaults.SPACING_BLOCKS / 2;
        return Math.floorDiv(levelCoord + half, IslandDefaults.SPACING_BLOCKS);
    }

    public int minBlockX() {
        return centerBlockX() - (IslandDefaults.ISLAND_SIZE_X_BLOCKS / 2);
    }

    public int maxBlockX() {
        return centerBlockX() + (IslandDefaults.ISLAND_SIZE_X_BLOCKS / 2);
    }

    public int minBlockZ() {
        return centerBlockZ() - (IslandDefaults.ISLAND_SIZE_Z_BLOCKS / 2);
    }

    public int maxBlockZ() {
        return centerBlockZ() + (IslandDefaults.ISLAND_SIZE_Z_BLOCKS / 2);
    }

    public int centerBlockX() {
        return this.x * IslandDefaults.SPACING_BLOCKS;
    }

    public int centerBlockZ() {
        return this.z * IslandDefaults.SPACING_BLOCKS;
    }

    public long computeSeed(final long levelSeed) {
        long s = (((long) this.x << 32) ^ ((long) this.z & 0xffffffffL)) ^ levelSeed;
        s ^= (s >>> 30);
        s *= 0xBF58476D1CE4E5B9L;
        s ^= (s >>> 27);
        s *= 0x94D049BB133111EBL;
        s ^= (s >>> 31);
        return s;
    }

    public boolean contains(final Position position) {
        return position.blockX() >= this.minBlockX() && position.x() <= this.maxBlockX() &&
                position.blockY() >= IslandDefaults.MIN_Y && position.y() <= IslandDefaults.MAX_Y &&
                position.blockZ() >= this.minBlockZ() && position.z() <= this.maxBlockZ();
    }
}
