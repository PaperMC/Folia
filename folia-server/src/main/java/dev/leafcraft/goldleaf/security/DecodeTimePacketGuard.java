package dev.leafcraft.goldleaf.security;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.NbtAccounter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1/2 decode-time packet and NBT hardening guard ({@code goldleaf-ADR-012} §1.2 &amp; §2.2).
 *
 * <p>Guarantees:
 * <ul>
 *   <li><strong>Untrusted-only NBT scoping:</strong> Tightened serverbound depth/byte quotas
 *       ({@link #createServerboundNbtAccounter()}) apply strictly to client-sent network packets
 *       and never alter trusted disk/world {@code NbtAccounter.uncompressedQuota()} ({@code 512}
 *       depth, {@code 100 MiB}).</li>
 *   <li><strong>Fail-closed structural validation ({@code S-004}, {@code ADR-102}):</strong>
 *       Oversized book payloads, excessive UTF-8 page bytes, or repeated malformed frames log a
 *       structured {@code WARNING} and return a deterministic {@link ValidationDecision} with
 *       disconnect escalation.</li>
 * </ul>
 * </p>
 */
public final class DecodeTimePacketGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(DecodeTimePacketGuard.class);
    private static final DecodeTimePacketGuard GLOBAL = new DecodeTimePacketGuard();

    public record ValidationDecision(boolean accepted, boolean shouldDisconnect, @Nullable String reason) {
        public static final ValidationDecision OK = new ValidationDecision(true, false, null);
    }

    private static final class MalformedTracker {
        private long windowSecond;
        private int violations;

        synchronized int recordViolation(final long currentSecond) {
            if (this.windowSecond != currentSecond) {
                this.windowSecond = currentSecond;
                this.violations = 0;
            }
            return ++this.violations;
        }
    }

    private static final class BurstBucket {
        private long lastRefillMillis;
        private double tokens;

        BurstBucket(final long nowMillis, final int capacity) {
            this.lastRefillMillis = nowMillis;
            this.tokens = capacity;
        }

        synchronized boolean tryConsume(final long nowMillis, final int capacity, final double refillPerSec) {
            if (nowMillis > this.lastRefillMillis) {
                final double elapsedSec = (nowMillis - this.lastRefillMillis) / 1000.0;
                this.tokens = Math.min(capacity, this.tokens + elapsedSec * refillPerSec);
                this.lastRefillMillis = nowMillis;
            }
            if (this.tokens >= 1.0) {
                this.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private static final int DEFAULT_BURST_CAPACITY = 100;
    private static final double DEFAULT_BURST_REFILL_PER_SEC = 100.0;

    private final ConcurrentHashMap<UUID, MalformedTracker> malformedTrackers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, BurstBucket> burstBuckets = new ConcurrentHashMap<>();
    private final AtomicLong totalRejected = new AtomicLong();

    public static DecodeTimePacketGuard global() {
        return GLOBAL;
    }

    /**
     * Creates an {@link NbtAccounter} scoped exclusively to untrusted serverbound network packet
     * decoding. When {@code anti-exploit.packet-hardening-enabled} is {@code false}, falls back to
     * vanilla {@link NbtAccounter#defaultQuota()}.
     */
    public static NbtAccounter createServerboundNbtAccounter() {
        final GoldLeafConfig.AntiExploit cfg = GoldLeafConfig.get().antiExploit;
        if (!cfg.packetHardeningEnabled) {
            return NbtAccounter.defaultQuota();
        }
        return new NbtAccounter(cfg.serverboundNbtMaxBytes, cfg.serverboundNbtMaxDepth);
    }

    /**
     * Validates a serverbound book-edit payload's page count and per-page UTF-8 byte length before
     * allocating game-thread {@code ItemStack} / {@code WrittenBookContent} structures.
     */
    public ValidationDecision validateBookPayload(
        final UUID playerId,
        final @Nullable List<String> pages,
        final long currentSecond
    ) {
        Objects.requireNonNull(playerId, "playerId");
        final GoldLeafConfig.AntiExploit cfg = GoldLeafConfig.get().antiExploit;
        if (!cfg.packetHardeningEnabled) {
            return ValidationDecision.OK;
        }
        if (pages == null) {
            return recordMalformed(playerId, "ServerboundEditBookPacket", "null page list", currentSecond);
        }
        if (pages.size() > cfg.maxBookPages) {
            return recordMalformed(
                playerId,
                "ServerboundEditBookPacket",
                "page count " + pages.size() + " > cap " + cfg.maxBookPages,
                currentSecond
            );
        }
        for (int i = 0; i < pages.size(); i++) {
            final String page = pages.get(i);
            if (page == null) {
                return recordMalformed(playerId, "ServerboundEditBookPacket", "null page at index " + i, currentSecond);
            }
            final int utf8Len = page.getBytes(StandardCharsets.UTF_8).length;
            if (utf8Len > cfg.maxBookPageUtf8Bytes) {
                return recordMalformed(
                    playerId,
                    "ServerboundEditBookPacket",
                    "page " + i + " UTF-8 length " + utf8Len + " > cap " + cfg.maxBookPageUtf8Bytes,
                    currentSecond
                );
            }
        }
        return ValidationDecision.OK;
    }

    /**
     * Records a decode-time malformed packet violation for {@code playerId} and returns a
     * fail-closed decision indicating whether the connection exceeded
     * {@code max-malformed-packets-per-second} and should be disconnected with
     * {@code DisconnectionReason.INVALID_PAYLOAD} ({@code S-004}).
     */
    public ValidationDecision recordMalformed(
        final UUID playerId,
        final String packetType,
        final String violationDetail,
        final long currentSecond
    ) {
        Objects.requireNonNull(playerId, "playerId");
        final GoldLeafConfig.AntiExploit cfg = GoldLeafConfig.get().antiExploit;
        this.totalRejected.incrementAndGet();
        final int count = this.malformedTrackers.computeIfAbsent(playerId, ignored -> new MalformedTracker())
            .recordViolation(currentSecond);
        final boolean disconnect = count >= cfg.maxMalformedPacketsPerSecond;
        LOGGER.warn(
            "Rejected malformed serverbound packet {} from {} ({}) [{}/{}/s]{}",
            packetType,
            playerId,
            violationDetail,
            count,
            cfg.maxMalformedPacketsPerSecond,
            disconnect ? " -> DISCONNECT(INVALID_PAYLOAD)" : ""
        );
        return new ValidationDecision(false, disconnect, violationDetail);
    }

    /**
     * Enforces a per-connection packet-burst token bucket ({@code goldleaf-ADR-012} §1.2).
     * When tokens are exhausted, records a malformed/burst violation that escalates to
     * {@code DisconnectionReason.INVALID_PAYLOAD} ({@code S-004}).
     */
    public ValidationDecision validatePacketBurst(
        final UUID playerId,
        final String packetType,
        final long nowMillis
    ) {
        return this.validatePacketBurst(
            playerId,
            packetType,
            nowMillis,
            DEFAULT_BURST_CAPACITY,
            DEFAULT_BURST_REFILL_PER_SEC
        );
    }

    public ValidationDecision validatePacketBurst(
        final UUID playerId,
        final String packetType,
        final long nowMillis,
        final int capacity,
        final double refillPerSec
    ) {
        Objects.requireNonNull(playerId, "playerId");
        final GoldLeafConfig.AntiExploit cfg = GoldLeafConfig.get().antiExploit;
        if (!cfg.packetHardeningEnabled) {
            return ValidationDecision.OK;
        }
        final int safeCapacity = Math.max(1, capacity);
        final double safeRefill = Math.max(1.0, refillPerSec);
        final BurstBucket bucket = this.burstBuckets.computeIfAbsent(
            playerId,
            ignored -> new BurstBucket(nowMillis, safeCapacity)
        );
        if (bucket.tryConsume(nowMillis, safeCapacity, safeRefill)) {
            return ValidationDecision.OK;
        }
        return this.recordMalformed(
            playerId,
            packetType,
            "packet burst exceeded capacity " + safeCapacity,
            Math.floorDiv(nowMillis, 1000L)
        );
    }

    public void onPlayerQuit(final UUID playerId) {
        if (playerId != null) {
            this.malformedTrackers.remove(playerId);
            this.burstBuckets.remove(playerId);
        }
    }

    public long totalRejected() {
        return this.totalRejected.get();
    }

    public int trackedConnections() {
        return Math.max(this.malformedTrackers.size(), this.burstBuckets.size());
    }

    public void clear() {
        this.malformedTrackers.clear();
        this.burstBuckets.clear();
    }
}
