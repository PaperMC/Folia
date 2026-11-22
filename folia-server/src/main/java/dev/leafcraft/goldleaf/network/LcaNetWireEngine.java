package dev.leafcraft.goldleaf.network;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.storage.LcaChunkResidencyCache;
import dev.leafcraft.goldleaf.storage.LcaResidencyManager;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 {@code LCA_NET_V1} negotiated chunk-streaming and {@code LCAS} Halo wire engine
 * implementing {@link ChunkStreamInterceptor} ({@code goldleaf-ADR-005} Stage 3,
 * {@code leafanvil-ADR-003}).
 *
 * <p>Reuses {@link LcaRemoteStreamCodec} from {@code anvil-api} to encode paced {@code LCAB}
 * batches ({@link LcaRemoteStreamCodec.EntryCleanAck}, {@link LcaRemoteStreamCodec.EntryPatchDelta},
 * {@link LcaRemoteStreamCodec.EntryFullChunk}), optional {@code LCD2} intra-batch compression,
 * proxy-safe stored-Zlib ({@link Deflater#NO_COMPRESSION}) envelopes for Velocity/BungeeCord
 * compatibility, and {@code LCAS} ({@code 0x4C434153}) inter-shard Halo delta frames.</p>
 *
 * <p>Safety guarantees:</p>
 * <ul>
 *   <li><b>Mixed-client safety</b>: Unnegotiated or mismatched-registry clients fall back cleanly
 *       to {@link NegotiatedSession#VANILLA_FALLBACK} with zero extra packet work.</li>
 *   <li><b>Anti-xray safety</b>: When {@code shouldModifyAntiXray} is {@code true} and no
 *       {@link ChunkSectionSanitizer} is registered, {@link #trySendChunk} returns {@code false}
 *       so Paper's vanilla anti-xray packet path remains authoritative.</li>
 *   <li><b>Lifecycle safety ({@code S-002})</b>: {@link #unregisterConnection(UUID)} and
 *       {@link #close()} deterministically release all per-session state and digest caches.</li>
 * </ul>
 */
public final class LcaNetWireEngine implements ChunkStreamInterceptor, Closeable {

    public static final String CHANNEL_HANDSHAKE = "leafanvil:chunk_stream_handshake";
    public static final String CHANNEL_REGION_DICT_SYNC = "leafanvil:lca_region_dict_sync";
    public static final String CHANNEL_CHUNK_BATCH = "leafanvil:lca_chunk_batch";
    public static final String CHANNEL_CHUNK_DIGEST = "leafanvil:lca_chunk_digest";

    public static final int PROTOCOL_VERSION = 1;

    public static final int CAP_LCA_SECTION_STREAM = 1 << 0;
    public static final int CAP_BATCH_LCD2 = 1 << 1;
    public static final int CAP_CLIENT_HEIGHTMAPS = 1 << 2;
    public static final int CAP_DELTA_REVISIT_CACHE = 1 << 3;
    public static final int CAP_PROXY_SAFE_STORED_ZLIB = 1 << 4;
    public static final int CAP_ORDERLESS_DICT_AND_MULTICAST = 1 << 5;
    public static final int CAP_LCA_V3_SECTIONS = 1 << 6;

    public static final int ALL_CAPABILITIES =
        CAP_LCA_SECTION_STREAM
            | CAP_BATCH_LCD2
            | CAP_CLIENT_HEIGHTMAPS
            | CAP_DELTA_REVISIT_CACHE
            | CAP_PROXY_SAFE_STORED_ZLIB
            | CAP_ORDERLESS_DICT_AND_MULTICAST
            | CAP_LCA_V3_SECTIONS;

    /**
     * Pluggable anti-xray / occlusion sanitizer applied to {@link LcaRemoteStreamCodec.SectionWireSlice}
     * lists before emitting {@code LCA_NET_V1} frames when {@code shouldModifyAntiXray} is true.
     */
    @FunctionalInterface
    public interface ChunkSectionSanitizer {
        List<LcaRemoteStreamCodec.SectionWireSlice> sanitize(
            UUID playerId,
            int chunkX,
            int chunkZ,
            List<LcaRemoteStreamCodec.SectionWireSlice> rawSlices
        );
    }

    /**
     * Pluggable outbound frame emitter invoked when {@link #trySendChunk} encodes a chunk batch
     * for a negotiated player connection.
     */
    @FunctionalInterface
    public interface OutboundBatchEmitter {
        boolean emitChunkBatch(
            ServerGamePacketListenerImpl connection,
            String channel,
            byte[] wirePayload
        );
    }

    /**
     * 20-byte big-endian configuration-phase capability handshake payload
     * ({@code leafanvil:chunk_stream_handshake}).
     */
    public record HandshakePayload(
        int protocolVersion,
        int capabilityFlags,
        int clientCacheBudgetKiB,
        long registryParityHash64
    ) {
        public static final int WIRE_BYTES = 20;

        public byte[] toBytes() {
            final ByteBuffer buffer = ByteBuffer.allocate(WIRE_BYTES);
            buffer.putInt(this.protocolVersion);
            buffer.putInt(this.capabilityFlags);
            buffer.putInt(this.clientCacheBudgetKiB);
            buffer.putLong(this.registryParityHash64);
            return buffer.array();
        }

        public static HandshakePayload fromBytes(final byte[] bytes) {
            if (bytes == null || bytes.length != WIRE_BYTES) {
                throw new IllegalArgumentException(
                    "Invalid LCA_NET_V1 HandshakePayload length: expected " + WIRE_BYTES
                        + " bytes, got " + (bytes == null ? -1 : bytes.length)
                );
            }
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new HandshakePayload(
                buffer.getInt(),
                buffer.getInt(),
                buffer.getInt(),
                buffer.getLong()
            );
        }
    }

    /**
     * Negotiated per-connection {@code LCA_NET_V1} streaming session capabilities.
     */
    public record NegotiatedSession(
        boolean lcaStreamActive,
        int activeCapabilities,
        int negotiatedCacheBudgetKiB,
        long registryParityHash64
    ) {
        public static final NegotiatedSession VANILLA_FALLBACK =
            new NegotiatedSession(false, 0, 0, 0L);

        public boolean hasCapability(final int capabilityFlag) {
            return this.lcaStreamActive && (this.activeCapabilities & capabilityFlag) == capabilityFlag;
        }

        public boolean isBatchLcd2Enabled() {
            return hasCapability(CAP_BATCH_LCD2);
        }

        public boolean isClientHeightmapsEnabled() {
            return hasCapability(CAP_CLIENT_HEIGHTMAPS);
        }

        public boolean isDeltaRevisitCacheEnabled() {
            return hasCapability(CAP_DELTA_REVISIT_CACHE);
        }

        public boolean isProxySafeStoredZlibEnabled() {
            return hasCapability(CAP_PROXY_SAFE_STORED_ZLIB);
        }

        public boolean isV3SectionsEnabled() {
            return hasCapability(CAP_LCA_V3_SECTIONS);
        }
    }

    /**
     * Result of encoding a single chunk into an {@code LCA_NET_V1} batch payload for a negotiated
     * connection.
     */
    public record EncodedChunkBatch(
        int entryType,
        long secDedSyndrome64,
        boolean lcd2Compressed,
        boolean proxySafeStoredZlibWrapped,
        byte[] wirePayload
    ) {
    }

    private record ClientChunkBaseline(long snapshotEpoch, long secDedSyndrome64) {
    }

    private static final class PlayerWireSession {
        private final NegotiatedSession negotiated;
        private final Map<Long, ClientChunkBaseline> knownChunks;

        PlayerWireSession(final NegotiatedSession negotiated, final int maxCachedChunks) {
            this.negotiated = negotiated;
            final int cap = Math.max(16, maxCachedChunks);
            this.knownChunks = new LinkedHashMap<>(Math.min(cap, 256), 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<Long, ClientChunkBaseline> eldest) {
                    return size() > cap;
                }
            };
        }

        synchronized @Nullable ClientChunkBaseline getBaseline(final long chunkKey) {
            return this.knownChunks.get(chunkKey);
        }

        synchronized void recordBaseline(final long chunkKey, final long epoch, final long syndrome64) {
            this.knownChunks.put(chunkKey, new ClientChunkBaseline(epoch, syndrome64));
        }

        synchronized void clear() {
            this.knownChunks.clear();
        }
    }

    private final LcaChunkResidencyCache residencyCache;
    private final @Nullable LcaResidencyManager residencyManager;
    private final int serverCapabilities;
    private final long serverRegistryParityHash64;
    private final int maxSessionCacheChunks;
    private final @Nullable ChunkSectionSanitizer antiXraySanitizer;
    private final @Nullable OutboundBatchEmitter outboundEmitter;
    private final ConcurrentHashMap<UUID, PlayerWireSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LcaNetWireEngine(
        final LcaChunkResidencyCache residencyCache,
        final int serverCapabilities,
        final long serverRegistryParityHash64,
        final int maxSessionCacheChunks,
        final @Nullable ChunkSectionSanitizer antiXraySanitizer,
        final @Nullable OutboundBatchEmitter outboundEmitter
    ) {
        this(residencyCache, null, serverCapabilities, serverRegistryParityHash64, maxSessionCacheChunks, antiXraySanitizer, outboundEmitter);
    }

    public LcaNetWireEngine(
        final LcaChunkResidencyCache residencyCache,
        final @Nullable LcaResidencyManager residencyManager,
        final int serverCapabilities,
        final long serverRegistryParityHash64,
        final int maxSessionCacheChunks,
        final @Nullable ChunkSectionSanitizer antiXraySanitizer,
        final @Nullable OutboundBatchEmitter outboundEmitter
    ) {
        this.residencyCache = Objects.requireNonNull(residencyCache, "residencyCache");
        this.residencyManager = residencyManager;
        if (maxSessionCacheChunks < 16) {
            throw new IllegalArgumentException("maxSessionCacheChunks must be >= 16, got " + maxSessionCacheChunks);
        }
        this.serverCapabilities = serverCapabilities;
        this.serverRegistryParityHash64 = serverRegistryParityHash64;
        this.maxSessionCacheChunks = maxSessionCacheChunks;
        this.antiXraySanitizer = antiXraySanitizer;
        this.outboundEmitter = outboundEmitter;
    }

    public static LcaNetWireEngine fromConfig(
        final LcaChunkResidencyCache residencyCache,
        final GoldLeafConfig.Network networkConfig,
        final long serverRegistryParityHash64,
        final @Nullable ChunkSectionSanitizer antiXraySanitizer,
        final @Nullable OutboundBatchEmitter outboundEmitter
    ) {
        return fromConfig(residencyCache, null, networkConfig, serverRegistryParityHash64, antiXraySanitizer, outboundEmitter);
    }

    public static LcaNetWireEngine fromConfig(
        final LcaChunkResidencyCache residencyCache,
        final @Nullable LcaResidencyManager residencyManager,
        final GoldLeafConfig.Network networkConfig,
        final long serverRegistryParityHash64,
        final @Nullable ChunkSectionSanitizer antiXraySanitizer,
        final @Nullable OutboundBatchEmitter outboundEmitter
    ) {
        Objects.requireNonNull(networkConfig, "networkConfig");
        int caps = CAP_LCA_SECTION_STREAM | CAP_CLIENT_HEIGHTMAPS | CAP_DELTA_REVISIT_CACHE | CAP_LCA_V3_SECTIONS;
        if (networkConfig.lcaNetBatchLcd2) {
            caps |= CAP_BATCH_LCD2;
        }
        if (networkConfig.lcaNetProxySafeStoredZlib) {
            caps |= CAP_PROXY_SAFE_STORED_ZLIB;
        }
        return new LcaNetWireEngine(
            residencyCache,
            residencyManager,
            caps,
            serverRegistryParityHash64,
            networkConfig.lcaNetMaxSessionCacheChunks,
            antiXraySanitizer,
            outboundEmitter
        );
    }

    /**
     * Negotiates {@code LCA_NET_V1} capabilities for a client handshake payload, failing closed to
     * {@link NegotiatedSession#VANILLA_FALLBACK} on protocol or registry-hash mismatch ({@code S-004}).
     */
    public static NegotiatedSession negotiate(
        final @Nullable HandshakePayload client,
        final int serverCapabilities,
        final long serverRegistryParityHash64
    ) {
        if (client == null
            || client.protocolVersion() != PROTOCOL_VERSION
            || client.registryParityHash64() != serverRegistryParityHash64) {
            return NegotiatedSession.VANILLA_FALLBACK;
        }
        final int intersection = client.capabilityFlags() & serverCapabilities;
        if ((intersection & CAP_LCA_SECTION_STREAM) == 0) {
            return NegotiatedSession.VANILLA_FALLBACK;
        }
        final int cacheBudgetKiB = (intersection & CAP_DELTA_REVISIT_CACHE) != 0
            ? Math.max(0, client.clientCacheBudgetKiB())
            : 0;
        return new NegotiatedSession(true, intersection, cacheBudgetKiB, serverRegistryParityHash64);
    }

    /**
     * Handles a client's {@code leafanvil:chunk_stream_handshake} payload, registering an active
     * session when negotiated or clearing any prior session on fallback.
     */
    public NegotiatedSession handleClientHandshake(final UUID playerId, final byte[] handshakeBytes) {
        Objects.requireNonNull(playerId, "playerId");
        if (this.closed.get()) {
            return NegotiatedSession.VANILLA_FALLBACK;
        }
        final HandshakePayload clientPayload;
        try {
            clientPayload = HandshakePayload.fromBytes(handshakeBytes);
        } catch (final IllegalArgumentException invalid) {
            unregisterConnection(playerId);
            return NegotiatedSession.VANILLA_FALLBACK;
        }
        final NegotiatedSession negotiated = negotiate(
            clientPayload,
            this.serverCapabilities,
            this.serverRegistryParityHash64
        );
        if (!negotiated.lcaStreamActive()) {
            unregisterConnection(playerId);
            return NegotiatedSession.VANILLA_FALLBACK;
        }
        this.sessions.put(playerId, new PlayerWireSession(negotiated, this.maxSessionCacheChunks));
        return negotiated;
    }

    public @Nullable NegotiatedSession getSession(final UUID playerId) {
        final PlayerWireSession session = this.sessions.get(playerId);
        return session == null ? null : session.negotiated;
    }

    public boolean isNegotiated(final UUID playerId) {
        final PlayerWireSession session = this.sessions.get(playerId);
        return session != null && session.negotiated.lcaStreamActive();
    }

    /**
     * Deterministically removes a disconnecting player's session and client digest cache ({@code S-002}).
     */
    public void unregisterConnection(final UUID playerId) {
        final PlayerWireSession removed = this.sessions.remove(playerId);
        if (removed != null) {
            removed.clear();
        }
    }

    public int activeSessionCount() {
        return this.sessions.size();
    }

    @Override
    public boolean supportsConnection(final ServerGamePacketListenerImpl connection) {
        if (this.closed.get() || connection == null || connection.player == null) {
            return false;
        }
        return isNegotiated(connection.player.getUUID());
    }

    @Override
    public boolean trySendChunk(
        final ServerGamePacketListenerImpl connection,
        final ServerLevel level,
        final LevelChunk chunk,
        final boolean shouldModifyAntiXray
    ) {
        if (this.closed.get() || connection == null || connection.player == null || chunk == null) {
            return false;
        }
        if (shouldModifyAntiXray && this.antiXraySanitizer == null) {
            // Fail closed to vanilla anti-xray packet transmission rather than leaking unsanitized ore data
            return false;
        }
        if (this.outboundEmitter == null) {
            return false;
        }
        final UUID playerId = connection.player.getUUID();
        final ChunkPos pos = chunk.getPos();
        final long chunkKey = ChunkPos.pack(pos.x(), pos.z());
        final LcaChunkResidencyCache worldCache = resolveCacheForLevel(level);
        final LcaChunkResidencyCache.FrozenReadSnapshot snapshot = worldCache.getReadSnapshot(chunkKey);
        if (snapshot == null) {
            return false;
        }
        try {
            final EncodedChunkBatch encoded = encodeChunkForSession(
                playerId,
                pos.x(),
                pos.z(),
                snapshot,
                shouldModifyAntiXray,
                s -> 0
            );
            if (encoded == null) {
                return false;
            }
            return this.outboundEmitter.emitChunkBatch(connection, CHANNEL_CHUNK_BATCH, encoded.wirePayload());
        } catch (final IOException ex) {
            return false;
        }
    }

    private LcaChunkResidencyCache resolveCacheForLevel(final @Nullable ServerLevel level) {
        if (this.residencyManager != null && level != null) {
            final LcaChunkResidencyCache perWorld = this.residencyManager.getCacheIfPresent(
                level.dimension().identifier().toString()
            );
            if (perWorld != null) {
                return perWorld;
            }
        }
        return this.residencyCache;
    }

    /**
     * Pure-Java {@code LCA_NET_V1} chunk batch encoder for a negotiated player session.
     *
     * <p>Selects the minimal valid wire representation:</p>
     * <ul>
     *   <li>{@link LcaRemoteStreamCodec#ENTRY_CLEAN_ACK} (2 bytes) when the client's cached
     *       {@code SEC-DED-64} syndrome matches the current clean snapshot.</li>
     *   <li>{@link LcaRemoteStreamCodec#ENTRY_PATCH_DELTA} when {@code 1..4} sections are dirty
     *       against a client-cached baseline.</li>
     *   <li>{@link LcaRemoteStreamCodec#ENTRY_FULL_CHUNK} otherwise, downgrading {@code 0x0003}
     *       extended sections if the client lacks {@link #CAP_LCA_V3_SECTIONS}.</li>
     * </ul>
     */
    public @Nullable EncodedChunkBatch encodeChunkForSession(
        final UUID playerId,
        final int chunkX,
        final int chunkZ,
        final LcaChunkResidencyCache.FrozenReadSnapshot snapshot,
        final boolean shouldModifyAntiXray,
        final Function<String, Integer> fallbackRuntimeLookup
    ) throws IOException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(fallbackRuntimeLookup, "fallbackRuntimeLookup");

        if (this.closed.get()) {
            return null;
        }
        if (shouldModifyAntiXray && this.antiXraySanitizer == null) {
            return null;
        }

        final PlayerWireSession session = this.sessions.get(playerId);
        if (session == null || !session.negotiated.lcaStreamActive()) {
            return null;
        }

        final NegotiatedSession neg = session.negotiated;
        final long chunkKey = ChunkPos.pack(chunkX, chunkZ);
        final int localChunkIdx = LcaRemoteStreamCodec.toLocalChunkIndex10(chunkX, chunkZ);
        final RawChunkFrameSnapshot baseFrame = snapshot.baseFrame();
        final List<LcaRemoteStreamCodec.SectionWireSlice> allSlices = buildSectionWireSlices(
            baseFrame,
            snapshot,
            neg.isV3SectionsEnabled(),
            fallbackRuntimeLookup
        );
        final List<LcaRemoteStreamCodec.SectionWireSlice> wireSlices = shouldModifyAntiXray
            ? Objects.requireNonNull(
                this.antiXraySanitizer.sanitize(playerId, chunkX, chunkZ, allSlices),
                "antiXraySanitizer returned null"
            )
            : allSlices;

        final LcaRemoteStreamCodec.EntryFullChunk fullEntry = LcaRemoteStreamCodec.EntryFullChunk.createWithComputedSyndrome(
            chunkX,
            chunkZ,
            baseFrame.lastUpdateTick(),
            wireSlices,
            new byte[0]
        );
        final long currentSyndrome64 = fullEntry.secDedSyndrome64();

        final ClientChunkBaseline prior = neg.isDeltaRevisitCacheEnabled() ? session.getBaseline(chunkKey) : null;
        final LcaRemoteStreamCodec.BatchChunkEntry chosenEntry;
        if (prior != null && prior.secDedSyndrome64() == currentSyndrome64) {
            chosenEntry = new LcaRemoteStreamCodec.EntryCleanAck(localChunkIdx);
        } else if (prior != null
            && !snapshot.isClean()
            && Integer.bitCount(snapshot.dirtySectionMask()) <= LcaRemoteStreamCodec.MAX_PATCH_DELTA_SECTIONS) {
            final List<LcaRemoteStreamCodec.SectionWireSlice> dirtySlices = new ArrayList<>();
            final int dirtyMask = snapshot.dirtySectionMask();
            for (final LcaRemoteStreamCodec.SectionWireSlice slice : wireSlices) {
                if ((dirtyMask & (1 << slice.sectionIndex())) != 0) {
                    dirtySlices.add(slice);
                }
            }
            if (!dirtySlices.isEmpty() && dirtySlices.size() <= LcaRemoteStreamCodec.MAX_PATCH_DELTA_SECTIONS) {
                chosenEntry = new LcaRemoteStreamCodec.EntryPatchDelta(
                    localChunkIdx,
                    baseFrame.lastUpdateTick(),
                    currentSyndrome64,
                    dirtyMask,
                    dirtySlices
                );
            } else {
                chosenEntry = fullEntry;
            }
        } else {
            chosenEntry = fullEntry;
        }

        final int regionX = chunkX >> 5;
        final int regionZ = chunkZ >> 5;
        final boolean omitHeightmaps = neg.isClientHeightmapsEnabled();
        final boolean useLcd2 = neg.isBatchLcd2Enabled();
        byte[] wireBytes = LcaRemoteStreamCodec.encodeChunkBatch(
            regionX,
            regionZ,
            List.of(chosenEntry),
            omitHeightmaps,
            useLcd2
        );
        final boolean wrapStoredZlib = neg.isProxySafeStoredZlibEnabled();
        if (wrapStoredZlib) {
            wireBytes = wrapProxySafeStoredZlib(wireBytes);
        }

        if (neg.isDeltaRevisitCacheEnabled()) {
            session.recordBaseline(chunkKey, snapshot.epoch(), currentSyndrome64);
        }

        return new EncodedChunkBatch(
            chosenEntry.entryType(),
            currentSyndrome64,
            useLcd2,
            wrapStoredZlib,
            wireBytes
        );
    }

    /**
     * Exports a compact {@code LCAS} ({@code 0x4C434153}) inter-shard Halo delta update payload
     * from a resident {@link LcaChunkResidencyCache.FrozenReadSnapshot} ({@code goldleaf-ADR-005}
     * Stage 3, {@code leafshard-ADR-004}).
     */
    public static byte[] exportLcasHaloDelta(
        final String sourceServerId,
        final String dimension,
        final int chunkX,
        final int chunkZ,
        final long epochSequence,
        final long previousBaselineSecDed64,
        final LcaChunkResidencyCache.FrozenReadSnapshot snapshot,
        final boolean lcd2Compress,
        final Function<String, Integer> fallbackRuntimeLookup
    ) throws IOException {
        Objects.requireNonNull(sourceServerId, "sourceServerId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(fallbackRuntimeLookup, "fallbackRuntimeLookup");

        final RawChunkFrameSnapshot baseFrame = snapshot.baseFrame();
        final List<LcaRemoteStreamCodec.SectionWireSlice> slices = buildSectionWireSlices(
            baseFrame,
            snapshot,
            true,
            fallbackRuntimeLookup
        );
        final LcaRemoteStreamCodec.EntryFullChunk fullEntry = LcaRemoteStreamCodec.EntryFullChunk.createWithComputedSyndrome(
            chunkX,
            chunkZ,
            baseFrame.lastUpdateTick(),
            slices,
            new byte[0]
        );
        final LcaRemoteStreamCodec.BatchChunkEntry deltaEntry;
        final int dirtyMask = snapshot.dirtySectionMask();
        if (previousBaselineSecDed64 != 0L
            && dirtyMask != 0
            && Integer.bitCount(dirtyMask) <= LcaRemoteStreamCodec.MAX_PATCH_DELTA_SECTIONS) {
            final List<LcaRemoteStreamCodec.SectionWireSlice> dirtySlices = new ArrayList<>();
            for (final LcaRemoteStreamCodec.SectionWireSlice slice : slices) {
                if ((dirtyMask & (1 << slice.sectionIndex())) != 0) {
                    dirtySlices.add(slice);
                }
            }
            deltaEntry = dirtySlices.isEmpty()
                ? fullEntry
                : new LcaRemoteStreamCodec.EntryPatchDelta(
                    LcaRemoteStreamCodec.toLocalChunkIndex10(chunkX, chunkZ),
                    baseFrame.lastUpdateTick(),
                    fullEntry.secDedSyndrome64(),
                    dirtyMask,
                    dirtySlices
                );
        } else {
            deltaEntry = fullEntry;
        }

        final LcaRemoteStreamCodec.StreamDeltaUpdatePayload payload = new LcaRemoteStreamCodec.StreamDeltaUpdatePayload(
            sourceServerId,
            dimension,
            chunkX >> 5,
            chunkZ >> 5,
            epochSequence,
            List.of(new LcaRemoteStreamCodec.StreamChunkDeltaEntry(chunkX, chunkZ, previousBaselineSecDed64, deltaEntry))
        );
        return LcaRemoteStreamCodec.encodeDeltaUpdatePayload(payload, true, lcd2Compress);
    }

    /**
     * Wraps an already-encoded {@code LCA_NET_V1} payload in a valid RFC-1950 stored-block Zlib
     * frame ({@link Deflater#NO_COMPRESSION}) so Velocity/BungeeCord proxies do not waste CPU
     * attempting to re-compress {@code LCD2} frames.
     */
    public static byte[] wrapProxySafeStoredZlib(final byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        final Deflater deflater = new Deflater(Deflater.NO_COMPRESSION);
        try {
            deflater.setInput(payload);
            deflater.finish();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 32);
            final byte[] buf = new byte[Math.max(256, Math.min(8192, payload.length + 32))];
            while (!deflater.finished()) {
                final int written = deflater.deflate(buf);
                if (written > 0) {
                    out.write(buf, 0, written);
                }
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * Unwraps a proxy-safe stored-block Zlib frame with a hard decompressed size bound
     * ({@code S-004}).
     */
    public static byte[] unwrapProxySafeStoredZlib(final byte[] wrapped, final int maxBytes) throws IOException {
        Objects.requireNonNull(wrapped, "wrapped");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be >= 1, got " + maxBytes);
        }
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(wrapped);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(wrapped.length, maxBytes));
            final byte[] buf = new byte[4096];
            int total = 0;
            while (!inflater.finished()) {
                final int read = inflater.inflate(buf);
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IOException("Truncated or invalid proxy-safe Zlib stream");
                    }
                } else {
                    total += read;
                    if (total > maxBytes) {
                        throw new IOException("Decompressed proxy-safe Zlib frame exceeds maxBytes=" + maxBytes);
                    }
                    out.write(buf, 0, read);
                }
            }
            return out.toByteArray();
        } catch (final DataFormatException ex) {
            throw new IOException("Corrupt proxy-safe Zlib frame", ex);
        } finally {
            inflater.end();
        }
    }

    private static List<LcaRemoteStreamCodec.SectionWireSlice> buildSectionWireSlices(
        final RawChunkFrameSnapshot baseFrame,
        final LcaChunkResidencyCache.FrozenReadSnapshot snapshot,
        final boolean allowV3Sections,
        final Function<String, Integer> fallbackRuntimeLookup
    ) {
        final int sectionCount = Math.min(32, baseFrame.sectionCount());
        final List<LcaRemoteStreamCodec.SectionWireSlice> slices = new ArrayList<>(sectionCount);
        for (int i = 0; i < sectionCount; i++) {
            final int dirEntry = baseFrame.sectionDirectoryEntry16(i);
            final int[] dirtyEntries = extractDirtyEntries(snapshot, i);
            if (dirEntry == 0 || (dirEntry & 0x8000) != 0) {
                final int uniformRuntimeId = dirEntry == 0
                    ? 0
                    : baseFrame.resolveInlineUniformRuntimeStateId(i, fallbackRuntimeLookup);
                if (dirtyEntries.length == 0) {
                    slices.add(LcaRemoteStreamCodec.SectionWireSlice.uniform(i, 15, 0, uniformRuntimeId & 0x1FFF));
                } else {
                    // Uniform base with sparse dirty overlay cells: retain uniform directory word + dirty cells
                    slices.add(new LcaRemoteStreamCodec.SectionWireSlice(
                        i,
                        LcaRemoteStreamCodec.encodeUniformDirectoryWord(15, 0, uniformRuntimeId & 0x1FFF),
                        new byte[0],
                        dirtyEntries
                    ));
                }
            } else {
                byte[] sectionBytes = baseFrame.sliceSectionFrameWithRuntimePalette(i, fallbackRuntimeLookup);
                if (sectionBytes.length == 0) {
                    slices.add(LcaRemoteStreamCodec.SectionWireSlice.uniform(i, 15, 0, 0));
                } else {
                    if (!allowV3Sections && RawChunkFrameSnapshot.isV3ExtendedSectionFrame(sectionBytes)) {
                        sectionBytes = RawChunkFrameSnapshot.downgradeV3BlockSectionFrameToV2(sectionBytes);
                    }
                    slices.add(LcaRemoteStreamCodec.SectionWireSlice.nonUniform(i, sectionBytes, dirtyEntries));
                }
            }
        }
        return slices;
    }

    private static int[] extractDirtyEntries(
        final LcaChunkResidencyCache.FrozenReadSnapshot snapshot,
        final int sectionIndex
    ) {
        if (snapshot.isClean() || (snapshot.dirtySectionMask() & (1 << sectionIndex)) == 0) {
            return new int[0];
        }
        return snapshot.copyDirtyCellsForSection(sectionIndex);
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        for (final UUID playerId : this.sessions.keySet()) {
            final PlayerWireSession removed = this.sessions.remove(playerId);
            if (removed != null) {
                removed.clear();
            }
        }
    }
}
