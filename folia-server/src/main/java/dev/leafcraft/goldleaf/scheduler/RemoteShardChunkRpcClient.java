package dev.leafcraft.goldleaf.scheduler;

import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 non-blocking Tier 4 remote shard chunk-read RPC SPI
 * ({@code goldleaf-ADR-002} §2, {@code leafshard-ADR-004}).
 *
 * <p>Allows {@link TieredChunkReadRouter} to route read-only probes for chunks owned by a remote
 * {@code LeafShard} node over the {@code LSHD} / Netty control plane without ever blocking a
 * Folia tick thread ({@code S-005}). Implementations must fail closed on transport, checksum, or
 * timeout faults ({@code S-004}) and cancel all pending RPC futures deterministically on
 * {@link #close()} ({@code S-002}).</p>
 */
public interface RemoteShardChunkRpcClient extends Closeable {

    /**
     * Fast lock-free ownership check returning {@code true} if the given chunk is currently
     * owned by a remote {@code LeafShard} node outside this node's local halo.
     */
    boolean isRemoteOwned(long chunkKey, int chunkX, int chunkZ);

    /**
     * Dispatches a strictly non-blocking asynchronous Tier 4 chunk-frame read RPC to the owning
     * remote shard ({@code S-005}).
     *
     * @return a future completing with the remote {@link RawChunkFrameSnapshot}, {@code null} if
     *         the remote shard no longer owns or holds the chunk, or completing exceptionally on
     *         timeout, corruption, transport failure, or client shutdown ({@code S-004})
     */
    CompletableFuture<@Nullable RawChunkFrameSnapshot> fetchRemoteChunkFrame(
        long chunkKey,
        int chunkX,
        int chunkZ,
        Function<String, Integer> runtimeStateLookup
    );

    /**
     * Closes the RPC client and deterministically cancels all in-flight RPC futures ({@code S-002}).
     */
    @Override
    void close();
}
