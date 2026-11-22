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

        @Override
        public void close() {
            final long h = this.nativeHandle.getAndSet(0L);
            if (h != 0L) {
                nativeReleaseProgram(h);
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

    private static native void nativeReleaseProgram(long handle);
}
