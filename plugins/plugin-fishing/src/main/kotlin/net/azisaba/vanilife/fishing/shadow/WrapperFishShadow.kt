package net.azisaba.vanilife.fishing.shadow

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.util.Quaternion4f
import com.github.retrooper.packetevents.util.Vector3f
import me.tofaa.entitylib.container.EntityContainer
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta
import me.tofaa.entitylib.meta.display.TextDisplayMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import net.azisaba.vanilife.fishing.FishingFonts
import net.azisaba.vanilife.fishing.ai.DirectEscapeFightingBehavior
import net.azisaba.vanilife.fishing.ai.FishBehavior
import net.azisaba.vanilife.fishing.ai.WavyApproachBehavior
import net.kyori.adventure.text.Component
import io.papermc.paper.math.FinePosition
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class WrapperFishShadow(private val behavior: FishBehavior) : WrapperEntity(EntityTypes.TEXT_DISPLAY) {
    private var state: State = State.APPROACHING
    private var origin: Position = Position(0.0, 0.0, 0.0)
    private var position: Position = Position(0.0, 0.0, 0.0)
    private var fishHookPosition: Position = Position(0.0, 0.0, 0.0)
    private var previousPosition: Position = Position(0.0, 0.0, 0.0)

    constructor() : this(
        FishBehavior(
            approach = WavyApproachBehavior(),
            fighting = DirectEscapeFightingBehavior(),
        ),
    )

    override fun spawn(location: Location, parent: EntityContainer): Boolean {
        if (!super.spawn(location, parent)) return false

        origin = Position(location.x, location.y, location.z)
        position = origin
        previousPosition = origin
        fishHookPosition = origin

        consumeEntityMeta(TextDisplayMeta::class.java) { meta ->
            meta.text = Component.text(FishingFonts.FishShadows.FISH_SHADOW).font(FishingFonts.FISH_SHADOWS)
            meta.billboardConstraints = AbstractDisplayMeta.BillboardConstraints.FIXED
            meta.backgroundColor = 0
            meta.brightnessOverride = 0x00f000f0
            meta.interpolationDelay = 0
            meta.transformationInterpolationDuration = 5
            meta.leftRotation = createAxisAngleQuaternion(1f, 0f, 0f, -90f)
            meta.rightRotation = Quaternion4f(0f, 0f, 0f, 1f)
            meta.translation = Vector3f(0f, SURFACE_OFFSET_Y, 0f)
            meta.scale = Vector3f(0.8f, 0.8f, 0.8f)
            meta.isSeeThrough = true
        }
        refresh()
        return true
    }

    override fun tick(time: Long) {
        val currentPosition = position
        val nextPosition = when (state) {
            State.APPROACHING -> behavior.approach.tick(time, currentPosition, fishHookPosition)
                ?: run {
                    state = State.FIGHTING
                    fishHookPosition
                }

            State.FIGHTING -> behavior.fighting.tick(time, currentPosition, fishHookPosition, 0.0).toPosition()
        }

        previousPosition = currentPosition
        position = nextPosition.toPosition()
        updateTransform()
    }

    fun setPosition(position: FinePosition) {
        val nextPosition = position.toPosition()
        previousPosition = this.position
        this.position = nextPosition
        updateTransform()
    }

    fun setFishHookPosition(position: FinePosition) {
        fishHookPosition = position.toPosition()
    }

    fun updateTranslation(translationX: Float, translationY: Float, translationZ: Float, directionX: Float, directionZ: Float) {
        previousPosition = Position(
            origin.x() + translationX - directionX,
            origin.y() + translationY,
            origin.z() + translationZ - directionZ,
        )
        position = Position(
            origin.x() + translationX,
            origin.y() + translationY,
            origin.z() + translationZ,
        )
        updateTransform()
    }

    fun setApproaching() {
        state = State.APPROACHING
    }

    fun setFighting() {
        state = State.FIGHTING
    }

    private fun updateTransform() {
        val directionX = (position.x() - previousPosition.x()).toFloat()
        val directionZ = (position.z() - previousPosition.z()).toFloat()
        val translationX = (position.x() - origin.x()).toFloat()
        val translationY = (position.y() - origin.y()).toFloat()
        val translationZ = (position.z() - origin.z()).toFloat()

        consumeEntityMeta(TextDisplayMeta::class.java) { meta ->
            meta.translation = Vector3f(translationX, translationY + SURFACE_OFFSET_Y, translationZ)
            if (directionX != 0f || directionZ != 0f) {
                meta.rightRotation = createFacingQuaternion(directionX, directionZ)
            }
        }
        refresh()
    }

    private fun createFacingQuaternion(directionX: Float, directionZ: Float): Quaternion4f {
        val angle = atan2(-directionX, -directionZ)
        val half = angle / 2f
        return Quaternion4f(0f, 0f, sin(half), cos(half))
    }

    private fun createAxisAngleQuaternion(ax: Float, ay: Float, az: Float, degrees: Float): Quaternion4f {
        val length = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
        val normalizedX = ax / length
        val normalizedY = ay / length
        val normalizedZ = az / length
        val half = Math.toRadians((degrees / 2.0))
        val sin = sin(half).toFloat()
        val cos = cos(half).toFloat()
        return Quaternion4f(normalizedX * sin, normalizedY * sin, normalizedZ * sin, cos)
    }

    private fun FinePosition.toPosition(): Position = Position(x(), y(), z())

    private enum class State {
        APPROACHING,
        FIGHTING,
    }

    private data class Position(
        private val xValue: Double,
        private val yValue: Double,
        private val zValue: Double,
    ) : FinePosition {
        override fun x(): Double = xValue

        override fun y(): Double = yValue

        override fun z(): Double = zValue
    }

    companion object {
        private const val SURFACE_OFFSET_Y: Float = 0.02f
    }
}
