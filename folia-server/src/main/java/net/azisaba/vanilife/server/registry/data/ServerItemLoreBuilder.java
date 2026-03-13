package net.azisaba.vanilife.server.registry.data;

import com.google.common.collect.ImmutableList;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.azisaba.vanilife.Season;
import net.azisaba.vanilife.registry.data.ServerItemRegistryEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.translation.Translatable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ServerItemLoreBuilder {
    public static final ServerItemLoreBuilder DEFAULT = new ServerItemLoreBuilder(List.of(
            ConditionalSection.of(ServerItemRegistryEntry::described, new Description()),
            ConditionalSection.of(new Category()),
            ConditionalSection.of(ServerItemRegistryEntry::hasPeakSeason, new PeakSeason())
    ));

    private static final Style RESET_LORE_STYLE = Style.style()
            .color(NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false)
            .build();

    private final List<ConditionalSection> sections;

    public ServerItemLoreBuilder(final List<ConditionalSection> sections) {
        this.sections = sections;
    }

    public ItemLore build(final ServerItemRegistryEntry item) {
        final List<Component> lines = this.buildLines(item).stream().map((line) -> line.style(RESET_LORE_STYLE)).toList();
        return ItemLore.lore(lines);
    }

    private List<Component> buildLines(final ServerItemRegistryEntry item) {
        final ImmutableList.Builder<Component> builder = ImmutableList.builder();
        boolean appendedAnySection = false;
        for (final ConditionalSection entry : this.sections) {
            if (!entry.predicate.test(item)) {
                continue;
            }

            if (appendedAnySection) {
                builder.add(Component.empty());
            }
            entry.section.append(item, builder);
            appendedAnySection = true;
        }
        return builder.build();
    }

    @FunctionalInterface
    public interface Section {
        void append(final ServerItemRegistryEntry item, final ImmutableList.Builder<Component> builder);
    }

    @NullMarked
    public record ConditionalSection(Predicate<ServerItemRegistryEntry> predicate, Section section) {
        public static ConditionalSection of(final Section section) {
            return new ConditionalSection((item) -> true, section);
        }

        public static ConditionalSection of(final Predicate<ServerItemRegistryEntry> predicate, final Section section) {
            return new ConditionalSection(predicate, section);
        }
    }

    @NullMarked
    static class Description implements Section {
        @Override
        public void append(final ServerItemRegistryEntry item, final ImmutableList.Builder<Component> builder) {
            if (item.described()) {
                builder.add(Component.translatable(item.translationKey() + ".description"));
            }
        }
    }

    @NullMarked
    static class Category implements Section {
        @Override
        public void append(final ServerItemRegistryEntry item, final ImmutableList.Builder<Component> builder) {
            builder.add(Component.translatable("item.vanilife.category"))
                    .add(Component.translatable(item.category()));
        }
    }

    @NullMarked
    static class PeakSeason implements Section {
        @Override
        public void append(final ServerItemRegistryEntry item, final ImmutableList.Builder<Component> builder) {
            if (item.hasPeakSeason()) {
                final List<Season.Sub> peakSeason = item.peakSeason().stream().sorted().toList();
                final List<Range> ranges = this.buildRanges(peakSeason);

                builder.add(Component.translatable("item.vanilife.peak_season"));

                for (int i = 0; i < ranges.size(); i++) {
                    if (i > 0) {
                        builder.add(Component.empty());
                    }
                    builder.add(ranges.get(i).render());
                }
            }
        }

        private List<Range> buildRanges(final List<Season.Sub> sorted) {
            final List<Range> ranges = new ArrayList<>();

            Season.Sub start = sorted.getFirst();
            Season.Sub prev = start;

            for (int i = 1; i < sorted.size(); i++) {
                final Season.Sub current = sorted.get(i);

                if (!prev.next().equals(current)) {
                    ranges.add(new Range(start, prev));
                    start = current;
                }

                prev = current;
            }

            ranges.add(new Range(start, prev));
            return ranges;
        }

        @NullMarked
        private record Range(Season.Sub start, Season.Sub end) implements Translatable {
            @Override
            public String translationKey() {
                if (this.isSingle()) {
                    return "item.vanilife.peak_season.range.single";
                } else {
                    return "item.vanilife.peak_season.range.multiple";
                }
            }

            public Component render() {
                if (this.isSingle()) {
                    return Component.translatable(this, Component.translatable(this.start, this.start.season().color()));
                } else {
                    return Component.translatable(this, Component.translatable(this.start, this.start.season().color()), Component.translatable(this.end, this.end.season().color()));
                }
            }

            public boolean isSingle() {
                return this.start.equals(this.end);
            }
        }
    }
}
