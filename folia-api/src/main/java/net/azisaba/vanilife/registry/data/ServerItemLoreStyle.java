package net.azisaba.vanilife.registry.data;

import com.google.common.collect.ImmutableList;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import net.azisaba.vanilife.Season;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record ServerItemLoreStyle(List<ConditionedPart> conditionedParts) {
    private static final ServerItemLoreStyle DEFAULT = loreStyle()
            .then(ServerItemRegistryEntry::described, Part.description())
            .then(Part.itemCategory())
            .then(ServerItemRegistryEntry::hasPeakSeason, Part.peakSeason())
            .build();

    private static final Style RESET_LORE_STYLE = Style.style()
            .color(NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false)
            .build();

    @Contract(value = "-> new", pure = true)
    public static Builder loreStyle() {
        return new Builder();
    }

    @Contract(pure = true)
    public static ServerItemLoreStyle defaultStyle() {
        return DEFAULT;
    }

    public List<Part> parts(final ServerItemRegistryEntry item) {
        return this.conditionedParts.stream()
                .filter((conditioned) -> conditioned.predicate().test(item))
                .map(ConditionedPart::part)
                .toList();
    }

    public ItemLore itemLore(final ServerItemRegistryEntry item) {
        final List<Component> lines = this.buildLines(item).stream().map((line) -> line.applyFallbackStyle(RESET_LORE_STYLE)).toList();
        return ItemLore.lore(lines);
    }

    private List<Component> buildLines(final ServerItemRegistryEntry item) {
        final List<Part> parts = this.parts(item);
        final ImmutableList.Builder<Component> builder = ImmutableList.builder();
        for (int i = 0; i < parts.size(); i++) {
            if (0 < i) {
                builder.add(Component.empty());
            }
            final Part part = parts.get(i);
            part.append(item, builder);
        }
        return builder.build();
    }

    @FunctionalInterface
    @NullMarked
    public interface Part {
        static Part description() {
            return (item, builder) -> builder.add(Component.translatable(item.translationKey() + ".description"));
        }

        static Part itemCategory() {
            return (item, builder) -> builder.add(Component.translatable("item.vanilife.category"))
                    .add(Component.translatable(item.category(), item.category().color()));
        }

        static Part peakSeason() {
            return new Part() {
                @Override
                public void append(ServerItemRegistryEntry item, ImmutableList.Builder<Component> builder) {
                    final List<Season.Sub> peakSeason = item.peakSeason().stream().sorted().toList();
                    final List<Range> ranges = this.buildRanges(peakSeason);

                    builder.add(Component.translatable("item.vanilife.peak_season"));

                    for (Range range : ranges) {
                        builder.add(range.toComponent());
                    }

                    builder.add(Component.translatable(item.translationKey() + ".season"));
                }

                private List<Range> buildRanges(final List<Season.Sub> sorted) {
                    final List<Range> result = new ArrayList<>();

                    Season.Sub start = sorted.getFirst();
                    Season.Sub prev = start;

                    for (int i = 1; i < sorted.size(); i++) {
                        final Season.Sub current = sorted.get(i);

                        if (!prev.next().equals(current)) {
                            result.add(new Range(start, prev));
                            start = current;
                        }

                        prev = current;
                    }

                    result.add(new Range(start, prev));
                    return Collections.unmodifiableList(result);
                }

                private record Range(Season.Sub start, Season.Sub end) {
                    public Component toComponent() {
                        if (this.start.equals(this.end)) {
                            return Component.translatable("item.vanilife.peak_season.range.single", Component.translatable(this.start, this.start.season().color()));
                        } else {
                            return Component.translatable("item.vanilife.peak_season.range.multiple", Component.translatable(this.start, this.start.season().color()), Component.translatable(this.end, this.end.season().color()));
                        }
                    }
                }
            };
        }

        void append(final ServerItemRegistryEntry item, final ImmutableList.Builder<Component> builder);
    }

    @NullMarked
    public record ConditionedPart(Predicate<ServerItemRegistryEntry> predicate, Part part) {
    }

    @NullMarked
    public static final class Builder {
        private final List<ConditionedPart> conditionedParts = new ArrayList<>();

        private Builder() {
        }

        @Contract(value = "_ -> this", mutates = "this")
        public Builder then(final Part part) {
            return this.then((item) -> true, part);
        }

        @Contract(value = "_, _ -> this", mutates = "this")
        public Builder then(final Predicate<ServerItemRegistryEntry> predicate, final Part part) {
            conditionedParts.add(new ConditionedPart(predicate, part));
            return this;
        }

        @Contract(value = "-> new", pure = true)
        public ServerItemLoreStyle build() {
            return new ServerItemLoreStyle(this.conditionedParts);
        }
    }
}
