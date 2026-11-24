package io.papermc.paper.spi;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate networking, protocol, and decode-time security SPI.
 *
 * <p>Zero product or config coupling: returns vanilla defaults when no Layer 2 provider is
 * registered.</p>
 */
public final class PaperNetworkSpi {

    public interface Provider {
        default @Nullable NbtAccounter createServerboundNbtAccounter() {
            return null;
        }

        default boolean isEntityTrackerAllocationFreePurgeEnabled() {
            return false;
        }

        default boolean tryBroadcast(
            final Entity entity,
            final Set<ServerPlayerConnection> seenBy,
            final Packet<? super ClientGamePacketListener> packet
        ) {
            return false;
        }

        default boolean trySendChunk(
            final ServerGamePacketListenerImpl connection,
            final ServerLevel level,
            final LevelChunk chunk,
            final boolean shouldModify
        ) {
            return false;
        }

        default void dispatchTechnicalProtocol(
            final UUID playerId,
            final String channel,
            final byte[] data,
            final BiPredicate<UUID, String> permissionCheck
        ) {
        }

        default boolean tryHandleCustomPayload(
            final ServerGamePacketListenerImpl connection,
            final String channel,
            final byte[] data
        ) {
            return false;
        }

        default void onPlayerJoin(final UUID playerId) {
        }

        default void onChannelRegister(final UUID playerId, final Set<String> channels) {
        }

        default void onPlayerTick(final UUID playerId, final long tickCount) {
        }

        default void onPlayerQuit(final UUID playerId) {
        }

        /**
         * Validates a serverbound book-edit payload.
         *
         * @return {@code 0} if accepted, {@code 1} if rejected (drop), {@code 2} if rejected (disconnect)
         */
        default int validateBookEdit(final UUID playerId, final List<String> pages) {
            return 0;
        }

        /**
         * Enforces per-connection packet-burst rate limiting on untrusted serverbound packets.
         *
         * @return {@code 0} if accepted, {@code 1} if rejected (drop), {@code 2} if rejected (disconnect)
         */
        default int validatePacketBurst(final UUID playerId, final String packetType) {
            return 0;
        }

        default boolean isChatSigningDisabled() {
            return false;
        }
    }

    private static volatile @Nullable Provider provider;

    private PaperNetworkSpi() {
    }

    public static void register(final @Nullable Provider p) {
        provider = p;
    }

    public static @Nullable Provider get() {
        return provider;
    }

    public static void resetDefault() {
        provider = null;
    }

    public static @Nullable NbtAccounter createServerboundNbtAccounter() {
        final Provider p = provider;
        return p != null ? p.createServerboundNbtAccounter() : null;
    }

    public static boolean isEntityTrackerAllocationFreePurgeEnabled() {
        final Provider p = provider;
        return p != null && p.isEntityTrackerAllocationFreePurgeEnabled();
    }

    public static boolean tryBroadcast(
        final Entity entity,
        final Set<ServerPlayerConnection> seenBy,
        final Packet<? super ClientGamePacketListener> packet
    ) {
        final Provider p = provider;
        return p != null && p.tryBroadcast(entity, seenBy, packet);
    }

    public static boolean trySendChunk(
        final ServerGamePacketListenerImpl connection,
        final ServerLevel level,
        final LevelChunk chunk,
        final boolean shouldModify
    ) {
        final Provider p = provider;
        return p != null && p.trySendChunk(connection, level, chunk, shouldModify);
    }

    public static void dispatchTechnicalProtocol(
        final UUID playerId,
        final String channel,
        final byte[] data,
        final BiPredicate<UUID, String> permissionCheck
    ) {
        final Provider p = provider;
        if (p != null) {
            p.dispatchTechnicalProtocol(playerId, channel, data, permissionCheck);
        }
    }

    public static boolean tryHandleCustomPayload(
        final ServerGamePacketListenerImpl connection,
        final String channel,
        final byte[] data
    ) {
        final Provider p = provider;
        return p != null && p.tryHandleCustomPayload(connection, channel, data);
    }

    public static void onPlayerJoin(final UUID playerId) {
        final Provider p = provider;
        if (p != null) {
            p.onPlayerJoin(playerId);
        }
    }

    public static void onChannelRegister(final UUID playerId, final Set<String> channels) {
        final Provider p = provider;
        if (p != null) {
            p.onChannelRegister(playerId, channels);
        }
    }

    public static void onPlayerTick(final UUID playerId, final long tickCount) {
        final Provider p = provider;
        if (p != null) {
            p.onPlayerTick(playerId, tickCount);
        }
    }

    public static void onPlayerQuit(final UUID playerId) {
        final Provider p = provider;
        if (p != null) {
            p.onPlayerQuit(playerId);
        }
    }

    public static int validateBookEdit(final UUID playerId, final List<String> pages) {
        final Provider p = provider;
        return p != null ? p.validateBookEdit(playerId, pages) : 0;
    }

    public static int validatePacketBurst(final UUID playerId, final String packetType) {
        final Provider p = provider;
        return p != null ? p.validatePacketBurst(playerId, packetType) : 0;
    }

    public static boolean isChatSigningDisabled() {
        final Provider p = provider;
        return p != null && p.isChatSigningDisabled();
    }
}
