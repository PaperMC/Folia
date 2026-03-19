package net.azisaba.vanilife.farming.listener

import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.keys.SoundEventKeys
import net.azisaba.vanilife.item.ServerItem
import net.kyori.adventure.sound.Sound
import org.bukkit.Particle
import org.bukkit.block.data.type.Farmland
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.MoistureChangeEvent
import org.bukkit.event.player.PlayerInteractEvent

internal class FarmlandListener(private val fertilizer: TypedKey<ServerItem>) : Listener {
    @EventHandler
    fun onMoistureChange(event: MoistureChangeEvent) {
        val farmland = event.newState.blockData as? Farmland ?: return
        if (farmland.moisture == farmland.maximumMoisture) {
            farmland.moisture = farmland.maximumMoisture - 1
            event.newState.blockData = farmland
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val interacted = event.clickedBlock ?: return
        val farmland = interacted.blockData as? Farmland ?: return

        val itemStack = event.item ?: return

        if (itemStack.isOf(fertilizer)) {
            farmland.moisture = farmland.maximumMoisture
            interacted.blockData = farmland

            event.player.playSound(Sound.sound(SoundEventKeys.ITEM_BONE_MEAL_USE, Sound.Source.BLOCK, 0.5f, 2f))

            interacted.world.spawnParticle(
                Particle.HAPPY_VILLAGER,
                interacted.location.add(0.5, 1.5, 0.5),
                4,
                0.02,
                0.02,
                0.02,
                0.14
            )

            if (!event.player.gameMode.isInvulnerable) {
                itemStack.subtract()
            }
        }
    }
}
