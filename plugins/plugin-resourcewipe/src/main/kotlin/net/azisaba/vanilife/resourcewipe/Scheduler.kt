package net.azisaba.vanilife.resourcewipe

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

class Scheduler(private val plugin: JavaPlugin) {
    fun scheduleRepeatingMinutes(intervalMinutes: Long, task: () -> Unit) {
        if (intervalMinutes <= 0L) return
        val ticks = intervalMinutes * 60L * 20L
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { task() }, ticks, ticks)
    }
}
