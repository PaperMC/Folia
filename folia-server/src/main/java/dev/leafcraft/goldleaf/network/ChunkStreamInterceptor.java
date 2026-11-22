package dev.leafcraft.goldleaf.network;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 negotiated chunk-stream interceptor SPI for {@code PlayerChunkSender}
 * ({@code goldleaf-ADR-005} Stage 3).
 *
 * <p>Allows a registered network engine (e.g., {@code LCA_NET_V1} / {@code LeafShard}) to emit
 * custom wire frames for negotiated clients while preserving vanilla
 * {@code ClientboundChunkBatchStartPacket}/{@code ClientboundChunkBatchFinishedPacket} pacing,
 * {@code PlayerChunkLoadEvent}, {@code debugSynchronizers}, and stock vanilla fallback for
 * unmodified clients.</p>
 */
public interface ChunkStreamInterceptor {

    /**
     * Returns {@code true} if {@code connection} has negotiated a custom chunk wire protocol.
     * Returning {@code false} guarantees zero extra work on vanilla connections.
     */
    boolean supportsConnection(ServerGamePacketListenerImpl connection);

    /**
     * Attempts to send {@code chunk} over {@code connection} using the negotiated wire format.
     *
     * @param shouldModifyAntiXray result of Paper's {@code chunkPacketBlockController.shouldModify}
     * @return {@code true} if the interceptor sent the chunk payload (skipping the default
     *         {@code ClientboundLevelChunkWithLightPacket}), or {@code false} to fall back to
     *         vanilla packet transmission
     */
    boolean trySendChunk(
        ServerGamePacketListenerImpl connection,
        ServerLevel level,
        LevelChunk chunk,
        boolean shouldModifyAntiXray
    );

    static @Nullable ChunkStreamInterceptor get() {
        return Holder.current;
    }

    static void register(final @Nullable ChunkStreamInterceptor interceptor) {
        Holder.current = interceptor;
    }

    static void resetDefault() {
        Holder.current = null;
    }

    final class Holder {
        private static volatile @Nullable ChunkStreamInterceptor current;

        private Holder() {
        }
    }
}
