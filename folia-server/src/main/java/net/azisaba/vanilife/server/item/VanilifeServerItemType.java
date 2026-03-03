package net.azisaba.vanilife.server.item;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.registry.HolderableBase;
import java.util.Set;
import net.azisaba.vanilife.Season;
import net.azisaba.vanilife.Vanilife;
import net.azisaba.vanilife.item.ServerItemType;
import net.azisaba.vanilife.registry.data.ServerItemTypeRegistryEntry;
import net.kyori.adventure.text.Component;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class VanilifeServerItemType extends HolderableBase<ServerItemTypeRegistryEntry> implements ServerItemType {
    public static final NamespacedKey TYPE_KEY = new NamespacedKey(Vanilife.NAMESPACE, "item");

    public static VanilifeServerItemType minecraftToBukkit(final Holder<ServerItemTypeRegistryEntry> minecraft) {
        return CraftRegistry.minecraftHolderToBukkit(minecraft, Registries.SERVER_ITEM);
    }

    public static Holder<ServerItemTypeRegistryEntry> bukkitToMinecraft(final ServerItemType bukkit) {
        return CraftRegistry.bukkitToMinecraftHolder(bukkit);
    }

    public VanilifeServerItemType(final Holder<ServerItemTypeRegistryEntry> holder) {
        super(holder);
    }

    @Override
    public Component displayName() {
        return this.getHandle().displayName();
    }

    @Override
    public @Nullable Component flavorText() {
        return this.getHandle().flavorText();
    }

    @Override
    public Set<Season.Sub> peakSeason() {
        return this.getHandle().peakSeason();
    }

    @Override
    public @Nullable <T> T getData(final DataComponentType.Valued<T> type) {
        return this.getHandle().component(type);
    }

    @Override
    public @Nullable <T> T getDataOrDefault(final DataComponentType.Valued<? extends T> type, final @Nullable T fallback) {
        final T object = this.getHandle().component(type);
        return object != null ? object : fallback;
    }

    @Override
    public boolean hasData(final DataComponentType type) {
        return this.getHandle().hasComponent(type);
    }

    @Override
    public ItemStack createItemStack(final int amount) {
        final ItemStack itemStack = ItemStack.of(Material.STICK, amount);
        this.getHandle().applyComponents(itemStack);
        itemStack.editPersistentDataContainer((pdc) -> {
            pdc.set(TYPE_KEY, PersistentDataType.STRING, this.key().asString());
        });
        return itemStack;
    }
}
