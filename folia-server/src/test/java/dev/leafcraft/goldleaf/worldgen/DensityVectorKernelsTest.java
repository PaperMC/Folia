package dev.leafcraft.goldleaf.worldgen;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity tests for {@link DensityVectorKernels} against scalar vanilla reference implementations.
 *
 * @governedBy goldleaf-ADR-004
 * @trace S-004, S-005
 */
class DensityVectorKernelsTest {

    private static final float FLOAT_PARITY_TOLERANCE = 1.0E-5F;

    @Test
    void interpolateBlockStepMatchesVanillaOnAlignedOverworldChunk() {
        assertInterpolationParity(new DensityVolume(16, 384, 16, 128, -64, -256), 4, 8, 101L);
    }

    @Test
    void interpolateBlockStepMatchesVanillaOnAlignedFourByFourCells() {
        assertInterpolationParity(new DensityVolume(16, 128, 16, -64, 0, 64), 4, 4, 202L);
    }

    @Test
    void interpolateBlockStepMatchesVanillaOnUnalignedClippedVolume() {
        assertInterpolationParity(new DensityVolume(11, 23, 13, -3, -5, 7), 4, 8, 303L);
        assertInterpolationParity(new DensityVolume(9, 17, 7, 2, -11, -9), 3, 5, 404L);
    }

    @Test
    void perlinAddToVolumeMatchesPointEvaluationAcrossFrequenciesAndNegativeCoords() {
        final PerlinNoise noise = new PerlinNoise(new XoroshiroRandomSource(123456789L));
        final DensityVolume volume = new DensityVolume(16, 128, 16, -512, -64, -256);

        final double[][] scales = {
            {1.0 / 64.0, 1.0 / 64.0},
            {1.0 / 8.0, 1.0 / 16.0},
            {0.37, 0.73},
            {1.5, 1.25}
        };

        for (final double[] pair : scales) {
            final double xzScale = pair[0];
            final double yScale = pair[1];
            final float amplitude = 0.625F;

            final DensityBuffer buf = DensityBuffer.createUnpooled(volume.size());
            buf.fill(0.25F);
            noise.addToVolume(buf, volume, xzScale, yScale, amplitude);

            float maxDiff = 0.0F;
            int idx = 0;
            for (int z = 0; z < volume.sizeZ(); z++) {
                final double wz = volume.blockZ(z) * xzScale;
                for (int x = 0; x < volume.sizeX(); x++) {
                    final double wx = volume.blockX(x) * xzScale;
                    for (int y = 0; y < volume.sizeY(); y++) {
                        final double wy = volume.blockY(y) * yScale;
                        final float expected = 0.25F + amplitude * noise.get(wx, wy, wz);
                        final float actual = buf.get(idx++);
                        assertTrue(Float.isFinite(actual), "Non-finite Perlin value at idx=" + (idx - 1));
                        maxDiff = Math.max(maxDiff, Math.abs(expected - actual));
                    }
                }
            }
            assertTrue(maxDiff <= FLOAT_PARITY_TOLERANCE,
                    "PerlinNoise max diff " + maxDiff + " exceeded tolerance for scales " + xzScale + "," + yScale);
        }
    }

    @Test
    void smearedPerlinAddToVolumeMatchesPointEvaluation() {
        final SmearedPerlinNoise noise = new SmearedPerlinNoise(new XoroshiroRandomSource(987654321L), 0.125);
        final DensityVolume volume = new DensityVolume(16, 96, 16, -128, -32, 256);
        final double xzScale = 1.0 / 16.0;
        final double yScale = 1.0 / 24.0;
        final float amplitude = 1.35F;

        final DensityBuffer buf = DensityBuffer.createUnpooled(volume.size());
        buf.fill(-0.5F);
        noise.addToVolume(buf, volume, xzScale, yScale, amplitude);

        float maxDiff = 0.0F;
        int idx = 0;
        for (int z = 0; z < volume.sizeZ(); z++) {
            final double wz = volume.blockZ(z) * xzScale;
            for (int x = 0; x < volume.sizeX(); x++) {
                final double wx = volume.blockX(x) * xzScale;
                for (int y = 0; y < volume.sizeY(); y++) {
                    final double wy = volume.blockY(y) * yScale;
                    final float expected = -0.5F + amplitude * noise.get(wx, wy, wz);
                    final float actual = buf.get(idx++);
                    assertTrue(Float.isFinite(actual), "Non-finite SmearedPerlin value at idx=" + (idx - 1));
                    maxDiff = Math.max(maxDiff, Math.abs(expected - actual));
                }
            }
        }
        assertTrue(maxDiff <= FLOAT_PARITY_TOLERANCE, "SmearedPerlinNoise max diff " + maxDiff);
    }

    @Test
    void binaryKernelsMatchScalarReferenceBitForBit() {
        final int n = 1024;
        final Random rng = new Random(777L);
        final float[] a = new float[n];
        final float[] b = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = (rng.nextFloat() - 0.5F) * 20.0F;
            b[i] = (rng.nextFloat() - 0.5F) * 20.0F;
        }
        final float c = -3.25F;

        final float[] out = a.clone();
        final float[] exp = a.clone();
        DensityVectorKernels.add(out, b, n);
        for (int i = 0; i < n; i++) exp[i] += b[i];
        assertArrayEquals(exp, out);

        System.arraycopy(a, 0, out, 0, n);
        System.arraycopy(a, 0, exp, 0, n);
        DensityVectorKernels.constAdd(out, c, n);
        for (int i = 0; i < n; i++) exp[i] += c;
        assertArrayEquals(exp, out);

        System.arraycopy(a, 0, out, 0, n);
        System.arraycopy(a, 0, exp, 0, n);
        DensityVectorKernels.sub(out, b, n);
        for (int i = 0; i < n; i++) exp[i] -= b[i];
        assertArrayEquals(exp, out);

        System.arraycopy(a, 0, out, 0, n);
        System.arraycopy(a, 0, exp, 0, n);
        DensityVectorKernels.constSub(out, c, n);
        for (int i = 0; i < n; i++) exp[i] = c - exp[i];
        assertArrayEquals(exp, out);

        System.arraycopy(a, 0, out, 0, n);
        System.arraycopy(a, 0, exp, 0, n);
        DensityVectorKernels.mul(out, b, n);
        for (int i = 0; i < n; i++) exp[i] *= b[i];
        assertArrayEquals(exp, out);

        System.arraycopy(a, 0, out, 0, n);
        System.arraycopy(a, 0, exp, 0, n);
        DensityVectorKernels.constMul(out, c, n);
        for (int i = 0; i < n; i++) exp[i] *= c;
        assertArrayEquals(exp, out);

        System.arraycopy(a, 0, out, 0, n);
        System.arraycopy(a, 0, exp, 0, n);
        DensityVectorKernels.min(out, b, n);
        for (int i = 0; i < n; i++) exp[i] = Math.min(exp[i], b[i]);
        assertArrayEquals(exp, out);

        System.arraycopy(a, 0, out, 0, n);
        System.arraycopy(a, 0, exp, 0, n);
        DensityVectorKernels.max(out, b, n);
        for (int i = 0; i < n; i++) exp[i] = Math.max(exp[i], b[i]);
        assertArrayEquals(exp, out);

        System.arraycopy(a, 0, out, 0, n);
        System.arraycopy(a, 0, exp, 0, n);
        DensityVectorKernels.constMin(out, c, n);
        for (int i = 0; i < n; i++) exp[i] = Math.min(exp[i], c);
        assertArrayEquals(exp, out);

        System.arraycopy(a, 0, out, 0, n);
        System.arraycopy(a, 0, exp, 0, n);
        DensityVectorKernels.constMax(out, c, n);
        for (int i = 0; i < n; i++) exp[i] = Math.max(exp[i], c);
        assertArrayEquals(exp, out);
    }

    private static void assertInterpolationParity(
            final DensityVolume volume,
            final int cellSizeXz,
            final int cellSizeY,
            final long seed
    ) {
        final int minCellX = Mth.floorDiv(volume.minBlockX(), cellSizeXz);
        final int minCellY = Mth.floorDiv(volume.minBlockY(), cellSizeY);
        final int minCellZ = Mth.floorDiv(volume.minBlockZ(), cellSizeXz);
        final int maxCellX = Mth.floorDiv(volume.maxBlockX(), cellSizeXz);
        final int maxCellY = Mth.floorDiv(volume.maxBlockY(), cellSizeY);
        final int maxCellZ = Mth.floorDiv(volume.maxBlockZ(), cellSizeXz);
        final int cellCountX = maxCellX - minCellX + 1;
        final int cellCountY = maxCellY - minCellY + 1;
        final int cellCountZ = maxCellZ - minCellZ + 1;

        final DensityVolume cellVolume = new DensityVolume(
                Math.floorMod(volume.maxBlockX(), cellSizeXz) == 0 ? cellCountX : cellCountX + 1,
                Math.floorMod(volume.maxBlockY(), cellSizeY) == 0 ? cellCountY : cellCountY + 1,
                Math.floorMod(volume.maxBlockZ(), cellSizeXz) == 0 ? cellCountZ : cellCountZ + 1,
                minCellX * cellSizeXz,
                minCellY * cellSizeY,
                minCellZ * cellSizeXz,
                cellSizeXz,
                cellSizeY,
                cellSizeXz
        );

        final float[] cellValues = new float[cellVolume.size()];
        final Random rng = new Random(seed);
        for (int i = 0; i < cellValues.length; i++) {
            cellValues[i] = (rng.nextFloat() - 0.5F) * 8.0F;
        }

        final float cellSizeXzInv = 1.0F / cellSizeXz;
        final float cellSizeYInv = 1.0F / cellSizeY;

        final float[] actual = new float[volume.size()];
        DensityVectorKernels.interpolateBlockStep(
                cellValues, cellVolume, actual, volume,
                cellCountX, cellCountY, cellCountZ,
                cellSizeXz, cellSizeY, cellSizeXzInv, cellSizeYInv
        );

        final float[] expected = new float[volume.size()];
        for (int cellZ = 0; cellZ < cellCountZ; cellZ++) {
            final int nextCellZ = Math.min(cellZ + 1, cellVolume.sizeZ() - 1);
            for (int cellX = 0; cellX < cellCountX; cellX++) {
                final int nextCellX = Math.min(cellX + 1, cellVolume.sizeX() - 1);
                float v000 = cellValues[cellVolume.indexUnchecked(cellX, 0, cellZ)];
                float v100 = cellValues[cellVolume.indexUnchecked(nextCellX, 0, cellZ)];
                float v001 = cellValues[cellVolume.indexUnchecked(cellX, 0, nextCellZ)];
                float v101 = cellValues[cellVolume.indexUnchecked(nextCellX, 0, nextCellZ)];
                for (int cellY = 0; cellY < cellCountY; cellY++) {
                    final int nextCellY = Math.min(cellY + 1, cellVolume.sizeY() - 1);
                    final float v010 = cellValues[cellVolume.indexUnchecked(cellX, nextCellY, cellZ)];
                    final float v110 = cellValues[cellVolume.indexUnchecked(nextCellX, nextCellY, cellZ)];
                    final float v011 = cellValues[cellVolume.indexUnchecked(cellX, nextCellY, nextCellZ)];
                    final float v111 = cellValues[cellVolume.indexUnchecked(nextCellX, nextCellY, nextCellZ)];

                    final int cellOutputX = cellVolume.blockX(cellX) - volume.minBlockX();
                    final int cellOutputY = cellVolume.blockY(cellY) - volume.minBlockY();
                    final int cellOutputZ = cellVolume.blockZ(cellZ) - volume.minBlockZ();
                    final int x0 = Math.max(0, -cellOutputX);
                    final int y0 = Math.max(0, -cellOutputY);
                    final int z0 = Math.max(0, -cellOutputZ);
                    final int x1 = Math.min(cellSizeXz, volume.sizeX() - cellOutputX) - 1;
                    final int y1 = Math.min(cellSizeY, volume.sizeY() - cellOutputY) - 1;
                    final int z1 = Math.min(cellSizeXz, volume.sizeZ() - cellOutputZ) - 1;

                    for (int z = z0; z <= z1; z++) {
                        final int outputZ = cellOutputZ + z;
                        final float alphaZ = z * cellSizeXzInv;
                        final float v00_ = Mth.lerp(alphaZ, v000, v001);
                        final float v01_ = Mth.lerp(alphaZ, v010, v011);
                        final float v10_ = Mth.lerp(alphaZ, v100, v101);
                        final float v11_ = Mth.lerp(alphaZ, v110, v111);
                        for (int x = x0; x <= x1; x++) {
                            final int outputX = cellOutputX + x;
                            final float alphaX = x * cellSizeXzInv;
                            final float v_0_ = Mth.lerp(alphaX, v00_, v10_);
                            final float v_1_ = Mth.lerp(alphaX, v01_, v11_);
                            final float valueStep = (v_1_ - v_0_) * cellSizeYInv;
                            float value = v_0_ + valueStep * y0;
                            int outputIndex = volume.indexUnchecked(outputX, cellOutputY + y0, outputZ);
                            for (int y = y0; y <= y1; y++) {
                                expected[outputIndex++] = value;
                                value += valueStep;
                            }
                        }
                    }

                    v000 = v010;
                    v100 = v110;
                    v001 = v011;
                    v101 = v111;
                }
            }
        }

        float maxDiff = 0.0F;
        for (int i = 0; i < expected.length; i++) {
            assertTrue(Float.isFinite(actual[i]), "Non-finite interpolated value at " + i);
            maxDiff = Math.max(maxDiff, Math.abs(expected[i] - actual[i]));
        }
        assertEquals(0.0F, maxDiff, FLOAT_PARITY_TOLERANCE, "Interpolation max diff exceeded tolerance");
    }
}
