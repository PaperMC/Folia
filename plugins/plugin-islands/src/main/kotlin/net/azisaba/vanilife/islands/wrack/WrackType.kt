package net.azisaba.vanilife.islands.wrack

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.data.renderer.ModelRenderer
import net.kyori.adventure.sound.Sound
import org.bukkit.inventory.ItemStack

data class WrackType(val modelName: String, val dropSound: Sound, val itemStacks: Iterator<ItemStack>) {
    fun modelOrThrow(): ModelRenderer = BetterModel.model(modelName).orElseThrow()
}
