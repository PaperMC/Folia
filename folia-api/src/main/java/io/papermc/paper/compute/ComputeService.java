package io.papermc.paper.compute;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Public plugin-facing GPU/CPU compute offload SPI ({@code goldleaf-ADR-004},
 * {@code goldleaf-ADR-017}).
 *
 * <p>Allows plugins to execute audited built-in compute kernels (such as
 * {@link BuiltInKernels#MASS_EXPLOSION_RAYCAST}) or admin-allowlisted custom OpenCL C99 kernels
 * off-tick ({@code S-005}) with automatic CPU fallback, strict VRAM quotas ({@code S-002}), and
 * optional {@code 1..20 Hz} latest-frame-wins paced execution.</p>
 *
 * <p>Plugins may discover this service via {@link #get()}.</p>
 */
@NullMarked
public interface ComputeService {

    int MIN_TARGET_HZ = 1;
    int MAX_TARGET_HZ = 20;

    /**
     * Execution backend used to satisfy a compute dispatch.
     */
    enum ExecutionBackend {
        OPENCL_GPU,
        CPU_FALLBACK
    }

    /**
     * Built-in audited kernel identifiers shipped by the server.
     */
    final class BuiltInKernels {
        /**
         * Batched vanilla 16x16x16 cube-surface (1,352 rays per explosion) mass explosion
         * raycaster producing a compact destroyed-block bitset over an immutable blast-resistance
         * grid snapshot.
         */
        public static final String MASS_EXPLOSION_RAYCAST = "paper:mass_explosion_raycast";

        private BuiltInKernels() {
        }
    }

    /**
     * Mandatory CPU fallback executor invoked whenever OpenCL GPU acceleration is unavailable,
     * disabled, or bypassed.
     */
    @FunctionalInterface
    interface CpuKernelExecutor {
        /**
         * Executes the kernel on CPU using the same buffer layout and scalar parameters as the
         * OpenCL kernel.
         *
         * @param input      read-only direct/heap input buffer ({@code inputBytes} readable bytes)
         * @param state      optional persistent state buffer ({@code null} if {@code persistentStateBytes == 0})
         * @param output     caller-owned output buffer to populate ({@code outputBytes} writable bytes)
         * @param params     scalar integer parameters
         * @param globalWork total 1D work-item count
         */
        void execute(
            ByteBuffer input,
            @Nullable ByteBuffer state,
            ByteBuffer output,
            int[] params,
            int globalWork
        );
    }

    /**
     * Immutable descriptor for registering a custom OpenCL C99 compute kernel.
     *
     * <p>Custom kernels must adhere to the packed-kernel ABI:
     * {@code __kernel void <kernelName>(__global const uchar* in_buf, __global uchar* state_buf,
     * __global uchar* out_buf, __constant const int* params, const int param_count,
     * const int global_items)}.</p>
     */
    record KernelDescriptor(
        String ownerId,
        String kernelId,
        String kernelName,
        String openClSource,
        String buildOptions,
        int maxInputBytes,
        int maxOutputBytes,
        int persistentStateBytes,
        int maxGlobalWorkItems,
        CpuKernelExecutor cpuFallback
    ) {
        public KernelDescriptor {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(kernelId, "kernelId");
            Objects.requireNonNull(kernelName, "kernelName");
            Objects.requireNonNull(openClSource, "openClSource");
            Objects.requireNonNull(buildOptions, "buildOptions");
            Objects.requireNonNull(cpuFallback, "cpuFallback");
            if (ownerId.isBlank()) {
                throw new IllegalArgumentException("ownerId must not be blank");
            }
            if (kernelId.isBlank()) {
                throw new IllegalArgumentException("kernelId must not be blank");
            }
            if (kernelName.isBlank()) {
                throw new IllegalArgumentException("kernelName must not be blank");
            }
            if (openClSource.isBlank()) {
                throw new IllegalArgumentException("openClSource must not be blank");
            }
            if (maxInputBytes < 0) {
                throw new IllegalArgumentException("maxInputBytes must be >= 0");
            }
            if (maxOutputBytes <= 0) {
                throw new IllegalArgumentException("maxOutputBytes must be > 0");
            }
            if (persistentStateBytes < 0) {
                throw new IllegalArgumentException("persistentStateBytes must be >= 0");
            }
            if (maxGlobalWorkItems <= 0) {
                throw new IllegalArgumentException("maxGlobalWorkItems must be > 0");
            }
        }
    }

    /**
     * Single dispatch invocation request against a {@link ComputeKernelHandle}.
     */
    record DispatchRequest(
        ByteBuffer input,
        int inputBytes,
        ByteBuffer output,
        int outputBytes,
        int[] params,
        int globalWorkItems,
        int localWorkItems,
        boolean clearOutputBeforeDispatch
    ) {
        public DispatchRequest {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(params, "params");
            if (inputBytes < 0 || inputBytes > input.capacity()) {
                throw new IllegalArgumentException("inputBytes out of bounds: " + inputBytes);
            }
            if (outputBytes <= 0 || outputBytes > output.capacity()) {
                throw new IllegalArgumentException("outputBytes out of bounds: " + outputBytes);
            }
            if (globalWorkItems <= 0) {
                throw new IllegalArgumentException("globalWorkItems must be > 0");
            }
            if (localWorkItems < 0) {
                throw new IllegalArgumentException("localWorkItems must be >= 0");
            }
            params = params.clone();
        }

        public static DispatchRequest of(
            final ByteBuffer input,
            final int inputBytes,
            final ByteBuffer output,
            final int outputBytes,
            final int[] params,
            final int globalWorkItems
        ) {
            return new DispatchRequest(input, inputBytes, output, outputBytes, params, globalWorkItems, 0, false);
        }
    }

    /**
     * Timing and execution metadata returned when a dispatch completes.
     */
    record DispatchResult(
        ExecutionBackend backend,
        long computeNanos,
        long readbackNanos,
        int outputBytes,
        long frameSequence
    ) {
    }

    /**
     * Latest-frame-wins {@code 1..20 Hz} frame-paced compute session backed by a bounded 2-slot
     * mailbox. Submitting a new frame while a previous frame is still queued drops the stale
     * unstarted frame rather than growing an unbounded queue.
     */
    interface PacedSession extends AutoCloseable {

        String kernelId();

        int targetHz();

        /**
         * Effective cadence after any automatic GPU-budget step-down ({@code 1..targetHz}).
         */
        int effectiveHz();

        long submittedFrames();

        long completedFrames();

        long droppedFrames();

        boolean isClosed();

        /**
         * Submits the latest frame request into the bounded mailbox. Never blocks the calling
         * thread ({@code S-005}).
         *
         * @return {@code true} if accepted, {@code false} if the session is closed
         */
        boolean submitLatest(DispatchRequest request);

        @Override
        void close();
    }

    /**
     * Scoped handle for a registered or opened compute kernel. Must be closed on plugin disable to
     * release native {@code cl_mem}/{@code cl_kernel}/{@code cl_program} resources and VRAM quota
     * ({@code S-002}).
     */
    interface ComputeKernelHandle extends AutoCloseable {

        String ownerId();

        String kernelId();

        boolean isGpuCompiled();

        boolean isClosed();

        int maxInputBytes();

        int maxOutputBytes();

        int persistentStateBytes();

        /**
         * Dispatches a single off-tick compute batch asynchronously ({@code S-005}).
         */
        CompletableFuture<DispatchResult> dispatchAsync(DispatchRequest request);

        /**
         * Starts a {@code 1..20 Hz} latest-frame-wins paced session on this kernel handle.
         *
         * @param targetHz   desired cadence in {@code [1, 20]}
         * @param onComplete off-tick callback invoked when each completed frame finishes
         */
        PacedSession startPacedSession(int targetHz, Consumer<DispatchResult> onComplete);

        @Override
        void close();
    }

    /**
     * @return {@code true} if the compute service is enabled in server configuration
     */
    boolean isEnabled();

    /**
     * @return {@code true} if at least one non-PoCL OpenCL GPU device is ready for compute dispatch
     */
    boolean isGpuAvailable();

    /**
     * @return immutable set of built-in audited kernel identifiers
     */
    Set<String> builtInKernelIds();

    /**
     * @return total persistent + scratch VRAM currently reserved across all active handles
     */
    long allocatedVramBytes();

    /**
     * Opens a handle to a built-in audited kernel (e.g.
     * {@link BuiltInKernels#MASS_EXPLOSION_RAYCAST}).
     *
     * @param ownerId          owning plugin or subsystem identifier (used for VRAM quota &amp; teardown)
     * @param builtInKernelId  identifier from {@link BuiltInKernels}
     * @throws IllegalStateException    if the compute service is disabled
     * @throws IllegalArgumentException if {@code builtInKernelId} is unknown
     */
    ComputeKernelHandle openBuiltInKernel(String ownerId, String builtInKernelId);

    /**
     * Registers a custom OpenCL C99 kernel with a mandatory CPU fallback.
     *
     * <p>Fails closed with {@link SecurityException} unless {@code compute.allow-custom-opencl} is
     * enabled and {@code descriptor.ownerId()} is present in
     * {@code compute.custom-kernel-allowlist}.</p>
     *
     * @throws IllegalStateException if the compute service is disabled or VRAM quota is exceeded
     * @throws SecurityException     if custom OpenCL kernels are disallowed for {@code ownerId}
     */
    ComputeKernelHandle registerCustomKernel(KernelDescriptor descriptor);

    /**
     * Releases all active handles and paced sessions owned by {@code ownerId} (e.g., on plugin
     * disable, {@code S-002}).
     *
     * @return number of handles closed
     */
    int closeAllForOwner(String ownerId);

    /**
     * Global service holder populated by the server bootstrap at startup.
     */
    final class Holder {
        private static volatile @Nullable ComputeService instance;

        private Holder() {
        }
    }

    static @Nullable ComputeService get() {
        return Holder.instance;
    }

    static void register(final @Nullable ComputeService service) {
        Holder.instance = service;
    }
}
