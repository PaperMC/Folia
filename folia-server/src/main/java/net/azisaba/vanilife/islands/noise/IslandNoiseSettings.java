package net.azisaba.vanilife.islands.noise;

import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record IslandNoiseSettings(
        double cornerRadius,
        double cornerRadiusNoiseAmplitude,
        double coastlineNoiseBand,
        double coastlineNoiseAmplitude,
        double inlandShapeNoiseBaseAmplitude,
        double inlandShapeNoiseProgressAmplitude,
        double surfaceDetailNoiseAmplitude,
        int offshoreDepthStepDistanceBlocks,
        IntProvider terraceCountProvider,
        IntProvider terraceStepHeightProvider
) {
    public static IslandNoiseSettings createDefault() {
        return new IslandNoiseSettings(
                28.0,
                9.0,
                26.0,
                10.0,
                0.08,
                0.12,
                0.3,
                8,
                UniformInt.of(3, 5),
                UniformInt.of(4, 6)
        );
    }
}
