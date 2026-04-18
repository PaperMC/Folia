package org.bukkit.block;

import org.bukkit.entity.Creaking;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Represents a captured state of a creaking heart in a Folia region.
 */
@NullMarked
public interface CreakingHeart extends TileState {

    /**
     * Gets the creaking currently tied to this heart.
     * In Folia, this lookup is region-local and thread-safe.
     *
     * @return the creaking if one is active in this region, null otherwise
     */
    @Nullable
    Creaking getCreaking();

    /**
     * Checks if this heart is currently active and controlling a creaking.
     *
     * @return true if active
     */
    boolean isActive();
}
