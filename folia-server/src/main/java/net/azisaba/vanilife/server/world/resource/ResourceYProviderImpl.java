package net.azisaba.vanilife.server.world.resource;

import net.azisaba.vanilife.api.ResourceYProvider;
import net.minecraft.world.level.dimension.DimensionDefaults;
import org.bukkit.World;
import org.bukkit.block.Block;

public class ResourceYProviderImpl implements ResourceYProvider {
    @Override
    public int getHighestYForLayer(World world, int x, int z, String layerId) {
        // Reconstruct layout minY and layer heights to compute absolute ranges
        final int layoutMinY = DimensionDefaults.OVERWORLD_MIN_Y - DimensionDefaults.NETHER_GENERATION_HEIGHT;
        final int netherHeight = DimensionDefaults.NETHER_GENERATION_HEIGHT;
        final int overworldHeight = DimensionDefaults.OVERWORLD_GENERATION_HEIGHT;
        final int endHeight = DimensionDefaults.END_GENERATION_HEIGHT;

        int layerMin;
        int layerMax;
        switch (layerId.toLowerCase()) {
            case "nether":
                layerMin = layoutMinY;
                layerMax = layerMin + netherHeight - 1;
                break;
            case "overworld":
                layerMin = layoutMinY + netherHeight;
                layerMax = layerMin + overworldHeight - 1;
                break;
            case "end":
                layerMin = layoutMinY + netherHeight + overworldHeight;
                layerMax = layerMin + endHeight - 1;
                break;
            default:
                // default to overworld
                layerMin = layoutMinY + netherHeight;
                layerMax = layerMin + overworldHeight - 1;
                break;
        }

        // Scan downward from layerMax to layerMin to find the first solid block
        for (int y = layerMax; y >= layerMin; y--) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getType().isSolid()) {
                return y + 1;
            }
        }

        // fallback
        return layerMin + 1;
    }
}
