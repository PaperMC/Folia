package io.papermc.paper.threadedregions;

import io.papermc.paper.tps.RegionHealthManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.NullMarked;

/**
 * Handles per-region performance monitoring and stabilization.
 */
@NullMarked
public class FoliaPerformanceOptimizer implements RegionHealthManager {

    private final TickRegion region;
    private boolean mitigationEnabled = true;

    public FoliaPerformanceOptimizer(TickRegion region) {
        this.region = region;
    }

    @Override
    public boolean isRegionLagging() {
        // Threshold: If MSMPT (MilliSeconds Per Partition Tick) > 45ms (Target is 50ms)
        return getRegionTickTime() > 45.0;
    }

    @Override
    public double getRegionTickTime() {
        // Access Folia's internal region profiler data
        return this.region.getData().getAverageTickTime();
    }

    @Override
    public boolean isMitigationActive() {
        return mitigationEnabled && isRegionLagging();
    }

    @Override
    public void setMitigationEnabled(boolean enabled) {
        this.mitigationEnabled = enabled;
    }

    /**
     * Called during entity ticking to decide if an entity should skip its AI/Physics.
     */
    public boolean shouldSkipTick(Entity entity) {
        if (!isMitigationActive()) return false;

        // Never skip player ticks
        if (entity instanceof Player) return false;

        // Skip non-essential entities every other tick if lagging
        return entity.tickCount % 2 != 0;
    }
}
