package net.azisaba.vanilife.islands.wrack

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import kr.toxicity.model.api.bukkit.platform.BukkitEntity
import kr.toxicity.model.api.bukkit.platform.BukkitLocation
import kr.toxicity.model.api.bukkit.platform.BukkitPlayer
import kr.toxicity.model.api.entity.BaseEntity
import kr.toxicity.model.api.event.hitbox.HitBoxDamagedEvent
import kr.toxicity.model.api.event.hitbox.HitBoxEvent
import kr.toxicity.model.api.event.hitbox.HitBoxInteractEvent
import kr.toxicity.model.api.nms.HitBoxListener
import kr.toxicity.model.api.tracker.DummyTracker
import kr.toxicity.model.api.util.function.BonePredicate
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class WrackEntity(
    private val wrackType: WrackType,
    private val world: World,
    private val path: DriftPath,
    private val spawnTime: Long,
    private val onDestroyed: (WrackEntity) -> Unit,
) : KoinComponent {
    private val plugin: Plugin by inject()

    private val totalTicks: Long = path.random.nextLong(20L * 15, 20L * 30)

    private val tracker: DummyTracker = wrackType.modelOrThrow()
        .create(BukkitLocation(Location(world, path.startPos.x(), path.startPos.y(), path.startPos.z())))

    fun addViewer(player: Player) {
        val platformPlayer = BukkitPlayer(player)
        tracker.spawn(platformPlayer)
        tracker.show(platformPlayer)
    }

    fun removeViewer(player: Player) {
        tracker.remove(BukkitPlayer(player))
    }

    fun tick(time: Long): Boolean {
        val elapsed = time - spawnTime
        if (elapsed < 0) return true

        val progress = (elapsed.toDouble() / totalTicks).coerceIn(0.0, 1.0)
        val computedPos = path.computePos(progress)
        tracker.location(BukkitLocation(Location(world, computedPos.x(), computedPos.y(), computedPos.z())))

        if (elapsed >= totalTicks) {
            strandTick()
            return false
        }

        return true
    }

    private fun strandTick() {
        val location = Location(world, path.endPos.x(), path.endPos.y(), path.endPos.z())
        plugin.launch(plugin.regionDispatcher(location)) {
            val textDisplay = world.spawn(location, TextDisplay::class.java) { it.isPersistent = false }
            tracker.createHitBox(
                BaseEntity.of(BukkitEntity(textDisplay)),
                HitBoxListener.builder()
                    .listen(HitBoxInteractEvent::class.java, ::interact)
                    .listen(HitBoxDamagedEvent::class.java, ::interact)
                    .build(),
                BonePredicate.TRUE,
            )
        }
    }

    private fun interact(event: HitBoxEvent) {
        val hitBoxSource = (event.hitBox.source() as? BukkitEntity)?.source()
        hitBoxSource?.remove()

        val dropLocation = Location(world, path.endPos.x(), path.endPos.y(), path.endPos.z())
        plugin.launch(plugin.regionDispatcher(dropLocation)) {
            world.playSound(wrackType.dropSound, dropLocation.x(), dropLocation.y(), dropLocation.z())
            wrackType.itemStacks.forEach {
                world.dropItemNaturally(dropLocation, it)
            }
        }

        tracker.close()
        onDestroyed(this)
    }
}
