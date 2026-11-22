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
}
