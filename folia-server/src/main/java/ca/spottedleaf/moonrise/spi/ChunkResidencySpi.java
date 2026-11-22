package ca.spottedleaf.moonrise.spi;

import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate chunk cold-section residency lifecycle SPI.
 *
 * <p>Receives O(1) notifications on block-state edits, region tick-end snapshot freezes, and
 * chunk unloads so a registered storage engine can keep cold unmodified sections backed by its
 * compact on-disk representation while maintaining unload-fenced eviction safety.</p>
 */
public interface ChunkResidencySpi {

    void onChunkBlockEdited(
        String worldId,
        int chunkX,
        int chunkZ,
        int sectionIndex,
        int localPos12,
        int runtimeStateId
    );

    void onChunkUnloaded(String worldId, int chunkX, int chunkZ);

    default void onRegionTickEnd(final String worldId, final long[] chunkKeys, final int count) {
    }

    static @Nullable ChunkResidencySpi get() {
        return Holder.current;
    }

    static boolean isActive() {
        return Holder.current != null;
    }

    static void register(final @Nullable ChunkResidencySpi spi) {
        Holder.current = spi;
    }

    static void resetDefault() {
        Holder.current = null;
    }

    final class Holder {
        private static volatile @Nullable ChunkResidencySpi current;

        private Holder() {
        }
    }
}
