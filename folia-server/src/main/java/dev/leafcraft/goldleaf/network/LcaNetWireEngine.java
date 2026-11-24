package dev.leafcraft.goldleaf.network;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.storage.GoldLeafModeABlockSectionBacking;
import dev.leafcraft.goldleaf.storage.LcaChunkResidencyCache;
import dev.leafcraft.goldleaf.storage.LcaDirectSectionSerializer;
import dev.leafcraft.goldleaf.storage.LcaResidencyManager;
import io.github.dailystruggle.anvil.LcaModeABlockSection;
import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import io.github.dailystruggle.anvil.stream.LcaOrderlessDictWireTable;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        public boolean isOrderlessDictAndMulticastEnabled() {
            return hasCapability(CAP_ORDERLESS_DICT_AND_MULTICAST);
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
        private final LcaOrderlessDictWireTable.KnownSlotBitSet knownDictSlots =
            new LcaOrderlessDictWireTable.KnownSlotBitSet();

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
            this.knownDictSlots.clear();
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(LcaNetWireEngine.class);
    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private static final ThreadLocal<int[]> LIVE_SECTION_VOXELS_SCRATCH = ThreadLocal.withInitial(() -> new int[4096]);

    private final LcaChunkResidencyCache residencyCache;
    private final @Nullable LcaResidencyManager residencyManager;
    private final int serverCapabilities;
    private final long serverRegistryParityHash64;
    private volatile long lazyRegistryParityHash64;
    private final int maxSessionCacheChunks;
    private final @Nullable ChunkSectionSanitizer antiXraySanitizer;
    private final @Nullable OutboundBatchEmitter outboundEmitter;
    private final LcaOrderlessDictWireTable orderlessDict = new LcaOrderlessDictWireTable();
    private final ConcurrentHashMap<UUID, PlayerWireSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong handshakesAccepted = new AtomicLong();
    private final AtomicLong handshakesRejected = new AtomicLong();
    private final AtomicLong chunksSentLca = new AtomicLong();
    private final AtomicLong chunksSentFromResidency = new AtomicLong();
    private final AtomicLong chunksSentFromLiveChunk = new AtomicLong();
    private final AtomicLong chunksFallbackVanilla = new AtomicLong();

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
        int caps = CAP_LCA_SECTION_STREAM
            | CAP_CLIENT_HEIGHTMAPS
            | CAP_DELTA_REVISIT_CACHE
            | CAP_ORDERLESS_DICT_AND_MULTICAST
            | CAP_LCA_V3_SECTIONS;
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
     * Computes the deterministic 64-bit FNV-1a parity hash over {@link Block#BLOCK_STATE_REGISTRY}
     * matching {@code FabricLeafAnvilClientMod#computeBlockStateRegistryParityHash()}
     * ({@code leafanvil-ADR-003} Section 1).
     */
    public static long computeBlockStateRegistryParityHash() {
        long hash = 0xCBF29CE484222325L;
        final int size = Block.BLOCK_STATE_REGISTRY.size();
        hash = (hash ^ size) * 0x100000001B3L;
        for (int id = 0; id < size; id++) {
            final BlockState state = Block.stateById(id);
            final int actualId = Block.getId(state);
            hash = (hash ^ (((long) id << 32) | (actualId & 0xFFFFFFFFL))) * 0x100000001B3L;
        }
        return hash;
    }

    /**
     * Resolves the server's 64-bit block-state registry parity hash, lazily computing it from
     * {@link Block#BLOCK_STATE_REGISTRY} when constructed with {@code 0L}.
     */
    public long resolveServerRegistryParityHash64() {
        if (this.serverRegistryParityHash64 != 0L) {
            return this.serverRegistryParityHash64;
        }
        long cached = this.lazyRegistryParityHash64;
        if (cached != 0L) {
            return cached;
        }
        if (Block.BLOCK_STATE_REGISTRY.size() <= 0) {
            return 0L;
        }
        cached = computeBlockStateRegistryParityHash();
        this.lazyRegistryParityHash64 = cached;
        return cached;
    }

    /**
     * Encodes the 20-byte server {@code leafanvil:chunk_stream_handshake} ACK payload for a
     * negotiated session (or {@code caps=0} fallback ACK).
     */
    public static byte[] buildHandshakeAckBytes(final NegotiatedSession session) {
        Objects.requireNonNull(session, "session");
        return new HandshakePayload(
            PROTOCOL_VERSION,
            session.lcaStreamActive() ? session.activeCapabilities() : 0,
            session.negotiatedCacheBudgetKiB(),
            session.registryParityHash64()
        ).toBytes();
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
            this.handshakesRejected.incrementAndGet();
            return NegotiatedSession.VANILLA_FALLBACK;
        }
        final long serverHash = resolveServerRegistryParityHash64();
        final NegotiatedSession negotiated = negotiate(
            clientPayload,
            this.serverCapabilities,
            serverHash
        );
        if (!negotiated.lcaStreamActive()) {
            unregisterConnection(playerId);
            this.handshakesRejected.incrementAndGet();
            return NegotiatedSession.VANILLA_FALLBACK;
        }
        this.sessions.put(playerId, new PlayerWireSession(negotiated, this.maxSessionCacheChunks));
        this.handshakesAccepted.incrementAndGet();
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

    public LcaOrderlessDictWireTable orderlessDict() {
        return this.orderlessDict;
    }

    /**
     * Resets a player's known orderless dictionary bitset and builds a full {@code ODCT}
     * dictionary snapshot for deterministic resynchronization.
     */
    public byte @Nullable [] resetAndBuildFullDictionarySnapshot(final UUID playerId) throws IOException {
        final PlayerWireSession session = this.sessions.get(playerId);
        if (session == null || !session.negotiated.lcaStreamActive()) {
            return null;
        }
        session.knownDictSlots.clear();
        return this.orderlessDict.encodeFullDictionarySnapshot(session.knownDictSlots);
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
            this.chunksFallbackVanilla.incrementAndGet();
            return false;
        }
        if (this.outboundEmitter == null) {
            return false;
        }
        final UUID playerId = connection.player.getUUID();
        final PlayerWireSession session = this.sessions.get(playerId);
        if (session == null || !session.negotiated.lcaStreamActive()) {
            return false;
        }
        final ChunkPos pos = chunk.getPos();
        final long chunkKey = ChunkPos.pack(pos.x(), pos.z());
        final LcaChunkResidencyCache worldCache = resolveCacheForLevel(level);
        final LcaChunkResidencyCache.FrozenReadSnapshot snapshot;
        if (chunk.goldleaf$isGeneratedFresh()) {
            // Freshly promoted ProtoChunk -> LevelChunk must always encode live sections, never a stale pre-FULL snapshot.
            worldCache.markUnloadedAndEvict(chunkKey);
            snapshot = null;
        } else {
            snapshot = worldCache.getReadSnapshot(chunkKey);
        }
        try {
            final byte[] wireHeightmaps = encodeClientHeightmaps(chunk);
            final EncodedChunkBatch encoded;
            final boolean fromResidency;
            if (snapshot != null) {
                encoded = encodeChunkForSessionInternal(
                    playerId,
                    pos.x(),
                    pos.z(),
                    snapshot,
                    shouldModifyAntiXray,
                    LcaDirectSectionSerializer.canonicalToRuntimeStateLookup(),
                    false,
                    true,
                    wireHeightmaps
                );
                fromResidency = true;
            } else {
                encoded = encodeLiveChunkForSession(
                    playerId,
                    chunk,
                    shouldModifyAntiXray,
                    wireHeightmaps
                );
                fromResidency = false;
            }
            if (encoded == null) {
                this.chunksFallbackVanilla.incrementAndGet();
                return false;
            }
            if (!this.outboundEmitter.emitChunkBatch(connection, CHANNEL_CHUNK_BATCH, encoded.wirePayload())) {
                this.chunksFallbackVanilla.incrementAndGet();
                return false;
            }
            // Deliver biomes, lighting, and block-entity state in strict TCP FIFO order right after
            // the LCA_NET_V1 block-section batch so the client hydrates full chunk fidelity without
            // needing ClientboundLevelChunkWithLightPacket.
            connection.send(ClientboundChunksBiomesPacket.forChunks(List.of(chunk)));
            connection.send(new ClientboundLightUpdatePacket(pos, level.getLightEngine(), null, null));
            final Map<?, BlockEntity> blockEntities = chunk.getBlockEntities();
            if (blockEntities != null && !blockEntities.isEmpty()) {
                for (final BlockEntity blockEntity : blockEntities.values()) {
                    if (blockEntity != null) {
                        final Packet<ClientGamePacketListener> updatePacket = blockEntity.getUpdatePacket();
                        if (updatePacket != null) {
                            connection.send(updatePacket);
                        }
                    }
                }
            }
            this.chunksSentLca.incrementAndGet();
            if (fromResidency) {
                this.chunksSentFromResidency.incrementAndGet();
            } else {
                this.chunksSentFromLiveChunk.incrementAndGet();
            }
            return true;
        } catch (final IOException | RuntimeException ex) {
            LOGGER.warn(
                "[GoldLeaf-LcaNet] Failed encoding LCA_NET_V1 chunk ({}, {}); falling back to vanilla packet",
                pos.x(), pos.z(), ex
            );
            this.chunksFallbackVanilla.incrementAndGet();
            return false;
        }
    }

    public @Nullable EncodedChunkBatch encodeLiveChunkForSession(
        final UUID playerId,
        final LevelChunk chunk,
        final boolean shouldModifyAntiXray
    ) throws IOException {
        return encodeLiveChunkForSession(playerId, chunk, shouldModifyAntiXray, encodeClientHeightmaps(chunk));
    }

    private @Nullable EncodedChunkBatch encodeLiveChunkForSession(
        final UUID playerId,
        final LevelChunk chunk,
        final boolean shouldModifyAntiXray,
        final byte[] heightmapBytes
    ) throws IOException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(chunk, "chunk");
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
        final List<LcaRemoteStreamCodec.SectionWireSlice> rawSlices = buildSectionWireSlicesFromLiveChunk(chunk);
        if (rawSlices == null || rawSlices.isEmpty()) {
            return null;
        }
        final ChunkPos pos = chunk.getPos();
        final List<LcaRemoteStreamCodec.SectionWireSlice> wireSlices = shouldModifyAntiXray
            ? Objects.requireNonNull(this.antiXraySanitizer).sanitize(playerId, pos.x(), pos.z(), rawSlices)
            : rawSlices;
        final NegotiatedSession neg = session.negotiated;
        final byte[] hmBytes = heightmapBytes == null ? new byte[0] : heightmapBytes;
        final LcaRemoteStreamCodec.EntryFullChunk fullEntry = LcaRemoteStreamCodec.EntryFullChunk.createWithComputedSyndrome(
            pos.x(),
            pos.z(),
            0L,
            wireSlices,
            hmBytes
        );
        final long currentSyndrome64 = fullEntry.secDedSyndrome64();
        final boolean omitHeightmaps = hmBytes.length == 0 && neg.isClientHeightmapsEnabled();
        final boolean useLcd2 = neg.isBatchLcd2Enabled();
        final boolean useOrderlessDict = neg.isOrderlessDictAndMulticastEnabled();
        final byte[] wireBytes = LcaRemoteStreamCodec.encodeChunkBatchDirect(
            pos.x() >> 5,
            pos.z() >> 5,
            List.of(fullEntry),
            omitHeightmaps,
            useLcd2,
            useOrderlessDict ? this.orderlessDict : null,
            useOrderlessDict ? session.knownDictSlots : null
        );
        if (neg.isDeltaRevisitCacheEnabled()) {
            session.recordBaseline(ChunkPos.pack(pos.x(), pos.z()), 0L, currentSyndrome64);
        }
        return new EncodedChunkBatch(
            fullEntry.entryType(),
            currentSyndrome64,
            useLcd2,
            false,
            wireBytes
        );
    }

    private static final Heightmap.Types[] CLIENT_WIRE_HEIGHTMAP_TYPES = {
        Heightmap.Types.WORLD_SURFACE,
        Heightmap.Types.MOTION_BLOCKING,
        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
        Heightmap.Types.OCEAN_FLOOR
    };

    /**
     * Encodes the chunk's 4 client-facing heightmaps in compact binary wire format
     * ({@code [magic='H', version=1, count, (typeId, longCountBE16, longsBE64[])*]}) so the
     * {@code LCA_NET_V1} client installs them in O(1) without scanning 98,304 voxels on the render thread.
     */
    public static byte[] encodeClientHeightmaps(final LevelChunk chunk) {
        if (chunk == null) {
            return new byte[0];
        }
        int presentCount = 0;
        int totalBytes = 3;
        final long[][] rawArrays = new long[CLIENT_WIRE_HEIGHTMAP_TYPES.length][];
        for (int i = 0; i < CLIENT_WIRE_HEIGHTMAP_TYPES.length; i++) {
            final Heightmap.Types type = CLIENT_WIRE_HEIGHTMAP_TYPES[i];
            if (chunk.hasPrimedHeightmap(type)) {
                final long[] raw = chunk.getOrCreateHeightmapUnprimed(type).getRawData();
                if (raw != null && raw.length > 0 && raw.length <= Short.MAX_VALUE) {
                    rawArrays[i] = raw;
                    presentCount++;
                    totalBytes += 3 + (raw.length << 3);
                }
            }
        }
        if (presentCount == 0) {
            return new byte[0];
        }
        final ByteBuffer buf = ByteBuffer.allocate(totalBytes);
        buf.put((byte) 'H');
        buf.put((byte) 1);
        buf.put((byte) presentCount);
        for (int i = 0; i < rawArrays.length; i++) {
            final long[] raw = rawArrays[i];
            if (raw == null) {
                continue;
            }
            buf.put((byte) i);
            buf.putShort((short) raw.length);
            for (final long word : raw) {
                buf.putLong(word);
            }
        }
        return buf.array();
    }

    private static @Nullable List<LcaRemoteStreamCodec.SectionWireSlice> buildSectionWireSlicesFromLiveChunk(
        final LevelChunk chunk
    ) {
        final LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return null;
        }
        final int sectionCount = Math.min(32, sections.length);
        final List<LcaRemoteStreamCodec.SectionWireSlice> slices = new ArrayList<>(sectionCount);
        int[] voxels4096 = null;
        for (int i = 0; i < sectionCount; i++) {
            final LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir()) {
                slices.add(LcaRemoteStreamCodec.SectionWireSlice.uniform(i, 15, 0, 0));
                continue;
            }
            final PalettedContainer<BlockState> states = sec.getStates();
            // Fast path 1: already-compressed Mode A cold backing -> direct byte copy + sparse dirty overlay
            // (zero voxel decompression, zero Hilbert/Delta-Roaring re-encode).
            final var coldBacking = states.paper$getColdBacking();
            if (coldBacking instanceof GoldLeafModeABlockSectionBacking modeABacking) {
                final byte[] modeAWire = modeABacking.baseSection().toWireSectionFrameBytes();
                if (modeAWire.length > 0 && modeAWire.length <= 0x7FFF) {
                    final int[] dirtyOverlay = modeABacking.exportDirtyOverlayEntries();
                    slices.add(LcaRemoteStreamCodec.SectionWireSlice.nonUniform(i, modeAWire, dirtyOverlay));
                    continue;
                }
            }
            if (coldBacking instanceof dev.leafcraft.goldleaf.storage.GoldLeafFlatBlockSectionBacking flatBacking) {
                final byte[] flatWire = flatBacking.toWireSectionFrameBytes();
                if (flatWire != null && flatWire.length > 0 && flatWire.length <= 0x7FFF) {
                    final int[] dirtyOverlay = flatBacking.exportDirtyOverlayEntries();
                    slices.add(LcaRemoteStreamCodec.SectionWireSlice.nonUniform(i, flatWire, dirtyOverlay));
                    continue;
                }
            }
            // Fast path 2: uniform SingleValuePalette / ZeroBitStorage -> O(1) 2-byte uniform slice.
            final PalettedContainer.Data<BlockState> data = states.data;
            final BitStorage storage = data.storage();
            final Palette<BlockState> palette = data.palette();
            final int palSize = palette.getSize();
            if (storage instanceof ZeroBitStorage || palSize <= 1) {
                final BlockState single = palette.valueFor(0);
                final int singleId = single == null ? 0 : Block.getId(single);
                if (singleId < 0 || singleId > 0x1FFF) {
                    return null;
                }
                slices.add(LcaRemoteStreamCodec.SectionWireSlice.uniform(i, 15, 0, singleId));
                continue;
            }
            // Fast path 3: hot SimpleBitStorage (e.g. GPU Pass 5 4-bit packed sections) -> direct
            // BLOCK_MODE_FLAT_BITPACK wire slice without unpacking 4,096 BlockStates or Hilbert search.
            final int storageBits = storage.getBits();
            if (storage instanceof SimpleBitStorage simpleStorage
                && storageBits >= 4
                && storageBits <= 8
                && palSize >= 2
                && palSize <= 256) {
                final int[] paletteIds = new int[palSize];
                boolean palOk = true;
                for (int p = 0; p < palSize; p++) {
                    final BlockState st = palette.valueFor(p);
                    final int stId = st == null ? 0 : Block.getId(st);
                    if (stId < 0 || stId > LcaSectionBacking.MAX_STATE_ID_19) {
                        palOk = false;
                        break;
                    }
                    paletteIds[p] = stId;
                }
                if (palOk) {
                    final byte[] flatWire = LcaModeABlockSection.encodeFlatBitpackFromSimpleBitStorage(
                        paletteIds,
                        storageBits,
                        simpleStorage.getRaw()
                    );
                    if (flatWire != null && flatWire.length > 0 && flatWire.length <= 0x7FFF) {
                        slices.add(LcaRemoteStreamCodec.SectionWireSlice.nonUniform(i, flatWire, EMPTY_INT_ARRAY));
                        continue;
                    }
                }
            }
            // Rare fallback (GlobalPalette > 256 unique states in one section): standard compact encoder.
            if (voxels4096 == null) {
                voxels4096 = LIVE_SECTION_VOXELS_SCRATCH.get();
            }
            int firstId = -1;
            boolean allSame = true;
            int idx = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final int stateId = Block.getId(sec.getBlockState(x, y, z));
                        voxels4096[idx++] = stateId;
                        if (firstId < 0) {
                            firstId = stateId;
                        } else if (stateId != firstId) {
                            allSame = false;
                        }
                    }
                }
            }
            if (allSame) {
                if (firstId < 0 || firstId > 0x1FFF) {
                    return null;
                }
                slices.add(LcaRemoteStreamCodec.SectionWireSlice.uniform(i, 15, 0, firstId));
                continue;
            }
            final LcaSectionBacking.EncodedCompactFrame compact = LcaSectionBacking.encodeCompactSectionFrame(
                voxels4096, 15, 0
            );
            final byte[] frameBytes = compact.frameBytes();
            if (frameBytes.length == 0) {
                final int maj = compact.majorityStateId();
                if (maj < 0 || maj > 0x1FFF) {
                    return null;
                }
                slices.add(LcaRemoteStreamCodec.SectionWireSlice.uniform(i, 15, 0, maj));
            } else {
                slices.add(LcaRemoteStreamCodec.SectionWireSlice.nonUniform(i, frameBytes, EMPTY_INT_ARRAY));
            }
        }
        return slices;
    }

    public String formatTelemetrySummary() {
        return String.format(
            java.util.Locale.ROOT,
            "[GoldLeaf-LcaNet] handshakesAccepted=%d handshakesRejected=%d activeSessions=%d "
                + "chunksSentLca=%d (residency=%d liveChunk=%d) fallbackVanilla=%d",
            this.handshakesAccepted.get(),
            this.handshakesRejected.get(),
            this.sessions.size(),
            this.chunksSentLca.get(),
            this.chunksSentFromResidency.get(),
            this.chunksSentFromLiveChunk.get(),
            this.chunksFallbackVanilla.get()
        );
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
        return encodeChunkForSessionInternal(
            playerId,
            chunkX,
            chunkZ,
            snapshot,
            shouldModifyAntiXray,
            fallbackRuntimeLookup,
            true,
            false,
            new byte[0]
        );
    }

    private @Nullable EncodedChunkBatch encodeChunkForSessionInternal(
        final UUID playerId,
        final int chunkX,
        final int chunkZ,
        final LcaChunkResidencyCache.FrozenReadSnapshot snapshot,
        final boolean shouldModifyAntiXray,
        final Function<String, Integer> fallbackRuntimeLookup,
        final boolean allowProxyStoredZlibWrapper,
        final boolean allowOrderlessDict,
        final byte[] heightmapBytes
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

        final byte[] hmBytes = heightmapBytes == null ? new byte[0] : heightmapBytes;
        final LcaRemoteStreamCodec.EntryFullChunk fullEntry = LcaRemoteStreamCodec.EntryFullChunk.createWithComputedSyndrome(
            chunkX,
            chunkZ,
            baseFrame.lastUpdateTick(),
            wireSlices,
            hmBytes
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
        final boolean omitHeightmaps = hmBytes.length == 0 && neg.isClientHeightmapsEnabled();
        final boolean useLcd2 = neg.isBatchLcd2Enabled();
        final boolean useOrderlessDict = allowOrderlessDict && neg.isOrderlessDictAndMulticastEnabled();
        byte[] wireBytes = LcaRemoteStreamCodec.encodeChunkBatchDirect(
            regionX,
            regionZ,
            List.of(chosenEntry),
            omitHeightmaps,
            useLcd2,
            useOrderlessDict ? this.orderlessDict : null,
            useOrderlessDict ? session.knownDictSlots : null
        );
        final boolean wrapStoredZlib = allowProxyStoredZlibWrapper && neg.isProxySafeStoredZlibEnabled();
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
