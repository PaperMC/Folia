package net.azisaba.vanilife.npc

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.data.renderer.ModelRenderer
import net.azisaba.vanilife.npc.trading.NEKO_OFFERS
import net.azisaba.vanilife.npc.trading.NpcOffer
import net.azisaba.vanilife.npc.trading.NpcOffers

data class NpcType(val icon: Char, val modelName: String, val offers: NpcOffers) {
    fun modelOrThrow(): ModelRenderer = BetterModel.model(modelName).orElseThrow()

    companion object {
        val NEKO: NpcType = NpcType(
            NpcFonts.NpcIcons.NEKO,
            "npc",
            NpcOffer.NEKO_OFFERS,
        )
    }
}
