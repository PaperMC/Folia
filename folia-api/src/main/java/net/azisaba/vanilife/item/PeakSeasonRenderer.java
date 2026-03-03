package net.azisaba.vanilife.item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.azisaba.vanilife.Season;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;import org.jspecify.annotations.NullMarked;

@NullMarked
public final class PeakSeasonRenderer {
    public static Component render(final Collection<Season.Sub> peakSeason) {
        if (peakSeason.isEmpty()) {
            return Component.empty();
        }

        final List<Season.Sub> sorted = peakSeason.stream().distinct().sorted().toList();

        final List<Range> ranges = buildRanges(sorted);
        mergeCycleIfNeeded(ranges);

        final TextComponent.Builder builder = Component.text();

        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) {
                builder.append(Component.text(", ", NamedTextColor.GRAY));
            }
            appendRange(builder, ranges.get(i));
        }

        return builder.build();
    }

    private static List<Range> buildRanges(final List<Season.Sub> sorted) {
        final List<Range> ranges = new ArrayList<>();

        Season.Sub rangeStart = sorted.getFirst();
        Season.Sub previous = rangeStart;

        for (int i = 1; i < sorted.size(); i++) {
            final Season.Sub current = sorted.get(i);

            if (!previous.next().equals(current)) {
                ranges.add(new Range(previous, current));
                rangeStart = current;
            }

            previous = current;
        }

        ranges.add(new Range(rangeStart, previous));
        return ranges;
    }

    private static void mergeCycleIfNeeded(final List<Range> ranges) {
        if (ranges.size() <= 1) return;

        final Range first = ranges.getFirst();
        final Range last = ranges.getLast();

        if (last.end().next().equals(first.start())) {
            final Range merged = new Range(last.start(), first.end());
            ranges.set(0, merged);
            ranges.removeLast();
        }
    }

    private static void appendRange(final TextComponent.Builder builder, final Range range) {
        if (range.start().equals(range.end())) {
            builder.append(Component.translatable(range.start(), range.start().season().color()));
        } else {
            builder.append(Component.translatable(range.start(), range.start().season().color()))
                    .append(Component.text("~", NamedTextColor.GRAY))
                    .append(Component.translatable(range.end(), range.end().season().color()));
        }
    }

    private PeakSeasonRenderer() {
    }

    private record Range(Season.Sub start, Season.Sub end) {
    }
}
