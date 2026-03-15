package net.azisaba.vanilife.npc.ai

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import io.papermc.paper.registry.keys.SoundEventKeys
import kr.toxicity.model.api.bukkit.platform.BukkitPlayer
import kr.toxicity.model.api.event.hitbox.HitBoxInteractEvent
import kr.toxicity.model.api.tracker.Tracker
import kr.toxicity.model.api.tracker.TrackerUpdateAction
import net.azisaba.vanilife.npc.Npc
import net.azisaba.vanilife.npc.recipe.UnreadableRecipe
import net.kyori.adventure.sound.Sound
import org.bukkit.Particle
import org.bukkit.entity.Mob
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.concurrent.atomic.AtomicLong

internal class ReadRecipeGoal(private val npc: Npc, private val mob: Mob, private val tracker: Tracker) : Goal<Mob> {
    private var readRequest: ReadRequest? = null

    private var readingRecipe: UnreadableRecipe? = null

    private var remainingReadTime: AtomicLong = AtomicLong(0)

    init {
        tracker.listenHitBox(HitBoxInteractEvent::class.java, ::handleHitboxInteract)
    }

    override fun getKey(): GoalKey<Mob> = NpcGoalKeys.READ_RECIPE

    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.LOOK, GoalType.JUMP)

    override fun shouldActivate(): Boolean = readRequest != null && readingRecipe == null

    override fun shouldStayActive(): Boolean = readingRecipe != null

    override fun start() {
        val (unreadableRecipe, itemStack) = readRequest ?: return

        itemStack.subtract()

        this.readRequest = null
        readingRecipe = unreadableRecipe

        remainingReadTime.set(20L * 2)

        npc.sitDown()

        mob.equipment.setItemInMainHand(itemStack)
        tracker.update(TrackerUpdateAction.itemMapping())
    }

    override fun stop() {
        readingRecipe = null
        mob.equipment.setItemInMainHand(null)
        tracker.update(TrackerUpdateAction.itemMapping())

        npc.standUp()
    }

    override fun tick() {
        val readingRecipe = readingRecipe ?: return
        val remainingReadTime = remainingReadTime.decrementAndGet()

        mob.world.spawnParticle(Particle.ENCHANT, mob.location, (2..5).random())

        if (remainingReadTime <= 0) {
            npc.readUnreadableRecipe(readingRecipe)
            mob.world.playSound(
                Sound.sound(SoundEventKeys.ENTITY_EXPERIENCE_ORB_PICKUP, Sound.Source.PLAYER, 1f, 2f),
                mob.x,
                mob.y,
                mob.z,
            )
            mob.world.dropItemNaturally(mob.location, readingRecipe.createResultItem())
            this.readingRecipe = null
        }
    }

    private fun handleHitboxInteract(event: HitBoxInteractEvent) {
        if (readingRecipe != null) return

        val player = (event.who as? BukkitPlayer)?.source() ?: return

        val itemStack = player.equipment.itemInMainHand
        val serverItem = itemStack.serverItem() ?: return
        val unreadableRecipe = UnreadableRecipe.byRecipeItem(serverItem) ?: return
        if (unreadableRecipe.canReadBy(npc)) {
            readRequest = ReadRequest(unreadableRecipe, itemStack)
        }
    }

    private data class ReadRequest(val unreadableRecipe: UnreadableRecipe, val itemStack: ItemStack)
}
