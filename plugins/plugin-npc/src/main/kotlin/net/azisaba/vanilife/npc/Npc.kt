package net.azisaba.vanilife.npc

import kr.toxicity.model.api.tracker.Tracker
import org.bukkit.entity.Mob
import org.bukkit.inventory.Merchant

data class Npc(
    val mob: Mob,
    val tracker: Tracker,
    val merchant: Merchant,
)
