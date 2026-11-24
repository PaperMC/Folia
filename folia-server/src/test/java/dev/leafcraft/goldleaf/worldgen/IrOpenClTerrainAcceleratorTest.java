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

            // Compare against Mojang's own DensityFunctionCompiler + DensitySampler#sampleVolume
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
                            2e-2f,
                            "MC 26.3 AST GPU INT64_Q32_FP32 parity drift at chunk=" + b + " idx=" + i
                        );
                    }
                }
            }

            // Opt-in diagnostic VANILLA_EXACT_FP64 preserves 1e-4f exactness on every sample when supported
            if (GoldLeafOpenClBridge.deviceSupportsFp64(0)) {
                final OpenClBackendEmitter.DensityVolumeProgram fp64Prog = accel.lowerDensityFunctionProgram(
                    root,
                    compileCtx,
                    "Mc263OverworldAstFp64",
                    TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT_FP64,
                    true
                );
                assertNotNull(fp64Prog);
                final IrOpenClTerrainAccelerator.CompiledDensityKernel fp64Compiled = accel.compileProgramBlocking(
                    fp64Prog,
                    TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT_FP64
                );
                assertTrue(fp64Compiled.hasLiveGpuKernel());
                final float[] fp64Out = new float[batchCount * volumeSize];
                accel.executeDensityVolumeBatch(fp64Compiled, origins, batchCount, sizeX, sizeY, sizeZ, stepX, stepY, stepZ, fp64Out);
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
                                fp64Out[base + i],
                                1e-4f,
                                "MC 26.3 AST GPU FP64 parity drift at chunk=" + b + " idx=" + i
                            );
                        }
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

    @Test
    void vanillaExactUnconditionallyEmitsInt64Q32Fp32AndFp64IsStrictlyOptIn(@TempDir final Path tempDir) throws Exception {
        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("cache-precision"))) {
            assertEquals(
                io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32,
                accel.selectArithmeticMode(TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT),
                "VANILLA_EXACT must always use INT64_Q32_FP32 even on FP64-capable GPUs"
            );
            assertEquals(
                io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.GpuExactArithmeticMode.DOWNCAST_FP32,
                accel.selectArithmeticMode(TerrainGenerationAccelerator.ParityMode.FAST)
            );
            final var fp64Mode = accel.selectArithmeticMode(TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT_FP64);
            if (!accel.isGpuReady() || GoldLeafOpenClBridge.deviceSupportsFp64(0)) {
                assertEquals(
                    io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.GpuExactArithmeticMode.NATIVE_FP64,
                    fp64Mode,
                    "VANILLA_EXACT_FP64 must select NATIVE_FP64 when explicitly requested"
                );
            } else {
                assertEquals(
                    io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32,
                    fp64Mode
                );
            }

            final OpenClBackendEmitter.DensityVolumeProgram exactProg = accel.liftStaticMethodProgram(
                SyntheticTerrainKernel.class,
                "sampleDensity",
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertFalse(
                exactProg.sourceCode().contains("cl_khr_fp64"),
                "VANILLA_EXACT emitted source must never enable cl_khr_fp64 by default"
            );
            assertTrue(
                exactProg.sourceCode().contains("#pragma OPENCL FP_CONTRACT OFF"),
                "VANILLA_EXACT must preserve strict FP_CONTRACT OFF semantics"
            );

            if (fp64Mode == io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.GpuExactArithmeticMode.NATIVE_FP64) {
                final OpenClBackendEmitter.DensityVolumeProgram fp64Prog = accel.liftStaticMethodProgram(
                    SyntheticTerrainKernel.class,
                    "sampleDensity",
                    TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT_FP64
                );
                assertTrue(
                    fp64Prog.sourceCode().contains("cl_khr_fp64"),
                    "Opt-in VANILLA_EXACT_FP64 must emit cl_khr_fp64 when supported"
                );
            }
        }
    }

    @Test
    void compactSectionWritebackBufferPoolRefcountsAndReleasesOnException() {
        try (CompactSectionWritebackBufferPool pool = new CompactSectionWritebackBufferPool(4, 16)) {
            assertEquals(0, pool.inUseCount());
            assertEquals(0, pool.pooledCount());

            final CompactSectionWritebackBufferPool.PooledWaveLease lease1 = pool.acquire(16, 4);
            final CompactSectionWritebackBuffer firstBuf = lease1.buffer();
            assertEquals(1, pool.inUseCount());
            assertEquals(0, pool.pooledCount());

            for (int i = 0; i < 3; i++) {
                lease1.releaseChunk();
                assertEquals(1, pool.inUseCount(), "Buffer must stay leased until final chunk releases");
            }
            // Simulate exception on final chunk with try/finally release
            try {
                throw new IllegalStateException("synthetic chunk apply failure");
            } catch (final IllegalStateException ignored) {
                // handled by fallback
            } finally {
                lease1.releaseChunk();
            }
            assertEquals(0, pool.inUseCount());
            assertEquals(1, pool.pooledCount());

            // Extra releaseChunk/releaseAll must be idempotent (S-002)
            lease1.releaseChunk();
            lease1.releaseAll();
            assertEquals(0, pool.inUseCount());
            assertEquals(1, pool.pooledCount());

            // Subsequent acquire reuses pooled buffer
            final CompactSectionWritebackBufferPool.PooledWaveLease lease2 = pool.acquire(16, 2);
            org.junit.jupiter.api.Assertions.assertSame(firstBuf, lease2.buffer());
            assertEquals(1, pool.inUseCount());
            assertEquals(0, pool.pooledCount());
            lease2.releaseAll();
            assertEquals(0, pool.inUseCount());
            assertEquals(1, pool.pooledCount());
        }
    }

    @Test
    void asyncWaveSubmissionSaturatesWavesBeyondSubmitterThreadsAndParallelizesHostApply() throws Exception {
        final java.util.concurrent.ExecutorService dispatchExec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "test-gpu-dispatch");
            t.setDaemon(true);
            return t;
        });
        final java.util.concurrent.atomic.AtomicInteger applyThreadIds = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.ExecutorService applyExec = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            final Thread t = new Thread(r, "test-host-apply-" + applyThreadIds.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        final java.util.concurrent.ExecutorService submitters = java.util.concurrent.Executors.newFixedThreadPool(2);
        final java.util.List<Integer> observedWaveSizes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final java.util.Set<String> applyThreadNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final java.util.concurrent.CountDownLatch applyConcurrencyLatch = new java.util.concurrent.CountDownLatch(2);

        try (CompactSectionWritebackBufferPool pool = new CompactSectionWritebackBufferPool(4, 16);
             WorldgenWaveCoalescer<Integer, java.util.concurrent.CompletableFuture<Boolean>> coalescer =
                 new WorldgenWaveCoalescer<>(
                     16,
                     TimeUnit.MILLISECONDS.toNanos(10L),
                     (key, batch) -> {
                         observedWaveSizes.add(batch.size());
                         final CompactSectionWritebackBufferPool.PooledWaveLease lease =
                             pool.acquire(batch.size(), batch.size());
                         final java.util.List<java.util.concurrent.CompletableFuture<Boolean>> outs =
                             new java.util.ArrayList<>(batch.size());
                         for (final Integer id : batch) {
                             final java.util.concurrent.CompletableFuture<Boolean> fut = new java.util.concurrent.CompletableFuture<>();
                             outs.add(fut);
                             applyExec.execute(() -> {
                                 try {
                                     applyThreadNames.add(Thread.currentThread().getName());
                                     applyConcurrencyLatch.countDown();
                                     applyConcurrencyLatch.await(2, TimeUnit.SECONDS);
                                     if (id == 7) {
                                         throw new RuntimeException("injected chunk apply fault");
                                     }
                                     fut.complete(Boolean.TRUE);
                                 } catch (final Throwable t) {
                                     fut.complete(Boolean.FALSE);
                                 } finally {
                                     lease.releaseChunk();
                                 }
                             });
                         }
                         return outs;
                     },
                     dispatchExec
                 )) {
            final WorldgenWaveCoalescer.WaveKey key = new WorldgenWaveCoalescer.WaveKey("minecraft:overworld", 42);
            final java.util.List<java.util.concurrent.CompletableFuture<Boolean>> futures = new java.util.ArrayList<>(32);
            // Submit 32 chunks asynchronously from only 2 submitter threads without blocking on .join()
            for (int i = 0; i < 32; i++) {
                final int id = i;
                futures.add(
                    java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> coalescer.submit(key, id).thenCompose(java.util.function.Function.identity()),
                        submitters
                    ).thenCompose(java.util.function.Function.identity())
                );
            }
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS);

            int succeeded = 0;
            for (int i = 0; i < 32; i++) {
                final boolean ok = Boolean.TRUE.equals(futures.get(i).join());
                if (i == 7) {
                    assertFalse(ok, "Injected failing chunk must resolve false for CPU fallback");
                } else {
                    assertTrue(ok, "Healthy chunk " + i + " must succeed");
                    succeeded++;
                }
            }
            assertEquals(31, succeeded);
            final int maxWave = observedWaveSizes.stream().mapToInt(Integer::intValue).max().orElse(0);
            assertTrue(maxWave >= 16, "Async submission from 2 threads must coalesce waves >= 16, got " + observedWaveSizes);
            assertTrue(
                applyThreadNames.size() >= 2,
                "hostApply must execute in parallel across >= 2 threads off dispatch thread, got " + applyThreadNames
            );
            assertFalse(applyThreadNames.contains("test-gpu-dispatch"));
            assertEquals(0, pool.inUseCount(), "All pooled leases must be returned even when a chunk fails");
        } finally {
            submitters.shutdownNow();
            applyExec.shutdownNow();
            dispatchExec.shutdownNow();
        }
    }

    @Test
    void fused6ChannelBiomeClimateKernelMatchesSixIndividualDispatches(@TempDir final Path tempDir) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        final Holder<NormalNoise> tempNoise = Holder.direct(NormalNoise.createParity(-5, 1.0, 0.5));
        final Holder<NormalNoise> vegNoise = Holder.direct(NormalNoise.createParity(-4, 1.0, 0.5));
        final DensityFunction.CompileContext compileCtx = new DensityFunction.CompileContext() {
            @Override
            public Noise createNoiseSampler(final Holder<NormalNoise> parameters) {
                return parameters.value().create(new XoroshiroRandomSource(987654321L ^ parameters.value().hashCode()));
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

        final java.util.List<DensityFunction> climate6 = java.util.List.of(
            new NoiseFunction(tempNoise, 0.015625, 0.0, DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero()),
            new NoiseFunction(vegNoise, 0.015625, 0.0, DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero()),
            new NoiseFunction(tempNoise, 0.0078125, 0.0, DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero()),
            new NoiseFunction(vegNoise, 0.03125, 0.0, DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero()),
            new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 320, 1.5f, -1.5f),
            new NoiseFunction(tempNoise, 0.02, 0.0, DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero()).abs()
        );

        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("cache-biome6"))) {
            for (final TerrainGenerationAccelerator.ParityMode mode : new TerrainGenerationAccelerator.ParityMode[]{
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT,
                TerrainGenerationAccelerator.ParityMode.FAST
            }) {
                final OpenClBackendEmitter.DensityVolumeProgram fusedProg = accel.lowerFusedBiomeClimateProgram(
                    climate6,
                    compileCtx,
                    "test_biome6_" + mode.name().toLowerCase(java.util.Locale.ROOT) + "_",
                    mode
                );
                assertNotNull(fusedProg);
                assertEquals("eval_biome_climate6_batch", fusedProg.kernelName());
                assertFalse(fusedProg.sourceCode().contains("cl_khr_fp64"));

                if (!accel.isGpuReady()) {
                    continue;
                }
                final IrOpenClTerrainAccelerator.CompiledDensityKernel fusedKernel =
                    accel.compileProgramBlocking(fusedProg, mode);
                assertTrue(fusedKernel.hasLiveGpuKernel());

                final int batchCount = 4;
                final int sizeX = 4;
                final int sizeY = 24;
                final int sizeZ = 4;
                final int quartPerChunk = sizeX * sizeY * sizeZ;
                final int[] origins = {
                    0, -64, 0, 0,
                    16, -64, 0, 0,
                    -32, -64, 48, 0,
                    128, -64, -96, 0
                };
                final float[] fusedOut = new float[batchCount * quartPerChunk * 6];
                accel.executeBiomeClimate6Batch(
                    fusedKernel, origins, batchCount, sizeX, sizeY, sizeZ, 4, 4, 4, fusedOut
                );

                for (int c = 0; c < 6; c++) {
                    final OpenClBackendEmitter.DensityVolumeProgram singleProg = accel.lowerDensityFunctionProgram(
                        climate6.get(c),
                        compileCtx,
                        "single_ch_" + c + "_" + mode.name(),
                        mode,
                        true
                    );
                    assertNotNull(singleProg);
                    final IrOpenClTerrainAccelerator.CompiledDensityKernel singleKernel =
                        accel.compileProgramBlocking(singleProg, mode);
                    final float[] singleOut = new float[batchCount * quartPerChunk];
                    accel.executeDensityVolumeBatch(
                        singleKernel, origins, batchCount, sizeX, sizeY, sizeZ, 4, 4, 4, singleOut
                    );
                    for (int i = 0; i < batchCount * quartPerChunk; i++) {
                        assertEquals(
                            singleOut[i],
                            fusedOut[i * 6 + c],
                            1e-5f,
                            "Fused 6-channel mismatch for mode=" + mode + " ch=" + c + " idx=" + i
                        );
                    }
                }
            }
        }
    }

    @Test
    void doubleBufferedTwoSlotNativeDmaProducesBitIdenticalOutputUnderConcurrentWaves(@TempDir final Path tempDir) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        final Holder<NormalNoise> terrainNoise = Holder.direct(NormalNoise.createParity(-5, 1.0, 0.5, 0.25));
        final DensityFunction.CompileContext compileCtx = new DensityFunction.CompileContext() {
            @Override
            public Noise createNoiseSampler(final Holder<NormalNoise> parameters) {
                return parameters.value().create(new XoroshiroRandomSource(424242L));
            }

            @Override
            public RandomSource createRandom(final Identifier seed) {
                return new XoroshiroRandomSource(seed.hashCode());
            }

            @Override
            public RandomSource createEndIslandRandom() {
                return new XoroshiroRandomSource(7L);
            }
        };
        final DensityFunction root = new NoiseFunction(
            terrainNoise, 0.015625, 0.03125, DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero()
        ).add(new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 192, 1.5f, -1.5f));

        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("cache-dma2"))) {
            if (!accel.isGpuReady()) {
                return;
            }
            final OpenClBackendEmitter.DensityVolumeProgram prog = accel.lowerSectionPaletteFillProgram(
                root, compileCtx, "dma2_stress", TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT, true
            );
            assertNotNull(prog);
            final IrOpenClTerrainAccelerator.CompiledDensityKernel compiled =
                accel.compileProgramBlocking(prog, TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            assertTrue(compiled.hasLiveGpuKernel());

            final int batchCount = 4;
            final int sectionCount = 8;
            final int[] origins = {
                0, -64, 0, 0,
                16, -64, 32, 0,
                -48, -64, 16, 0,
                64, -64, -64, 0
            };
            final CompactSectionWritebackBuffer golden = new CompactSectionWritebackBuffer(batchCount * sectionCount);
            accel.executeSectionPaletteFillBatch(compiled, origins, batchCount, sectionCount, 63, -54, golden);
            final byte[] goldenWords = new byte[batchCount * sectionCount * CompactSectionWritebackBuffer.WORD_BYTES_PER_SECTION];
            final byte[] goldenMeta = new byte[batchCount * sectionCount * CompactSectionWritebackBuffer.META_BYTES_PER_SECTION];
            golden.wordsDirectBuffer().get(0, goldenWords);
            golden.metaDirectBuffer().get(0, goldenMeta);

            final int workers = 4;
            final int wavesPerWorker = 12;
            final java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newFixedThreadPool(workers);
            final java.util.concurrent.CountDownLatch startGate = new java.util.concurrent.CountDownLatch(1);
            final java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>(workers);
            try {
                for (int w = 0; w < workers; w++) {
                    futures.add(exec.submit(() -> {
                        startGate.await(5, TimeUnit.SECONDS);
                        final CompactSectionWritebackBuffer localBuf =
                            new CompactSectionWritebackBuffer(batchCount * sectionCount);
                        final byte[] actualWords = new byte[goldenWords.length];
                        final byte[] actualMeta = new byte[goldenMeta.length];
                        for (int iter = 0; iter < wavesPerWorker; iter++) {
                            accel.executeSectionPaletteFillBatch(
                                compiled, origins, batchCount, sectionCount, 63, -54, localBuf
                            );
                            localBuf.wordsDirectBuffer().get(0, actualWords);
                            localBuf.metaDirectBuffer().get(0, actualMeta);
                            org.junit.jupiter.api.Assertions.assertArrayEquals(
                                goldenWords, actualWords, "Concurrent 2-slot DMA words drift at iter=" + iter
                            );
                            org.junit.jupiter.api.Assertions.assertArrayEquals(
                                goldenMeta, actualMeta, "Concurrent 2-slot DMA meta drift at iter=" + iter
                            );
                        }
                        return null;
                    }));
                }
                startGate.countDown();
                for (final java.util.concurrent.Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                exec.shutdownNow();
            }
        }
    }

    @Test
    void gpuBiomeRTreeMatchesVanillaCpuRTreeBitIdentically(@TempDir final Path tempDir) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        final java.util.List<Holder<NormalNoise>> noises = java.util.List.of(
            Holder.direct(NormalNoise.createParity(-6, 1.0, 0.5)),
            Holder.direct(NormalNoise.createParity(-5, 1.0, 0.5)),
            Holder.direct(NormalNoise.createParity(-4, 1.0, 0.5)),
            Holder.direct(NormalNoise.createParity(-5, 0.5, 1.0)),
            Holder.direct(NormalNoise.createParity(-3, 1.0)),
            Holder.direct(NormalNoise.createParity(-6, 0.75, 0.25))
        );
        final DensityFunction.CompileContext compileCtx = new DensityFunction.CompileContext() {
            @Override
            public Noise createNoiseSampler(final Holder<NormalNoise> parameters) {
                return parameters.value().create(new XoroshiroRandomSource(1000L + noises.indexOf(parameters)));
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
        final java.util.List<DensityFunction> climate6 = new java.util.ArrayList<>(6);
        for (int c = 0; c < 6; c++) {
            climate6.add(new NoiseFunction(
                noises.get(c), 0.01 * (c + 1), 0.01 * (c + 1),
                DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero()
            ));
        }

        // Build a realistic multi-level 19-ary R-Tree (> 60 leaves so depth >= 2)
        final java.util.List<com.mojang.datafixers.util.Pair<net.minecraft.world.level.biome.Climate.ParameterPoint, Holder<net.minecraft.world.level.biome.Biome>>> entries =
            new java.util.ArrayList<>();
        final net.minecraft.world.level.biome.Biome dummyBiomeA = new net.minecraft.world.level.biome.Biome.BiomeBuilder()
            .hasPrecipitation(true).temperature(0.8f).downfall(0.4f)
            .specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects.Builder().waterColor(0x3F76E4).build())
            .mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
            .generationSettings(net.minecraft.world.level.biome.BiomeGenerationSettings.EMPTY)
            .build();
        @SuppressWarnings("unchecked")
        final Holder<net.minecraft.world.level.biome.Biome>[] biomeHolders = new Holder[12];
        for (int i = 0; i < biomeHolders.length; i++) {
            biomeHolders[i] = Holder.direct(dummyBiomeA);
        }
        int idx = 0;
        for (float t = -1.0f; t <= 1.0f; t += 0.5f) {
            for (float h = -1.0f; h <= 1.0f; h += 0.5f) {
                for (float c = -0.8f; c <= 0.8f; c += 0.8f) {
                    entries.add(com.mojang.datafixers.util.Pair.of(
                        net.minecraft.world.level.biome.Climate.parameters(
                            net.minecraft.world.level.biome.Climate.Parameter.span(t - 0.25f, t + 0.25f),
                            net.minecraft.world.level.biome.Climate.Parameter.span(h - 0.25f, h + 0.25f),
                            net.minecraft.world.level.biome.Climate.Parameter.span(c - 0.4f, c + 0.4f),
                            net.minecraft.world.level.biome.Climate.Parameter.span(-1.0f, 1.0f),
                            net.minecraft.world.level.biome.Climate.Parameter.span(-1.0f, 1.0f),
                            net.minecraft.world.level.biome.Climate.Parameter.span(-1.0f, 1.0f),
                            (idx % 5) * 0.05f
                        ),
                        biomeHolders[idx % biomeHolders.length]
                    ));
                    idx++;
                }
            }
        }
        final net.minecraft.world.level.biome.Climate.ParameterList<Holder<net.minecraft.world.level.biome.Biome>> paramList =
            new net.minecraft.world.level.biome.Climate.ParameterList<>(entries);
        final GpuBiomeRTree.FlattenedRTree flattened = GpuBiomeRTree.flatten(paramList.goldleaf$index());
        assertNotNull(flattened);
        assertTrue(flattened.nodeCount() > entries.size(), "Expected internal nodes + leaves");
        assertTrue(flattened.maxDepth() >= 2, "Expected multi-level R-tree");

        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("cache-rtree"))) {
            if (!accel.isGpuReady()) {
                return;
            }
            final OpenClBackendEmitter.DensityVolumeProgram climate6Prog = accel.lowerFusedBiomeClimateProgram(
                climate6, compileCtx, "rtree_test_", TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertNotNull(climate6Prog);
            final GpuBiomeRTree.FusedBiomeRTreeProgram fused = GpuBiomeRTree.buildFusedProgram(climate6Prog, flattened);
            assertNotNull(fused);

            final IrOpenClTerrainAccelerator.CompiledDensityKernel climate6Kernel =
                accel.compileProgramBlocking(climate6Prog, TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final IrOpenClTerrainAccelerator.CompiledDensityKernel rtreeKernel =
                accel.compileProgramBlocking(fused.program(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            assertTrue(rtreeKernel.hasLiveGpuKernel());

            final int batchCount = 3;
            final int sizeX = 4, sizeY = 16, sizeZ = 4;
            final int quartPerChunk = sizeX * sizeY * sizeZ;
            final int[] origins = {
                0, -64, 0, 0,
                32, -64, -16, 0,
                -64, -64, 48, 0
            };
            final float[] climateOut = new float[batchCount * quartPerChunk * 6];
            accel.executeBiomeClimate6Batch(climate6Kernel, origins, batchCount, sizeX, sizeY, sizeZ, 4, 4, 4, climateOut);

            final byte[] gpuBiomeIds = new byte[batchCount * quartPerChunk];
            accel.executeBiomeRTreeBatch(rtreeKernel, origins, batchCount, sizeX, sizeY, sizeZ, 4, 4, 4, gpuBiomeIds);

            final Holder<net.minecraft.world.level.biome.Biome>[] palette = flattened.biomePalette();
            for (int i = 0; i < batchCount * quartPerChunk; i++) {
                final int off = i * 6;
                final Holder<net.minecraft.world.level.biome.Biome> cpuBiome = paramList.findValueQuantized(
                    net.minecraft.world.level.biome.Climate.quantizeCoord(climateOut[off]),
                    net.minecraft.world.level.biome.Climate.quantizeCoord(climateOut[off + 1]),
                    net.minecraft.world.level.biome.Climate.quantizeCoord(climateOut[off + 2]),
                    net.minecraft.world.level.biome.Climate.quantizeCoord(climateOut[off + 3]),
                    net.minecraft.world.level.biome.Climate.quantizeCoord(climateOut[off + 4]),
                    net.minecraft.world.level.biome.Climate.quantizeCoord(climateOut[off + 5])
                );
                final Holder<net.minecraft.world.level.biome.Biome> gpuBiome = palette[gpuBiomeIds[i] & 0xFF];
                org.junit.jupiter.api.Assertions.assertSame(cpuBiome, gpuBiome, "GPU R-Tree biome mismatch at quart " + i);
            }
        }
    }

    @Test
    void gpuOreVeinRasterizerMatchesVanillaCpuOreFeature(@TempDir final Path tempDir) throws Exception {
        final String src = GpuOrePlacementEngine.buildOreVeinRasterKernelSource();
        assertFalse(src.contains("cl_khr_fp64"), "Ore-vein kernel must not use FP64");
        assertFalse(src.contains("double "), "Ore-vein kernel must not use double precision");
        assertTrue(src.contains("rasterize_ore_veins_batch"));

        if (!GoldLeafOpenClBridge.isAvailable() || GoldLeafOpenClBridge.deviceCount() <= 0) {
            return;
        }
        try (GoldLeafOpenClBridge.CompiledKernel kernel = GoldLeafOpenClBridge.compileFromSource(
            0, src, GpuOrePlacementEngine.KERNEL_NAME, "-cl-std=CL1.2", null
        )) {
            final int[] sizes = {4, 7, 8, 9, 10, 17, 20, 33, 64};
            final int veinCount = 72;
            final int[] veinMeta4 = new int[veinCount * 4];
            final float[] veinRel6 = new float[veinCount * 6];
            final long[] veinSeeds2 = new long[veinCount * 2];
            final int[] outSlices2 = new int[veinCount * 2];
            final int[] outVoxels = new int[65536];
            final long[] timings = new long[2];

            // Test both near-origin and far-world (29,999,000) coordinates to verify relative-FP32 precision
            for (final int[] baseOrigin : new int[][]{{0, 0}, {29_999_008, -29_999_008}}) {
                final int centerCx = baseOrigin[0] >> 4;
                final int centerCz = baseOrigin[1] >> 4;
                final int regionMinX = (centerCx - 1) << 4;
                final int regionMinZ = (centerCz - 1) << 4;
                final int minBuildY = -64;
                final int maxBuildY = 319;

                final net.minecraft.world.level.levelgen.WorldgenRandom setupRng =
                    new net.minecraft.world.level.levelgen.WorldgenRandom(new XoroshiroRandomSource(42424242L ^ centerCx));
                final java.util.List<java.util.Set<Integer>> expectedPerVein = new java.util.ArrayList<>(veinCount);

                for (int v = 0; v < veinCount; v++) {
                    final int size = sizes[v % sizes.length];
                    final int ox = (centerCx << 4) + setupRng.nextInt(16);
                    final int oz = (centerCz << 4) + setupRng.nextInt(16);
                    final int oy = -50 + setupRng.nextInt(140);

                    final float dir = setupRng.nextFloat() * (float) Math.PI;
                    final float spreadXY = size / 8.0F;
                    final int maxRadius = net.minecraft.util.Mth.ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);
                    final double x0 = ox + Math.sin(dir) * spreadXY;
                    final double x1 = ox - Math.sin(dir) * spreadXY;
                    final double z0 = oz + Math.cos(dir) * spreadXY;
                    final double z1 = oz - Math.cos(dir) * spreadXY;
                    final double y0 = oy + setupRng.nextInt(3) - 2;
                    final double y1 = oy + setupRng.nextInt(3) - 2;
                    final int ceilSpread = net.minecraft.util.Mth.ceil(spreadXY);
                    final int xStart = ox - ceilSpread - maxRadius;
                    final int yStart = oy - 2 - maxRadius;
                    final int zStart = oz - ceilSpread - maxRadius;
                    final int sizeXZ = 2 * (ceilSpread + maxRadius);
                    final int sizeY = 2 * (2 + maxRadius);

                    final XoroshiroRandomSource xoro = (XoroshiroRandomSource) setupRng.goldleaf$randomSource();
                    final long sLo = xoro.goldleaf$rng().goldleaf$seedLo();
                    final long sHi = xoro.goldleaf$rng().goldleaf$seedHi();

                    veinMeta4[v * 4] = xStart;
                    veinMeta4[v * 4 + 1] = yStart;
                    veinMeta4[v * 4 + 2] = zStart;
                    veinMeta4[v * 4 + 3] = (size & 0xFF) | ((sizeXZ & 0xFF) << 8) | ((sizeY & 0xFF) << 16);
                    veinRel6[v * 6] = (float) (x0 - xStart);
                    veinRel6[v * 6 + 1] = (float) (x1 - xStart);
                    veinRel6[v * 6 + 2] = (float) (y0 - yStart);
                    veinRel6[v * 6 + 3] = (float) (y1 - yStart);
                    veinRel6[v * 6 + 4] = (float) (z0 - zStart);
                    veinRel6[v * 6 + 5] = (float) (z1 - zStart);
                    veinSeeds2[v * 2] = sLo;
                    veinSeeds2[v * 2 + 1] = sHi;

                    // Reference vanilla OreFeature#doPlace calculation (consuming exact same RNG)
                    final double[] data = new double[size * 4];
                    for (int i = 0; i < size; i++) {
                        final float step = (float) i / size;
                        data[i * 4] = net.minecraft.util.Mth.lerp(step, x0, x1);
                        data[i * 4 + 1] = net.minecraft.util.Mth.lerp(step, y0, y1);
                        data[i * 4 + 2] = net.minecraft.util.Mth.lerp(step, z0, z1);
                        final double ss = setupRng.nextDouble() * size / 16.0;
                        data[i * 4 + 3] = ((net.minecraft.util.Mth.sin(net.minecraft.util.Mth.PI * step) + 1.0F) * ss + 1.0) / 2.0;
                    }
                    for (int i1 = 0; i1 < size - 1; i1++) {
                        if (data[i1 * 4 + 3] <= 0.0) continue;
                        for (int i2 = i1 + 1; i2 < size; i2++) {
                            if (data[i2 * 4 + 3] <= 0.0) continue;
                            final double dx = data[i1 * 4] - data[i2 * 4];
                            final double dy = data[i1 * 4 + 1] - data[i2 * 4 + 1];
                            final double dz = data[i1 * 4 + 2] - data[i2 * 4 + 2];
                            final double dr = data[i1 * 4 + 3] - data[i2 * 4 + 3];
                            if (dr * dr > dx * dx + dy * dy + dz * dz) {
                                if (dr > 0.0) data[i2 * 4 + 3] = -1.0;
                                else data[i1 * 4 + 3] = -1.0;
                            }
                        }
                    }
                    final java.util.Set<Integer> expected = new java.util.HashSet<>();
                    for (int i = 0; i < size; i++) {
                        final double r = data[i * 4 + 3];
                        if (r < 0.0) continue;
                        final double xx = data[i * 4];
                        final double yy = data[i * 4 + 1];
                        final double zz = data[i * 4 + 2];
                        final int xMin = Math.max(net.minecraft.util.Mth.floor(xx - r), xStart);
                        final int yMin = Math.max(net.minecraft.util.Mth.floor(yy - r), yStart);
                        final int zMin = Math.max(net.minecraft.util.Mth.floor(zz - r), zStart);
                        final int xMax = Math.max(net.minecraft.util.Mth.floor(xx + r), xMin);
                        final int yMax = Math.max(net.minecraft.util.Mth.floor(yy + r), yMin);
                        final int zMax = Math.max(net.minecraft.util.Mth.floor(zz + r), zMin);
                        for (int x = xMin; x <= xMax; x++) {
                            final int rx = x - regionMinX;
                            if (rx < 0 || rx >= 48) continue;
                            final double xd = (x + 0.5 - xx) / r;
                            if (xd * xd >= 1.0) continue;
                            for (int z = zMin; z <= zMax; z++) {
                                final int rz = z - regionMinZ;
                                if (rz < 0 || rz >= 48) continue;
                                final double zd = (z + 0.5 - zz) / r;
                                if (xd * xd + zd * zd >= 1.0) continue;
                                for (int y = yMin; y <= yMax; y++) {
                                    if (y < minBuildY || y > maxBuildY) continue;
                                    final double yd = (y + 0.5 - yy) / r;
                                    if (xd * xd + zd * zd + yd * yd < 1.0) {
                                        final int ry = y - minBuildY;
                                        expected.add((rx & 63) | ((rz & 63) << 6) | ((ry & 511) << 12));
                                    }
                                }
                            }
                        }
                    }
                    expectedPerVein.add(expected);
                }

                final int totalVoxels = kernel.dispatchOreVeinRasterBatch(
                    veinMeta4, veinRel6, veinSeeds2, veinCount,
                    regionMinX, regionMinZ, minBuildY, maxBuildY,
                    outSlices2, outVoxels, timings
                );
                assertTrue(totalVoxels > 0, "Expected non-empty GPU ore voxel output");

                long totalExpected = 0;
                long totalMatched = 0;
                long totalExtra = 0;
                for (int v = 0; v < veinCount; v++) {
                    final int base = outSlices2[v * 2];
                    final int cnt = outSlices2[v * 2 + 1];
                    assertTrue(cnt >= 0, "Unexpected overflow on vein " + v);
                    final java.util.Set<Integer> gpuSet = new java.util.HashSet<>(cnt * 2);
                    for (int i = 0; i < cnt; i++) {
                        assertTrue(gpuSet.add(outVoxels[base + i]), "Duplicate voxel in GPU output for vein " + v);
                    }
                    final java.util.Set<Integer> cpuSet = expectedPerVein.get(v);
                    totalExpected += cpuSet.size();
                    for (final Integer p : cpuSet) {
                        if (gpuSet.contains(p)) totalMatched++;
                    }
                    for (final Integer p : gpuSet) {
                        if (!cpuSet.contains(p)) totalExtra++;
                    }
                }
                final double matchRatio = totalExpected == 0 ? 1.0 : (double) totalMatched / (double) totalExpected;
                assertTrue(
                    matchRatio >= 0.999 && totalExtra <= Math.max(4L, totalExpected / 1000L),
                    "GPU ore rasterizer parity mismatch at base=(" + baseOrigin[0] + "," + baseOrigin[1]
                        + "): matched=" + totalMatched + "/" + totalExpected + " (" + matchRatio + "), extra=" + totalExtra
                );
            }
        }
    }

    @Test
    void carvingMaskBitsetExportMatchesEverySetBit() {
        final int minBlockY = -64;
        final int height = 384;
        final int wordsPerCol = (height + 31) >>> 5;
        final net.minecraft.world.level.chunk.CarvingMask mask =
            new net.minecraft.world.level.chunk.CarvingMask(minBlockY + 1, minBlockY + height - 8);

        final int[][] samples = {
            {0, -63, 0}, {15, -63, 15}, {7, 0, 11}, {3, 62, 14}, {12, 63, 2}, {9, 127, 5}, {1, 311, 8}
        };
        for (final int[] s : samples) {
            mask.carve(s[0], s[1], s[2]);
        }
        final int[] packed = mask.goldleaf$packColumnMasks(minBlockY, wordsPerCol);
        assertEquals(256 * wordsPerCol, packed.length);

        int totalBits = 0;
        for (final int w : packed) {
            totalBits += Integer.bitCount(w);
        }
        assertEquals(samples.length, totalBits, "Exact set-bit count must match");
        for (final int[] s : samples) {
            final int tid = (s[2] << 4) | s[0];
            final int relY = s[1] - minBlockY;
            final int bit = (packed[tid * wordsPerCol + (relY >>> 5)] >>> (relY & 31)) & 1;
            assertEquals(1, bit, "Missing carved bit at (" + s[0] + "," + s[1] + "," + s[2] + ")");
        }
    }

    @Test
    void regionRelativeMultiChunkOreWaveMatchesSingleChunkDispatchBitIdentically() {
        if (!GoldLeafOpenClBridge.isAvailable() || GoldLeafOpenClBridge.deviceCount() <= 0) {
            return;
        }

        final String src = GpuOrePlacementEngine.buildOreVeinRasterKernelSource();
        final int minBuildY = -64;
        final int maxBuildY = 319;
        final int[][] chunkOrigins = {{0, 0}, {1600, -3200}, {-29999008, 29999008}};
        final int veinsPerChunk = 8;
        final int totalVeins = chunkOrigins.length * veinsPerChunk;

        final int[] waveMeta4 = new int[totalVeins * 4];
        final float[] waveRel6 = new float[totalVeins * 6];
        final long[] waveSeeds2 = new long[totalVeins * 2];

        try (GoldLeafOpenClBridge.CompiledKernel kernel = GoldLeafOpenClBridge.compileFromSource(
            0, src, GpuOrePlacementEngine.KERNEL_NAME, "-cl-std=CL1.2", null
        )) {
            final int[][] singleSlices = new int[chunkOrigins.length][];
            final int[][] singleVoxels = new int[chunkOrigins.length][];
            final int[] singleTotals = new int[chunkOrigins.length];

            for (int c = 0; c < chunkOrigins.length; c++) {
                final int regionMinX = chunkOrigins[c][0] - 16;
                final int regionMinZ = chunkOrigins[c][1] - 16;
                final int[] cMeta4 = new int[veinsPerChunk * 4];
                final float[] cRel6 = new float[veinsPerChunk * 6];
                final long[] cSeeds2 = new long[veinsPerChunk * 2];
                final net.minecraft.world.level.levelgen.XoroshiroRandomSource xoro =
                    new net.minecraft.world.level.levelgen.XoroshiroRandomSource(1337L + c * 997L);
                final net.minecraft.world.level.levelgen.WorldgenRandom rng =
                    new net.minecraft.world.level.levelgen.WorldgenRandom(xoro);

                for (int v = 0; v < veinsPerChunk; v++) {
                    final int size = 8 + (v % 4) * 12;
                    final int ox = chunkOrigins[c][0] + rng.nextInt(16);
                    final int oz = chunkOrigins[c][1] + rng.nextInt(16);
                    final int oy = -40 + rng.nextInt(120);
                    final float dir = rng.nextFloat() * (float) Math.PI;
                    final float spreadXY = size / 8.0F;
                    final int maxRadius = net.minecraft.util.Mth.ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);
                    final double x0 = ox + Math.sin(dir) * spreadXY;
                    final double x1 = ox - Math.sin(dir) * spreadXY;
                    final double z0 = oz + Math.cos(dir) * spreadXY;
                    final double z1 = oz - Math.cos(dir) * spreadXY;
                    final double y0 = oy + rng.nextInt(3) - 2;
                    final double y1 = oy + rng.nextInt(3) - 2;
                    final int ceilSpread = net.minecraft.util.Mth.ceil(spreadXY);
                    final int xStart = ox - ceilSpread - maxRadius;
                    final int yStart = oy - 2 - maxRadius;
                    final int zStart = oz - ceilSpread - maxRadius;
                    final int sizeXZ = 2 * (ceilSpread + maxRadius);
                    final int sizeY = 2 * (2 + maxRadius);
                    final int packedMeta = (size & 0xFF) | ((sizeXZ & 0xFF) << 8) | ((sizeY & 0xFF) << 16);

                    cMeta4[v * 4] = xStart;
                    cMeta4[v * 4 + 1] = yStart;
                    cMeta4[v * 4 + 2] = zStart;
                    cMeta4[v * 4 + 3] = packedMeta;
                    cRel6[v * 6] = (float) (x0 - xStart);
                    cRel6[v * 6 + 1] = (float) (x1 - xStart);
                    cRel6[v * 6 + 2] = (float) (y0 - yStart);
                    cRel6[v * 6 + 3] = (float) (y1 - yStart);
                    cRel6[v * 6 + 4] = (float) (z0 - zStart);
                    cRel6[v * 6 + 5] = (float) (z1 - zStart);
                    cSeeds2[v * 2] = xoro.goldleaf$rng().goldleaf$seedLo();
                    cSeeds2[v * 2 + 1] = xoro.goldleaf$rng().goldleaf$seedHi();
                    xoro.consumeCount(size << 1);

                    final int wv = c * veinsPerChunk + v;
                    waveMeta4[wv * 4] = xStart - regionMinX;
                    waveMeta4[wv * 4 + 1] = yStart;
                    waveMeta4[wv * 4 + 2] = zStart - regionMinZ;
                    waveMeta4[wv * 4 + 3] = packedMeta;
                    System.arraycopy(cRel6, v * 6, waveRel6, wv * 6, 6);
                    System.arraycopy(cSeeds2, v * 2, waveSeeds2, wv * 2, 2);
                }

                singleSlices[c] = new int[veinsPerChunk * 2];
                singleVoxels[c] = new int[65536];
                singleTotals[c] = kernel.dispatchOreVeinRasterBatch(
                    cMeta4, cRel6, cSeeds2, veinsPerChunk,
                    regionMinX, regionMinZ, minBuildY, maxBuildY,
                    singleSlices[c], singleVoxels[c], new long[2]
                );
                assertTrue(singleTotals[c] > 0);
            }

            final int[] waveSlices = new int[totalVeins * 2];
            final int[] waveVoxels = new int[131072];
            final int waveTotal = kernel.dispatchOreVeinRasterBatch(
                waveMeta4, waveRel6, waveSeeds2, totalVeins,
                0, 0, minBuildY, maxBuildY,
                waveSlices, waveVoxels, new long[2]
            );
            assertEquals(singleTotals[0] + singleTotals[1] + singleTotals[2], waveTotal);

            for (int c = 0; c < chunkOrigins.length; c++) {
                for (int v = 0; v < veinsPerChunk; v++) {
                    final int wv = c * veinsPerChunk + v;
                    final int sBase = singleSlices[c][v * 2];
                    final int sCnt = singleSlices[c][v * 2 + 1];
                    final int wBase = waveSlices[wv * 2];
                    final int wCnt = waveSlices[wv * 2 + 1];
                    assertEquals(sCnt, wCnt, "Voxel count mismatch for chunk " + c + " vein " + v);
                    for (int i = 0; i < sCnt; i++) {
                        assertEquals(singleVoxels[c][sBase + i], waveVoxels[wBase + i], "Voxel mismatch at chunk " + c + " vein " + v + " idx " + i);
                    }
                }
            }
        }
    }

    @Test
    void hilbert3dOreCurveIsExactFaceAdjacentBijectionOver8x8x8() {
        final boolean[] seen = new boolean[HilbertOreVeinWalker.CURVE_LENGTH];
        int prevX = 0, prevY = 0, prevZ = 0;
        for (int i = 0; i < HilbertOreVeinWalker.CURVE_LENGTH; i++) {
            final int p = HilbertOreVeinWalker.packedPoint(i) & 0xFFFF;
            final int x = p & 7;
            final int y = (p >>> 3) & 7;
            final int z = (p >>> 6) & 7;
            final int flat = (z << 6) | (y << 3) | x;
            assertFalse(seen[flat], "Duplicate Hilbert 3D cell at step " + i + " (" + x + "," + y + "," + z + ")");
            seen[flat] = true;
            if (i > 0) {
                final int dist = Math.abs(x - prevX) + Math.abs(y - prevY) + Math.abs(z - prevZ);
                assertEquals(1, dist, "Hilbert 3D step " + (i - 1) + "->" + i + " must be face-adjacent, got dist=" + dist);
            }
            prevX = x;
            prevY = y;
            prevZ = z;
        }
        final net.minecraft.world.level.levelgen.XoroshiroRandomSource rng =
            new net.minecraft.world.level.levelgen.XoroshiroRandomSource(12345L);
        assertTrue(HilbertOreVeinWalker.computeBlockCount(4, rng) >= 1);
        assertTrue(HilbertOreVeinWalker.computeBlockCount(64, rng) <= HilbertOreVeinWalker.CURVE_LENGTH);
    }

    @Test
    void gpuSkyAndBlockLightMatchesExactReferenceBfsBitIdentically() {
        final String src = GpuLightEngine.buildLightKernelSource();
        assertFalse(src.contains("cl_khr_fp64"), "GPU light kernel must use zero FP64");
        assertFalse(src.contains("double"), "GPU light kernel must not contain double arithmetic");
        org.junit.jupiter.api.Assumptions.assumeTrue(GoldLeafOpenClBridge.isAvailable(), "OpenCL GPU required");

        final int activeSections = 4;
        final int height = activeSections * 16; // 64 blocks tall
        final byte[] voxelInfo = new byte[activeSections * 4096];

        // Solid bedrock floor at y = 0 (opacity = 15, zero bottom leak)
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                voxelInfo[(z << 4) | x] = (byte) 15;
            }
        }
        // Solid stone layer y = 1..20 with an L-shaped underground cave at y = 6..12
        for (int y = 1; y <= 20; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    voxelInfo[(y << 8) | (z << 4) | x] = (byte) 15;
                }
            }
        }
        // Carve cave tunnel open to sky at x=2..3, z=2..3 going sideways and up into an overhang pocket
        for (int y = 6; y <= 20; y++) {
            for (int z = 2; z <= 3; z++) {
                for (int x = 2; x <= 3; x++) {
                    voxelInfo[(y << 8) | (z << 4) | x] = 0;
                }
            }
        }
        for (int y = 6; y <= 10; y++) {
            for (int z = 2; z <= 12; z++) {
                for (int x = 2; x <= 12; x++) {
                    voxelInfo[(y << 8) | (z << 4) | x] = 0;
                }
            }
        }
        // Upward pocket inside cave ceiling at x=9..10, z=9..10, y=11..16
        for (int y = 11; y <= 16; y++) {
            for (int z = 9; z <= 10; z++) {
                for (int x = 9; x <= 10; x++) {
                    voxelInfo[(y << 8) | (z << 4) | x] = 0;
                }
            }
        }
        // Water lake (opacity = 1) at x=8..15, z=0..7, y=21..36 (16 blocks deep)
        for (int y = 21; y <= 36; y++) {
            for (int z = 0; z <= 7; z++) {
                for (int x = 8; x <= 15; x++) {
                    voxelInfo[(y << 8) | (z << 4) | x] = (byte) 1;
                }
            }
        }
        // Lava block (emission = 15, opacity = 0) at (11, 6, 11) and glow lichen (emission = 7, opacity = 0) at (4, 8, 10)
        voxelInfo[(6 << 8) | (11 << 4) | 11] = (byte) (15 << 4);
        voxelInfo[(8 << 8) | (10 << 4) | 4] = (byte) (7 << 4);

        // Exact reference CPU BFS for sky and block light
        final int[] refSky = new int[height * 256];
        final int[] refBlk = new int[height * 256];
        final java.util.ArrayDeque<Integer> skyQ = new java.util.ArrayDeque<>();
        final java.util.ArrayDeque<Integer> blkQ = new java.util.ArrayDeque<>();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                boolean open = true;
                for (int y = height - 1; y >= 0; y--) {
                    final int idx = (y << 8) | (z << 4) | x;
                    final int op = voxelInfo[idx] & 15;
                    final int em = (voxelInfo[idx] >>> 4) & 15;
                    if (open) {
                        if (op == 0) {
                            refSky[idx] = 15;
                            skyQ.add(idx);
                        } else {
                            open = false;
                            if (y == height - 1 && op < 15) {
                                final int v = 15 - Math.max(1, op);
                                if (v > 0) {
                                    refSky[idx] = v;
                                    skyQ.add(idx);
                                }
                            }
                        }
                    }
                    if (em > 0) {
                        refBlk[idx] = em;
                        blkQ.add(idx);
                    }
                }
            }
        }
        final int[][] dirs = { {1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1} };
        while (!skyQ.isEmpty()) {
            final int cur = skyQ.poll();
            final int cx = cur & 15, cz = (cur >>> 4) & 15, cy = cur >>> 8;
            final int lv = refSky[cur];
            for (final int[] d : dirs) {
                final int nx = cx + d[0], ny = cy + d[1], nz = cz + d[2];
                if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16 || ny < 0 || ny >= height) continue;
                final int nIdx = (ny << 8) | (nz << 4) | nx;
                final int op = voxelInfo[nIdx] & 15;
                if (op >= 15) continue;
                final int cand = lv - Math.max(1, op);
                if (cand > refSky[nIdx]) {
                    refSky[nIdx] = cand;
                    skyQ.add(nIdx);
                }
            }
        }
        while (!blkQ.isEmpty()) {
            final int cur = blkQ.poll();
            final int cx = cur & 15, cz = (cur >>> 4) & 15, cy = cur >>> 8;
            final int lv = refBlk[cur];
            for (final int[] d : dirs) {
                final int nx = cx + d[0], ny = cy + d[1], nz = cz + d[2];
                if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16 || ny < 0 || ny >= height) continue;
                final int nIdx = (ny << 8) | (nz << 4) | nx;
                final int op = voxelInfo[nIdx] & 15;
                if (op >= 15) continue;
                final int cand = lv - Math.max(1, op);
                if (cand > refBlk[nIdx]) {
                    refBlk[nIdx] = cand;
                    blkQ.add(nIdx);
                }
            }
        }

        try (GpuLightEngine engine = new GpuLightEngine(0, null)) {
            final io.papermc.paper.spi.PaperWorldgenSpi.PrecomputedChunkLight out =
                engine.dispatchSingleChunkForTest(voxelInfo, activeSections, true);
            assertNotNull(out);
            assertFalse(out.hasBottomLeak(), "Solid bedrock floor must have zero bottom leak");
            assertNotNull(out.blkNibbles());
            for (int y = 0; y < height; y++) {
                final int s = y >> 4;
                final int ly = y & 15;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final int idx = (y << 8) | (z << 4) | x;
                        final int localIdx = x | (z << 4) | (ly << 8);
                        final int byteIdx = (s << 11) + (localIdx >>> 1);
                        final int shift = (localIdx & 1) << 2;
                        final int gpuSky = (out.skyNibbles()[byteIdx] >>> shift) & 0xF;
                        final int gpuBlk = (out.blkNibbles()[byteIdx] >>> shift) & 0xF;
                        assertEquals(refSky[idx], gpuSky, "Sky light mismatch at (" + x + "," + y + "," + z + ")");
                        assertEquals(refBlk[idx], gpuBlk, "Block light mismatch at (" + x + "," + y + "," + z + ")");
                    }
                }
            }
            // Top section (y = 48..63) is pure open air -> must be flagged SKY_ALL_15 and BLK_ALL_ZERO
            assertTrue((out.secFlags()[3] & GpuLightEngine.FLAG_SKY_ALL_15) != 0);
            assertTrue((out.secFlags()[3] & GpuLightEngine.FLAG_BLK_ALL_ZERO) != 0);
        }
    }

    @Test
    void asyncWaveCoalescedGpuLightMatchesSingleChunkBitIdentically() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            GoldLeafOpenClBridge.isAvailable() && GoldLeafOpenClBridge.deviceCount() > 0,
            "OpenCL runtime/device not available"
        );
        final int activeSections = 4;
        final int height = activeSections * 16;
        final byte[] voxelInfo = new byte[height * 256];
        for (int y = 0; y < 20; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    voxelInfo[(y << 8) | (z << 4) | x] = (byte) 15;
                }
            }
        }
        for (int y = 5; y <= 10; y++) {
            for (int z = 4; z <= 11; z++) {
                for (int x = 4; x <= 11; x++) {
                    voxelInfo[(y << 8) | (z << 4) | x] = 0;
                }
            }
        }
        voxelInfo[(6 << 8) | (7 << 4) | 7] = (byte) (15 << 4);

        try (GpuLightEngine engine = new GpuLightEngine(0, null)) {
            final io.papermc.paper.spi.PaperWorldgenSpi.PrecomputedChunkLight golden =
                engine.dispatchSingleChunkForTest(voxelInfo, activeSections, true);
            assertNotNull(golden);

            final int count = 32;
            final java.util.List<java.util.concurrent.CompletableFuture<io.papermc.paper.spi.PaperWorldgenSpi.PrecomputedChunkLight>> futures = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                futures.add(engine.submitPackedForTest(voxelInfo, activeSections, true));
            }
            for (int i = 0; i < count; i++) {
                final io.papermc.paper.spi.PaperWorldgenSpi.PrecomputedChunkLight out = futures.get(i).join();
                assertNotNull(out, "Wave-coalesced GPU light result " + i + " must be non-null");
                assertEquals(activeSections, out.activeSections());
                assertNotNull(out.blkNibbles());
                for (int s = 0; s < activeSections; s++) {
                    assertEquals(golden.secFlags()[s], out.secFlags()[out.secFlagsOffset() + s]);
                }
                for (int b = 0; b < activeSections * 2048; b++) {
                    assertEquals(golden.skyNibbles()[b], out.skyNibbles()[out.skyNibblesOffset() + b]);
                    assertEquals(golden.blkNibbles()[b], out.blkNibbles()[out.blkNibblesOffset() + b]);
                }
            }
            assertTrue(engine.gpuWaves() < count, "32 concurrent submissions must coalesce into fewer waves");
            assertEquals(count, engine.gpuChunks());
        }
    }

    @Test
    void gpuCarverMaskMatchesVanillaCpuCarversNearAndFar() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            GoldLeafOpenClBridge.isAvailable() && GoldLeafOpenClBridge.deviceCount() > 0,
            "OpenCL runtime/device not available"
        );
        final long worldSeed = 42424242L;
        final int minY = -64;
        final int genDepth = 384;
        final int minCarveY = minY + 1;
        final int maxCarveY = minY + genDepth - 1 - 7;
        final int wordsPerCol = (genDepth + 31) >>> 5;
        final net.minecraft.world.level.levelgen.WorldGenerationContext wgCtx =
            new net.minecraft.world.level.levelgen.WorldGenerationContext(
                new net.minecraft.world.level.levelgen.FlatLevelSource(
                    new net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings(
                        java.util.Optional.empty(),
                        Holder.direct(new net.minecraft.world.level.biome.Biome.BiomeBuilder()
                            .hasPrecipitation(true).temperature(0.8f).downfall(0.4f)
                            .specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects.Builder().waterColor(0x3F76E4).build())
                            .mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
                            .generationSettings(net.minecraft.world.level.biome.BiomeGenerationSettings.EMPTY)
                            .build()),
                        java.util.List.of()
                    )
                ),
                net.minecraft.world.level.LevelHeightAccessor.create(minY, genDepth)
            ) {
                @Override
                public int getMinGenY() {
                    return minY;
                }

                @Override
                public int getGenDepth() {
                    return genDepth;
                }
            };

        final java.util.List<net.minecraft.world.level.levelgen.carver.WorldCarver> carvers = java.util.List.of(
            new net.minecraft.world.level.levelgen.carver.CaveWorldCarver(
                0.15F,
                net.minecraft.world.level.levelgen.heightproviders.UniformHeight.of(
                    net.minecraft.world.level.levelgen.VerticalAnchor.aboveBottom(8),
                    net.minecraft.world.level.levelgen.VerticalAnchor.absolute(180)
                ),
                net.minecraft.util.valueproviders.VeryBiasedToBottomInt.of(0, 14),
                net.minecraft.util.valueproviders.TrapezoidFloat.of(0.0F, 3.0F, 1.0F),
                true,
                net.minecraft.util.valueproviders.UniformFloat.of(0.1F, 0.9F),
                net.minecraft.util.valueproviders.UniformFloat.of(0.7F, 1.4F),
                net.minecraft.util.valueproviders.UniformFloat.of(0.8F, 1.3F),
                net.minecraft.util.valueproviders.ConstantFloat.of(1.0F),
                net.minecraft.util.valueproviders.UniformFloat.of(-1.0F, -0.4F)
            ),
            new net.minecraft.world.level.levelgen.carver.CaveWorldCarver(
                0.07F,
                net.minecraft.world.level.levelgen.heightproviders.UniformHeight.of(
                    net.minecraft.world.level.levelgen.VerticalAnchor.aboveBottom(8),
                    net.minecraft.world.level.levelgen.VerticalAnchor.absolute(47)
                ),
                net.minecraft.util.valueproviders.VeryBiasedToBottomInt.of(0, 14),
                net.minecraft.util.valueproviders.TrapezoidFloat.of(0.0F, 3.0F, 1.0F),
                true,
                net.minecraft.util.valueproviders.UniformFloat.of(0.1F, 0.9F),
                net.minecraft.util.valueproviders.UniformFloat.of(0.7F, 1.4F),
                net.minecraft.util.valueproviders.UniformFloat.of(0.8F, 1.3F),
                net.minecraft.util.valueproviders.ConstantFloat.of(1.0F),
                net.minecraft.util.valueproviders.UniformFloat.of(-1.0F, -0.4F)
            ),
            new net.minecraft.world.level.levelgen.carver.CanyonWorldCarver(
                0.01F,
                net.minecraft.world.level.levelgen.heightproviders.UniformHeight.of(
                    net.minecraft.world.level.levelgen.VerticalAnchor.absolute(10),
                    net.minecraft.world.level.levelgen.VerticalAnchor.absolute(67)
                ),
                net.minecraft.util.valueproviders.UniformFloat.of(-0.125F, 0.125F),
                new net.minecraft.world.level.levelgen.carver.CanyonWorldCarver.Shape(
                    net.minecraft.util.valueproviders.UniformFloat.of(0.75F, 1.0F),
                    net.minecraft.util.valueproviders.TrapezoidFloat.of(0.0F, 6.0F, 2.0F),
                    3,
                    net.minecraft.util.valueproviders.UniformFloat.of(0.75F, 1.0F),
                    1.0F,
                    0.0F,
                    net.minecraft.util.valueproviders.ConstantFloat.of(3.0F)
                )
            )
        );

        final OpenClBackendEmitter.DensityVolumeProgram prog =
            GpuCarverMaskCompiler.compile(carvers, worldSeed, wgCtx, "test_carver_mask");
        assertNotNull(prog, "GpuCarverMaskCompiler must compile vanilla Overworld carvers");
        assertFalse(prog.sourceCode().contains("cl_khr_fp64"), "Carver mask kernel must not use FP64");
        assertFalse(prog.sourceCode().contains("double "), "Carver mask kernel must not use double");

        final int[][] coords = {
            {0, 0}, {1, 0}, {0, 1}, {-3, 5}, {7, -4}, {12, 19},
            {10000, -10000}, {-1874938, 1874938}
        };
        final int batchCount = coords.length;
        final int[] origins = new int[batchCount * 4];
        for (int i = 0; i < batchCount; i++) {
            origins[i * 4] = coords[i][0] << 4;
            origins[i * 4 + 1] = minY;
            origins[i * 4 + 2] = coords[i][1] << 4;
        }
        final int[] gpuMeta = new int[batchCount];
        final int[] gpuMasks = new int[batchCount * 256 * wordsPerCol];

        try (GoldLeafOpenClBridge.CompiledKernel kernel = GoldLeafOpenClBridge.compileFromSource(
            0, prog.sourceCode(), prog.kernelName(), "-cl-std=CL1.2", null
        )) {
            final int err = kernel.dispatchCarverMaskBatch(
                origins, batchCount, wordsPerCol, minY, minCarveY, maxCarveY, gpuMeta, gpuMasks
            );
            assertEquals(0, err, "dispatchCarverMaskBatch failed");
        }

        final net.minecraft.world.level.levelgen.WorldgenRandom cpuRand =
            new net.minecraft.world.level.levelgen.WorldgenRandom(
                new net.minecraft.world.level.levelgen.LegacyRandomSource(0L)
            );
        long totalVoxels = 0L;
        long totalCpuCarved = 0L;
        long mismatches = 0L;
        for (int b = 0; b < batchCount; b++) {
            final net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(coords[b][0], coords[b][1]);
            final net.minecraft.world.level.chunk.CarvingMask cpuMask =
                new net.minecraft.world.level.chunk.CarvingMask(minCarveY, maxCarveY);
            for (int dx = -8; dx <= 8; dx++) {
                for (int dz = -8; dz <= 8; dz++) {
                    final net.minecraft.world.level.ChunkPos srcPos = new net.minecraft.world.level.ChunkPos(pos.x() + dx, pos.z() + dz);
                    for (int ci = 0; ci < carvers.size(); ci++) {
                        cpuRand.setLargeFeatureSeed(worldSeed + ci, srcPos.x(), srcPos.z());
                        if (carvers.get(ci).isStartChunk(cpuRand)) {
                            carvers.get(ci).carve(wgCtx, cpuRand, pos, srcPos, cpuMask);
                        }
                    }
                }
            }
            final int[] cpuWords = cpuMask.isEmpty()
                ? new int[256 * wordsPerCol]
                : cpuMask.goldleaf$packColumnMasks(minY, wordsPerCol);
            final int gpuBase = b * 256 * wordsPerCol;
            for (int w = 0; w < 256 * wordsPerCol; w++) {
                final int cw = cpuWords[w];
                final int gw = gpuMeta[b] >= 0 ? gpuMasks[gpuBase + w] : 0;
                totalVoxels += 32L;
                totalCpuCarved += Integer.bitCount(cw);
                mismatches += Integer.bitCount(cw ^ gw);
            }
        }
        assertTrue(totalCpuCarved > 500, "Test chunks must contain non-trivial carved caves/canyons");
        final double matchPct = 100.0 * (1.0 - (double) mismatches / (double) totalVoxels);
        assertTrue(
            matchPct >= 99.95,
            "GPU carver mask parity must be >= 99.95% (got " + String.format(java.util.Locale.ROOT, "%.4f%%", matchPct)
                + ", mismatches=" + mismatches + ", cpuCarved=" + totalCpuCarved + ")"
        );
    }

    @Test
    void featurePassBiomeMaskFailsClosedOnUnmappedBiome() {
        final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>, Integer> partial =
            new java.util.HashMap<>();
        partial.put(net.minecraft.world.level.biome.Biomes.DESERT, 0);
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> GpuFeaturePassCompiler.buildBiomeMask(
                partial,
                net.minecraft.world.level.biome.Biomes.DESERT,
                net.minecraft.world.level.biome.Biomes.SNOWY_PLAINS
            ),
            "Missing biome in biomeKeyToId must fail closed (S-004)"
        );

        final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>, Integer> complete =
            new java.util.HashMap<>();
        int nextId = 0;
        for (final var key : GpuFeaturePassCompiler.featureBiomes()) {
            complete.put(key, nextId++);
        }
        final long mask = GpuFeaturePassCompiler.buildBiomeMask(
            complete,
            net.minecraft.world.level.biome.Biomes.SNOWY_PLAINS,
            net.minecraft.world.level.biome.Biomes.SNOWY_TAIGA,
            net.minecraft.world.level.biome.Biomes.FROZEN_RIVER
        );
        assertEquals(3, Long.bitCount(mask), "All registered cold biomes must set distinct mask bits");
    }

    @Test
    void gpuTerrainAquiferPassMatchesCpuNoiseBasedAquiferAndLeavesUndergroundCavesDry() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        org.junit.jupiter.api.Assumptions.assumeTrue(
            GoldLeafOpenClBridge.isAvailable() && GoldLeafOpenClBridge.deviceCount() > 0,
            "OpenCL runtime/device not available"
        );

        final java.util.Map<net.minecraft.resources.ResourceKey<NormalNoise>, Holder.Reference<NormalNoise>> noiseMap =
            new java.util.HashMap<>();
        final net.minecraft.core.HolderOwner<NormalNoise> dummyOwner = new net.minecraft.core.HolderOwner<>() {};
        final java.util.function.BiFunction<net.minecraft.resources.ResourceKey<NormalNoise>, NormalNoise, Holder.Reference<NormalNoise>> regNoise =
            (k, v) -> {
                final Holder.Reference<NormalNoise> ref = Holder.Reference.createStandAlone(dummyOwner, k);
                try {
                    final java.lang.reflect.Method bind = Holder.Reference.class.getDeclaredMethod("bindValue", Object.class);
                    bind.setAccessible(true);
                    bind.invoke(ref, v);
                } catch (final Exception ex) {
                    throw new RuntimeException(ex);
                }
                noiseMap.put(k, ref);
                return ref;
            };
        final Holder<NormalNoise> nSurface = regNoise.apply(net.minecraft.world.level.levelgen.Noises.SURFACE, NormalNoise.createParity(-6, 1.0, 1.0, 1.0));
        final Holder<NormalNoise> nBarrier = regNoise.apply(net.minecraft.world.level.levelgen.Noises.AQUIFER_BARRIER, NormalNoise.createParity(-3, 1.0, 0.5));
        final Holder<NormalNoise> nFlood = regNoise.apply(net.minecraft.world.level.levelgen.Noises.AQUIFER_FLUID_LEVEL_FLOODEDNESS, NormalNoise.createParity(-6, 1.0, 0.5));
        final Holder<NormalNoise> nSpread = regNoise.apply(net.minecraft.world.level.levelgen.Noises.AQUIFER_FLUID_LEVEL_SPREAD, NormalNoise.createParity(-5, 1.0, 0.5));
        final Holder<NormalNoise> nLava = regNoise.apply(net.minecraft.world.level.levelgen.Noises.AQUIFER_LAVA, NormalNoise.createParity(-1, 1.0, 0.5));
        final net.minecraft.core.HolderGetter<NormalNoise> noiseGetter = new net.minecraft.core.HolderGetter<>() {
            @Override
            public java.util.Optional<Holder.Reference<NormalNoise>> get(final net.minecraft.resources.ResourceKey<NormalNoise> id) {
                Holder.Reference<NormalNoise> ref = noiseMap.get(id);
                if (ref == null) {
                    ref = regNoise.apply(id, NormalNoise.createParity(-6, 1.0, 1.0));
                }
                return java.util.Optional.of(ref);
            }
            @Override
            public java.util.Optional<net.minecraft.core.HolderSet.Named<NormalNoise>> get(final net.minecraft.tags.TagKey<NormalNoise> id) {
                return java.util.Optional.empty();
            }
        };

        final DensityFunction barrierFn = new NoiseFunction(nBarrier, 1.0, 0.5, DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero());
        final DensityFunction floodFn = new NoiseFunction(nFlood, 1.0, 0.67, DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero());
        final DensityFunction spreadFn = new NoiseFunction(nSpread, 1.0, 0.7142857142857143, DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero());
        final DensityFunction lavaFn = new NoiseFunction(nLava, 1.0, 1.0, DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero());
        final DensityFunction surfFn = DensityFunctions.constant(80.0f);

        final net.minecraft.world.level.levelgen.Aquifer.Config aqCfg =
            new net.minecraft.world.level.levelgen.Aquifer.Config(
                barrierFn, floodFn, spreadFn, lavaFn, DensityFunctions.zero(), surfFn
            );
        // Synthetic finalDensity: solid stone above Y=32, wide open noise cavern at Y=-48..16 (like Deep Dark / Ancient City)
        final DensityFunction caveDensity = DensityFunctions.interpolated(
            DensityFunctions.yClampedGradient(-48, 32, -0.8f, 0.8f), 4, 8
        );
        final net.minecraft.world.level.levelgen.NoiseRouter router =
            new net.minecraft.world.level.levelgen.NoiseRouter(
                DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
                DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
                surfFn, caveDensity
            );
        final net.minecraft.world.level.levelgen.NoiseGeneratorSettings settings =
            new net.minecraft.world.level.levelgen.NoiseGeneratorSettings(
                new net.minecraft.world.level.levelgen.NoiseSettings(1, 2),
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(),
                router,
                Holder.direct(new net.minecraft.world.level.levelgen.material.rule.SequenceRule(java.util.List.of())),
                java.util.List.of(),
                63,
                false,
                java.util.Optional.of(aqCfg),
                false,
                net.minecraft.world.level.levelgen.NoiseGeneratorSettings.DebugFunctions.EMPTY
            );
        final net.minecraft.world.level.levelgen.RandomState randomState =
            net.minecraft.world.level.levelgen.RandomState.create(
                noiseGetter, 42424242L, false,
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 63, router
            );
        final int minY = -64;
        final int genDepth = 384;
        final net.minecraft.world.level.levelgen.WorldGenerationContext wgCtx =
            new net.minecraft.world.level.levelgen.WorldGenerationContext(
                new net.minecraft.world.level.levelgen.FlatLevelSource(
                    new net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings(
                        java.util.Optional.empty(),
                        Holder.direct(new net.minecraft.world.level.biome.Biome.BiomeBuilder()
                            .hasPrecipitation(true).temperature(0.8f).downfall(0.4f)
                            .specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects.Builder().waterColor(0x3F76E4).build())
                            .mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
                            .generationSettings(net.minecraft.world.level.biome.BiomeGenerationSettings.EMPTY)
                            .build()),
                        java.util.List.of()
                    )
                ),
                net.minecraft.world.level.LevelHeightAccessor.create(minY, genDepth)
            ) {
                @Override public int getMinGenY() { return minY; }
                @Override public int getGenDepth() { return genDepth; }
            };
        final OpenClBackendEmitter emitter = new OpenClBackendEmitter();
        final var mode = io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;

        final GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram surfProg =
            GpuVanillaSurfaceCompiler.compile(settings, randomState, wgCtx, false, false, false, emitter, mode, "test_aq_surf");
        assertNotNull(surfProg, "CompiledVanillaSurfaceProgram must compile");
        final OpenClBackendEmitter.DensityVolumeProgram aqProg =
            GpuCarverAquiferCompiler.compile(settings, randomState, surfProg.blockPalette(), emitter, mode, "test_aq_pass");
        assertNotNull(aqProg, "GpuCarverAquiferCompiler must compile");
        assertTrue(aqProg.sourceCode().contains("apply_terrain_aquifer_u8"), "Program must include Pass 1B apply_terrain_aquifer_u8");
        assertFalse(aqProg.sourceCode().contains("cl_khr_fp64"), "Aquifer kernel must not use FP64");

        final int sectionCount = 24;
        final int batchCount = 4;
        final int[] origins = {
            0, minY, 0, 0,
            64, minY, 64, 0,
            -128, minY, 96, 0,
            256, minY, -192, 0
        };
        final byte[] biomesHalo = new byte[batchCount * 36 * sectionCount * 4];
        java.util.Arrays.fill(biomesHalo, (byte) 255);
        final float[] prelimCorners = new float[batchCount * 4];
        java.util.Arrays.fill(prelimCorners, 80.0f);
        final int[] beardMeta = new int[batchCount * 2];
        final int[] beardItems = new int[8];
        final int[] carverMeta = new int[batchCount];
        java.util.Arrays.fill(carverMeta, -1);
        final byte[] outFlags = new byte[batchCount];
        final int[] outEvents = new int[batchCount * GpuCarverAquiferCompiler.MAX_CARVER_EVENTS];
        final int[] outCounts = new int[batchCount];
        final CompactVanillaSurfaceWritebackBuffer waveBuf = new CompactVanillaSurfaceWritebackBuffer(batchCount * sectionCount);
        waveBuf.ensureCapacity(batchCount, sectionCount);

        try (
            GoldLeafOpenClBridge.CompiledKernel kTerrain = GoldLeafOpenClBridge.compileFromSource(
                0, surfProg.terrainProgram().sourceCode(), surfProg.terrainProgram().kernelName(), "-cl-std=CL1.2", surfProg.terrainProgram().spilledLutWords());
            GoldLeafOpenClBridge.CompiledKernel kSurface = GoldLeafOpenClBridge.compileFromSource(
                0, surfProg.surfaceProgram().sourceCode(), surfProg.surfaceProgram().kernelName(), "-cl-std=CL1.2", surfProg.surfaceProgram().spilledLutWords());
            GoldLeafOpenClBridge.CompiledKernel kPack = GoldLeafOpenClBridge.compileFromSource(
                0, surfProg.packProgram().sourceCode(), surfProg.packProgram().kernelName(), "-cl-std=CL1.2", surfProg.packProgram().spilledLutWords());
            GoldLeafOpenClBridge.CompiledKernel kAquifer = GoldLeafOpenClBridge.compileFromSource(
                0, aqProg.sourceCode(), aqProg.kernelName(), "-cl-std=CL1.2", aqProg.spilledLutWords())
        ) {
            final int err = kTerrain.dispatchVanillaSurfaceU8Batch(
                kSurface, kPack, null, kAquifer, null,
                origins, biomesHalo, prelimCorners, beardMeta, beardItems, 0,
                carverMeta, null, 0, 12, minY, 180,
                batchCount, sectionCount, 63, -54, minY, 0L,
                waveBuf.voxelsDirectBuffer(), waveBuf.metaDirectBuffer(), waveBuf.heightmapsDirectBuffer(),
                outFlags, outEvents, outCounts, null
            );
            assertEquals(0, err, "dispatchVanillaSurfaceU8Batch with Pass 1B aquifer failed");
        }

        // Compare GPU Pass 1 + Pass 1B output block-by-block against CPU NoiseBasedAquifer in the underground cavern (Y=-54..15)
        final net.minecraft.world.level.levelgen.Aquifer.FluidStatus lavaStatus =
            new net.minecraft.world.level.levelgen.Aquifer.FluidStatus(-54, net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState());
        final net.minecraft.world.level.levelgen.Aquifer.FluidStatus seaStatus =
            new net.minecraft.world.level.levelgen.Aquifer.FluidStatus(63, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        final net.minecraft.world.level.levelgen.Aquifer.FluidPicker picker = (x, y, z) -> y < Math.min(-54, 63) ? lavaStatus : seaStatus;
        final var posRand = randomState.getOrCreateRandomFactory(Identifier.withDefaultNamespace("aquifer"));

        long totalCaveVoxels = 0L;
        long gpuDryAir = 0L;
        long cpuDryAir = 0L;
        long exactMatches = 0L;
        final net.minecraft.world.level.block.state.BlockState[] palette = surfProg.blockPalette();
        final java.nio.ByteBuffer voxBuf = waveBuf.voxelsDirectBuffer();
        final java.nio.ByteBuffer metaBuf = waveBuf.metaDirectBuffer();

        for (int b = 0; b < batchCount; b++) {
            final int ox = origins[b * 4];
            final int oz = origins[b * 4 + 2];
            final var vol = new net.minecraft.world.level.levelgen.densityfunction.DensityVolume(16, 384, 16, ox, minY, oz);
            final var samplers = randomState.samplersWithContext(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED);
            final var cpuAquifer = aqCfg.create(samplers, posRand, vol, picker);
            final var densitySampler = samplers.get(caveDensity);

            for (int by = -54; by < 16; by++) {
                final int relY = by - minY;
                final int secIdx = b * sectionCount + (relY >> 4);
                final int nonEmpty = Short.toUnsignedInt(metaBuf.getShort((secIdx * 4) * 2));
                final int modeVal = Short.toUnsignedInt(metaBuf.getShort((secIdx * 4 + 1) * 2));
                final int uniformCode = Short.toUnsignedInt(metaBuf.getShort((secIdx * 4 + 2) * 2));
                final int secBase = secIdx * 4096;
                final long usedMask = modeVal == 2 ? voxBuf.getLong(secBase + 2048) : 0L;
                final int[] localToGlobal = modeVal == 2 ? new int[16] : null;
                if (localToGlobal != null) {
                    int li = 0;
                    for (int g = 0; g < 64; g++) {
                        if ((usedMask & (1L << g)) != 0L) {
                            localToGlobal[li++] = g;
                        }
                    }
                }
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        final int bx = ox + lx;
                        final int bz = oz + lz;
                        final double d = densitySampler.sampleValue(bx, by, bz);
                        if (d > 0.0) continue;
                        final var cpuState = cpuAquifer.computeSubstance(bx, by, bz, d);
                        final var expected = cpuState == null ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState() : cpuState;

                        final int gpuCode;
                        if (nonEmpty == 0) {
                            gpuCode = 0;
                        } else if (modeVal == 1) {
                            gpuCode = uniformCode;
                        } else if (modeVal == 2) {
                            final int rowIdx = ((relY & 15) << 4) | lz;
                            final long word = voxBuf.getLong(secBase + (rowIdx << 3));
                            final int lid = (int) ((word >>> (lx << 2)) & 0xFL);
                            gpuCode = localToGlobal[lid];
                        } else {
                            gpuCode = Byte.toUnsignedInt(voxBuf.get(secBase + (((relY & 15) << 8) | (lz << 4) | lx)));
                        }
                        final var actual = palette[gpuCode];
                        totalCaveVoxels++;
                        if (actual.isAir()) gpuDryAir++;
                        if (expected.isAir()) cpuDryAir++;
                        if (actual == expected) exactMatches++;
                    }
                }
            }
        }
        assertTrue(cpuDryAir > totalCaveVoxels / 2, "Synthetic underground cavern must be mostly dry air on CPU");
        assertTrue(gpuDryAir > totalCaveVoxels / 2, "GPU Pass 1B must leave underground caves dry (not flooded!), got " + gpuDryAir + "/" + totalCaveVoxels);
        final double matchPct = 100.0 * (double) exactMatches / (double) totalCaveVoxels;
        assertTrue(
            matchPct >= 99.5,
            "GPU Pass 1B aquifer parity vs CPU NoiseBasedAquifer must be >= 99.5% (got "
                + String.format(java.util.Locale.ROOT, "%.3f%%", matchPct) + ", gpuDry=" + gpuDryAir + ", cpuDry=" + cpuDryAir + ")"
        );
    }

    @Test
    void productionOverworldNoiseRouterLowersAndMatchesMojangCpuSampler(@TempDir final Path tempDir) {
        final net.minecraft.core.HolderLookup.Provider registries =
            net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
        final net.minecraft.world.level.levelgen.NoiseGeneratorSettings overworld = registries
            .lookupOrThrow(net.minecraft.core.registries.Registries.NOISE_SETTINGS)
            .getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD)
            .value();
        final var noiseGetter = registries.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE);
        final net.minecraft.world.level.levelgen.NoiseRouter router = overworld.noiseRouter();
        final net.minecraft.world.level.levelgen.RandomState randomState =
            net.minecraft.world.level.levelgen.RandomState.create(
                noiseGetter,
                42424242L,
                overworld.useLegacyRandomSource(),
                overworld.defaultBlock(),
                overworld.seaLevel(),
                router
            );

        final DensityFunction.CompileContext compileCtx = randomState.paper$compileContext();

        final DensityFunction finalDensity = router.finalDensity();
        final DensityFunctionIrLowerer.LoweringResult lowered = DensityFunctionIrLowerer.lower(
            finalDensity,
            compileCtx,
            "VanillaOverworldRouterFinalDensity",
            true
        );
        assertTrue(
            lowered.isSupported(),
            "Production Overworld NoiseRouter#finalDensity must lower cleanly: " + lowered.unsupportedReason()
        );
        assertTrue(lowered.noiseOctaves() >= 32, "Expected full vanilla overworld noise octaves, got " + lowered.noiseOctaves());

        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("overworld-router-cache"))) {
            final OpenClBackendEmitter.DensityVolumeProgram prog = accel.lowerDensityFunctionProgram(
                finalDensity,
                compileCtx,
                "VanillaOverworldRouterFinalDensity",
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT,
                true
            );
            assertNotNull(prog, "Production Overworld NoiseRouter program must emit OpenCL C");
            if (!accel.isGpuReady()) {
                return;
            }
            final IrOpenClTerrainAccelerator.CompiledDensityKernel compiled = accel.compileProgramBlocking(
                prog,
                TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertTrue(compiled.hasLiveGpuKernel());

            final int batchCount = 2;
            final int sizeX = 5;
            final int sizeY = 49;
            final int sizeZ = 5;
            final int stepX = 4;
            final int stepY = 8;
            final int stepZ = 4;
            final int volumeSize = sizeX * sizeY * sizeZ;
            final int[] origins = {
                0, -64, 0, 0,
                128, -64, -192, 0
            };
            final float[] gpuOut = new float[batchCount * volumeSize];
            accel.executeDensityVolumeBatch(compiled, origins, batchCount, sizeX, sizeY, sizeZ, stepX, stepY, stepZ, gpuOut);

            final DensityFunctionCompiler mojangCompiler = new DensityFunctionCompiler(compileCtx);
            final DensitySampler mojangSampler = mojangCompiler.getSampler(finalDensity);
            final SamplerContext samplerCtx = SamplerContext.builder().enableCaches().build();
            int withinTol = 0;
            int total = batchCount * volumeSize;
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
                        final float cpuVal = cpuBuf.get(i);
                        final float gpuVal = gpuOut[base + i];
                        if (Math.abs(cpuVal - gpuVal) <= 5e-2f || (cpuVal > 0f) == (gpuVal > 0f)) {
                            withinTol++;
                        }
                    }
                }
            }
            final double parityPct = 100.0 * (double) withinTol / (double) total;
            assertTrue(
                parityPct >= 99.5,
                "Production Overworld NoiseRouter GPU parity must be >= 99.5% (got "
                    + String.format(java.util.Locale.ROOT, "%.3f%%", parityPct) + ")"
            );
        }
    }
}
