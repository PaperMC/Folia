package dev.leafcraft.goldleaf.protocol;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1 registry and fail-closed dispatcher for opt-in technical client protocols
 * ({@code goldleaf-ADR-010}).
 *
 * <p>Guarantees:
 * <ul>
 *   <li><strong>Default-off zero overhead:</strong> When no handlers are registered (the default,
 *       as all {@code protocols.*-enabled} flags default to {@code false}), {@link #isEmpty()} is a
 *       single {@code volatile} boolean read with zero allocations.</li>
 *   <li><strong>Fail-closed byte caps &amp; rate limits ({@code S-004}):</strong> Oversized,
 *       rate-limited, unauthorized, or throwing payloads are isolated with a {@code WARNING} log
 *       and structured {@link DispatchStatus} rather than crashing the region tick thread.</li>
 *   <li><strong>Clean player disconnect cleanup ({@code S-002}):</strong> {@link #onPlayerQuit(UUID)}
 *       evicts per-player rate-limit buckets and notifies all registered handlers.</li>
 * </ul>
 * </p>
 */
public final class TechnicalProtocolRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(TechnicalProtocolRegistry.class);
    private static final TechnicalProtocolRegistry GLOBAL = new TechnicalProtocolRegistry();

    public enum DispatchStatus {
        NO_HANDLER,
        HANDLED,
        REJECTED_PERMISSION,
        REJECTED_OVERSIZED,
        REJECTED_RATE_LIMITED,
        REJECTED_MALFORMED,
        HANDLER_ERROR
    }

    private static final class TokenBucket {
        private long windowSecond;
        private int count;

        synchronized boolean tryAcquire(final long currentSecond, final int maxPerSecond) {
            if (this.windowSecond != currentSecond) {
                this.windowSecond = currentSecond;
                this.count = 0;
            }
            if (this.count >= maxPerSecond) {
                return false;
            }
            this.count++;
            return true;
        }
    }

    private final ConcurrentHashMap<String, ClientProtocolHandler> channelHandlers = new ConcurrentHashMap<>();
    private final Set<ClientProtocolHandler> registeredHandlers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, TokenBucket> rateLimiters = new ConcurrentHashMap<>();
    private final AtomicLong handledCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private volatile boolean empty = true;

    public static TechnicalProtocolRegistry global() {
        return GLOBAL;
    }

    public boolean isEmpty() {
        return this.empty;
    }

    public void register(final ClientProtocolHandler handler) {
        Objects.requireNonNull(handler, "handler");
        final Set<String> channels = handler.channelIds();
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("ClientProtocolHandler must declare at least one channelId");
        }
        for (final String channel : channels) {
            if (channel == null || channel.isBlank()) {
                throw new IllegalArgumentException("Channel identifier must not be blank");
            }
            final ClientProtocolHandler existing = this.channelHandlers.putIfAbsent(channel, handler);
            if (existing != null && existing != handler) {
                throw new IllegalStateException(
                    "Protocol channel '" + channel + "' is already registered by " + existing.namespace()
                );
            }
        }
        this.registeredHandlers.add(handler);
        this.empty = this.channelHandlers.isEmpty();
    }

    public void unregister(final ClientProtocolHandler handler) {
        if (handler == null) {
            return;
        }
        for (final String channel : handler.channelIds()) {
            this.channelHandlers.remove(channel, handler);
        }
        this.registeredHandlers.remove(handler);
        this.empty = this.channelHandlers.isEmpty();
    }

    public void clear() {
        this.channelHandlers.clear();
        this.registeredHandlers.clear();
        this.rateLimiters.clear();
        this.empty = true;
    }

    public Map<String, ClientProtocolHandler> registeredChannelsView() {
        return Collections.unmodifiableMap(this.channelHandlers);
    }

    public DispatchStatus dispatchIncoming(
        final UUID playerId,
        final String channelId,
        final byte @Nullable [] payload,
        final @Nullable BiPredicate<UUID, String> permissionChecker
    ) {
        return this.dispatchIncoming(playerId, channelId, payload, permissionChecker, System.nanoTime() / 1_000_000_000L);
    }

    public DispatchStatus dispatchIncoming(
        final UUID playerId,
        final String channelId,
        final byte @Nullable [] payload,
        final @Nullable BiPredicate<UUID, String> permissionChecker,
        final long currentSecond
    ) {
        if (this.empty) {
            return DispatchStatus.NO_HANDLER;
        }
        final ClientProtocolHandler handler = this.channelHandlers.get(channelId);
        if (handler == null) {
            return DispatchStatus.NO_HANDLER;
        }
        Objects.requireNonNull(playerId, "playerId");
        final GoldLeafConfig.Protocols cfg = GoldLeafConfig.get().protocols;

        final String permission = handler.requiredPermission();
        if (permission != null && permissionChecker != null && !permissionChecker.test(playerId, permission)) {
            this.rejectedCount.incrementAndGet();
            return DispatchStatus.REJECTED_PERMISSION;
        }

        final int effectiveMaxBytes = handler.maxPayloadBytes() > 0
            ? Math.min(handler.maxPayloadBytes(), cfg.maxPayloadBytes)
            : cfg.maxPayloadBytes;
        if (payload == null || payload.length > effectiveMaxBytes) {
            this.rejectedCount.incrementAndGet();
            LOGGER.warn(
                "Rejected oversized or null protocol payload on channel '{}' from player {} ({} bytes > cap {})",
                channelId,
                playerId,
                payload == null ? -1 : payload.length,
                effectiveMaxBytes
            );
            return DispatchStatus.REJECTED_OVERSIZED;
        }

        final TokenBucket bucket = this.rateLimiters.computeIfAbsent(playerId, ignored -> new TokenBucket());
        if (!bucket.tryAcquire(currentSecond, cfg.maxPacketsPerSecond)) {
            this.rejectedCount.incrementAndGet();
            LOGGER.warn(
                "Rate-limited protocol payload on channel '{}' from player {} (cap {}/s)",
                channelId,
                playerId,
                cfg.maxPacketsPerSecond
            );
            return DispatchStatus.REJECTED_RATE_LIMITED;
        }

        try {
            final boolean accepted = handler.onCustomPayload(playerId, channelId, payload);
            if (!accepted) {
                this.rejectedCount.incrementAndGet();
                LOGGER.warn("Rejected malformed protocol payload on channel '{}' from player {}", channelId, playerId);
                return DispatchStatus.REJECTED_MALFORMED;
            }
            this.handledCount.incrementAndGet();
            return DispatchStatus.HANDLED;
        } catch (final Throwable t) {
            this.rejectedCount.incrementAndGet();
            LOGGER.warn("Protocol handler '{}' failed on channel '{}' for player {}", handler.namespace(), channelId, playerId, t);
            return DispatchStatus.HANDLER_ERROR;
        }
    }

    public void onChannelRegister(final UUID playerId, final String channelId) {
        if (this.empty) {
            return;
        }
        final ClientProtocolHandler handler = this.channelHandlers.get(channelId);
        if (handler == null) {
            return;
        }
        try {
            handler.onChannelRegister(playerId, channelId);
        } catch (final Throwable t) {
            LOGGER.warn("Protocol handler '{}' failed during onChannelRegister for {}", handler.namespace(), playerId, t);
        }
    }

    public void onPlayerJoin(final UUID playerId) {
        if (this.empty) {
            return;
        }
        for (final ClientProtocolHandler handler : this.registeredHandlers) {
            try {
                handler.onPlayerJoin(playerId);
            } catch (final Throwable t) {
                LOGGER.warn("Protocol handler '{}' failed during onPlayerJoin for {}", handler.namespace(), playerId, t);
            }
        }
    }

    public void onPlayerTick(final UUID playerId) {
        if (this.empty) {
            return;
        }
        for (final ClientProtocolHandler handler : this.registeredHandlers) {
            try {
                handler.onPlayerTick(playerId);
            } catch (final Throwable t) {
                LOGGER.warn("Protocol handler '{}' failed during onPlayerTick for {}", handler.namespace(), playerId, t);
            }
        }
    }

    public void onPlayerQuit(final UUID playerId) {
        this.rateLimiters.remove(playerId);
        if (this.empty) {
            return;
        }
        for (final ClientProtocolHandler handler : this.registeredHandlers) {
            try {
                handler.onPlayerQuit(playerId);
            } catch (final Throwable t) {
                LOGGER.warn("Protocol handler '{}' failed during onPlayerQuit for {}", handler.namespace(), playerId, t);
            }
        }
    }

    public long handledCount() {
        return this.handledCount.get();
    }

    public long rejectedCount() {
        return this.rejectedCount.get();
    }

    public int trackedRateLimiters() {
        return this.rateLimiters.size();
    }
}
