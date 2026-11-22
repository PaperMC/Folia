package dev.leafcraft.goldleaf.diagnostics;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 read-only per-region health snapshot and opt-in live admin HUD subscription manager
 * ({@code goldleaf-ADR-011} §1.2 &amp; §2.1).
 *
 * <p>Guarantees zero idle overhead: when no administrator has toggled {@code /goldleaf hud},
 * {@link #hasActiveHudSubscribers()} is a single {@code volatile} boolean read and no HUD string
 * formatting or packet allocations occur on region tick loops.</p>
 */
public final class RegionHealthSnapshot {

    public record RegionMetrics(
        String worldName,
        int centerChunkX,
        int centerChunkZ,
        double tps,
        double msptP50,
        double msptP95,
        double msptP99,
        double utilisation,
        int loadedChunks,
        int entityCount,
        int playerCount
    ) {
        public String formatHudLine() {
            return String.format(
                "Region (%d,%d) %s | %.2f TPS | %.2fms (p95 %.2fms) | %d chunks, %d ent, %d pl",
                this.centerChunkX,
                this.centerChunkZ,
                this.worldName,
                this.tps,
                this.msptP50,
                this.msptP95,
                this.loadedChunks,
                this.entityCount,
                this.playerCount
            );
        }
    }

    private static final RegionHealthSnapshot GLOBAL = new RegionHealthSnapshot();

    private final Set<UUID> hudSubscribers = ConcurrentHashMap.newKeySet();
    private volatile boolean anyHudActive = false;

    public static RegionHealthSnapshot global() {
        return GLOBAL;
    }

    public boolean hasActiveHudSubscribers() {
        return this.anyHudActive;
    }

    public boolean toggleHud(final UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        final boolean nowEnabled;
        if (this.hudSubscribers.remove(playerId)) {
            nowEnabled = false;
        } else {
            this.hudSubscribers.add(playerId);
            nowEnabled = true;
        }
        this.anyHudActive = !this.hudSubscribers.isEmpty();
        return nowEnabled;
    }

    public boolean isHudSubscribed(final UUID playerId) {
        return this.anyHudActive && this.hudSubscribers.contains(playerId);
    }

    public void onPlayerQuit(final UUID playerId) {
        if (this.hudSubscribers.remove(playerId)) {
            this.anyHudActive = !this.hudSubscribers.isEmpty();
        }
    }

    public void clearSubscribers() {
        this.hudSubscribers.clear();
        this.anyHudActive = false;
    }

    /**
     * Formats a live HUD update line for {@code playerId} only if HUD is enabled in config,
     * {@code playerId} is subscribed, and {@code tickCount} aligns with the configured update
     * interval. Returns {@code null} with zero formatting allocations otherwise.
     */
    public @Nullable String maybeFormatHudForTick(
        final UUID playerId,
        final long tickCount,
        final RegionMetrics metrics
    ) {
        if (!this.anyHudActive) {
            return null;
        }
        final GoldLeafConfig.Diagnostics cfg = GoldLeafConfig.get().diagnostics;
        if (!cfg.liveRegionHudEnabled || (tickCount % cfg.liveRegionHudIntervalTicks) != 0L) {
            return null;
        }
        if (!this.hudSubscribers.contains(playerId)) {
            return null;
        }
        return metrics.formatHudLine();
    }
}
