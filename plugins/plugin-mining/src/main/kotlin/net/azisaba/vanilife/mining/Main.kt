package net.azisaba.vanilife.mining

import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin() {
    override fun onEnable() {
        MiningRecipes.bootstrap(server)
    }
}
