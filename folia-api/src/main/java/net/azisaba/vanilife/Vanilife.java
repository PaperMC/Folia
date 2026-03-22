package net.azisaba.vanilife;

import java.util.Objects;
import net.azisaba.vanilife.world.IslandDefaults;
import net.azisaba.vanilife.world.IslandsWorld;
import net.azisaba.vanilife.world.ResourceWorld;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class Vanilife {
    public static final String NAMESPACE = "vanilife";

    public static IslandsWorld getIslandsWorld() {
        return (IslandsWorld) Objects.requireNonNull(Bukkit.getWorld(IslandDefaults.WORLD_KEY));
    }

    // TODO: Implement auto wipe
    public static ResourceWorld getResourceWorld() {
        return (ResourceWorld) Objects.requireNonNull(Bukkit.getWorld(Key.key(NAMESPACE, "2026/spring")));
    }

    private Vanilife() {
    }
}
