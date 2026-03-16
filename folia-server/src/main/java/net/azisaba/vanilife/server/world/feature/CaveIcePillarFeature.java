package net.azisaba.vanilife.server.world.feature;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class CaveIcePillarFeature extends Feature<NoneFeatureConfiguration> {
    private static final int MIN_HEIGHT = 4;
    private static final int MAX_HEIGHT = 12;
    private static final int MIN_RADIUS = 2;
    private static final int MAX_RADIUS = 4;
    private static final float POOL_CHANCE = 0.18F;
    private static final int SURFACE_LAYER_THICKNESS = 2;
    private static final int VERTICAL_SURFACE_SEARCH_RANGE = 16;

    public CaveIcePillarFeature(final Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(final FeaturePlaceContext<NoneFeatureConfiguration> context) {
        final WorldGenLevel level = context.level();
        final BlockPos origin = context.origin();
        final RandomSource random = context.random();
        final BlockState originState = level.getBlockState(origin);
        if (!originState.isAir() && !originState.is(Blocks.WATER)) {
            return false;
        }

        final int rangeX = Mth.nextInt(random, MIN_RADIUS, MAX_RADIUS);
        final int rangeZ = Mth.nextInt(random, MIN_RADIUS, MAX_RADIUS);
        final int peakHeight = Mth.nextInt(random, MIN_HEIGHT, MAX_HEIGHT);
        final int clusterCount = 2 + random.nextInt(3);
        final IceCluster[] clusters = this.createClusters(random, rangeX, rangeZ, peakHeight, clusterCount);

        boolean placed = false;
        for (int dx = -rangeX; dx <= rangeX; dx++) {
            for (int dz = -rangeZ; dz <= rangeZ; dz++) {
                placed |= this.placeColumn(level, random, origin, dx, dz, clusters);
            }
        }
        return placed;
    }

    private boolean placeColumn(
            final WorldGenLevel level,
            final RandomSource random,
            final BlockPos origin,
            final int deltaX,
            final int deltaZ,
            final IceCluster[] clusters
    ) {
        final int x = origin.getX() + deltaX;
        final int z = origin.getZ() + deltaZ;
        final Integer ceiling = this.findCeiling(level, x, origin.getY(), z);
        final Integer floor = this.findFloor(level, x, origin.getY(), z);

        if (ceiling == null && floor == null) {
            return false;
        }

        if (floor != null && random.nextFloat() < POOL_CHANCE) {
            this.fillPool(level, x, floor + 1, z);
        }

        final int stalactiteHeight = ceiling == null ? 0 : this.sampleHeight(random, deltaX, deltaZ, clusters);
        if (ceiling != null && stalactiteHeight > 0) {
            this.buildColumnBase(level, x, ceiling, z, -1);
            this.decorateBase(level, x, ceiling - SURFACE_LAYER_THICKNESS, z);
        }

        int stalagmiteHeight = floor == null ? 0 : this.sampleHeight(random, deltaX, deltaZ, clusters);
        if (floor != null && stalagmiteHeight > 0) {
            this.buildColumnBase(level, x, floor, z, 1);
            if (ceiling != null && stalactiteHeight > 0) {
                stalagmiteHeight = Math.max(1, stalactiteHeight + Mth.nextInt(random, -2, 3));
            }
            this.decorateBase(level, x, floor + SURFACE_LAYER_THICKNESS, z);
        }

        if (ceiling != null && stalactiteHeight > 0) {
            this.buildColumn(level, x, ceiling - 1, z, -1, stalactiteHeight);
        }
        if (floor != null && stalagmiteHeight > 0) {
            this.buildColumn(level, x, floor + 1, z, 1, stalagmiteHeight);
        }

        return stalactiteHeight > 0 || stalagmiteHeight > 0;
    }

    private IceCluster[] createClusters(
            final RandomSource random,
            final int rangeX,
            final int rangeZ,
            final int peakHeight,
            final int clusterCount
    ) {
        final IceCluster[] clusters = new IceCluster[clusterCount];
        for (int i = 0; i < clusterCount; i++) {
            clusters[i] = new IceCluster(
                    random.nextFloat() * rangeX * 1.6F - rangeX * 0.8F,
                    random.nextFloat() * rangeZ * 1.6F - rangeZ * 0.8F,
                    Math.max(1.4F, (0.8F + random.nextFloat()) * Math.min(rangeX, rangeZ) * 0.7F),
                    Math.max(3, peakHeight - random.nextInt(4))
            );
        }
        return clusters;
    }

    private int sampleHeight(final RandomSource random, final int dx, final int dz, final IceCluster[] clusters) {
        float best = Float.MAX_VALUE;
        int height = 0;
        for (final IceCluster cluster : clusters) {
            final float nx = (dx - cluster.offsetX()) / cluster.radius();
            final float nz = (dz - cluster.offsetZ()) / cluster.radius();
            final float distance = nx * nx + nz * nz;
            if (distance < best) {
                best = distance;
                height = cluster.height();
            }
        }

        if (best > 1.1F) {
            return 0;
        }

        final float profile = Mth.clamp(1.05F - best, 0.0F, 1.0F);
        final int result = Mth.floor(profile * height + random.nextFloat() * 1.5F);
        return Math.max(0, result);
    }

    private void buildColumn(final WorldGenLevel level, final int x, final int startY, final int z, final int deltaY, final int height) {
        for (int i = 0; i < height; i++) {
            final int y = startY + i * deltaY;
            final BlockPos pos = new BlockPos(x, y, z);
            if (level.isOutsideBuildHeight(pos)) {
                return;
            }

            final BlockState current = level.getBlockState(pos);
            if (!current.isAir() && !current.is(Blocks.WATER)) {
                return;
            }

            this.setBlock(level, pos, i == height - 1 ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState());
        }
    }

    private void buildColumnBase(final WorldGenLevel level, final int x, final int y, final int z, final int deltaY) {
        for (int i = 0; i < SURFACE_LAYER_THICKNESS; i++) {
            final BlockPos pos = new BlockPos(x, y + i * deltaY, z);
            if (level.isOutsideBuildHeight(pos)) {
                return;
            }
            this.setBlock(level, pos, Blocks.PACKED_ICE.defaultBlockState());
        }
    }

    private void decorateBase(final WorldGenLevel level, final int x, final int y, final int z) {
        this.tryPlaceDecoration(level, x + 1, y, z);
        this.tryPlaceDecoration(level, x - 1, y, z);
        this.tryPlaceDecoration(level, x, y, z + 1);
        this.tryPlaceDecoration(level, x, y, z - 1);
    }

    private void tryPlaceDecoration(final WorldGenLevel level, final int x, final int y, final int z) {
        final BlockPos pos = new BlockPos(x, y, z);
        if (level.isOutsideBuildHeight(pos)) {
            return;
        }

        final BlockState current = level.getBlockState(pos);
        if (current.isAir() || current.is(Blocks.WATER)) {
            this.setBlock(level, pos, Blocks.PACKED_ICE.defaultBlockState());
        }
    }

    private Integer findCeiling(final WorldGenLevel level, final int x, final int startY, final int z) {
        return this.findSurface(level, x, startY, z, 1);
    }

    private Integer findFloor(final WorldGenLevel level, final int x, final int startY, final int z) {
        return this.findSurface(level, x, startY, z, -1);
    }

    private void fillPool(final WorldGenLevel level, final int x, final int y, final int z) {
        final BlockPos pos = new BlockPos(x, y, z);
        if (level.isOutsideBuildHeight(pos)) {
            return;
        }

        final BlockState current = level.getBlockState(pos);
        if (current.isAir()) {
            this.setBlock(level, pos, Blocks.WATER.defaultBlockState());
        }
    }

    private Integer findSurface(final WorldGenLevel level, final int x, final int startY, final int z, final int step) {
        for (int offset = 0; offset <= VERTICAL_SURFACE_SEARCH_RANGE; offset++) {
            final BlockPos pos = new BlockPos(x, startY + offset * step, z);
            if (level.isOutsideBuildHeight(pos)) {
                continue;
            }
            if (!this.isReplaceable(level.getBlockState(pos))) {
                return pos.getY();
            }
        }
        return null;
    }

    private boolean isReplaceable(final BlockState state) {
        return state.isAir() || state.is(Blocks.WATER);
    }

    private record IceCluster(float offsetX, float offsetZ, float radius, int height) {
    }
}
