package net.azisaba.vanilife.api;

import org.bukkit.World;

public interface ResourceYProvider {
    /**
     * Return the highest Y for the named resource layer at (x,z).
     * layerId is one of: "nether", "overworld", "end"
     */
    int getHighestYForLayer(World world, int x, int z, String layerId);
}
