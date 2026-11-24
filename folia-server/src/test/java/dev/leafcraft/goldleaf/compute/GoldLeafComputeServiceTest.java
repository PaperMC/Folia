package dev.leafcraft.goldleaf.compute;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import io.papermc.paper.compute.ComputeService;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate 1 tests for {@link ComputeService}, {@link GoldLeafComputeServiceImpl}, and the
 * built-in {@link MassExplosionRaycastKernel} ({@code goldleaf-ADR-004}, {@code goldleaf-ADR-017}).
 */
class GoldLeafComputeServiceTest {

    private static GoldLeafComputeServiceImpl.ComputePolicy permissivePolicy(
        final boolean allowCustom,
        final Set<String> allowlist,
        final long maxPerOwner,
        final long maxTotal,
        final long budgetNanos
    ) {
        return new GoldLeafComputeServiceImpl.ComputePolicy(
            true,
            allowCustom,
            allowlist,
            maxPerOwner,
            maxTotal,
            budgetNanos,
            2
        );
    }

    @Test
    void massExplosionRaycastMatchesBitExactParityBetweenGpuAndCpu(@TempDir final Path tempDir) throws Exception {
        final int sizeX = 48;
        final int sizeY = 24;
        final int sizeZ = 48;
        final float[] resistances = new float[sizeX * sizeY * sizeZ];
        Arrays.fill(resistances, MassExplosionRaycastKernel.AIR_RESISTANCE);

        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    final int idx = (y * sizeZ + z) * sizeX + x;
                    if (y == 0) {
                        resistances[idx] = 3_600_000.0f; // bedrock
                    } else if (y <= 6) {
                        resistances[idx] = 6.0f; // stone
                    } else if (y <= 11) {
                        resistances[idx] = 0.5f; // dirt
                    }
                    // Obsidian shield wall at x == 24, y in [7..14]
                    if (x == 24 && y >= 7 && y <= 14) {
                        resistances[idx] = 1200.0f;
                    }
                }
            }
        }

        final List<MassExplosionRaycastKernel.ExplosionSpec> explosions = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            final float ex = 6.5f + (i % 4) * 11.0f;
            final float ez = 6.5f + (i / 4) * 11.0f;
            final float ey = 12.25f;
            final float power = (i == 7) ? 6.0f : 4.0f;
            explosions.add(new MassExplosionRaycastKernel.ExplosionSpec(ex, ey, ez, power, 1000 + i * 37));
        }

        final GoldLeafComputeServiceImpl.ComputePolicy policy = permissivePolicy(
            false,
            Set.of(),
            64L * 1024L * 1024L,
            256L * 1024L * 1024L,
            10_000_000L
        );

        final MassExplosionRaycastKernel.PackedBatch cpuBatch = MassExplosionRaycastKernel.packBatch(
            sizeX, sizeY, sizeZ, resistances, explosions
        );
        final ComputeService.DispatchResult cpuResult;
        try (GoldLeafComputeServiceImpl cpuService = new GoldLeafComputeServiceImpl(policy, tempDir.resolve("cpu"), true);
             ComputeService.ComputeKernelHandle cpuHandle = cpuService.openBuiltInKernel(
                 "explosion-test",
                 ComputeService.BuiltInKernels.MASS_EXPLOSION_RAYCAST
             )) {
            assertFalse(cpuHandle.isGpuCompiled());
            cpuResult = cpuHandle.dispatchAsync(cpuBatch.toDispatchRequest()).get(10, TimeUnit.SECONDS);
            assertEquals(ComputeService.ExecutionBackend.CPU_FALLBACK, cpuResult.backend());
            assertEquals(cpuBatch.outputBytes(), cpuResult.outputBytes());
        }

        final List<MassExplosionRaycastKernel.VoxelPos> cpuDestroyed = cpuBatch.decodeDestroyedVoxels();
        assertTrue(cpuDestroyed.size() > 200, "Expected 16 TNT blasts to destroy > 200 dirt/stone blocks, got " + cpuDestroyed.size());
        for (final MassExplosionRaycastKernel.VoxelPos pos : cpuDestroyed) {
            assertTrue(pos.y() > 0, "Bedrock at y=0 must never be destroyed");
            assertFalse(
                pos.x() == 24 && pos.y() >= 7 && pos.y() <= 14,
                "Obsidian shield wall must never be destroyed: " + pos
            );
        }

        // Now run through the live OpenCL GPU path when a GPU device is present on this host
        try (GoldLeafComputeServiceImpl gpuService = new GoldLeafComputeServiceImpl(policy, tempDir.resolve("gpu"), false)) {
            if (!gpuService.isGpuAvailable()) {
                return;
            }
            final MassExplosionRaycastKernel.PackedBatch gpuBatch = MassExplosionRaycastKernel.packBatch(
                sizeX, sizeY, sizeZ, resistances, explosions
            );
            try (ComputeService.ComputeKernelHandle gpuHandle = gpuService.openBuiltInKernel(
                "explosion-test",
                ComputeService.BuiltInKernels.MASS_EXPLOSION_RAYCAST
            )) {
                assertTrue(gpuHandle.isGpuCompiled(), "Built-in mass explosion kernel should compile on OpenCL GPU");
                final ComputeService.DispatchResult gpuResult = gpuHandle
                    .dispatchAsync(gpuBatch.toDispatchRequest())
                    .get(10, TimeUnit.SECONDS);
                assertEquals(ComputeService.ExecutionBackend.OPENCL_GPU, gpuResult.backend());
                assertEquals(gpuBatch.outputBytes(), gpuResult.outputBytes());

                final Set<MassExplosionRaycastKernel.VoxelPos> cpuSet = new HashSet<>(cpuDestroyed);
                final Set<MassExplosionRaycastKernel.VoxelPos> gpuSet = new HashSet<>(gpuBatch.decodeDestroyedVoxels());
                assertEquals(cpuSet, gpuSet, "GPU and CPU mass explosion raycast bitsets must match bit-for-bit");
            }
            assertEquals(0L, gpuService.allocatedVramBytes(), "Closing handle must release reserved VRAM quota");
        }
    }

    @Test
    void customKernelAllowlistFailsClosedAndPersistentStateWorksAcrossDispatches(@TempDir final Path tempDir) throws Exception {
        final String accumSource = """
            __kernel void accum_frame(
                __global const uchar* in_buf,
                __global uchar* state_buf,
                __global uchar* out_buf,
                __constant const int* params,
                const int param_count,
                const int global_items
            ) {
                const int gid = (int)get_global_id(0);
                if (gid >= global_items) {
                    return;
                }
                __global const int* in_i = (__global const int*)in_buf;
                __global int* st_i = (__global int*)state_buf;
                __global int* out_i = (__global int*)out_buf;
                const int add = param_count > 0 ? params[0] : 0;
                const int next = st_i[gid] + in_i[gid] + add;
                st_i[gid] = next;
                out_i[gid] = next;
            }
            """;

        final ComputeService.CpuKernelExecutor cpuAccum = (in, state, out, params, globalWork) -> {
            assertNotNull(state);
            final ByteBuffer inLe = in.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            final ByteBuffer stLe = state.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            final ByteBuffer outLe = out.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            final int add = params.length > 0 ? params[0] : 0;
            for (int i = 0; i < globalWork; i++) {
                final int off = i * Integer.BYTES;
                final int next = stLe.getInt(off) + inLe.getInt(off) + add;
                stLe.putInt(off, next);
                outLe.putInt(off, next);
            }
        };

        final ComputeService.KernelDescriptor blockedDesc = new ComputeService.KernelDescriptor(
            "untrusted-plugin",
            "untrusted:accum",
            "accum_frame",
            accumSource,
            "",
            64,
            64,
            64,
            16,
            cpuAccum
        );

        final GoldLeafComputeServiceImpl.ComputePolicy restricted = permissivePolicy(
            false,
            Set.of("trusted-plugin"),
            1024 * 1024,
            4 * 1024 * 1024,
            6_000_000L
        );
        try (GoldLeafComputeServiceImpl svc = new GoldLeafComputeServiceImpl(restricted, tempDir.resolve("denied"), true)) {
            assertThrows(SecurityException.class, () -> svc.registerCustomKernel(blockedDesc));
            assertEquals(0L, svc.allocatedVramBytes());
        }

        final ComputeService.KernelDescriptor allowedDesc = new ComputeService.KernelDescriptor(
            "trusted-plugin",
            "trusted:accum",
            "accum_frame",
            accumSource,
            "",
            64,
            64,
            64,
            16,
            cpuAccum
        );
        final GoldLeafComputeServiceImpl.ComputePolicy allowed = permissivePolicy(
            true,
            Set.of("trusted-plugin"),
            1024 * 1024,
            4 * 1024 * 1024,
            6_000_000L
        );
        try (GoldLeafComputeServiceImpl svc = new GoldLeafComputeServiceImpl(allowed, tempDir.resolve("allowed"), false);
             ComputeService.ComputeKernelHandle handle = svc.registerCustomKernel(allowedDesc)) {
            assertEquals(192L, svc.allocatedVramBytes());

            final ByteBuffer inBuf = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 4; i++) {
                inBuf.putInt(i * 4, i + 1); // [1, 2, 3, 4]
            }
            final ByteBuffer outBuf = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN);
            final ComputeService.DispatchRequest req = ComputeService.DispatchRequest.of(
                inBuf, 16, outBuf, 16, new int[] { 10 }, 4
            );

            handle.dispatchAsync(req).get(10, TimeUnit.SECONDS);
            assertEquals(11, outBuf.getInt(0));
            assertEquals(12, outBuf.getInt(4));
            assertEquals(13, outBuf.getInt(8));
            assertEquals(14, outBuf.getInt(12));

            // Second dispatch should accumulate on top of persistent VRAM / state buffer
            handle.dispatchAsync(req).get(10, TimeUnit.SECONDS);
            assertEquals(22, outBuf.getInt(0));
            assertEquals(24, outBuf.getInt(4));
            assertEquals(26, outBuf.getInt(8));
            assertEquals(28, outBuf.getInt(12));

            // Idempotent close (S-002)
            handle.close();
            handle.close();
            assertTrue(handle.isClosed());
            assertEquals(0L, svc.allocatedVramBytes());
        }
    }

    @Test
    void pacedSessionDropsStaleFramesAndStepsDownCadenceOnBudgetOverrun(@TempDir final Path tempDir) throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final ComputeService.CpuKernelExecutor slowCpu = (in, state, out, params, globalWork) -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(4L);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            out.putInt(0, params[0]);
        };

        final ComputeService.KernelDescriptor desc = new ComputeService.KernelDescriptor(
            "video-plugin",
            "video:frame",
            "dummy_frame",
            "__kernel void dummy_frame(__global const uchar* a, __global uchar* b, __global uchar* c, __constant const int* p, int n, int g) {}",
            "",
            16,
            16,
            0,
            1,
            slowCpu
        );
        // Tiny budget (100 us) so 4 ms CPU kernel triggers automatic cadence step-down after 3 frames
        final GoldLeafComputeServiceImpl.ComputePolicy tightBudget = permissivePolicy(
            true,
            Set.of("video-plugin"),
            1024 * 1024,
            4 * 1024 * 1024,
            100_000L
        );

        try (GoldLeafComputeServiceImpl svc = new GoldLeafComputeServiceImpl(tightBudget, tempDir.resolve("paced"), true);
             ComputeService.ComputeKernelHandle handle = svc.registerCustomKernel(desc)) {
            final CountDownLatch fourCompletions = new CountDownLatch(4);
            try (ComputeService.PacedSession session = handle.startPacedSession(20, res -> fourCompletions.countDown())) {
                final ByteBuffer inBuf = ByteBuffer.allocateDirect(4);
                final ByteBuffer outBuf = ByteBuffer.allocateDirect(4);

                // Burst 10 frames immediately -> latest-frame-wins mailbox must drop stale ones
                for (int i = 0; i < 10; i++) {
                    assertTrue(session.submitLatest(
                        ComputeService.DispatchRequest.of(inBuf, 4, outBuf, 4, new int[] { i }, 1)
                    ));
                }
                assertTrue(session.droppedFrames() > 0, "Latest-frame-wins mailbox should drop overwritten frames");

                // Feed additional frames across ticks so 4 frames complete and trigger step-down
                for (int tick = 0; tick < 12 && fourCompletions.getCount() > 0; tick++) {
                    session.submitLatest(
                        ComputeService.DispatchRequest.of(inBuf, 4, outBuf, 4, new int[] { 100 + tick }, 1)
                    );
                    Thread.sleep(60L);
                }
                assertTrue(fourCompletions.await(3, TimeUnit.SECONDS), "Expected at least 4 paced frames to complete");
                assertTrue(
                    session.effectiveHz() < session.targetHz(),
                    "Expected automatic cadence step-down below 20 Hz, got " + session.effectiveHz()
                );
            }
        }
    }

    @Test
    void enforcesVramQuotasAndOwnerTeardown(@TempDir final Path tempDir) throws Exception {
        final GoldLeafComputeServiceImpl.ComputePolicy smallQuota = permissivePolicy(
            false,
            Set.of(),
            10L * 1024L * 1024L, // fits 1 built-in mass explosion handle (~8.25 MiB), rejects 2nd for same owner
            20L * 1024L * 1024L,
            6_000_000L
        );
        try (GoldLeafComputeServiceImpl svc = new GoldLeafComputeServiceImpl(smallQuota, tempDir.resolve("quota"), true)) {
            final ComputeService.ComputeKernelHandle h1 = svc.openBuiltInKernel(
                "plugin-a",
                ComputeService.BuiltInKernels.MASS_EXPLOSION_RAYCAST
            );
            assertThrows(
                IllegalStateException.class,
                () -> svc.openBuiltInKernel("plugin-a", ComputeService.BuiltInKernels.MASS_EXPLOSION_RAYCAST)
            );
            final ComputeService.ComputeKernelHandle h2 = svc.openBuiltInKernel(
                "plugin-b",
                ComputeService.BuiltInKernels.MASS_EXPLOSION_RAYCAST
            );
            assertEquals(1, svc.closeAllForOwner("plugin-a"));
            assertTrue(h1.isClosed());
            assertFalse(h2.isClosed());
            assertEquals(1, svc.closeAllForOwner("plugin-b"));
            assertTrue(h2.isClosed());
            assertEquals(0L, svc.allocatedVramBytes());
        }

        final GoldLeafConfig cfg = GoldLeafConfig.defaults();
        cfg.validate();
        assertTrue(cfg.compute.enabled);
        assertFalse(cfg.compute.allowCustomOpenCl);
    }
}
