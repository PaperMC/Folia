package net.azisaba.vanilife.fishing.game

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import io.papermc.paper.registry.keys.SoundEventKeys
import kotlinx.coroutines.withContext
import net.azisaba.vanilife.fishing.FishType
import net.azisaba.vanilife.fishing.FishingContext
import net.azisaba.vanilife.fishing.loot
import net.azisaba.vanilife.fishing.shadow.WrapperFishShadow
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

class FishingGame(val plugin: Plugin, val context: FishingContext) {
    private val fishType: FishType = FishType.loot(context)
    private var fishStamina: Double = 0.5
    private val fishShadow: WrapperFishShadow = WrapperFishShadow(context.fishHook, fishType.behavior).apply {
        addViewer(context.player.uniqueId)
        plugin.launch(plugin.regionDispatcher(context.fishHook.location)) {
            spawn(SpigotConversionUtil.fromBukkitLocation(computeFishHookSurfaceLocation()))
        }
    }

    private val timeCounter: AtomicLong = AtomicLong(0L)

    private val playerPullRequestCounter: AtomicInteger = AtomicInteger(0)

    private val playerPullSustainTimeCounter: AtomicInteger = AtomicInteger(0)

    private val bossBar: BossBar = BossBar.bossBar(
        Component.text("Fishing"),
        0.5f,
        BossBar.Color.PINK,
        BossBar.Overlay.PROGRESS,
    )

    suspend fun tick(): Boolean {
        if (!context.player.isOnline || !context.fishHook.isValid) {
            return false
        }

        val time = timeCounter.getAndIncrement()

        val previousFishShadowLocation = fishShadow.translatedLocation
        fishShadow.tick(time)
        val currentFishShadowLocation = fishShadow.translatedLocation

        if (fishShadow.state == WrapperFishShadow.State.FIGHTING) {
            context.player.showBossBar(bossBar)

            val fishEscapeVelocity = Vector(
                currentFishShadowLocation.x - previousFishShadowLocation.x,
                0.0,
                currentFishShadowLocation.z - previousFishShadowLocation.z,
            )
            updateFishHookVelocity(fishEscapeVelocity, influenceFishStamina = true)

            if (fishStamina <= 0.0) {
                completeCatch()
                return false
            } else if (fishStamina >= 1.0) {
                completeEmptyReel()
                return false
            }
        } else {
            updateFishHookVelocity(influenceFishStamina = false)
        }

        return true
    }

    fun cleanup() {
        fishShadow.remove()
        context.player.hideBossBar(bossBar)
    }

    fun pullFishHook() {
        playerPullRequestCounter.incrementAndGet()
        playerPullSustainTimeCounter.set(4)

        context.player.playSound(
            Sound.sound(
                SoundEventKeys.ENTITY_FISHING_BOBBER_RETRIEVE,
                Sound.Source.PLAYER,
                0.35f,
                1.35f
            )
        )
    }

    private suspend fun completeCatch() {
        val reward = ItemStack.of(fishType.unwrapItem())

        val dropLocation = computeFishHookSurfaceLocation()
        val toPlayerVec = context.player.location.toVector().subtract(dropLocation.toVector())

        plugin.launch(plugin.regionDispatcher(dropLocation)) {
            dropLocation.world.dropItem(dropLocation, reward) { spawned ->
                spawned.setCanMobPickup(false)
                spawned.velocity = if (toPlayerVec.lengthSquared() > 1.0e-6) {
                    toPlayerVec.normalize().multiply(0.35).setY(0.22)
                } else Vector(0.0, 0.22, 0.0)
            }
        }

        plugin.launch(plugin.regionDispatcher(context.fishHook.location)) {
            context.fishHook.retrieve(EquipmentSlot.HAND)
        }
    }

    private fun completeEmptyReel() {
        plugin.launch(plugin.regionDispatcher(context.fishHook.location)) {
            context.fishHook.retrieve(EquipmentSlot.HAND)
        }
    }

    private suspend fun updateFishHookVelocity(fishEscapeVelocity: Vector = Vector(), influenceFishStamina: Boolean) {
        val pullRequests = playerPullRequestCounter.getAndSet(0)
        val sustainedPull = playerPullSustainTimeCounter.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0
        val playerPullStrength = playerPullStrengthOf(pullRequests, sustainedPull)

        val finalVelocity = fishEscapeVelocity.clone()

        if (playerPullStrength > 1.0e-6) {
            val toPlayerRawVec =
                context.player.location.toVector().subtract(computeFishHookSurfaceLocation().toVector()).setY(0)
            val toPlayerVec = if (toPlayerRawVec.lengthSquared() > 1.0e-6) toPlayerRawVec.normalize() else Vector()
            if (toPlayerVec.lengthSquared() > 1.0e-6) {
                val playerPullVelocity =
                    Vector(toPlayerVec.x * playerPullStrength, 0.045, toPlayerVec.z * playerPullStrength)
                finalVelocity.add(playerPullVelocity)
            }
        }

        withContext(plugin.regionDispatcher(context.fishHook.location)) {
            context.fishHook.velocity = finalVelocity
        }

        if (influenceFishStamina) {
            updateFishStamina(playerPullStrength, fishEscapeStrengthOf(fishEscapeVelocity))
        }
    }

    private fun updateFishStamina(playerPullStrength: Double, fishEscapeStrength: Double) {
        val adjustedFishEscapeStrength = fishEscapeStrength * STAMINA_ESCAPE_STRENGTH_SCALE
        val delta = when {
            playerPullStrength > adjustedFishEscapeStrength -> PLAYER_ADVANTAGE_BASE_DELTA - (playerPullStrength - adjustedFishEscapeStrength) * PLAYER_ADVANTAGE_SCALE
            adjustedFishEscapeStrength > playerPullStrength -> FISH_ADVANTAGE_BASE_DELTA + (adjustedFishEscapeStrength - playerPullStrength) * FISH_ADVANTAGE_SCALE
            else -> 0.0
        }
        fishStamina = (fishStamina + delta).coerceIn(0.0, 1.0)
        bossBar.progress(fishStamina.toFloat())
    }

    private fun playerPullStrengthOf(pullRequests: Int, sustainedPull: Boolean): Double = when {
        pullRequests > 0 -> 0.22 + (pullRequests.coerceAtMost(3) - 1) * 0.045
        sustainedPull -> 0.22
        else -> 0.0
    }

    private fun fishEscapeStrengthOf(velocity: Vector): Double = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)

    private suspend fun computeFishHookSurfaceLocation(): Location {
        val location = context.fishHook.location

        val fluidHeight = withContext(plugin.regionDispatcher(location)) {
            val fluidData = location.world.getFluidData(location)
            fluidData.computeHeight(location)
        }

        return location.clone().apply {
            y = blockY + fluidHeight.toDouble()
            yaw = 0f
            pitch = 0f
        }
    }

    private companion object {
        private const val STAMINA_ESCAPE_STRENGTH_SCALE = 0.8
        private const val PLAYER_ADVANTAGE_BASE_DELTA = -0.065
        private const val PLAYER_ADVANTAGE_SCALE = 0.36
        private const val FISH_ADVANTAGE_BASE_DELTA = 0.018
        private const val FISH_ADVANTAGE_SCALE = 0.12
    }
}
