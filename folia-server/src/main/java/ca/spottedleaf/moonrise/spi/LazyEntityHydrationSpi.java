package ca.spottedleaf.moonrise.spi;

import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate SPI controlling lazy entity-chunk hydration during exploration
 * ({@code goldleaf-ADR-005}).
 *
 * <p>When enabled, disk-loaded chunks entering {@code FullChunkStatus.FULL} or {@code BLOCK_TICKING}
 * defer {@code ChunkEntitySlices.readEntities(...)} and keep their loaded {@code pendingEntityChunk}
 * dormant until the chunk promotes to {@code FullChunkStatus.ENTITY_TICKING} or is directly queried
 * for entities. Unloaded outer-ring exploration chunks drop the dormant tag without ever allocating
 * live NMS {@code Entity} instances or rewriting unchanged {@code entities/} data to disk.</p>
 */
@FunctionalInterface
public interface LazyEntityHydrationSpi {

    boolean shouldDeferHydration(ServerLevel world, int chunkX, int chunkZ);

    static boolean shouldDeferEntityHydration(
        final ServerLevel world,
        final int chunkX,
        final int chunkZ
    ) {
        final LazyEntityHydrationSpi spi = Holder.current;
        return spi != null && spi.shouldDeferHydration(world, chunkX, chunkZ);
    }

    static @Nullable LazyEntityHydrationSpi get() {
        return Holder.current;
    }

    static boolean isActive() {
        return Holder.current != null;
    }

    static void register(final @Nullable LazyEntityHydrationSpi spi) {
        Holder.current = spi;
    }

    static void resetDefault() {
        Holder.current = null;
    }

    final class Holder {
        private static volatile @Nullable LazyEntityHydrationSpi current;

        private Holder() {
        }
    }
}
