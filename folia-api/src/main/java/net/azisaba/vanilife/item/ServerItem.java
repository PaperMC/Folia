package net.azisaba.vanilife.item;

import net.azisaba.vanilife.Vanilife;
import net.azisaba.vanilife.registry.data.ServerItemRegistryEntry;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

@ApiStatus.NonExtendable
@NullMarked
public interface ServerItem extends Keyed, ServerItemRegistryEntry {
    NamespacedKey TYPE_KEY = new NamespacedKey(Vanilife.NAMESPACE, "item");

    Material NATIVE_ITEM = Material.STICK;
}
