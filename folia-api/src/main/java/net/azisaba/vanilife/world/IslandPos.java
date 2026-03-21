package net.azisaba.vanilife.world;

import com.google.common.base.Preconditions;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record IslandPos(int x, int z) {
    private static final long SERIALIZED_WIDTH = 4096L;

    public static IslandPos fromBlockPos(final int blockX, final int blockZ) {
        return new IslandPos(gridOf(blockX), gridOf(blockZ));
    }

    public static IslandPos fromLong(final long value) {
        Preconditions.checkArgument(value >= 1L, "Value must be >= 1: %s", value);

        final long id0 = value - 1L;
        final int x = (int) (id0 % SERIALIZED_WIDTH);
        final long zLong = id0 / SERIALIZED_WIDTH;
        Preconditions.checkArgument(zLong <= Integer.MAX_VALUE, "Z out of Int range: %s", zLong);

        return new IslandPos(x, (int) zLong);
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

    public long toLong() {
        final long x = this.x;
        final long z = this.z;
        Preconditions.checkArgument(x >= 0L && x < SERIALIZED_WIDTH, "X out of range: %s (expected 0..%s)", x, SERIALIZED_WIDTH - 1L);
        Preconditions.checkArgument(z >= 0L, "Z must be >= 0: %s", z);

        return z * SERIALIZED_WIDTH + x + 1L;
    }
}
