package net.azisaba.vanilife.server.world.islands.river;

import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record RiverSettings(
        IntProvider riverMouthCount,
        IntProvider riverCount,
        double riverScale,
        double riverWidth,
        int riverDepth,
        double riverMeanderAmplitude,
        double coastBand
) {
    public static RiverSettings createDefault() {
        return new RiverSettings(
                UniformInt.of(2, 3),
                UniformInt.of(2, 5),
                1.0 / 180.0,
                11.8,
                10,
                7.4,
                26.0
        );
    }
}
