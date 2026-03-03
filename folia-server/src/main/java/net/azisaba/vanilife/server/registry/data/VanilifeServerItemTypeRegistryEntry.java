package net.azisaba.vanilife.server.registry.data;

import com.google.common.collect.Sets;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.PaperDataComponentType;
import io.papermc.paper.registry.PaperRegistryBuilder;
import io.papermc.paper.registry.data.util.Conversions;
import java.util.Collections;
import java.util.Set;
import net.azisaba.vanilife.Season;
import net.azisaba.vanilife.item.ServerItemType;
import net.azisaba.vanilife.registry.data.ServerItemTypeRegistryEntry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import static io.papermc.paper.registry.data.util.Checks.asConfigured;

@NullMarked
public class VanilifeServerItemTypeRegistryEntry implements ServerItemTypeRegistryEntry {
    protected final Component displayName;
    protected final Set<Season.Sub> peakSeason;
    protected final DataComponentMap components;

    public VanilifeServerItemTypeRegistryEntry(final Component displayName, final Set<Season.Sub> peakSeason, final DataComponentMap components) {
        this.displayName = displayName;
        this.peakSeason = peakSeason;
        this.components = DataComponentMap.composite(components, this.createBaseComponents());
    }

    @Override
    public net.kyori.adventure.text.Component displayName() {
        return PaperAdventure.asAdventure(this.displayName);
    }

    @Override
    public Set<Season.Sub> peakSeason() {
        return Collections.unmodifiableSet(this.peakSeason);
    }

    @Override
    public @Nullable <T> T component(final DataComponentType.Valued<T> type) {
        return PaperDataComponentType.convertDataComponentValue(this.components, (PaperDataComponentType.ValuedImpl<T, ?>) type);
    }

    @Override
    public boolean hasComponent(final DataComponentType type) {
        return this.components.has(PaperDataComponentType.bukkitToMinecraft(type));
    }

    @Override
    public void applyComponents(final ItemStack itemStack) {
        ((CraftItemStack) itemStack).handle.applyComponents(this.components);
    }

    private DataComponentMap createBaseComponents() {
        final DataComponentMap.Builder componentsBuilder = DataComponentMap.builder();
        componentsBuilder.set(DataComponents.ITEM_NAME, displayName);
        return componentsBuilder.build();
    }

    @NullMarked
    public static class VanilifeBuilder implements ServerItemTypeRegistryEntry.Builder, PaperRegistryBuilder<ServerItemTypeRegistryEntry, ServerItemType> {
        private final Conversions conversions;

        private @Nullable Component displayName;
        private Set<Season.Sub> peakSeason = Collections.emptySet();
        private final DataComponentMap.Builder componentsBuilder = DataComponentMap.builder();

        public VanilifeBuilder(final Conversions conversions, final @Nullable ServerItemTypeRegistryEntry initial) {
            this.conversions = conversions;

            if (initial instanceof VanilifeServerItemTypeRegistryEntry entry) {
                this.displayName = entry.displayName;
                this.peakSeason = entry.peakSeason;
                this.componentsBuilder.addAll(entry.components);
            }
        }

        @Override
        public Builder displayName(final net.kyori.adventure.text.Component displayName) {
            this.displayName = conversions.asVanilla(displayName);
            return this;
        }

        @Override
        public Builder peakSeason(final Season.Sub... subSeasons) {
            this.peakSeason = Sets.newHashSet(subSeasons);
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
        public ServerItemTypeRegistryEntry build() {
            return new VanilifeServerItemTypeRegistryEntry(
                    asConfigured(this.displayName, "displayName"),
                    this.peakSeason,
                    this.componentsBuilder.build()
            );
        }

        private <A, V> void withComponentInternal(final PaperDataComponentType<A, V> type, final @Nullable A value) {
            this.componentsBuilder.set(type.getHandle(), type.getAdapter().toVanilla(value, type.getHolder()));
        }
    }
}
