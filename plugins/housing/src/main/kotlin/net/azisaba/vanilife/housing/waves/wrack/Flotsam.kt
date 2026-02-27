package net.azisaba.vanilife.housing.waves.wrack

import com.github.retrooper.packetevents.protocol.world.Location
import com.github.shynixn.mccoroutine.folia.launch
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import kr.toxicity.model.api.bukkit.platform.BukkitLocation
import kr.toxicity.model.api.bukkit.platform.BukkitPlayer
import kr.toxicity.model.api.bukkit.platform.BukkitWorld
import kr.toxicity.model.api.tracker.DummyTracker
import kr.toxicity.model.api.tracker.ModelScaler
import net.azisaba.vanilife.housing.waves.WavePos
import net.azisaba.vanilife.housing.waves.WrapperWave
import net.azisaba.vanilife.islands.IslandDefaults
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.joml.Vector3d
import org.joml.Vector3dc
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal class Flotsam private constructor(
    private val wrackType: WrackType,
    private val tracker: DummyTracker
) : KoinComponent {
    private val plugin: Plugin by inject()

    private val random: Random = Random(System.currentTimeMillis())
    private val offsetRandom: FloatingOffsetRandom = FloatingOffsetRandom(random)

    fun addViewer(uuid: UUID) {
        val player = Bukkit.getPlayer(uuid) ?: return
        val platformPlayer = BukkitPlayer(player)
        tracker.spawn(platformPlayer)
        tracker.show(platformPlayer)
    }

    fun removeViewer(uuid: UUID) {
        val player = Bukkit.getPlayer(uuid) ?: return
        tracker.hide(BukkitPlayer(player))
    }

    fun driftTick(time: Long, pos: WavePos, location: Location) {
        val world = (tracker.location().world() as? BukkitWorld)?.source() ?: return
        val baseLocation = pos.computeForward(location, 9.5)
        val paperLocation = SpigotConversionUtil.toBukkitLocation(world, offsetRandom.compute(baseLocation, time, 1.0))
        val platformLocation = BukkitLocation(paperLocation)
        tracker.location(platformLocation)
    }

    fun endTick(pos: WavePos) {
        val world = (tracker.location().world() as? BukkitWorld)?.source() ?: return
        plugin.launch {
            val placed = PlacedWrack.tryPlace(plugin, world, pos, wrackType)
            placed?.show(tracker.pipeline.allPlayer().toList())
            tracker.close()
        }
    }

    companion object {
        fun create(wrackType: WrackType, wrapperWave: WrapperWave): Flotsam {
            val world = Bukkit.getWorld(IslandDefaults.WORLD_KEY)!!
            val paperLocation = SpigotConversionUtil.toBukkitLocation(world, wrapperWave.location)
            val platformLocation = BukkitLocation(paperLocation)

            val tracker = wrackType.modelOrThrow().create(platformLocation)
            tracker.scaler(ModelScaler.value(2.5f))

            return Flotsam(wrackType, tracker)
        }
    }
}

private class FloatingOffsetRandom(random: Random) {
    private val amplitude: Vector3dc = Vector3d(
        random.nextDouble(0.05, 0.18),
        random.nextDouble(0.18, 0.42),
        random.nextDouble(0.05, 0.18),
    )
    private val frequency: Vector3dc = Vector3d(
        random.nextDouble(1.0, 2.2),
        random.nextDouble(1.6, 3.0),
        random.nextDouble(1.0, 2.2),
    )
    private val phase: Vector3dc = Vector3d(
        random.nextDouble(0.0, PI * 2.0),
        random.nextDouble(0.0, PI * 2.0),
        random.nextDouble(0.0, PI * 2.0)
    )

    fun compute(base: Location, tick: Long, intensity: Double): Location {
        val t = tick.toDouble() / TICKS_PER_SECOND
        val dx = sin(t * frequency.x() + phase.x()) * amplitude.x() * intensity
        val dy = sin(t * frequency.y() + phase.y()) * amplitude.y() * intensity
        val dz = cos(t * frequency.z() + phase.z()) * amplitude.z() * intensity
        return Location(
            base.x + dx,
            base.y + dy,
            base.z + dz,
            base.yaw,
            base.pitch,
        )
    }

    companion object {
        private const val TICKS_PER_SECOND = 20.0
    }
}
