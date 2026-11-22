package dev.leafcraft.goldleaf.worldgen;

import dev.leafcraft.goldleaf.storage.LcaChunkResidencyCache;
import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctionCompiler;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.TilingMode;
import net.minecraft.world.level.levelgen.densityfunction.generator.EndIslandFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.GradientFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.ShiftNoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.SimpleDensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BlendDensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.CacheFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.SplineFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct Gate 1 parity, JAR-pack, and disk-cache tests for {@link IrOpenClTerrainAccelerator},
 * {@link OpenClKernelCache}, and {@link GoldLeafOpenClBridge} ({@code goldleaf-ADR-004}).
 */
class IrOpenClTerrainAcceleratorTest {

    /**
     * Pure static 3D density function representing a terrain column + harmonic ridge field.
     */
    public static final class SyntheticTerrainKernel {
        public static double sampleDensity(final int bx, final int by, final int bz) {
            final double x = bx * 0.015625;
            final double y = by * 0.03125;
            final double z = bz * 0.015625;
            final double continent = Math.sin(x) * Math.cos(z) * 1.25;
            final double ridges = Math.abs(Math.sin(x * 2.0 + z * 1.5)) * 0.4;
            final double heightBias = (64.0 - by) * 0.02;
            return continent + ridges + heightBias - Math.cos(y) * 0.15;
        }
    }

    /**
     * Stateful live object-graph density evaluator with instance-captured lookup table and weights
     * (mirrors live MC 26.3 DensityFunction / NoiseRouter object graphs).
     */
    public static final class StatefulOctaveDensity {
        private final double[] permBias;
        private final double amplitude;
        private final double seaLevel;

        public StatefulOctaveDensity(final double amplitude, final double seaLevel) {
            this.amplitude = amplitude;
            this.seaLevel = seaLevel;
            this.permBias = new double[64];
            for (int i = 0; i < this.permBias.length; i++) {
                this.permBias[i] = ((i * 37) % 64) / 64.0 - 0.5;
            }
        }

        public double compute(final int bx, final int by, final int bz) {
            final int h = ((bx * 73856093) ^ (bz * 19349663) ^ (by * 83492791)) & 63;
            final double lutTerm = this.permBias[h] * this.amplitude;
            final double vertical = (this.seaLevel - by) * 0.025;
            return lutTerm + vertical + Math.sin(bx * 0.02) * Math.cos(bz * 0.02) * 0.5;
        }
    }

    @Test
    void liftsStaticKernelAndMatchesCpuParityAcrossMultiChunkDensityVolume(@TempDir final Path tempDir) throws Exception {
        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("cache"))) {
            final OpenClBackendEmitter.DensityVolumeProgram progExact = accel.liftStaticMethodProgram(
                SyntheticTerrainKernel.class,
                "sampleDensity",
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertEquals("evaluate_density_volume_batch_f32", progExact.kernelName());
            assertTrue(progExact.sourceCode().contains("__kernel void evaluate_density_volume_batch_f32("));
            assertEquals(64, progExact.kernelDigestSha256().length());

            if (!accel.isGpuReady()) {
                return;
            }

            final IrOpenClTerrainAccelerator.CompiledDensityKernel compiled = accel.compileProgramAsync(
                progExact,
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            ).get(30, TimeUnit.SECONDS);
            assertTrue(compiled.hasLiveGpuKernel(), "Expected live GPU kernel on OpenCL-capable host");

            final int batchCount = 4;
            final int sizeX = 5;
            final int sizeY = 49;
            final int sizeZ = 5;
            final int stepX = 4;
            final int stepY = 8;
            final int stepZ = 4;
            final int volumeSize = sizeX * sizeY * sizeZ;
            final int[] origins = {
                0, -64, 0, 0,
                16, -64, 0, 0,
                -32, -64, 48, 0,
                1024, -64, -2048, 0
            };
            final float[] gpuOut = new float[batchCount * volumeSize];
            final IrOpenClTerrainAccelerator.DispatchStats stats = accel.executeDensityVolumeBatch(
                compiled,
                origins,
                batchCount,
                sizeX,
                sizeY,
                sizeZ,
                stepX,
                stepY,
                stepZ,
                gpuOut
            );
            assertTrue(stats.gpuKernelNanos() >= 0L);

            for (int b = 0; b < batchCount; b++) {
                final int ox = origins[b * 4];
                final int oy = origins[b * 4 + 1];
                final int oz = origins[b * 4 + 2];
                for (int lz = 0; lz < sizeZ; lz++) {
                    for (int lx = 0; lx < sizeX; lx++) {
                        for (int ly = 0; ly < sizeY; ly++) {
                            final int idx = b * volumeSize + (lx + lz * sizeX) * sizeY + ly;
                            final float expected = (float) SyntheticTerrainKernel.sampleDensity(
                                ox + lx * stepX,
                                oy + ly * stepY,
                                oz + lz * stepZ
                            );
                            assertEquals(expected, gpuOut[idx], 1e-5f,
                                "GPU vs CPU DensityVolume mismatch at b=" + b + " (" + lx + "," + ly + "," + lz + ")");
                        }
                    }
                }
            }
        }
    }

    @Test
    void liftsLiveStatefulInstanceGraphAndMatchesGpuParity(@TempDir final Path tempDir) {
        final StatefulOctaveDensity liveDensity = new StatefulOctaveDensity(0.85, 63.0);
        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("live-cache"))) {
            final OpenClBackendEmitter.DensityVolumeProgram program = accel.liftLiveInstanceProgram(
                liveDensity,
                "compute",
                "(III)D",
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertTrue(program.sourceCode().contains("__kernel void evaluate_density_volume_batch_f32("));

            if (!accel.isGpuReady()) {
                return;
            }

            final IrOpenClTerrainAccelerator.CompiledDensityKernel compiled = accel.compileProgramBlocking(
                program,
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertTrue(compiled.hasLiveGpuKernel());

            final int[] origins = { 64, -32, -128, 0, -256, 0, 512, 0 };
            final int sizeX = 5;
            final int sizeY = 17;
            final int sizeZ = 5;
            final int volumeSize = sizeX * sizeY * sizeZ;
            final float[] gpuOut = new float[2 * volumeSize];
            accel.executeDensityVolumeBatch(compiled, origins, 2, sizeX, sizeY, sizeZ, 4, 8, 4, gpuOut);

            for (int b = 0; b < 2; b++) {
                final int ox = origins[b * 4];
                final int oy = origins[b * 4 + 1];
                final int oz = origins[b * 4 + 2];
                for (int lz = 0; lz < sizeZ; lz++) {
                    for (int lx = 0; lx < sizeX; lx++) {
                        for (int ly = 0; ly < sizeY; ly++) {
                            final int idx = b * volumeSize + (lx + lz * sizeX) * sizeY + ly;
                            final float expected = (float) liveDensity.compute(
                                ox + lx * 4,
                                oy + ly * 8,
                                oz + lz * 4
                            );
                            assertEquals(expected, gpuOut[idx], 1e-5f, "Live-instance GPU parity drift at idx=" + idx);
                        }
                    }
                }
            }
        }
    }

    @Test
    void persistsBinaryAcrossBootsRecoversCorruptBinaryAndLoadsJarPackedKernel(@TempDir final Path tempDir) throws Exception {
        final Path cacheDir = tempDir.resolve("disk-cache");
        final OpenClBackendEmitter.DensityVolumeProgram program;
        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(cacheDir)) {
            program = accel.liftStaticMethodProgram(
                SyntheticTerrainKernel.class,
                "sampleDensity",
                TerrainGenerationAccelerator.ParityMode.FAST
            );
        }

        // 1. Verify JAR-packed resource packing + ClassLoader loading works even without a GPU
        final Path jarRoot = tempDir.resolve("fake-jar");
        final Path kernelsDir = jarRoot.resolve(OpenClKernelCache.PACKED_KERNEL_RESOURCE_PREFIX);
        final Path packedFile = OpenClKernelCache.packKernelToDirectory(kernelsDir, program.kernelDigestSha256(), program);
        assertTrue(Files.isRegularFile(packedFile));

        try (URLClassLoader jarLoader = new URLClassLoader(new URL[] { jarRoot.toUri().toURL() }, null)) {
            // 2. Cold boot: loads packed .cl from JAR, compiles on GPU, persists .cl + .clbin to disk
            try (OpenClKernelCache coldCache = new OpenClKernelCache(cacheDir, jarLoader)) {
                final var packed = coldCache.loadPackedSource(program.kernelDigestSha256());
                assertTrue(packed.isPresent(), "Expected packed .cl kernel to load from META-INF/goldleaf/kernels/");
                assertEquals(program.sourceCode(), packed.get().sourceCode());

                if (!GoldLeafOpenClBridge.isAvailable()) {
                    return;
                }

                final GoldLeafOpenClBridge.CompiledKernel k1 = coldCache.getOrCompile(0, program, "-cl-std=CL1.2");
                assertNotNull(k1, "Expected cold GPU compile from packed .cl to succeed");
                assertEquals(1L, coldCache.sourceCompiles());
                assertEquals(0L, coldCache.diskBinaryHits());
                assertTrue(Files.isRegularFile(coldCache.sourceCachePath(program.kernelDigestSha256())));
                final String devSig = GoldLeafOpenClBridge.deviceSignature(0);
                assertNotNull(devSig);
                final Path binPath = coldCache.binaryCachePath(devSig, "-cl-std=CL1.2", program.kernelDigestSha256());
                assertTrue(Files.isRegularFile(binPath), "Expected exported CL_PROGRAM_BINARIES .clbin file on disk");
                assertTrue(Files.size(binPath) > 0L);
            }

            // 3. Warm boot (brand-new OpenClKernelCache): loads directly from .clbin without source compile
            try (OpenClKernelCache warmCache = new OpenClKernelCache(cacheDir, jarLoader)) {
                final GoldLeafOpenClBridge.CompiledKernel k2 = warmCache.getOrCompile(0, program, "-cl-std=CL1.2");
                assertNotNull(k2, "Expected warm GPU load from cached .clbin to succeed");
                assertEquals(1L, warmCache.diskBinaryHits(), "Expected warm boot to hit .clbin disk cache");
                assertEquals(0L, warmCache.sourceCompiles(), "Warm boot must not recompile from .cl source");

                final int[] origins = { 0, 0, 0, 0 };
                final float[] out = new float[8];
                assertEquals(0, k2.dispatchDensityVolumeBatch(origins, 1, 2, 2, 2, 1, 1, 1, out, null));
                for (int lz = 0; lz < 2; lz++) {
                    for (int lx = 0; lx < 2; lx++) {
                        for (int ly = 0; ly < 2; ly++) {
                            final int idx = (lx + lz * 2) * 2 + ly;
                            assertEquals((float) SyntheticTerrainKernel.sampleDensity(lx, ly, lz), out[idx], 1e-4f);
                        }
                    }
                }
            }

            // 4. Corrupt .clbin recovery (CL_INVALID_BINARY -> warn + delete + recompile from packed .cl)
            final String devSig = GoldLeafOpenClBridge.deviceSignature(0);
            assertNotNull(devSig);
            final Path binPath = new OpenClKernelCache(cacheDir, jarLoader)
                .binaryCachePath(devSig, "-cl-std=CL1.2", program.kernelDigestSha256());
            Files.writeString(binPath, "CORRUPT-NOT-AN-OPENCL-BINARY", StandardCharsets.UTF_8);

            try (OpenClKernelCache recoveryCache = new OpenClKernelCache(cacheDir, jarLoader)) {
                final GoldLeafOpenClBridge.CompiledKernel k3 = recoveryCache.getOrCompile(0, program, "-cl-std=CL1.2");
                assertNotNull(k3, "Expected transparent recompile after corrupt .clbin rejection");
                assertEquals(1L, recoveryCache.binaryRejections(), "Expected corrupt .clbin to trigger binary rejection counter");
                assertEquals(1L, recoveryCache.sourceCompiles(), "Expected fallback source recompile after binary rejection");
            }
        }
    }

    @Test
    void lowersRealMc263DensityFunctionAstAndMatchesMojangSamplerParity(@TempDir final Path tempDir) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        final Holder<NormalNoise> terrainNoise = Holder.direct(NormalNoise.createParity(-5, 1.0, 0.5, 0.25, 0.125));
        final Holder<NormalNoise> offsetNoise = Holder.direct(NormalNoise.createParity(-3, 1.0, 0.5));
        final DensityFunction.CompileContext compileCtx = new DensityFunction.CompileContext() {
            @Override
            public Noise createNoiseSampler(final Holder<NormalNoise> parameters) {
                return parameters.value().create(new XoroshiroRandomSource(123456789L ^ parameters.value().hashCode()));
            }

            @Override
            public RandomSource createRandom(final Identifier seed) {
                return new XoroshiroRandomSource(seed.hashCode());
            }

            @Override
            public RandomSource createEndIslandRandom() {
                return new XoroshiroRandomSource(42L);
            }
        };

        // Realistic MC 26.3 DensityFunction DAG: shared Noise + ShiftA + Y Gradient + Spline + RangeChoice + Interpolated
        final DensityFunction shiftX = new ShiftNoiseFunction.ShiftA(offsetNoise);
        final DensityFunction sharedBase = new CacheFunction(
            new NoiseFunction(terrainNoise, 0.015625, 0.03125, shiftX, DensityFunctions.zero(), DensityFunctions.zero())
        );
        final DensityFunction yGrad = new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 192, 1.5f, -1.5f);
        final CubicSpline<SplineFunction.Coordinate> spline = CubicSpline.builder(new SplineFunction.Coordinate(sharedBase))
            .addPoint(-1.0f, -0.4f, 0.2f)
            .addPoint(0.0f, 0.15f, 0.6f)
            .addPoint(1.0f, 0.85f, 0.1f)
            .build();
        final DensityFunction sculpted = new RangeChoiceFunction(
            sharedBase,
            -0.25f,
            0.75f,
            sharedBase.squeeze().add(new SplineFunction(spline)),
            sharedBase.abs().halfNegative()
        );
        final DensityFunction blendedBase = new BlendedNoise(0.25, 0.125, 80.0, 160.0, 8.0);
        final DensityFunction root = new InterpolatedFunction(
            new BlendDensityFunction(sculpted.add(yGrad).add(blendedBase).clamp(-2.0f, 2.0f)),
            4,
            8
        ).add(SimpleDensityFunction.BEARDIFIER);

        // Verify fail-closed behavior on unsupported EndIslandFunction
        final DensityFunction unsupported = new EndIslandFunction();
        assertFalse(DensityFunctionIrLowerer.lower(unsupported, compileCtx, "UnsupportedTest", true).isSupported());

        final DensityFunctionIrLowerer.LoweringResult lowered = DensityFunctionIrLowerer.lower(
            root,
            compileCtx,
            "Mc263OverworldAst",
            true
        );
        assertTrue(lowered.isSupported(), "Expected MC 26.3 DensityFunction AST to lower cleanly: " + lowered.unsupportedReason());
        assertTrue(lowered.deduplicatedNodes() >= 1, "Expected shared DAG nodes to be deduplicated via CSE");
        assertTrue(lowered.noiseOctaves() >= 12, "Expected 8 terrain + 4 offset Perlin octaves, got " + lowered.noiseOctaves());

        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("ast-cache"))) {
            assertNull(accel.lowerDensityFunctionProgram(
                unsupported,
                compileCtx,
                "UnsupportedTest",
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT,
                true
            ));

            final OpenClBackendEmitter.DensityVolumeProgram prog = accel.lowerDensityFunctionProgram(
                root,
                compileCtx,
                "Mc263OverworldAst",
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT,
                true
            );
            assertNotNull(prog);
            assertTrue(prog.sourceCode().contains("const int gid = (int)get_global_id(0);"));
            assertTrue(prog.sourceCode().contains("gl_perlin_3d"));

            if (!accel.isGpuReady()) {
                return;
            }

            final IrOpenClTerrainAccelerator.CompiledDensityKernel compiled = accel.compileProgramBlocking(
                prog,
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertTrue(compiled.hasLiveGpuKernel());

            final int batchCount = 4;
            final int sizeX = 5;
            final int sizeY = 49;
            final int sizeZ = 5;
            final int stepX = 4;
            final int stepY = 8;
            final int stepZ = 4;
            final int volumeSize = sizeX * sizeY * sizeZ;
            final int[] origins = {
                0, -64, 0, 0,
                16, -64, 0, 0,
                -32, -64, 48, 0,
                256, -64, -512, 0
            };
            final float[] gpuOut = new float[batchCount * volumeSize];
            accel.executeDensityVolumeBatch(compiled, origins, batchCount, sizeX, sizeY, sizeZ, stepX, stepY, stepZ, gpuOut);

            // Compare 1:1 against Mojang's own DensityFunctionCompiler + DensitySampler#sampleVolume
            final DensityFunctionCompiler mojangCompiler = new DensityFunctionCompiler(compileCtx);
            final DensitySampler mojangSampler = mojangCompiler.getSampler(root);
            final SamplerContext samplerCtx = SamplerContext.builder().enableCaches().build();
            for (int b = 0; b < batchCount; b++) {
                final DensityVolume vol = new DensityVolume(
                    sizeX, sizeY, sizeZ,
                    origins[b * 4], origins[b * 4 + 1], origins[b * 4 + 2],
                    stepX, stepY, stepZ
                );
                try (ScopedDensityBuffer cpuBuf = samplerCtx.acquireBuffer(vol)) {
                    mojangSampler.sampleVolume(samplerCtx, cpuBuf, vol);
                    final int base = b * volumeSize;
                    for (int i = 0; i < volumeSize; i++) {
                        assertEquals(
                            cpuBuf.get(i),
                            gpuOut[base + i],
                            1e-4f,
                            "MC 26.3 AST GPU parity drift at chunk=" + b + " idx=" + i
                        );
                    }
                }
            }
        }
    }

    @Test
    void executesEndToEndGpuSectionFillWithCompactDmaWritebackAndMatchesMojangParity(@TempDir final Path tempDir) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        final Holder<NormalNoise> terrainNoise = Holder.direct(NormalNoise.createParity(-5, 1.0, 0.5, 0.25, 0.125));
        final DensityFunction.CompileContext compileCtx = new DensityFunction.CompileContext() {
            @Override
            public Noise createNoiseSampler(final Holder<NormalNoise> parameters) {
                return parameters.value().create(new XoroshiroRandomSource(1337L));
            }

            @Override
            public RandomSource createRandom(final Identifier seed) {
                return new XoroshiroRandomSource(seed.hashCode());
            }

            @Override
            public RandomSource createEndIslandRandom() {
                return new XoroshiroRandomSource(42L);
            }
        };

        // Full Overworld height (-64..320, 24 sections): guarantees uniform sky AIR sections,
        // mixed surface/sea-level/cave sections, and deep lava/stone sections.
        final DensityFunction noise = new NoiseFunction(
            terrainNoise,
            0.015625,
            0.03125,
            DensityFunctions.zero(),
            DensityFunctions.zero(),
            DensityFunctions.zero()
        );
        final DensityFunction yGrad = new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 160, 2.0f, -2.0f);
        final DensityFunction root = noise.add(yGrad).clamp(-2.0f, 2.0f);

        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("compact-fill-cache"))) {
            final OpenClBackendEmitter.DensityVolumeProgram prog = accel.lowerSectionPaletteFillProgram(
                root,
                compileCtx,
                "Mc263CompactFill",
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT,
                false
            );
            assertNotNull(prog);
            assertEquals("fill_section_palette_batch_u2", prog.kernelName());
            assertTrue(prog.sourceCode().contains("__kernel void fill_section_palette_batch_u2("));

            if (!accel.isGpuReady()) {
                return;
            }

            final IrOpenClTerrainAccelerator.CompiledDensityKernel compiled = accel.compileProgramBlocking(
                prog,
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertTrue(compiled.hasLiveGpuKernel());

            final int batchCount = 2;
            final int sectionCount = 24;
            final int minBlockY = -64;
            final int seaLevel = 63;
            final int deepFluidY = -54;
            final int[] origins = {
                0, minBlockY, 0, 0,
                32, minBlockY, -16, 0
            };

            final CompactSectionWritebackBuffer dmaBuf = new CompactSectionWritebackBuffer(batchCount * sectionCount);
            final IrOpenClTerrainAccelerator.DispatchStats stats = accel.executeSectionPaletteFillBatch(
                compiled,
                origins,
                batchCount,
                sectionCount,
                seaLevel,
                deepFluidY,
                dmaBuf
            );
            assertTrue(stats.gpuKernelNanos() >= 0L);

            final DensityFunctionCompiler mojangCompiler = new DensityFunctionCompiler(compileCtx);
            final DensitySampler mojangSampler = mojangCompiler.getSampler(root);
            final SamplerContext samplerCtx = SamplerContext.builder().enableCaches().build();
            final Integer[] palette4 = {0, 1, 2, 3};

            for (int b = 0; b < batchCount; b++) {
                final int ox = origins[b * 4];
                final int oz = origins[b * 4 + 2];

                // 1. Apply GPU compact DMA writeback into virtual sections + heightmaps
                final int[][] gpuSections = new int[sectionCount][4096];
                final int[] gpuWorldSurface = new int[256];
                final int[] gpuOceanFloor = new int[256];
                java.util.Arrays.fill(gpuWorldSurface, minBlockY);
                java.util.Arrays.fill(gpuOceanFloor, minBlockY);

                final CompactSectionWritebackApplier.ApplyStats applyStats = CompactSectionWritebackApplier.applySections(
                    dmaBuf,
                    b,
                    sectionCount,
                    minBlockY,
                    offset -> gpuSections[offset],
                    palette4,
                    (sec, lx, ly, lz, blockY, code) -> sec[(ly << 8) | (lz << 4) | lx] = code,
                    (lx, blockY, lz, code) -> {
                        final int col = (lz << 4) | lx;
                        if (code != 0 && blockY + 1 > gpuWorldSurface[col]) {
                            gpuWorldSurface[col] = blockY + 1;
                        }
                        if (code == 1 && blockY + 1 > gpuOceanFloor[col]) {
                            gpuOceanFloor[col] = blockY + 1;
                        }
                    }
                );
                assertTrue(applyStats.skippedAirSections() >= 6, "Expected uniform sky AIR sections to be skipped in O(1): " + applyStats);
                assertTrue(applyStats.mixedSections() >= 1, "Expected mixed terrain sections (chunk=" + b + "): " + applyStats);

                // 2. Compute Mojang CPU reference via DensitySampler#sampleVolume + SectionBatchedTerrainFill
                final DensityVolume vol = new DensityVolume(16, sectionCount * 16, 16, ox, minBlockY, oz, 1, 1, 1);
                final float[] cpuDensities = new float[vol.size()];
                try (ScopedDensityBuffer cpuBuf = samplerCtx.acquireBuffer(vol)) {
                    mojangSampler.sampleVolume(samplerCtx, cpuBuf, vol);
                    for (int i = 0; i < vol.size(); i++) {
                        cpuDensities[i] = cpuBuf.get(i);
                    }
                }
                final int[][] cpuSections = new int[sectionCount][4096];
                final int[] cpuWorldSurface = new int[256];
                final int[] cpuOceanFloor = new int[256];
                java.util.Arrays.fill(cpuWorldSurface, minBlockY);
                java.util.Arrays.fill(cpuOceanFloor, minBlockY);
                SectionBatchedTerrainFill.fillSections(
                    vol,
                    cpuDensities,
                    minBlockY >> 4,
                    offset -> cpuSections[offset],
                    (bx, by, bz, d) -> d > 0.0f ? null : (by < deepFluidY ? 3 : (by < seaLevel ? 2 : 0)),
                    1,
                    0,
                    (sec, lx, ly, lz, bx, by, bz, code) -> {
                        sec[(ly << 8) | (lz << 4) | lx] = code;
                        final int col = (lz << 4) | lx;
                        if (code != 0 && by + 1 > cpuWorldSurface[col]) {
                            cpuWorldSurface[col] = by + 1;
                        }
                        if (code == 1 && by + 1 > cpuOceanFloor[col]) {
                            cpuOceanFloor[col] = by + 1;
                        }
                    }
                );

                // 3. Assert 1:1 parity across all 98,304 voxels, section counts, and heightmaps
                for (int s = 0; s < sectionCount; s++) {
                    int expNonEmpty = 0;
                    int expFluid = 0;
                    for (int i = 0; i < 4096; i++) {
                        final int exp = cpuSections[s][i];
                        assertEquals(exp, gpuSections[s][i], "Voxel mismatch at chunk=" + b + " sec=" + s + " idx=" + i);
                        if (exp != 0) expNonEmpty++;
                        if (exp >= 2) expFluid++;
                    }
                    final int globalSec = b * sectionCount + s;
                    assertEquals(expNonEmpty, dmaBuf.nonEmptyCount(globalSec), "nonEmptyCount mismatch at sec=" + s);
                    assertEquals(expFluid, dmaBuf.fluidCount(globalSec), "fluidCount mismatch at sec=" + s);
                }
                for (int col = 0; col < 256; col++) {
                    assertEquals(cpuWorldSurface[col], gpuWorldSurface[col], "WORLD_SURFACE_WG mismatch at col=" + col);
                    assertEquals(cpuOceanFloor[col], gpuOceanFloor[col], "OCEAN_FLOOR_WG mismatch at col=" + col);
                }
            }
        }
    }

    @Test
    void writesGpuSectionPaletteFillDirectlyToLcaResidencyCacheWithZeroPalettedContainerAndExactParity(@TempDir final Path tempDir) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        final Holder<NormalNoise> terrainNoise = Holder.direct(NormalNoise.createParity(-5, 1.0, 0.5, 0.25, 0.125));
        final DensityFunction.CompileContext compileCtx = new DensityFunction.CompileContext() {
            @Override
            public Noise createNoiseSampler(final Holder<NormalNoise> parameters) {
                return parameters.value().create(new XoroshiroRandomSource(42424242L ^ parameters.value().hashCode()));
            }

            @Override
            public RandomSource createRandom(final Identifier seed) {
                return new XoroshiroRandomSource(seed.hashCode());
            }

            @Override
            public RandomSource createEndIslandRandom() {
                return new XoroshiroRandomSource(17L);
            }
        };

        final DensityFunction baseNoise = new NoiseFunction(
            terrainNoise, 0.015625, 0.03125, DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero()
        );
        final DensityFunction yGrad = new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 192, 1.8f, -2.2f);
        final DensityFunction root = baseNoise.add(yGrad).clamp(-2.0f, 2.0f);

        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("lca-direct-cache"))) {
            final OpenClBackendEmitter.DensityVolumeProgram prog = accel.lowerSectionPaletteFillProgram(
                root,
                compileCtx,
                "Mc263DirectLcaTerrain",
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT,
                true
            );
            assertNotNull(prog);
            if (!accel.isGpuReady()) {
                return;
            }

            final IrOpenClTerrainAccelerator.CompiledDensityKernel compiled = accel.compileProgramBlocking(
                prog,
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertTrue(compiled.hasLiveGpuKernel());

            final int batchCount = 2;
            final int sectionCount = 24;
            final int minSectionY = -4;
            final int minBlockY = minSectionY << 4;
            final int seaLevel = 63;
            final int deepFluidY = -54;
            final int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
            final int[] runtimeIds4 = {0, 1, 34, 50}; // AIR, STONE, WATER, LAVA
            final int[] origins = {
                0, minBlockY, 0, 0,
                32, minBlockY, -16, 0
            };
            final long[] chunkKeys = {
                ChunkPos.pack(0, 0),
                ChunkPos.pack(2, -1)
            };

            final CompactSectionWritebackBuffer dmaBuf = new CompactSectionWritebackBuffer(batchCount * sectionCount);
            final LcaChunkResidencyCache residencyCache = new LcaChunkResidencyCache();

            final IrOpenClTerrainAccelerator.LcaWaveDispatchResult wave = accel.executeSectionPaletteFillToLcaCache(
                compiled,
                origins,
                chunkKeys,
                batchCount,
                minSectionY,
                sectionCount,
                seaLevel,
                deepFluidY,
                dataVersion,
                runtimeIds4,
                true,
                dmaBuf,
                residencyCache
            );
            assertEquals(batchCount, residencyCache.residentCount());
            assertTrue(wave.totalLcaBytes() > 0L);

            final DensityFunctionCompiler mojangCompiler = new DensityFunctionCompiler(compileCtx);
            final DensitySampler mojangSampler = mojangCompiler.getSampler(root);
            final SamplerContext samplerCtx = SamplerContext.builder().enableCaches().build();

            for (int b = 0; b < batchCount; b++) {
                final int ox = origins[b * 4];
                final int oz = origins[b * 4 + 2];
                final CompactLcaChunkFrameBuilder.BuildResult built = wave.chunkResults()[b];
                assertTrue(built.uniformAirSections() >= 6, "Expected uniform sky AIR sections folded to 2B");
                assertTrue(
                    built.frameByteLength() < sectionCount * 2048,
                    "Expected native .lca ChunkFrame (" + built.frameByteLength()
                        + " B) to stay far below 24 hydrated 4-bit PalettedContainers (49,152 B)"
                );

                // Verify every mixed section passes CBIT T6b validation via anvil-api
                for (int s = 0; s < sectionCount; s++) {
                    final byte[] slice = built.snapshot().sliceSectionFrameWithRuntimePalette(s, null);
                    if (slice.length > 0) {
                        final LcaSectionBacking parsed = LcaSectionBacking.fromColdSectionFrame(
                            slice, 0, -1, 0, 0, id -> id != 0, id -> false, id -> false
                        );
                        assertNotNull(parsed);
                    }
                }

                // Compare all 98,304 voxels read directly from LcaChunkResidencyCache against Mojang sampler
                final DensityVolume vol = new DensityVolume(16, sectionCount * 16, 16, ox, minBlockY, oz, 1, 1, 1);
                final float[] cpuDensities = new float[vol.size()];
                try (ScopedDensityBuffer cpuBuf = samplerCtx.acquireBuffer(vol)) {
                    mojangSampler.sampleVolume(samplerCtx, cpuBuf, vol);
                    for (int i = 0; i < vol.size(); i++) {
                        cpuDensities[i] = cpuBuf.get(i);
                    }
                }
                final int[][] cpuSections = new int[sectionCount][4096];
                final int[] cpuWorldSurface = new int[256];
                final int[] cpuOceanFloor = new int[256];
                java.util.Arrays.fill(cpuWorldSurface, minBlockY);
                java.util.Arrays.fill(cpuOceanFloor, minBlockY);
                SectionBatchedTerrainFill.fillSections(
                    vol,
                    cpuDensities,
                    minSectionY,
                    offset -> cpuSections[offset],
                    (bx, by, bz, d) -> d > 0.0f ? null : (by < deepFluidY ? 3 : (by < seaLevel ? 2 : 0)),
                    1,
                    0,
                    (sec, lx, ly, lz, bx, by, bz, code) -> {
                        sec[(ly << 8) | (lz << 4) | lx] = runtimeIds4[code];
                        final int col = (lz << 4) | lx;
                        if (code != 0 && by + 1 > cpuWorldSurface[col]) {
                            cpuWorldSurface[col] = by + 1;
                        }
                        if (code == 1 && by + 1 > cpuOceanFloor[col]) {
                            cpuOceanFloor[col] = by + 1;
                        }
                    }
                );

                final LcaChunkResidencyCache.FrozenReadSnapshot snap = residencyCache.getReadSnapshot(chunkKeys[b]);
                assertNotNull(snap);
                assertTrue(snap.isClean());
                for (int s = 0; s < sectionCount; s++) {
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                final int expectedState = cpuSections[s][(ly << 8) | (lz << 4) | lx];
                                final int actualState = snap.readRuntimeStateId(s, lx, ly, lz);
                                assertEquals(
                                    expectedState,
                                    actualState,
                                    "LCA voxel mismatch at chunk=" + b + " s=" + s + " (" + lx + "," + ly + "," + lz + ")"
                                );
                            }
                        }
                    }
                }
                assertNotNull(built.worldSurfaceHeightmap());
                assertNotNull(built.oceanFloorHeightmap());
                for (int col = 0; col < 256; col++) {
                    assertEquals(cpuWorldSurface[col], built.worldSurfaceHeightmap()[col], "WORLD_SURFACE_WG mismatch at col=" + col);
                    assertEquals(cpuOceanFloor[col], built.oceanFloorHeightmap()[col], "OCEAN_FLOOR_WG mismatch at col=" + col);
                }

                // Simulate BUILD_SURFACE / FEATURES dirty edits + section-spliced CoW compaction directly in .lca RAM
                final int editPos = (5 << 8) | (4 << 4) | 3;
                assertTrue(residencyCache.recordDirtyVoxel(chunkKeys[b], 8, editPos, 777));
                assertEquals(777, residencyCache.readLiveRuntimeStateId(chunkKeys[b], 8, 3, 5, 4));
                assertTrue(residencyCache.compactDirtySnapshot(chunkKeys[b]));
                final LcaChunkResidencyCache.FrozenReadSnapshot postCompact = residencyCache.getReadSnapshot(chunkKeys[b]);
                assertNotNull(postCompact);
                assertTrue(postCompact.isClean());
                assertEquals(777, postCompact.readRuntimeStateId(8, 3, 5, 4));
                assertEquals(cpuSections[8][0], postCompact.readRuntimeStateId(8, 0, 0, 0));
            }
        }
    }

    @Test
    void executesCoarseLocalTrilinearSectionPaletteFillWithExactMojangParity(@TempDir final Path tempDir) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        final Holder<NormalNoise> terrainNoise = Holder.direct(NormalNoise.createParity(-5, 1.0, 0.5, 0.25));
        final Holder<NormalNoise> noodleToggleNoise = Holder.direct(NormalNoise.createParity(-6, 1.0, 0.5));
        final Holder<NormalNoise> noodleRidgeNoise = Holder.direct(NormalNoise.createParity(-7, 1.0, 0.5));
        final DensityFunction.CompileContext compileCtx = new DensityFunction.CompileContext() {
            @Override
            public Noise createNoiseSampler(final Holder<NormalNoise> parameters) {
                final long seed = parameters == terrainNoise ? 13371337L
                    : (parameters == noodleToggleNoise ? 13371338L : 13371339L);
                return parameters.value().create(new XoroshiroRandomSource(seed));
            }

            @Override
            public RandomSource createRandom(final Identifier seed) {
                return new XoroshiroRandomSource(seed.hashCode());
            }

            @Override
            public RandomSource createEndIslandRandom() {
                return new XoroshiroRandomSource(17L);
            }
        };

        // 1. Multi-channel Overworld-style 4x8 InterpolatedFunction graph
        final DensityFunction grad = new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 128, 1.5f, -1.5f);
        final DensityFunction slide = DensityFunctions.add(
            grad,
            DensityFunctions.noise(terrainNoise, 0.25, 0.25)
        );
        final DensityFunction postProcessed = DensityFunctions.interpolated(
            DensityFunctions.mul(new BlendDensityFunction(slide), DensityFunctions.constant(0.64f)),
            4,
            8
        ).squeeze();
        final DensityFunction noodleToggle = DensityFunctions.interpolated(
            DensityFunctions.noise(noodleToggleNoise, 1.0, 1.0),
            4,
            8
        );
        final DensityFunction noodleRidge = DensityFunctions.interpolated(
            DensityFunctions.noise(noodleRidgeNoise, 2.0, 2.0),
            4,
            8
        );
        final DensityFunction noodle = new RangeChoiceFunction(
            noodleToggle,
            -1000000.0f,
            0.0f,
            DensityFunctions.constant(64.0f),
            DensityFunctions.add(DensityFunctions.constant(-0.08f), noodleRidge.abs().mul(1.5f))
        );
        final DensityFunction overworldLike = DensityFunctions.add(
            DensityFunctions.min(postProcessed, noodle),
            SimpleDensityFunction.BEARDIFIER
        );

        final DensityFunctionIrLowerer.SplitLoweringResult split4x8 = DensityFunctionIrLowerer.lowerSplitForSectionFill(
            overworldLike,
            compileCtx,
            "mc263_coarse_4x8_test"
        );
        assertTrue(split4x8.isSupported(), "Expected split lowering to succeed: " + split4x8.unsupportedReason());
        assertTrue(split4x8.hasCoarseInterp(), "Expected 4x8 coarse interpolation channels");
        assertEquals(3, split4x8.coarseSpec().coarseFunctionNames().size());
        assertEquals(5, split4x8.coarseSpec().gridSizeXz());
        assertEquals(3, split4x8.coarseSpec().gridSizeY());

        // 2. End-style 8x4 InterpolatedFunction graph
        final DensityFunction endLike = DensityFunctions.interpolated(
            DensityFunctions.mul(slide, DensityFunctions.constant(0.64f)),
            8,
            4
        ).squeeze();
        final DensityFunctionIrLowerer.SplitLoweringResult split8x4 = DensityFunctionIrLowerer.lowerSplitForSectionFill(
            endLike,
            compileCtx,
            "mc263_coarse_8x4_test"
        );
        assertTrue(split8x4.hasCoarseInterp(), "Expected 8x4 coarse interpolation channel");
        assertEquals(1, split8x4.coarseSpec().coarseFunctionNames().size());
        assertEquals(3, split8x4.coarseSpec().gridSizeXz());
        assertEquals(5, split8x4.coarseSpec().gridSizeY());

        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("cache-coarse"))) {
            assertTrue(accel.isGpuReady(), "Expected live OpenCL GPU");
            final DensityFunctionCompiler mojangCompiler = new DensityFunctionCompiler(compileCtx);
            final SamplerContext samplerCtx = SamplerContext.builder().enableCaches().build();

            for (final DensityFunction root : java.util.List.of(overworldLike, endLike)) {
                final OpenClBackendEmitter.DensityVolumeProgram program = accel.lowerSectionPaletteFillProgram(
                    root,
                    compileCtx,
                    root == overworldLike ? "coarse_ow" : "coarse_end",
                    TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT,
                    true
                );
                assertNotNull(program);
                assertTrue(program.sourceCode().contains("__local float coarse_vals["), "Expected coarse __local buffer");

                final IrOpenClTerrainAccelerator.CompiledDensityKernel compiled = accel.compileProgramBlocking(
                    program,
                    TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
                );
                assertTrue(compiled.hasLiveGpuKernel());

                final int sectionCount = 8;
                final int minBlockY = -64;
                final int seaLevel = 63;
                final int deepFluidY = -54;
                final int[] origins = {16, minBlockY, -32, 0};
                final CompactSectionWritebackBuffer dmaBuf = new CompactSectionWritebackBuffer(sectionCount);
                accel.executeSectionPaletteFillBatch(compiled, origins, 1, sectionCount, seaLevel, deepFluidY, dmaBuf);

                final int[][] gpuSections = new int[sectionCount][4096];
                final Integer[] palette4 = {0, 1, 2, 3};
                CompactSectionWritebackApplier.applySections(
                    dmaBuf,
                    0,
                    sectionCount,
                    minBlockY,
                    offset -> gpuSections[offset],
                    palette4,
                    (sec, lx, ly, lz, blockY, code) -> sec[(ly << 8) | (lz << 4) | lx] = code,
                    (lx, blockY, lz, code) -> {}
                );

                final DensitySampler mojangSampler = mojangCompiler.getSampler(root);
                final DensityVolume vol = new DensityVolume(16, sectionCount * 16, 16, 16, minBlockY, -32, 1, 1, 1);
                final float[] cpuDensities = new float[vol.size()];
                try (ScopedDensityBuffer cpuBuf = samplerCtx.acquireBuffer(vol)) {
                    mojangSampler.sampleVolume(samplerCtx, cpuBuf, vol);
                    for (int i = 0; i < vol.size(); i++) {
                        cpuDensities[i] = cpuBuf.get(i);
                    }
                }
                final int[][] cpuSections = new int[sectionCount][4096];
                SectionBatchedTerrainFill.fillSections(
                    vol,
                    cpuDensities,
                    minBlockY >> 4,
                    offset -> cpuSections[offset],
                    (bx, by, bz, d) -> d > 0.0f ? null : (by < deepFluidY ? 3 : (by < seaLevel ? 2 : 0)),
                    1,
                    0,
                    (sec, lx, ly, lz, bx, by, bz, code) -> sec[(ly << 8) | (lz << 4) | lx] = code
                );

                int mismatches = 0;
                for (int s = 0; s < sectionCount; s++) {
                    for (int i = 0; i < 4096; i++) {
                        if (cpuSections[s][i] != gpuSections[s][i]) {
                            mismatches++;
                        }
                    }
                }
                assertEquals(0, mismatches, "Expected 0 voxel mismatches across 32,768 voxels for coarse trilinear GPU fill");
            }
        }
    }

    @Test
    void verifiesSwar2To4BitScatterRemapLutAndSimpleBitStorageParity() {
        final java.util.Random rng = new java.util.Random(0x5EED_CAFE_1234L);
        final long[] rawIdentity = new long[256];
        final long[] rawNether = new long[256];
        final int[] netherMap = {0, 1, 2, 2};
        final int[] netherLut = CompactSectionWritebackApplier.buildRemapByteLut(netherMap);
        final int[] expectedIdentity = new int[4096];
        final int[] expectedNether = new int[4096];

        for (int w = 0; w < 256; w++) {
            final int word = w == 0 ? 0 : (w == 1 ? 0xFFFF_FFFF : (w == 2 ? 0xE4E4_E4E4 : rng.nextInt()));
            rawIdentity[w] = CompactSectionWritebackApplier.spread2To4(word);
            rawNether[w] = CompactSectionWritebackApplier.remapAndSpread2To4(word, netherLut);

            final int[] codeCounts = new int[4];
            for (int lx = 0; lx < 16; lx++) {
                final int code = (word >>> (lx << 1)) & 0x3;
                codeCounts[code]++;
                expectedIdentity[(w << 4) | lx] = code;
                expectedNether[(w << 4) | lx] = netherMap[code];
            }
            for (int c = 0; c < 4; c++) {
                assertEquals(
                    codeCounts[c],
                    CompactSectionWritebackApplier.countCodeInWord(word, c),
                    "countCodeInWord mismatch for code=" + c + " word=0x" + Integer.toHexString(word)
                );
            }
        }

        final net.minecraft.util.SimpleBitStorage idStorage = new net.minecraft.util.SimpleBitStorage(4, 4096, rawIdentity);
        final net.minecraft.util.SimpleBitStorage netherStorage = new net.minecraft.util.SimpleBitStorage(4, 4096, rawNether);
        for (int i = 0; i < 4096; i++) {
            assertEquals(expectedIdentity[i], idStorage.get(i), "SimpleBitStorage identity mismatch at idx=" + i);
            assertEquals(expectedNether[i], netherStorage.get(i), "SimpleBitStorage Nether remap mismatch at idx=" + i);
        }
    }
}
