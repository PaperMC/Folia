package net.azisaba.vanilife.server.world.wiper;

import net.azisaba.vanilife.Season;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

public class WorldWiper {
    static Season currentSeason = Season.now();

    //    private static final ZoneId ASIA_TOKYO = ZoneId.of("Asia/Tokyo");
    public static void registerTask(Plugin plugin) {
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            if (checkOrChange(plugin, Season.now())) {
                plugin.getSLF4JLogger().info("Season changed!");
            }
        }, 1, 1, TimeUnit.HOURS);
    }

    /**
     * Check or change season
     *
     * @param nowSeason now season
     * @return is changed
     */
    public static boolean checkOrChange(Plugin plugin, Season nowSeason) {
        if (currentSeason == nowSeason) return false; // ignore if season wasn't changed

        // change internal current season state
        var oldSeason = currentSeason;
        currentSeason = nowSeason;

        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            var players = Bukkit.getOnlinePlayers();
            Bukkit.broadcast(Component.text("季節変わるよー!"));
//            players.forEach(p -> ); Todo: teleport to each island
            Bukkit.unloadWorld("", true);
        });

        // broadcast player to change season
        // Todo: broadcast to player that season was changed
        return true;
    }
}
