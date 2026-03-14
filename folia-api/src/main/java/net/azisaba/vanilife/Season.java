package net.azisaba.vanilife;

import java.time.LocalDate;
import java.time.Month;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.translation.Translatable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public enum Season implements Translatable {
    SPRING(TextColor.color(242, 163, 179), Month.MARCH, Month.APRIL, Month.MAY),
    SUMMER(TextColor.color(126, 215, 193), Month.JUNE, Month.JULY, Month.AUGUST),
    FALL(TextColor.color(230, 126, 34), Month.SEPTEMBER, Month.OCTOBER, Month.NOVEMBER),
    WINTER(TextColor.color(143, 163, 191), Month.DECEMBER, Month.JANUARY, Month.FEBRUARY);

    public static Season now() {
        final Month month = LocalDate.now().getMonth();
        return Arrays.stream(Season.values())
                .filter(season -> season.months().contains(month))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No season for month: " + month));
    }

    private final TextColor color;
    private final List<Month> months;

    Season(final TextColor color, final Month... months) {
        this.color = color;
        this.months = Arrays.asList(months);
    }

    public TextColor color() {
        return this.color;
    }

    public List<Month> months() {
        return this.months;
    }

    public Season next() {
        return values()[(this.ordinal() + 1) % values().length];
    }

    public Season previous() {
        return values()[(this.ordinal() - 1 + values().length) % values().length];
    }

    public Sub withStage(final Stage stage) {
        return new Sub(this, stage);
    }

    public Sub[] subSeasons() {
        return new Sub[]{withStage(Stage.EARLY), withStage(Stage.MID), withStage(Stage.LATE)};
    }

    @Override
    public String translationKey() {
        return "season." + this.name().toLowerCase(Locale.ROOT);
    }

    @NullMarked
    public record Sub(Season season, Stage stage) implements Comparable<Sub>, Translatable {
        public static Sub now() {
            return new Sub(Season.now(), Stage.now());
        }

        public Sub next() {
            final Stage nextStage = this.stage.next();
            if (nextStage != null) {
                return this.season.withStage(nextStage);
            } else {
                return this.season.next().withStage(Stage.EARLY);
            }
        }

        public Sub previous() {
            final Stage previousStage = this.stage.previous();
            if (previousStage != null) {
                return this.season.withStage(previousStage);
            }
            return this.season.previous().withStage(Stage.LATE);
        }

        public int toIndex() {
            return this.season.ordinal() * Stage.values().length + stage.ordinal();
        }

        @Override
        public String translationKey() {
            return this.season.translationKey() + "." + this.stage.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public int compareTo(final Season.Sub other) {
            int seasonCompare = Integer.compare(this.season.ordinal(), other.season().ordinal());
            if (seasonCompare != 0) {
                return seasonCompare;
            } else {
                return Integer.compare(this.stage.ordinal(), other.stage().ordinal());
            }
        }
    }

    @NullMarked
    public enum Stage {
        EARLY, MID, LATE;

        public static Stage now() {
            final Season season = Season.now();
            final Month month = LocalDate.now().getMonth();
            if (season.months().getFirst() == month) {
                return EARLY;
            } else if (season.months().get(2) == month) {
                return MID;
            } else {
                return LATE;
            }
        }

        public @Nullable Stage next() {
            final int nextOrdinal = this.ordinal() + 1;
            return nextOrdinal < values().length ? values()[nextOrdinal] : null;
        }

        public @Nullable Stage previous() {
            final int previousOrdinal = this.ordinal() - 1;
            return previousOrdinal >= 0 ? values()[previousOrdinal] : null;
        }
    }
}
