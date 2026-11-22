package dev.leafcraft.goldleaf.network;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 2 shared-buffer broadcast implementation for {@link BroadcastPacketEncoder}
 * ({@code goldleaf-ADR-009} §1.2, §2.2, §3.2).
 *
 * <p>Guarantees:
 * <ul>
 *   <li><strong>Mixed-interceptor safety ({@code S-004}):</strong> Any viewer flagged by
 *       {@link ConnectionInterceptorGuard} bypasses the shared {@link ByteBuf} and receives the
 *       packet via per-player fallback so rewritten payloads never leak across connections.</li>
 *   <li><strong>Zero {@link ByteBuf} leaks ({@code S-002}):</strong> The shared buffer is encoded
 *       once, each eligible viewer receives a {@code retainedSlice()}, and the owning reference is
 *       always released via {@link ReferenceCountUtil#safeRelease(Object)} in {@code finally}.</li>
 * </ul>
 * </p>
 */
public final class SharedBroadcastPacketEncoder implements BroadcastPacketEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(SharedBroadcastPacketEncoder.class);

    /**
     * Predicate returning {@code true} if a viewer connection has a per-player packet listener /
     * interceptor (e.g., ProtocolLib, PacketEvents, or custom Netty handler) that requires
     * standard per-player packet delivery instead of shared-buffer broadcast.
     */
    @FunctionalInterface
    public interface ConnectionInterceptorGuard<C> {
        boolean hasPerPlayerInterceptor(C connection);
    }

    /**
     * Consumer receiving a retained {@link ByteBuf} slice for a single viewer. The consumer owns
     * the slice's reference count and must release it after writing/flushing (or on error).
     */
    @FunctionalInterface
    public interface RetainedSliceSink<C> {
        void writeRetainedSlice(C connection, ByteBuf retainedSlice);
    }

    /**
     * Fallback sender used for intercepted viewers or when shared encoding declines/fails.
     */
    @FunctionalInterface
    public interface PerPlayerFallback<C, P> {
        void sendPerPlayer(C connection, P packet);
    }

    public record BroadcastResult(
        boolean usedSharedBuffer,
        int sharedDeliveredCount,
        int fallbackDeliveredCount
    ) {
    }

    private final ConnectionInterceptorGuard<ServerPlayerConnection> interceptorGuard;
    private final @Nullable Function<Packet<? super ClientGamePacketListener>, @Nullable ByteBuf> packetSerializer;
    private final @Nullable RetainedSliceSink<ServerPlayerConnection> sliceSink;
    private final AtomicLong sharedBroadcastCount = new AtomicLong();
    private final AtomicLong fallbackCount = new AtomicLong();

    public SharedBroadcastPacketEncoder() {
        this(SharedBroadcastPacketEncoder::defaultHasPerPlayerInterceptor, null, null);
    }

    public SharedBroadcastPacketEncoder(
        final ConnectionInterceptorGuard<ServerPlayerConnection> interceptorGuard,
        final @Nullable Function<Packet<? super ClientGamePacketListener>, @Nullable ByteBuf> packetSerializer,
        final @Nullable RetainedSliceSink<ServerPlayerConnection> sliceSink
    ) {
        this.interceptorGuard = Objects.requireNonNull(interceptorGuard, "interceptorGuard");
        this.packetSerializer = packetSerializer;
        this.sliceSink = sliceSink;
    }

    /**
     * Returns {@code true} if {@code packet} is one of the high-fan-out entity state packets
     * eligible for shared pre-encoding under {@code goldleaf-ADR-009} §1.2.
     */
    public static boolean isEligiblePacket(final @Nullable Packet<?> packet) {
        return packet instanceof ClientboundMoveEntityPacket
            || packet instanceof ClientboundSetEntityDataPacket
            || packet instanceof ClientboundRotateHeadPacket
            || packet instanceof ClientboundTeleportEntityPacket
            || packet instanceof ClientboundSetEntityMotionPacket;
    }

    /**
     * Default Netty pipeline inspection detecting common per-player packet interceptors
     * (ProtocolLib, PacketEvents, ViaVersion custom rewriters, etc.). Fail-closed: returns
     * {@code true} (bypass shared buffer) if channel state is unavailable or non-standard.
     */
    public static boolean defaultHasPerPlayerInterceptor(final ServerPlayerConnection connection) {
        if (connection == null) {
            return true;
        }
        try {
            final var player = connection.getPlayer();
            if (player == null || player.connection == null || player.connection.connection == null) {
                return true;
            }
            final var channel = player.connection.connection.channel;
            if (channel == null || !channel.isActive()) {
                return true;
            }
            for (final String handlerName : channel.pipeline().names()) {
                final String lower = handlerName.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("protocol")
                    || lower.contains("packetevents")
                    || lower.contains("pe-")
                    || lower.contains("via")
                    || lower.contains("interceptor")
                    || lower.contains("denizen")) {
                    return true;
                }
            }
            return false;
        } catch (final Throwable t) {
            LOGGER.warn("Falling back to per-player packet send after interceptor inspection error", t);
            return true;
        }
    }

    @Override
    public boolean tryBroadcast(
        final Entity entity,
        final Set<ServerPlayerConnection> seenBy,
        final Packet<? super ClientGamePacketListener> packet
    ) {
        final int threshold = GoldLeafConfig.get().network.entityTrackerSharedBroadcastThreshold;
        if (threshold <= 0 || seenBy.size() < threshold || !isEligiblePacket(packet)) {
            return false;
        }
        if (this.packetSerializer == null || this.sliceSink == null) {
            return false;
        }
        final BroadcastResult result = broadcastGeneric(
            seenBy,
            packet,
            threshold,
            p -> true,
            this.interceptorGuard,
            this.packetSerializer,
            this.sliceSink,
            ServerPlayerConnection::send
        );
        if (result.usedSharedBuffer()) {
            this.sharedBroadcastCount.incrementAndGet();
        } else {
            this.fallbackCount.incrementAndGet();
        }
        return result.usedSharedBuffer();
    }

    /**
     * Generic, NMS-independent shared-buffer broadcast engine. Directly unit-testable under Netty
     * {@code ResourceLeakDetector.Level.PARANOID} ({@code goldleaf-ADR-009} §3.2–§3.3).
     *
     * <p>Encodes {@code packet} at most once when at least {@code threshold} unintercepted viewers
     * are present. Every intercepted viewer always uses {@code fallback}. All {@link ByteBuf}
     * references are strictly balanced on normal and exceptional exit paths ({@code S-002}).</p>
     */
    public static <C, P> BroadcastResult broadcastGeneric(
        final Collection<C> viewers,
        final P packet,
        final int threshold,
        final Predicate<P> eligibilityCheck,
        final ConnectionInterceptorGuard<C> interceptorGuard,
        final Function<P, @Nullable ByteBuf> serializer,
        final RetainedSliceSink<C> sharedSink,
        final PerPlayerFallback<C, P> fallback
    ) {
        Objects.requireNonNull(viewers, "viewers");
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(eligibilityCheck, "eligibilityCheck");
        Objects.requireNonNull(interceptorGuard, "interceptorGuard");
        Objects.requireNonNull(serializer, "serializer");
        Objects.requireNonNull(sharedSink, "sharedSink");
        Objects.requireNonNull(fallback, "fallback");

        if (threshold <= 0 || viewers.size() < threshold || !eligibilityCheck.test(packet)) {
            return new BroadcastResult(false, 0, 0);
        }

        int eligibleCount = 0;
        for (final C viewer : viewers) {
            if (!interceptorGuard.hasPerPlayerInterceptor(viewer)) {
                eligibleCount++;
            }
        }
        if (eligibleCount < threshold) {
            return new BroadcastResult(false, 0, 0);
        }

        final ByteBuf sharedBuf;
        try {
            sharedBuf = serializer.apply(packet);
        } catch (final Throwable t) {
            LOGGER.warn("SharedBroadcastPacketEncoder failed to serialize packet {}; falling back", packet.getClass().getName(), t);
            return new BroadcastResult(false, 0, 0);
        }
        if (sharedBuf == null) {
            return new BroadcastResult(false, 0, 0);
        }

        int sharedDelivered = 0;
        int fallbackDelivered = 0;
        try {
            for (final C viewer : viewers) {
                if (interceptorGuard.hasPerPlayerInterceptor(viewer)) {
                    fallback.sendPerPlayer(viewer, packet);
                    fallbackDelivered++;
                } else {
                    final ByteBuf slice = sharedBuf.retainedSlice();
                    try {
                        sharedSink.writeRetainedSlice(viewer, slice);
                        sharedDelivered++;
                    } catch (final Throwable t) {
                        ReferenceCountUtil.safeRelease(slice);
                        LOGGER.warn("SharedBroadcastPacketEncoder slice sink failed; falling back for viewer", t);
                        fallback.sendPerPlayer(viewer, packet);
                        fallbackDelivered++;
                    }
                }
            }
        } finally {
            ReferenceCountUtil.safeRelease(sharedBuf);
        }
        return new BroadcastResult(true, sharedDelivered, fallbackDelivered);
    }

    /**
     * Convenience helper for allocating an immutable direct/heap buffer filled from raw bytes.
     */
    public static ByteBuf allocateBuffer(final ByteBufAllocator allocator, final byte[] payload) {
        final ByteBuf buf = allocator.buffer(payload.length);
        buf.writeBytes(payload);
        return buf;
    }

    public long sharedBroadcastCount() {
        return this.sharedBroadcastCount.get();
    }

    public long fallbackCount() {
        return this.fallbackCount.get();
    }
}
