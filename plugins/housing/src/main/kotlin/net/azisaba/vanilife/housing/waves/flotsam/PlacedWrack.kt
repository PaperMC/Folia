package net.azisaba.vanilife.housing.waves.flotsam

import com.github.shynixn.mccoroutine.folia.launch
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import io.papermc.paper.registry.keys.SoundEventKeys
import kotlinx.coroutines.withContext
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.bukkit.platform.BukkitEntity
import kr.toxicity.model.api.bukkit.platform.BukkitLocation
import kr.toxicity.model.api.entity.BaseEntity
import kr.toxicity.model.api.event.hitbox.HitBoxDamagedEvent
import kr.toxicity.model.api.event.hitbox.HitBoxInteractEvent
import kr.toxicity.model.api.nms.HitBoxListener
import kr.toxicity.model.api.platform.PlatformPlayer
import kr.toxicity.model.api.tracker.DummyTracker
import kr.toxicity.model.api.tracker.ModelScaler
import kr.toxicity.model.api.util.function.BonePredicate
import net.kyori.adventure.sound.Sound
import org.bukkit.Location
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

@ConsistentCopyVisibility
data class PlacedWrack private constructor(val holder: Plugin, val tracker: DummyTracker, val itemStack: ItemStack) {
    fun interact(event: HitBoxInteractEvent) {
        dropItemStack()
        tracker.close()
    }

    fun damage(event: HitBoxDamagedEvent) {
        dropItemStack()
        tracker.close()
    }

    fun dropItemStack() {
        val location = (tracker.location() as? BukkitLocation)?.source() ?: return
        holder.launch(holder.regionDispatcher(location)) {
            location.world.playSound(Sound.sound(SoundEventKeys.ENTITY_ITEM_PICKUP, Sound.Source.PLAYER, 0.5f, 0.1f))
            location.world.dropItemNaturally(location, itemStack)
        }
    }

    fun show(players: List<PlatformPlayer>) {
        if (tracker.isClosed) return
        players.forEach(tracker::spawn)
        players.forEach(tracker::show)
    }

    companion object {
        suspend fun place(plugin: Plugin, location: Location, itemStack: ItemStack): PlacedWrack {
            val tracker = BetterModel.model("bottle")
                .orElseThrow()
                .create(BukkitLocation(location))
            tracker.scaler(ModelScaler.value(2.5f))


            val placedWrack = PlacedWrack(plugin, tracker, itemStack)
            withContext(plugin.regionDispatcher(location)) {
                val textDisplay = location.world.spawn(location, TextDisplay::class.java) { it.isPersistent = false }
                val a = BaseEntity.of(BukkitEntity(textDisplay))
                val hitBoxListener = HitBoxListener.builder()
                    .listen(HitBoxInteractEvent::class.java, placedWrack::interact)
                    .listen(HitBoxDamagedEvent::class.java, placedWrack::damage)
                    .build()
                tracker.createHitBox(a, hitBoxListener, BonePredicate.TRUE)
            }

            return placedWrack
        }
    }
}
