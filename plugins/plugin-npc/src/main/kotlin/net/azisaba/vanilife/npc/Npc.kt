package net.azisaba.vanilife.npc

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import kr.toxicity.model.api.animation.AnimationIterator
import kr.toxicity.model.api.animation.AnimationModifier
import kr.toxicity.model.api.bukkit.platform.BukkitEntity
import kr.toxicity.model.api.tracker.Tracker
import net.azisaba.vanilife.fishing.FishingItems
import net.azisaba.vanilife.islands.Island
import net.azisaba.vanilife.npc.ai.ReadRecipeGoal
import net.azisaba.vanilife.npc.ai.SitGoal
import net.azisaba.vanilife.npc.ai.TradingGoal
import net.azisaba.vanilife.npc.recipe.UnreadableRecipe
import net.azisaba.vanilife.npc.recipe.UnreadableRecipeReader
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Nameable
import org.bukkit.RegionAccessor
import org.bukkit.entity.Chicken
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Merchant
import org.bukkit.inventory.MerchantRecipe

fun RegionAccessor.spawn(location: Location, npcType: NpcType): Npc {
    val chicken = spawn(location, Chicken::class.java) { spawned ->
        spawned.isSilent = true
        spawned.isPersistent = false
    }
    return WildNpcImpl(npcType, chicken)
}

sealed interface Npc : Audience, Nameable, UnreadableRecipeReader {
    val npcType: NpcType

    val merchant: Merchant

    val isSitting: Boolean

    fun sitDown()

    fun standUp()

    fun updateMerchantRecipes()

    fun remove()

    @Deprecated("Use Npc#customName()")
    override fun getCustomName(): String? = TODO()

    @Deprecated("Use Npc#customName(net.kyori.adventure.text.Component)")
    override fun setCustomName(name: String?) = TODO()

    sealed interface Wild : Npc {
        override fun customName(): Component? = null

        override fun customName(customName: Component?) {
        }

        fun tame(player: Player)
    }

    sealed interface Tamed : Npc {
        val island: Island
    }
}

private abstract class AbstractNpcImpl(override val npcType: NpcType, protected val mob: Mob) : Npc {
    override var merchant: Merchant = this.npcType.offers.bake(12)

    override val isSitting: Boolean
        get() = tracker.bones().any { bone -> bone.runningAnimation()?.name == "sit" }

    protected val tracker: Tracker = npcType.modelOrThrow().create(BukkitEntity(mob))

    init {
        Bukkit.getMobGoals().addGoal(mob, 1, ReadRecipeGoal(this, mob, tracker))
        Bukkit.getMobGoals().addGoal(mob, 3, TradingGoal(this, mob, tracker))
        Bukkit.getMobGoals().addGoal(mob, 2, SitGoal(this, mob, tracker))
    }

    override fun sitDown() {
        tracker.animate(
            "sit",
            AnimationModifier.builder()
                .type(AnimationIterator.Type.LOOP)
                .build()
        )
    }

    override fun standUp() {
        tracker.stopAnimation("sit")
    }

    override fun updateMerchantRecipes() {
        merchant = npcType.offers.bake(12)
    }

    override fun remove() {
        mob.remove()
        tracker.close()
    }
}

private class WildNpcImpl(npcType: NpcType, mob: Mob) : AbstractNpcImpl(npcType, mob), Npc.Wild {
    override val readRecipes: Collection<UnreadableRecipe>
        get() = _readRecipes.toSet()

    private val _readRecipes: MutableSet<UnreadableRecipe> = mutableSetOf()

    override fun readRecipe(recipe: UnreadableRecipe) {
        _readRecipes.add(recipe)
        merchant.recipes = merchant.recipes + listOf(
            MerchantRecipe(recipe.createResultItem(), 5).apply {
                addIngredient(ItemStack.of(FishingItems.SALMON))
            }
        )
    }

    override fun canRead(recipe: UnreadableRecipe): Boolean = true

    override fun tame(player: Player) {
        val dialog = Dialog.create { builder ->
            builder.empty()
                .base(
                    DialogBase.builder(Component.translatable("dialog.vanilife.tame"))
                        .inputs(
                            listOf(
                                DialogInput.text("name", Component.translatable("dialog.vanilife.name")).build()
                            )
                        ).build()
                )
                .type(
                    DialogType.notice(
                        ActionButton.builder(Component.translatable("dialog.vanilife.tame.complete")).build()
                    )
                )
        }
        player.showDialog(dialog)
    }
}
