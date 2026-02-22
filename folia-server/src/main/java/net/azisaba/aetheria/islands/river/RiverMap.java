package net.azisaba.aetheria.islands.river;

import net.azisaba.aetheria.islands.IslandsGeneratorSettings;
import net.azisaba.aetheria.islands.noise.IslandNoise;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class RiverMap {
    private static final int TERRACE_CROSS_DROP_THRESHOLD = 1;
    private static final int CROSS_SECTION_HEIGHT_TOLERANCE = 0;

    private final RiverLayout layout;
    private final IslandNoise islandNoise;
    private final RiverSettings settings;
    private final IslandsGeneratorSettings generatorSettings;

    public static RiverMap createDefault(
            final IslandNoise islandNoise,
            final IslandsGeneratorSettings generatorSettings
    ) {
        return new RiverMap(
                islandNoise,
                RiverSettings.createDefault(),
                generatorSettings
        );
    }

    private static double distanceToSegment(final double px, final double pz, final double ax, final double az, final double bx, final double bz) {
        final double abx = bx - ax;
        final double abz = bz - az;
        final double abLenSq = abx * abx + abz * abz;
        if (abLenSq <= 1.0e-8) {
            final double dx = px - ax;
            final double dz = pz - az;
            return Math.sqrt(dx * dx + dz * dz);
        }

        final double t = projectedTOnSegment(px, pz, ax, az, bx, bz);
        final double cx = ax + abx * t;
        final double cz = az + abz * t;
        final double dx = px - cx;
        final double dz = pz - cz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double projectedTOnSegment(
            final double px,
            final double pz,
            final double ax,
            final double az,
            final double bx,
            final double bz
    ) {
        final double abx = bx - ax;
        final double abz = bz - az;
        final double apx = px - ax;
        final double apz = pz - az;
        final double abLenSq = abx * abx + abz * abz;
        if (abLenSq <= 1.0e-8) {
            return 0.0;
        }
        return Mth.clamp((apx * abx + apz * abz) / abLenSq, 0.0, 1.0);
    }

    public RiverMap(
            final IslandNoise islandNoise,
            final RiverSettings settings,
            final IslandsGeneratorSettings generatorSettings
    ) {
        this.islandNoise = islandNoise;
        this.settings = settings;
        this.generatorSettings = generatorSettings;
        this.layout = new RiverLayout.Builder()
                .seed(islandNoise.seed())
                .size(islandNoise.width(), islandNoise.height())
                .islandNoise(islandNoise)
                .settings(settings)
                .generatorSettings(generatorSettings)
                .build();
    }

    public int sampleDepth(final double x, final double z, final double signedDistance, final int baseHighestY) {
        final double mouthChannelStrength = this.computeMouthChannelStrength(x, z, signedDistance);
        final boolean inMouthCorridor = this.isInMouthCorridor(x, z);

        if (signedDistance > 0.0) {
            final boolean nearMouthChannel = (mouthChannelStrength > 0.16 || inMouthCorridor)
                    && signedDistance < this.generatorSettings.beachWidth() * 2.2;
            if (nearMouthChannel) {
                final int highestY = Math.min(baseHighestY, this.generatorSettings.seaLevel() - 1);
                return Math.max(0, baseHighestY - highestY);
            }
            return 0;
        }

        final int seaLevel = this.generatorSettings.seaLevel();
        int highestY = baseHighestY;

        final boolean nearCoastBand = this.isNearCoastBand(signedDistance);
        if (inMouthCorridor && nearCoastBand) {
            highestY = Math.min(highestY, seaLevel - 1);
            return Math.max(0, baseHighestY - Math.max(seaLevel - 1, highestY));
        }

        final int terracedBaseY = this.islandNoise.computeTerracedHighestY(x, z, signedDistance, this.generatorSettings);
        final double riverStrength = this.computeRiverStrength(x, z, signedDistance, terracedBaseY);
        if (riverStrength <= 0.0) {
            return 0;
        }

        final int localStepHeight = this.islandNoise.terraceStepAtY(baseHighestY, this.generatorSettings);
        final int maxCarveInStep = Math.max(1, localStepHeight - 1);
        final int riverCarve = (int) Math.round(Math.min(this.settings.riverDepth(), maxCarveInStep) * riverStrength);
        highestY -= Math.max(0, riverCarve);

        final boolean nearBeachMouth = this.isNearCoastBand(signedDistance)
                && (mouthChannelStrength > 0.10 || inMouthCorridor);
        if (nearBeachMouth) {
            highestY = Math.min(highestY, seaLevel - 1);
        }

        final int clampedHighestY = Math.max(seaLevel - 1, highestY);
        return Math.max(0, baseHighestY - clampedHighestY);
    }

    private double computeRiverStrength(final double x, final double z, final double signedDistance, final int terracedBaseY) {
        if (signedDistance > this.settings.coastBand() * 0.9) {
            return 0.0;
        }
        double strength = this.computePathRiverStrength(x, z, terracedBaseY);
        strength = Math.max(strength, this.computeMouthChannelStrength(x, z, signedDistance));
        return strength;
    }

    private double computePathRiverStrength(final double x, final double z, final int terracedBaseY) {
        double strength = 0.0;
        for (final RiverLayout.Path path : this.layout.paths()) {
            for (int i = 0; i < path.xs().length - 1; i++) {
                final double ax = path.xs()[i];
                final double az = path.zs()[i];
                final double bx = path.xs()[i + 1];
                final double bz = path.zs()[i + 1];
                final double dist = distanceToSegment(x, z, ax, az, bx, bz);
                final double width = Math.max(1.0, (path.widths()[i] + path.widths()[i + 1]) * 0.5);
                if (dist > width) {
                    continue;
                }
                final double t = projectedTOnSegment(x, z, ax, az, bx, bz);
                final double cx = ax + (bx - ax) * t;
                final double cz = az + (bz - az) * t;
                if (!this.isCompatibleTerraceBand(cx, cz, terracedBaseY)) {
                    continue;
                }
                if (!this.hasStableCrossSection(cx, cz, ax, az, bx, bz, width, terracedBaseY)) {
                    continue;
                }
                final double core = Mth.clamp((width - dist) / width, 0.0, 1.0);
                strength = Math.max(strength, core);
            }
        }
        return strength;
    }

    private boolean isCompatibleTerraceBand(final double centerX, final double centerZ, final int terracedBaseY) {
        final double centerSignedDistance = this.islandNoise.computeSignedDistance(centerX, centerZ);
        if (centerSignedDistance > 0.0) {
            return false;
        }
        final int centerHighestY = this.islandNoise.computeTerracedHighestY(
                centerX,
                centerZ,
                centerSignedDistance,
                this.generatorSettings
        );
        return Math.abs(centerHighestY - terracedBaseY) < TERRACE_CROSS_DROP_THRESHOLD;
    }

    private boolean hasStableCrossSection(
            final double centerX,
            final double centerZ,
            final double ax,
            final double az,
            final double bx,
            final double bz,
            final double width,
            final int terracedBaseY
    ) {
        final double dx = bx - ax;
        final double dz = bz - az;
        final double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= 1.0e-8) {
            return true;
        }

        final double nx = -dz / length;
        final double nz = dx / length;
        final double sampleDistance = Math.max(1.0, width * 0.7);
        return this.isCrossSectionPointValid(centerX + nx * sampleDistance, centerZ + nz * sampleDistance, terracedBaseY)
                && this.isCrossSectionPointValid(centerX - nx * sampleDistance, centerZ - nz * sampleDistance, terracedBaseY);
    }

    private boolean isCrossSectionPointValid(final double sampleX, final double sampleZ, final int terracedBaseY) {
        final double signedDistance = this.islandNoise.computeSignedDistance(sampleX, sampleZ);
        if (signedDistance > 0.0) {
            return false;
        }
        final int sampleHighestY = this.islandNoise.computeTerracedHighestY(sampleX, sampleZ, signedDistance, this.generatorSettings);
        return Math.abs(sampleHighestY - terracedBaseY) <= CROSS_SECTION_HEIGHT_TOLERANCE;
    }

    private double computeMouthChannelStrength(final double x, final double z, final double signedDistance) {
        double strength = 0.0;
        final double coastChannelBand = Math.max(this.generatorSettings.beachWidth() * 2.4, this.settings.coastBand() * 0.7);
        final double coastBlend = Mth.clamp(1.0 - Math.abs(signedDistance) / coastChannelBand, 0.0, 1.0);
        if (coastBlend > 0.0) {
            for (final RiverLayout.MouthChannel channel : this.layout.mouthChannels()) {
                final double endX = this.mouthChannelEndX(channel);
                final double endZ = this.mouthChannelEndZ(channel);
                final double dist = distanceToSegment(x, z, channel.headX(), channel.headZ(), endX, endZ);
                final double channelWidth = Math.max(2.8, channel.width());
                final double core = Mth.clamp((channelWidth - dist) / channelWidth, 0.0, 1.0);
                final double boosted = core * (0.72 + 0.28 * coastBlend);
                strength = Math.max(strength, boosted);
            }
        }
        return strength;
    }

    private boolean isInMouthCorridor(final double x, final double z) {
        final double corridorWidth = Math.max(3.0, this.settings.riverWidth() * 0.88);
        final double corridorWidthSq = corridorWidth * corridorWidth;
        final double maxSearchDistance = this.generatorSettings.beachWidth() * 2.2 + this.settings.coastBand() * 0.20;
        for (final RiverLayout.MouthChannel channel : this.layout.mouthChannels()) {
            final double endX = this.mouthChannelEndX(channel);
            final double endZ = this.mouthChannelEndZ(channel);
            final double dist = distanceToSegment(x, z, channel.headX(), channel.headZ(), endX, endZ);
            if (dist * dist <= corridorWidthSq) {
                return true;
            }
            final double dx = x - channel.mouthX();
            final double dz = z - channel.mouthZ();
            if (dx * dx + dz * dz <= maxSearchDistance * maxSearchDistance && dist <= corridorWidth * 1.2) {
                return true;
            }
        }
        return false;
    }

    private boolean isNearCoastBand(final double signedDistance) {
        return signedDistance > -this.generatorSettings.beachWidth() * 0.70;
    }

    private double mouthChannelEndX(final RiverLayout.MouthChannel channel) {
        return channel.mouthX() + Math.cos(channel.angle()) * this.mouthOffShoreLength();
    }

    private double mouthChannelEndZ(final RiverLayout.MouthChannel channel) {
        return channel.mouthZ() + Math.sin(channel.angle()) * this.mouthOffShoreLength();
    }

    private double mouthOffShoreLength() {
        return this.generatorSettings.beachWidth() * 1.5 + this.settings.coastBand() * 0.40;
    }
}
