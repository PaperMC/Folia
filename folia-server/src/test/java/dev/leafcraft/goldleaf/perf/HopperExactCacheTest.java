package dev.leafcraft.goldleaf.perf;

import io.papermc.paper.spi.HopperExactCache;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Layer 1 {@link HopperExactCache} ({@code goldleaf-ADR-013}).
 */
class HopperExactCacheTest {

    @Test
    void pushCacheSkipsOnlyWhenAllEpochsAndIdentityMatch() {
        final HopperExactCache cache = new HopperExactCache();
        final Object chestA = new Object();
        final Object chestB = new Object();

        assertFalse(cache.canSkipPushScan(1, 5, 10, chestA));

        cache.recordPushNoOp(1, 5, 10, chestA);
        assertTrue(cache.canSkipPushScan(1, 5, 10, chestA));

        // Any hopper mutation invalidates immediately
        assertFalse(cache.canSkipPushScan(2, 5, 10, chestA));
        // Any destination container mutation invalidates immediately
        assertFalse(cache.canSkipPushScan(1, 6, 10, chestA));
        // Any neighbor sectionEpoch change (block update / replacement) invalidates immediately
        assertFalse(cache.canSkipPushScan(1, 5, 11, chestA));
        // Different container instance invalidates immediately
        assertFalse(cache.canSkipPushScan(1, 5, 10, chestB));

        cache.invalidateAll();
        assertFalse(cache.canSkipPushScan(1, 5, 10, chestA));
    }

    @Test
    void pullCacheInvalidatesOnSourceMutationOrRemoval() {
        final HopperExactCache cache = new HopperExactCache();
        final Object sourceChest = new Object();

        cache.recordPullNoOp(3, 7, 42, sourceChest);
        assertTrue(cache.canSkipPullScan(3, 7, 42, sourceChest));

        assertFalse(cache.canSkipPullScan(4, 7, 42, sourceChest));
        assertFalse(cache.canSkipPullScan(3, 8, 42, sourceChest));
        assertFalse(cache.canSkipPullScan(3, 7, 43, sourceChest));

        cache.recordPullNoOp(3, 7, 42, null);
        assertFalse(cache.canSkipPullScan(3, 7, 42, sourceChest));
    }
}
