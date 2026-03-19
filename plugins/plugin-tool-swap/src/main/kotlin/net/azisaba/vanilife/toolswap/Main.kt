package net.azisaba.vanilife.toolswap

import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin() {
    override fun onEnable() {
        setupEventListeners()
    }
}
