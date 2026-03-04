package net.azisaba.vanilife.islands.waves

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.particle.Particle
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle
import me.tofaa.entitylib.container.EntityContainer
import me.tofaa.entitylib.meta.display.TextDisplayMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import net.azisaba.vanilife.islands.IslandDefaults
import net.azisaba.vanilife.islands.IslandsFonts
import net.kyori.adventure.text.Component
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal class WrapperWave(val pos: WavePos) : WrapperEntity(EntityTypes.TEXT_DISPLAY) {
    private val random: Random = Random(pos.computeSeed())
    private var cycleRandom: CycleRandom = CycleRandom.roll(random)
    private val ticksOffset: Long = random.nextLong(0L, CYCLE_TICKS)

    override fun spawn(location: Location, parent: EntityContainer): Boolean {
        if (!super.spawn(location, parent)) return false
        consumeEntityMeta(TextDisplayMeta::class.java) { meta ->
            meta.text = Component.text(IslandsFonts.Waves.LARGE_0).font(IslandsFonts.WAVES)
            meta.backgroundColor = 0
            meta.brightnessOverride = 0x00f000f0
            meta.leftRotation = pos.coastSide.rotation
            meta.transformationInterpolationDuration = 5
        }
        refresh()
        return true
    }

    override fun tick(time: Long) {
        val progress = progressAt(time)
        if ((time + ticksOffset) % CYCLE_TICKS == 0L) {
            startCycleTick()
        } else if (progress < cycleRandom.movementProgressEnd) {
            movementTick(time, progress)
        } else if (!entityMeta.isInvisible) {
            movementEndTick()
        }
    }

    private fun startCycleTick() {
        cycleRandom = CycleRandom.roll(random)
        consumeEntityMeta(TextDisplayMeta::class.java) { meta ->
            meta.translation = Vector3f()
            meta.scale = Vector3f(cycleRandom.scale, cycleRandom.scale, cycleRandom.scale)
            meta.isInvisible = false
        }
        refresh()
    }

    private fun movementTick(time: Long, computedProgress: Double) {
        val target = computeLocation(computedProgress)
        val dx = target.x - x
        val dy = target.y - y
        val dz = target.z - z

        val radians = Math.toRadians(target.yaw.toDouble())
        val cos = cos(radians)
        val sin = sin(radians)
        val lx = dx * cos + dz * sin
        val lz = -dx * sin + dz * cos
        val translation = Vector3f(lx.toFloat(), dy.toFloat(), lz.toFloat())

        consumeEntityMeta(TextDisplayMeta::class.java) { meta ->
            meta.translation = translation
        }
        refresh()
    }

    private fun movementEndTick() {
        val particlePacket = WrapperPlayServerParticle(
            Particle(ParticleTypes.POOF),
            false,
            pos.computeForward(computeLocation(cycleRandom.movementProgressEnd), 4.5).position,
            Vector3f(0.9f, 0f, 0.9f),
            0.01f,
            6,
            false
        )
        sendPacketsToViewers(particlePacket)

        consumeEntityMeta(TextDisplayMeta::class.java) { meta ->
            meta.isInvisible = true
        }
        refresh()
    }

    private fun progressAt(ticks: Long): Double {
        val raw = (ticks + ticksOffset).toDouble() / CYCLE_TICKS.toDouble()
        val wrapped = raw % 1.0
        return if (wrapped < 0.0) wrapped + 1.0 else wrapped
    }

    private fun computeLocation(progress: Double): Location {
        val coastSize = if (pos.coastSide.axisX) IslandDefaults.ISLAND_SIZE_X_BLOCKS else IslandDefaults.ISLAND_SIZE_Z_BLOCKS

        val forwardEnd = (coastSize * 0.18 - 20.0).coerceIn(12.0, 30.0)
        val forwardSpin = (coastSize * 0.078).coerceIn(10.0, 28.0)
        val forwardStart = forwardEnd + forwardSpin

        val lateralInset = 37.0
        val lateralStart = if (pos.coastSide.axisX) pos.islandPos.minBlockZ() else pos.islandPos.minBlockX()
        val lateralEnd = if (pos.coastSide.axisX) pos.islandPos.maxBlockZ() else pos.islandPos.maxBlockX()
        val lateralStep = (lateralEnd - lateralStart) / (WavePos.WAVES_PER_COAST_SIDE - 1).toDouble()
        val lateralMin = (lateralStart + lateralInset).coerceAtMost(lateralEnd - lateralInset)
        val lateralMax = (lateralEnd - lateralInset).coerceAtLeast(lateralMin)
        val lateralRaw = lateralStart + lateralStep * pos.index + cycleRandom.lateralOffset
        val lateral = lateralRaw.coerceIn(lateralMin, lateralMax)

        val t = (progress / cycleRandom.movementProgressEnd).coerceIn(0.0, 1.0)
        val eased = 1.0 - (1.0 - t) * (1.0 - t)
        val blended = t * (1.0 - 0.25) + eased * 0.25
        val active = (blended * 0.96).coerceIn(0.0, 1.0)

        val forwardRaw = forwardStart - forwardSpin * active + cycleRandom.forwardOffset
        val forward = forwardRaw.coerceIn(forwardEnd, forwardStart)
        val fixed = pos.edgeCoord() + pos.coastSide.coastNormalSign * forward

        val bob = sin((progress * 0.07) + ((ticksOffset.toDouble() / CYCLE_TICKS.toDouble()) * (PI * 2.0))) * 0.12
        val x = if (pos.coastSide.axisX) fixed + bob else lateral
        val z = if (pos.coastSide.axisX) lateral else fixed + bob

        return Location(x, IslandDefaults.SEA_LEVEL + 0.15, z, pos.coastSide.yaw, 0f)
    }

    companion object {
        const val CYCLE_TICKS: Long = 20L * 5
    }

    private data class CycleRandom(
        val scale: Float,
        val forwardOffset: Double,
        val lateralOffset: Double,
        val movementProgressEnd: Double,
    ) {
        companion object {
            fun roll(random: Random): CycleRandom {
                val scale = 1f * random.nextDouble(0.72, 1.38).toFloat()
                val forwardOffset = random.nextDouble(-5.5, 5.5)
                val lateralOffset = random.nextDouble(-8.0, 8.0)
                val movementProgressEnd = random.nextDouble(0.66, 0.80)
                return CycleRandom(scale, forwardOffset, lateralOffset, movementProgressEnd)
            }
        }
    }
}
