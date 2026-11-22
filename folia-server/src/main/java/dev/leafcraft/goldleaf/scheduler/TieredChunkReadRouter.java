package dev.leafcraft.goldleaf.scheduler;

import dev.leafcraft.goldleaf.storage.LcaChunkResidencyCache;
import dev.leafcraft.goldleaf.storage.RegionStorageProvider;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import io.papermc.paper.threadedregions.scheduler.AsyncReadRouter;
import io.papermc.paper.threadedregions.spi.MidTickReadDrain;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Latency-tiered read/dispatch router implementing {@link AsyncReadRouter}
 * ({@code goldleaf-ADR-002} §2, {@code goldleaf-ADR-005} Stage 2).
 *
 * <p>Routes read-only chunk probes through the lowest-latency physically valid tier:</p>
 * <ul>
 *   <li><b>Tier 0</b>: Clean resident {@link RawChunkFrameSnapshot} or local {@code LCAS} halo
 *       snapshot (inline, zero copy, zero lock, 64-byte cache-line isolated).</li>
 *   <li><b>Tier 1</b>: Dirty resident tick-end {@link LcaChunkResidencyCache.FrozenReadSnapshot}
 *       (inline, zero contention with the owning region thread's live dirty map), or opt-in
 *       budgeted live mid-tick read via {@link MidTickReadDrain}.</li>
 *   <li><b>Tier 2</b>: Local non-blocking interrupt path ({@code localInterruptExecutor} backed
 *       by region/entity/global immediate schedulers) for in-memory region-owned state/hooks;
 *       never executes blocking disk or chunk-load I/O ({@code S-005}).</li>
 *   <li><b>Tier 3</b>: Unloaded cold disk {@link RegionStorageProvider#readRawChunkFrame} executed
 *       strictly on {@code asyncIoExecutor} with zero regionizer activation ({@code S-005}).</li>
 *   <li><b>Tier 4</b>: Remote {@code LeafShard} node non-blocking async RPC via
 *       {@link RemoteShardChunkRpcClient} ({@code S-005}).</li>
 * </ul>
 */
public final class TieredChunkReadRouter implements AsyncReadRouter {

    public enum ReadTier {
        TIER_0_CLEAN_LOCAL,
        TIER_1_DIRTY_FROZEN_LOCAL,
        TIER_2_LOCAL_INTERRUPT,
        TIER_3_COLD_DISK,
        TIER_4_REMOTE_SHARD
    }

    public record RoutedReadResult<T>(ReadTier tier, T value) {
        public RoutedReadResult {
            Objects.requireNonNull(tier, "tier");
        }
    }

    private final LcaChunkResidencyCache residencyCache;
    private final @Nullable LcaChunkResidencyCache haloCache;
    private final @Nullable RegionStorageProvider coldStorageProvider;
    private final @Nullable RemoteShardChunkRpcClient remoteShardRpcClient;

    public TieredChunkReadRouter(
        final LcaChunkResidencyCache residencyCache,
        final @Nullable RegionStorageProvider coldStorageProvider
    ) {
        this(residencyCache, null, coldStorageProvider, null);
    }

    public TieredChunkReadRouter(
        final LcaChunkResidencyCache residencyCache,
        final @Nullable RegionStorageProvider coldStorageProvider,
        final @Nullable RemoteShardChunkRpcClient remoteShardRpcClient
    ) {
        this(residencyCache, null, coldStorageProvider, remoteShardRpcClient);
    }

    public TieredChunkReadRouter(
        final LcaChunkResidencyCache residencyCache,
        final @Nullable LcaChunkResidencyCache haloCache,
        final @Nullable RegionStorageProvider coldStorageProvider,
        final @Nullable RemoteShardChunkRpcClient remoteShardRpcClient
    ) {
        this.residencyCache = Objects.requireNonNull(residencyCache, "residencyCache");
        this.haloCache = haloCache;
        this.coldStorageProvider = coldStorageProvider;
        this.remoteShardRpcClient = remoteShardRpcClient;
    }

    /**
     * Probes a chunk through Tier 0 (local clean / halo) -> Tier 1 (local dirty frozen) ->
     * Tier 4 (remote shard RPC) -> Tier 3 (off-thread disk) -> Tier 2 fallback.
     */
    public <T> CompletableFuture<RoutedReadResult<T>> probeChunk(
        final long chunkKey,
        final int chunkX,
        final int chunkZ,
        final Function<String, Integer> runtimeStateLookup,
        final Function<LcaChunkResidencyCache.FrozenReadSnapshot, T> residentReader,
        final Function<RawChunkFrameSnapshot, T> coldDiskReader,
        final Supplier<T> fallbackSupplier,
        final Executor asyncIoExecutor
    ) {
        return this.probeChunk(
            chunkKey,
            chunkX,
            chunkZ,
            runtimeStateLookup,
            residentReader,
            coldDiskReader,
            fallbackSupplier,
            asyncIoExecutor,
            null,
            null,
            false
        );
    }

    /**
     * Full latency-tiered probe supporting a dedicated non-blocking Tier 2
     * {@code localInterruptExecutor} (distinct from {@code asyncIoExecutor} per {@code S-005})
     * and an opt-in live mid-tick read via {@code midTickReadDrain} when a local chunk is dirty.
     */
    public <T> CompletableFuture<RoutedReadResult<T>> probeChunk(
        final long chunkKey,
        final int chunkX,
        final int chunkZ,
        final Function<String, Integer> runtimeStateLookup,
        final Function<LcaChunkResidencyCache.FrozenReadSnapshot, T> residentReader,
        final Function<RawChunkFrameSnapshot, T> coldDiskReader,
        final Supplier<T> fallbackSupplier,
        final Executor asyncIoExecutor,
        final @Nullable Executor localInterruptExecutor,
        final @Nullable MidTickReadDrain midTickReadDrain,
        final boolean requestLiveMidTickRead
    ) {
        Objects.requireNonNull(runtimeStateLookup, "runtimeStateLookup");
        Objects.requireNonNull(residentReader, "residentReader");
        Objects.requireNonNull(coldDiskReader, "coldDiskReader");
        Objects.requireNonNull(fallbackSupplier, "fallbackSupplier");
        Objects.requireNonNull(asyncIoExecutor, "asyncIoExecutor");

        // Tier 0 / Tier 1: lock-free, cache-line-isolated resident immutable snapshot lookup
        final LcaChunkResidencyCache.FrozenReadSnapshot snapshot = this.residencyCache.getReadSnapshot(chunkKey);
        if (snapshot != null) {
            if (!snapshot.isClean() && requestLiveMidTickRead && midTickReadDrain != null) {
                return midTickReadDrain
                    .submitRead(chunkX, chunkZ, fallbackSupplier)
                    .thenApply(val -> new RoutedReadResult<>(ReadTier.TIER_1_DIRTY_FROZEN_LOCAL, val));
            }
            final ReadTier tier = snapshot.isClean()
                ? ReadTier.TIER_0_CLEAN_LOCAL
                : ReadTier.TIER_1_DIRTY_FROZEN_LOCAL;
            try {
                return CompletableFuture.completedFuture(new RoutedReadResult<>(tier, residentReader.apply(snapshot)));
            } catch (final Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        }

        // Tier 0 (LCAS Halo): local immutable halo shortcut before remote Tier 4 RPC
        if (this.haloCache != null) {
            final LcaChunkResidencyCache.FrozenReadSnapshot haloSnapshot = this.haloCache.getReadSnapshot(chunkKey);
            if (haloSnapshot != null) {
                try {
                    return CompletableFuture.completedFuture(
                        new RoutedReadResult<>(ReadTier.TIER_0_CLEAN_LOCAL, residentReader.apply(haloSnapshot))
                    );
                } catch (final Throwable t) {
                    return CompletableFuture.failedFuture(t);
                }
            }
        }

        // Tier 4: remote LeafShard non-blocking async RPC (goldleaf-ADR-002, S-005)
        if (this.remoteShardRpcClient != null && this.remoteShardRpcClient.isRemoteOwned(chunkKey, chunkX, chunkZ)) {
            return this.remoteShardRpcClient
                .fetchRemoteChunkFrame(chunkKey, chunkX, chunkZ, runtimeStateLookup)
                .handle((remoteFrame, error) -> {
                    if (error != null) {
                        if (isCancellation(error)) {
                            // Deterministic fallback on shard client close/disconnect (DSN-2)
                            return probeColdDiskOrFallback(
                                chunkX,
                                chunkZ,
                                runtimeStateLookup,
                                coldDiskReader,
                                fallbackSupplier,
                                asyncIoExecutor,
                                localInterruptExecutor
                            );
                        }
                        return CompletableFuture.<RoutedReadResult<T>>failedFuture(unwrapCompletionException(error));
                    }
                    if (remoteFrame != null) {
                        return CompletableFuture.supplyAsync(
                            () -> new RoutedReadResult<>(ReadTier.TIER_4_REMOTE_SHARD, coldDiskReader.apply(remoteFrame)),
                            asyncIoExecutor
                        );
                    }
                    // Remote shard returned NOT_FOUND (e.g. ownership migrated) -> fall through to Tier 3/2
                    return probeColdDiskOrFallback(
                        chunkX,
                        chunkZ,
                        runtimeStateLookup,
                        coldDiskReader,
                        fallbackSupplier,
                        asyncIoExecutor,
                        localInterruptExecutor
                    );
                })
                .thenCompose(Function.identity());
        }

        return probeColdDiskOrFallback(
            chunkX,
            chunkZ,
            runtimeStateLookup,
            coldDiskReader,
            fallbackSupplier,
            asyncIoExecutor,
            localInterruptExecutor
        );
    }

    private <T> CompletableFuture<RoutedReadResult<T>> probeColdDiskOrFallback(
        final int chunkX,
        final int chunkZ,
        final Function<String, Integer> runtimeStateLookup,
        final Function<RawChunkFrameSnapshot, T> coldDiskReader,
        final Supplier<T> fallbackSupplier,
        final Executor asyncIoExecutor,
        final @Nullable Executor localInterruptExecutor
    ) {
        final Executor tier2Executor = localInterruptExecutor != null ? localInterruptExecutor : asyncIoExecutor;

        // Tier 3: off-thread cold .lca disk shortcut (zero regionizer activation, S-005)
        if (this.coldStorageProvider != null) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return this.coldStorageProvider.readRawChunkFrame(
                        chunkX,
                        chunkZ,
                        runtimeStateLookup
                    );
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, asyncIoExecutor).thenCompose(coldFrame -> {
                if (coldFrame != null) {
                    try {
                        return CompletableFuture.completedFuture(
                            new RoutedReadResult<>(ReadTier.TIER_3_COLD_DISK, coldDiskReader.apply(coldFrame))
                        );
                    } catch (final Throwable t) {
                        return CompletableFuture.failedFuture(t);
                    }
                }
                return CompletableFuture.supplyAsync(
                    () -> new RoutedReadResult<>(ReadTier.TIER_2_LOCAL_INTERRUPT, fallbackSupplier.get()),
                    tier2Executor
                );
            });
        }

        // Tier 2: non-blocking local interrupt executor fallback (never runs blocking disk I/O, S-005)
        return CompletableFuture.supplyAsync(
            () -> new RoutedReadResult<>(ReadTier.TIER_2_LOCAL_INTERRUPT, fallbackSupplier.get()),
            tier2Executor
        );
    }

    private static boolean isCancellation(final Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof CancellationException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static Throwable unwrapCompletionException(final Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    @Override
    public <T> CompletableFuture<T> submitAsyncRead(
        final Supplier<T> asyncReader,
        final Executor asyncExecutor
    ) {
        return DefaultAsyncReadRouter.INSTANCE.submitAsyncRead(asyncReader, asyncExecutor);
    }

    @Override
    public <T> CompletableFuture<T> readAndDispatch(
        final Supplier<T> asyncReader,
        final Executor asyncExecutor,
        final Consumer<T> regionConsumer,
        final Executor targetRegionExecutor
    ) {
        return DefaultAsyncReadRouter.INSTANCE.readAndDispatch(
            asyncReader,
            asyncExecutor,
            regionConsumer,
            targetRegionExecutor
        );
    }
}
