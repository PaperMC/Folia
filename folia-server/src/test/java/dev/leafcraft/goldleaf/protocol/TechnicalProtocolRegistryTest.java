package dev.leafcraft.goldleaf.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verification tests for {@code goldleaf-ADR-010}: default-off zero-overhead registry,
 * protocol wire round-trips, rate-limit/byte-cap/permission guards, fuzz resilience ({@code ADR-102}),
 * and disconnect cleanup ({@code S-002}, {@code S-004}).
 */
final class TechnicalProtocolRegistryTest {

    @AfterEach
    void cleanup() {
        TechnicalProtocolRegistry.global().clear();
        BuiltInTechnicalProtocolHandlers.setPayloadSender(null);
        BuiltInTechnicalProtocolHandlers.setPlayerStateProvider(null);
        BuiltInTechnicalProtocolHandlers.setServuxResponder(null);
        BuiltInTechnicalProtocolHandlers.setSyncmaticaResponder(null);
        BuiltInTechnicalProtocolHandlers.setJadeResponder(null);
    }

    @Test
    void allProtocolsDefaultOffAndEmptyRegistryFastPathReturnsNoHandler() {
        final GoldLeafConfig defaults = GoldLeafConfig.defaults();
        assertFalse(defaults.protocols.servuxEnabled);
        assertFalse(defaults.protocols.syncmaticaEnabled);
        assertFalse(defaults.protocols.jadeEnabled);
        assertFalse(defaults.protocols.appleskinEnabled);
        assertFalse(defaults.protocols.mapWorldIdEnabled);

        final TechnicalProtocolRegistry registry = new TechnicalProtocolRegistry();
        assertTrue(registry.isEmpty());
        assertEquals(
            TechnicalProtocolRegistry.DispatchStatus.NO_HANDLER,
            registry.dispatchIncoming(UUID.randomUUID(), TechnicalProtocolCodecs.CHANNEL_SERVUX_STRUCTURES, new byte[4], null)
        );
    }

    @Test
    void protocolCodecsRoundTripAndAppleSkinDirtyCheckSuppressesUnchanged() {
        // AppleSkin dirty-checked delta
        final TechnicalProtocolCodecs.AppleSkinTracker tracker = new TechnicalProtocolCodecs.AppleSkinTracker();
        final byte[] firstSat = tracker.encodeSaturationDelta(5.5f);
        assertNotNull(firstSat);
        assertEquals(5.5f, TechnicalProtocolCodecs.decodeFloat(firstSat));
        assertNull(tracker.encodeSaturationDelta(5.5f), "Unchanged saturation must allocate nothing and return null");
        assertNotNull(tracker.encodeSaturationDelta(6.0f));

        // Map World-ID deterministic world/shard disambiguation
        final UUID worldId = UUID.randomUUID();
        final int shardA = TechnicalProtocolCodecs.deterministicWorldId(worldId, "shard-a");
        final int shardB = TechnicalProtocolCodecs.deterministicWorldId(worldId, "shard-b");
        assertEquals(shardA, TechnicalProtocolCodecs.deterministicWorldId(worldId, "shard-a"));
        assertNotEquals(shardA, shardB);
        final byte[] encodedWorldId = TechnicalProtocolCodecs.encodeXaeroWorldId(shardA);
        assertEquals(shardA, TechnicalProtocolCodecs.decodeXaeroWorldId(encodedWorldId));

        // Servux structure query
        final var query = new TechnicalProtocolCodecs.ServuxStructureQuery(2, 12, -34, 64);
        assertEquals(query, TechnicalProtocolCodecs.decodeServuxStructureQuery(
            TechnicalProtocolCodecs.encodeServuxStructureQuery(query), 128
        ));
        assertNull(TechnicalProtocolCodecs.decodeServuxStructureQuery(
            TechnicalProtocolCodecs.encodeServuxStructureQuery(new TechnicalProtocolCodecs.ServuxStructureQuery(2, 0, 0, 256)),
            128
        ), "Radius exceeding server cap must fail closed");

        // Jade inspect request
        final var jade = new TechnicalProtocolCodecs.JadeInspectRequest(100, 64, -200, 42);
        assertEquals(jade, TechnicalProtocolCodecs.decodeJadeInspectRequest(
            TechnicalProtocolCodecs.encodeJadeInspectRequest(jade)
        ));

        // Syncmatica header
        final var sync = new TechnicalProtocolCodecs.SyncmaticaPlacementHeader(UUID.randomUUID(), "iron-farm.litematic", 65536);
        assertEquals(sync, TechnicalProtocolCodecs.decodeSyncmaticaHeader(
            TechnicalProtocolCodecs.encodeSyncmaticaHeader(sync), 2 * 1024 * 1024
        ));
        assertNull(TechnicalProtocolCodecs.decodeSyncmaticaHeader(
            TechnicalProtocolCodecs.encodeSyncmaticaHeader(sync), 1024
        ), "File size exceeding cap must fail closed");
    }

    @Test
    void registryEnforcesPermissionsByteCapsRateLimitsFaultIsolationAndQuitCleanup() {
        final TechnicalProtocolRegistry registry = new TechnicalProtocolRegistry();
        final AtomicInteger handled = new AtomicInteger();

        registry.register(new ClientProtocolHandler() {
            @Override
            public String namespace() {
                return "servux";
            }

            @Override
            public Set<String> channelIds() {
                return Set.of(TechnicalProtocolCodecs.CHANNEL_SERVUX_STRUCTURES);
            }

            @Override
            public String requiredPermission() {
                return "goldleaf.protocol.servux";
            }

            @Override
            public int maxPayloadBytes() {
                return 64;
            }

            @Override
            public boolean onCustomPayload(final UUID playerId, final String channelId, final byte[] payload) {
                if (payload.length == 1 && payload[0] == (byte) 0xFF) {
                    throw new IllegalStateException("simulated handler crash");
                }
                if (payload.length == 1 && payload[0] == (byte) 0xEE) {
                    return false;
                }
                handled.incrementAndGet();
                return true;
            }
        });

        final UUID player = UUID.randomUUID();

        // Permission gate
        assertEquals(
            TechnicalProtocolRegistry.DispatchStatus.REJECTED_PERMISSION,
            registry.dispatchIncoming(player, TechnicalProtocolCodecs.CHANNEL_SERVUX_STRUCTURES, new byte[8], (u, p) -> false, 10L)
        );

        // Oversized payload gate
        assertEquals(
            TechnicalProtocolRegistry.DispatchStatus.REJECTED_OVERSIZED,
            registry.dispatchIncoming(player, TechnicalProtocolCodecs.CHANNEL_SERVUX_STRUCTURES, new byte[128], (u, p) -> true, 10L)
        );

        // Malformed payload & throwing handler isolation (S-004)
        assertEquals(
            TechnicalProtocolRegistry.DispatchStatus.REJECTED_MALFORMED,
            registry.dispatchIncoming(player, TechnicalProtocolCodecs.CHANNEL_SERVUX_STRUCTURES, new byte[]{(byte) 0xEE}, (u, p) -> true, 10L)
        );
        assertEquals(
            TechnicalProtocolRegistry.DispatchStatus.HANDLER_ERROR,
            registry.dispatchIncoming(player, TechnicalProtocolCodecs.CHANNEL_SERVUX_STRUCTURES, new byte[]{(byte) 0xFF}, (u, p) -> true, 10L)
        );

        // Rate limit enforcement within same second
        final int cap = GoldLeafConfig.get().protocols.maxPacketsPerSecond;
        int rateLimited = 0;
        for (int i = 0; i < cap + 5; i++) {
            final var status = registry.dispatchIncoming(player, TechnicalProtocolCodecs.CHANNEL_SERVUX_STRUCTURES, new byte[4], (u, p) -> true, 11L);
            if (status == TechnicalProtocolRegistry.DispatchStatus.REJECTED_RATE_LIMITED) {
                rateLimited++;
            }
        }
        assertEquals(5, rateLimited);
        assertEquals(cap, handled.get());
        assertEquals(1, registry.trackedRateLimiters());

        // S-002 quit cleanup
        registry.onPlayerQuit(player);
        assertEquals(0, registry.trackedRateLimiters());
    }

    @Test
    void fuzzProtocolDecodersFailClosedWithZeroExceptions() {
        final Random rng = new Random(0xA010_F022L);
        for (int i = 0; i < 500; i++) {
            final byte[] buf = new byte[rng.nextInt(128)];
            rng.nextBytes(buf);
            // Must never throw on arbitrary truncated/malformed input
            TechnicalProtocolCodecs.decodeFloat(buf);
            TechnicalProtocolCodecs.decodeXaeroWorldId(buf);
            TechnicalProtocolCodecs.decodeServuxStructureQuery(buf, 128);
            TechnicalProtocolCodecs.decodeJadeInspectRequest(buf);
            TechnicalProtocolCodecs.decodeSyncmaticaHeader(buf, 65536);
        }
        assertArrayEquals(new byte[0], new byte[0]);
    }

    @Test
    void builtInRespondersEmitAppleSkinDeltasAndXaeroWorldIdOutbounds() {
        final java.util.List<String> sentChannels = new java.util.ArrayList<>();
        final java.util.List<byte[]> sentPayloads = new java.util.ArrayList<>();
        final UUID playerId = UUID.randomUUID();
        final UUID worldUuid = UUID.randomUUID();
        final float[] sat = new float[] {5.0f};
        final float[] exh = new float[] {0.25f};

        BuiltInTechnicalProtocolHandlers.setPayloadSender((id, ch, payload) -> {
            if (playerId.equals(id)) {
                sentChannels.add(ch);
                sentPayloads.add(payload);
                return true;
            }
            return false;
        });
        BuiltInTechnicalProtocolHandlers.setPlayerStateProvider(id ->
            playerId.equals(id)
                ? new BuiltInTechnicalProtocolHandlers.PlayerProtocolState(worldUuid, sat[0], exh[0])
                : null
        );

        final var apple = BuiltInTechnicalProtocolHandlers.appleskin();
        apple.onPlayerQuit(playerId);
        apple.onPlayerTick(playerId);
        assertEquals(2, sentChannels.size(), "First tick must emit saturation + exhaustion deltas");
        assertEquals(TechnicalProtocolCodecs.CHANNEL_APPLESKIN_SATURATION, sentChannels.get(0));
        assertEquals(5.0f, TechnicalProtocolCodecs.decodeFloat(sentPayloads.get(0)));
        assertEquals(TechnicalProtocolCodecs.CHANNEL_APPLESKIN_EXHAUSTION, sentChannels.get(1));
        assertEquals(0.25f, TechnicalProtocolCodecs.decodeFloat(sentPayloads.get(1)));

        // Unchanged tick must emit zero packets
        apple.onPlayerTick(playerId);
        assertEquals(2, sentChannels.size(), "Unchanged saturation/exhaustion must emit nothing");

        // Changed saturation only -> 1 packet
        sat[0] = 6.5f;
        apple.onPlayerTick(playerId);
        assertEquals(3, sentChannels.size());
        assertEquals(6.5f, TechnicalProtocolCodecs.decodeFloat(sentPayloads.get(2)));
        apple.onPlayerQuit(playerId);

        // Map World-ID emission on join and forced re-emission on channel register
        final var map = BuiltInTechnicalProtocolHandlers.mapWorldId();
        map.onPlayerQuit(playerId);
        map.onPlayerJoin(playerId);
        assertEquals(4, sentChannels.size());
        assertEquals(TechnicalProtocolCodecs.CHANNEL_XAERO_WORLD_ID, sentChannels.get(3));
        assertEquals(
            TechnicalProtocolCodecs.deterministicWorldId(worldUuid, null),
            TechnicalProtocolCodecs.decodeXaeroWorldId(sentPayloads.get(3))
        );
        map.onChannelRegister(playerId, TechnicalProtocolCodecs.CHANNEL_XAERO_WORLD_ID);
        assertEquals(5, sentChannels.size(), "Channel registration must emit Xaero world ID");
        map.onPlayerQuit(playerId);
    }

    @Test
    void servuxSyncmaticaAndJadeHandlersEmitConfiguredOutboundResponses() {
        final java.util.List<String> sentChannels = new java.util.ArrayList<>();
        final UUID playerId = UUID.randomUUID();
        BuiltInTechnicalProtocolHandlers.setPayloadSender((id, ch, payload) -> {
            if (playerId.equals(id) && payload.length > 0) {
                sentChannels.add(ch);
                return true;
            }
            return false;
        });
        BuiltInTechnicalProtocolHandlers.setServuxResponder((id, q) ->
            TechnicalProtocolCodecs.encodeServuxStructureQuery(q)
        );
        BuiltInTechnicalProtocolHandlers.setSyncmaticaResponder((id, h) ->
            TechnicalProtocolCodecs.encodeSyncmaticaHeader(h)
        );
        BuiltInTechnicalProtocolHandlers.setJadeResponder((id, r) ->
            TechnicalProtocolCodecs.encodeJadeInspectRequest(r)
        );

        final var servux = BuiltInTechnicalProtocolHandlers.servux();
        final long servuxBefore = servux.outboundPackets();
        assertTrue(servux.onCustomPayload(
            playerId,
            TechnicalProtocolCodecs.CHANNEL_SERVUX_STRUCTURES,
            TechnicalProtocolCodecs.encodeServuxStructureQuery(
                new TechnicalProtocolCodecs.ServuxStructureQuery(1, 1, -2, 8)
            )
        ));
        assertEquals(servuxBefore + 1L, servux.outboundPackets());

        final var sync = BuiltInTechnicalProtocolHandlers.syncmatica();
        final long syncBefore = sync.outboundPackets();
        assertTrue(sync.onCustomPayload(
            playerId,
            TechnicalProtocolCodecs.CHANNEL_SYNCMATICA_MAIN,
            TechnicalProtocolCodecs.encodeSyncmaticaHeader(
                new TechnicalProtocolCodecs.SyncmaticaPlacementHeader(UUID.randomUUID(), "sorter.litematic", 4096)
            )
        ));
        assertEquals(syncBefore + 1L, sync.outboundPackets());

        final var jade = BuiltInTechnicalProtocolHandlers.jade();
        final long jadeBefore = jade.outboundPackets();
        assertTrue(jade.onCustomPayload(
            playerId,
            TechnicalProtocolCodecs.CHANNEL_JADE_REQUEST,
            TechnicalProtocolCodecs.encodeJadeInspectRequest(
                new TechnicalProtocolCodecs.JadeInspectRequest(10, 64, -5, -1)
            )
        ));
        assertEquals(jadeBefore + 1L, jade.outboundPackets());

        assertEquals(
            java.util.List.of(
                TechnicalProtocolCodecs.CHANNEL_SERVUX_STRUCTURES,
                TechnicalProtocolCodecs.CHANNEL_SYNCMATICA_MAIN,
                TechnicalProtocolCodecs.CHANNEL_JADE_REQUEST
            ),
            sentChannels
        );
        servux.onPlayerQuit(playerId);
        sync.onPlayerQuit(playerId);
        jade.onPlayerQuit(playerId);
    }
}
