package net.azisaba.vanilife.farming

import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin() {
    override fun onEnable() {
        setupEventListeners()
    }
}
