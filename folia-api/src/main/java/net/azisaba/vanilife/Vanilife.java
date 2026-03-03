package net.azisaba.vanilife;

import net.azisaba.vanilife.islands.IslandDefaults;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jspecify.annotations.NullMarked;

import java.util.Objects;

@NullMarked
public final class Vanilife {
    public static final String NAMESPACE = "vanilife";

    public static World getIslandsWorld() {
        return Objects.requireNonNull(Bukkit.getWorld(IslandDefaults.WORLD_KEY));
    }

    private Vanilife() {
    }
}
