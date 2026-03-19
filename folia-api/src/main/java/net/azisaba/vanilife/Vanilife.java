package net.azisaba.vanilife;

import java.util.Objects;
import net.azisaba.vanilife.islands.IslandDefaults;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class Vanilife {
    public static final String NAMESPACE = "vanilife";

    public static World getIslandsWorld() {
        return Objects.requireNonNull(Bukkit.getWorld(IslandDefaults.WORLD_KEY));
    }

    public static @Nullable World getResourceWorld(final int year, final Season season) {
        // TODO: Implement resource world manager
        return null;
    }

    private Vanilife() {
    }
}
