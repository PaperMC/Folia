package net.azisaba.vanilife.server.item;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.registry.HolderableBase;
import java.util.Set;
import net.azisaba.vanilife.Season;
import net.azisaba.vanilife.item.ServerItem;
import net.azisaba.vanilife.registry.data.ServerItemCategory;
import net.azisaba.vanilife.registry.data.ServerItemLoreStyle;
import net.azisaba.vanilife.registry.data.ServerItemRegistryEntry;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class ServerItemImpl extends HolderableBase<ServerItemRegistryEntry> implements ServerItem {
    public static ServerItemImpl minecraftToBukkit(final Holder<ServerItemRegistryEntry> minecraft) {
        return CraftRegistry.minecraftHolderToBukkit(minecraft, Registries.SERVER_ITEM);
    }

    public static Holder<ServerItemRegistryEntry> bukkitToMinecraft(final ServerItem bukkit) {
        return CraftRegistry.bukkitToMinecraftHolder(bukkit);
    }

    public ServerItemImpl(final Holder<ServerItemRegistryEntry> holder) {
        super(holder);
    }

    @Override
    public String translationKey() {
        return this.getHandle().translationKey();
    }

    @Override
    public boolean described() {
        return this.getHandle().described();
    }

    @Override
    public ServerItemCategory category() {
        return this.getHandle().category();
    }

    @Override
    public Set<Season.Sub> peakSeason() {
        return this.getHandle().peakSeason();
    }

    @Override
    public ServerItemLoreStyle loreStyle() {
        return this.getHandle().loreStyle();
    }

    @Override
    public @Nullable <T> T getData(final DataComponentType.Valued<T> type) {
        return this.getHandle().getData(type);
    }

    @Override
    public @Nullable <T> T getDataOrDefault(final DataComponentType.Valued<? extends T> type, final @Nullable T fallback) {
        return this.getHandle().getDataOrDefault(type, fallback);
    }

    @Override
    public boolean hasData(final DataComponentType type) {
        return this.getHandle().hasData(type);
    }

    @Override
    public void applyData(final ItemStack itemStack) {
        this.getHandle().applyData(itemStack);
    }

    @Override
    public void applyItemName(final ItemStack itemStack) {
        this.getHandle().applyItemName(itemStack);
    }

    @Override
    public void applyItemLore(final ItemStack itemStack) {
        this.getHandle().applyItemLore(itemStack);
    }
}
