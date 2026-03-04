package net.azisaba.vanilife.islands.listener

import com.github.shynixn.mccoroutine.folia.launch
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent
import io.papermc.paper.registry.keys.SoundEventKeys
import kotlinx.coroutines.runBlocking
import net.azisaba.vanilife.islands.IslandManager
import net.azisaba.vanilife.islands.addPlayer
import net.azisaba.vanilife.islands.removePlayer
import net.azisaba.vanilife.islands.storage.resolveSpawnPoint
import net.azisaba.vanilife.islands.wrack.WrackType
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

internal class IslandPlayerListener(private val plugin: Plugin, private val service: IslandManager) : Listener {
    @EventHandler
    fun onAsyncPlayerSpawnLocation(event: AsyncPlayerSpawnLocationEvent) {
        val playerUuid = event.connection.profile.id ?: return
        runBlocking {
            val island = service.lookupOrCreateByOwner(playerUuid)
            event.spawnLocation = island.primaryData.resolveSpawnPoint(island.pos)
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        plugin.launch {
            val island = service.lookupByOwner(player.uniqueId)
            island?.addPlayer(player)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        plugin.launch {
            val island = service.lookupByOwner(player.uniqueId)
            island?.removePlayer(player)
        }
    }

    // Test code
    @EventHandler
    fun onPlayerChat(event: AsyncChatEvent) {
        plugin.launch {
            val island = service.lookupByOwner(event.player.uniqueId)
            val message = PlainTextComponentSerializer.plainText().serialize(event.message())
            repeat(message.length) {
                island?.spawnWrack(
                    WrackType(
                        "bottle",
                        Sound.sound(SoundEventKeys.ENTITY_ITEM_PICKUP, Sound.Source.PLAYER, 0.5f, 0.1f),
                        listOf(ItemStack.of(Material.COOKED_BEEF)).iterator(),
                    )
                )
            }
        }
    }
}
