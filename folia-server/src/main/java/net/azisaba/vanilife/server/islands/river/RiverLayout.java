package net.azisaba.vanilife.server.islands.river;

import com.google.common.base.Preconditions;
import de.articdive.jnoise.core.api.functions.Interpolation;
import de.articdive.jnoise.generators.noise_parameters.fade_functions.FadeFunction;
import de.articdive.jnoise.modules.octavation.fractal_functions.FractalFunction;
import de.articdive.jnoise.pipeline.JNoise;
import net.azisaba.vanilife.server.islands.IslandsGeneratorSettings;
import net.azisaba.vanilife.server.islands.noise.IslandNoise;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@NullMarked
public record RiverLayout(Path[] paths, MouthChannel[] mouthChannels) {
    public RiverLayout {
        paths = paths.clone();
        mouthChannels = mouthChannels.clone();
    }

    @Override
    public Path[] paths() {
        return this.paths.clone();
    }

    @Override
    public MouthChannel[] mouthChannels() {
        return this.mouthChannels.clone();
    }

    public record Path(double[] xs, double[] zs, double[] widths) {
    }

    public record MouthChannel(double headX, double headZ, double mouthX, double mouthZ, double angle, double width) {
    }

    @NullMarked
    public static final class Builder {
        private static final int RIVER_SAMPLES = 40;
        private static final double WATERFALL_START_T = 0.58;
        private static final double WATERFALL_MAX_WIDTH = 4.6;
        private static final int WATERFALL_DROP_THRESHOLD = 2;
        private static final int WATERFALL_STRAIGHT_BEFORE = 7;
        private static final int WATERFALL_STRAIGHT_AFTER = 7;
        private static final int MOUTH_CANDIDATE_TRIES = 8;
        private static final int TRUNK_SOURCE_CANDIDATE_TRIES = 20;

        private static double clamp(final double value, final double halfExtent) {
            final double limit = halfExtent * 0.84;
            return Math.max(-limit, Math.min(limit, value));
        }

        private static double lerp(final double a, final double b, final double t) {
            return a + (b - a) * t;
        }

        private static int dominantCardinalAxis(final double angle) {
            final double x = Math.cos(angle);
            final double z = Math.sin(angle);
            return Math.abs(x) >= Math.abs(z) ? 0 : 1;
        }

        private static double projectTOnSegment(final double px, final double pz, final double ax, final double az, final double bx, final double bz) {
            final double abx = bx - ax;
            final double abz = bz - az;
            final double apx = px - ax;
            final double apz = pz - az;
            final double lenSq = abx * abx + abz * abz;
            if (lenSq <= 1.0e-8) {
                return 0.5;
            }
            return Mth.clamp((apx * abx + apz * abz) / lenSq, 0.0, 1.0);
        }

        private @Nullable Long seed;
        private @Nullable Integer width;
        private @Nullable Integer height;
        private @Nullable IslandNoise islandNoise;
        private @Nullable RiverSettings settings;
        private @Nullable IslandsGeneratorSettings generatorSettings;

        public Builder seed(final long seed) {
            this.seed = seed;
            return this;
        }

        public Builder width(final int width) {
            this.width = width;
            return this;
        }

        public Builder height(final int height) {
            this.height = height;
            return this;
        }

        public Builder size(final int width, final int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder islandNoise(final IslandNoise islandNoise) {
            this.islandNoise = islandNoise;
            return this;
        }

        public Builder settings(final RiverSettings settings) {
            this.settings = settings;
            return this;
        }

        public Builder generatorSettings(final IslandsGeneratorSettings generatorSettings) {
            this.generatorSettings = generatorSettings;
            return this;
        }

        public RiverLayout build() {
            Preconditions.checkState(this.seed != null, "Seed not set");
            Preconditions.checkState(this.width != null, "Width not set");
            Preconditions.checkState(this.height != null, "Height not set");
            Preconditions.checkState(this.islandNoise != null, "Island noise not set");
            Preconditions.checkState(this.settings != null, "River settings not set");
            Preconditions.checkState(this.generatorSettings != null, "Generator settings not set");

            final RandomSource randomSource = RandomSource.create(this.seed ^ 0x4CF5AD432745937FL);
            final BuildContext context = new BuildContext(
                    this.width / 2.0,
                    this.height / 2.0,
                    this.settings.riverMouthCount().sample(randomSource),
                    this.settings.riverCount().sample(randomSource),
                    this.islandNoise,
                    this.settings,
                    this.generatorSettings,
                    JNoise.newBuilder()
                            .perlin(this.seed ^ 0x8B8B8B8B8B8B8B8BL, Interpolation.COSINE, FadeFunction.CUBIC_POLY)
                            .scale(this.settings.riverScale())
                            .octavate(3, 0.5, 2.0, FractalFunction.FBM, false)
                            .build(),
                    randomSource
            );

            final List<Path> paths = new ArrayList<>();
            final List<MouthChannel> channels = new ArrayList<>();

            final Estuary estuary = this.appendEstuaryPaths(paths, channels, context);
            final int primaryMouthIndex = this.selectPrimaryMouthIndex(estuary.mouths(), context);
            final Node primaryHead = estuary.estuaryHeads()[primaryMouthIndex];
            final Node trunkSource = this.createTrunkSource(context, primaryHead);

            this.appendTrunkPath(paths, trunkSource, primaryHead, context);
            this.appendMouthConnectionPaths(paths, estuary.estuaryHeads(), primaryMouthIndex, trunkSource, primaryHead, context);
            this.appendUpstreamBranchPaths(paths, trunkSource, primaryHead, context);

            final Path[] aligned = this.alignWaterfallsToCardinal(paths, context);
            return new RiverLayout(aligned, channels.toArray(new MouthChannel[0]));
        }

        private Estuary appendEstuaryPaths(final List<Path> paths, final List<MouthChannel> channels, final BuildContext context) {
            final Mouth[] mouths = this.createMouths(context.riverMouthCount(), context);
            final Node[] estuaryHeads = new Node[mouths.length];

            for (int i = 0; i < mouths.length; i++) {
                final Mouth mouth = mouths[i];
                final double headX = clamp(mouth.x() * (0.78 + context.randomSource().nextDouble() * 0.12)
                        + (context.randomSource().nextDouble() * 2.0 - 1.0) * context.halfWidth() * 0.05, context.halfWidth());
                final double headZ = clamp(mouth.z() * (0.78 + context.randomSource().nextDouble() * 0.12)
                        + (context.randomSource().nextDouble() * 2.0 - 1.0) * context.halfHeight() * 0.05, context.halfHeight());
                estuaryHeads[i] = new Node(headX, headZ);
                channels.add(new MouthChannel(headX, headZ, mouth.x(), mouth.z(), mouth.angle(), context.settings().riverWidth() * 0.72));
                paths.add(this.createDirectedRiverPath(
                        context,
                        i * 11 + 1,
                        headX,
                        headZ,
                        mouth.x(),
                        mouth.z(),
                        mouth.angle(),
                        0.92,
                        true
                ));
            }

            return new Estuary(mouths, estuaryHeads);
        }

        private Mouth[] createMouths(final int mouthCount, final BuildContext context) {
            final List<Mouth> mouths = new ArrayList<>(mouthCount);
            final int[] edgeOrder = new int[]{0, 1, 2, 3};
            this.shuffle(edgeOrder, context.randomSource());
            for (int i = 0; i < mouthCount; i++) {
                final int edge = edgeOrder[i % edgeOrder.length];
                final int preferredSign = context.randomSource().nextBoolean() ? 1 : -1;
                mouths.add(this.createMouthOnEdge(edge, preferredSign, context));
            }
            return mouths.toArray(new Mouth[0]);
        }

        private Mouth createMouthOnEdge(final int edge, final int preferredSign, final BuildContext context) {
            final double edgeX = context.halfWidth() * 0.96;
            final double edgeZ = context.halfHeight() * 0.96;
            double bestX = 0.0;
            double bestZ = 0.0;
            double bestScore = Double.POSITIVE_INFINITY;
            final double coastBand = Math.max(6.0, context.generatorSettings().beachWidth() * 1.8);

            for (int i = 0; i < MOUTH_CANDIDATE_TRIES; i++) {
                final int sign = i == 0 ? preferredSign : (context.randomSource().nextBoolean() ? 1 : -1);
                final double alongX = context.halfWidth() * (0.22 + context.randomSource().nextDouble() * 0.62);
                final double alongZ = context.halfHeight() * (0.22 + context.randomSource().nextDouble() * 0.62);

                final double x;
                final double z;
                if (edge == 0) {
                    x = edgeX;
                    z = sign * alongZ;
                } else if (edge == 1) {
                    x = -edgeX;
                    z = sign * alongZ;
                } else if (edge == 2) {
                    x = sign * alongX;
                    z = edgeZ;
                } else {
                    x = sign * alongX;
                    z = -edgeZ;
                }

                final double signedDistance = context.islandNoise().computeSignedDistance(x, z);
                final double shorelineScore = Math.abs(signedDistance);
                final double inlandPenalty = signedDistance < -coastBand ? (-coastBand - signedDistance) : 0.0;
                final double deepSeaPenalty = signedDistance > coastBand * 1.2 ? signedDistance - coastBand * 1.2 : 0.0;
                final double score = shorelineScore + inlandPenalty * 2.5 + deepSeaPenalty * 1.8;
                if (score < bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestZ = z;
                }
            }

            return new Mouth(bestX, bestZ, Math.atan2(bestZ, bestX));
        }

        private int selectPrimaryMouthIndex(final Mouth[] mouths, final BuildContext context) {
            if (mouths.length == 1) {
                return 0;
            }

            final double biasAngle = context.randomSource().nextDouble() * Math.PI * 2.0;
            final double biasX = Math.cos(biasAngle);
            final double biasZ = Math.sin(biasAngle);
            int index = 0;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < mouths.length; i++) {
                final Mouth mouth = mouths[i];
                final double coastTangent = Math.abs((-mouth.z()) * biasX + mouth.x() * biasZ);
                final double radial = mouth.x() * biasX + mouth.z() * biasZ;
                final double score = radial * 0.7 + coastTangent * 0.3 + context.randomSource().nextDouble() * 8.0;
                if (score > bestScore) {
                    bestScore = score;
                    index = i;
                }
            }
            return index;
        }

        private Node createTrunkSource(final BuildContext context, final Node primaryHead) {
            Node best = new Node(
                    clamp((context.randomSource().nextDouble() * 2.0 - 1.0) * context.halfWidth() * 0.32, context.halfWidth()),
                    clamp((context.randomSource().nextDouble() * 2.0 - 1.0) * context.halfHeight() * 0.32, context.halfHeight())
            );
            double bestScore = Double.NEGATIVE_INFINITY;
            final double minInlandDistance = Math.max(8.0, context.generatorSettings().beachWidth() * 1.2);

            for (int i = 0; i < TRUNK_SOURCE_CANDIDATE_TRIES; i++) {
                final double angle = context.randomSource().nextDouble() * Math.PI * 2.0;
                final double radius = Math.min(context.halfWidth(), context.halfHeight()) * (0.16 + context.randomSource().nextDouble() * 0.44);
                final double x = clamp(Math.cos(angle) * radius + (context.randomSource().nextDouble() * 2.0 - 1.0) * context.halfWidth() * 0.08, context.halfWidth());
                final double z = clamp(Math.sin(angle) * radius + (context.randomSource().nextDouble() * 2.0 - 1.0) * context.halfHeight() * 0.08, context.halfHeight());
                final double signedDistance = context.islandNoise().computeSignedDistance(x, z);
                if (signedDistance > -minInlandDistance) {
                    continue;
                }
                final int baseY = context.islandNoise().computeBaseHighestY(x, z, signedDistance, context.generatorSettings());
                final double distToPrimary = Math.sqrt((x - primaryHead.x()) * (x - primaryHead.x()) + (z - primaryHead.z()) * (z - primaryHead.z()));
                final double centerBias = 1.0 - (Math.sqrt(x * x + z * z) / Math.min(context.halfWidth(), context.halfHeight()));
                final double score = baseY * 0.9 + distToPrimary * 0.25 + centerBias * 16.0 + context.randomSource().nextDouble() * 6.0;
                if (score > bestScore) {
                    bestScore = score;
                    best = new Node(x, z);
                }
            }
            return best;
        }

        private void shuffle(final int[] values, final RandomSource random) {
            for (int i = values.length - 1; i > 0; i--) {
                final int j = random.nextInt(i + 1);
                final int tmp = values[i];
                values[i] = values[j];
                values[j] = tmp;
            }
        }

        private void appendTrunkPath(final List<Path> paths, final Node trunkSource, final Node primaryHead, final BuildContext context) {
            paths.add(this.createDirectedRiverPath(
                    context,
                    700,
                    trunkSource.x(),
                    trunkSource.z(),
                    primaryHead.x(),
                    primaryHead.z(),
                    Math.atan2(primaryHead.z() - trunkSource.z(), primaryHead.x() - trunkSource.x()),
                    1.06,
                    false
            ));
        }

        private void appendMouthConnectionPaths(final List<Path> paths, final Node[] estuaryHeads, final int primaryMouthIndex, final Node trunkSource, final Node primaryHead, final BuildContext context) {
            for (int i = 0; i < estuaryHeads.length; i++) {
                if (i == primaryMouthIndex) {
                    continue;
                }
                final Node head = estuaryHeads[i];
                final double t = 0.34 + context.randomSource().nextDouble() * 0.42;
                final double junctionX = clamp(lerp(trunkSource.x(), primaryHead.x(), t)
                        + (context.randomSource().nextDouble() * 2.0 - 1.0) * context.halfWidth() * 0.06, context.halfWidth());
                final double junctionZ = clamp(lerp(trunkSource.z(), primaryHead.z(), t)
                        + (context.randomSource().nextDouble() * 2.0 - 1.0) * context.halfHeight() * 0.06, context.halfHeight());
                paths.add(this.createDirectedRiverPath(
                        context,
                        900 + i * 17,
                        junctionX,
                        junctionZ,
                        head.x(),
                        head.z(),
                        Math.atan2(head.z() - junctionZ, head.x() - junctionX),
                        0.90,
                        false
                ));
            }
        }

        private void appendUpstreamBranchPaths(final List<Path> paths, final Node trunkSource, final Node primaryHead, final BuildContext context) {
            final double branchBaseAngle = context.randomSource().nextDouble() * Math.PI * 2.0;
            final double branchStep = (Math.PI * 2.0) / context.upstreamBranchCount();
            for (int i = 0; i < context.upstreamBranchCount(); i++) {
                final double jitter = (context.randomSource().nextDouble() * 2.0 - 1.0) * branchStep * 0.28;
                final double angle = branchBaseAngle + i * branchStep + jitter;
                final double radius = Math.min(context.halfWidth(), context.halfHeight()) * (0.48 + context.randomSource().nextDouble() * 0.34);
                final double sourceX = clamp(Math.cos(angle) * radius + (context.randomSource().nextDouble() * 2.0 - 1.0) * context.halfWidth() * 0.08, context.halfWidth());
                final double sourceZ = clamp(Math.sin(angle) * radius + (context.randomSource().nextDouble() * 2.0 - 1.0) * context.halfHeight() * 0.08, context.halfHeight());
                final double t = projectTOnSegment(sourceX, sourceZ, trunkSource.x(), trunkSource.z(), primaryHead.x(), primaryHead.z());
                final double targetT = Mth.clamp(t + (context.randomSource().nextDouble() * 2.0 - 1.0) * 0.06, 0.0, 1.0);
                final double targetX = clamp(lerp(trunkSource.x(), primaryHead.x(), targetT), context.halfWidth());
                final double targetZ = clamp(lerp(trunkSource.z(), primaryHead.z(), targetT), context.halfHeight());
                paths.add(this.createDirectedRiverPath(
                        context,
                        1300 + i * 19,
                        sourceX,
                        sourceZ,
                        targetX,
                        targetZ,
                        Math.atan2(targetZ - sourceZ, targetX - sourceX),
                        0.70,
                        false
                ));
            }
        }

        private Path createDirectedRiverPath(
                final BuildContext context,
                final int index,
                final double startX, final double startZ,
                final double endX, final double endZ,
                final double mouthAngle,
                final double widthFactor,
                final boolean downstream
        ) {
            final double baseAngle = Math.atan2(endZ - startZ, endX - startX);
            final double normalX = -Math.sin(baseAngle);
            final double normalZ = Math.cos(baseAngle);
            final double meanderScale = downstream ? 1.0 : 0.72;
            final double meanderAmplitude = context.settings().riverMeanderAmplitude() * meanderScale * (0.82 + context.randomSource().nextDouble() * 0.28);
            final double widthScale = widthFactor * (0.72 + context.randomSource().nextDouble() * 0.30);
            final double phase = context.randomSource().nextDouble() * Math.PI * 2.0;
            final double freq = 1.35 + context.randomSource().nextDouble() * 1.30;
            final double microPhase = context.randomSource().nextDouble() * Math.PI * 2.0;
            final double microFreq = 2.8 + context.randomSource().nextDouble() * 1.6;
            final int cardinalAxis = dominantCardinalAxis(mouthAngle);

            final double[] xs = new double[RIVER_SAMPLES];
            final double[] zs = new double[RIVER_SAMPLES];
            final double[] widths = new double[RIVER_SAMPLES];

            for (int i = 0; i < RIVER_SAMPLES; i++) {
                final double t = (double) i / (RIVER_SAMPLES - 1);
                final double baseX = lerp(startX, endX, t);
                final double baseZ = lerp(startZ, endZ, t);
                final double envelope = Math.sin(Math.PI * t);
                final double coarse = Math.sin((t * freq + phase) * Math.PI * 2.0);
                final double micro = Math.sin((t * microFreq + microPhase) * Math.PI * 2.0);
                final double noise = context.riverNoise().evaluateNoise(index * 21.7 + t * 8.1, index * 13.3 - t * 6.9);
                final double meander = (coarse * 0.62 + micro * 0.16 + noise * 0.22) * meanderAmplitude * envelope;

                double x = baseX + normalX * meander;
                double z = baseZ + normalZ * meander;
                if (downstream && t > 0.82) {
                    final double k = (t - 0.82) / 0.18;
                    final double blend = Mth.smoothstep(Mth.clamp(k, 0.0, 1.0));
                    final double deltaX = Math.cos(mouthAngle);
                    final double deltaZ = Math.sin(mouthAngle);
                    x = lerp(x, endX - deltaX * (1.0 - t) * context.halfWidth() * 0.28, blend);
                    z = lerp(z, endZ - deltaZ * (1.0 - t) * context.halfHeight() * 0.28, blend);
                }
                if (downstream && t >= WATERFALL_START_T) {
                    if (cardinalAxis == 0) {
                        z = zs[i - 1];
                    } else {
                        x = xs[i - 1];
                    }
                }

                xs[i] = x;
                zs[i] = z;
                final double widthProfile;
                if (downstream) {
                    final double broad = 0.72 + 0.14 * Mth.smoothstep(t);
                    final double mouthTaper = 1.0 - 0.20 * Mth.smoothstep(Mth.clamp((t - 0.82) / 0.18, 0.0, 1.0));
                    widthProfile = broad * mouthTaper;
                } else {
                    widthProfile = 0.72 - 0.24 * t;
                }
                double width = Math.max(3.2, context.settings().riverWidth() * widthScale * widthProfile);
                if (downstream && t >= WATERFALL_START_T) {
                    width = Math.min(width, WATERFALL_MAX_WIDTH);
                }
                widths[i] = width;
            }

            return new Path(xs, zs, widths);
        }

        private Path[] alignWaterfallsToCardinal(final List<Path> paths, final BuildContext context) {
            final List<Path> alignedPaths = new ArrayList<>(paths.size());
            for (final Path path : paths) {
                alignedPaths.add(this.alignWaterfallsToCardinal(path, context));
            }
            return alignedPaths.toArray(new Path[0]);
        }

        private Path alignWaterfallsToCardinal(final Path path, final BuildContext context) {
            final double[] xs = path.xs().clone();
            final double[] zs = path.zs().clone();
            final double[] widths = path.widths().clone();

            int i = 0;
            while (i < xs.length - 1) {
                if (!this.isWaterfallDrop(xs, zs, i, context)) {
                    i++;
                    continue;
                }

                final int dropStart = i;
                int dropEnd = i;
                while (dropEnd + 1 < xs.length - 1 && this.isWaterfallDrop(xs, zs, dropEnd + 1, context)) {
                    dropEnd++;
                }

                final int from = Math.max(0, dropStart - WATERFALL_STRAIGHT_BEFORE);
                final int to = Math.min(xs.length - 1, dropEnd + 1 + WATERFALL_STRAIGHT_AFTER);
                final double dx = xs[to] - xs[from];
                final double dz = zs[to] - zs[from];
                final int axis = dominantCardinalAxis(Math.atan2(dz, dx));
                final int count = Math.max(1, to - from);
                final double anchorX = this.average(xs, dropStart, dropEnd + 1);
                final double anchorZ = this.average(zs, dropStart, dropEnd + 1);
                final double startX = xs[from];
                final double startZ = zs[from];
                final double endX = xs[to];
                final double endZ = zs[to];

                if (axis == 0) {
                    for (int j = from; j <= to; j++) {
                        final double t = (double) (j - from) / count;
                        xs[j] = lerp(startX, endX, t);
                        zs[j] = anchorZ;
                        widths[j] = Math.min(widths[j], WATERFALL_MAX_WIDTH);
                    }
                } else {
                    for (int j = from; j <= to; j++) {
                        final double t = (double) (j - from) / count;
                        xs[j] = anchorX;
                        zs[j] = lerp(startZ, endZ, t);
                        widths[j] = Math.min(widths[j], WATERFALL_MAX_WIDTH);
                    }
                }
                i = dropEnd + 1;
            }

            return new Path(xs, zs, widths);
        }

        private boolean isWaterfallDrop(final double[] xs, final double[] zs, final int segmentIndex, final BuildContext context) {
            final double signedDistance0 = context.islandNoise().computeSignedDistance(xs[segmentIndex], zs[segmentIndex]);
            final double signedDistance1 = context.islandNoise().computeSignedDistance(xs[segmentIndex + 1], zs[segmentIndex + 1]);
            if (signedDistance0 > 0.0 || signedDistance1 > 0.0) {
                return false;
            }

            final int y0 = context.islandNoise().computeBaseHighestY(
                    xs[segmentIndex],
                    zs[segmentIndex],
                    signedDistance0,
                    context.generatorSettings()
            );
            final int y1 = context.islandNoise().computeBaseHighestY(
                    xs[segmentIndex + 1],
                    zs[segmentIndex + 1],
                    signedDistance1,
                    context.generatorSettings()
            );
            return y0 - y1 >= WATERFALL_DROP_THRESHOLD;
        }

        private double average(final double[] values, final int from, final int toInclusive) {
            double sum = 0.0;
            int count = 0;
            for (int i = from; i <= toInclusive; i++) {
                sum += values[i];
                count++;
            }
            return count > 0 ? sum / count : values[Math.max(0, Math.min(values.length - 1, from))];
        }

        private record Mouth(double x, double z, double angle) {
        }

        private record Node(double x, double z) {
        }

        private record Estuary(Mouth[] mouths, Node[] estuaryHeads) {
        }
    }

    @NullMarked
    private record BuildContext(
            double halfWidth,
            double halfHeight,
            int riverMouthCount,
            int upstreamBranchCount,
            IslandNoise islandNoise,
            RiverSettings settings,
            IslandsGeneratorSettings generatorSettings,
            JNoise riverNoise,
            RandomSource randomSource
    ) {
    }
}
