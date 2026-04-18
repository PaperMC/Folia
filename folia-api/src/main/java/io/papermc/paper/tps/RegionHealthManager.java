package io.papermc.paper.tps;

import org.jspecify.annotations.NullMarked;

/**
 * Provides health and performance metrics for the current Folia region.
 * This manager allows per-region TPS stabilization.
 */
@NullMarked
public interface RegionHealthManager {

    /**
     * Checks if the current region is experiencing heavy load.
     *
     * @return true if the region is lagging
     */
    boolean isRegionLagging();

    /**
     * Gets the current tick time (MSPPT - MilliSeconds Per Partition Tick) for this region.
     *
     * @return the average tick time in milliseconds
     */
    double getRegionTickTime();

    /**
     * Checks if dynamic ticking (AI/Physics reduction) is currently active for this region.
     *
     * @return true if mitigation is active
     */
    boolean isMitigationActive();

    /**
     * Sets whether performance mitigation is allowed to run in this region.
     *
     * @param enabled true to allow automatic mitigation
     */
    void setMitigationEnabled(boolean enabled);
}
