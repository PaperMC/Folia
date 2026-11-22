package dev.leafcraft.goldleaf.diagnostics;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.telemetry.ChunkActivityCounters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lock-free, fault-isolated registry and built-in formatter for {@code FoliaWatchdogThread}
 * region-context diagnostics ({@code goldleaf-ADR-011} §1.1).
 *
 * <p>Guarantees:
 * <ul>
 *   <li><strong>Deadlock freedom ({@code S-005}):</strong> Reads only immutable snapshots and
 *       concurrent collections; never acquires region locks.</li>
 *   <li><strong>Fault isolation ({@code S-004}):</strong> Any exception thrown by a contributor is
 *       caught and logged at {@code WARNING} so {@code WatchdogThread.dumpThread(...)} always
 *       executes cleanly.</li>
 * </ul>
 * </p>
 */
public final class WatchdogDiagnosticsRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(WatchdogDiagnosticsRegistry.class);
    private static final WatchdogDiagnosticsRegistry GLOBAL = new WatchdogDiagnosticsRegistry();

    /**
     * Lock-free snapshot of a player's last-known position inside a region.
     */
    public record PlayerLocationSnapshot(String playerName, int blockX, int blockY, int blockZ) {
    }

    /**
     * Lock-free snapshot of a chunk's {@code ADR-006} activity score inside a stalled region.
     */
    public record HotChunkSnapshot(
        int chunkX,
        int chunkZ,
        long entityTicks,
        long blockEntityTicks,
        long redstoneUpdates,
        long fluidUpdates
    ) {
        public long totalScore() {
            return this.entityTicks + this.blockEntityTicks + this.redstoneUpdates + this.fluidUpdates;
        }

        public static HotChunkSnapshot fromCounters(final int chunkX, final int chunkZ, final ChunkActivityCounters c) {
            return new HotChunkSnapshot(
                chunkX,
                chunkZ,
                c.entityTicks(),
                c.hopperTransfers() + c.pistonMoves(),
                c.redstoneUpdates(),
                c.blockGrowths()
            );
        }
    }

    /**
     * Preflight hazard attribution metadata registered from {@code goldleaf-ADR-007}.
     */
    public record PluginHazardAttribution(String pluginName, String packagePrefix, String hazardCode) {
    }

    private final CopyOnWriteArrayList<WatchdogContextContributor> contributors = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, PluginHazardAttribution> preflightHazards = new ConcurrentHashMap<>();

    public static WatchdogDiagnosticsRegistry global() {
        return GLOBAL;
    }

    public void registerContributor(final WatchdogContextContributor contributor) {
        Objects.requireNonNull(contributor, "contributor");
        this.contributors.addIfAbsent(contributor);
    }

    public void unregisterContributor(final WatchdogContextContributor contributor) {
        if (contributor != null) {
            this.contributors.remove(contributor);
        }
    }

    public void registerPluginHazard(final PluginHazardAttribution attribution) {
        Objects.requireNonNull(attribution, "attribution");
        if (attribution.packagePrefix() != null && !attribution.packagePrefix().isBlank()) {
            this.preflightHazards.put(attribution.packagePrefix(), attribution);
        }
    }

    public void clear() {
        this.contributors.clear();
        this.preflightHazards.clear();
    }

    /**
     * Formats built-in region context (nearby players, top hot chunks from {@code ADR-006}, and
     * stack-frame plugin attribution from {@code ADR-007}) plus all registered contributors with
     * strict per-contributor fault isolation ({@code S-004}).
     */
    public List<String> collectDiagnosticLines(
        final WatchdogContextContributor.StalledTickContext context,
        final @Nullable List<PlayerLocationSnapshot> nearbyPlayers,
        final @Nullable List<HotChunkSnapshot> regionChunks
    ) {
        Objects.requireNonNull(context, "context");
        final GoldLeafConfig.Diagnostics cfg = GoldLeafConfig.get().diagnostics;
        if (!cfg.watchdogEnrichedContext) {
            return List.of();
        }

        final List<String> lines = new ArrayList<>();
        lines.add(String.format(
            "[GoldLeaf Watchdog] Region=%s world='%s' center=(%d,%d) thread='%s' elapsed=%.2fs",
            context.globalRegion() ? "GLOBAL" : "REGION",
            context.worldName(),
            context.centerChunkX(),
            context.centerChunkZ(),
            context.threadName(),
            context.elapsedSeconds()
        ));

        if (nearbyPlayers != null && !nearbyPlayers.isEmpty()) {
            final int limit = Math.min(cfg.watchdogMaxPlayers, nearbyPlayers.size());
            final StringBuilder sb = new StringBuilder("[GoldLeaf Watchdog] Nearby players (")
                .append(limit).append('/').append(nearbyPlayers.size()).append("): ");
            for (int i = 0; i < limit; i++) {
                final PlayerLocationSnapshot p = nearbyPlayers.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(p.playerName()).append('@')
                    .append('(').append(p.blockX()).append(',').append(p.blockY()).append(',').append(p.blockZ()).append(')');
            }
            lines.add(sb.toString());
        }

        if (regionChunks != null && !regionChunks.isEmpty()) {
            final List<HotChunkSnapshot> ranked = new ArrayList<>(regionChunks);
            ranked.sort(Comparator.comparingLong(HotChunkSnapshot::totalScore).reversed());
            final int limit = Math.min(cfg.watchdogTopHotChunks, ranked.size());
            final StringBuilder sb = new StringBuilder("[GoldLeaf Watchdog] Top hot chunks (ADR-006): ");
            int emitted = 0;
            for (int i = 0; i < limit; i++) {
                final HotChunkSnapshot c = ranked.get(i);
                if (c.totalScore() <= 0L) {
                    break;
                }
                if (emitted > 0) {
                    sb.append(", ");
                }
                sb.append('(').append(c.chunkX()).append(',').append(c.chunkZ()).append(")[ent=")
                    .append(c.entityTicks()).append(",be=").append(c.blockEntityTicks())
                    .append(",rs=").append(c.redstoneUpdates()).append(",fl=").append(c.fluidUpdates()).append(']');
                emitted++;
            }
            if (emitted > 0) {
                lines.add(sb.toString());
            }
        }

        final List<String> matchedPlugins = matchPreflightHazards(context.stackTrace());
        if (!matchedPlugins.isEmpty()) {
            lines.add("[GoldLeaf Watchdog] Preflight-flagged plugins on stack (ADR-007): " + String.join(", ", matchedPlugins));
        }

        for (final WatchdogContextContributor contributor : this.contributors) {
            try {
                final List<String> extra = contributor.contribute(context);
                if (extra != null && !extra.isEmpty()) {
                    lines.addAll(extra);
                }
            } catch (final Throwable t) {
                LOGGER.warn("WatchdogContextContributor '{}' threw during stall dump; isolating fault", contributor.getClass().getName(), t);
            }
        }

        return lines;
    }

    /**
     * Logs all collected diagnostic lines to {@code targetLogger} without ever throwing into
     * {@code FoliaWatchdogThread}.
     */
    public void emitToLogger(
        final Logger targetLogger,
        final WatchdogContextContributor.StalledTickContext context,
        final @Nullable List<PlayerLocationSnapshot> nearbyPlayers,
        final @Nullable List<HotChunkSnapshot> regionChunks
    ) {
        try {
            final List<String> lines = this.collectDiagnosticLines(context, nearbyPlayers, regionChunks);
            for (final String line : lines) {
                targetLogger.error(line);
            }
        } catch (final Throwable t) {
            LOGGER.warn("GoldLeaf watchdog context collection failed; continuing with base thread dump", t);
        }
    }

    private List<String> matchPreflightHazards(final @Nullable StackTraceElement[] stack) {
        if (stack == null || stack.length == 0 || this.preflightHazards.isEmpty()) {
            return List.of();
        }
        final List<String> matches = new ArrayList<>();
        for (final Map.Entry<String, PluginHazardAttribution> entry : this.preflightHazards.entrySet()) {
            final String prefix = entry.getKey();
            for (final StackTraceElement frame : stack) {
                if (frame != null && frame.getClassName().startsWith(prefix)) {
                    final PluginHazardAttribution attr = entry.getValue();
                    matches.add(attr.pluginName() + "[" + attr.hazardCode() + "]");
                    break;
                }
            }
        }
        return matches;
    }
}
