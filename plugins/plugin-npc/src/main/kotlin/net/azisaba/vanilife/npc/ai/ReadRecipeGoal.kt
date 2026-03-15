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
import net.azisaba.vanilife.npc.NpcSoundEvents
import net.azisaba.vanilife.npc.UnreadableRecipe
import net.kyori.adventure.sound.Sound
import org.bukkit.Particle
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

internal class ReadRecipeGoal(private val npc: Npc, private val mob: Mob, private val tracker: Tracker) : Goal<Mob> {
    private var readRequest: ReadRequest? = null

    private var readTask: ReadTask? = null

    private var remainingReadTime: AtomicLong = AtomicLong(0)

    init {
        tracker.listenHitBox(HitBoxInteractEvent::class.java, ::handleHitboxInteract)
    }

    override fun getKey(): GoalKey<Mob> = NpcGoalKeys.READ_RECIPE

    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.LOOK, GoalType.JUMP)

    override fun shouldActivate(): Boolean = readRequest != null && readTask == null && mob.isOnGround

    override fun shouldStayActive(): Boolean = readTask != null

    override fun start() {
        val (player, recipe, itemStack) = readRequest ?: return

        this.readRequest = null
        readTask = ReadTask(player, recipe)

        resetRemainingReadTime()

        npc.sitDown()
        mob.equipment.setItemInMainHand(itemStack.clone())
        tracker.update(TrackerUpdateAction.itemMapping())

        player.playSound(Sound.sound(SoundEventKeys.ITEM_BOOK_PAGE_TURN, Sound.Source.PLAYER, 0.4f, 2f))

        if (!player.gameMode.isInvulnerable) {
            itemStack.subtract()
        }
    }

    override fun stop() {
        readTask = null
        mob.equipment.setItemInMainHand(null)
        tracker.update(TrackerUpdateAction.itemMapping())
        npc.standUp()
    }

    override fun tick() {
        val (player, recipe) = readTask ?: return
        val remainingReadTime = remainingReadTime.decrementAndGet()

        mob.world.spawnParticle(Particle.ENCHANT, mob.location, (2..5).random())

        if (remainingReadTime <= 0) {
            npc.readRecipe(recipe)
            player.playSound(
                Sound.sound(NpcSoundEvents.NPC_READ_RECIPE, Sound.Source.PLAYER, 0.4f, 1f),
                mob.x,
                mob.y,
                mob.z,
            )
            mob.world.spawnParticle(
                Particle.HAPPY_VILLAGER,
                mob.x,
                mob.y + 1.25,
                mob.z,
                Random.nextInt(4, 7),
                0.5,
                0.2,
                0.5,
            )
            this.readTask = null
        }
    }

    private fun resetRemainingReadTime() {
        remainingReadTime.set(20L)
    }

    private fun handleHitboxInteract(event: HitBoxInteractEvent) {
        if (readTask != null || !mob.isOnGround) return

        val player = (event.who as? BukkitPlayer)?.source() ?: return

        val itemStack = player.equipment.itemInMainHand
        val serverItem = itemStack.serverItem() ?: return
        val unreadableRecipe = UnreadableRecipe.byItem(serverItem) ?: return
        readRequest = ReadRequest(player, unreadableRecipe, itemStack)
    }

    private data class ReadRequest(val player: Player, val recipe: UnreadableRecipe, val itemStack: ItemStack)

    private data class ReadTask(val player: Player, val recipe: UnreadableRecipe)
}
