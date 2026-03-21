package net.azisaba.vanilife;

import java.util.Objects;
import net.azisaba.vanilife.islands.IslandDefaults;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class Vanilife {
    public static final String NAMESPACE = "vanilife";

    public static World getIslandsWorld() {
        return Objects.requireNonNull(Bukkit.getWorld(IslandDefaults.WORLD_KEY));
    }

    // TODO: Implement auto wipe
    public static World getResourceWorld() {
        return Objects.requireNonNull(Bukkit.getWorld(Key.key(NAMESPACE, "2026/spring")));
    }

    private Vanilife() {
    }
}
