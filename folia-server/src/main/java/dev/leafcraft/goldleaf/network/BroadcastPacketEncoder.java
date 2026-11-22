package dev.leafcraft.goldleaf.network;

import java.util.Set;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 pre-encoded entity broadcast SPI hooked into
 * {@code ChunkMap.TrackedEntity#sendToTrackingPlayers} ({@code goldleaf-ADR-009} §1.2).
 *
 * <p>Allows high-fan-out entity packets to be serialized once into an immutable reference-counted
 * buffer for unintercepted viewers while falling back cleanly to {@code connection.send(packet)}
 * whenever a per-player packet listener/interceptor is attached ({@code S-004}).</p>
 */
public interface BroadcastPacketEncoder {

    /**
     * Attempts to broadcast {@code packet} for {@code entity} to all {@code seenBy} viewers.
     *
     * @return {@code true} if the encoder handled delivery for all viewers in {@code seenBy}, or
     *         {@code false} to fall back to the stock per-viewer {@code connection.send(packet)} loop
     */
    boolean tryBroadcast(
        Entity entity,
        Set<ServerPlayerConnection> seenBy,
        Packet<? super ClientGamePacketListener> packet
    );

    static @Nullable BroadcastPacketEncoder get() {
        return Holder.current;
    }

    static void register(final @Nullable BroadcastPacketEncoder encoder) {
        Holder.current = encoder;
    }

    static void resetDefault() {
        Holder.current = null;
    }

    final class Holder {
        private static volatile @Nullable BroadcastPacketEncoder current;

        private Holder() {
        }
    }
}
