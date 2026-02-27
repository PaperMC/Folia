package net.azisaba.vanilife.housing.waves.wrack

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.data.renderer.ModelRenderer
import org.bukkit.inventory.ItemStack

data class WrackType(val modelName: String, val itemStacks: Iterator<ItemStack>) {
    fun modelOrThrow(): ModelRenderer = BetterModel.model(modelName).orElseThrow()
}
