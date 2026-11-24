package dev.leafcraft.goldleaf.compute;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.worldgen.GoldLeafOpenClBridge;
import io.papermc.paper.compute.ComputeService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 2 implementation of {@link ComputeService} ({@code goldleaf-ADR-004},
 * {@code goldleaf-ADR-017}).
 *
 * <p>Dispatches built-in audited kernels and admin-allowlisted custom OpenCL C99 kernels on a
 * dedicated off-tick worker pool ({@code S-005}) backed by {@link GoldLeafOpenClBridge}'s isolated
 * per-device compute queue, with automatic CPU fallback, per-owner/global VRAM quota accounting
 * ({@code S-002}), and {@code 1..20 Hz} latest-frame-wins paced sessions with GPU budget
 * step-down ({@code S-004}).</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017
 * @trace S-002, S-004, S-005
 */
@NullMarked
public final class GoldLeafComputeServiceImpl implements ComputeService, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoldLeafComputeServiceImpl.class);
    private static final int CONSECUTIVE_OVERRUNS_BEFORE_STEPDOWN = 3;

    private static volatile @Nullable GoldLeafComputeServiceImpl globalInstance;

    /**
     * Runtime policy snapshot derived from {@link GoldLeafConfig.Compute} or supplied by tests.
     */
    public record ComputePolicy(
        boolean enabled,
        boolean allowCustomOpenCl,
        Set<String> customKernelAllowlist,
        long maxVramBytesPerOwner,
        long maxTotalVramBytes,
        long maxGpuBudgetNanosPerTick,
        int workerThreads
    ) {
        public ComputePolicy {
            customKernelAllowlist = Set.copyOf(Objects.requireNonNull(customKernelAllowlist, "customKernelAllowlist"));
            if (maxVramBytesPerOwner <= 0L) {
                throw new IllegalArgumentException("maxVramBytesPerOwner must be > 0");
            }
            if (maxTotalVramBytes < maxVramBytesPerOwner) {
                throw new IllegalArgumentException("maxTotalVramBytes must be >= maxVramBytesPerOwner");
            }
            if (maxGpuBudgetNanosPerTick <= 0L) {
                throw new IllegalArgumentException("maxGpuBudgetNanosPerTick must be > 0");
            }
            if (workerThreads < 1 || workerThreads > 16) {
                throw new IllegalArgumentException("workerThreads must be in [1, 16]");
            }
        }

        public static ComputePolicy fromConfig(final GoldLeafConfig.@Nullable Compute cfg) {
            if (cfg == null) {
                return new ComputePolicy(
                    true,
                    false,
                    Set.of(),
                    64L * 1024L * 1024L,
                    256L * 1024L * 1024L,
                    6_000_000L,
                    2
                );
            }
            final Set<String> allow = new HashSet<>();
            if (cfg.customKernelAllowlist != null) {
                for (final String entry : cfg.customKernelAllowlist) {
                    if (entry != null && !entry.isBlank()) {
                        allow.add(entry.trim());
                    }
                }
            }
            return new ComputePolicy(
                cfg.enabled,
                cfg.allowCustomOpenCl,
                allow,
                cfg.maxVramBytesPerOwner,
                cfg.maxTotalVramBytes,
                cfg.maxGpuBudgetMsPerTick * 1_000_000L,
                cfg.workerThreads
            );
        }
    }

    private final Supplier<ComputePolicy> policySupplier;
    private final Path kernelCacheDir;
    private final boolean forceCpuFallback;
    private final Map<String, Function<String, KernelDescriptor>> builtInFactories;
    private final ExecutorService workerPool;
    private final ScheduledExecutorService pacingScheduler;

    private final Object quotaLock = new Object();
    private long totalAllocatedVramBytes = 0L;
    private final Map<String, Long> ownerAllocatedVramBytes = new HashMap<>();
    private final Set<ManagedKernelHandle> activeHandles = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public static GoldLeafComputeServiceImpl global() {
        GoldLeafComputeServiceImpl inst = globalInstance;
        if (inst == null) {
            synchronized (GoldLeafComputeServiceImpl.class) {
                inst = globalInstance;
                if (inst == null) {
                    inst = new GoldLeafComputeServiceImpl(
                        () -> {
                            final GoldLeafConfig cfg = GoldLeafConfig.get();
                            return ComputePolicy.fromConfig(cfg != null ? cfg.compute : null);
                        },
                        Path.of("cache", "goldleaf-compute-kernels"),
                        false
                    );
                    globalInstance = inst;
                }
            }
        }
        return inst;
    }

    public GoldLeafComputeServiceImpl(
        final ComputePolicy policy,
        final Path kernelCacheDir,
        final boolean forceCpuFallback
    ) {
        this(() -> Objects.requireNonNull(policy, "policy"), kernelCacheDir, forceCpuFallback);
    }

    public GoldLeafComputeServiceImpl(
        final Supplier<ComputePolicy> policySupplier,
        final Path kernelCacheDir,
        final boolean forceCpuFallback
    ) {
        this.policySupplier = Objects.requireNonNull(policySupplier, "policySupplier");
        this.kernelCacheDir = Objects.requireNonNull(kernelCacheDir, "kernelCacheDir");
        this.forceCpuFallback = forceCpuFallback;

        final Map<String, Function<String, KernelDescriptor>> builtIns = new HashMap<>();
        builtIns.put(BuiltInKernels.MASS_EXPLOSION_RAYCAST, MassExplosionRaycastKernel::builtInDescriptor);
        this.builtInFactories = Collections.unmodifiableMap(builtIns);

        final int threads = Math.max(1, Math.min(16, this.policySupplier.get().workerThreads()));
        final AtomicInteger workerIdx = new AtomicInteger(1);
        this.workerPool = Executors.newFixedThreadPool(threads, r -> {
            final Thread t = new Thread(r, "GoldLeaf-Compute-Worker-" + workerIdx.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        this.pacingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "GoldLeaf-Compute-Pacer");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public boolean isEnabled() {
        return !this.closed.get() && this.policySupplier.get().enabled();
    }

    @Override
    public boolean isGpuAvailable() {
        return !this.forceCpuFallback && GoldLeafOpenClBridge.isAvailable();
    }

    @Override
    public Set<String> builtInKernelIds() {
        return this.builtInFactories.keySet();
    }

    @Override
    public long allocatedVramBytes() {
        synchronized (this.quotaLock) {
            return this.totalAllocatedVramBytes;
        }
    }

    @Override
    public ComputeKernelHandle openBuiltInKernel(final String ownerId, final String builtInKernelId) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(builtInKernelId, "builtInKernelId");
        ensureEnabled();
        final Function<String, KernelDescriptor> factory = this.builtInFactories.get(builtInKernelId);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown built-in GoldLeaf compute kernel: " + builtInKernelId);
        }
        return openInternal(factory.apply(ownerId));
    }

    @Override
    public ComputeKernelHandle registerCustomKernel(final KernelDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        ensureEnabled();
        final ComputePolicy policy = this.policySupplier.get();
        if (!policy.allowCustomOpenCl() || !policy.customKernelAllowlist().contains(descriptor.ownerId())) {
            LOGGER.warn(
                "[GoldLeaf-Compute S-004] Denied custom OpenCL kernel '{}' for owner '{}' (allowCustomOpenCl={}, allowlisted={})",
                descriptor.kernelId(),
                descriptor.ownerId(),
                policy.allowCustomOpenCl(),
                policy.customKernelAllowlist().contains(descriptor.ownerId())
            );
            throw new SecurityException(
                "Custom OpenCL compute kernels are not permitted for owner '" + descriptor.ownerId() + "'"
            );
        }
        return openInternal(descriptor);
    }

    @Override
    public int closeAllForOwner(final String ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        int closedCount = 0;
        for (final ManagedKernelHandle handle : List.copyOf(this.activeHandles)) {
            if (ownerId.equals(handle.ownerId())) {
                handle.close();
                closedCount++;
            }
        }
        return closedCount;
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        for (final ManagedKernelHandle handle : List.copyOf(this.activeHandles)) {
            handle.close();
        }
        this.pacingScheduler.shutdownNow();
        this.workerPool.shutdownNow();
    }

    private void ensureEnabled() {
        if (this.closed.get()) {
            throw new IllegalStateException("ComputeService has been closed");
        }
        if (!this.policySupplier.get().enabled()) {
            throw new IllegalStateException("ComputeService is disabled in goldleaf.yml");
        }
    }

    private ComputeKernelHandle openInternal(final KernelDescriptor descriptor) {
        final ComputePolicy policy = this.policySupplier.get();
        final long requiredVram = (long) descriptor.maxInputBytes()
            + (long) descriptor.maxOutputBytes()
            + (long) descriptor.persistentStateBytes();
        reserveVramQuota(descriptor.ownerId(), requiredVram, policy);

        GoldLeafOpenClBridge.CompiledKernel compiled = null;
        GoldLeafOpenClBridge.ComputeVramBuffer vramState = null;
        ByteBuffer cpuState = null;
        try {
            if (isGpuAvailable()) {
                compiled = compileWithBinaryCache(descriptor);
                if (compiled != null && descriptor.persistentStateBytes() > 0) {
                    vramState = GoldLeafOpenClBridge.allocComputeVramBuffer(0, descriptor.persistentStateBytes());
                    if (vramState == null) {
                        LOGGER.warn(
                            "[GoldLeaf-Compute S-004] Failed to allocate {} B persistent VRAM for '{}'; falling back to CPU",
                            descriptor.persistentStateBytes(),
                            descriptor.kernelId()
                        );
                        compiled.close();
                        compiled = null;
                    }
                }
            }
            if (compiled == null && descriptor.persistentStateBytes() > 0) {
                cpuState = ByteBuffer.allocateDirect(descriptor.persistentStateBytes());
            }
            final ManagedKernelHandle handle = new ManagedKernelHandle(
                descriptor,
                requiredVram,
                compiled,
                vramState,
                cpuState
            );
            this.activeHandles.add(handle);
            return handle;
        } catch (final RuntimeException ex) {
            if (vramState != null) {
                vramState.close();
            }
            if (compiled != null) {
                compiled.close();
            }
            releaseVramQuota(descriptor.ownerId(), requiredVram);
            throw ex;
        }
    }

    private void reserveVramQuota(final String ownerId, final long bytes, final ComputePolicy policy) {
        synchronized (this.quotaLock) {
            final long ownerCurrent = this.ownerAllocatedVramBytes.getOrDefault(ownerId, 0L);
            if (ownerCurrent + bytes > policy.maxVramBytesPerOwner()) {
                throw new IllegalStateException(
                    "Owner '" + ownerId + "' VRAM quota exceeded: current=" + ownerCurrent
                        + " + requested=" + bytes + " > max=" + policy.maxVramBytesPerOwner()
                );
            }
            if (this.totalAllocatedVramBytes + bytes > policy.maxTotalVramBytes()) {
                throw new IllegalStateException(
                    "Global compute VRAM quota exceeded: current=" + this.totalAllocatedVramBytes
                        + " + requested=" + bytes + " > max=" + policy.maxTotalVramBytes()
                );
            }
            this.ownerAllocatedVramBytes.put(ownerId, ownerCurrent + bytes);
            this.totalAllocatedVramBytes += bytes;
        }
    }

    private void releaseVramQuota(final String ownerId, final long bytes) {
        synchronized (this.quotaLock) {
            final long ownerCurrent = this.ownerAllocatedVramBytes.getOrDefault(ownerId, 0L);
            final long nextOwner = Math.max(0L, ownerCurrent - bytes);
            if (nextOwner == 0L) {
                this.ownerAllocatedVramBytes.remove(ownerId);
            } else {
                this.ownerAllocatedVramBytes.put(ownerId, nextOwner);
            }
            this.totalAllocatedVramBytes = Math.max(0L, this.totalAllocatedVramBytes - bytes);
        }
    }

    private GoldLeafOpenClBridge.@Nullable CompiledKernel compileWithBinaryCache(final KernelDescriptor descriptor) {
        final String deviceSig = GoldLeafOpenClBridge.deviceSignature(0);
        if (deviceSig == null) {
            return null;
        }
        final String opts = descriptor.buildOptions().trim();
        final String cacheKey = sha256Hex(
            deviceSig + "\n" + opts + "\n" + descriptor.kernelName() + "\n" + descriptor.openClSource()
        );
        final Path binPath = this.kernelCacheDir.resolve(cacheKey + ".clbin");
        if (Files.isRegularFile(binPath)) {
            try {
                final byte[] cachedBin = Files.readAllBytes(binPath);
                if (cachedBin.length > 0) {
                    final GoldLeafOpenClBridge.CompiledKernel fromBin = GoldLeafOpenClBridge.compileFromBinary(
                        0,
                        cachedBin,
                        descriptor.kernelName(),
                        opts,
                        new int[0]
                    );
                    if (fromBin != null) {
                        return fromBin;
                    }
                    LOGGER.warn(
                        "[GoldLeaf-Compute S-004] Stale .clbin rejected for '{}'; recompiling from source",
                        descriptor.kernelId()
                    );
                    Files.deleteIfExists(binPath);
                }
            } catch (final IOException ex) {
                LOGGER.warn(
                    "[GoldLeaf-Compute S-004] Failed reading cached .clbin '{}': {}",
                    binPath,
                    ex.getMessage()
                );
            }
        }

        final GoldLeafOpenClBridge.CompiledKernel compiled = GoldLeafOpenClBridge.compileFromSource(
            0,
            descriptor.openClSource(),
            descriptor.kernelName(),
            opts,
            new int[0]
        );
        if (compiled == null) {
            LOGGER.warn(
                "[GoldLeaf-Compute S-004] OpenCL compile failed for '{}'; using CPU fallback. Build log: {}",
                descriptor.kernelId(),
                GoldLeafOpenClBridge.lastBuildLog()
            );
            return null;
        }
        final byte[] exported = compiled.exportBinary();
        if (exported != null && exported.length > 0) {
            try {
                Files.createDirectories(this.kernelCacheDir);
                final Path tmp = this.kernelCacheDir.resolve(cacheKey + ".clbin.tmp." + Thread.currentThread().threadId());
                Files.write(tmp, exported);
                try {
                    Files.move(tmp, binPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (final AtomicMoveNotSupportedException ignored) {
                    Files.move(tmp, binPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (final IOException ex) {
                LOGGER.warn(
                    "[GoldLeaf-Compute S-004] Failed persisting .clbin for '{}': {}",
                    descriptor.kernelId(),
                    ex.getMessage()
                );
            }
        }
        return compiled;
    }

    private static String sha256Hex(final String text) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private final class ManagedKernelHandle implements ComputeKernelHandle {

        private final KernelDescriptor descriptor;
        private final long reservedVramBytes;
        private final GoldLeafOpenClBridge.@Nullable CompiledKernel compiledKernel;
        private final GoldLeafOpenClBridge.@Nullable ComputeVramBuffer persistentVram;
        private final @Nullable ByteBuffer cpuPersistentState;
        private final Object dispatchLock = new Object();
        private final AtomicBoolean handleClosed = new AtomicBoolean(false);
        private final AtomicLong frameCounter = new AtomicLong(0L);
        private final Set<ManagedPacedSession> sessions = ConcurrentHashMap.newKeySet();
        private @Nullable ByteBuffer stagingDirectIn;
        private @Nullable ByteBuffer stagingDirectOut;

        private ManagedKernelHandle(
            final KernelDescriptor descriptor,
            final long reservedVramBytes,
            final GoldLeafOpenClBridge.@Nullable CompiledKernel compiledKernel,
            final GoldLeafOpenClBridge.@Nullable ComputeVramBuffer persistentVram,
            final @Nullable ByteBuffer cpuPersistentState
        ) {
            this.descriptor = descriptor;
            this.reservedVramBytes = reservedVramBytes;
            this.compiledKernel = compiledKernel;
            this.persistentVram = persistentVram;
            this.cpuPersistentState = cpuPersistentState;
        }

        @Override
        public String ownerId() {
            return this.descriptor.ownerId();
        }

        @Override
        public String kernelId() {
            return this.descriptor.kernelId();
        }

        @Override
        public boolean isGpuCompiled() {
            return this.compiledKernel != null && !this.compiledKernel.isClosed();
        }

        @Override
        public boolean isClosed() {
            return this.handleClosed.get();
        }

        @Override
        public int maxInputBytes() {
            return this.descriptor.maxInputBytes();
        }

        @Override
        public int maxOutputBytes() {
            return this.descriptor.maxOutputBytes();
        }

        @Override
        public int persistentStateBytes() {
            return this.descriptor.persistentStateBytes();
        }

        @Override
        public CompletableFuture<DispatchResult> dispatchAsync(final DispatchRequest request) {
            Objects.requireNonNull(request, "request");
            validateRequest(request);
            final long seq = this.frameCounter.incrementAndGet();
            return CompletableFuture.supplyAsync(() -> executeSync(request, seq), GoldLeafComputeServiceImpl.this.workerPool);
        }

        @Override
        public PacedSession startPacedSession(final int targetHz, final Consumer<DispatchResult> onComplete) {
            Objects.requireNonNull(onComplete, "onComplete");
            if (targetHz < MIN_TARGET_HZ || targetHz > MAX_TARGET_HZ) {
                throw new IllegalArgumentException(
                    "targetHz must be in [" + MIN_TARGET_HZ + ", " + MAX_TARGET_HZ + "], got " + targetHz
                );
            }
            if (this.handleClosed.get()) {
                throw new IllegalStateException("ComputeKernelHandle '" + this.descriptor.kernelId() + "' is closed");
            }
            final ManagedPacedSession session = new ManagedPacedSession(this, targetHz, onComplete);
            this.sessions.add(session);
            session.start();
            return session;
        }

        @Override
        public void close() {
            if (!this.handleClosed.compareAndSet(false, true)) {
                return;
            }
            for (final ManagedPacedSession session : new ArrayList<>(this.sessions)) {
                session.close();
            }
            this.sessions.clear();
            synchronized (this.dispatchLock) {
                if (this.persistentVram != null) {
                    this.persistentVram.close();
                }
                if (this.compiledKernel != null) {
                    this.compiledKernel.close();
                }
                this.stagingDirectIn = null;
                this.stagingDirectOut = null;
            }
            GoldLeafComputeServiceImpl.this.activeHandles.remove(this);
            GoldLeafComputeServiceImpl.this.releaseVramQuota(this.descriptor.ownerId(), this.reservedVramBytes);
        }

        private void validateRequest(final DispatchRequest request) {
            if (this.handleClosed.get()) {
                throw new IllegalStateException("ComputeKernelHandle '" + this.descriptor.kernelId() + "' is closed");
            }
            if (request.inputBytes() > this.descriptor.maxInputBytes()) {
                throw new IllegalArgumentException(
                    "inputBytes (" + request.inputBytes() + ") exceeds maxInputBytes (" + this.descriptor.maxInputBytes() + ")"
                );
            }
            if (request.outputBytes() > this.descriptor.maxOutputBytes()) {
                throw new IllegalArgumentException(
                    "outputBytes (" + request.outputBytes() + ") exceeds maxOutputBytes (" + this.descriptor.maxOutputBytes() + ")"
                );
            }
            if (request.globalWorkItems() > this.descriptor.maxGlobalWorkItems()) {
                throw new IllegalArgumentException(
                    "globalWorkItems (" + request.globalWorkItems() + ") exceeds maxGlobalWorkItems ("
                        + this.descriptor.maxGlobalWorkItems() + ")"
                );
            }
        }

        private DispatchResult executeSync(final DispatchRequest request, final long seq) {
            synchronized (this.dispatchLock) {
                if (this.handleClosed.get()) {
                    throw new IllegalStateException("ComputeKernelHandle '" + this.descriptor.kernelId() + "' is closed");
                }
                if (this.compiledKernel != null && !this.compiledKernel.isClosed()) {
                    final ByteBuffer directIn = ensureDirectInputLocked(request.input(), request.inputBytes());
                    final boolean callerOutDirect = request.output().isDirect();
                    final ByteBuffer directOut = callerOutDirect
                        ? request.output()
                        : ensureDirectOutputLocked(request.outputBytes());
                    final long[] timings = new long[2];
                    final int rc = this.compiledKernel.dispatchComputeKernel(
                        directIn,
                        request.inputBytes(),
                        this.persistentVram,
                        directOut,
                        request.outputBytes(),
                        request.params(),
                        request.globalWorkItems(),
                        request.localWorkItems(),
                        request.clearOutputBeforeDispatch(),
                        timings
                    );
                    if (rc == 0) {
                        if (!callerOutDirect) {
                            final ByteBuffer src = directOut.duplicate();
                            src.position(0).limit(request.outputBytes());
                            final ByteBuffer dst = request.output().duplicate();
                            dst.position(0).limit(request.outputBytes());
                            dst.put(src);
                        }
                        return new DispatchResult(
                            ExecutionBackend.OPENCL_GPU,
                            timings[0],
                            timings[1],
                            request.outputBytes(),
                            seq
                        );
                    }
                    LOGGER.warn(
                        "[GoldLeaf-Compute S-004] OpenCL dispatch failed for '{}' (rc={}); falling back to CPU",
                        this.descriptor.kernelId(),
                        rc
                    );
                }

                final long t0 = System.nanoTime();
                if (request.clearOutputBeforeDispatch()) {
                    final ByteBuffer outDup = request.output().duplicate();
                    for (int i = 0; i < request.outputBytes(); i++) {
                        outDup.put(i, (byte) 0);
                    }
                }
                final ByteBuffer inSlice = request.input().duplicate();
                inSlice.position(0).limit(request.inputBytes());
                final ByteBuffer outSlice = request.output().duplicate();
                outSlice.position(0).limit(request.outputBytes());
                this.descriptor.cpuFallback().execute(
                    inSlice,
                    this.cpuPersistentState,
                    outSlice,
                    request.params(),
                    request.globalWorkItems()
                );
                final long elapsed = Math.max(0L, System.nanoTime() - t0);
                return new DispatchResult(
                    ExecutionBackend.CPU_FALLBACK,
                    elapsed,
                    0L,
                    request.outputBytes(),
                    seq
                );
            }
        }

        private @Nullable ByteBuffer ensureDirectInputLocked(final ByteBuffer src, final int inputBytes) {
            if (inputBytes <= 0) {
                return null;
            }
            if (src.isDirect()) {
                return src;
            }
            if (this.stagingDirectIn == null || this.stagingDirectIn.capacity() < inputBytes) {
                this.stagingDirectIn = ByteBuffer.allocateDirect(inputBytes);
            }
            final ByteBuffer srcDup = src.duplicate();
            srcDup.position(0).limit(inputBytes);
            this.stagingDirectIn.clear();
            this.stagingDirectIn.put(srcDup);
            this.stagingDirectIn.flip();
            return this.stagingDirectIn;
        }

        private ByteBuffer ensureDirectOutputLocked(final int outputBytes) {
            if (this.stagingDirectOut == null || this.stagingDirectOut.capacity() < outputBytes) {
                this.stagingDirectOut = ByteBuffer.allocateDirect(outputBytes);
            }
            this.stagingDirectOut.clear();
            return this.stagingDirectOut;
        }
    }

    private final class ManagedPacedSession implements PacedSession {

        private final ManagedKernelHandle handle;
        private final int targetHz;
        private final Consumer<DispatchResult> onComplete;
        private final AtomicReference<@Nullable DispatchRequest> mailbox = new AtomicReference<>(null);
        private final AtomicBoolean inFlight = new AtomicBoolean(false);
        private final AtomicBoolean sessionClosed = new AtomicBoolean(false);
        private final AtomicInteger effectiveHz;
        private final AtomicInteger consecutiveOverruns = new AtomicInteger(0);
        private final AtomicLong submitted = new AtomicLong(0L);
        private final AtomicLong completed = new AtomicLong(0L);
        private final AtomicLong dropped = new AtomicLong(0L);
        private final AtomicReference<@Nullable ScheduledFuture<?>> futureRef = new AtomicReference<>(null);

        private ManagedPacedSession(
            final ManagedKernelHandle handle,
            final int targetHz,
            final Consumer<DispatchResult> onComplete
        ) {
            this.handle = handle;
            this.targetHz = targetHz;
            this.onComplete = onComplete;
            this.effectiveHz = new AtomicInteger(targetHz);
        }

        private void start() {
            reschedule(this.effectiveHz.get());
        }

        private void reschedule(final int hz) {
            if (this.sessionClosed.get()) {
                return;
            }
            final long periodMs = Math.max(50L, 1000L / Math.max(1, hz));
            final ScheduledFuture<?> next = GoldLeafComputeServiceImpl.this.pacingScheduler.scheduleAtFixedRate(
                this::onPaceTick,
                0L,
                periodMs,
                TimeUnit.MILLISECONDS
            );
            final ScheduledFuture<?> prev = this.futureRef.getAndSet(next);
            if (prev != null) {
                prev.cancel(false);
            }
            if (this.sessionClosed.get()) {
                next.cancel(false);
            }
        }

        private void onPaceTick() {
            if (this.sessionClosed.get() || this.handle.isClosed()) {
                close();
                return;
            }
            if (!this.inFlight.compareAndSet(false, true)) {
                return;
            }
            final DispatchRequest latest = this.mailbox.getAndSet(null);
            if (latest == null) {
                this.inFlight.set(false);
                return;
            }
            final long seq = this.handle.frameCounter.incrementAndGet();
            try {
                GoldLeafComputeServiceImpl.this.workerPool.execute(() -> {
                    try {
                        final DispatchResult result = this.handle.executeSync(latest, seq);
                        this.completed.incrementAndGet();
                        checkBudgetAndStepDown(result);
                        try {
                            this.onComplete.accept(result);
                        } catch (final RuntimeException cbEx) {
                            LOGGER.warn(
                                "[GoldLeaf-Compute S-004] PacedSession callback failed for '{}': {}",
                                this.handle.kernelId(),
                                cbEx.getMessage()
                            );
                        }
                    } catch (final RuntimeException ex) {
                        LOGGER.warn(
                            "[GoldLeaf-Compute S-004] PacedSession dispatch failed for '{}': {}",
                            this.handle.kernelId(),
                            ex.getMessage()
                        );
                    } finally {
                        this.inFlight.set(false);
                    }
                });
            } catch (final RuntimeException ex) {
                this.inFlight.set(false);
                LOGGER.warn(
                    "[GoldLeaf-Compute S-004] Failed to enqueue PacedSession frame for '{}': {}",
                    this.handle.kernelId(),
                    ex.getMessage()
                );
            }
        }

        private void checkBudgetAndStepDown(final DispatchResult result) {
            final long budgetNanos = GoldLeafComputeServiceImpl.this.policySupplier.get().maxGpuBudgetNanosPerTick();
            if (result.computeNanos() > budgetNanos) {
                if (this.consecutiveOverruns.incrementAndGet() >= CONSECUTIVE_OVERRUNS_BEFORE_STEPDOWN) {
                    this.consecutiveOverruns.set(0);
                    final int current = this.effectiveHz.get();
                    if (current > MIN_TARGET_HZ) {
                        final int stepped = Math.max(MIN_TARGET_HZ, current / 2);
                        if (this.effectiveHz.compareAndSet(current, stepped)) {
                            LOGGER.warn(
                                "[GoldLeaf-Compute S-004] Kernel '{}' exceeded per-tick compute budget ({} ns > {} ns); stepping cadence {} Hz -> {} Hz",
                                this.handle.kernelId(),
                                result.computeNanos(),
                                budgetNanos,
                                current,
                                stepped
                            );
                            reschedule(stepped);
                        }
                    }
                }
            } else {
                this.consecutiveOverruns.set(0);
            }
        }

        @Override
        public String kernelId() {
            return this.handle.kernelId();
        }

        @Override
        public int targetHz() {
            return this.targetHz;
        }

        @Override
        public int effectiveHz() {
            return this.effectiveHz.get();
        }

        @Override
        public long submittedFrames() {
            return this.submitted.get();
        }

        @Override
        public long completedFrames() {
            return this.completed.get();
        }

        @Override
        public long droppedFrames() {
            return this.dropped.get();
        }

        @Override
        public boolean isClosed() {
            return this.sessionClosed.get();
        }

        @Override
        public boolean submitLatest(final DispatchRequest request) {
            Objects.requireNonNull(request, "request");
            if (this.sessionClosed.get() || this.handle.isClosed()) {
                return false;
            }
            this.handle.validateRequest(request);
            this.submitted.incrementAndGet();
            final DispatchRequest overwritten = this.mailbox.getAndSet(request);
            if (overwritten != null) {
                this.dropped.incrementAndGet();
            }
            return true;
        }

        @Override
        public void close() {
            if (!this.sessionClosed.compareAndSet(false, true)) {
                return;
            }
            this.mailbox.set(null);
            final ScheduledFuture<?> f = this.futureRef.getAndSet(null);
            if (f != null) {
                f.cancel(false);
            }
            this.handle.sessions.remove(this);
        }
    }
}
