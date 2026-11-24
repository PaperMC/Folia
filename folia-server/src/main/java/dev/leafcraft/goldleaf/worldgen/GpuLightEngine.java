package dev.leafcraft.goldleaf.worldgen;

import ca.spottedleaf.moonrise.patches.starlight.blockstate.StarlightAbstractBlockState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vanilla-exact OpenCL 1.2 GPU Sky+Block lighting engine ({@code compute_chunk_light_batch}).
 *
 * <p>Coalesces concurrent {@code ChunkLightTask} workers into multi-chunk GPU waves on dedicated
 * {@code LightDmaSlot} command queues, computing exact intra-chunk sky column drop, 3D wavefront
 * sky+block light propagation (zero FP64), and Starlight {@code byte[2048]} nibble packing on GPU
 * so Starlight on CPU only performs cross-chunk {@code checkChunkEdges} propagation.</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017
 * @trace S-002, S-004, S-005
 */
public final class GpuLightEngine implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GpuLightEngine.class);

    public static final String KERNEL_NAME = "compute_chunk_light_batch";
    public static final byte FLAG_SKY_ALL_ZERO = io.papermc.paper.spi.PaperWorldgenSpi.LIGHT_FLAG_SKY_ALL_ZERO;
    public static final byte FLAG_SKY_ALL_15 = io.papermc.paper.spi.PaperWorldgenSpi.LIGHT_FLAG_SKY_ALL_15;
    public static final byte FLAG_BLK_ALL_ZERO = io.papermc.paper.spi.PaperWorldgenSpi.LIGHT_FLAG_BLK_ALL_ZERO;
    public static final byte FLAG_BOTTOM_LEAK = io.papermc.paper.spi.PaperWorldgenSpi.LIGHT_FLAG_BOTTOM_LEAK;

    private static final int WAVE_MAX_CHUNKS = 24;
    private static final long WAVE_FLUSH_DEADLINE_NANOS = TimeUnit.MILLISECONDS.toNanos(1L);
    private static final int DISPATCH_THREADS = 2;
    private static final WorldgenWaveCoalescer.WaveKey LIGHT_WAVE_KEY = new WorldgenWaveCoalescer.WaveKey("light:sky", 0);

    private static final ThreadLocal<int[]> UNPACK_SCRATCH = ThreadLocal.withInitial(() -> new int[4096]);

    private final int deviceIndex;
    private final @Nullable OpenClKernelCache kernelCache;
    private final AtomicReference<GoldLeafOpenClBridge.CompiledKernel> compiledKernel = new AtomicReference<>();
    private final AtomicBoolean compileStarted = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ExecutorService dispatchExecutor;
    private final WorldgenWaveCoalescer<PackedLightChunk, io.papermc.paper.spi.PaperWorldgenSpi.@Nullable PrecomputedChunkLight> waveCoalescer;

    private final LongAdder gpuWaves = new LongAdder();
    private final LongAdder gpuChunks = new LongAdder();
    private final LongAdder cpuFallbackChunks = new LongAdder();
    private final LongAdder totalKernelNanos = new LongAdder();
    private final LongAdder totalReadbackNanos = new LongAdder();
    private final LongAdder totalPackNanos = new LongAdder();

    private record PackedLightChunk(
        int activeSections,
        boolean hasBlockEmission,
        byte[] voxelInfo
    ) {}

    public GpuLightEngine(final int deviceIndex, final @Nullable OpenClKernelCache kernelCache) {
        this.deviceIndex = deviceIndex;
        this.kernelCache = kernelCache;
        final AtomicInteger tid = new AtomicInteger(1);
        this.dispatchExecutor = Executors.newFixedThreadPool(DISPATCH_THREADS, r -> {
            final Thread t = new Thread(r, "goldleaf-opencl-light-" + tid.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return t;
        });
        this.waveCoalescer = new WorldgenWaveCoalescer<>(
            WAVE_MAX_CHUNKS,
            WAVE_FLUSH_DEADLINE_NANOS,
            this::dispatchLightWave,
            this.dispatchExecutor
        );
    }

    /** Triggers asynchronous background compilation of {@code compute_chunk_light_batch}. */
    public void warmCompileAsync() {
        if (this.closed.get() || !this.compileStarted.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(this::compileBlocking, this.dispatchExecutor);
    }

    /** Synchronously compiles the kernel if not yet compiled (for unit tests). */
    public GoldLeafOpenClBridge.@Nullable CompiledKernel ensureCompiledBlocking() {
        final GoldLeafOpenClBridge.CompiledKernel existing = this.compiledKernel.get();
        if (existing != null) {
            return existing;
        }
        compileBlocking();
        return this.compiledKernel.get();
    }

    private void compileBlocking() {
        if (this.closed.get() || this.compiledKernel.get() != null) {
            return;
        }
        final long t0 = System.nanoTime();
        try {
            final String source = buildLightKernelSource();
            final String opts = "-cl-std=CL1.2";
            final GoldLeafOpenClBridge.CompiledKernel kernel;
            if (this.kernelCache != null) {
                final String digest = sha256Hex("goldleaf-light-v1\n" + source);
                final io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter.DensityVolumeProgram prog =
                    new io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter.DensityVolumeProgram(
                        KERNEL_NAME, source, digest, new int[0], 0, 0, 0
                    );
                kernel = this.kernelCache.getOrCompile(this.deviceIndex, prog, opts);
            } else {
                kernel = GoldLeafOpenClBridge.compileFromSource(this.deviceIndex, source, KERNEL_NAME, opts, new int[0]);
            }
            if (kernel == null) {
                return;
            }
            if (!this.closed.get() && this.compiledKernel.compareAndSet(null, kernel)) {
                LOGGER.info(
                    "[GoldLeaf-OpenCL] Vanilla-exact GPU Sky+Block light kernel ready in {} ms",
                    String.format(Locale.ROOT, "%.1f", (System.nanoTime() - t0) / 1_000_000.0)
                );
            } else if (this.kernelCache == null) {
                kernel.close();
            }
        } catch (final Throwable ex) {
            LOGGER.warn("[GoldLeaf-OpenCL S-004] Failed to compile GPU light kernel; light will stay on CPU Starlight", ex);
        }
    }

    private static String sha256Hex(final String text) {
        try {
            final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            final byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(hash.length * 2);
            for (final byte b : hash) {
                sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (final java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Asynchronously packs and submits {@code chunk} into the multi-chunk GPU light wave coalescer
     * without blocking the calling thread ({@code S-005}). Returns {@code null} when GPU lighting is
     * disabled or the kernel is not yet compiled.
     */
    public @Nullable CompletableFuture<io.papermc.paper.spi.PaperWorldgenSpi.@Nullable PrecomputedChunkLight> submitChunkLightAsync(
        final ChunkAccess chunk,
        final Boolean @Nullable [] emptySections,
        final boolean hasSkyLight,
        final Executor packExecutor
    ) {
        if (this.closed.get() || !hasSkyLight) {
            return null;
        }
        final dev.leafcraft.goldleaf.config.GoldLeafConfig cfg = dev.leafcraft.goldleaf.config.GoldLeafConfig.get();
        if (cfg == null || cfg.worldgen == null || !cfg.worldgen.gpuLightEnabled) {
            return null;
        }
        if (this.compiledKernel.get() == null) {
            warmCompileAsync();
            this.cpuFallbackChunks.increment();
            return null;
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (this.closed.get()) {
                    return null;
                }
                final long tPack0 = System.nanoTime();
                final PackedLightChunk packed = packChunkVoxels(chunk, emptySections);
                this.totalPackNanos.add(System.nanoTime() - tPack0);
                return packed;
            }, packExecutor).thenCompose(packed -> {
                if (packed == null) {
                    this.cpuFallbackChunks.increment();
                    return CompletableFuture.completedFuture(null);
                }
                if (packed.activeSections() == 0) {
                    return CompletableFuture.completedFuture(
                        new io.papermc.paper.spi.PaperWorldgenSpi.PrecomputedChunkLight(
                            0, false, new byte[0], 0, new byte[0], 0, null, 0
                        )
                    );
                }
                return this.waveCoalescer.submit(LIGHT_WAVE_KEY, packed);
            }).exceptionally(ex -> {
                LOGGER.warn("[GoldLeaf-OpenCL S-004] Async GPU light submission failed for chunk {}; falling back to CPU Starlight", chunk.getPos(), ex);
                this.cpuFallbackChunks.increment();
                return null;
            });
        } catch (final Throwable ex) {
            LOGGER.warn("[GoldLeaf-OpenCL S-004] Failed to enqueue async GPU light pack task for chunk {}; falling back to CPU Starlight", chunk.getPos(), ex);
            this.cpuFallbackChunks.increment();
            return null;
        }
    }

    /**
     * Submits a raw packed voxel chunk through the async wave coalescer (for unit & GPU parity tests).
     */
    public CompletableFuture<io.papermc.paper.spi.PaperWorldgenSpi.@Nullable PrecomputedChunkLight> submitPackedForTest(
        final byte[] voxelInfo,
        final int activeSections,
        final boolean hasBlockEmission
    ) {
        ensureCompiledBlocking();
        return this.waveCoalescer.submit(LIGHT_WAVE_KEY, new PackedLightChunk(activeSections, hasBlockEmission, voxelInfo));
    }

    /**
     * Computes vanilla-exact intra-chunk sky and block light nibble arrays on GPU for {@code chunk}.
     * Returns {@code null} when the kernel is not yet ready, the chunk contains conditionally-opaque
     * sided blocks, or any dispatch error occurs (clean CPU Starlight fallback).
     */
    public io.papermc.paper.spi.PaperWorldgenSpi.@Nullable PrecomputedChunkLight computeChunkLight(
        final ChunkAccess chunk,
        final Boolean[] emptySections,
        final boolean hasSkyLight
    ) {
        if (this.closed.get() || !hasSkyLight) {
            return null;
        }
        final dev.leafcraft.goldleaf.config.GoldLeafConfig cfg = dev.leafcraft.goldleaf.config.GoldLeafConfig.get();
        if (cfg == null || cfg.worldgen == null || !cfg.worldgen.gpuLightEnabled) {
            return null;
        }
        final GoldLeafOpenClBridge.CompiledKernel kernel = this.compiledKernel.get();
        if (kernel == null) {
            warmCompileAsync();
            this.cpuFallbackChunks.increment();
            return null;
        }

        final long tPack0 = System.nanoTime();
        final PackedLightChunk packed = packChunkVoxels(chunk, emptySections);
        this.totalPackNanos.add(System.nanoTime() - tPack0);
        if (packed == null) {
            this.cpuFallbackChunks.increment();
            return null;
        }
        final int secs = packed.activeSections();
        if (secs == 0) {
            return new io.papermc.paper.spi.PaperWorldgenSpi.PrecomputedChunkLight(0, false, new byte[0], 0, new byte[0], 0, null, 0);
        }

        // Zero-sleep direct dispatch onto lock-free 4-slot native LightDmaSlot pool
        final int[] meta4 = new int[] { 0, secs, packed.hasBlockEmission() ? 1 : 0, 0 };
        final byte[] outSky = new byte[secs * 2048];
        final byte[] outBlk = packed.hasBlockEmission() ? new byte[secs * 2048] : null;
        final byte[] outFlags = new byte[secs];
        final long[] timings = new long[2];
        try {
            final int rc = kernel.dispatchLightBatch(
                meta4, packed.voxelInfo(), 1, secs, packed.hasBlockEmission(), outSky, outBlk, outFlags, timings
            );
            if (rc != 0) {
                LOGGER.warn("[GoldLeaf-OpenCL S-004] GPU light dispatch returned rc={} for chunk {}; falling back to CPU Starlight", rc, chunk.getPos());
                this.cpuFallbackChunks.increment();
                return null;
            }
            this.gpuWaves.increment();
            this.gpuChunks.increment();
            this.totalKernelNanos.add(timings[0]);
            this.totalReadbackNanos.add(timings[1]);
            final io.papermc.paper.spi.PaperWorldgenSpi.PrecomputedChunkLight res =
                new io.papermc.paper.spi.PaperWorldgenSpi.PrecomputedChunkLight(
                    secs, packed.hasBlockEmission(), outFlags, 0, outSky, 0, outBlk, 0
                );
            if (res.hasBottomLeak()) {
                this.cpuFallbackChunks.increment();
                return null;
            }
            return res;
        } catch (final Throwable ex) {
            LOGGER.warn("[GoldLeaf-OpenCL S-004] GPU light dispatch failed for chunk {}; falling back to CPU Starlight", chunk.getPos(), ex);
            this.cpuFallbackChunks.increment();
            return null;
        }
    }

    /**
     * Direct synchronous single-wave dispatch helper for unit and GPU parity tests.
     */
    public io.papermc.paper.spi.PaperWorldgenSpi.@Nullable PrecomputedChunkLight dispatchSingleChunkForTest(
        final byte[] voxelInfo,
        final int activeSections,
        final boolean hasBlockEmission
    ) {
        final GoldLeafOpenClBridge.CompiledKernel kernel = ensureCompiledBlocking();
        if (kernel == null || activeSections <= 0) {
            return null;
        }
        final int[] meta4 = new int[] { 0, activeSections, hasBlockEmission ? 1 : 0, 0 };
        final byte[] outSky = new byte[activeSections * 2048];
        final byte[] outBlk = hasBlockEmission ? new byte[activeSections * 2048] : null;
        final byte[] outFlags = new byte[activeSections];
        final long[] timings = new long[2];
        final int rc = kernel.dispatchLightBatch(
            meta4, voxelInfo, 1, activeSections, hasBlockEmission, outSky, outBlk, outFlags, timings
        );
        if (rc != 0) {
            throw new IllegalStateException("GPU light dispatch failed rc=" + rc);
        }
        return new io.papermc.paper.spi.PaperWorldgenSpi.PrecomputedChunkLight(
            activeSections, hasBlockEmission, outFlags, 0, outSky, 0, outBlk, 0
        );
    }

    private List<io.papermc.paper.spi.PaperWorldgenSpi.@Nullable PrecomputedChunkLight> dispatchLightWave(
        final WorldgenWaveCoalescer.WaveKey key,
        final List<PackedLightChunk> batch
    ) {
        final int n = batch.size();
        final List<io.papermc.paper.spi.PaperWorldgenSpi.@Nullable PrecomputedChunkLight> results = new ArrayList<>(n);
        final GoldLeafOpenClBridge.CompiledKernel kernel = this.compiledKernel.get();
        if (kernel == null || this.closed.get() || n == 0) {
            for (int i = 0; i < n; i++) {
                results.add(null);
            }
            return results;
        }

        int totalSections = 0;
        boolean anyBlockEmission = false;
        for (int i = 0; i < n; i++) {
            final PackedLightChunk item = batch.get(i);
            totalSections += item.activeSections();
            if (item.hasBlockEmission()) {
                anyBlockEmission = true;
            }
        }
        if (totalSections <= 0) {
            for (int i = 0; i < n; i++) {
                results.add(null);
            }
            return results;
        }

        final int[] meta4 = new int[n * 4];
        final byte[] flatVoxels = new byte[totalSections * 4096];
        int secCursor = 0;
        for (int i = 0; i < n; i++) {
            final PackedLightChunk item = batch.get(i);
            final int secs = item.activeSections();
            meta4[i * 4] = secCursor * 4096;
            meta4[i * 4 + 1] = secs;
            meta4[i * 4 + 2] = item.hasBlockEmission() ? 1 : 0;
            meta4[i * 4 + 3] = secCursor;
            System.arraycopy(item.voxelInfo(), 0, flatVoxels, secCursor * 4096, secs * 4096);
            secCursor += secs;
        }

        final byte[] outSkyNibbles = new byte[totalSections * 2048];
        final byte[] outBlkNibbles = anyBlockEmission ? new byte[totalSections * 2048] : null;
        final byte[] outSecFlags = new byte[totalSections];
        final long[] timings = new long[2];

        final int rc;
        try {
            rc = kernel.dispatchLightBatch(
                meta4,
                flatVoxels,
                n,
                totalSections,
                anyBlockEmission,
                outSkyNibbles,
                outBlkNibbles,
                outSecFlags,
                timings
            );
        } catch (final Throwable ex) {
            LOGGER.warn("[GoldLeaf-OpenCL S-004] GPU light batch dispatch threw for {} chunks; falling back to CPU", n, ex);
            for (int i = 0; i < n; i++) {
                results.add(null);
            }
            return results;
        }

        if (rc != 0) {
            LOGGER.warn("[GoldLeaf-OpenCL S-004] GPU light batch dispatch returned rc={} for {} chunks; falling back to CPU", rc, n);
            for (int i = 0; i < n; i++) {
                results.add(null);
            }
            return results;
        }

        this.gpuWaves.increment();
        this.gpuChunks.add(n);
        this.totalKernelNanos.add(timings[0]);
        this.totalReadbackNanos.add(timings[1]);

        int outSecCursor = 0;
        for (int i = 0; i < n; i++) {
            final PackedLightChunk item = batch.get(i);
            final int secs = item.activeSections();
            final io.papermc.paper.spi.PaperWorldgenSpi.PrecomputedChunkLight res =
                new io.papermc.paper.spi.PaperWorldgenSpi.PrecomputedChunkLight(
                    secs,
                    item.hasBlockEmission(),
                    outSecFlags,
                    outSecCursor,
                    outSkyNibbles,
                    outSecCursor * 2048,
                    item.hasBlockEmission() ? outBlkNibbles : null,
                    outSecCursor * 2048
                );
            if (res.hasBottomLeak()) {
                this.cpuFallbackChunks.increment();
                results.add(null);
            } else {
                results.add(res);
            }
            outSecCursor += secs;
        }
        return results;
    }

    /**
     * Packs all active sections ({@code 0..highestNonEmptySection}) of {@code chunk} into a compact
     * {@code (emission << 4) | opacity} byte array. Returns {@code null} if any block in the chunk
     * is conditionally full-opaque (sided transparency such as stairs/slabs/snow layers), triggering
     * exact CPU Starlight fallback.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable PackedLightChunk packChunkVoxels(final ChunkAccess chunk, final Boolean[] emptySections) {
        final LevelChunkSection[] sections = chunk.getSections();
        int highestNonEmpty = -1;
        for (int s = sections.length - 1; s >= 0; s--) {
            final boolean isEmpty = (emptySections != null && s < emptySections.length && Boolean.TRUE.equals(emptySections[s]))
                || sections[s] == null
                || sections[s].hasOnlyAir();
            if (!isEmpty) {
                highestNonEmpty = s;
                break;
            }
        }
        if (highestNonEmpty < 0) {
            return new PackedLightChunk(0, false, new byte[0]);
        }

        final int activeSections = highestNonEmpty + 1;
        final byte[] voxelInfo = new byte[activeSections * 4096];
        boolean hasBlockEmission = false;
        final int[] scratch = UNPACK_SCRATCH.get();

        for (int s = 0; s < activeSections; s++) {
            final LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            final int secBase = s * 4096;
            final PalettedContainer<BlockState> states = section.getStates();
            final PalettedContainer.Data<BlockState> data = states.data;
            final Palette<BlockState> palette = data.palette();

            if (palette instanceof SingleValuePalette<BlockState> single) {
                final BlockState st = single.valueFor(0);
                if (((StarlightAbstractBlockState) st).starlight$isConditionallyFullOpaque()) {
                    return null;
                }
                final int op = st.getLightDampening() & 0x0F;
                final int em = st.getLightEmission() & 0x0F;
                if (em > 0) {
                    hasBlockEmission = true;
                }
                final byte packed = (byte) ((em << 4) | op);
                if (packed != 0) {
                    Arrays.fill(voxelInfo, secBase, secBase + 4096, packed);
                }
                continue;
            }

            final int palSize = palette.getSize();
            if (palSize > 0 && palSize <= 256) {
                final byte[] lut = new byte[palSize];
                boolean allZero = true;
                for (int p = 0; p < palSize; p++) {
                    final BlockState st = palette.valueFor(p);
                    if (((StarlightAbstractBlockState) st).starlight$isConditionallyFullOpaque()) {
                        return null;
                    }
                    final int op = st.getLightDampening() & 0x0F;
                    final int em = st.getLightEmission() & 0x0F;
                    if (em > 0) {
                        hasBlockEmission = true;
                    }
                    final byte packed = (byte) ((em << 4) | op);
                    lut[p] = packed;
                    if (packed != 0) {
                        allZero = false;
                    }
                }
                if (!allZero) {
                    final BitStorage storage = data.storage();
                    storage.unpack(scratch);
                    for (int i = 0; i < 4096; i++) {
                        voxelInfo[secBase + i] = lut[scratch[i]];
                    }
                }
            } else {
                for (int i = 0; i < 4096; i++) {
                    final BlockState st = states.get(i);
                    if (((StarlightAbstractBlockState) st).starlight$isConditionallyFullOpaque()) {
                        return null;
                    }
                    final int op = st.getLightDampening() & 0x0F;
                    final int em = st.getLightEmission() & 0x0F;
                    if (em > 0) {
                        hasBlockEmission = true;
                    }
                    voxelInfo[secBase + i] = (byte) ((em << 4) | op);
                }
            }
        }
        return new PackedLightChunk(activeSections, hasBlockEmission, voxelInfo);
    }

    public static String buildLightKernelSource() {
        return """
            // GoldLeaf vanilla-exact OpenCL 1.2 Sky+Block Light Kernel (zero FP64)
            __kernel void compute_chunk_light_batch(
                __global const int* meta4,
                __global const uchar* voxel_info,
                __global uchar* light_grid,
                __global uchar* out_sky_nibbles,
                __global uchar* out_blk_nibbles,
                __global uchar* out_sec_flags,
                const int batch_count
            ) {
                const int b = (int)get_group_id(0);
                if (b >= batch_count) return;
                const int lid = (int)get_local_id(0); // 0..255 -> (lz << 4) | lx
                const int lx = lid & 15;
                const int lz = lid >> 4;

                const int voxel_off = meta4[b * 4 + 0];
                const int active_secs = meta4[b * 4 + 1];
                const int flags = meta4[b * 4 + 2];
                const int sec_base = meta4[b * 4 + 3];
                const int has_blk = (flags & 1) != 0;
                const int H = active_secs << 4;

                __local int l_changed;
                __local int l_sky_or;
                __local int l_sky_and;
                __local int l_blk_or;
                __local int l_bottom_leak;

                // Vertical sky column drop + block emission init
                int sky_open = 1;
                for (int y = H - 1; y >= 0; --y) {
                    const int idx = voxel_off + (y << 8) + lid;
                    const uchar info = voxel_info[idx];
                    const int op = (int)(info & 15U);
                    const int em = (int)(info >> 4);
                    int sky = 0;
                    if (sky_open) {
                        if (op == 0) {
                            sky = 15;
                        } else {
                            sky_open = 0;
                        }
                    }
                    const int blk = has_blk ? em : 0;
                    light_grid[idx] = (uchar)((blk << 4) | sky);
                }
                barrier(CLK_GLOBAL_MEM_FENCE);

                // Exact 3D monotone fixed-point propagation (max 14 passes, early exit)
                for (int iter = 0; iter < 14; ++iter) {
                    if (lid == 0) {
                        l_changed = 0;
                    }
                    barrier(CLK_LOCAL_MEM_FENCE);
                    int local_changed = 0;

                    // Top-down + 4-horizontal sweep
                    int sky_above = 15;
                    int blk_above = 0;
                    for (int y = H - 1; y >= 0; --y) {
                        const int idx = voxel_off + (y << 8) + lid;
                        const uchar info = voxel_info[idx];
                        const int op = (int)(info & 15U);
                        const uchar cur = light_grid[idx];
                        int cur_sky = (int)(cur & 15U);
                        int cur_blk = (int)(cur >> 4);
                        if (op < 15) {
                            const int atten = op > 1 ? op : 1;
                            int max_sky = sky_above;
                            int max_blk = blk_above;
                            if (y > 0) {
                                const uchar dn = light_grid[idx - 256];
                                int ds = (int)(dn & 15U);
                                int db = (int)(dn >> 4);
                                if (ds > max_sky) max_sky = ds;
                                if (db > max_blk) max_blk = db;
                            }
                            if (lx > 0) {
                                const uchar nb = light_grid[idx - 1];
                                int ns = (int)(nb & 15U);
                                int nbk = (int)(nb >> 4);
                                if (ns > max_sky) max_sky = ns;
                                if (nbk > max_blk) max_blk = nbk;
                            }
                            if (lx < 15) {
                                const uchar nb = light_grid[idx + 1];
                                int ns = (int)(nb & 15U);
                                int nbk = (int)(nb >> 4);
                                if (ns > max_sky) max_sky = ns;
                                if (nbk > max_blk) max_blk = nbk;
                            }
                            if (lz > 0) {
                                const uchar nb = light_grid[idx - 16];
                                int ns = (int)(nb & 15U);
                                int nbk = (int)(nb >> 4);
                                if (ns > max_sky) max_sky = ns;
                                if (nbk > max_blk) max_blk = nbk;
                            }
                            if (lz < 15) {
                                const uchar nb = light_grid[idx + 16];
                                int ns = (int)(nb & 15U);
                                int nbk = (int)(nb >> 4);
                                if (ns > max_sky) max_sky = ns;
                                if (nbk > max_blk) max_blk = nbk;
                            }
                            const int cand_sky = max_sky - atten;
                            int updated = 0;
                            if (cand_sky > cur_sky) {
                                cur_sky = cand_sky;
                                updated = 1;
                            }
                            if (has_blk) {
                                const int cand_blk = max_blk - atten;
                                if (cand_blk > cur_blk) {
                                    cur_blk = cand_blk;
                                    updated = 1;
                                }
                            }
                            if (updated) {
                                light_grid[idx] = (uchar)((cur_blk << 4) | cur_sky);
                                local_changed = 1;
                            }
                        }
                        sky_above = cur_sky;
                        blk_above = cur_blk;
                    }
                    barrier(CLK_GLOBAL_MEM_FENCE);

                    // Bottom-up vertical sweep (resolves upward cave/overhang propagation in 1 pass)
                    int sky_below = 0;
                    int blk_below = 0;
                    for (int y = 0; y < H; ++y) {
                        const int idx = voxel_off + (y << 8) + lid;
                        const uchar info = voxel_info[idx];
                        const int op = (int)(info & 15U);
                        const uchar cur = light_grid[idx];
                        int cur_sky = (int)(cur & 15U);
                        int cur_blk = (int)(cur >> 4);
                        if (op < 15) {
                            const int atten = op > 1 ? op : 1;
                            const int cand_sky = sky_below - atten;
                            int updated = 0;
                            if (cand_sky > cur_sky) {
                                cur_sky = cand_sky;
                                updated = 1;
                            }
                            if (has_blk) {
                                const int cand_blk = blk_below - atten;
                                if (cand_blk > cur_blk) {
                                    cur_blk = cand_blk;
                                    updated = 1;
                                }
                            }
                            if (updated) {
                                light_grid[idx] = (uchar)((cur_blk << 4) | cur_sky);
                                local_changed = 1;
                            }
                        }
                        sky_below = cur_sky;
                        blk_below = cur_blk;
                    }
                    if (local_changed) {
                        atomic_or(&l_changed, 1);
                    }
                    barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
                    if (l_changed == 0) {
                        break;
                    }
                }

                // Pack Starlight byte[2048] nibbles + per-section summary flags
                const int lx_even = (lid & 7) << 1;
                const int lz_pair = lid >> 3;
                const int col_even = (lz_pair << 4) | lx_even;
                const int col_odd = col_even + 1;
                for (int s = 0; s < active_secs; ++s) {
                    if (lid == 0) {
                        l_sky_or = 0;
                        l_sky_and = 15;
                        l_blk_or = 0;
                        l_bottom_leak = 0;
                    }
                    barrier(CLK_LOCAL_MEM_FENCE);

                    if (lid < 128) {
                        const int y_base = s << 4;
                        const int out_sec_off = (sec_base + s) * 2048;
                        int s_sky_or = 0;
                        int s_sky_and = 15;
                        int s_blk_or = 0;
                        int s_leak = 0;
                        for (int ly = 0; ly < 16; ++ly) {
                            const int row_off = voxel_off + ((y_base + ly) << 8);
                            const uchar v0 = light_grid[row_off + col_even];
                            const uchar v1 = light_grid[row_off + col_odd];
                            const int sky0 = (int)(v0 & 15U);
                            const int sky1 = (int)(v1 & 15U);
                            const int blk0 = (int)(v0 >> 4);
                            const int blk1 = (int)(v1 >> 4);
                            out_sky_nibbles[out_sec_off + (ly << 7) + lid] = (uchar)((sky1 << 4) | sky0);
                            if (has_blk) {
                                out_blk_nibbles[out_sec_off + (ly << 7) + lid] = (uchar)((blk1 << 4) | blk0);
                            }
                            s_sky_or |= sky0 | sky1;
                            s_sky_and &= sky0 & sky1;
                            s_blk_or |= blk0 | blk1;
                            if (s == 0 && ly == 0 && ((sky0 | sky1 | blk0 | blk1) != 0)) {
                                s_leak = 1;
                            }
                        }
                        if (s_sky_or != 0) atomic_or(&l_sky_or, s_sky_or);
                        if (s_sky_and != 15) atomic_and(&l_sky_and, s_sky_and);
                        if (s_blk_or != 0) atomic_or(&l_blk_or, s_blk_or);
                        if (s_leak != 0) atomic_or(&l_bottom_leak, 1);
                    }
                    barrier(CLK_LOCAL_MEM_FENCE);
                    if (lid == 0) {
                        uchar f = 0;
                        if (l_sky_or == 0) f |= 1;
                        if (l_sky_and == 15) f |= 2;
                        if (l_blk_or == 0) f |= 4;
                        if (l_bottom_leak != 0) f |= 8;
                        out_sec_flags[sec_base + s] = f;
                    }
                    barrier(CLK_LOCAL_MEM_FENCE);
                }
            }
            """;
    }

    public long gpuWaves() {
        return this.gpuWaves.sum();
    }

    public long gpuChunks() {
        return this.gpuChunks.sum();
    }

    public String telemetrySummary() {
        return String.format(
            Locale.ROOT,
            "lightWaves=%d, lightChunks=%d, lightFallback=%d, lightPack=%.2fms, lightKern=%.2fms, lightRead=%.2fms",
            this.gpuWaves.sum(),
            this.gpuChunks.sum(),
            this.cpuFallbackChunks.sum(),
            this.totalPackNanos.sum() / 1_000_000.0,
            this.totalKernelNanos.sum() / 1_000_000.0,
            this.totalReadbackNanos.sum() / 1_000_000.0
        );
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        try {
            this.waveCoalescer.close();
        } catch (final Throwable ex) {
            LOGGER.warn("[GoldLeaf-OpenCL S-002] Failed to close light wave coalescer", ex);
        }
        this.dispatchExecutor.shutdown();
        try {
            if (!this.dispatchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.dispatchExecutor.shutdownNow();
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            this.dispatchExecutor.shutdownNow();
        }
        final GoldLeafOpenClBridge.CompiledKernel kernel = this.compiledKernel.getAndSet(null);
        if (kernel != null && this.kernelCache == null) {
            try {
                kernel.close();
            } catch (final Throwable ex) {
                LOGGER.warn("[GoldLeaf-OpenCL S-002] Failed to release GPU light kernel", ex);
            }
        }
    }
}
