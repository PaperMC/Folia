package dev.leafcraft.goldleaf.worldgen;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.storage.LcaChunkResidencyCache;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter;
import io.github.dailystruggle.bytecode.transpile.ConceptOptimizerPass;
import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import io.github.dailystruggle.bytecode.transpile.WorldgenKernelSwapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native GoldLeaf {@link TerrainGenerationAccelerator} backed by {@code :bytecode-api}'s Tier-1
 * SSA IR lifter ({@link BytecodeSsaLifter} / {@link ConceptOptimizerPass} /
 * {@link OpenClBackendEmitter}) and {@link GoldLeafOpenClBridge} + {@link OpenClKernelCache}
 * ({@code goldleaf-ADR-004}).
 *
 * <p>Replaces the retired external {@code LeafGen} plugin's reflective interception and hand-authored
 * {@code FN_*} stampers with a directly testable, single-pipeline IR-to-OpenCL compiler targeting
 * MC {@code 26.3}'s chunk-local {@code DensityVolume} flat {@code float[]} layout.</p>
 *
 * <p><b>Off-tick lift/compile isolation ({@code [DSN-3]} / {@code S-005}):</b> All IR lifting,
 * optimization, OpenCL source emission, and {@code clBuildProgram} calls run exclusively on a
 * dedicated bounded daemon executor ({@code goldleaf-ir-opencl-compiler}), never on Minecraft main
 * or Folia region tick threads.</p>
 *
 * @governedBy goldleaf-ADR-004, leafgen-ADR-008
 * @trace S-002, S-004, S-005
 */
public final class IrOpenClTerrainAccelerator implements TerrainGenerationAccelerator, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(IrOpenClTerrainAccelerator.class);
    private static final String DEFAULT_BUILD_OPTS_FP64 = "-cl-std=CL1.2";
    private static final String DEFAULT_BUILD_OPTS_FP32 = "-cl-std=CL1.2 -cl-mad-enable -cl-no-signed-zeros -cl-finite-math-only";

    private final OpenClKernelCache kernelCache;
    private final OpenClBackendEmitter openClEmitter;
    private final ExecutorService compilerExecutor;
    private final ExecutorService waveDispatchExecutor;
    private final WorldgenWaveCoalescer<TerrainTask, Boolean> terrainWaveCoalescer;
    private final WorldgenWaveCoalescer<BiomeTask, Boolean> biomeWaveCoalescer;
    private final int deviceIndex;
    private final ConcurrentHashMap<String, CompletableFuture<CompiledDensityKernel>> inFlightCompiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<@Nullable CompiledDensityKernel>> routerSectionFillKernels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<@Nullable CompiledClimateKernels>> routerBiomeKernels = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final LongAdder coalescedWaves = new LongAdder();
    private final LongAdder gpuAcceleratedChunks = new LongAdder();
    private final LongAdder cpuFallbackChunks = new LongAdder();
    private final LongAdder totalGpuKernelNanos = new LongAdder();
    private final LongAdder totalReadbackNanos = new LongAdder();
    private final LongAdder totalHostApplyNanos = new LongAdder();
    private volatile @Nullable CompactSectionWritebackBuffer waveScratchBuffer;

    public IrOpenClTerrainAccelerator(final Path cacheDir) {
        this(new OpenClKernelCache(cacheDir), 0);
    }

    public IrOpenClTerrainAccelerator(final OpenClKernelCache kernelCache, final int deviceIndex) {
        this.kernelCache = Objects.requireNonNull(kernelCache, "kernelCache");
        this.deviceIndex = deviceIndex;
        this.openClEmitter = new OpenClBackendEmitter();
        final AtomicInteger threadIds = new AtomicInteger(1);
        this.compilerExecutor = Executors.newFixedThreadPool(2, runnable -> {
            final Thread t = new Thread(runnable, "goldleaf-ir-opencl-compiler-" + threadIds.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        this.waveDispatchExecutor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread t = new Thread(runnable, "goldleaf-opencl-wave-dispatch");
            t.setDaemon(true);
            return t;
        });
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        final int waveMax = cfg != null && cfg.worldgen != null
            ? Math.max(1, Math.min(64, cfg.worldgen.waveMaxSize))
            : WorldgenWaveCoalescer.DEFAULT_MAX_WAVE_SIZE;
        final long deadlineNs = cfg != null && cfg.worldgen != null
            ? TimeUnit.MILLISECONDS.toNanos(Math.max(1, Math.min(10, cfg.worldgen.waveFlushDeadlineMs)))
            : WorldgenWaveCoalescer.DEFAULT_FLUSH_DEADLINE_NANOS;
        this.terrainWaveCoalescer = new WorldgenWaveCoalescer<>(
            waveMax,
            deadlineNs,
            (key, batch) -> this.dispatchTerrainWaveBatch(batch),
            this.waveDispatchExecutor
        );
        this.biomeWaveCoalescer = new WorldgenWaveCoalescer<>(
            waveMax,
            deadlineNs,
            (key, batch) -> this.dispatchBiomeWaveBatch(batch),
            this.waveDispatchExecutor
        );
    }

    public OpenClKernelCache kernelCache() {
        return this.kernelCache;
    }

    public boolean isGpuReady() {
        return !this.closed.get() && GoldLeafOpenClBridge.isAvailable() && this.deviceIndex < GoldLeafOpenClBridge.deviceCount();
    }

    /**
     * Primary GoldLeaf frontend ({@code goldleaf-ADR-017}): directly lowers an MC {@code 26.3}
     * {@link DensityFunction} AST into a {@link OpenClBackendEmitter.DensityVolumeProgram} without
     * ASM bytecode scanning. Returns {@code null} (fail-closed) when the tree contains an
     * unsupported custom node.
     */
    public OpenClBackendEmitter.@Nullable DensityVolumeProgram lowerDensityFunctionProgram(
        final DensityFunction root,
        final DensityFunction.@Nullable CompileContext compileContext,
        final String moduleName,
        final ParityMode parityMode,
        final boolean assumeAlignedCoarseGrid
    ) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(moduleName, "moduleName");
        Objects.requireNonNull(parityMode, "parityMode");
        final DensityFunctionIrLowerer.LoweringResult lowered = DensityFunctionIrLowerer.lower(
            root,
            compileContext,
            moduleName,
            assumeAlignedCoarseGrid
        );
        if (!lowered.isSupported() || lowered.module() == null) {
            LOGGER.debug("[GoldLeaf-OpenCL] DensityFunction '{}' fell back to CPU: {}", moduleName, lowered.unsupportedReason());
            return null;
        }
        return optimizeAndEmit(lowered.module(), parityMode);
    }

    /**
     * Lowers an MC {@code 26.3} {@link DensityFunction} AST into a fused
     * {@code fill_section_palette_batch_u2} OpenCL program that computes density, classifies each
     * voxel into 2-bit palette codes, packs {@code (ly << 8) | (lz << 4) | lx} words
     * ({@code 1,024 B/section}), and reduces per-section uniformity/count headers
     * ({@code 8 B/section}) directly on GPU ({@code goldleaf-ADR-017}).
     */
    public OpenClBackendEmitter.@Nullable DensityVolumeProgram lowerSectionPaletteFillProgram(
        final DensityFunction root,
        final DensityFunction.@Nullable CompileContext compileContext,
        final String moduleName,
        final ParityMode parityMode,
        final boolean assumeAlignedCoarseGrid
    ) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(moduleName, "moduleName");
        Objects.requireNonNull(parityMode, "parityMode");
        final DensityFunctionIrLowerer.SplitLoweringResult lowered = DensityFunctionIrLowerer.lowerSplitForSectionFill(
            root,
            compileContext,
            moduleName
        );
        if (!lowered.isSupported() || lowered.module() == null) {
            LOGGER.debug("[GoldLeaf-OpenCL] DensityFunction '{}' fell back to CPU (section-fill): {}", moduleName, lowered.unsupportedReason());
            return null;
        }
        return optimizeAndEmitSectionFill(lowered.module(), lowered.coarseSpec(), parityMode);
    }

    /**
     * Optional secondary frontend: lifts a static 3D density method from {@code ownerClass} bytecode
     * into a {@link OpenClBackendEmitter.DensityVolumeProgram} without requiring a GPU device.
     */
    public OpenClBackendEmitter.DensityVolumeProgram liftStaticMethodProgram(
        final Class<?> ownerClass,
        final String methodName,
        final ParityMode parityMode
    ) throws IOException {
        Objects.requireNonNull(ownerClass, "ownerClass");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(parityMode, "parityMode");
        final List<byte[]> classBytes = WorldgenKernelSwapper.pullClassBytes(ownerClass);
        final BytecodeConceptIr.ConceptModule lifted = BytecodeSsaLifter.liftModule(
            classBytes,
            ownerClass.getName(),
            methodName
        );
        return optimizeAndEmit(lifted, parityMode);
    }

    /**
     * Lifts a live stateful Java density/noise object graph (e.g. a {@code DensityFunction} or
     * custom datapack router instance) via {@link BytecodeSsaLifter#liftInstanceGraph} into a
     * {@link OpenClBackendEmitter.DensityVolumeProgram}.
     */
    public OpenClBackendEmitter.DensityVolumeProgram liftLiveInstanceProgram(
        final Object rootInstance,
        final String entryMethodName,
        final String entryDescriptor,
        final ParityMode parityMode
    ) {
        Objects.requireNonNull(rootInstance, "rootInstance");
        Objects.requireNonNull(entryMethodName, "entryMethodName");
        Objects.requireNonNull(entryDescriptor, "entryDescriptor");
        Objects.requireNonNull(parityMode, "parityMode");
        final BytecodeConceptIr.ConceptModule lifted = BytecodeSsaLifter.liftInstanceGraph(
            rootInstance,
            entryMethodName,
            entryDescriptor
        );
        return optimizeAndEmit(lifted, parityMode);
    }

    /**
     * Compiles (or loads from JAR/disk cache) a lifted {@link OpenClBackendEmitter.DensityVolumeProgram}
     * asynchronously on the dedicated compiler worker pool ({@code [DSN-3]} / {@code S-005}).
     */
    public CompletableFuture<CompiledDensityKernel> compileProgramAsync(
        final OpenClBackendEmitter.DensityVolumeProgram program,
        final ParityMode parityMode
    ) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(parityMode, "parityMode");
        if (this.closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("IrOpenClTerrainAccelerator is closed"));
        }
        final String compileKey = program.kernelDigestSha256() + ":" + parityMode.name();
        return this.inFlightCompiles.computeIfAbsent(compileKey, ignored ->
            CompletableFuture.supplyAsync(() -> compileProgramBlocking(program, parityMode), this.compilerExecutor)
        );
    }

    /**
     * Compiles (or loads from cache) a lifted program on the calling worker thread. Intended for
     * background worker threads and direct unit tests (never call from region tick threads).
     */
    public CompiledDensityKernel compileProgramBlocking(
        final OpenClBackendEmitter.DensityVolumeProgram program,
        final ParityMode parityMode
    ) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(parityMode, "parityMode");
        if (!isGpuReady()) {
            return new CompiledDensityKernel(program, parityMode, null);
        }
        final String buildOpts = parityMode == ParityMode.VANILLA_EXACT
            ? DEFAULT_BUILD_OPTS_FP64
            : DEFAULT_BUILD_OPTS_FP32;
        final GoldLeafOpenClBridge.CompiledKernel gpuKernel = this.kernelCache.getOrCompile(
            this.deviceIndex,
            program,
            buildOpts
        );
        return new CompiledDensityKernel(program, parityMode, gpuKernel);
    }

    /**
     * Executes a compiled {@code DensityVolume} kernel on GPU for {@code batchCount} volumes laid
     * out in MC {@code 26.3} {@code (lx + lz * sizeX) * sizeY + ly} order.
     */
    public DispatchStats executeDensityVolumeBatch(
        final CompiledDensityKernel kernel,
        final int[] originsXyz4,
        final int batchCount,
        final int sizeX,
        final int sizeY,
        final int sizeZ,
        final int stepX,
        final int stepY,
        final int stepZ,
        final float[] outValues
    ) {
        Objects.requireNonNull(kernel, "kernel");
        final GoldLeafOpenClBridge.CompiledKernel gpuKernel = kernel.gpuKernel();
        if (gpuKernel == null || gpuKernel.isClosed()) {
            throw new IllegalStateException("GPU kernel is unavailable for digest " + kernel.program().kernelDigestSha256());
        }
        final long[] timings = new long[2];
        final int err = gpuKernel.dispatchDensityVolumeBatch(
            originsXyz4,
            batchCount,
            sizeX,
            sizeY,
            sizeZ,
            stepX,
            stepY,
            stepZ,
            outValues,
            timings
        );
        if (err != 0) {
            throw new IllegalStateException(
                "OpenCL dispatchDensityVolumeBatch failed (err=" + err + ", kernel=" + kernel.program().kernelName() + ")"
            );
        }
        return new DispatchStats(timings[0], timings[1]);
    }

    /**
     * Executes a compiled {@code fill_section_palette_batch_u2} kernel on GPU for
     * {@code batchCount} chunks of {@code sectionCount} {@code 16x16x16} sections, writing packed
     * 2-bit palette words and per-section metadata directly into the reusable off-heap
     * {@link CompactSectionWritebackBuffer}.
     */
    public DispatchStats executeSectionPaletteFillBatch(
        final CompiledDensityKernel kernel,
        final int[] originsXyz4,
        final int batchCount,
        final int sectionCount,
        final int seaLevel,
        final int deepFluidY,
        final CompactSectionWritebackBuffer outBuffer
    ) {
        Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(outBuffer, "outBuffer");
        final GoldLeafOpenClBridge.CompiledKernel gpuKernel = kernel.gpuKernel();
        if (gpuKernel == null || gpuKernel.isClosed()) {
            throw new IllegalStateException("GPU kernel is unavailable for digest " + kernel.program().kernelDigestSha256());
        }
        outBuffer.ensureCapacity(batchCount, sectionCount);
        final long[] timings = new long[2];
        final int err = gpuKernel.dispatchSectionPaletteFillBatch(
            originsXyz4,
            batchCount,
            sectionCount,
            seaLevel,
            deepFluidY,
            outBuffer.wordsDirectBuffer(),
            outBuffer.metaDirectBuffer(),
            timings
        );
        if (err != 0) {
            throw new IllegalStateException(
                "OpenCL dispatchSectionPaletteFillBatch failed (err=" + err + ", kernel=" + kernel.program().kernelName() + ")"
            );
        }
        return new DispatchStats(timings[0], timings[1]);
    }

    /**
     * End-to-end zero-translation GPU terrain wave: dispatches {@code fill_section_palette_batch_u2}
     * into the caller-owned off-heap {@code scratchBuffer}, converts each chunk directly into a
     * spec-compliant {@code LCA-ICD-3.0} ({@code mode 10} FlatBitPack) {@code RawChunkFrameSnapshot}
     * via {@link CompactLcaChunkFrameBuilder}, and installs it directly into {@code residencyCache}
     * without hydrating {@code LevelChunkSection} or {@code PalettedContainer}
     * ({@code goldleaf-ADR-005}, {@code goldleaf-ADR-017}).
     */
    public LcaWaveDispatchResult executeSectionPaletteFillToLcaCache(
        final CompiledDensityKernel kernel,
        final int[] originsXyz4,
        final long[] chunkKeys,
        final int batchCount,
        final int minSectionY,
        final int sectionCount,
        final int seaLevel,
        final int deepFluidY,
        final int dataVersion,
        final int[] runtimeStateIds4,
        final boolean computeHeightmaps,
        final CompactSectionWritebackBuffer scratchBuffer,
        final LcaChunkResidencyCache residencyCache
    ) {
        Objects.requireNonNull(chunkKeys, "chunkKeys");
        Objects.requireNonNull(residencyCache, "residencyCache");
        if (chunkKeys.length < batchCount) {
            throw new IllegalArgumentException("chunkKeys.length (" + chunkKeys.length + ") < batchCount (" + batchCount + ")");
        }
        final DispatchStats dispatchStats = executeSectionPaletteFillBatch(
            kernel,
            originsXyz4,
            batchCount,
            sectionCount,
            seaLevel,
            deepFluidY,
            scratchBuffer
        );
        final CompactLcaChunkFrameBuilder.BuildResult[] results = new CompactLcaChunkFrameBuilder.BuildResult[batchCount];
        long totalLcaBytes = 0L;
        for (int b = 0; b < batchCount; b++) {
            final int chunkX = originsXyz4[b * 4] >> 4;
            final int chunkZ = originsXyz4[b * 4 + 2] >> 4;
            final CompactLcaChunkFrameBuilder.BuildResult built = CompactLcaChunkFrameBuilder.buildChunkFrame(
                scratchBuffer,
                b,
                chunkX,
                chunkZ,
                minSectionY,
                sectionCount,
                dataVersion,
                runtimeStateIds4,
                computeHeightmaps
            );
            residencyCache.installBaseFrame(chunkKeys[b], built.snapshot());
            results[b] = built;
            totalLcaBytes += built.frameByteLength();
        }
        return new LcaWaveDispatchResult(dispatchStats, results, totalLcaBytes);
    }

    @Override
    public WaveResult accelerateBiomes(final WorldGenContext context, final List<BiomeTask> tasks) {
        if (tasks.isEmpty() || !isGpuReady()) {
            return WaveResult.noneHandled(tasks.size());
        }
        if (tasks.size() == 1) {
            final BiomeTask task = tasks.get(0);
            if (!isEligibleForGpuBiomes(task)) {
                this.cpuFallbackChunks.increment();
                return WaveResult.noneHandled(1);
            }
            final NoiseBasedChunkGenerator nbGenerator = (NoiseBasedChunkGenerator) task.generator();
            final WorldgenWaveCoalescer.WaveKey key = new WorldgenWaveCoalescer.WaveKey(
                task.level().dimension().identifier().toString() + "#biomes",
                System.identityHashCode(nbGenerator.generatorSettings().value().noiseRouter())
            );
            final Boolean handled = this.biomeWaveCoalescer.submit(key, task).join();
            final boolean[] flags = new boolean[] {Boolean.TRUE.equals(handled)};
            return new WaveResult(flags, 0L, 0L, 0L);
        }
        final List<Boolean> handledList = dispatchBiomeWaveBatch(tasks);
        final boolean[] flags = new boolean[tasks.size()];
        for (int i = 0; i < tasks.size(); i++) {
            flags[i] = i < handledList.size() && Boolean.TRUE.equals(handledList.get(i));
        }
        return new WaveResult(flags, 0L, 0L, 0L);
    }

    private boolean isEligibleForGpuBiomes(final BiomeTask task) {
        if (!(task.generator() instanceof NoiseBasedChunkGenerator nbGenerator)) {
            return false;
        }
        if (!(nbGenerator.getBiomeSource() instanceof MultiNoiseBiomeSource)) {
            return false;
        }
        if (!task.blender().isEmpty()) {
            return false;
        }
        final int height = task.chunk().getHeight();
        return height > 0 && (height & 3) == 0;
    }

    private List<Boolean> dispatchBiomeWaveBatch(final List<BiomeTask> batch) {
        final int n = batch.size();
        final List<Boolean> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            results.add(Boolean.FALSE);
        }
        if (n == 0 || !isGpuReady()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final BiomeTask first = batch.get(0);
        if (!isEligibleForGpuBiomes(first)) {
            this.cpuFallbackChunks.add(n);
            return results;
        }
        final ParityMode mode = parityMode();
        final CompiledClimateKernels compiled = getOrScheduleBiomeKernels(first, mode);
        if (compiled == null || !compiled.hasAllLiveGpuKernels()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final int firstHeight = first.chunk().getHeight();
        final int quartSizeY = QuartPos.fromBlock(firstHeight);
        final List<Integer> eligibleIndices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final BiomeTask task = batch.get(i);
            if (isEligibleForGpuBiomes(task) && task.chunk().getHeight() == firstHeight) {
                eligibleIndices.add(i);
            }
        }
        if (eligibleIndices.isEmpty()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final int waveSize = eligibleIndices.size();
        final int[] originsXyz4 = new int[waveSize * 4];
        for (int b = 0; b < waveSize; b++) {
            final BiomeTask task = batch.get(eligibleIndices.get(b));
            final ChunkPos pos = task.chunk().getPos();
            originsXyz4[b * 4] = pos.getMinBlockX();
            originsXyz4[b * 4 + 1] = task.chunk().getMinY();
            originsXyz4[b * 4 + 2] = pos.getMinBlockZ();
            originsXyz4[b * 4 + 3] = 0;
        }

        final int quartPerChunk = 4 * quartSizeY * 4;
        final int totalQuarts = waveSize * quartPerChunk;
        final float[] tempArr = new float[totalQuarts];
        final float[] vegArr = new float[totalQuarts];
        final float[] contArr = new float[totalQuarts];
        final float[] erosArr = new float[totalQuarts];
        final float[] depthArr = new float[totalQuarts];
        final float[] ridgeArr = new float[totalQuarts];

        final DispatchStats sTemp = executeDensityVolumeBatch(compiled.temperature(), originsXyz4, waveSize, 4, quartSizeY, 4, 4, 4, 4, tempArr);
        final DispatchStats sVeg = executeDensityVolumeBatch(compiled.vegetation(), originsXyz4, waveSize, 4, quartSizeY, 4, 4, 4, 4, vegArr);
        final DispatchStats sCont = executeDensityVolumeBatch(compiled.continents(), originsXyz4, waveSize, 4, quartSizeY, 4, 4, 4, 4, contArr);
        final DispatchStats sEros = executeDensityVolumeBatch(compiled.erosion(), originsXyz4, waveSize, 4, quartSizeY, 4, 4, 4, 4, erosArr);
        final DispatchStats sDepth = executeDensityVolumeBatch(compiled.depth(), originsXyz4, waveSize, 4, quartSizeY, 4, 4, 4, 4, depthArr);
        final DispatchStats sRidge = executeDensityVolumeBatch(compiled.ridges(), originsXyz4, waveSize, 4, quartSizeY, 4, 4, 4, 4, ridgeArr);

        final long hostStart = System.nanoTime();
        for (int b = 0; b < waveSize; b++) {
            final int origIdx = eligibleIndices.get(b);
            final BiomeTask task = batch.get(origIdx);
            final ChunkAccess chunk = task.chunk();
            final MultiNoiseBiomeSource multiNoise = (MultiNoiseBiomeSource) task.generator().getBiomeSource();
            final int minQuartX = QuartPos.fromBlock(chunk.getPos().getMinBlockX());
            final int minQuartY = QuartPos.fromBlock(chunk.getMinY());
            final int minQuartZ = QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
            final int base = b * quartPerChunk;

            final BiomeResolver resolver = (quartX, quartY, quartZ) -> {
                final int lx = quartX - minQuartX;
                final int ly = quartY - minQuartY;
                final int lz = quartZ - minQuartZ;
                final int idx = base + (lx + lz * 4) * quartSizeY + ly;
                return multiNoise.getNoiseBiome(
                    Climate.target(
                        tempArr[idx],
                        vegArr[idx],
                        contArr[idx],
                        erosArr[idx],
                        depthArr[idx],
                        ridgeArr[idx]
                    )
                );
            };
            chunk.fillBiomesFromNoise(resolver);
            results.set(origIdx, Boolean.TRUE);
        }
        final long hostNanos = System.nanoTime() - hostStart;

        this.coalescedWaves.increment();
        this.gpuAcceleratedChunks.add(waveSize);
        if (waveSize < n) {
            this.cpuFallbackChunks.add(n - waveSize);
        }
        this.totalGpuKernelNanos.add(
            sTemp.gpuKernelNanos() + sVeg.gpuKernelNanos() + sCont.gpuKernelNanos()
                + sEros.gpuKernelNanos() + sDepth.gpuKernelNanos() + sRidge.gpuKernelNanos()
        );
        this.totalReadbackNanos.add(
            sTemp.readbackNanos() + sVeg.readbackNanos() + sCont.readbackNanos()
                + sEros.readbackNanos() + sDepth.readbackNanos() + sRidge.readbackNanos()
        );
        this.totalHostApplyNanos.add(hostNanos);
        return results;
    }

    private @Nullable CompiledClimateKernels getOrScheduleBiomeKernels(
        final BiomeTask sampleTask,
        final ParityMode mode
    ) {
        final NoiseBasedChunkGenerator nbGenerator = (NoiseBasedChunkGenerator) sampleTask.generator();
        final NoiseRouter router = nbGenerator.generatorSettings().value().noiseRouter();
        final String dimId = sampleTask.level().dimension().identifier().toString();
        final String routerKey = dimId + ":" + sampleTask.randomState().seed()
            + ":" + System.identityHashCode(router) + ":" + mode.name() + ":biomes";
        final CompletableFuture<@Nullable CompiledClimateKernels> future = this.routerBiomeKernels.computeIfAbsent(
            routerKey,
            ignored -> CompletableFuture.supplyAsync(() -> {
                final DensityFunction.CompileContext compileCtx = sampleTask.randomState().paper$compileContext();
                final String baseName = "goldleaf_" + dimId.replace(':', '_').replace('/', '_') + "_biome_";
                final CompiledDensityKernel temp = lowerAndCompileClimateChannel(router.temperature(), compileCtx, baseName + "temp", mode);
                if (temp == null) return null;
                final CompiledDensityKernel veg = lowerAndCompileClimateChannel(router.vegetation(), compileCtx, baseName + "veg", mode);
                if (veg == null) return null;
                final CompiledDensityKernel cont = lowerAndCompileClimateChannel(router.continents(), compileCtx, baseName + "cont", mode);
                if (cont == null) return null;
                final CompiledDensityKernel eros = lowerAndCompileClimateChannel(router.erosion(), compileCtx, baseName + "eros", mode);
                if (eros == null) return null;
                final CompiledDensityKernel depth = lowerAndCompileClimateChannel(router.depth(), compileCtx, baseName + "depth", mode);
                if (depth == null) return null;
                final CompiledDensityKernel ridges = lowerAndCompileClimateChannel(router.ridges(), compileCtx, baseName + "ridges", mode);
                if (ridges == null) return null;
                return new CompiledClimateKernels(temp, veg, cont, eros, depth, ridges);
            }, this.compilerExecutor).exceptionally(ex -> {
                LOGGER.warn("[GoldLeaf-OpenCL S-004] Async biome climate kernel compile failed for dimension '{}'", dimId, ex);
                return null;
            })
        );
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        final boolean requireAccel = cfg != null && cfg.worldgen != null && cfg.worldgen.requireAccelerator;
        if (!future.isDone() && !requireAccel) {
            return null;
        }
        return future.join();
    }

    private @Nullable CompiledDensityKernel lowerAndCompileClimateChannel(
        final DensityFunction fn,
        final DensityFunction.@Nullable CompileContext compileCtx,
        final String moduleName,
        final ParityMode mode
    ) {
        final DensityFunctionIrLowerer.LoweringResult lowered = DensityFunctionIrLowerer.lower(fn, compileCtx, moduleName, true);
        if (!lowered.isSupported() || lowered.module() == null) {
            LOGGER.debug("[GoldLeaf-OpenCL] Climate channel '{}' not GPU-lowerable: {}", moduleName, lowered.unsupportedReason());
            return null;
        }
        final OpenClBackendEmitter.DensityVolumeProgram program = optimizeAndEmit(lowered.module(), mode);
        return compileProgramBlocking(program, mode);
    }

    @Override
    public WaveResult accelerateTerrain(final @Nullable WorldGenContext context, final List<TerrainTask> tasks) {
        if (tasks.isEmpty() || !isGpuReady()) {
            return WaveResult.noneHandled(tasks.size());
        }
        if (tasks.size() == 1) {
            final TerrainTask task = tasks.get(0);
            if (!isEligibleForGpuSectionFill(task)) {
                this.cpuFallbackChunks.increment();
                return WaveResult.noneHandled(1);
            }
            final WorldgenWaveCoalescer.WaveKey key = new WorldgenWaveCoalescer.WaveKey(
                task.level().dimension().identifier().toString(),
                System.identityHashCode(task.noiseRouter())
            );
            final Boolean handled = this.terrainWaveCoalescer.submit(key, task).join();
            final boolean[] flags = new boolean[] {Boolean.TRUE.equals(handled)};
            return new WaveResult(flags, 0L, 0L, 0L);
        }
        final List<Boolean> handledList = dispatchTerrainWaveBatch(tasks);
        final boolean[] flags = new boolean[tasks.size()];
        for (int i = 0; i < tasks.size(); i++) {
            flags[i] = i < handledList.size() && Boolean.TRUE.equals(handledList.get(i));
        }
        return new WaveResult(flags, 0L, 0L, 0L);
    }

    private boolean isEligibleForGpuSectionFill(final TerrainTask task) {
        if (task.beardifier() != null && task.beardifier() != Beardifier.EMPTY) {
            return false;
        }
        if (!task.blender().isEmpty()) {
            return false;
        }
        final DensityVolume volume = task.volume();
        if (volume.sizeX() != 16 || volume.sizeZ() != 16 || volume.sizeY() <= 0 || (volume.sizeY() & 15) != 0) {
            return false;
        }
        return volume.stepBlockX() == 1 && volume.stepBlockY() == 1 && volume.stepBlockZ() == 1;
    }

    private List<Boolean> dispatchTerrainWaveBatch(final List<TerrainTask> batch) {
        final int n = batch.size();
        final List<Boolean> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            results.add(Boolean.FALSE);
        }
        if (n == 0 || !isGpuReady()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final TerrainTask first = batch.get(0);
        final ParityMode mode = parityMode();
        final CompiledDensityKernel compiled = getOrScheduleRouterKernel(first, mode);
        if (compiled == null || !compiled.hasLiveGpuKernel()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final NoiseGeneratorSettings settings = first.settings();
        final DensityVolume firstVol = first.volume();
        final int sectionCount = firstVol.sizeY() >>> 4;
        final int seaLevel = settings.seaLevel();
        final int deepFluidY = Math.min(-54, seaLevel);

        final List<Integer> eligibleIndices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final TerrainTask task = batch.get(i);
            final DensityVolume vol = task.volume();
            if (isEligibleForGpuSectionFill(task)
                && (vol.sizeY() >>> 4) == sectionCount
                && task.settings().seaLevel() == seaLevel) {
                eligibleIndices.add(i);
            }
        }
        if (eligibleIndices.isEmpty()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final int waveSize = eligibleIndices.size();
        final int[] originsXyz4 = new int[waveSize * 4];
        for (int k = 0; k < waveSize; k++) {
            final DensityVolume vol = batch.get(eligibleIndices.get(k)).volume();
            originsXyz4[k * 4] = vol.minBlockX();
            originsXyz4[k * 4 + 1] = vol.minBlockY();
            originsXyz4[k * 4 + 2] = vol.minBlockZ();
            originsXyz4[k * 4 + 3] = 0;
        }

        final CompactSectionWritebackBuffer scratch = ensureWaveScratchBuffer(waveSize * sectionCount);
        final DispatchStats stats;
        try {
            stats = executeSectionPaletteFillBatch(
                compiled,
                originsXyz4,
                waveSize,
                sectionCount,
                seaLevel,
                deepFluidY,
                scratch
            );
        } catch (final RuntimeException ex) {
            LOGGER.warn("[GoldLeaf-OpenCL S-004] GPU wave dispatch failed for {} chunks; falling back to CPU", waveSize, ex);
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final long hostStart = System.nanoTime();
        for (int k = 0; k < waveSize; k++) {
            final int origIdx = eligibleIndices.get(k);
            final TerrainTask task = batch.get(origIdx);
            final DensityVolume vol = task.volume();
            CompactSectionWritebackApplier.applyToChunk(
                scratch,
                k,
                sectionCount,
                vol.minBlockY(),
                task.chunk(),
                Blocks.AIR.defaultBlockState(),
                task.settings().defaultBlock(),
                task.settings().defaultFluid(),
                Blocks.LAVA.defaultBlockState()
            );
            results.set(origIdx, Boolean.TRUE);
        }
        final long hostNanos = System.nanoTime() - hostStart;

        this.coalescedWaves.increment();
        this.gpuAcceleratedChunks.add(waveSize);
        if (waveSize < n) {
            this.cpuFallbackChunks.add(n - waveSize);
        }
        this.totalGpuKernelNanos.add(stats.gpuKernelNanos());
        this.totalReadbackNanos.add(stats.readbackNanos());
        this.totalHostApplyNanos.add(hostNanos);

        final long totalAccel = this.gpuAcceleratedChunks.sum();
        if (totalAccel == waveSize || (totalAccel % 128L) < waveSize) {
            logTelemetrySummary("progress");
        }
        return results;
    }

    private CompactSectionWritebackBuffer ensureWaveScratchBuffer(final int requiredSections) {
        CompactSectionWritebackBuffer buf = this.waveScratchBuffer;
        if (buf == null || buf.capacitySections() < requiredSections) {
            buf = new CompactSectionWritebackBuffer(Math.max(requiredSections, 24 * 24));
            this.waveScratchBuffer = buf;
        }
        return buf;
    }

    private @Nullable CompiledDensityKernel getOrScheduleRouterKernel(
        final TerrainTask sampleTask,
        final ParityMode mode
    ) {
        final String dimId = sampleTask.level().dimension().identifier().toString();
        final String routerKey = dimId + ":" + sampleTask.randomState().seed()
            + ":" + System.identityHashCode(sampleTask.noiseRouter()) + ":" + mode.name();
        final CompletableFuture<@Nullable CompiledDensityKernel> future = this.routerSectionFillKernels.computeIfAbsent(
            routerKey,
            ignored -> CompletableFuture.supplyAsync(() -> {
                final long startNs = System.nanoTime();
                LOGGER.info(
                    "[GoldLeaf-OpenCL] Starting async IR lowering + OpenCL compile for dimension '{}' (mode={})...",
                    dimId,
                    mode
                );
                final String moduleName = "goldleaf_" + dimId.replace(':', '_').replace('/', '_') + "_terrain";
                final DensityFunctionIrLowerer.SplitLoweringResult lowered = DensityFunctionIrLowerer.lowerSplitForSectionFill(
                    sampleTask.noiseRouter().finalDensity(),
                    sampleTask.randomState().paper$compileContext(),
                    moduleName
                );
                if (!lowered.isSupported() || lowered.module() == null) {
                    LOGGER.info(
                        "[GoldLeaf-OpenCL] Dimension '{}' finalDensity not GPU-lowerable ({}); keeping on CPU.",
                        dimId,
                        lowered.unsupportedReason()
                    );
                    return null;
                }
                final double lowerMs = (System.nanoTime() - startNs) / 1_000_000.0;
                final long emitStartNs = System.nanoTime();
                final OpenClBackendEmitter.DensityVolumeProgram program = optimizeAndEmitSectionFill(
                    lowered.module(),
                    lowered.coarseSpec(),
                    mode
                );
                final double emitMs = (System.nanoTime() - emitStartNs) / 1_000_000.0;
                final String coarseDesc = lowered.coarseSpec() != null
                    ? (lowered.coarseSpec().gridSizeXz() + "x" + lowered.coarseSpec().gridSizeY() + "x"
                        + lowered.coarseSpec().gridSizeXz() + " ch=" + lowered.coarseSpec().coarseFunctionNames().size())
                    : "dense";
                LOGGER.info(
                    "[GoldLeaf-OpenCL] Dimension '{}' lowered ({} ms) + emitted OpenCL ({} ms, {} chars, coarse={}, nodes={}, dedup={}, octaves={}, lutWords={}, digest={}..); compiling on GPU...",
                    dimId,
                    String.format(Locale.ROOT, "%.1f", lowerMs),
                    String.format(Locale.ROOT, "%.1f", emitMs),
                    program.sourceCode().length(),
                    coarseDesc,
                    lowered.loweredNodes(),
                    lowered.deduplicatedNodes(),
                    lowered.noiseOctaves(),
                    program.spilledLutWords().length,
                    program.kernelDigestSha256().substring(0, Math.min(12, program.kernelDigestSha256().length()))
                );
                final long buildStartNs = System.nanoTime();
                final CompiledDensityKernel compiled = compileProgramBlocking(program, mode);
                final double buildMs = (System.nanoTime() - buildStartNs) / 1_000_000.0;
                final double totalMs = (System.nanoTime() - startNs) / 1_000_000.0;
                LOGGER.info(
                    "[GoldLeaf-OpenCL] Dimension '{}' GPU kernel ready in {} ms (build/load={} ms, mode={}, digest={}..)",
                    dimId,
                    String.format(Locale.ROOT, "%.1f", totalMs),
                    String.format(Locale.ROOT, "%.1f", buildMs),
                    mode,
                    program.kernelDigestSha256().substring(0, Math.min(12, program.kernelDigestSha256().length()))
                );
                return compiled;
            }, this.compilerExecutor).exceptionally(ex -> {
                LOGGER.warn("[GoldLeaf-OpenCL S-004] Async kernel lowering/compile failed for dimension '{}'", dimId, ex);
                return null;
            })
        );
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        final boolean requireAccel = cfg != null && cfg.worldgen != null && cfg.worldgen.requireAccelerator;
        if (!future.isDone() && !requireAccel) {
            return null;
        }
        return future.join();
    }

    public void logTelemetrySummary(final String phase) {
        final long waves = this.coalescedWaves.sum();
        final long accel = this.gpuAcceleratedChunks.sum();
        final long fallback = this.cpuFallbackChunks.sum();
        final double avgWave = waves == 0L ? 0.0 : (double) accel / (double) waves;
        LOGGER.info(
            "[GoldLeaf-OpenCL] Telemetry ({}): waves={}, gpuChunks={}, cpuFallback={}, avgChunksPerWave={}, gpuKernel={}ms, readback={}ms, hostApply={}ms",
            phase,
            waves,
            accel,
            fallback,
            String.format(Locale.ROOT, "%.2f", avgWave),
            String.format(Locale.ROOT, "%.2f", this.totalGpuKernelNanos.sum() / 1_000_000.0),
            String.format(Locale.ROOT, "%.2f", this.totalReadbackNanos.sum() / 1_000_000.0),
            String.format(Locale.ROOT, "%.2f", this.totalHostApplyNanos.sum() / 1_000_000.0)
        );
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.terrainWaveCoalescer.close();
            this.biomeWaveCoalescer.close();
            if (this.gpuAcceleratedChunks.sum() > 0L || this.cpuFallbackChunks.sum() > 0L) {
                logTelemetrySummary("shutdown");
            }
            this.inFlightCompiles.clear();
            this.routerSectionFillKernels.clear();
            this.routerBiomeKernels.clear();
            this.waveDispatchExecutor.shutdownNow();
            this.compilerExecutor.shutdownNow();
            this.kernelCache.close();
        }
    }

    private OpenClBackendEmitter.DensityVolumeProgram optimizeAndEmit(
        final BytecodeConceptIr.ConceptModule lifted,
        final ParityMode parityMode
    ) {
        if (!lifted.unmappedReasons().isEmpty()) {
            LOGGER.warn(
                "[GoldLeaf-IR S-004] Lifted module '{}' reported {} unmapped construct(s): {}",
                lifted.moduleName(),
                lifted.unmappedReasons().size(),
                lifted.unmappedReasons()
            );
        }
        final ConceptBackendEmitter.GpuExactArithmeticMode arithmeticMode = selectArithmeticMode(parityMode);
        final ConceptBackendEmitter.EmitOptions emitOptions = ConceptBackendEmitter.EmitOptions.of(
            arithmeticMode,
            ConceptBackendEmitter.BatchLayout.DENSITY_VOLUME_BATCH_F32
        );
        final BytecodeConceptIr.ConceptModule optimized = ConceptOptimizerPass.optimize(lifted, emitOptions);
        return this.openClEmitter.emitDensityVolumeBatchProgram(optimized, emitOptions);
    }

    private OpenClBackendEmitter.DensityVolumeProgram optimizeAndEmitSectionFill(
        final BytecodeConceptIr.ConceptModule lifted,
        final OpenClBackendEmitter.@Nullable CoarseInterpSpec coarseSpec,
        final ParityMode parityMode
    ) {
        if (!lifted.unmappedReasons().isEmpty()) {
            LOGGER.warn(
                "[GoldLeaf-IR S-004] Lifted module '{}' reported {} unmapped construct(s): {}",
                lifted.moduleName(),
                lifted.unmappedReasons().size(),
                lifted.unmappedReasons()
            );
        }
        final ConceptBackendEmitter.GpuExactArithmeticMode arithmeticMode = selectArithmeticMode(parityMode);
        final ConceptBackendEmitter.EmitOptions emitOptions = ConceptBackendEmitter.EmitOptions.of(
            arithmeticMode,
            ConceptBackendEmitter.BatchLayout.SECTION_PALETTE_FILL_U2
        );
        final BytecodeConceptIr.ConceptModule optimized = ConceptOptimizerPass.optimize(lifted, emitOptions);
        if (coarseSpec != null) {
            return this.openClEmitter.emitInterpolatedSectionPaletteFillProgram(optimized, emitOptions, coarseSpec);
        }
        return this.openClEmitter.emitSectionPaletteFillProgram(optimized, emitOptions);
    }

    private ConceptBackendEmitter.GpuExactArithmeticMode selectArithmeticMode(final ParityMode parityMode) {
        if (parityMode == ParityMode.FAST) {
            return ConceptBackendEmitter.GpuExactArithmeticMode.DOWNCAST_FP32;
        }
        if (isGpuReady() && !GoldLeafOpenClBridge.deviceSupportsFp64(this.deviceIndex)) {
            return ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;
        }
        return ConceptBackendEmitter.GpuExactArithmeticMode.NATIVE_FP64;
    }

    public record CompiledDensityKernel(
        OpenClBackendEmitter.DensityVolumeProgram program,
        ParityMode parityMode,
        GoldLeafOpenClBridge.@Nullable CompiledKernel gpuKernel
    ) {
        public CompiledDensityKernel {
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(parityMode, "parityMode");
        }

        public boolean hasLiveGpuKernel() {
            return this.gpuKernel != null && !this.gpuKernel.isClosed();
        }
    }

    public record DispatchStats(long gpuKernelNanos, long readbackNanos) {
    }

    private record CompiledClimateKernels(
        CompiledDensityKernel temperature,
        CompiledDensityKernel vegetation,
        CompiledDensityKernel continents,
        CompiledDensityKernel erosion,
        CompiledDensityKernel depth,
        CompiledDensityKernel ridges
    ) {
        boolean hasAllLiveGpuKernels() {
            return this.temperature.hasLiveGpuKernel()
                && this.vegetation.hasLiveGpuKernel()
                && this.continents.hasLiveGpuKernel()
                && this.erosion.hasLiveGpuKernel()
                && this.depth.hasLiveGpuKernel()
                && this.ridges.hasLiveGpuKernel();
        }
    }

    public record LcaWaveDispatchResult(
        DispatchStats dispatchStats,
        CompactLcaChunkFrameBuilder.BuildResult[] chunkResults,
        long totalLcaBytes
    ) {
    }
}
