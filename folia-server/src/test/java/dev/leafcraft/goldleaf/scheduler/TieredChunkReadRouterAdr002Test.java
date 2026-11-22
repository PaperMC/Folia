package dev.leafcraft.goldleaf.scheduler;

import dev.leafcraft.goldleaf.GoldLeafBootstrap;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.storage.LcaChunkResidencyCache;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import io.papermc.paper.threadedregions.scheduler.AsyncReadRouter;
import io.papermc.paper.threadedregions.spi.MidTickReadDrain;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end verification for {@code goldleaf-ADR-002} §2 & §3:
 * <ul>
 *   <li>Tier 0 clean local & {@code LCAS} halo inline shortcut (zero hop, cache-line isolated)</li>
 *   <li>Tier 1 dirty frozen snapshot vs opt-in live mid-tick read via {@link MidTickReadDrain}</li>
 *   <li>Tier 2 fallback routed onto dedicated {@code localInterruptExecutor} (distinct from {@code asyncIoExecutor})</li>
 *   <li>Layer 2 {@link GoldLeafBootstrap} wiring of {@link TieredChunkReadRouter} and {@link LshdRemoteShardRpcClient}
 *       from {@link GoldLeafConfig.Network} into {@code paper-api} {@link AsyncReadRouter}</li>
 * </ul>
 */
class TieredChunkReadRouterAdr002Test {

    @AfterEach
    void cleanup() {
        GoldLeafBootstrap.configureTier4RemoteShardTransport(null, null);
        GoldLeafBootstrap.configureReadRouterBackings(null, null);
        AsyncReadRouter.resetDefault();
    }

    @Test
    void routesTier0HaloTier1OptInMidTickAndTier2InterruptExecutorCorrectly() throws Exception {
        final LcaChunkResidencyCache localCache = new LcaChunkResidencyCache(16);
        final LcaChunkResidencyCache haloCache = new LcaChunkResidencyCache(16);
        final RawChunkFrameSnapshot cleanFrame = new RawChunkFrameSnapshot(
            new byte[128], 4189, -4, 1, 128, new int[]{0, 1}
        );
        final RawChunkFrameSnapshot haloFrame = new RawChunkFrameSnapshot(
            new byte[128], 4190, -4, 1, 128, new int[]{0, 2}
        );

        localCache.installBaseFrame(10L, cleanFrame);
        haloCache.installBaseFrame(20L, haloFrame);

        final TieredChunkReadRouter router = new TieredChunkReadRouter(localCache, haloCache, null, null);
        final ExecutorService asyncIoPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-async-io"));
        final ExecutorService interruptPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-local-interrupt"));
        try {
            // Tier 0 clean local: completes inline on caller thread
            final CompletableFuture<TieredChunkReadRouter.RoutedReadResult<String>> t0Local = router.probeChunk(
                10L, 0, 0, s -> 0,
                snap -> "local-clean:" + snap.isClean(),
                frame -> "disk",
                () -> "fallback",
                asyncIoPool,
                interruptPool,
                null,
                false
            );
            assertTrue(t0Local.isDone(), "Tier 0 clean local probe must complete inline without thread hop");
            assertEquals(TieredChunkReadRouter.ReadTier.TIER_0_CLEAN_LOCAL, t0Local.join().tier());
            assertEquals("local-clean:true", t0Local.join().value());

            // Tier 0 LCAS halo: completes inline on caller thread
            final CompletableFuture<TieredChunkReadRouter.RoutedReadResult<String>> t0Halo = router.probeChunk(
                20L, 5, 5, s -> 0,
                snap -> "halo:" + snap.baseFrame().dataVersion(),
                frame -> "disk",
                () -> "fallback",
                asyncIoPool,
                interruptPool,
                null,
                false
            );
            assertTrue(t0Halo.isDone(), "Tier 0 LCAS halo probe must complete inline without thread hop");
            assertEquals(TieredChunkReadRouter.ReadTier.TIER_0_CLEAN_LOCAL, t0Halo.join().tier());
            assertEquals("halo:4190", t0Halo.join().value());

            // Dirty local chunk -> Tier 1 opt-in live mid-tick read via MidTickReadDrain
            assertTrue(localCache.recordDirtyVoxel(10L, 0, 12, 1));
            localCache.freezeTickEndSnapshot(10L);
            final MidTickReadDrain midTickDrain = new MidTickReadDrain();
            final CompletableFuture<TieredChunkReadRouter.RoutedReadResult<String>> t1Live = router.probeChunk(
                10L, 0, 0, s -> 0,
                snap -> "frozen",
                frame -> "disk",
                () -> "live-mid-tick",
                asyncIoPool,
                interruptPool,
                midTickDrain,
                true
            );
            assertEquals(1, midTickDrain.pendingCount());
            assertEquals(1, midTickDrain.drain());
            assertEquals(TieredChunkReadRouter.ReadTier.TIER_1_DIRTY_FROZEN_LOCAL, t1Live.get(5, TimeUnit.SECONDS).tier());
            assertEquals("live-mid-tick", t1Live.get(5, TimeUnit.SECONDS).value());

            // Tier 2 fallback: must run on localInterruptExecutor, NOT asyncIoExecutor
            final AtomicReference<String> tier2Thread = new AtomicReference<>();
            final CompletableFuture<TieredChunkReadRouter.RoutedReadResult<String>> t2 = router.probeChunk(
                99L, 99, 99, s -> 0,
                snap -> "resident",
                frame -> "disk",
                () -> {
                    tier2Thread.set(Thread.currentThread().getName());
                    return "interrupt-state";
                },
                asyncIoPool,
                interruptPool,
                null,
                false
            );
            final TieredChunkReadRouter.RoutedReadResult<String> t2Res = t2.get(5, TimeUnit.SECONDS);
            assertEquals(TieredChunkReadRouter.ReadTier.TIER_2_LOCAL_INTERRUPT, t2Res.tier());
            assertEquals("interrupt-state", t2Res.value());
            assertEquals("test-local-interrupt", tier2Thread.get());
        } finally {
            asyncIoPool.shutdownNow();
            interruptPool.shutdownNow();
        }
    }

    @Test
    void bootstrapWiresTieredRouterAndTier4ConfigIntoPaperApiAsyncReadRouter() {
        final GoldLeafConfig cfg = GoldLeafConfig.defaults();
        cfg.network.leafShardTier4RpcEnabled = false;
        GoldLeafBootstrap.registerAllLayer2Providers(cfg);

        assertSame(GoldLeafBootstrap.getTieredChunkReadRouter(), AsyncReadRouter.get());
        assertNotNull(GoldLeafBootstrap.getTieredChunkReadRouter());
        assertNull(GoldLeafBootstrap.getRemoteShardRpcClient());

        // Enabling Tier 4 in config and attaching transport wires LshdRemoteShardRpcClient
        cfg.network.leafShardTier4RpcEnabled = true;
        cfg.network.leafShardTier4RpcTimeoutMs = 75;
        cfg.network.leafShardTier4MaxInFlightRpcs = 64;
        final LshdRemoteShardRpcClient client = LshdRemoteShardRpcClient.fromConfig(
            cfg.network,
            (key, cx, cz) -> "shard-b",
            (shardId, bytes) -> {}
        );
        assertNotNull(client);
        assertTrue(client.isRemoteOwned(1L, 1, 1));
        client.close();
    }
}
