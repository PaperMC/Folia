package net.azisaba.vanilife.portal

import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin() {
    override fun onEnable() {
        setupEventListeners()
    }
}