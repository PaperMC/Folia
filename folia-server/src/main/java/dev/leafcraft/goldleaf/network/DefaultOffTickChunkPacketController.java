package dev.leafcraft.goldleaf.network;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default Layer 2 implementation of {@link OffTickChunkPacketController}
 * ({@code goldleaf-ADR-009} §1.3).
 *
 * <p>Captures an immutable {@link ChunkPacketPlan} guarded by per-section {@code sectionEpoch}
 * values on the owning region thread, constructs {@link ClientboundLevelChunkWithLightPacket}
 * (whose {@code isReady()} gate lets Paper's Async-Anti-Xray and Netty I/O threads run off-tick
 * without reordering packets), and enqueues the packet in strict FIFO order. If any section epoch
 * mutated or preparation/dispatch fails, returns {@code false} so {@code PlayerChunkSender}
 * falls back synchronously instead of ever dropping a chunk ({@code S-004}, {@code S-005}).</p>
 */
public final class DefaultOffTickChunkPacketController implements OffTickChunkPacketController {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultOffTickChunkPacketController.class);

    private final AtomicLong offTickSentCount = new AtomicLong();
    private final AtomicLong staleFallbackCount = new AtomicLong();

    @Override
    public boolean trySendOffTick(
        final ServerGamePacketListenerImpl connection,
        final ServerLevel level,
        final LevelChunk chunk,
        final boolean shouldModifyAntiXray
    ) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(chunk, "chunk");
        if (!GoldLeafConfig.get().network.antiXrayOffTickEncoding) {
            return false;
        }
        if (!connection.isAcceptingMessages()) {
            return false;
        }

        final ChunkPacketPlan plan = ChunkPacketPlan.capture(chunk, shouldModifyAntiXray);
        final ClientboundLevelChunkWithLightPacket packet;
        try {
            packet = new ClientboundLevelChunkWithLightPacket(
                chunk,
                level.getLightEngine(),
                null,
                null,
                shouldModifyAntiXray
            );
        } catch (final RuntimeException error) {
            LOGGER.warn(
                "Off-tick chunk packet preparation failed for ({}, {}); falling back to synchronous send",
                plan.chunkX(),
                plan.chunkZ(),
                error
            );
            this.staleFallbackCount.incrementAndGet();
            return false;
        }

        return this.dispatchPreparedPacket(connection, chunk, plan, packet);
    }

    boolean dispatchPreparedPacket(
        final ServerGamePacketListenerImpl connection,
        final LevelChunk chunk,
        final ChunkPacketPlan plan,
        final ClientboundLevelChunkWithLightPacket packet
    ) {
        if (!connection.isAcceptingMessages()) {
            return false;
        }
        if (plan.isStale(chunk)) {
            this.staleFallbackCount.incrementAndGet();
            return false;
        }
        try {
            connection.send(packet);
        } catch (final RuntimeException error) {
            LOGGER.warn(
                "Off-tick chunk packet send failed for ({}, {}); falling back to synchronous send",
                plan.chunkX(),
                plan.chunkZ(),
                error
            );
            this.staleFallbackCount.incrementAndGet();
            return false;
        }
        this.offTickSentCount.incrementAndGet();
        return true;
    }

    public long offTickSentCount() {
        return this.offTickSentCount.get();
    }

    public long staleFallbackCount() {
        return this.staleFallbackCount.get();
    }
}
