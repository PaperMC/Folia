package net.azisaba.vanilife.item;

import io.papermc.paper.datacomponent.DataComponentView;
import java.util.Set;
import net.azisaba.vanilife.Season;
import net.kyori.adventure.text.Component;
import org.bukkit.Keyed;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public interface ServerItemType extends DataComponentView, Keyed {
    Component displayName();

    @Nullable Component flavorText();

    Set<Season.Sub> peakSeason();

    @ApiStatus.Internal
    ItemStack createItemStack(final int amount);
}
