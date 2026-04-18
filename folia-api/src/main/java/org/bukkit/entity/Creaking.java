package org.bukkit.entity;

import org.bukkit.Location;
import org.bukkit.block.CreakingHeart;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Represents a Creaking entity within a Folia region.
 */
@NullMarked
public interface Creaking extends Monster {

    /**
     * Gets the home location for this creaking.
     *
     * @return the location of the heart, null otherwise
     */
    @Nullable
    Location getHome();

    /**
     * Gets the creaking heart that this creaking is tied to.
     *
     * @return the heart if available in the current region, null otherwise
     */
    @Nullable
    CreakingHeart getCreakingHeart();

    /**
     * Activates this creaking to target a player.
     *
     * @param player the target
     */
    void activate(final Player player);
}
