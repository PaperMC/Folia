package net.azisaba.vanilife.item;

import io.papermc.paper.datacomponent.DataComponentView;
import java.util.Set;
import net.azisaba.vanilife.Season;
import net.kyori.adventure.text.Component;
import org.bukkit.Keyed;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface ServerItem extends DataComponentView, Keyed {
    Component displayName();

    Set<Season.Sub> peakSeason();
}
