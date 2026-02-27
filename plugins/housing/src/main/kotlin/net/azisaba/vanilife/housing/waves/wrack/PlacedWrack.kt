package net.azisaba.vanilife.housing.waves.wrack

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import io.papermc.paper.registry.keys.SoundEventKeys
import kotlinx.coroutines.withContext
import kr.toxicity.model.api.bukkit.platform.BukkitEntity
import kr.toxicity.model.api.bukkit.platform.BukkitLocation
import kr.toxicity.model.api.entity.BaseEntity
import kr.toxicity.model.api.event.hitbox.HitBoxDamagedEvent
import kr.toxicity.model.api.event.hitbox.HitBoxInteractEvent
import kr.toxicity.model.api.nms.HitBoxListener
import kr.toxicity.model.api.platform.PlatformPlayer
import kr.toxicity.model.api.tracker.DummyTracker
import kr.toxicity.model.api.util.function.BonePredicate
import net.azisaba.vanilife.housing.waves.WavePos
import net.kyori.adventure.sound.Sound
import org.bukkit.World
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import kotlin.random.Random

@ConsistentCopyVisibility
internal data class PlacedWrack private constructor(val holder: Plugin, val wrackType: WrackType, val tracker: DummyTracker) {
    fun interact(event: HitBoxInteractEvent) {
        val hitBoxSource = (event.hitBox.source() as? BukkitEntity)?.source()
        hitBoxSource?.remove()
        dropItemStack()
        tracker.close()
    }

    fun damage(event: HitBoxDamagedEvent) {
        val hitBoxSource = (event.hitBox.source() as? BukkitEntity)?.source()
        hitBoxSource?.remove()
        dropItemStack()
        tracker.close()
    }

    fun dropItemStack() {
        val location = (tracker.location() as? BukkitLocation)?.source() ?: return
        holder.launch(holder.regionDispatcher(location)) {
            location.world.playSound(Sound.sound(SoundEventKeys.ENTITY_ITEM_PICKUP, Sound.Source.PLAYER, 0.5f, 0.1f))
            wrackType.itemStacks.forEach {
                location.world.dropItemNaturally(location, it)
            }
        }
    }

    fun show(players: List<PlatformPlayer>) {
        if (tracker.isClosed) return
        players.forEach(tracker::spawn)
        players.forEach(tracker::show)
    }

    companion object {
        private val landFinder: AsyncLandFinder = AsyncLandFinder(Random(System.currentTimeMillis()))

        suspend fun tryPlace(plugin: Plugin, world: World, wavePos: WavePos, wrackType: WrackType): PlacedWrack? {
            val location = landFinder.find(plugin, world, wavePos) ?: return null

            val tracker = wrackType.modelOrThrow().create(BukkitLocation(location))
            val placedWrack = PlacedWrack(plugin, wrackType, tracker)

            withContext(plugin.regionDispatcher(location)) {
                val textDisplay = world.spawn(location, TextDisplay::class.java) { it.isPersistent = false }
                val baseEntity = BaseEntity.of(BukkitEntity(textDisplay))
                val hitBoxListener = HitBoxListener.builder()
                    .listen(HitBoxInteractEvent::class.java, placedWrack::interact)
                    .listen(HitBoxDamagedEvent::class.java, placedWrack::damage)
                    .build()
                tracker.createHitBox(baseEntity, hitBoxListener, BonePredicate.TRUE)
            }

            return placedWrack
        }
    }
}
