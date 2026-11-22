package dev.leafcraft.goldleaf.worldgen;

import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Content-addressed JAR-packed and on-disk OpenCL kernel cache ({@code goldleaf-ADR-004}).
 *
 * <p>Supports two complementary layers:
 * <ul>
 *   <li><b>JAR-packed {@code .cl} kernels</b> under {@code META-INF/goldleaf/kernels/<key>.cl}
 *       (with optional {@code <key>.spill.bin} LUT spill tables) so stock vanilla kernels load
 *       with zero runtime bytecode-lifting overhead.</li>
 *   <li><b>Persistent disk cache</b> under {@code <cacheDir>/} storing both lifted {@code .cl}
 *       sources (for datapacks/custom routers) and driver-compiled {@code .clbin} program binaries
 *       keyed by {@code (deviceSignatureHash, buildOptsHash, kernelDigestSha256)}.</li>
 * </ul>
 *
 * <p><b>Concurrency & atomicity ({@code [DSN-2]}):</b> All disk writes use two-phase temporary
 * files followed by {@link StandardCopyOption#ATOMIC_MOVE}, synchronized per cache key via
 * single-flight lock stripes so concurrent workers never observe torn binaries. On
 * {@code CL_INVALID_BINARY} (driver upgrade or corrupted cache), the stale {@code .clbin} is
 * deleted with a {@code WARN} log ({@code S-004}) and transparently recompiled from {@code .cl}
 * source.</p>
 *
 * @governedBy goldleaf-ADR-004
 * @trace S-002, S-004, S-005
 */
public final class OpenClKernelCache implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenClKernelCache.class);
    public static final String PACKED_KERNEL_RESOURCE_PREFIX = "META-INF/goldleaf/kernels/";

    private final Path cacheDir;
    private final ClassLoader resourceLoader;
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GoldLeafOpenClBridge.CompiledKernel> liveKernels = new ConcurrentHashMap<>();

    private final AtomicLong memoryHits = new AtomicLong();
    private final AtomicLong diskBinaryHits = new AtomicLong();
    private final AtomicLong packedSourceHits = new AtomicLong();
    private final AtomicLong sourceCompiles = new AtomicLong();
    private final AtomicLong binaryRejections = new AtomicLong();

    public OpenClKernelCache(final Path cacheDir) {
        this(cacheDir, OpenClKernelCache.class.getClassLoader());
    }

    public OpenClKernelCache(final Path cacheDir, final ClassLoader resourceLoader) {
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader");
    }

    public Path cacheDir() {
        return this.cacheDir;
    }

    public long memoryHits() {
        return this.memoryHits.get();
    }

    public long diskBinaryHits() {
        return this.diskBinaryHits.get();
    }

    public long packedSourceHits() {
        return this.packedSourceHits.get();
    }

    public long sourceCompiles() {
        return this.sourceCompiles.get();
    }

    public long binaryRejections() {
        return this.binaryRejections.get();
    }

    /**
     * Loads a JAR-packed {@code .cl} kernel source (and optional {@code .spill.bin} LUT words)
     * from {@code META-INF/goldleaf/kernels/<kernelKey>.cl}.
     */
    public Optional<PackedKernelSource> loadPackedSource(final String kernelKey) {
        Objects.requireNonNull(kernelKey, "kernelKey");
        final String clResource = PACKED_KERNEL_RESOURCE_PREFIX + sanitizeKey(kernelKey) + ".cl";
        try (InputStream in = this.resourceLoader.getResourceAsStream(clResource)) {
            if (in == null) {
                return Optional.empty();
            }
            final String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            final int[] spillWords = loadPackedSpillWords(sanitizeKey(kernelKey));
            this.packedSourceHits.incrementAndGet();
            return Optional.of(new PackedKernelSource(kernelKey, source, spillWords));
        } catch (final IOException ex) {
            LOGGER.warn("[GoldLeaf-KernelCache S-004] Failed reading packed kernel resource '{}': {}", clResource, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Writes a verified {@link OpenClBackendEmitter.DensityVolumeProgram} into a resource output
     * directory for JAR packing ({@code <outputDir>/<kernelKey>.cl} and optional
     * {@code <outputDir>/<kernelKey>.spill.bin}).
     */
    public static Path packKernelToDirectory(
        final Path outputDir,
        final String kernelKey,
        final OpenClBackendEmitter.DensityVolumeProgram program
    ) throws IOException {
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(kernelKey, "kernelKey");
        Objects.requireNonNull(program, "program");
        Files.createDirectories(outputDir);
        final String safeKey = sanitizeKey(kernelKey);
        final Path clPath = outputDir.resolve(safeKey + ".cl");
        atomicWriteBytes(clPath, program.sourceCode().getBytes(StandardCharsets.UTF_8));
        final int[] spill = program.spilledLutWords();
        if (spill.length > 0) {
            final Path spillPath = outputDir.resolve(safeKey + ".spill.bin");
            atomicWriteIntArray(spillPath, spill);
        }
        return clPath;
    }

    /**
     * Resolves or compiles a {@link GoldLeafOpenClBridge.CompiledKernel} for the given device and
     * program, using in-memory cache -> disk {@code .clbin} binary cache -> JAR-packed / provided
     * {@code .cl} source compile + atomic disk persistence.
     */
    public GoldLeafOpenClBridge.@Nullable CompiledKernel getOrCompile(
        final int deviceIndex,
        final OpenClBackendEmitter.DensityVolumeProgram program,
        final String buildOpts
    ) {
        Objects.requireNonNull(program, "program");
        final String normOpts = buildOpts != null ? buildOpts.trim() : "";
        final String deviceSig = GoldLeafOpenClBridge.deviceSignature(deviceIndex);
        if (deviceSig == null) {
            return null;
        }
        final String binaryKey = buildBinaryCacheKey(deviceSig, normOpts, program.kernelDigestSha256());
        final GoldLeafOpenClBridge.CompiledKernel existing = this.liveKernels.get(binaryKey);
        if (existing != null && !existing.isClosed()) {
            this.memoryHits.incrementAndGet();
            return existing;
        }

        final Object lock = this.keyLocks.computeIfAbsent(binaryKey, k -> new Object());
        synchronized (lock) {
            final GoldLeafOpenClBridge.CompiledKernel doubleCheck = this.liveKernels.get(binaryKey);
            if (doubleCheck != null && !doubleCheck.isClosed()) {
                this.memoryHits.incrementAndGet();
                return doubleCheck;
            }

            final int[] spillWords = program.spilledLutWords();
            final Path binPath = this.cacheDir.resolve(binaryKey + ".clbin");
            if (Files.isRegularFile(binPath)) {
                final byte[] cachedBin = tryReadBytes(binPath);
                if (cachedBin != null && cachedBin.length > 0) {
                    final GoldLeafOpenClBridge.CompiledKernel fromBin = GoldLeafOpenClBridge.compileFromBinary(
                        deviceIndex,
                        cachedBin,
                        program.kernelName(),
                        normOpts,
                        spillWords
                    );
                    if (fromBin != null) {
                        this.liveKernels.put(binaryKey, fromBin);
                        this.diskBinaryHits.incrementAndGet();
                        return fromBin;
                    }
                    this.binaryRejections.incrementAndGet();
                    tryDeleteQuietly(binPath);
                }
            }

            final Optional<PackedKernelSource> packed = loadPackedSource(program.kernelDigestSha256());
            final String effectiveSource = packed.map(PackedKernelSource::sourceCode).orElse(program.sourceCode());
            final int[] effectiveSpill = packed.map(PackedKernelSource::spilledLutWords).orElse(spillWords);

            final GoldLeafOpenClBridge.CompiledKernel compiled = GoldLeafOpenClBridge.compileFromSource(
                deviceIndex,
                effectiveSource,
                program.kernelName(),
                normOpts,
                effectiveSpill
            );
            if (compiled == null) {
                return null;
            }
            this.sourceCompiles.incrementAndGet();
            this.liveKernels.put(binaryKey, compiled);

            persistArtifacts(program.kernelDigestSha256(), binaryKey, effectiveSource, compiled);
            return compiled;
        }
    }

    /**
     * Releases only in-memory compiled handles (simulating a server restart) while keeping disk
     * {@code .cl} and {@code .clbin} files intact.
     */
    public void evictInMemoryKernels() {
        for (final String key : this.liveKernels.keySet()) {
            final GoldLeafOpenClBridge.CompiledKernel kernel = this.liveKernels.remove(key);
            if (kernel != null) {
                kernel.close();
            }
        }
    }

    @Override
    public void close() {
        evictInMemoryKernels();
    }

    public Path binaryCachePath(final String deviceSignature, final String buildOpts, final String kernelDigestSha256) {
        return this.cacheDir.resolve(buildBinaryCacheKey(deviceSignature, buildOpts != null ? buildOpts.trim() : "", kernelDigestSha256) + ".clbin");
    }

    public Path sourceCachePath(final String kernelDigestSha256) {
        return this.cacheDir.resolve(sanitizeKey(kernelDigestSha256) + ".cl");
    }

    private void persistArtifacts(
        final String digest,
        final String binaryKey,
        final String sourceCode,
        final GoldLeafOpenClBridge.CompiledKernel compiled
    ) {
        try {
            Files.createDirectories(this.cacheDir);
            final Path clPath = this.cacheDir.resolve(sanitizeKey(digest) + ".cl");
            if (!Files.isRegularFile(clPath)) {
                atomicWriteBytes(clPath, sourceCode.getBytes(StandardCharsets.UTF_8));
            }
            final byte[] binBytes = compiled.exportBinary();
            if (binBytes != null && binBytes.length > 0) {
                final Path binPath = this.cacheDir.resolve(binaryKey + ".clbin");
                atomicWriteBytes(binPath, binBytes);
            }
        } catch (final IOException ex) {
            LOGGER.warn("[GoldLeaf-KernelCache S-004] Failed persisting OpenCL cache artifacts in {}: {}", this.cacheDir, ex.getMessage());
        }
    }

    private int[] loadPackedSpillWords(final String safeKey) throws IOException {
        final String spillResource = PACKED_KERNEL_RESOURCE_PREFIX + safeKey + ".spill.bin";
        try (InputStream in = this.resourceLoader.getResourceAsStream(spillResource)) {
            if (in == null) {
                return new int[0];
            }
            try (DataInputStream dis = new DataInputStream(in)) {
                final int count = dis.readInt();
                if (count <= 0) {
                    return new int[0];
                }
                final int[] words = new int[count];
                for (int i = 0; i < count; i++) {
                    words[i] = dis.readInt();
                }
                return words;
            }
        }
    }

    private static void atomicWriteBytes(final Path target, final byte[] bytes) throws IOException {
        final Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        final String tmpName = target.getFileName() + "." + ProcessHandle.current().pid() + "." + Thread.currentThread().threadId() + ".tmp";
        final Path tmp = target.resolveSibling(tmpName);
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            tryDeleteQuietly(tmp);
        }
    }

    private static void atomicWriteIntArray(final Path target, final int[] words) throws IOException {
        final Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        final String tmpName = target.getFileName() + "." + ProcessHandle.current().pid() + "." + Thread.currentThread().threadId() + ".tmp";
        final Path tmp = target.resolveSibling(tmpName);
        try {
            try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(tmp))) {
                dos.writeInt(words.length);
                for (final int w : words) {
                    dos.writeInt(w);
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            tryDeleteQuietly(tmp);
        }
    }

    private static byte @Nullable [] tryReadBytes(final Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (final IOException ex) {
            LOGGER.warn("[GoldLeaf-KernelCache S-004] Failed reading cached binary {}: {}", path, ex.getMessage());
            return null;
        }
    }

    private static void tryDeleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException ignored) {
            // Best-effort cleanup of temp/corrupt cache artifact
        }
    }

    public static String buildBinaryCacheKey(final String deviceSignature, final String buildOpts, final String kernelDigestSha256) {
        final String envHash = sha256Prefix16(deviceSignature + "\n" + buildOpts);
        return envHash + "-" + sanitizeKey(kernelDigestSha256);
    }

    private static String sanitizeKey(final String key) {
        return key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String sha256Prefix16(final String input) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((hash[i] >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record PackedKernelSource(String kernelKey, String sourceCode, int[] spilledLutWords) {
        public PackedKernelSource {
            Objects.requireNonNull(kernelKey, "kernelKey");
            Objects.requireNonNull(sourceCode, "sourceCode");
            spilledLutWords = spilledLutWords == null ? new int[0] : spilledLutWords.clone();
        }

        @Override
        public int[] spilledLutWords() {
            return this.spilledLutWords.clone();
        }
    }
}
