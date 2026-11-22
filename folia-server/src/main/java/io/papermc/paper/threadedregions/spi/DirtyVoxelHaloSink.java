package io.papermc.paper.threadedregions.spi;

import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate per-region dirty-voxel halo sink SPI for {@code RegionizedWorldData}.
 *
 * <p>When registered by a multi-node sharding engine, records 4-byte packed voxel mutations
 * {@code ((localPos12 + 1) << 19) | (runtimeStateId & 0x7FFFF)} on the owning region thread and
 * receives end-of-tick halo delta flushes. Idle cost when unregistered is a single volatile read.</p>
 */
public interface DirtyVoxelHaloSink {

    int RUNTIME_STATE_MASK = 0x7FFFF;

    static int packDirtyVoxel(final int localPos12, final int runtimeStateId) {
        if ((localPos12 & ~0xFFF) != 0) {
            throw new IllegalArgumentException("localPos12 out of range [0, 4095]: " + localPos12);
        }
        if ((runtimeStateId & ~RUNTIME_STATE_MASK) != 0) {
            throw new IllegalArgumentException("runtimeStateId out of 19-bit range: " + runtimeStateId);
        }
        return ((localPos12 + 1) << 19) | (runtimeStateId & RUNTIME_STATE_MASK);
    }

    static int unpackLocalPos12(final int packedCell) {
        return (packedCell >>> 19) - 1;
    }

    static int unpackRuntimeStateId(final int packedCell) {
        return packedCell & RUNTIME_STATE_MASK;
    }

    void recordDirtyVoxel(
        @Nullable ServerLevel world,
        int chunkX,
        int chunkZ,
        int sectionY,
        int packedCell
    );

    default void flushRegionHalo(
        final @Nullable ServerLevel world,
        final long regionId,
        final long[] chunkKeys,
        final int[] sectionYs,
        final int[] packedCells,
        final int count
    ) {
    }

    static boolean isActive() {
        return Holder.current != null;
    }

    static @Nullable DirtyVoxelHaloSink get() {
        return Holder.current;
    }

    static void register(final @Nullable DirtyVoxelHaloSink sink) {
        Holder.current = sink;
    }

    static void resetDefault() {
        Holder.current = null;
    }

    final class Holder {
        private static volatile @Nullable DirtyVoxelHaloSink current;

        private Holder() {
        }
    }
}
