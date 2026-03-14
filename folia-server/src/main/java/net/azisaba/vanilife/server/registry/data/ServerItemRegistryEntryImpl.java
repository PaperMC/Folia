package net.azisaba.vanilife.server.registry.data;

import com.google.common.collect.Sets;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.PaperDataComponentType;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.registry.PaperRegistryBuilder;
import io.papermc.paper.registry.data.util.Conversions;
import java.util.Collections;
import java.util.Set;
import net.azisaba.vanilife.Season;
import net.azisaba.vanilife.item.ServerItem;
import net.azisaba.vanilife.registry.data.ServerItemCategory;
import net.azisaba.vanilife.registry.data.ServerItemLoreStyle;
import net.azisaba.vanilife.registry.data.ServerItemRegistryEntry;
import net.kyori.adventure.text.Component;
import net.minecraft.core.component.DataComponentMap;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import static io.papermc.paper.registry.data.util.Checks.asConfigured;

@NullMarked
public record ServerItemRegistryEntryImpl(
        String translationKey,
        boolean described,
        ServerItemCategory category,
        Set<Season.Sub> peakSeason,
        ServerItemLoreStyle loreStyle,
        DataComponentMap components
) implements ServerItemRegistryEntry {
    @Override
    public @Nullable <T> T getData(final DataComponentType.Valued<T> type) {
        return PaperDataComponentType.convertDataComponentValue(this.components, (PaperDataComponentType.ValuedImpl<T, ?>) type);
    }

    @Override
    public @Nullable <T> T getDataOrDefault(final DataComponentType.Valued<? extends T> type, final @Nullable T fallback) {
        final T object = this.getData(type);
        return object != null ? object : fallback;
    }

    @Override
    public boolean hasData(final DataComponentType type) {
        return this.components.has(PaperDataComponentType.bukkitToMinecraft(type));
    }

    @Override
    public void applyData(final ItemStack itemStack) {
        ((CraftItemStack) itemStack).handle.applyComponents(this.components);
    }

    @Override
    public void applyItemName(final ItemStack itemStack) {
        itemStack.setData(DataComponentTypes.ITEM_NAME, Component.translatable(this));
    }

    @Override
    public void applyItemLore(final ItemStack itemStack) {
        final ItemLore itemLore = this.loreStyle.itemLore(this);
        itemStack.setData(DataComponentTypes.LORE, itemLore);
    }

    @NullMarked
    public static class BuilderImpl implements ServerItemRegistryEntry.Builder, PaperRegistryBuilder<ServerItemRegistryEntry, ServerItem> {
        private @Nullable String translationKey;
        private boolean described = false;
        private @Nullable ServerItemCategory category;
        private Set<Season.Sub> peakSeason = Collections.emptySet();
        private ServerItemLoreStyle loreStyle = ServerItemLoreStyle.defaultStyle();
        private final DataComponentMap.Builder componentsBuilder = DataComponentMap.builder();

        public BuilderImpl(final Conversions conversions, final @Nullable ServerItemRegistryEntry initial) {
            if (initial instanceof ServerItemRegistryEntryImpl(
                    final String initialTranslationKey,
                    final boolean initialDescribed,
                    final ServerItemCategory initialCategory,
                    final Set<Season.Sub> initialPeakSeason,
                    final ServerItemLoreStyle initialLoreStyle,
                    final DataComponentMap components
            )) {
                this.translationKey = initialTranslationKey;
                this.described = initialDescribed;
                this.category = initialCategory;
                this.peakSeason = initialPeakSeason;
                this.loreStyle = initialLoreStyle;
                this.componentsBuilder.addAll(components);
            }
        }

        @Override
        public Builder translationKey(final String translationKey) {
            this.translationKey = translationKey;
            return this;
        }

        @Override
        public Builder describe() {
            this.described = true;
            return this;
        }

        @Override
        public Builder category(final ServerItemCategory category) {
            this.category = category;
            return this;
        }

        @Override
        public Builder peakSeason(final Season.Sub... peakSeason) {
            this.peakSeason = Sets.newHashSet(peakSeason);
            return this;
        }

        @Override
        public Builder loreStyle(final ServerItemLoreStyle loreStyle) {
            this.loreStyle = loreStyle;
            return this;
        }

        @Override
        public <T> Builder withComponent(final DataComponentType.Valued<T> type, T value) {
            this.withComponentInternal((PaperDataComponentType.ValuedImpl<T, ?>) type, value);
            return this;
        }

        @Override
        public Builder withComponent(final DataComponentType.NonValued type) {
            this.withComponentInternal((PaperDataComponentType.NonValuedImpl<?, ?>) type, null);
            return this;
        }

        @Override
        public ServerItemRegistryEntry build() {
            return new ServerItemRegistryEntryImpl(
                    asConfigured(this.translationKey, "translationKey"),
                    described,
                    asConfigured(this.category, "category"),
                    this.peakSeason,
                    this.loreStyle,
                    this.componentsBuilder.build()
            );
        }

        private <A, V> void withComponentInternal(final PaperDataComponentType<A, V> type, final @Nullable A value) {
            this.componentsBuilder.set(type.getHandle(), type.getAdapter().toVanilla(value, type.getHolder()));
        }
    }
}
