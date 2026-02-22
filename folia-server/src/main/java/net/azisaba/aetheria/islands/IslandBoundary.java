package net.azisaba.aetheria.islands;

import de.articdive.jnoise.core.api.functions.Interpolation;
import de.articdive.jnoise.generators.noise_parameters.fade_functions.FadeFunction;
import de.articdive.jnoise.modules.octavation.fractal_functions.FractalFunction;
import de.articdive.jnoise.pipeline.JNoise;
import org.jspecify.annotations.NonNull;

public class IslandBoundary {
    public static @NonNull IslandBoundary createDefault(final long seed) {
        return new IslandBoundary(
                seed,
                Islands.ISLAND_WIDTH_GRIDS * Islands.GRID_SIZE,
                Islands.ISLAND_HEIGHT_GRIDS * Islands.GRID_SIZE,
                28.0,
                9.0,
                26.0,
                10.0
        );
    }

    private static double clamp01(final double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    private static double smooth(final double t) {
        return t * t * (3 - 2 * t);
    }

    private final double halfWidth;
    private final double halfHeight;

    private final double baseCornerRadius;
    private final double cornerRadiusAmplitude;

    private final double coastBand;
    private final double coastAmplitude;

    private final JNoise coastNoise;
    private final JNoise cornerNoise;

    public IslandBoundary(
            final long seed,
            final int width, final int height,
            final double baseCornerRadius, final double cornerRadiusAmplitude,
            final double coastBand, final double coastAmplitude
    ) {
        this.halfWidth = width / 2.0;
        this.halfHeight = height / 2.0;
        this.baseCornerRadius = baseCornerRadius;
        this.cornerRadiusAmplitude = cornerRadiusAmplitude;
        this.coastBand = coastBand;
        this.coastAmplitude = coastAmplitude;
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
    }

    public double signedDistance(final double x, final double z) {
        final double cornerInfluence = this.cornerMask(x, z);
        final double cornerNoiseSample = this.cornerNoise.evaluateNoise(x, z);
        final double cornerRadius = this.baseCornerRadius + cornerNoiseSample * this.cornerRadiusAmplitude * cornerInfluence;

        final double baseSignedDistance = this.sdfRoundedRect(x, z, cornerRadius);

        final double coastInfluence = clamp01(1.0 - Math.abs(baseSignedDistance) / this.coastBand);
        final double coastNoiseSample = this.coastNoise.evaluateNoise(x, z);
        final double coastlineOffset = coastNoiseSample * this.coastAmplitude * coastInfluence;

        return baseSignedDistance + coastlineOffset;
    }

    private double cornerMask(final double x, final double z) {
        final double ax = Math.abs(x);
        final double az = Math.abs(z);

        final double sx = this.halfWidth -this.baseCornerRadius;
        final double sz = this.halfHeight - this.baseCornerRadius;

        final double tx = clamp01((ax - sx) / this.baseCornerRadius);
        final double tz = clamp01((az - sz) / this.baseCornerRadius);

        return smooth(tx) * smooth(tz);
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
}
