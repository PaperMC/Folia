package dev.leafcraft.goldleaf.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 wire-format codecs and dirty-checked state helpers for opt-in technical client protocols
 * ({@code goldleaf-ADR-010} §2): {@code AppleSkin}, {@code Map World-ID} (Xaero/JourneyMap),
 * {@code Servux}, {@code Syncmatica}, and {@code Jade}.
 *
 * <p>All decoders are fail-closed: truncated, negative-length, or out-of-bounds payloads return
 * {@code null} rather than throwing uncaught exceptions or allocating unbounded buffers
 * ({@code S-004}, {@code ADR-102}).</p>
 */
public final class TechnicalProtocolCodecs {

    public static final String CHANNEL_SERVUX_STRUCTURES = "servux:structures";
    public static final String CHANNEL_SYNCMATICA_MAIN = "syncmatica:main";
    public static final String CHANNEL_JADE_REQUEST = "jade:request_tile";
    public static final String CHANNEL_APPLESKIN_SATURATION = "appleskin:saturation_sync";
    public static final String CHANNEL_APPLESKIN_EXHAUSTION = "appleskin:exhaustion_sync";
    public static final String CHANNEL_XAERO_WORLD_ID = "xaerominimap:main";

    private static final int MAX_STRING_BYTES = 1024;

    private TechnicalProtocolCodecs() {
    }

    // ---- AppleSkin (dirty-checked delta, zero allocations when unchanged) ----

    public static final class AppleSkinTracker {
        private float lastSaturation = Float.NaN;
        private float lastExhaustion = Float.NaN;

        public byte @Nullable [] encodeSaturationDelta(final float saturation) {
            if (!Float.isFinite(saturation) || Float.floatToRawIntBits(saturation) == Float.floatToRawIntBits(this.lastSaturation)) {
                return null;
            }
            this.lastSaturation = saturation;
            return encodeFloat(saturation);
        }

        public byte @Nullable [] encodeExhaustionDelta(final float exhaustion) {
            if (!Float.isFinite(exhaustion) || Float.floatToRawIntBits(exhaustion) == Float.floatToRawIntBits(this.lastExhaustion)) {
                return null;
            }
            this.lastExhaustion = exhaustion;
            return encodeFloat(exhaustion);
        }

        public void reset() {
            this.lastSaturation = Float.NaN;
            this.lastExhaustion = Float.NaN;
        }
    }

    public static byte[] encodeFloat(final float value) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(value).array();
    }

    public static @Nullable Float decodeFloat(final byte @Nullable [] payload) {
        if (payload == null || payload.length != 4) {
            return null;
        }
        final float val = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getFloat();
        return Float.isFinite(val) ? val : null;
    }

    // ---- Map World-ID (Xaero / JourneyMap deterministic world/shard ID) ----

    public static int deterministicWorldId(final UUID worldUuid, final @Nullable String shardId) {
        Objects.requireNonNull(worldUuid, "worldUuid");
        final long hi = worldUuid.getMostSignificantBits();
        final long lo = worldUuid.getLeastSignificantBits();
        int hash = (int) (hi ^ (hi >>> 32) ^ lo ^ (lo >>> 32));
        if (shardId != null && !shardId.isEmpty()) {
            hash = 31 * hash + shardId.hashCode();
        }
        return hash;
    }

    public static byte[] encodeXaeroWorldId(final int worldId) {
        return ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            .put((byte) 0) // Xaero level-id packet discriminator
            .putInt(worldId)
            .array();
    }

    public static @Nullable Integer decodeXaeroWorldId(final byte @Nullable [] payload) {
        if (payload == null || payload.length != 5 || payload[0] != 0) {
            return null;
        }
        return ByteBuffer.wrap(payload, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    // ---- Servux Structure Query (bounded radius) ----

    public record ServuxStructureQuery(int protocolVersion, int centerChunkX, int centerChunkZ, int radiusChunks) {
    }

    public static byte[] encodeServuxStructureQuery(final ServuxStructureQuery query) {
        Objects.requireNonNull(query, "query");
        return ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putInt(query.protocolVersion())
            .putInt(query.centerChunkX())
            .putInt(query.centerChunkZ())
            .putInt(query.radiusChunks())
            .array();
    }

    public static @Nullable ServuxStructureQuery decodeServuxStructureQuery(
        final byte @Nullable [] payload,
        final int maxAllowedRadius
    ) {
        if (payload == null || payload.length != 16 || maxAllowedRadius < 1) {
            return null;
        }
        final ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        final int version = buf.getInt();
        final int cx = buf.getInt();
        final int cz = buf.getInt();
        final int radius = buf.getInt();
        if (version < 1 || radius < 1 || radius > maxAllowedRadius) {
            return null;
        }
        return new ServuxStructureQuery(version, cx, cz, radius);
    }

    // ---- Jade Block/Entity Inspection Request (reach-bounded) ----

    public record JadeInspectRequest(int blockX, int blockY, int blockZ, int entityId) {
    }

    public static byte[] encodeJadeInspectRequest(final JadeInspectRequest request) {
        Objects.requireNonNull(request, "request");
        return ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putInt(request.blockX())
            .putInt(request.blockY())
            .putInt(request.blockZ())
            .putInt(request.entityId())
            .array();
    }

    public static @Nullable JadeInspectRequest decodeJadeInspectRequest(final byte @Nullable [] payload) {
        if (payload == null || payload.length != 16) {
            return null;
        }
        final ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        return new JadeInspectRequest(buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt());
    }

    // ---- Syncmatica Placement Header (size-capped) ----

    public record SyncmaticaPlacementHeader(UUID placementId, String name, int fileBytes) {
    }

    public static byte[] encodeSyncmaticaHeader(final SyncmaticaPlacementHeader header) {
        Objects.requireNonNull(header, "header");
        final byte[] nameBytes = header.name().getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("Syncmatica placement name exceeds " + MAX_STRING_BYTES + " UTF-8 bytes");
        }
        return ByteBuffer.allocate(16 + 4 + 4 + nameBytes.length).order(ByteOrder.BIG_ENDIAN)
            .putLong(header.placementId().getMostSignificantBits())
            .putLong(header.placementId().getLeastSignificantBits())
            .putInt(header.fileBytes())
            .putInt(nameBytes.length)
            .put(nameBytes)
            .array();
    }

    public static @Nullable SyncmaticaPlacementHeader decodeSyncmaticaHeader(
        final byte @Nullable [] payload,
        final int maxFileBytes
    ) {
        if (payload == null || payload.length < 24 || maxFileBytes < 1) {
            return null;
        }
        final ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        final long msb = buf.getLong();
        final long lsb = buf.getLong();
        final int fileBytes = buf.getInt();
        final int nameLen = buf.getInt();
        if (fileBytes < 1 || fileBytes > maxFileBytes || nameLen < 1 || nameLen > MAX_STRING_BYTES || buf.remaining() != nameLen) {
            return null;
        }
        final byte[] nameBytes = new byte[nameLen];
        buf.get(nameBytes);
        return new SyncmaticaPlacementHeader(new UUID(msb, lsb), new String(nameBytes, StandardCharsets.UTF_8), fileBytes);
    }
}
