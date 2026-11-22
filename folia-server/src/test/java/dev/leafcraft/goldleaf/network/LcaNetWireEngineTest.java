package dev.leafcraft.goldleaf.network;

import dev.leafcraft.goldleaf.storage.LcaChunkResidencyCache;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LcaNetWireEngineTest {

    private static final long REGISTRY_HASH = 0x1234_5678_9ABC_DEF0L;

    private static RawChunkFrameSnapshot uniformChunkFrame(final int sectionCount) {
        final int len = Math.max(128, 32 + sectionCount * 2);
        return new RawChunkFrameSnapshot(
            new byte[len],
            4189,
            -4,
            sectionCount,
            len,
            new int[]{0, 1, 2}
        );
    }

    @Test
    void handshakeNegotiatesCapabilitiesAndFailsClosedOnMismatch() {
        final LcaChunkResidencyCache cache = new LcaChunkResidencyCache();
        try (LcaNetWireEngine engine = new LcaNetWireEngine(
            cache,
            LcaNetWireEngine.ALL_CAPABILITIES,
            REGISTRY_HASH,
            256,
            null,
            null
        )) {
            final UUID playerId = UUID.randomUUID();
            final LcaNetWireEngine.HandshakePayload valid = new LcaNetWireEngine.HandshakePayload(
                LcaNetWireEngine.PROTOCOL_VERSION,
                LcaNetWireEngine.CAP_LCA_SECTION_STREAM
                    | LcaNetWireEngine.CAP_BATCH_LCD2
                    | LcaNetWireEngine.CAP_DELTA_REVISIT_CACHE
                    | LcaNetWireEngine.CAP_PROXY_SAFE_STORED_ZLIB
                    | LcaNetWireEngine.CAP_LCA_V3_SECTIONS,
                4096,
                REGISTRY_HASH
            );
            final LcaNetWireEngine.NegotiatedSession session = engine.handleClientHandshake(playerId, valid.toBytes());
            assertTrue(session.lcaStreamActive());
            assertTrue(session.isBatchLcd2Enabled());
            assertTrue(session.isDeltaRevisitCacheEnabled());
            assertTrue(session.isProxySafeStoredZlibEnabled());
            assertTrue(session.isV3SectionsEnabled());
            assertTrue(engine.isNegotiated(playerId));
            assertEquals(1, engine.activeSessionCount());

            // Registry parity mismatch must fall back to vanilla and evict prior session (S-004)
            final LcaNetWireEngine.HandshakePayload wrongHash = new LcaNetWireEngine.HandshakePayload(
                LcaNetWireEngine.PROTOCOL_VERSION,
                LcaNetWireEngine.ALL_CAPABILITIES,
                4096,
                REGISTRY_HASH ^ 1L
            );
            final LcaNetWireEngine.NegotiatedSession fallback = engine.handleClientHandshake(playerId, wrongHash.toBytes());
            assertFalse(fallback.lcaStreamActive());
            assertFalse(engine.isNegotiated(playerId));
            assertEquals(0, engine.activeSessionCount());

            // Truncated payload falls back cleanly
            assertFalse(engine.handleClientHandshake(playerId, new byte[5]).lcaStreamActive());
        }
    }

    @Test
    void emitsFullChunkThenCleanAckThenPatchDeltaWithProxySafeZlibAndLcd2() throws Exception {
        final LcaChunkResidencyCache cache = new LcaChunkResidencyCache();
        final long chunkKey = ChunkPos.pack(7, -9);
        cache.installBaseFrame(chunkKey, uniformChunkFrame(24));

        try (LcaNetWireEngine engine = new LcaNetWireEngine(
            cache,
            LcaNetWireEngine.ALL_CAPABILITIES,
            REGISTRY_HASH,
            256,
            null,
            null
        )) {
            final UUID playerId = UUID.randomUUID();
            final LcaNetWireEngine.HandshakePayload hs = new LcaNetWireEngine.HandshakePayload(
                LcaNetWireEngine.PROTOCOL_VERSION,
                LcaNetWireEngine.CAP_LCA_SECTION_STREAM
                    | LcaNetWireEngine.CAP_BATCH_LCD2
                    | LcaNetWireEngine.CAP_CLIENT_HEIGHTMAPS
                    | LcaNetWireEngine.CAP_DELTA_REVISIT_CACHE
                    | LcaNetWireEngine.CAP_PROXY_SAFE_STORED_ZLIB
                    | LcaNetWireEngine.CAP_LCA_V3_SECTIONS,
                2048,
                REGISTRY_HASH
            );
            assertTrue(engine.handleClientHandshake(playerId, hs.toBytes()).lcaStreamActive());

            // 1. Initial send -> EntryFullChunk
            final LcaChunkResidencyCache.FrozenReadSnapshot cleanSnap = cache.getReadSnapshot(chunkKey);
            assertNotNull(cleanSnap);
            final LcaNetWireEngine.EncodedChunkBatch first = engine.encodeChunkForSession(
                playerId, 7, -9, cleanSnap, false, s -> 0
            );
            assertNotNull(first);
            assertEquals(LcaRemoteStreamCodec.ENTRY_FULL_CHUNK, first.entryType());
            assertTrue(first.lcd2Compressed());
            assertTrue(first.proxySafeStoredZlibWrapped());

            // Unwrap stored-Zlib and decode LCAB batch via anvil-api LcaRemoteStreamCodec
            final byte[] unwrappedFirst = LcaNetWireEngine.unwrapProxySafeStoredZlib(
                first.wirePayload(),
                LcaRemoteStreamCodec.MAX_DECOMPRESSED_BATCH_BYTES
            );
            final LcaRemoteStreamCodec.StreamChunkBatchPayload decodedFirst =
                LcaRemoteStreamCodec.decodeChunkBatch("world", unwrappedFirst, true);
            assertEquals(1, decodedFirst.entries().size());
            assertEquals(LcaRemoteStreamCodec.ENTRY_FULL_CHUNK, decodedFirst.entries().get(0).entryType());

            // 2. Clean revisit -> EntryCleanAck (2-byte entry)
            final LcaNetWireEngine.EncodedChunkBatch revisit = engine.encodeChunkForSession(
                playerId, 7, -9, cleanSnap, false, s -> 0
            );
            assertNotNull(revisit);
            assertEquals(LcaRemoteStreamCodec.ENTRY_CLEAN_ACK, revisit.entryType());
            assertEquals(first.secDedSyndrome64(), revisit.secDedSyndrome64());

            // 3. Single-section dirty edit -> EntryPatchDelta
            assertTrue(cache.recordDirtyVoxel(chunkKey, 3, 42, 1234));
            final LcaChunkResidencyCache.FrozenReadSnapshot dirtySnap = cache.freezeTickEndSnapshot(chunkKey);
            assertNotNull(dirtySnap);
            final LcaNetWireEngine.EncodedChunkBatch patch = engine.encodeChunkForSession(
                playerId, 7, -9, dirtySnap, false, s -> 0
            );
            assertNotNull(patch);
            assertEquals(LcaRemoteStreamCodec.ENTRY_PATCH_DELTA, patch.entryType());

            final byte[] unwrappedPatch = LcaNetWireEngine.unwrapProxySafeStoredZlib(
                patch.wirePayload(),
                LcaRemoteStreamCodec.MAX_DECOMPRESSED_BATCH_BYTES
            );
            final LcaRemoteStreamCodec.StreamChunkBatchPayload decodedPatch =
                LcaRemoteStreamCodec.decodeChunkBatch("world", unwrappedPatch, true);
            assertEquals(1, decodedPatch.entries().size());
            assertEquals(LcaRemoteStreamCodec.ENTRY_PATCH_DELTA, decodedPatch.entries().get(0).entryType());

            // 4. Disconnect cleanup releases session state (S-002)
            engine.unregisterConnection(playerId);
            assertFalse(engine.isNegotiated(playerId));
            assertEquals(0, engine.activeSessionCount());
            assertNull(engine.encodeChunkForSession(playerId, 7, -9, dirtySnap, false, s -> 0));
        }
    }

    @Test
    void failsClosedToVanillaWhenAntiXrayRequiredWithoutSanitizer() throws Exception {
        final LcaChunkResidencyCache cache = new LcaChunkResidencyCache();
        final long chunkKey = ChunkPos.pack(2, 3);
        cache.installBaseFrame(chunkKey, uniformChunkFrame(24));

        try (LcaNetWireEngine engine = new LcaNetWireEngine(
            cache,
            LcaNetWireEngine.ALL_CAPABILITIES,
            REGISTRY_HASH,
            128,
            null,
            null
        )) {
            final UUID playerId = UUID.randomUUID();
            final LcaNetWireEngine.HandshakePayload hs = new LcaNetWireEngine.HandshakePayload(
                LcaNetWireEngine.PROTOCOL_VERSION,
                LcaNetWireEngine.CAP_LCA_SECTION_STREAM,
                0,
                REGISTRY_HASH
            );
            assertTrue(engine.handleClientHandshake(playerId, hs.toBytes()).lcaStreamActive());

            final LcaChunkResidencyCache.FrozenReadSnapshot snap = cache.getReadSnapshot(chunkKey);
            assertNotNull(snap);

            // Without a sanitizer, anti-xray chunks must return null (vanilla fallback)
            assertNull(engine.encodeChunkForSession(playerId, 2, 3, snap, true, s -> 0));
        }
    }

    @Test
    void exportsAndDecodesLcasHaloDeltaFrameAndValidatesStoredZlibBounds() throws Exception {
        final LcaChunkResidencyCache cache = new LcaChunkResidencyCache();
        final long chunkKey = ChunkPos.pack(15, 22);
        cache.installBaseFrame(chunkKey, uniformChunkFrame(24));
        assertTrue(cache.recordDirtyVoxel(chunkKey, 1, 77, 999));
        final LcaChunkResidencyCache.FrozenReadSnapshot dirtySnap = cache.freezeTickEndSnapshot(chunkKey);
        assertNotNull(dirtySnap);

        final byte[] lcasBytes = LcaNetWireEngine.exportLcasHaloDelta(
            "shard-north-1",
            "minecraft:overworld",
            15,
            22,
            9L,
            0xCAFEBABEL,
            dirtySnap,
            true,
            s -> 0
        );
        final LcaRemoteStreamCodec.StreamDeltaUpdatePayload decoded =
            LcaRemoteStreamCodec.decodeDeltaUpdatePayload(lcasBytes, true);
        assertEquals("shard-north-1", decoded.sourceServerId());
        assertEquals("minecraft:overworld", decoded.dimension());
        assertEquals(9L, decoded.epochSequence());
        assertEquals(1, decoded.entries().size());
        assertEquals(LcaRemoteStreamCodec.ENTRY_PATCH_DELTA, decoded.entries().get(0).chunkEntry().entryType());

        // Stored-Zlib round-trip & size-limit fail-closed check (S-004)
        final byte[] raw = "goldleaf-lca-net-v1-payload".getBytes();
        final byte[] wrapped = LcaNetWireEngine.wrapProxySafeStoredZlib(raw);
        assertArrayEquals(raw, LcaNetWireEngine.unwrapProxySafeStoredZlib(wrapped, 1024));
        assertThrows(IOException.class, () -> LcaNetWireEngine.unwrapProxySafeStoredZlib(wrapped, 4));
    }
}
