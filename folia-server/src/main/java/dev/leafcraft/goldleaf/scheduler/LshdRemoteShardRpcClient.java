package dev.leafcraft.goldleaf.scheduler;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 {@code LSHD} ({@code 0x4C534844}) non-blocking Tier 4 remote shard chunk-read RPC
 * client and binary frame codec ({@code goldleaf-ADR-002} §2, {@code leafshard-ADR-004}).
 *
 * <p>Encodes compact {@code LSHD} v1 chunk-probe request/response frames protected by
 * {@code SEC-DED-64} syndromes ({@link LcaRemoteStreamCodec#computeSecDedSyndrome64}), tracks
 * in-flight requests in a bounded {@link ConcurrentHashMap} with timeout eviction ({@code S-004}),
 * and cancels all pending futures deterministically on {@link #close()} ({@code S-002}). Never
 * blocks the calling thread ({@code S-005}).</p>
 */
public final class LshdRemoteShardRpcClient implements RemoteShardChunkRpcClient {

    /** 32-bit big-endian magic header for LeafShard peer frames ({@code "LSHD"}). */
    public static final int PEER_FRAME_MAGIC = 0x4C534844;

    /** Binary protocol version ({@code 1}). */
    public static final byte PEER_FRAME_VERSION = 1;

    /** Frame type {@code 0x0D}: Tier 4 remote chunk-frame read request. */
    public static final byte FRAME_TIER4_CHUNK_PROBE_REQ = 0x0D;

    /** Frame type {@code 0x0E}: Tier 4 remote chunk-frame read response. */
    public static final byte FRAME_TIER4_CHUNK_PROBE_RESP = 0x0E;

    /** Response status: chunk frame present and attached. */
    public static final byte STATUS_OK = 0x00;

    /** Response status: remote shard does not own or hold the requested chunk. */
    public static final byte STATUS_NOT_FOUND = 0x01;

    /** Response status: remote shard rejected or failed the read request. */
    public static final byte STATUS_ERROR = 0x02;

    /** Maximum permitted raw chunk-frame payload size over Tier 4 RPC ({@code 4 MiB}). */
    public static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

    /** Maximum permitted dictIdToRuntimeId entries in a response frame. */
    public static final int MAX_DICT_ENTRIES = 65_536;

    /**
     * Resolves the owning remote shard ID for a chunk, returning {@code null} when the chunk is
     * locally owned or within the local halo.
     */
    @FunctionalInterface
    public interface RemoteShardLocator {
        @Nullable String resolveRemoteShardId(long chunkKey, int chunkX, int chunkZ);
    }

    /** Decoded Tier 4 {@code LSHD} chunk-probe request frame. */
    public record ProbeRequest(long requestId, int chunkX, int chunkZ) {
    }

    /** Decoded Tier 4 {@code LSHD} chunk-probe response frame. */
    public record ProbeResponse(
        long requestId,
        byte status,
        @Nullable RawChunkFrameSnapshot snapshot,
        @Nullable String errorMessage
    ) {
    }

    private final RemoteShardLocator shardLocator;
    private final BiConsumer<String, byte[]> frameSender;
    private final long timeoutMs;
    private final int maxInFlightRpcs;
    private final AtomicLong requestIdGen = new AtomicLong(1L);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ConcurrentHashMap<Long, CompletableFuture<@Nullable RawChunkFrameSnapshot>> pendingRpcs =
        new ConcurrentHashMap<>();

    public LshdRemoteShardRpcClient(
        final RemoteShardLocator shardLocator,
        final BiConsumer<String, byte[]> frameSender,
        final long timeoutMs,
        final int maxInFlightRpcs
    ) {
        this.shardLocator = Objects.requireNonNull(shardLocator, "shardLocator");
        this.frameSender = Objects.requireNonNull(frameSender, "frameSender");
        if (timeoutMs < 1L) {
            throw new IllegalArgumentException("timeoutMs must be >= 1, got " + timeoutMs);
        }
        if (maxInFlightRpcs < 1) {
            throw new IllegalArgumentException("maxInFlightRpcs must be >= 1, got " + maxInFlightRpcs);
        }
        this.timeoutMs = timeoutMs;
        this.maxInFlightRpcs = maxInFlightRpcs;
    }

    /**
     * Creates an {@link LshdRemoteShardRpcClient} from {@link GoldLeafConfig.Network} settings, or
     * returns {@code null} when {@code leafshard-tier4-rpc-enabled} is {@code false}.
     */
    public static @Nullable LshdRemoteShardRpcClient fromConfig(
        final GoldLeafConfig.Network networkConfig,
        final RemoteShardLocator shardLocator,
        final BiConsumer<String, byte[]> frameSender
    ) {
        Objects.requireNonNull(networkConfig, "networkConfig");
        if (!networkConfig.leafShardTier4RpcEnabled) {
            return null;
        }
        return new LshdRemoteShardRpcClient(
            shardLocator,
            frameSender,
            networkConfig.leafShardTier4RpcTimeoutMs,
            networkConfig.leafShardTier4MaxInFlightRpcs
        );
    }

    @Override
    public boolean isRemoteOwned(final long chunkKey, final int chunkX, final int chunkZ) {
        if (this.closed.get()) {
            return false;
        }
        final String shardId = this.shardLocator.resolveRemoteShardId(chunkKey, chunkX, chunkZ);
        return shardId != null && !shardId.isEmpty();
    }

    @Override
    public CompletableFuture<@Nullable RawChunkFrameSnapshot> fetchRemoteChunkFrame(
        final long chunkKey,
        final int chunkX,
        final int chunkZ,
        final Function<String, Integer> runtimeStateLookup
    ) {
        Objects.requireNonNull(runtimeStateLookup, "runtimeStateLookup");
        if (this.closed.get()) {
            return CompletableFuture.failedFuture(new CancellationException("LshdRemoteShardRpcClient is closed"));
        }

        final String targetShardId = this.shardLocator.resolveRemoteShardId(chunkKey, chunkX, chunkZ);
        if (targetShardId == null || targetShardId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        if (this.pendingRpcs.size() >= this.maxInFlightRpcs) {
            return CompletableFuture.failedFuture(
                new IOException("Tier 4 RPC backpressure cap reached (" + this.maxInFlightRpcs + " in-flight)")
            );
        }

        final long requestId = this.requestIdGen.getAndIncrement();
        final CompletableFuture<@Nullable RawChunkFrameSnapshot> future = new CompletableFuture<>();
        this.pendingRpcs.put(requestId, future);

        // Guard against race with concurrent close()
        if (this.closed.get()) {
            this.pendingRpcs.remove(requestId, future);
            future.cancel(true);
            return future;
        }

        final byte[] encodedRequest = encodeProbeRequest(requestId, chunkX, chunkZ);
        try {
            this.frameSender.accept(targetShardId, encodedRequest);
        } catch (final Throwable sendFailure) {
            this.pendingRpcs.remove(requestId, future);
            future.completeExceptionally(sendFailure);
            return future;
        }

        return future
            .orTimeout(this.timeoutMs, TimeUnit.MILLISECONDS)
            .whenComplete((ignored, error) -> this.pendingRpcs.remove(requestId, future));
    }

    /**
     * Handles an inbound {@code LSHD} Tier 4 chunk-probe response frame from a peer shard and
     * completes the matching pending future. Fails closed on malformed or corrupted frames
     * ({@code S-004}).
     *
     * @return {@code true} if the response matched an active pending request
     */
    public boolean onResponseFrameReceived(final byte[] lshdFrameBytes) {
        Objects.requireNonNull(lshdFrameBytes, "lshdFrameBytes");
        final ProbeResponse response;
        try {
            response = decodeProbeResponse(lshdFrameBytes);
        } catch (final IOException ex) {
            // Attempt to fail the correlated request if requestId can be extracted from header
            final long correlatedId = tryPeekRequestId(lshdFrameBytes);
            if (correlatedId > 0L) {
                final CompletableFuture<@Nullable RawChunkFrameSnapshot> pending =
                    this.pendingRpcs.remove(correlatedId);
                if (pending != null) {
                    pending.completeExceptionally(ex);
                    return true;
                }
            }
            return false;
        }

        final CompletableFuture<@Nullable RawChunkFrameSnapshot> pending =
            this.pendingRpcs.remove(response.requestId());
        if (pending == null) {
            return false;
        }

        switch (response.status()) {
            case STATUS_OK -> pending.complete(response.snapshot());
            case STATUS_NOT_FOUND -> pending.complete(null);
            default -> pending.completeExceptionally(
                new IOException(
                    "Remote shard Tier 4 RPC error (status=" + response.status() + "): "
                        + (response.errorMessage() != null ? response.errorMessage() : "unknown error")
                )
            );
        }
        return true;
    }

    public int pendingCount() {
        return this.pendingRpcs.size();
    }

    public boolean isClosed() {
        return this.closed.get();
    }

    /**
     * Marks this client closed and deterministically cancels all in-flight RPC futures
     * ({@code S-002}). Idempotent.
     */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        for (final Long requestId : this.pendingRpcs.keySet()) {
            final CompletableFuture<@Nullable RawChunkFrameSnapshot> pending = this.pendingRpcs.remove(requestId);
            if (pending != null) {
                pending.cancel(true);
            }
        }
    }

    // =========================================================================
    // LSHD Tier 4 Binary Wire Codec (0x4C534844, version 1, SEC-DED-64)
    // =========================================================================

    /**
     * Encodes an {@code LSHD} v1 Tier 4 chunk-probe request frame (22 bytes).
     */
    public static byte[] encodeProbeRequest(final long requestId, final int chunkX, final int chunkZ) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(22);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(PEER_FRAME_MAGIC);
            out.writeByte(PEER_FRAME_VERSION);
            out.writeByte(FRAME_TIER4_CHUNK_PROBE_REQ);
            out.writeLong(requestId);
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
        } catch (final IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return baos.toByteArray();
    }

    /**
     * Decodes an {@code LSHD} v1 Tier 4 chunk-probe request frame, failing closed on bad magic,
     * version, frame type, or truncation ({@code S-004}).
     */
    public static ProbeRequest decodeProbeRequest(final byte[] frameBytes) throws IOException {
        Objects.requireNonNull(frameBytes, "frameBytes");
        if (frameBytes.length != 22) {
            throw new IOException("Invalid LSHD Tier 4 request length: " + frameBytes.length);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frameBytes))) {
            validateHeader(in, FRAME_TIER4_CHUNK_PROBE_REQ);
            final long requestId = in.readLong();
            final int chunkX = in.readInt();
            final int chunkZ = in.readInt();
            return new ProbeRequest(requestId, chunkX, chunkZ);
        }
    }

    /**
     * Encodes an {@code LSHD} v1 Tier 4 chunk-probe response carrying a {@link RawChunkFrameSnapshot}
     * protected by a {@code SEC-DED-64} syndrome.
     */
    public static byte[] encodeProbeResponseOk(
        final long requestId,
        final RawChunkFrameSnapshot snapshot
    ) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        final byte[] rawFrame = snapshot.chunkFrame();
        if (rawFrame.length > MAX_FRAME_BYTES) {
            throw new IOException("Chunk frame exceeds MAX_FRAME_BYTES: " + rawFrame.length);
        }
        final int[] dictMap = snapshot.dictIdToRuntimeId() != null ? snapshot.dictIdToRuntimeId() : new int[0];
        if (dictMap.length > MAX_DICT_ENTRIES) {
            throw new IOException("dictIdToRuntimeId exceeds MAX_DICT_ENTRIES: " + dictMap.length);
        }
        final long syndrome64 = LcaRemoteStreamCodec.computeSecDedSyndrome64(requestId, rawFrame);

        final ByteArrayOutputStream baos = new ByteArrayOutputStream(64 + rawFrame.length + (dictMap.length << 2));
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(PEER_FRAME_MAGIC);
            out.writeByte(PEER_FRAME_VERSION);
            out.writeByte(FRAME_TIER4_CHUNK_PROBE_RESP);
            out.writeLong(requestId);
            out.writeByte(STATUS_OK);
            out.writeInt(snapshot.dataVersion());
            out.writeInt(snapshot.minSectionY());
            out.writeInt(snapshot.sectionCount());
            out.writeInt(snapshot.residualNbtRelOffset());
            out.writeInt(snapshot.formatVersion());
            out.writeLong(syndrome64);
            out.writeInt(rawFrame.length);
            out.write(rawFrame);
            out.writeInt(dictMap.length);
            for (final int runtimeId : dictMap) {
                out.writeInt(runtimeId);
            }
        }
        return baos.toByteArray();
    }

    /**
     * Encodes an {@code LSHD} v1 Tier 4 chunk-probe {@link #STATUS_NOT_FOUND} response.
     */
    public static byte[] encodeProbeResponseNotFound(final long requestId) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(15);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(PEER_FRAME_MAGIC);
            out.writeByte(PEER_FRAME_VERSION);
            out.writeByte(FRAME_TIER4_CHUNK_PROBE_RESP);
            out.writeLong(requestId);
            out.writeByte(STATUS_NOT_FOUND);
        } catch (final IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return baos.toByteArray();
    }

    /**
     * Encodes an {@code LSHD} v1 Tier 4 chunk-probe {@link #STATUS_ERROR} response.
     */
    public static byte[] encodeProbeResponseError(final long requestId, final String errorMessage) {
        final String safeMsg = errorMessage == null ? "remote error" : errorMessage;
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(32 + safeMsg.length());
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(PEER_FRAME_MAGIC);
            out.writeByte(PEER_FRAME_VERSION);
            out.writeByte(FRAME_TIER4_CHUNK_PROBE_RESP);
            out.writeLong(requestId);
            out.writeByte(STATUS_ERROR);
            out.writeUTF(safeMsg);
        } catch (final IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return baos.toByteArray();
    }

    /**
     * Decodes and {@code SEC-DED-64}-verifies an {@code LSHD} v1 Tier 4 chunk-probe response frame.
     * Fails closed on corrupt magic, version, bounds, or syndrome mismatch ({@code S-004}).
     */
    public static ProbeResponse decodeProbeResponse(final byte[] frameBytes) throws IOException {
        Objects.requireNonNull(frameBytes, "frameBytes");
        if (frameBytes.length < 15) {
            throw new IOException("Truncated LSHD Tier 4 response (" + frameBytes.length + " bytes)");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frameBytes))) {
            validateHeader(in, FRAME_TIER4_CHUNK_PROBE_RESP);
            final long requestId = in.readLong();
            final byte status = in.readByte();
            if (status == STATUS_NOT_FOUND) {
                return new ProbeResponse(requestId, STATUS_NOT_FOUND, null, null);
            }
            if (status == STATUS_ERROR) {
                final String msg = in.readUTF();
                return new ProbeResponse(requestId, STATUS_ERROR, null, msg);
            }
            if (status != STATUS_OK) {
                throw new IOException("Unknown LSHD Tier 4 response status: " + status);
            }

            final int dataVersion = in.readInt();
            final int minSectionY = in.readInt();
            final int sectionCount = in.readInt();
            final int residualNbtRelOffset = in.readInt();
            final int formatVersion = in.readInt();
            final long expectedSyndrome64 = in.readLong();
            final int frameLen = in.readInt();
            if (frameLen < 24 || frameLen > MAX_FRAME_BYTES) {
                throw new IOException("Invalid LSHD Tier 4 chunkFrame length: " + frameLen);
            }
            final byte[] rawFrame = in.readNBytes(frameLen);
            if (rawFrame.length != frameLen) {
                throw new IOException("Truncated LSHD Tier 4 chunkFrame payload: expected " + frameLen + ", got " + rawFrame.length);
            }
            if (!LcaRemoteStreamCodec.verifySecDedSyndrome64(requestId, rawFrame, expectedSyndrome64)) {
                throw new IOException("SEC-DED-64 checksum mismatch on LSHD Tier 4 response (requestId=" + requestId + ")");
            }
            final int dictLen = in.readInt();
            if (dictLen < 0 || dictLen > MAX_DICT_ENTRIES) {
                throw new IOException("Invalid LSHD Tier 4 dictIdToRuntimeId length: " + dictLen);
            }
            final int[] dictMap = new int[dictLen];
            for (int i = 0; i < dictLen; i++) {
                dictMap[i] = in.readInt();
            }
            final RawChunkFrameSnapshot snapshot;
            try {
                snapshot = new RawChunkFrameSnapshot(
                    rawFrame,
                    dataVersion,
                    minSectionY,
                    sectionCount,
                    residualNbtRelOffset,
                    dictMap,
                    formatVersion
                );
            } catch (final IllegalArgumentException ex) {
                throw new IOException("Invalid RawChunkFrameSnapshot in LSHD Tier 4 response", ex);
            }
            return new ProbeResponse(requestId, STATUS_OK, snapshot, null);
        }
    }

    private static void validateHeader(final DataInputStream in, final byte expectedType) throws IOException {
        final int magic = in.readInt();
        if (magic != PEER_FRAME_MAGIC) {
            throw new IOException("Invalid LSHD magic: 0x" + Integer.toHexString(magic));
        }
        final byte version = in.readByte();
        if (version != PEER_FRAME_VERSION) {
            throw new IOException("Unsupported LSHD version: " + version);
        }
        final byte frameType = in.readByte();
        if (frameType != expectedType) {
            throw new IOException("Unexpected LSHD frame type: expected " + expectedType + ", got " + frameType);
        }
    }

    private static long tryPeekRequestId(final byte[] frameBytes) {
        if (frameBytes.length < 14) {
            return -1L;
        }
        final int magic = ((frameBytes[0] & 0xFF) << 24)
            | ((frameBytes[1] & 0xFF) << 16)
            | ((frameBytes[2] & 0xFF) << 8)
            | (frameBytes[3] & 0xFF);
        if (magic != PEER_FRAME_MAGIC || frameBytes[4] != PEER_FRAME_VERSION || frameBytes[5] != FRAME_TIER4_CHUNK_PROBE_RESP) {
            return -1L;
        }
        long id = 0L;
        for (int i = 6; i < 14; i++) {
            id = (id << 8) | (frameBytes[i] & 0xFFL);
        }
        return id;
    }

    static boolean isTimeoutFailure(final @Nullable Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof TimeoutException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
