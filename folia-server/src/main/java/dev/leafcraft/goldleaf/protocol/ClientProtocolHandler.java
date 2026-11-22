package dev.leafcraft.goldleaf.protocol;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 SPI for region-safe technical client utility protocols ({@code goldleaf-ADR-010}).
 *
 * <p>All callbacks execute on the player's owning Folia region thread ({@code F-001}..{@code F-003}).
 * Handlers must never perform synchronous cross-region chunk loads or blocking disk I/O
 * ({@code S-005}); cross-region reads must delegate to {@code AsyncReadRouter} / immutable
 * snapshots, and schematic storage must use async workers.</p>
 */
public interface ClientProtocolHandler {

    /**
     * Protocol namespace (e.g., {@code "servux"}, {@code "syncmatica"}, {@code "jade"},
     * {@code "appleskin"}, {@code "xaerominimap"}).
     */
    String namespace();

    /**
     * Full channel identifiers handled by this protocol (e.g., {@code "servux:structures"}).
     */
    Set<String> channelIds();

    /**
     * Optional permission node required before dispatching payloads to this handler, or
     * {@code null} if no permission gate is required beyond the opt-in config toggle.
     */
    default @Nullable String requiredPermission() {
        return null;
    }

    /**
     * Per-channel maximum payload size in bytes. Returns {@code <= 0} to defer to
     * {@code goldleaf.yml protocols.max-payload-bytes}.
     */
    default int maxPayloadBytes() {
        return 0;
    }

    /**
     * Invoked when a player joins on their owning region thread.
     */
    default void onPlayerJoin(final UUID playerId) {
    }

    /**
     * Invoked when the player registers one of this handler's channels via
     * {@code minecraft:register}.
     */
    default void onChannelRegister(final UUID playerId, final String channelId) {
    }

    /**
     * Handles a validated, rate-limited custom payload on the player's owning region thread.
     *
     * @return {@code true} if the payload was consumed and valid, or {@code false} if malformed
     */
    boolean onCustomPayload(UUID playerId, String channelId, byte[] payload);

    /**
     * Optional per-player tick hook (e.g., dirty-checked AppleSkin saturation/exhaustion delta).
     */
    default void onPlayerTick(final UUID playerId) {
    }

    /**
     * Invoked when a player disconnects so per-player state is cleaned up ({@code S-002}).
     */
    default void onPlayerQuit(final UUID playerId) {
    }
}
