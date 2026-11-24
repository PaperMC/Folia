package dev.leafcraft.goldleaf.protocol;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 config-gated built-in {@link ClientProtocolHandler} implementations for
 * {@code Servux}, {@code Syncmatica}, {@code Jade}, {@code AppleSkin}, and {@code Map World-ID}
 * ({@code goldleaf-ADR-001} Stage 5, {@code goldleaf-ADR-010}).
 *
 * <p>All handlers default to disabled when {@code goldleaf.yml protocols.*-enabled} is {@code false}.
 * Every handler validates payloads fail-closed via {@link TechnicalProtocolCodecs} ({@code S-004})
 * and evicts per-player state on {@link #onPlayerQuit(UUID)} ({@code S-002}).</p>
 */
public final class BuiltInTechnicalProtocolHandlers {

    @FunctionalInterface
    public interface PayloadSender {
        boolean send(UUID playerId, String channelId, byte[] payload);
    }

    public record PlayerProtocolState(@Nullable UUID worldUuid, float saturation, float exhaustion) {
    }

    @FunctionalInterface
    public interface PlayerStateProvider {
        @Nullable PlayerProtocolState lookup(UUID playerId);
    }

    @FunctionalInterface
    public interface ServuxQueryResponder {
        byte @Nullable [] respond(UUID playerId, TechnicalProtocolCodecs.ServuxStructureQuery query);
    }

    @FunctionalInterface
    public interface SyncmaticaHeaderResponder {
        byte @Nullable [] respond(UUID playerId, TechnicalProtocolCodecs.SyncmaticaPlacementHeader header);
    }

    @FunctionalInterface
    public interface JadeInspectResponder {
        byte @Nullable [] respond(UUID playerId, TechnicalProtocolCodecs.JadeInspectRequest request);
    }

    private static volatile @Nullable PayloadSender payloadSender;
    private static volatile @Nullable PlayerStateProvider playerStateProvider;
    private static volatile @Nullable ServuxQueryResponder servuxResponder;
    private static volatile @Nullable SyncmaticaHeaderResponder syncmaticaResponder;
    private static volatile @Nullable JadeInspectResponder jadeResponder;

    private static final ServuxHandler SERVUX = new ServuxHandler();
    private static final SyncmaticaHandler SYNCMATICA = new SyncmaticaHandler();
    private static final JadeHandler JADE = new JadeHandler();
    private static final AppleSkinHandler APPLESKIN = new AppleSkinHandler();
    private static final MapWorldIdHandler MAP_WORLD_ID = new MapWorldIdHandler();

    private BuiltInTechnicalProtocolHandlers() {
    }

    public static void setPayloadSender(final @Nullable PayloadSender sender) {
        payloadSender = sender;
    }

    public static void setPlayerStateProvider(final @Nullable PlayerStateProvider provider) {
        playerStateProvider = provider;
    }

    public static void setServuxResponder(final @Nullable ServuxQueryResponder responder) {
        servuxResponder = responder;
    }

    public static void setSyncmaticaResponder(final @Nullable SyncmaticaHeaderResponder responder) {
        syncmaticaResponder = responder;
    }

    public static void setJadeResponder(final @Nullable JadeInspectResponder responder) {
        jadeResponder = responder;
    }

    /**
     * Synchronizes built-in protocol handler registrations in {@code registry} against the active
     * {@link GoldLeafConfig.Protocols} toggles (safe on initial boot and config reload).
     */
    public static void syncFromConfig(
        final TechnicalProtocolRegistry registry,
        final GoldLeafConfig.@Nullable Protocols config
    ) {
        Objects.requireNonNull(registry, "registry");
        syncSingle(registry, SERVUX, config != null && config.servuxEnabled);
        syncSingle(registry, SYNCMATICA, config != null && config.syncmaticaEnabled);
        syncSingle(registry, JADE, config != null && config.jadeEnabled);
        syncSingle(registry, APPLESKIN, config != null && config.appleskinEnabled);
        syncSingle(registry, MAP_WORLD_ID, config != null && config.mapWorldIdEnabled);
    }

    private static void syncSingle(
        final TechnicalProtocolRegistry registry,
        final ClientProtocolHandler handler,
        final boolean enabled
    ) {
        if (enabled) {
            if (!registry.registeredChannelsView().containsValue(handler)) {
                registry.register(handler);
            }
        } else {
            registry.unregister(handler);
        }
    }

    public static ServuxHandler servux() {
        return SERVUX;
    }

    public static SyncmaticaHandler syncmatica() {
        return SYNCMATICA;
    }

    public static JadeHandler jade() {
        return JADE;
    }

    public static AppleSkinHandler appleskin() {
        return APPLESKIN;
    }

    public static MapWorldIdHandler mapWorldId() {
        return MAP_WORLD_ID;
    }

    public static final class ServuxHandler implements ClientProtocolHandler {
        private static final Set<String> CHANNELS = Set.of(TechnicalProtocolCodecs.CHANNEL_SERVUX_STRUCTURES);
        private final ConcurrentHashMap<UUID, TechnicalProtocolCodecs.ServuxStructureQuery> lastQueries = new ConcurrentHashMap<>();
        private final AtomicLong validQueries = new AtomicLong();
        private final AtomicLong outboundPackets = new AtomicLong();

        @Override
        public String namespace() {
            return "servux";
        }

        @Override
        public Set<String> channelIds() {
            return CHANNELS;
        }

        @Override
        public String requiredPermission() {
            return "goldleaf.protocol.servux";
        }

        @Override
        public boolean onCustomPayload(final UUID playerId, final String channelId, final byte[] payload) {
            final GoldLeafConfig cfg = GoldLeafConfig.get();
            final int maxRadius = (cfg != null && cfg.protocols != null) ? Math.max(1, cfg.protocols.servuxMaxStructureRadius) : 128;
            final TechnicalProtocolCodecs.ServuxStructureQuery query =
                TechnicalProtocolCodecs.decodeServuxStructureQuery(payload, maxRadius);
            if (query == null) {
                return false;
            }
            this.lastQueries.put(playerId, query);
            this.validQueries.incrementAndGet();
            final PayloadSender sender = payloadSender;
            final ServuxQueryResponder responder = servuxResponder;
            if (sender != null && responder != null) {
                final byte[] response = responder.respond(playerId, query);
                if (response != null && sender.send(playerId, TechnicalProtocolCodecs.CHANNEL_SERVUX_STRUCTURES, response)) {
                    this.outboundPackets.incrementAndGet();
                }
            }
            return true;
        }

        @Override
        public void onPlayerQuit(final UUID playerId) {
            this.lastQueries.remove(playerId);
        }

        public TechnicalProtocolCodecs.@Nullable ServuxStructureQuery lastQuery(final UUID playerId) {
            return this.lastQueries.get(playerId);
        }

        public long validQueries() {
            return this.validQueries.get();
        }

        public long outboundPackets() {
            return this.outboundPackets.get();
        }
    }

    public static final class SyncmaticaHandler implements ClientProtocolHandler {
        private static final Set<String> CHANNELS = Set.of(TechnicalProtocolCodecs.CHANNEL_SYNCMATICA_MAIN);
        private final ConcurrentHashMap<UUID, TechnicalProtocolCodecs.SyncmaticaPlacementHeader> lastHeaders = new ConcurrentHashMap<>();
        private final AtomicLong validHeaders = new AtomicLong();
        private final AtomicLong outboundPackets = new AtomicLong();

        @Override
        public String namespace() {
            return "syncmatica";
        }

        @Override
        public Set<String> channelIds() {
            return CHANNELS;
        }

        @Override
        public String requiredPermission() {
            return "goldleaf.protocol.syncmatica";
        }

        @Override
        public boolean onCustomPayload(final UUID playerId, final String channelId, final byte[] payload) {
            final GoldLeafConfig cfg = GoldLeafConfig.get();
            final int maxBytes = (cfg != null && cfg.protocols != null) ? Math.max(1024, cfg.protocols.syncmaticaMaxFileBytes) : 2 * 1024 * 1024;
            final TechnicalProtocolCodecs.SyncmaticaPlacementHeader header =
                TechnicalProtocolCodecs.decodeSyncmaticaHeader(payload, maxBytes);
            if (header == null) {
                return false;
            }
            this.lastHeaders.put(playerId, header);
            this.validHeaders.incrementAndGet();
            final PayloadSender sender = payloadSender;
            final SyncmaticaHeaderResponder responder = syncmaticaResponder;
            if (sender != null && responder != null) {
                final byte[] response = responder.respond(playerId, header);
                if (response != null && sender.send(playerId, TechnicalProtocolCodecs.CHANNEL_SYNCMATICA_MAIN, response)) {
                    this.outboundPackets.incrementAndGet();
                }
            }
            return true;
        }

        @Override
        public void onPlayerQuit(final UUID playerId) {
            this.lastHeaders.remove(playerId);
        }

        public TechnicalProtocolCodecs.@Nullable SyncmaticaPlacementHeader lastHeader(final UUID playerId) {
            return this.lastHeaders.get(playerId);
        }

        public long validHeaders() {
            return this.validHeaders.get();
        }

        public long outboundPackets() {
            return this.outboundPackets.get();
        }
    }

    public static final class JadeHandler implements ClientProtocolHandler {
        private static final Set<String> CHANNELS = Set.of(TechnicalProtocolCodecs.CHANNEL_JADE_REQUEST);
        private final ConcurrentHashMap<UUID, TechnicalProtocolCodecs.JadeInspectRequest> lastRequests = new ConcurrentHashMap<>();
        private final AtomicLong validRequests = new AtomicLong();
        private final AtomicLong outboundPackets = new AtomicLong();

        @Override
        public String namespace() {
            return "jade";
        }

        @Override
        public Set<String> channelIds() {
            return CHANNELS;
        }

        @Override
        public String requiredPermission() {
            return "goldleaf.protocol.jade";
        }

        @Override
        public boolean onCustomPayload(final UUID playerId, final String channelId, final byte[] payload) {
            final TechnicalProtocolCodecs.JadeInspectRequest req =
                TechnicalProtocolCodecs.decodeJadeInspectRequest(payload);
            if (req == null) {
                return false;
            }
            this.lastRequests.put(playerId, req);
            this.validRequests.incrementAndGet();
            final PayloadSender sender = payloadSender;
            final JadeInspectResponder responder = jadeResponder;
            if (sender != null && responder != null) {
                final byte[] response = responder.respond(playerId, req);
                if (response != null && sender.send(playerId, TechnicalProtocolCodecs.CHANNEL_JADE_REQUEST, response)) {
                    this.outboundPackets.incrementAndGet();
                }
            }
            return true;
        }

        @Override
        public void onPlayerQuit(final UUID playerId) {
            this.lastRequests.remove(playerId);
        }

        public TechnicalProtocolCodecs.@Nullable JadeInspectRequest lastRequest(final UUID playerId) {
            return this.lastRequests.get(playerId);
        }

        public long validRequests() {
            return this.validRequests.get();
        }

        public long outboundPackets() {
            return this.outboundPackets.get();
        }
    }

    public static final class AppleSkinHandler implements ClientProtocolHandler {
        private static final Set<String> CHANNELS = Set.of(
            TechnicalProtocolCodecs.CHANNEL_APPLESKIN_SATURATION,
            TechnicalProtocolCodecs.CHANNEL_APPLESKIN_EXHAUSTION
        );
        private final ConcurrentHashMap<UUID, TechnicalProtocolCodecs.AppleSkinTracker> trackers = new ConcurrentHashMap<>();
        private final AtomicLong validUpdates = new AtomicLong();
        private final AtomicLong outboundPackets = new AtomicLong();

        @Override
        public String namespace() {
            return "appleskin";
        }

        @Override
        public Set<String> channelIds() {
            return CHANNELS;
        }

        @Override
        public boolean onCustomPayload(final UUID playerId, final String channelId, final byte[] payload) {
            final Float value = TechnicalProtocolCodecs.decodeFloat(payload);
            if (value == null) {
                return false;
            }
            final TechnicalProtocolCodecs.AppleSkinTracker tracker =
                this.trackers.computeIfAbsent(playerId, ignored -> new TechnicalProtocolCodecs.AppleSkinTracker());
            if (TechnicalProtocolCodecs.CHANNEL_APPLESKIN_SATURATION.equals(channelId)) {
                tracker.encodeSaturationDelta(value);
            } else {
                tracker.encodeExhaustionDelta(value);
            }
            this.validUpdates.incrementAndGet();
            return true;
        }

        @Override
        public void onPlayerTick(final UUID playerId) {
            final PayloadSender sender = payloadSender;
            final PlayerStateProvider provider = playerStateProvider;
            if (sender == null || provider == null) {
                return;
            }
            final PlayerProtocolState state = provider.lookup(playerId);
            if (state == null) {
                return;
            }
            final TechnicalProtocolCodecs.AppleSkinTracker tracker =
                this.trackers.computeIfAbsent(playerId, ignored -> new TechnicalProtocolCodecs.AppleSkinTracker());
            final byte[] satDelta = tracker.encodeSaturationDelta(state.saturation());
            if (satDelta != null && sender.send(playerId, TechnicalProtocolCodecs.CHANNEL_APPLESKIN_SATURATION, satDelta)) {
                this.outboundPackets.incrementAndGet();
            }
            final byte[] exhDelta = tracker.encodeExhaustionDelta(state.exhaustion());
            if (exhDelta != null && sender.send(playerId, TechnicalProtocolCodecs.CHANNEL_APPLESKIN_EXHAUSTION, exhDelta)) {
                this.outboundPackets.incrementAndGet();
            }
        }

        @Override
        public void onPlayerQuit(final UUID playerId) {
            this.trackers.remove(playerId);
        }

        public int trackedPlayers() {
            return this.trackers.size();
        }

        public long validUpdates() {
            return this.validUpdates.get();
        }

        public long outboundPackets() {
            return this.outboundPackets.get();
        }
    }

    public static final class MapWorldIdHandler implements ClientProtocolHandler {
        private static final Set<String> CHANNELS = Set.of(TechnicalProtocolCodecs.CHANNEL_XAERO_WORLD_ID);
        private final ConcurrentHashMap<UUID, Integer> lastWorldIds = new ConcurrentHashMap<>();
        private final AtomicLong outboundPackets = new AtomicLong();

        @Override
        public String namespace() {
            return "xaerominimap";
        }

        @Override
        public Set<String> channelIds() {
            return CHANNELS;
        }

        @Override
        public void onPlayerJoin(final UUID playerId) {
            this.emitWorldIdIfAvailable(playerId, false);
        }

        @Override
        public void onChannelRegister(final UUID playerId, final String channelId) {
            if (TechnicalProtocolCodecs.CHANNEL_XAERO_WORLD_ID.equals(channelId)) {
                this.emitWorldIdIfAvailable(playerId, true);
            }
        }

        @Override
        public boolean onCustomPayload(final UUID playerId, final String channelId, final byte[] payload) {
            final Integer id = TechnicalProtocolCodecs.decodeXaeroWorldId(payload);
            if (id == null) {
                return false;
            }
            this.lastWorldIds.put(playerId, id);
            return true;
        }

        @Override
        public void onPlayerQuit(final UUID playerId) {
            this.lastWorldIds.remove(playerId);
        }

        private void emitWorldIdIfAvailable(final UUID playerId, final boolean force) {
            final PayloadSender sender = payloadSender;
            final PlayerStateProvider provider = playerStateProvider;
            if (sender == null || provider == null) {
                return;
            }
            final PlayerProtocolState state = provider.lookup(playerId);
            if (state == null || state.worldUuid() == null) {
                return;
            }
            final int worldId = TechnicalProtocolCodecs.deterministicWorldId(state.worldUuid(), null);
            final Integer prev = this.lastWorldIds.put(playerId, worldId);
            if (force || !Integer.valueOf(worldId).equals(prev)) {
                final byte[] wire = TechnicalProtocolCodecs.encodeXaeroWorldId(worldId);
                if (sender.send(playerId, TechnicalProtocolCodecs.CHANNEL_XAERO_WORLD_ID, wire)) {
                    this.outboundPackets.incrementAndGet();
                }
            }
        }

        public @Nullable Integer lastWorldId(final UUID playerId) {
            return this.lastWorldIds.get(playerId);
        }

        public long outboundPackets() {
            return this.outboundPackets.get();
        }
    }
}
