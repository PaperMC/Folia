package net.azisaba.vanilife.fishing

import net.azisaba.vanilife.Season
import org.bukkit.entity.FishHook
import org.bukkit.entity.Player

data class FishingContext(
    val player: Player,
    val fishHook: FishHook,
    val season: Season.Sub = Season.Sub.now(),
)
