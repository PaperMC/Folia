package net.azisaba.vanilife.registry.data;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.registry.RegistryBuilder;
import net.azisaba.vanilife.Season;
import net.azisaba.vanilife.item.ServerItem;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface ServerItemRegistryEntry {
    @Contract(pure = true)
    Component displayName();

    @Contract(pure = true)
    Set<Season.Sub> peakSeason();

    @Contract(pure = true)
    <T> @Nullable T component(final DataComponentType.Valued<T> type);

    @Contract(pure = true)
    boolean hasComponent(final DataComponentType type);

    @ApiStatus.Experimental
    @ApiStatus.NonExtendable
    interface Builder extends RegistryBuilder<ServerItem> {
        @Contract(value = "_ -> this", mutates = "this")
        Builder displayName(Component displayName);

        @Contract(value = "_ -> this", mutates = "this")
        Builder peakSeason(Season.Sub... subSeasons);

        @Contract(value = "_, _ -> this", mutates = "this")
        <T> Builder withComponent(final DataComponentType.Valued<T> type, T value);

        @Contract(value = "_ -> this", mutates = "this")
        Builder withComponent(final DataComponentType.NonValued type);
    }
}
