package net.azisaba.vanilife.housing.waves.flotsam

import com.github.retrooper.packetevents.protocol.world.Location
import com.github.shynixn.mccoroutine.folia.launch
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.bukkit.platform.BukkitLocation
import kr.toxicity.model.api.bukkit.platform.BukkitPlayer
import kr.toxicity.model.api.bukkit.platform.BukkitWorld
import kr.toxicity.model.api.tracker.DummyTracker
import kr.toxicity.model.api.tracker.ModelScaler
import net.azisaba.vanilife.housing.waves.WavePos
import net.azisaba.vanilife.housing.waves.WrapperWave
import net.azisaba.vanilife.islands.IslandDefaults
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import kotlin.random.Random

data class Flotsam(val wavePos: WavePos, val tracker: DummyTracker, val itemStack: ItemStack) : KoinComponent {
    private val plugin: Plugin by inject()

    private val random: Random = Random(wavePos.computeSeed() xor System.nanoTime())
    private val offset: FloatingOffset = FloatingOffset(random)

    fun addViewer(uuid: UUID) {
        val player = Bukkit.getPlayer(uuid) ?: return
        val platformPlayer = BukkitPlayer(player)
        tracker.spawn(platformPlayer)
        tracker.show(platformPlayer)
    }

    fun removeViewer(uuid: UUID) {
        val player = Bukkit.getPlayer(uuid) ?: return
        val platformPlayer = BukkitPlayer(player)
        tracker.hide(platformPlayer)
    }

    fun driftTick(time: Long, location: Location) {
        val world = (tracker.location().world() as? BukkitWorld)?.source() ?: return
        val baseLocation = wavePos.computeForward(location, 9.5)
        val paperLocation = SpigotConversionUtil.toBukkitLocation(world, offset.compute(baseLocation, time, 1.0))
        val platformLocation = BukkitLocation(paperLocation)
        tracker.location(platformLocation)
    }

    fun endTick() {
        val world = (tracker.location().world() as? BukkitWorld)?.source() ?: return

        plugin.launch {
            val finder = AsyncLandFinder(plugin, world, random)
            val location = finder.find(wavePos)?.add(0.0, 1.0, 0.0) ?: return@launch
            val placed = PlacedWrack.place(plugin, location, itemStack)
            placed.show(tracker.pipeline.allPlayer().toList())
            tracker.close()
        }
    }

    companion object {
        fun create(wrapperWave: WrapperWave, itemStack: ItemStack): Flotsam {
            val world = Bukkit.getWorld(IslandDefaults.WORLD_KEY)!!
            val paperLocation = SpigotConversionUtil.toBukkitLocation(world, wrapperWave.location)
            val platformLocation = BukkitLocation(paperLocation)

            val tracker = BetterModel.model("bottle").orElseThrow().create(platformLocation)
            tracker.scaler(ModelScaler.value(2.5f))

            return Flotsam(wrapperWave.pos, tracker, itemStack)
        }
    }
}
