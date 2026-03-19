package net.azisaba.vanilife.server.world.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;

@NullMarked
public final class CaveIceClusterFeature extends Feature<CaveIceClusterFeature.Configuration> {
    private static final int FLOOR_SEARCH_RANGE = 10;
    private static final int MIN_RADIUS = 2;
    private static final int MAX_RADIUS = 5;
    private static final int MIN_HEIGHT = 4;
    private static final int MAX_HEIGHT = 8;

    public CaveIceClusterFeature(final Codec<Configuration> codec) {
        super(codec);
    }

    @Override
    public boolean place(final FeaturePlaceContext<Configuration> context) {
        final WorldGenLevel level = context.level();
        final BlockPos origin = context.origin();
        final RandomSource random = context.random();
        final Configuration config = context.config();
        final Integer floorY = this.findFloor(level, origin.getX(), origin.getY(), origin.getZ());
        if (floorY == null) {
            return false;
        }

        final int radiusX = Mth.nextInt(random, MIN_RADIUS, MAX_RADIUS);
        final int radiusZ = Mth.nextInt(random, MIN_RADIUS, MAX_RADIUS);
        final int height = Mth.nextInt(random, MIN_HEIGHT, MAX_HEIGHT);
        final BlockPos center = new BlockPos(origin.getX(), floorY + 1, origin.getZ());
        final List<Lobe> lobes = this.createLobes(random, radiusX, radiusZ, height);
        final int sizeX = (radiusX + 3) * 2 + 1;
        final int sizeY = height + 4;
        final int sizeZ = (radiusZ + 3) * 2 + 1;
        final boolean[][][] mask = new boolean[sizeX][sizeY][sizeZ];

        for (int ix = 0; ix < sizeX; ix++) {
            final int dx = ix - (radiusX + 3);
            for (int iz = 0; iz < sizeZ; iz++) {
                final int dz = iz - (radiusZ + 3);
                for (int iy = 0; iy < sizeY; iy++) {
                    final float density = this.computeDensity(dx, iy, dz, height, lobes);
                    if (density > 1.0F) {
                        continue;
                    }
                    final float edgeNoise = this.sampleEdgeNoise(dx, iy, dz);
                    if (density + edgeNoise * 0.18F > 1.02F) {
                        continue;
                    }
                    mask[ix][iy][iz] = true;
                }
            }
        }

        this.erodeMask(mask);

        boolean placed = false;
        for (int ix = 0; ix < sizeX; ix++) {
            final int dx = ix - (radiusX + 3);
            for (int iz = 0; iz < sizeZ; iz++) {
                final int dz = iz - (radiusZ + 3);
                for (int iy = 0; iy < sizeY; iy++) {
                    if (!mask[ix][iy][iz]) {
                        continue;
                    }
                    final BlockPos pos = center.offset(dx, iy, dz);
                    if (level.isOutsideBuildHeight(pos)) {
                        continue;
                    }

                    if (!this.canReplace(level.getBlockState(pos))) {
                        continue;
                    }

                    this.setBlock(level, pos, this.sampleIce(random, pos, iy, height, config));
                    placed = true;
                }
            }
        }

        final int spireCount = 1 + random.nextInt(3);
        for (int i = 0; i < spireCount; i++) {
            final int dx = Mth.nextInt(random, -Math.max(1, radiusX / 2), Math.max(1, radiusX / 2));
            final int dz = Mth.nextInt(random, -Math.max(1, radiusZ / 2), Math.max(1, radiusZ / 2));
            final int startY = this.findTopSurface(level, center.getX() + dx, center.getY(), center.getZ() + dz, height + 3);
            if (startY >= 0) {
                placed |= this.placeSpire(level, random, new BlockPos(center.getX() + dx, startY + 1, center.getZ() + dz), random.nextInt(1, 4), config);
            }
        }

        return placed;
    }

    private List<Lobe> createLobes(final RandomSource random, final int radiusX, final int radiusZ, final int height) {
        final List<Lobe> lobes = new ArrayList<>();
        lobes.add(new Lobe(0.0F, height * 0.35F, 0.0F, radiusX + 0.9F, height * 0.72F, radiusZ + 0.9F));

        final int sideLobes = 2 + random.nextInt(3);
        for (int i = 0; i < sideLobes; i++) {
            final float offsetX = random.nextFloat() * radiusX * 1.2F - radiusX * 0.6F;
            final float offsetZ = random.nextFloat() * radiusZ * 1.2F - radiusZ * 0.6F;
            final float offsetY = random.nextFloat() * Math.max(1.5F, height * 0.35F);
            final float lobeRadiusX = Math.max(1.4F, radiusX * (0.45F + random.nextFloat() * 0.35F));
            final float lobeRadiusY = Math.max(1.6F, height * (0.28F + random.nextFloat() * 0.22F));
            final float lobeRadiusZ = Math.max(1.4F, radiusZ * (0.45F + random.nextFloat() * 0.35F));
            lobes.add(new Lobe(offsetX, offsetY, offsetZ, lobeRadiusX, lobeRadiusY, lobeRadiusZ));
        }

        return lobes;
    }

    private float computeDensity(final int dx, final int dy, final int dz, final int maxHeight, final List<Lobe> lobes) {
        float best = Float.MAX_VALUE;
        for (final Lobe lobe : lobes) {
            final float nx = (dx - lobe.offsetX()) / lobe.radiusX();
            final float ny = (dy - lobe.offsetY()) / lobe.radiusY();
            final float nz = (dz - lobe.offsetZ()) / lobe.radiusZ();
            best = Math.min(best, nx * nx + ny * ny + nz * nz);
        }
        final float normalizedHeight = dy / (float) Math.max(1, maxHeight);
        final float verticalWarp = normalizedHeight * normalizedHeight * 0.18F;
        final float baseBulge = (1.0F - normalizedHeight) * 0.08F;
        return best + verticalWarp - baseBulge;
    }

    private void erodeMask(final boolean[][][] mask) {
        final int maxX = mask.length;
        final int maxY = mask[0].length;
        final int maxZ = mask[0][0].length;
        for (int pass = 0; pass < 2; pass++) {
            final boolean[][][] next = new boolean[maxX][maxY][maxZ];
            for (int x = 0; x < maxX; x++) {
                for (int y = 0; y < maxY; y++) {
                    for (int z = 0; z < maxZ; z++) {
                        if (!mask[x][y][z]) {
                            continue;
                        }
                        final int neighbors = this.countNeighbors(mask, x, y, z);
                        final float noise = this.hashNoise(x * 3 + pass, y * 5 + pass, z * 7 + pass);
                        final int threshold = y <= 1 ? 2 : 3;
                        if (neighbors >= threshold || noise > 0.35F) {
                            next[x][y][z] = true;
                        }
                    }
                }
            }
            for (int x = 0; x < maxX; x++) {
                for (int y = 0; y < maxY; y++) {
                    System.arraycopy(next[x][y], 0, mask[x][y], 0, maxZ);
                }
            }
        }
    }

    private int countNeighbors(final boolean[][][] mask, final int x, final int y, final int z) {
        int neighbors = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    final int nx = x + dx;
                    final int ny = y + dy;
                    final int nz = z + dz;
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= mask.length || ny >= mask[0].length || nz >= mask[0][0].length) {
                        continue;
                    }
                    if (mask[nx][ny][nz]) {
                        neighbors++;
                    }
                }
            }
        }
        return neighbors;
    }

    private float sampleEdgeNoise(final int dx, final int dy, final int dz) {
        final float x = dx * 0.42F;
        final float y = dy * 0.35F;
        final float z = dz * 0.42F;
        final float broad = this.valueNoise(x, y, z);
        final float detail = this.valueNoise(x * 0.55F + 17.0F, y * 0.55F + 11.0F, z * 0.55F - 23.0F) * 0.25F;
        return broad * 0.75F + detail;
    }

    private float valueNoise(final float x, final float y, final float z) {
        final int x0 = Mth.floor(x);
        final int y0 = Mth.floor(y);
        final int z0 = Mth.floor(z);
        final float tx = x - x0;
        final float ty = y - y0;
        final float tz = z - z0;

        final float sx = this.smoothStep(tx);
        final float sy = this.smoothStep(ty);
        final float sz = this.smoothStep(tz);

        final float c000 = this.hashNoise(x0, y0, z0);
        final float c100 = this.hashNoise(x0 + 1, y0, z0);
        final float c010 = this.hashNoise(x0, y0 + 1, z0);
        final float c110 = this.hashNoise(x0 + 1, y0 + 1, z0);
        final float c001 = this.hashNoise(x0, y0, z0 + 1);
        final float c101 = this.hashNoise(x0 + 1, y0, z0 + 1);
        final float c011 = this.hashNoise(x0, y0 + 1, z0 + 1);
        final float c111 = this.hashNoise(x0 + 1, y0 + 1, z0 + 1);

        final float nx00 = Mth.lerp(sx, c000, c100);
        final float nx10 = Mth.lerp(sx, c010, c110);
        final float nx01 = Mth.lerp(sx, c001, c101);
        final float nx11 = Mth.lerp(sx, c011, c111);
        final float nxy0 = Mth.lerp(sy, nx00, nx10);
        final float nxy1 = Mth.lerp(sy, nx01, nx11);
        return Mth.lerp(sz, nxy0, nxy1);
    }

    private float smoothStep(final float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    private float hashNoise(final int x, final int y, final int z) {
        int hash = x * 73428767 ^ y * 912931 ^ z * 438289;
        hash = (hash << 13) ^ hash;
        return 1.0F - ((hash * (hash * hash * 15731 + 789221) + 1376312589) & 0x7fffffff) / 1073741824.0F;
    }

    private boolean placeSpire(
            final WorldGenLevel level,
            final RandomSource random,
            final BlockPos start,
            final int height,
            final Configuration config
    ) {
        boolean placed = false;
        for (int i = 0; i < height; i++) {
            final BlockPos pos = start.above(i);
            if (level.isOutsideBuildHeight(pos)) {
                break;
            }

            if (!this.canReplace(level.getBlockState(pos))) {
                break;
            }

            this.setBlock(level, pos, this.sampleIce(random, pos, i, height, config));
            placed = true;
        }
        return placed;
    }

    private int findTopSurface(final WorldGenLevel level, final int x, final int startY, final int z, final int searchHeight) {
        int top = -1;
        for (int y = startY; y <= startY + searchHeight; y++) {
            final BlockPos pos = new BlockPos(x, y, z);
            if (level.isOutsideBuildHeight(pos)) {
                break;
            }

            final BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.is(Blocks.WATER) && !state.is(Blocks.SNOW)) {
                top = y;
            }
        }
        return top;
    }

    private BlockState sampleIce(final RandomSource random, final BlockPos pos, final int dy, final int height, final Configuration config) {
        if (dy == height && random.nextFloat() < 0.55F) {
            return config.topStateProvider().getState(random, pos);
        }
        return config.stateProvider().getState(random, pos);
    }

    private Integer findFloor(final WorldGenLevel level, final int x, final int startY, final int z) {
        return this.findSurface(level, x, startY, z, -1, FLOOR_SEARCH_RANGE);
    }

    private Integer findSurface(final WorldGenLevel level, final int x, final int startY, final int z, final int step, final int maxDistance) {
        for (int offset = 0; offset <= maxDistance; offset++) {
            final BlockPos pos = new BlockPos(x, startY + offset * step, z);
            if (level.isOutsideBuildHeight(pos)) {
                continue;
            }
            if (!this.canReplace(level.getBlockState(pos))) {
                return pos.getY();
            }
        }
        return null;
    }

    private boolean canReplace(final BlockState state) {
        return state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.SNOW);
    }

    public record Configuration(BlockStateProvider stateProvider, BlockStateProvider topStateProvider) implements FeatureConfiguration {
        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockStateProvider.CODEC.fieldOf("state_provider").forGetter(Configuration::stateProvider),
                BlockStateProvider.CODEC.fieldOf("top_state_provider").forGetter(Configuration::topStateProvider)
        ).apply(instance, Configuration::new));
    }

    private record Lobe(float offsetX, float offsetY, float offsetZ, float radiusX, float radiusY, float radiusZ) {
    }
}
