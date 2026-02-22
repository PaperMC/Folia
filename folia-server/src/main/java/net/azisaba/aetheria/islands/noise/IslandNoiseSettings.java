package net.azisaba.aetheria.islands.noise;

import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record IslandNoiseSettings(
        double baseCornerRadius,
        double cornerRadiusAmplitude,
        double coastBand,
        double coastAmplitude,
        IntProvider terraceCount,
        IntProvider terraceStepHeight,
        int shoreDepthStepBlocks,
        double shapeNoiseBaseAmplitude,
        double shapeNoiseProgressAmplitude,
        double detailNoiseBaseAmplitude
) {
    public static IslandNoiseSettings createDefault() {
        return new IslandNoiseSettings(
                28.0,
                9.0,
                26.0,
                10.0,
                UniformInt.of(3, 5),
                UniformInt.of(4, 6),
                8,
                0.08,
                0.12,
                0.3
        );
    }
}
