package net.azisaba.vanilife.npc

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.data.renderer.ModelRenderer
import net.azisaba.vanilife.npc.trading.MerchantConstructor
import net.azisaba.vanilife.npc.trading.MerchantConstructors

data class NpcType(val modelName: String, val merchantConstructor: MerchantConstructor) {
    fun modelOrThrow(): ModelRenderer = BetterModel.model(modelName).orElseThrow()

    companion object {
        val PLAINS: NpcType = NpcType(
            "npc",
            MerchantConstructors.PLAINS,
        )
    }
}
