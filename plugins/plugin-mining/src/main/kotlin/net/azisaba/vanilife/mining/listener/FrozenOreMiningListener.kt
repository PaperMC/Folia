package net.azisaba.vanilife.mining.listener

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.tag.TagKey
import net.azisaba.vanilife.event.BlockDropLootEvent
import net.azisaba.vanilife.mining.OreType
import net.coreprotect.CoreProtectAPI
import org.bukkit.block.Biome
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack

internal class FrozenOreMiningListener(
    private val biomes: TagKey<Biome>,
    private val coreProtectApi: CoreProtectAPI,
) : Listener {
    @EventHandler
    fun onBlockDropLoot(event: BlockDropLootEvent) {
        val biome = RegistryKey.BIOME.typedKey(event.block.biome.key())
        if (RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).getTag(biomes).contains(biome)) {
            val oreType = OreType.byBlockWithNaturalCheck(event.blockState, coreProtectApi) ?: return
            event.mapDrops { drops ->
                drops.map { drop ->
                    if (drop.type == oreType.base) {
                        ItemStack.of(oreType.frozen, drop.amount)
                    } else drop
                }
            }
        }
    }
}
