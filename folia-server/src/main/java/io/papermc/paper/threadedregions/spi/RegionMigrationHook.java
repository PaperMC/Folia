package io.papermc.paper.threadedregions.spi;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate region lifecycle &amp; migration hook SPI for {@code TickRegions}.
 *
 * <p>Exposes region merge, split, inactivity, destruction, and cross-shard player handoff
 * notifications so external sharding or storage engines can flush region deltas and coordinate
 * proxy switching without patching Folia's core regionizer.</p>
 */
public interface RegionMigrationHook {

    default void onPreMerge(final @Nullable ServerLevel world, final long fromRegionId, final long intoRegionId) {
    }

    default void onPreSplit(final @Nullable ServerLevel world, final long fromRegionId, final long[] intoRegionIds) {
    }

    default void onRegionInactive(final @Nullable ServerLevel world, final long regionId) {
    }

    default void onRegionDestroy(final @Nullable ServerLevel world, final long regionId) {
    }

    /**
     * Signals that a player is crossing into a remotely leased shard and requires proxy handoff
     * (e.g., PSBD Velocity proxy switching).
     */
    default void onProxyPlayerSwitchRequest(
        final @Nullable ServerLevel world,
        final UUID playerId,
        final String targetShardId,
        final int targetChunkX,
        final int targetChunkZ
    ) {
    }

    static @Nullable RegionMigrationHook get() {
        return Holder.current;
    }

    static void register(final @Nullable RegionMigrationHook hook) {
        Holder.current = hook;
    }

    static void resetDefault() {
        Holder.current = null;
    }

    final class Holder {
        private static volatile @Nullable RegionMigrationHook current;

        private Holder() {
        }
    }
}
