package net.azisaba.aetheria.islands.noise;

import de.articdive.jnoise.core.api.functions.Interpolation;
import de.articdive.jnoise.generators.noise_parameters.fade_functions.FadeFunction;
import de.articdive.jnoise.modules.octavation.fractal_functions.FractalFunction;
import de.articdive.jnoise.pipeline.JNoise;
import net.azisaba.aetheria.islands.Islands;
import net.azisaba.aetheria.islands.IslandsGeneratorSettings;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.NullMarked;

import java.util.Arrays;

@NullMarked
public final class IslandNoise {
    public static IslandNoise createDefault(final long seed) {
        return new IslandNoise(
                seed,
                Islands.ISLAND_WIDTH_GRIDS * Islands.GRID_SIZE,
                Islands.ISLAND_HEIGHT_GRIDS * Islands.GRID_SIZE,
                IslandNoiseSettings.createDefault()
        );
    }

    private static double clamp01(final double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    private static double smoothstep(final double t) {
        return t * t * (3 - 2 * t);
    }

    private final double halfWidth;
    private final double halfHeight;
    private final IslandNoiseSettings settings;

    private final JNoise coastNoise;
    private final JNoise cornerNoise;
    private final JNoise landShapeNoise;
    private final JNoise landDetailNoise;

    private final int[] terraceStepHeights;
    private final int terraceTotalHeight;

    public IslandNoise(final long seed, final int width, final int height, final IslandNoiseSettings noiseSettings) {
        this.halfWidth = width / 2.0;
        this.halfHeight = height / 2.0;
        this.settings = noiseSettings;
        this.coastNoise = JNoise.newBuilder()
                .perlin(seed, Interpolation.COSINE, FadeFunction.CUBIC_POLY)
                .scale(1.0 / 48.0)
                .octavate(4, 0.5, 2.0, FractalFunction.FBM, false)
                .build();
        this.cornerNoise = JNoise.newBuilder()
                .perlin(seed ^ 0x9E3779B97F4A7C15L, Interpolation.COSINE, FadeFunction.CUBIC_POLY)
                .scale(1.0 / 26.0)
                .octavate(3, 0.55, 2.1, FractalFunction.FBM, false)
                .build();
        this.landShapeNoise = JNoise.newBuilder()
                .perlin(seed ^ 0x6EED0E9DA4D94A4FL, Interpolation.COSINE, FadeFunction.CUBIC_POLY)
                .scale(1.0 / 72.0)
                .octavate(3, 0.55, 2.0, FractalFunction.FBM, false)
                .build();
        this.landDetailNoise = JNoise.newBuilder()
                .perlin(seed ^ 0xA5A1CE1B29D83CF1L, Interpolation.COSINE, FadeFunction.CUBIC_POLY)
                .scale(1.0 / 20.0)
                .octavate(2, 0.6, 2.2, FractalFunction.FBM, false)
                .build();

        final RandomSource terraceRandomSource = RandomSource.create(seed ^ 0x4CF5AD432745937FL);
        this.terraceStepHeights = new int[Math.max(1, this.settings.terraceCount().sample(terraceRandomSource))];
        for (int i = 0; i < this.terraceStepHeights.length; i++) {
            final int stepHeight = Math.max(1, this.settings.terraceStepHeight().sample(terraceRandomSource));
            this.terraceStepHeights[i] = stepHeight;
        }
        this.terraceTotalHeight = Arrays.stream(this.terraceStepHeights).sum();
    }

    public Sample sample(final double x, final double z, final IslandsGeneratorSettings generatorSettings) {
        final double signedDistance = this.computeSignedDistance(x, z);
        return new Sample(signedDistance, this.computeSurfaceY(x, z, signedDistance, generatorSettings));
    }

    public double computeSignedDistance(final double x, final double z) {
        final double cornerInfluence = this.cornerMask(x, z);
        final double cornerNoiseSample = this.cornerNoise.evaluateNoise(x, z);
        final double cornerRadius = this.settings.baseCornerRadius() + cornerNoiseSample * this.settings.cornerRadiusAmplitude() * cornerInfluence;

        final double baseSignedDistance = this.sdfRoundedRect(x, z, cornerRadius);

        final double coastInfluence = clamp01(1.0 - Math.abs(baseSignedDistance) / this.settings.coastBand());
        final double coastNoiseSample = this.coastNoise.evaluateNoise(x, z);
        final double coastlineOffset = coastNoiseSample * this.settings.coastAmplitude() * coastInfluence;

        return baseSignedDistance + coastlineOffset;
    }

    private int computeSurfaceY(final double x, final double z, final double signedDistance, final IslandsGeneratorSettings generatorSettings) {
        if (signedDistance > 0.0) {
            final int shoreDepthStepBlocks = Math.max(1, this.settings.shoreDepthStepBlocks());
            final int gradualDepth = Math.min(generatorSettings.seaDepth(), (int) Math.floor(signedDistance / shoreDepthStepBlocks));
            return generatorSettings.seaLevel() - gradualDepth;
        }

        final double insideDistance = Math.max(0.0, -signedDistance - generatorSettings.beachWidth());
        final double progress = smoothstep(insideDistance / Math.max(1.0, this.halfWidth));

        final double shapeNoiseSample = this.landShapeNoise.evaluateNoise(x, z);
        final double noisyProgress = clamp01(progress + shapeNoiseSample * (this.settings.shapeNoiseBaseAmplitude() + this.settings.shapeNoiseProgressAmplitude() * progress));

        final int coastY = generatorSettings.seaLevel() + 1;
        final int centerY = Math.max(generatorSettings.landTopY() - 1, coastY + this.terraceTotalHeight);
        final int span = centerY - coastY;

        final int rawSurfaceY = coastY + (int) Math.round(noisyProgress * span);
        final int surfaceY = coastY + this.terracedHeight(rawSurfaceY - coastY);

        final double detailNoise = this.landDetailNoise.evaluateNoise(x, z);
        final int detailOffset = (int) Math.round(detailNoise * (this.settings.detailNoiseBaseAmplitude() + progress));

        return Math.max(coastY, Math.min(centerY + 1, surfaceY + detailOffset));
    }

    private double cornerMask(final double x, final double z) {
        final double ax = Math.abs(x);
        final double az = Math.abs(z);

        final double sx = this.halfWidth - this.settings.baseCornerRadius();
        final double sz = this.halfHeight - this.settings.baseCornerRadius();

        final double tx = clamp01((ax - sx) / this.settings.baseCornerRadius());
        final double tz = clamp01((az - sz) / this.settings.baseCornerRadius());

        return smoothstep(tx) * smoothstep(tz);
    }

    private double sdfRoundedRect(final double x, final double z, final double r) {
        final double qx = Math.abs(x) - (this.halfWidth - r);
        final double qz = Math.abs(z) - (this.halfHeight - r);

        final double ox = Math.max(qx, 0);
        final double oz = Math.max(qz, 0);

        final double outside = Math.sqrt(ox * ox + oz * oz);
        final double inside = Math.min(Math.max(qx, qz), 0);

        return outside + inside - r;
    }

    private int terracedHeight(final int relativeHeight) {
        final int height = Math.max(0, relativeHeight);
        int accumulated = 0;

        for (final int stepHeight : this.terraceStepHeights) {
            final int next = accumulated + stepHeight;
            if (height < next) {
                return accumulated;
            }
            accumulated = next;
        }

        return accumulated;
    }

    public record Sample(double signedDistance, int highestY) {
    }
}
