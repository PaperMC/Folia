package net.azisaba.vanilife.server.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class CaveSnowCoverFeature extends Feature<CaveSnowCoverFeature.Configuration> {
    private static final int NO_FLOOR = Integer.MIN_VALUE;

    public CaveSnowCoverFeature(final Codec<Configuration> codec) {
        super(codec);
    }

    @Override
    public boolean place(final FeaturePlaceContext<Configuration> context) {
        final WorldGenLevel level = context.level();
        final BlockPos origin = context.origin();
        final RandomSource random = context.random();
        final Configuration config = context.config();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final int[][] floorMap = this.sampleFloorMap(level, origin, config);
        final float[][] smoothMap = this.smoothFloorMap(floorMap, config);
        return this.placeSnowCover(level, origin, random, floorMap, smoothMap, cursor, config);
    }

    private boolean placeSnowCover(
            final WorldGenLevel level,
            final BlockPos origin,
            final RandomSource random,
            final int[][] floorMap,
            final float[][] smoothMap,
            final BlockPos.MutableBlockPos cursor,
            final Configuration config
    ) {
        final int diameter = config.diameter();
        boolean placed = false;
        for (int x = 0; x < diameter; x++) {
            for (int z = 0; z < diameter; z++) {
                final int floorY = floorMap[x][z];
                if (floorY == NO_FLOOR) {
                    continue;
                }

                final int blockX = origin.getX() + x - config.radius();
                final int blockZ = origin.getZ() + z - config.radius();
                cursor.set(blockX, floorY + 1, blockZ);
                if (!level.isStateAtPosition(cursor, BlockState::isAir)) {
                    continue;
                }

                final int targetLayers = this.computeTargetLayers(floorY, smoothMap[x][z], config);
                final BlockState state = this.selectSurfaceState(level, cursor, random, targetLayers, config);
                if (state == null) {
                    continue;
                }

                this.setBlock(level, cursor, state);
                placed = true;
            }
        }
        return placed;
    }

    private int[][] sampleFloorMap(final WorldGenLevel level, final BlockPos origin, final Configuration config) {
        final int diameter = config.diameter();
        final int[][] floorMap = new int[diameter][diameter];
        this.fillFloorMap(floorMap);

        for (int x = 0; x < diameter; x++) {
            for (int z = 0; z < diameter; z++) {
                final int blockX = origin.getX() + x - config.radius();
                final int blockZ = origin.getZ() + z - config.radius();
                final Integer floorY = this.findFloor(level, blockX, origin.getY(), blockZ, config.verticalSearchRange());
                if (floorY != null) {
                    floorMap[x][z] = floorY;
                }
            }
        }
        return floorMap;
    }

    private float[][] smoothFloorMap(final int[][] floorMap, final Configuration config) {
        final int diameter = config.diameter();
        final float[][] smoothMap = new float[diameter][diameter];
        final float[][] scratchMap = new float[diameter][diameter];

        for (int x = 0; x < diameter; x++) {
            for (int z = 0; z < diameter; z++) {
                if (floorMap[x][z] != NO_FLOOR) {
                    smoothMap[x][z] = floorMap[x][z];
                }
            }
        }

        for (int pass = 0; pass < config.blurPasses(); pass++) {
            this.blurAlongX(floorMap, smoothMap, scratchMap, config);
            this.blurAlongZ(floorMap, scratchMap, smoothMap, config);
        }
        return smoothMap;
    }

    private void fillFloorMap(final int[][] floorMap) {
        for (final int[] row : floorMap) {
            java.util.Arrays.fill(row, NO_FLOOR);
        }
    }

    private @Nullable BlockState selectSurfaceState(final WorldGenLevel level, final BlockPos pos, final RandomSource random, final int targetLayers, final Configuration config) {
        final BlockState icePatch = this.sampleIcePatch(pos, random, targetLayers, config);
        if (icePatch != null) {
            return icePatch.canSurvive(level, pos) ? icePatch : null;
        }

        final BlockState snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, targetLayers);
        return snow.canSurvive(level, pos) ? snow : null;
    }

    private int computeTargetLayers(final int floorY, final float smoothHeight, final Configuration config) {
        final float clampedSmoothHeight = Math.max(smoothHeight, floorY + config.minSmoothOffset());
        return Mth.clamp(
                Math.round((clampedSmoothHeight - floorY) * config.layerScale()),
                1,
                SnowLayerBlock.MAX_HEIGHT
        );
    }

    private void blur(final int[][] floorMap, final float[][] source, final float[][] target, final boolean alongX, final Configuration config) {
        final int diameter = config.diameter();
        for (int x = 0; x < diameter; x++) {
            for (int z = 0; z < diameter; z++) {
                if (floorMap[x][z] == NO_FLOOR) {
                    target[x][z] = 0.0F;
                    continue;
                }

                float sum = 0.0F;
                float weight = 0.0F;
                for (int offset = -1; offset <= 1; offset++) {
                    final int sampleX = alongX ? x + offset : x;
                    final int sampleZ = alongX ? z : z + offset;
                    if (!this.isSampledFloor(floorMap, sampleX, sampleZ, diameter)) {
                        continue;
                    }
                    final float sampleWeight = offset == 0 ? config.centerWeight() : config.sideWeight();
                    sum += source[sampleX][sampleZ] * sampleWeight;
                    weight += sampleWeight;
                }
                target[x][z] = sum / weight;
            }
        }
    }

    private void blurAlongX(final int[][] floorMap, final float[][] source, final float[][] target, final Configuration config) {
        this.blur(floorMap, source, target, true, config);
    }

    private void blurAlongZ(final int[][] floorMap, final float[][] source, final float[][] target, final Configuration config) {
        this.blur(floorMap, source, target, false, config);
    }

    private @Nullable Integer findFloor(final WorldGenLevel level, final int x, final int startY, final int z, final int range) {
        for (int y = startY; y >= startY - range; y--) {
            final BlockPos pos = new BlockPos(x, y, z);
            if (level.isOutsideBuildHeight(pos)) {
                continue;
            }

            final BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.is(Blocks.WATER) && !state.is(Blocks.SNOW)) {
                return y;
            }
        }
        return null;
    }

    private @Nullable BlockState sampleIcePatch(final BlockPos pos, final RandomSource random, final int targetLayers, final Configuration config) {
        if (targetLayers > config.maxIcePatchLayers()) {
            return null;
        }
        return config.icePatchProvider().getState(random, pos);
    }

    private boolean isSampledFloor(final int[][] floorMap, final int x, final int z, final int diameter) {
        return x >= 0 && x < diameter && z >= 0 && z < diameter && floorMap[x][z] != NO_FLOOR;
    }

    public record Configuration(
            int radius,
            int verticalSearchRange,
            int blurPasses,
            float minSmoothOffset,
            float layerScale,
            float centerWeight,
            float sideWeight,
            int maxIcePatchLayers,
            BlockStateProvider icePatchProvider
    ) implements FeatureConfiguration {
        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("radius").forGetter(Configuration::radius),
                Codec.INT.fieldOf("vertical_search_range").forGetter(Configuration::verticalSearchRange),
                Codec.INT.fieldOf("blur_passes").forGetter(Configuration::blurPasses),
                Codec.FLOAT.fieldOf("min_smooth_offset").forGetter(Configuration::minSmoothOffset),
                Codec.FLOAT.fieldOf("layer_scale").forGetter(Configuration::layerScale),
                Codec.FLOAT.fieldOf("center_weight").forGetter(Configuration::centerWeight),
                Codec.FLOAT.fieldOf("side_weight").forGetter(Configuration::sideWeight),
                Codec.INT.fieldOf("max_ice_patch_layers").forGetter(Configuration::maxIcePatchLayers),
                BlockStateProvider.CODEC.fieldOf("ice_patch_provider").forGetter(Configuration::icePatchProvider)
        ).apply(instance, Configuration::new));

        public int diameter() {
            return this.radius * 2 + 1;
        }
    }
}
