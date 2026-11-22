package io.papermc.paper.telemetry;

import org.bukkit.Chunk;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Public plugin-facing service for native per-chunk tick telemetry, primitive activity counters,
 * and pre-tick {@code ChunkExecutionMask} gating ({@code goldleaf-ADR-006}).
 *
 * <p>Plugins (such as {@code LeafSleuth} and {@code LeafShard}) may discover this service via
 * {@code Bukkit.getServicesManager().load(ChunkTelemetryService.class)}. When no listeners are
 * registered and all chunk masks remain {@code 0}, runtime overhead is a single volatile read per
 * tick ({@code ~0 ns}, {@code 0 B/tick}).</p>
 */
@NullMarked
public interface ChunkTelemetryService {

    int MASK_NONE = 0;
    int SUPPRESS_REDSTONE = 1 << 0;
    int SUPPRESS_HOPPERS = 1 << 1;
    int SUPPRESS_PISTONS = 1 << 2;
    int DAMPEN_ENTITY_AI = 1 << 3;
    int VIRTUALIZED_BLACKBOX = 1 << 4;

    enum TickPhase {
        ENTITY,
        BLOCK_ENTITY,
        SCHEDULED_TICK,
        RANDOM_TICK,
        REDSTONE,
        HOPPER
    }

    @FunctionalInterface
    interface PhaseListener {
        void onChunkPhaseSample(
            long regionId,
            String worldName,
            int chunkX,
            int chunkZ,
            TickPhase phase,
            long durationNanos
        );
    }

    record ActivitySnapshot(
        long redstoneUpdates,
        long hopperTransfers,
        long pistonMoves,
        long blockGrowths,
        long entityTicks
    ) {
        public long total() {
            return this.redstoneUpdates + this.hopperTransfers + this.pistonMoves + this.blockGrowths + this.entityTicks;
        }
    }

    boolean isTelemetrySpiEnabled();

    boolean isTelemetryActive();

    boolean registerPhaseListener(PhaseListener listener);

    boolean unregisterPhaseListener(PhaseListener listener);

    /**
     * Mutates the chunk's execution mask. Must be invoked on the chunk's owning Folia region thread.
     */
    boolean setChunkExecutionMask(Chunk chunk, int mask);

    /**
     * Resets the chunk's execution mask to {@link #MASK_NONE}. Must be invoked on the owning region thread.
     */
    void clearChunkExecutionMask(Chunk chunk);

    int getChunkExecutionMask(Chunk chunk);

    /**
     * Drains and resets the lazily allocated primitive activity counters for {@code chunk}, or
     * returns {@code null} if no activity was recorded.
     */
    @Nullable ActivitySnapshot drainChunkActivity(Chunk chunk);

    /**
     * Global service holder populated by the server bootstrap at startup.
     */
    final class Holder {
        private static volatile @Nullable ChunkTelemetryService instance;

        private Holder() {
        }
    }

    static @Nullable ChunkTelemetryService get() {
        return Holder.instance;
    }

    static void register(final @Nullable ChunkTelemetryService service) {
        Holder.instance = service;
    }
}
