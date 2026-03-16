package net.azisaba.vanilife.mining

import net.azisaba.vanilife.mining.combo.setupMiningComboBetterHudPlaceholders
import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin() {
    override fun onEnable() {
        MiningRecipes.bootstrap(server)

        setupEventListeners()
        setupMiningComboBetterHudPlaceholders()
    }
}
