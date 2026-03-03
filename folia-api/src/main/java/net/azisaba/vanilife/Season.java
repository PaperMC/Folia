package net.azisaba.vanilife;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.translation.Translatable;
import org.jspecify.annotations.NullMarked;

import java.time.LocalDate;
import java.time.Month;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@NullMarked
public enum Season implements Translatable {
    SPRING(TextColor.color(242, 156, 167), Month.MARCH, Month.APRIL, Month.MAY),
    SUMMER(TextColor.color(0, 154, 68), Month.JUNE, Month.JULY, Month.AUGUST),
    FALL(TextColor.color(224, 126, 16), Month.SEPTEMBER, Month.OCTOBER, Month.NOVEMBER),
    WINTER(TextColor.color(114, 134, 161), Month.DECEMBER, Month.JANUARY, Month.FEBRUARY);

    public static Season current() {
        final Month month = LocalDate.now().getMonth();
        return Arrays.stream(Season.values())
                .filter(season -> season.months().contains(month))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No season for month: " + month));
    }

    private final TextColor color;
    private final Set<Month> months;

    Season(final TextColor color, final Month... months) {
        this.color = color;
        this.months = new HashSet<>(Arrays.asList(months));
    }

    public TextColor color() {
        return this.color;
    }

    public Set<Month> months() {
        return this.months;
    }

    @Override
    public String translationKey() {
        return "season." + this.name().toLowerCase(Locale.ROOT);
    }

    public Sub withStage(final Stage stage) {
        return new Sub(this, stage);
    }

    @NullMarked
    public record Sub(Season season, Stage stage) implements Comparable<Sub>, Translatable {
        public static Sub current() {
            return new Sub(Season.current(), Stage.current());
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

        public static Stage current() {
            final int dayOfMonth = LocalDate.now().getDayOfMonth();
            if (dayOfMonth <= 10) {
                return EARLY;
            } else if (dayOfMonth <= 20) {
                return MID;
            } else {
                return LATE;
            }
        }
    }
}
