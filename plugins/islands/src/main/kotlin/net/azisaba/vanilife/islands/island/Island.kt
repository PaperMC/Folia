package net.azisaba.vanilife.islands.island

import com.github.shynixn.mccoroutine.folia.launch
import io.papermc.paper.registry.keys.SoundEventKeys
import kotlinx.coroutines.delay
import net.azisaba.vanilife.islands.IslandDefaults
import net.azisaba.vanilife.islands.IslandPos
import net.azisaba.vanilife.islands.persistence.IslandRepository
import net.azisaba.vanilife.islands.waves.IslandWaveAccessor
import net.azisaba.vanilife.islands.waves.WaveAccessor
import net.azisaba.vanilife.islands.wrack.IslandWrackAccessor
import net.azisaba.vanilife.islands.wrack.WrackAccessor
import net.azisaba.vanilife.islands.wrack.WrackType
import net.kyori.adventure.pointer.Pointer
import net.kyori.adventure.pointer.Pointers
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.joml.Vector2fc
import org.joml.Vector3dc
import kotlin.random.Random
import kotlin.uuid.Uuid

class Island internal constructor(
    plugin: Plugin,
    val world: World,
    override val pos: IslandPos,
    override val ownerUuid: Uuid,
    settings: IslandSettings,
    private val islandRepository: IslandRepository,
) : IslandInfo, WaveAccessor by IslandWaveAccessor(pos), WrackAccessor by IslandWrackAccessor(pos, world, plugin) {
    override var settings: IslandSettings = settings
        private set

    private val players: MutableSet<Player> = mutableSetOf()

    init {
        var time = 0L
        plugin.launch {
            while (true) {
                time++
                wrackTick(time)
                waveTick(time)
                delay(50L)
                if (Random.nextDouble() < 0.05) {
                    enqueueSpawnWrack(
                        WrackType(
                            "bottle",
                            Sound.sound(SoundEventKeys.ENTITY_ITEM_PICKUP, Sound.Source.PLAYER, 0.5f, 0.1f),
                            setOf(
                                ItemStack.of(
                                    Material.BREAD
                                )
                            ).iterator()
                        )
                    )
                }
            }
        }
    }

    val spawnPoint: Location
        get() {
            val spawnPoint = Location(
                world,
                pos.centerBlockX().toDouble(),
                IslandDefaults.SEA_LEVEL + 1.0,
                pos.centerBlockZ().toDouble()
            )
            spawnPoint.add(settings.spawnOffset.x(), settings.spawnOffset.y(), settings.spawnOffset.z())
            spawnPoint.addRotation(settings.spawnRotation.x(), settings.spawnRotation.y())
            return spawnPoint
        }

    suspend fun <T> set(pointer: Pointer<T>, value: T) = when (value) {
        IslandInfo.DISPLAY_NAME -> setDisplayName(value as Component?)
        IslandInfo.SPAWN_OFFSET -> setSpawnOffset(value as Vector3dc)
        IslandInfo.SPAWN_ROTATION -> setSpawnRotation(value as Vector2fc)
        else -> throw IllegalArgumentException("Invalid island pointer: $pointer")
    }

    suspend fun setDisplayName(displayName: Component?) {
        islandRepository.updateDisplayName(pos, displayName)
        settings = settings.copy(displayName = displayName)
    }

    suspend fun setSpawnOffset(spawnOffset: Vector3dc) {
        islandRepository.updateSpawnOffset(pos, spawnOffset)
        settings = settings.copy(spawnOffset = spawnOffset)
    }

    suspend fun setSpawnRotation(spawnRotation: Vector2fc) {
        islandRepository.updateSpawnRotation(pos, spawnRotation)
        settings = settings.copy(spawnRotation = spawnRotation)
    }

    fun addPlayer(player: Player) {
        players.add(player)
        addWaveViewer(player.uniqueId)
        addWrackViewer(player)
    }

    fun removePlayer(player: Player) {
        players.remove(player)
        removeWaveViewer(player.uniqueId)
        removeWrackViewer(player)
    }

    override fun pointers(): Pointers = super.pointers()
}
