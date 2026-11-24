package dev.leafcraft.goldleaf.worldgen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JNI bridge to {@code libgoldleaf_opencl.so} ({@code goldleaf-ADR-004}).
 *
 * <p>Loads the native bridge from {@code -Dgoldleaf.opencl.lib} or extracts the bundled
 * {@code META-INF/native/linux-x86_64/libgoldleaf_opencl.so} resource. Manages OpenCL device
 * discovery (with PoCL CPU drivers blacklisted in native code), source/binary compilation,
 * {@code CL_PROGRAM_BINARIES} export for on-disk caching, and idempotent program teardown
 * ({@code S-002}).</p>
 *
 * @governedBy goldleaf-ADR-004
 * @trace S-002, S-004, S-005
 */
public final class GoldLeafOpenClBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoldLeafOpenClBridge.class);
    private static final String BUNDLED_NATIVE_RESOURCE = "META-INF/native/linux-x86_64/libgoldleaf_opencl.so";
    private static final String NATIVE_LIB_PROPERTY = "goldleaf.opencl.lib";

    private static final AtomicBoolean LOAD_ATTEMPTED = new AtomicBoolean(false);
    private static volatile boolean nativeLoaded;
    private static volatile int initResult = -99;

    private GoldLeafOpenClBridge() {
    }

    /**
     * Initializes the native bridge and discovers usable non-PoCL OpenCL GPU devices.
     *
     * @return number of usable OpenCL GPU devices, or {@code 0} when unavailable
     */
    public static int initialize() {
        if (LOAD_ATTEMPTED.compareAndSet(false, true)) {
            nativeLoaded = loadNativeLibrary();
            if (nativeLoaded) {
                try {
                    initResult = nativeInit();
                    if (initResult > 0) {
                        LOGGER.info(
                            "[GoldLeaf-OpenCL] Initialized {} OpenCL GPU device(s); primary='{}'",
                            initResult,
                            nativeDeviceName(0)
                        );
                    } else {
                        LOGGER.info("[GoldLeaf-OpenCL] No usable OpenCL GPU devices found (initResult={}).", initResult);
                    }
                } catch (final UnsatisfiedLinkError err) {
                    LOGGER.warn("[GoldLeaf-OpenCL S-004] JNI link error during nativeInit: {}", err.getMessage());
                    nativeLoaded = false;
                    initResult = -3;
                }
            }
        }
        return nativeLoaded ? Math.max(0, initResult) : 0;
    }

    public static boolean isAvailable() {
        return initialize() > 0;
    }

    public static int deviceCount() {
        return isAvailable() ? nativeDeviceCount() : 0;
    }

    public static @Nullable String deviceName(final int deviceIndex) {
        return isAvailable() ? nativeDeviceName(deviceIndex) : null;
    }

    public static @Nullable String deviceSignature(final int deviceIndex) {
        return isAvailable() ? nativeDeviceSignature(deviceIndex) : null;
    }

    public static boolean deviceSupportsFp64(final int deviceIndex) {
        return isAvailable() && nativeDeviceSupportsFp64(deviceIndex);
    }

    public static String lastBuildLog() {
        if (!nativeLoaded) {
            return "";
        }
        final String log = nativeLastBuildLog();
        return log != null ? log : "";
    }

    /**
     * Compiles an OpenCL C99 kernel from source on {@code deviceIndex}.
     *
     * @return compiled kernel handle, or {@code null} on build failure (details logged at WARN)
     */
    public static @Nullable CompiledKernel compileFromSource(
        final int deviceIndex,
        final String sourceCode,
        final String kernelName,
        final String buildOpts,
        final int[] spilledLutWords
    ) {
        Objects.requireNonNull(sourceCode, "sourceCode");
        Objects.requireNonNull(kernelName, "kernelName");
        if (!isAvailable()) {
            return null;
        }
        final long handle = nativeCompileFromSource(deviceIndex, sourceCode, kernelName, buildOpts != null ? buildOpts : "");
        if (handle == 0L) {
            LOGGER.warn(
                "[GoldLeaf-OpenCL S-004] Source build failed for kernel '{}' on device {}: {}",
                kernelName,
                deviceIndex,
                lastBuildLog()
            );
            return null;
        }
        return attachSpillLut(handle, deviceIndex, kernelName, spilledLutWords);
    }

    /**
     * Loads a pre-compiled OpenCL program binary via {@code clCreateProgramWithBinary}.
     *
     * @return compiled kernel handle, or {@code null} if rejected (e.g. {@code CL_INVALID_BINARY})
     */
    public static @Nullable CompiledKernel compileFromBinary(
        final int deviceIndex,
        final byte[] binary,
        final String kernelName,
        final String buildOpts,
        final int[] spilledLutWords
    ) {
        Objects.requireNonNull(binary, "binary");
        Objects.requireNonNull(kernelName, "kernelName");
        if (!isAvailable() || binary.length == 0) {
            return null;
        }
        final long handle = nativeCompileFromBinary(deviceIndex, binary, kernelName, buildOpts != null ? buildOpts : "");
        if (handle == 0L) {
            LOGGER.warn(
                "[GoldLeaf-OpenCL S-004] Cached binary rejected for kernel '{}' on device {} ({}); will recompile from source.",
                kernelName,
                deviceIndex,
                lastBuildLog()
            );
            return null;
        }
        return attachSpillLut(handle, deviceIndex, kernelName, spilledLutWords);
    }

    private static @Nullable CompiledKernel attachSpillLut(
        final long handle,
        final int deviceIndex,
        final String kernelName,
        final int @Nullable [] spilledLutWords
    ) {
        if (spilledLutWords != null && spilledLutWords.length > 0) {
            final int uploadErr = nativeUploadSpillLut(handle, spilledLutWords);
            if (uploadErr != 0) {
                LOGGER.warn(
                    "[GoldLeaf-OpenCL S-004] Failed uploading {} spilled LUT words for '{}' (err={}); releasing program.",
                    spilledLutWords.length,
                    kernelName,
                    uploadErr
                );
                nativeReleaseProgram(handle);
                return null;
            }
        }
        return new CompiledKernel(handle, deviceIndex, kernelName);
    }

    private static boolean loadNativeLibrary() {
        final String explicitPath = System.getProperty(NATIVE_LIB_PROPERTY);
        if (explicitPath != null && !explicitPath.isBlank()) {
            final Path path = Path.of(explicitPath);
            if (Files.isRegularFile(path)) {
                try {
                    System.load(path.toAbsolutePath().toString());
                    return true;
                } catch (final UnsatisfiedLinkError err) {
                    LOGGER.warn("[GoldLeaf-OpenCL S-004] Failed loading {}: {}", path, err.getMessage());
                    return false;
                }
            }
        }

        try (InputStream in = GoldLeafOpenClBridge.class.getClassLoader().getResourceAsStream(BUNDLED_NATIVE_RESOURCE)) {
            if (in == null) {
                LOGGER.info("[GoldLeaf-OpenCL] Bundled native bridge '{}' not present; GPU offload disabled.", BUNDLED_NATIVE_RESOURCE);
                return false;
            }
            final Path tmp = Files.createTempFile("goldleaf-opencl-", ".so");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.toAbsolutePath().toString());
            return true;
        } catch (final IOException | UnsatisfiedLinkError err) {
            LOGGER.warn("[GoldLeaf-OpenCL S-004] Failed extracting/loading bundled native bridge: {}", err.getMessage());
            return false;
        }
    }

    /**
     * Thread-safe, idempotently closeable handle to a compiled OpenCL program and kernel ({@code S-002}).
     */
    public static final class CompiledKernel implements AutoCloseable {

        private final AtomicLong nativeHandle;
        private final int deviceIndex;
        private final String kernelName;

        CompiledKernel(final long handle, final int deviceIndex, final String kernelName) {
            this.nativeHandle = new AtomicLong(handle);
            this.deviceIndex = deviceIndex;
            this.kernelName = kernelName;
        }

        public int deviceIndex() {
            return this.deviceIndex;
        }

        public String kernelName() {
            return this.kernelName;
        }

        public boolean isClosed() {
            return this.nativeHandle.get() == 0L;
        }

        /**
         * Exports the driver-compiled OpenCL program binary ({@code CL_PROGRAM_BINARIES}) for
         * persistent disk caching across server boots.
         */
        public byte @Nullable [] exportBinary() {
            final long h = this.nativeHandle.get();
            if (h == 0L) {
                return null;
            }
            return nativeExportProgramBinary(h);
        }

        /**
         * Executes {@code evaluate_density_volume_batch_f32} for {@code batchCount} volumes.
         *
         * @return {@code 0} on success, or a non-zero OpenCL/JNI error code
         */
        public int dispatchDensityVolumeBatch(
            final int[] originsXyz4,
            final int batchCount,
            final int sizeX,
            final int sizeY,
            final int sizeZ,
            final int stepX,
            final int stepY,
            final int stepZ,
            final float[] outValues,
            final long @Nullable [] outTimingsNanos
        ) {
            final long h = this.nativeHandle.get();
            if (h == 0L) {
                throw new IllegalStateException("CompiledKernel '" + this.kernelName + "' has been closed");
            }
            return nativeDispatchDensityVolumeBatch(
                h,
                originsXyz4,
                batchCount,
                sizeX,
                sizeY,
                sizeZ,
                stepX,
                stepY,
                stepZ,
                outValues,
                outTimingsNanos
            );
        }

        /**
         * Executes fused 6-channel {@code eval_biome_climate6_batch} for {@code batchCount}
         * quart volumes in one kernel dispatch and one contiguous interleaved {@code float[6]}
         * readback.
         *
         * @return {@code 0} on success, or a non-zero OpenCL/JNI error code
         */
        public int dispatchBiomeClimate6Batch(
            final int[] originsXyz4,
            final int batchCount,
            final int sizeX,
            final int sizeY,
            final int sizeZ,
            final int stepX,
            final int stepY,
            final int stepZ,
            final float[] outInterleaved6,
            final long @Nullable [] outTimingsNanos
        ) {
            final long h = this.nativeHandle.get();
            if (h == 0L) {
                throw new IllegalStateException("CompiledKernel '" + this.kernelName + "' has been closed");
            }
            return nativeDispatchBiomeClimate6Batch(
                h,
                originsXyz4,
                batchCount,
                sizeX,
                sizeY,
                sizeZ,
                stepX,
                stepY,
                stepZ,
                outInterleaved6,
                outTimingsNanos
            );
        }

        /**
         * Executes {@code fill_section_palette_batch_u2} for {@code batchCount} chunks of
         * {@code sectionCount} {@code 16x16x16} sections, writing packed 2-bit palette words
         * ({@code 1024 B/section}) and {@code ushort4} metadata headers ({@code 8 B/section})
         * directly into caller-owned off-heap {@link ByteBuffer}s with zero JVM heap allocation.
         *
         * @return {@code 0} on success, or a non-zero OpenCL/JNI error code
         */
        public int dispatchSectionPaletteFillBatch(
            final int[] originsXyz4,
            final int batchCount,
            final int sectionCount,
            final int seaLevel,
            final int deepFluidY,
            final ByteBuffer outWordsDirectBuf,
            final ByteBuffer outMetaDirectBuf,
            final long @Nullable [] outTimingsNanos
        ) {
            Objects.requireNonNull(outWordsDirectBuf, "outWordsDirectBuf");
            Objects.requireNonNull(outMetaDirectBuf, "outMetaDirectBuf");
            if (!outWordsDirectBuf.isDirect() || !outMetaDirectBuf.isDirect()) {
                throw new IllegalArgumentException("DMA writeback buffers must be direct ByteBuffers");
            }
            final long h = this.nativeHandle.get();
            if (h == 0L) {
                throw new IllegalStateException("CompiledKernel '" + this.kernelName + "' has been closed");
            }
            return nativeDispatchSectionPaletteFillBatch(
                h,
                originsXyz4,
                batchCount,
                sectionCount,
                seaLevel,
                deepFluidY,
                outWordsDirectBuf,
                outMetaDirectBuf,
                outTimingsNanos
            );
        }

        /**
         * Executes {@code fill_surface_palette_batch_u4} (fused {@code TERRAIN + SURFACE}, {@code goldleaf-ADR-017})
         * for {@code batchCount} chunks of {@code sectionCount} {@code 16x16x16} sections, writing
         * native 4-bit {@code SimpleBitStorage(4, 4096)} {@code uint64} rows ({@code 2048 B/section})
         * and {@code ushort4} metadata headers ({@code 8 B/section}) directly into caller-owned
         * off-heap {@link ByteBuffer}s.
         */
        public int dispatchSurfaceSectionFill4BitBatch(
            final int[] originsXyz4,
            final int batchCount,
            final int sectionCount,
            final int seaLevel,
            final int deepFluidY,
            final int minBlockY,
            final ByteBuffer outWordsDirectBuf,
            final ByteBuffer outMetaDirectBuf,
            final long @Nullable [] outTimingsNanos
        ) {
            Objects.requireNonNull(outWordsDirectBuf, "outWordsDirectBuf");
            Objects.requireNonNull(outMetaDirectBuf, "outMetaDirectBuf");
            if (!outWordsDirectBuf.isDirect() || !outMetaDirectBuf.isDirect()) {
                throw new IllegalArgumentException("DMA writeback buffers must be direct ByteBuffers");
            }
            final long h = this.nativeHandle.get();
            if (h == 0L) {
                throw new IllegalStateException("CompiledKernel '" + this.kernelName + "' has been closed");
            }
            return nativeDispatchSurfaceSectionFill4BitBatch(
                h,
                originsXyz4,
                batchCount,
                sectionCount,
                seaLevel,
                deepFluidY,
                minBlockY,
                outWordsDirectBuf,
                outMetaDirectBuf,
                outTimingsNanos
            );
        }

        /**
         * Executes {@code eval_biome_rtree_batch} for {@code batchCount} chunks, writing compact
         * {@code uint8} biome palette indices ({@code 1 byte/quart}, {@code 24x} smaller DMA than
         * 6-channel float climate readback).
         */
        public int dispatchBiomeRTreeBatch(
            final int[] originsXyz4,
            final int batchCount,
            final int sizeX,
            final int sizeY,
            final int sizeZ,
            final int stepX,
            final int stepY,
            final int stepZ,
            final byte[] outBiomeIds,
            final long @Nullable [] outTimingsNanos
        ) {
            final long h = this.nativeHandle.get();
            if (h == 0L) {
                throw new IllegalStateException("CompiledKernel '" + this.kernelName + "' has been closed");
            }
            return nativeDispatchBiomeRTreeBatch(
                h,
                originsXyz4,
                batchCount,
                sizeX,
                sizeY,
                sizeZ,
                stepX,
                stepY,
                stepZ,
                outBiomeIds,
                outTimingsNanos
            );
        }

        public int dispatchVanillaSurfaceU8Batch(
            final CompiledKernel surfaceKernel,
            final CompiledKernel packKernel,
            final @Nullable CompiledKernel carverKernel,
            final int[] originsXyz4,
            final byte[] biomesHalo,
            final float[] prelimCorners4,
            final int[] beardMeta2,
            final int[] beardItems8,
            final int beardItemCount,
            final int @Nullable [] carverMeta,
            final int @Nullable [] carverMasks,
            final int carvedChunkCount,
            final int wordsPerCol,
            final int batchCount,
            final int sectionCount,
            final int seaLevel,
            final int deepFluidY,
            final int minBlockY,
            final long biomeZoomSeed,
            final ByteBuffer outVoxelsDirectBuf,
            final ByteBuffer outMetaDirectBuf,
            final @Nullable ByteBuffer outHeightmapsDirectBuf,
            final byte[] outChunkFlags,
            final int @Nullable [] outCarverEvents,
            final int @Nullable [] outCarverCounts,
            final long @Nullable [] outTimingsNanos
        ) {
            return dispatchVanillaSurfaceU8Batch(
                surfaceKernel,
                packKernel,
                null,
                carverKernel,
                null,
                originsXyz4,
                biomesHalo,
                prelimCorners4,
                beardMeta2,
                beardItems8,
                beardItemCount,
                carverMeta,
                carverMasks,
                carvedChunkCount,
                wordsPerCol,
                minBlockY + 1,
                minBlockY + sectionCount * 16 - 8,
                batchCount,
                sectionCount,
                seaLevel,
                deepFluidY,
                minBlockY,
                biomeZoomSeed,
                outVoxelsDirectBuf,
                outMetaDirectBuf,
                outHeightmapsDirectBuf,
                outChunkFlags,
                outCarverEvents,
                outCarverCounts,
                outTimingsNanos
            );
        }

        public int dispatchVanillaSurfaceU8Batch(
            final CompiledKernel surfaceKernel,
            final CompiledKernel packKernel,
            final @Nullable CompiledKernel carverMaskKernel,
            final @Nullable CompiledKernel carverKernel,
            final @Nullable CompiledKernel featureKernel,
            final int[] originsXyz4,
            final byte[] biomesHalo,
            final float[] prelimCorners4,
            final int[] beardMeta2,
            final int[] beardItems8,
            final int beardItemCount,
            final int @Nullable [] carverMeta,
            final int @Nullable [] carverMasks,
            final int carvedChunkCount,
            final int wordsPerCol,
            final int minCarveY,
            final int maxCarveY,
            final int batchCount,
            final int sectionCount,
            final int seaLevel,
            final int deepFluidY,
            final int minBlockY,
            final long biomeZoomSeed,
            final ByteBuffer outVoxelsDirectBuf,
            final ByteBuffer outMetaDirectBuf,
            final @Nullable ByteBuffer outHeightmapsDirectBuf,
            final byte[] outChunkFlags,
            final int @Nullable [] outCarverEvents,
            final int @Nullable [] outCarverCounts,
            final long @Nullable [] outTimingsNanos
        ) {
            Objects.requireNonNull(surfaceKernel, "surfaceKernel");
            Objects.requireNonNull(packKernel, "packKernel");
            Objects.requireNonNull(outVoxelsDirectBuf, "outVoxelsDirectBuf");
            Objects.requireNonNull(outMetaDirectBuf, "outMetaDirectBuf");
            if (!outVoxelsDirectBuf.isDirect() || !outMetaDirectBuf.isDirect()
                || (outHeightmapsDirectBuf != null && !outHeightmapsDirectBuf.isDirect())) {
                throw new IllegalArgumentException("DMA writeback buffers must be direct ByteBuffers");
            }
            final long hTerrain = this.nativeHandle.get();
            final long hSurface = surfaceKernel.nativeHandle.get();
            final long hPack = packKernel.nativeHandle.get();
            final long hCarverMask = carverMaskKernel != null ? carverMaskKernel.nativeHandle.get() : 0L;
            final long hCarver = carverKernel != null ? carverKernel.nativeHandle.get() : 0L;
            final long hFeature = featureKernel != null ? featureKernel.nativeHandle.get() : 0L;
            if (hTerrain == 0L || hSurface == 0L || hPack == 0L
                || (carverMaskKernel != null && hCarverMask == 0L)
                || (carverKernel != null && hCarver == 0L)
                || (featureKernel != null && hFeature == 0L)) {
                throw new IllegalStateException("CompiledKernel has been closed");
            }
            return nativeDispatchVanillaSurfaceU8Batch(
                hTerrain,
                hSurface,
                hPack,
                hCarverMask,
                hCarver,
                hFeature,
                originsXyz4,
                biomesHalo,
                prelimCorners4,
                beardMeta2,
                beardItems8,
                beardItemCount,
                carverMeta,
                carverMasks,
                carvedChunkCount,
                wordsPerCol,
                minCarveY,
                maxCarveY,
                batchCount,
                sectionCount,
                seaLevel,
                deepFluidY,
                minBlockY,
                biomeZoomSeed,
                outVoxelsDirectBuf,
                outMetaDirectBuf,
                outHeightmapsDirectBuf,
                outChunkFlags,
                outCarverEvents,
                outCarverCounts,
                outTimingsNanos
            );
        }

        public int dispatchCarverMaskBatch(
            final int[] originsXyz4,
            final int batchCount,
            final int wordsPerCol,
            final int minBlockY,
            final int minCarveY,
            final int maxCarveY,
            final int[] outCarverMeta,
            final int[] outCarverMasks
        ) {
            final long h = this.nativeHandle.get();
            if (h == 0L) {
                throw new IllegalStateException("CompiledKernel has been closed");
            }
            return nativeDispatchCarverMaskBatch(
                h,
                originsXyz4,
                batchCount,
                wordsPerCol,
                minBlockY,
                minCarveY,
                maxCarveY,
                outCarverMeta,
                outCarverMasks
            );
        }

        public int dispatchOreVeinRasterBatch(
            final int[] veinMeta4,
            final float[] veinRel6,
            final long[] veinSeeds2,
            final int veinCount,
            final int regionMinX,
            final int regionMinZ,
            final int minBuildY,
            final int maxBuildY,
            final int[] outSlices2,
            final int[] outVoxels,
            final long @Nullable [] outTimingsNanos
        ) {
            final long h = this.nativeHandle.get();
            if (h == 0L) {
                throw new IllegalStateException("CompiledKernel has been closed");
            }
            return nativeDispatchOreVeinRasterBatch(
                h,
                veinMeta4,
                veinRel6,
                veinSeeds2,
                veinCount,
                regionMinX,
                regionMinZ,
                minBuildY,
                maxBuildY,
                outSlices2,
                outVoxels,
                outTimingsNanos
            );
        }

        public int dispatchLightBatch(
            final int[] meta4,
            final byte[] voxelInfo,
            final int batchCount,
            final int totalSections,
            final boolean readBlockLight,
            final byte[] outSkyNibbles,
            final byte @Nullable [] outBlkNibbles,
            final byte[] outSecFlags,
            final long @Nullable [] outTimingsNanos
        ) {
            final long h = this.nativeHandle.get();
            if (h == 0L) {
                throw new IllegalStateException("CompiledKernel has been closed");
            }
            return nativeDispatchLightBatch(
                h,
                meta4,
                voxelInfo,
                batchCount,
                totalSections,
                readBlockLight,
                outSkyNibbles,
                outBlkNibbles,
                outSecFlags,
                outTimingsNanos
            );
        }

        /**
         * Dispatches a generic GoldLeaf compute kernel on the dedicated per-device compute queue
         * (isolated from worldgen DMA slots).
         *
         * @return {@code 0} on success, or a non-zero OpenCL/JNI error code
         */
        public int dispatchComputeKernel(
            final @Nullable ByteBuffer inputDirectBuf,
            final int inputBytes,
            final @Nullable ComputeVramBuffer persistentState,
            final ByteBuffer outputDirectBuf,
            final int outputBytes,
            final int @Nullable [] scalarParams,
            final int globalWorkItems,
            final int localWorkItems,
            final boolean clearOutputBeforeDispatch,
            final long @Nullable [] outTimingsNanos
        ) {
            Objects.requireNonNull(outputDirectBuf, "outputDirectBuf");
            if (!outputDirectBuf.isDirect()) {
                throw new IllegalArgumentException("outputDirectBuf must be a direct ByteBuffer");
            }
            if (inputBytes > 0) {
                Objects.requireNonNull(inputDirectBuf, "inputDirectBuf");
                if (!inputDirectBuf.isDirect()) {
                    throw new IllegalArgumentException("inputDirectBuf must be a direct ByteBuffer");
                }
            }
            final long h = this.nativeHandle.get();
            if (h == 0L) {
                throw new IllegalStateException("CompiledKernel '" + this.kernelName + "' has been closed");
            }
            final long vramHandle;
            if (persistentState != null) {
                vramHandle = persistentState.nativeHandle();
                if (vramHandle == 0L) {
                    throw new IllegalStateException("ComputeVramBuffer has been closed");
                }
            } else {
                vramHandle = 0L;
            }
            return nativeDispatchComputeKernel(
                h,
                inputDirectBuf,
                inputBytes,
                vramHandle,
                outputDirectBuf,
                outputBytes,
                scalarParams,
                globalWorkItems,
                localWorkItems,
                clearOutputBeforeDispatch,
                outTimingsNanos
            );
        }

        public int uploadBlockDictLut(final int[] dictIds) {
            Objects.requireNonNull(dictIds, "dictIds");
            final long h = this.nativeHandle.get();
            if (h == 0L) {
                return -1;
            }
            return nativeUploadBlockDictLut(h, dictIds);
        }

        @Override
        public void close() {
            final long h = this.nativeHandle.getAndSet(0L);
            if (h != 0L) {
                nativeReleaseProgram(h);
            }
        }
    }

    /**
     * Allocates a zero-initialized persistent {@code CL_MEM_READ_WRITE} VRAM buffer on
     * {@code deviceIndex} for stateful compute kernels ({@code S-002}).
     */
    public static @Nullable ComputeVramBuffer allocComputeVramBuffer(final int deviceIndex, final int sizeBytes) {
        if (!isAvailable() || sizeBytes <= 0) {
            return null;
        }
        final long handle = nativeAllocComputeVramBuffer(deviceIndex, sizeBytes);
        if (handle == 0L) {
            return null;
        }
        return new ComputeVramBuffer(deviceIndex, sizeBytes, handle);
    }

    /**
     * Native persistent VRAM buffer handle with idempotent {@link #close()} teardown ({@code S-002}).
     */
    public static final class ComputeVramBuffer implements AutoCloseable {
        private final int deviceIndex;
        private final int sizeBytes;
        private final AtomicLong nativeHandle;

        private ComputeVramBuffer(final int deviceIndex, final int sizeBytes, final long nativeHandle) {
            this.deviceIndex = deviceIndex;
            this.sizeBytes = sizeBytes;
            this.nativeHandle = new AtomicLong(nativeHandle);
        }

        public int deviceIndex() {
            return this.deviceIndex;
        }

        public int sizeBytes() {
            return this.sizeBytes;
        }

        public boolean isClosed() {
            return this.nativeHandle.get() == 0L;
        }

        long nativeHandle() {
            return this.nativeHandle.get();
        }

        @Override
        public void close() {
            final long h = this.nativeHandle.getAndSet(0L);
            if (h != 0L) {
                nativeReleaseComputeVramBuffer(h);
            }
        }
    }

    private static native int nativeInit();

    private static native int nativeDeviceCount();

    private static native @Nullable String nativeDeviceName(int deviceIndex);

    private static native @Nullable String nativeDeviceSignature(int deviceIndex);

    private static native boolean nativeDeviceSupportsFp64(int deviceIndex);

    private static native @Nullable String nativeLastBuildLog();

    private static native long nativeCompileFromSource(int deviceIndex, String source, String kernelName, String buildOpts);

    private static native byte @Nullable [] nativeExportProgramBinary(long handle);

    private static native long nativeCompileFromBinary(int deviceIndex, byte[] binary, String kernelName, String buildOpts);

    private static native int nativeUploadSpillLut(long handle, int[] spillWords);

    private static native int nativeUploadBlockDictLut(long handle, int[] dictIds);

    private static native int nativeDispatchDensityVolumeBatch(
        long handle,
        int[] originsXyz4,
        int batchCount,
        int sizeX,
        int sizeY,
        int sizeZ,
        int stepX,
        int stepY,
        int stepZ,
        float[] outValues,
        long @Nullable [] outTimingsNanos
    );

    private static native int nativeDispatchBiomeClimate6Batch(
        long handle,
        int[] originsXyz4,
        int batchCount,
        int sizeX,
        int sizeY,
        int sizeZ,
        int stepX,
        int stepY,
        int stepZ,
        float[] outInterleaved6,
        long @Nullable [] outTimingsNanos
    );

    private static native int nativeDispatchBiomeRTreeBatch(
        long handle,
        int[] originsXyz4,
        int batchCount,
        int sizeX,
        int sizeY,
        int sizeZ,
        int stepX,
        int stepY,
        int stepZ,
        byte[] outBiomeIds,
        long @Nullable [] outTimingsNanos
    );

    private static native int nativeDispatchSectionPaletteFillBatch(
        long handle,
        int[] originsXyz4,
        int batchCount,
        int sectionCount,
        int seaLevel,
        int deepFluidY,
        ByteBuffer outWordsDirectBuf,
        ByteBuffer outMetaDirectBuf,
        long @Nullable [] outTimingsNanos
    );

    private static native int nativeDispatchSurfaceSectionFill4BitBatch(
        long handle,
        int[] originsXyz4,
        int batchCount,
        int sectionCount,
        int seaLevel,
        int deepFluidY,
        int minBlockY,
        ByteBuffer outWordsDirectBuf,
        ByteBuffer outMetaDirectBuf,
        long @Nullable [] outTimingsNanos
    );

    private static native int nativeDispatchVanillaSurfaceU8Batch(
        long terrainHandle,
        long surfaceHandle,
        long packHandle,
        long carverMaskHandle,
        long carverHandle,
        long featureHandle,
        int[] originsXyz4,
        byte[] biomesHalo,
        float[] prelimCorners4,
        int[] beardMeta2,
        int[] beardItems8,
        int beardItemCount,
        int @Nullable [] carverMeta,
        int @Nullable [] carverMasks,
        int carvedChunkCount,
        int wordsPerCol,
        int minCarveY,
        int maxCarveY,
        int batchCount,
        int sectionCount,
        int seaLevel,
        int deepFluidY,
        int minBlockY,
        long biomeZoomSeed,
        ByteBuffer outVoxelsDirectBuf,
        ByteBuffer outMetaDirectBuf,
        @Nullable ByteBuffer outHeightmapsDirectBuf,
        byte[] outChunkFlags,
        int @Nullable [] outCarverEvents,
        int @Nullable [] outCarverCounts,
        long @Nullable [] outTimingsNanos
    );

    private static native int nativeDispatchCarverMaskBatch(
        long carverMaskHandle,
        int[] originsXyz4,
        int batchCount,
        int wordsPerCol,
        int minBlockY,
        int minCarveY,
        int maxCarveY,
        int[] outCarverMeta,
        int[] outCarverMasks
    );

    private static native int nativeDispatchOreVeinRasterBatch(
        long handle,
        int[] veinMeta4,
        float[] veinRel6,
        long[] veinSeeds2,
        int veinCount,
        int regionMinX,
        int regionMinZ,
        int minBuildY,
        int maxBuildY,
        int[] outSlices2,
        int[] outVoxels,
        long @Nullable [] outTimingsNanos
    );

    private static native int nativeDispatchLightBatch(
        long handle,
        int[] meta4,
        byte[] voxelInfo,
        int batchCount,
        int totalSections,
        boolean readBlockLight,
        byte[] outSkyNibbles,
        byte @Nullable [] outBlkNibbles,
        byte[] outSecFlags,
        long @Nullable [] outTimingsNanos
    );

    private static native void nativeReleaseProgram(long handle);

    private static native long nativeAllocComputeVramBuffer(int deviceIndex, int sizeBytes);

    private static native void nativeReleaseComputeVramBuffer(long bufferHandle);

    private static native int nativeDispatchComputeKernel(
        long handle,
        @Nullable ByteBuffer inputDirectBuf,
        int inputBytes,
        long persistentVramHandle,
        ByteBuffer outputDirectBuf,
        int outputBytes,
        int @Nullable [] scalarParams,
        int globalWorkItems,
        int localWorkItems,
        boolean clearOutputBeforeDispatch,
        long @Nullable [] outTimingsNanos
    );
}
