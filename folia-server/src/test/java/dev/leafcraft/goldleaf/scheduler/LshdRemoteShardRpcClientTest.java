package dev.leafcraft.goldleaf.scheduler;

import dev.leafcraft.goldleaf.storage.LcaChunkResidencyCache;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LshdRemoteShardRpcClientTest {

    private static RawChunkFrameSnapshot dummyFrame(final int dataVersion, final int sectionCount) {
        final int len = Math.max(128, 32 + sectionCount * 2);
        final byte[] raw = new byte[len];
        raw[0] = 0x11;
        raw[len - 1] = 0x7E;
        return new RawChunkFrameSnapshot(
            raw,
            dataVersion,
            -4,
            sectionCount,
            len,
            new int[]{0, 1, 9}
        );
    }

    @Test
    void roundTripsLshdRequestAndSecDedVerifiedResponse() throws Exception {
        final byte[] reqBytes = LshdRemoteShardRpcClient.encodeProbeRequest(42L, 12, -34);
        final LshdRemoteShardRpcClient.ProbeRequest req = LshdRemoteShardRpcClient.decodeProbeRequest(reqBytes);
        assertEquals(42L, req.requestId());
        assertEquals(12, req.chunkX());
        assertEquals(-34, req.chunkZ());

        final RawChunkFrameSnapshot original = dummyFrame(4189, 24);
        final byte[] respBytes = LshdRemoteShardRpcClient.encodeProbeResponseOk(42L, original);
        final LshdRemoteShardRpcClient.ProbeResponse resp = LshdRemoteShardRpcClient.decodeProbeResponse(respBytes);
        assertEquals(42L, resp.requestId());
        assertEquals(LshdRemoteShardRpcClient.STATUS_OK, resp.status());
        assertNotNull(resp.snapshot());
        assertEquals(original, resp.snapshot());
        assertArrayEquals(original.dictIdToRuntimeId(), resp.snapshot().dictIdToRuntimeId());

        // Flip a payload byte -> SEC-DED-64 verification must fail closed (S-004)
        final byte[] corrupted = respBytes.clone();
        corrupted[corrupted.length - 16] ^= 0x55;
        assertThrows(IOException.class, () -> LshdRemoteShardRpcClient.decodeProbeResponse(corrupted));
    }

    @Test
    void routesTier4RemoteShardThroughTieredChunkReadRouterNonBlockingly() throws Exception {
        final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "goldleaf-tier4-io");
            t.setDaemon(true);
            return t;
        });

        final RawChunkFrameSnapshot remoteSnapshot = dummyFrame(4189, 24);
        final AtomicReference<LshdRemoteShardRpcClient> clientRef = new AtomicReference<>();
        final AtomicReference<String> observedTargetShard = new AtomicReference<>();

        try (LshdRemoteShardRpcClient rpcClient = new LshdRemoteShardRpcClient(
            (key, cx, cz) -> (cx == 200 && cz == -300) ? "shard-east-2" : null,
            (shardId, reqFrame) -> {
                observedTargetShard.set(shardId);
                // Simulate async network response from remote shard
                ioExecutor.execute(() -> {
                    try {
                        final LshdRemoteShardRpcClient.ProbeRequest req =
                            LshdRemoteShardRpcClient.decodeProbeRequest(reqFrame);
                        final byte[] resp = LshdRemoteShardRpcClient.encodeProbeResponseOk(
                            req.requestId(),
                            remoteSnapshot
                        );
                        clientRef.get().onResponseFrameReceived(resp);
                    } catch (final IOException ex) {
                        throw new RuntimeException(ex);
                    }
                });
            },
            500L,
            32
        )) {
            clientRef.set(rpcClient);
            final TieredChunkReadRouter router = new TieredChunkReadRouter(
                new LcaChunkResidencyCache(),
                null,
                rpcClient
            );

            final TieredChunkReadRouter.RoutedReadResult<Integer> result = router.probeChunk(
                ChunkPos.pack(200, -300),
                200,
                -300,
                s -> 0,
                snap -> -1,
                RawChunkFrameSnapshot::dataVersion,
                () -> -2,
                ioExecutor
            ).get();

            assertEquals("shard-east-2", observedTargetShard.get());
            assertEquals(TieredChunkReadRouter.ReadTier.TIER_4_REMOTE_SHARD, result.tier());
            assertEquals(4189, result.value());
            assertEquals(0, rpcClient.pendingCount());
        } finally {
            ioExecutor.shutdownNow();
        }
    }

    @Test
    void fallsThroughToFallbackWhenRemoteShardReturnsNotFoundOrCloses() throws Exception {
        final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
        final AtomicReference<LshdRemoteShardRpcClient> clientRef = new AtomicReference<>();

        try (LshdRemoteShardRpcClient rpcClient = new LshdRemoteShardRpcClient(
            (key, cx, cz) -> "shard-west-1",
            (shardId, reqFrame) -> ioExecutor.execute(() -> {
                try {
                    final LshdRemoteShardRpcClient.ProbeRequest req =
                        LshdRemoteShardRpcClient.decodeProbeRequest(reqFrame);
                    if (req.chunkX() == 10) {
                        clientRef.get().onResponseFrameReceived(
                            LshdRemoteShardRpcClient.encodeProbeResponseNotFound(req.requestId())
                        );
                    }
                    // For chunkX == 20, leave in-flight so close() cancels it
                } catch (final IOException ex) {
                    throw new RuntimeException(ex);
                }
            }),
            500L,
            16
        )) {
            clientRef.set(rpcClient);
            final TieredChunkReadRouter router = new TieredChunkReadRouter(
                new LcaChunkResidencyCache(),
                null,
                rpcClient
            );

            // 1. STATUS_NOT_FOUND -> falls through cleanly to Tier 2 fallback
            final TieredChunkReadRouter.RoutedReadResult<String> notFoundResult = router.probeChunk(
                ChunkPos.pack(10, 10), 10, 10, s -> 0,
                snap -> "resident",
                frame -> "remote",
                () -> "local-fallback",
                ioExecutor
            ).get();
            assertEquals(TieredChunkReadRouter.ReadTier.TIER_2_LOCAL_INTERRUPT, notFoundResult.tier());
            assertEquals("local-fallback", notFoundResult.value());

            // 2. Client close() while RPC is in-flight -> cancels pending future (S-002) and router falls back
            final CompletableFuture<TieredChunkReadRouter.RoutedReadResult<String>> inFlight = router.probeChunk(
                ChunkPos.pack(20, 20), 20, 20, s -> 0,
                snap -> "resident",
                frame -> "remote",
                () -> "close-fallback",
                ioExecutor
            );
            rpcClient.close();
            assertEquals(0, rpcClient.pendingCount());
            assertTrue(rpcClient.isClosed());

            final TieredChunkReadRouter.RoutedReadResult<String> afterClose = inFlight.get();
            assertEquals(TieredChunkReadRouter.ReadTier.TIER_2_LOCAL_INTERRUPT, afterClose.tier());
            assertEquals("close-fallback", afterClose.value());
        } finally {
            ioExecutor.shutdownNow();
        }
    }

    @Test
    void failsClosedOnTimeoutAndBackpressureCap() throws Exception {
        final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
        try (LshdRemoteShardRpcClient rpcClient = new LshdRemoteShardRpcClient(
            (key, cx, cz) -> "shard-slow",
            (shardId, reqFrame) -> {
                // Intentionally never reply to trigger timeout & backpressure
            },
            30L,
            1
        )) {
            final CompletableFuture<RawChunkFrameSnapshot> first = rpcClient.fetchRemoteChunkFrame(
                ChunkPos.pack(1, 1), 1, 1, s -> 0
            );
            // Second request exceeds maxInFlightRpcs=1 -> fails immediately with IOException (S-004)
            final CompletableFuture<RawChunkFrameSnapshot> second = rpcClient.fetchRemoteChunkFrame(
                ChunkPos.pack(2, 2), 2, 2, s -> 0
            );
            final ExecutionException capEx = assertThrows(ExecutionException.class, second::get);
            assertInstanceOf(IOException.class, capEx.getCause());

            // First request times out and cleans up pendingRpcs (S-002 / S-004)
            final ExecutionException timeoutEx = assertThrows(ExecutionException.class, first::get);
            assertInstanceOf(TimeoutException.class, timeoutEx.getCause());
            assertTrue(LshdRemoteShardRpcClient.isTimeoutFailure(timeoutEx));
            assertEquals(0, rpcClient.pendingCount());
            assertFalse(rpcClient.isClosed());
        } finally {
            ioExecutor.shutdownNow();
        }
    }

    @Test
    void returnsNullWhenShardLocatorReportsLocalChunk() throws Exception {
        try (LshdRemoteShardRpcClient rpcClient = new LshdRemoteShardRpcClient(
            (key, cx, cz) -> null,
            (shardId, reqFrame) -> {
                throw new AssertionError("Should not send RPC for locally owned chunk");
            },
            100L,
            8
        )) {
            assertFalse(rpcClient.isRemoteOwned(ChunkPos.pack(0, 0), 0, 0));
            assertNull(rpcClient.fetchRemoteChunkFrame(ChunkPos.pack(0, 0), 0, 0, s -> 0).get());
        }
    }
}
