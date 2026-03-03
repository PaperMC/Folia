package net.azisaba.vanilife.server.item;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.registry.HolderableBase;
import java.util.Set;
import net.azisaba.vanilife.Season;
import net.azisaba.vanilife.item.ServerItem;
import net.azisaba.vanilife.registry.data.ServerItemRegistryEntry;
import net.kyori.adventure.text.Component;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import org.bukkit.craftbukkit.CraftRegistry;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class VanilifeServerItem extends HolderableBase<ServerItemRegistryEntry> implements ServerItem {
    public static VanilifeServerItem minecraftToBukkit(final Holder<ServerItemRegistryEntry> minecraft) {
        return CraftRegistry.minecraftHolderToBukkit(minecraft, Registries.SERVER_ITEM);
    }

    public static Holder<ServerItemRegistryEntry> bukkitToMinecraft(final ServerItem bukkit) {
        return CraftRegistry.bukkitToMinecraftHolder(bukkit);
    }

    public VanilifeServerItem(final Holder<ServerItemRegistryEntry> holder) {
        super(holder);
    }

    @Override
    public Component displayName() {
        return this.getHandle().displayName();
    }

    @Override
    public Set<Season.Sub> peakSeasons() {
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
}
