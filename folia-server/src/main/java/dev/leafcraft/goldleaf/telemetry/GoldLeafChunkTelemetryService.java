package dev.leafcraft.goldleaf.telemetry;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import io.papermc.paper.telemetry.ChunkTelemetryService;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Chunk;
import org.bukkit.craftbukkit.CraftChunk;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 implementation of the public {@link ChunkTelemetryService} API backed by
 * {@link ChunkTickTelemetry} and {@link ChunkExecutionMask} ({@code goldleaf-ADR-006}).
 */
@NullMarked
public final class GoldLeafChunkTelemetryService implements ChunkTelemetryService {

    private static final GoldLeafChunkTelemetryService INSTANCE = new GoldLeafChunkTelemetryService();

    private final Map<PhaseListener, RegionTickTelemetryListener> listenerBridges = new ConcurrentHashMap<>();

    private GoldLeafChunkTelemetryService() {
    }

    public static GoldLeafChunkTelemetryService instance() {
        return INSTANCE;
    }

    @Override
    public boolean isTelemetrySpiEnabled() {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        return cfg != null && cfg.diagnostics != null && cfg.diagnostics.chunkTelemetrySpi;
    }

    @Override
    public boolean isTelemetryActive() {
        return ChunkTickTelemetry.isActive();
    }

    @Override
    public boolean registerPhaseListener(final PhaseListener listener) {
        Objects.requireNonNull(listener, "listener");
        final RegionTickTelemetryListener bridge = (regionId, worldName, chunkX, chunkZ, phase, durationNanos) ->
            listener.onChunkPhaseSample(regionId, worldName, chunkX, chunkZ, toApiPhase(phase), durationNanos);
        if (this.listenerBridges.putIfAbsent(listener, bridge) != null) {
            return false;
        }
        final boolean registered = ChunkTickTelemetry.registerListener(bridge);
        if (!registered) {
            this.listenerBridges.remove(listener, bridge);
        }
        return registered;
    }

    @Override
    public boolean unregisterPhaseListener(final PhaseListener listener) {
        Objects.requireNonNull(listener, "listener");
        final RegionTickTelemetryListener bridge = this.listenerBridges.remove(listener);
        return bridge != null && ChunkTickTelemetry.unregisterListener(bridge);
    }

    @Override
    public boolean setChunkExecutionMask(final Chunk chunk, final int mask) {
        final LevelChunk handle = toLevelChunk(chunk);
        return handle != null && ChunkExecutionMask.setMask(handle, mask);
    }

    @Override
    public void clearChunkExecutionMask(final Chunk chunk) {
        final LevelChunk handle = toLevelChunk(chunk);
        if (handle != null) {
            ChunkExecutionMask.clearMask(handle);
        }
    }

    @Override
    public int getChunkExecutionMask(final Chunk chunk) {
        final LevelChunk handle = toLevelChunk(chunk);
        if (handle == null) {
            return MASK_NONE;
        }
        if (handle.paper$getMaskEpoch() != ChunkTickTelemetry.currentMaskEpoch()) {
            return MASK_NONE;
        }
        return handle.paper$getExecutionMask();
    }

    @Override
    public @Nullable ActivitySnapshot drainChunkActivity(final Chunk chunk) {
        final LevelChunk handle = toLevelChunk(chunk);
        if (handle == null) {
            return null;
        }
        final ChunkActivityCounters.Snapshot snap = ChunkTickTelemetry.drainChunkActivity(handle);
        if (snap == null) {
            return null;
        }
        return new ActivitySnapshot(
            snap.redstoneUpdates(),
            snap.hopperTransfers(),
            snap.pistonMoves(),
            snap.blockGrowths(),
            snap.entityTicks()
        );
    }

    private static @Nullable LevelChunk toLevelChunk(final Chunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        if (chunk instanceof CraftChunk craftChunk) {
            final ChunkAccess access = craftChunk.getHandle(ChunkStatus.FULL);
            if (access instanceof LevelChunk levelChunk) {
                return levelChunk;
            }
        }
        return null;
    }

    private static TickPhase toApiPhase(final ChunkTickPhase phase) {
        return switch (phase) {
            case ENTITY -> TickPhase.ENTITY;
            case BLOCK_ENTITY -> TickPhase.BLOCK_ENTITY;
            case SCHEDULED_TICK -> TickPhase.SCHEDULED_TICK;
            case RANDOM_TICK -> TickPhase.RANDOM_TICK;
            case REDSTONE -> TickPhase.REDSTONE;
            case HOPPER -> TickPhase.HOPPER;
        };
    }
}
