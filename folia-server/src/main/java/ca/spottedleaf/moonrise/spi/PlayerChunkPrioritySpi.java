package ca.spottedleaf.moonrise.spi;

import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate directional chunk prioritization SPI for
 * {@code RegionizedPlayerChunkLoader}.
 *
 * <p>Uses elliptical focal priority {@code score(dx, dz) = r - e * (dx * hx + dz * hz)} with an
 * inner isotropic safety ring so chunks ahead of a player's facing direction and movement velocity
 * load, generate, and send first without altering the steady-state radial view-distance footprint
 * or violating {@code LongHeapPriorityQueue} strict weak ordering.</p>
 *
 * @governedBy goldleaf-ADR-001, goldleaf-ADR-020
 * @trace S-004, S-005
 */
public final class PlayerChunkPrioritySpi {

    public static final double DEFAULT_LOOK_BIAS = 0.45D;
    public static final double DEFAULT_VELOCITY_BIAS = 0.75D;
    public static final int DEFAULT_INNER_RADIAL_RADIUS = 2;
    public static final double DEFAULT_YAW_REPRIORITIZE_DEGREES = 25.0D;

    private static final double SPEED_BLEND_LOW = 0.15D;
    private static final double SPEED_BLEND_HIGH = 1.00D;
    private static final double EMA_ALPHA = 0.35D;
    private static final double TELEPORT_DELTA_SQ = 256.0D;
    private static final long REPRIORITIZE_COOLDOWN_TICKS = 10L;

    private static volatile Settings current = Settings.DISABLED;

    private PlayerChunkPrioritySpi() {
    }

    public record Settings(
        boolean enabled,
        double lookBias,
        double velocityBias,
        int innerRadialRadius,
        double yawReprioritizeCosThreshold
    ) {
        public static final Settings DISABLED = new Settings(
            false,
            0.0D,
            0.0D,
            0,
            1.0D
        );

        public static final Settings DEFAULT = of(
            true,
            DEFAULT_LOOK_BIAS,
            DEFAULT_VELOCITY_BIAS,
            DEFAULT_INNER_RADIAL_RADIUS,
            DEFAULT_YAW_REPRIORITIZE_DEGREES
        );

        public static Settings of(
            final boolean enabled,
            final double lookBias,
            final double velocityBias,
            final int innerRadialRadius,
            final double yawReprioritizeDegrees
        ) {
            if (!Double.isFinite(lookBias) || lookBias < 0.0D || lookBias > 0.85D) {
                throw new IllegalArgumentException("lookBias must be in [0.0, 0.85], got " + lookBias);
            }
            if (!Double.isFinite(velocityBias) || velocityBias < 0.0D || velocityBias > 0.90D) {
                throw new IllegalArgumentException("velocityBias must be in [0.0, 0.90], got " + velocityBias);
            }
            if (innerRadialRadius < 0 || innerRadialRadius > 8) {
                throw new IllegalArgumentException("innerRadialRadius must be in [0, 8], got " + innerRadialRadius);
            }
            if (!Double.isFinite(yawReprioritizeDegrees) || yawReprioritizeDegrees < 5.0D || yawReprioritizeDegrees > 180.0D) {
                throw new IllegalArgumentException(
                    "yawReprioritizeDegrees must be in [5.0, 180.0], got " + yawReprioritizeDegrees
                );
            }
            return new Settings(
                enabled,
                lookBias,
                velocityBias,
                innerRadialRadius,
                Math.cos(Math.toRadians(yawReprioritizeDegrees))
            );
        }
    }

    /**
     * Per-player tick-thread-confined heading snapshot used by {@code RegionizedPlayerChunkLoader}.
     *
     * <p>Snapshot fields ({@code dirX}, {@code dirZ}, {@code bias}, {@code innerRadiusSq},
     * {@code active}) are mutated ONLY immediately before clearing and re-enqueueing the priority
     * heaps so {@code LongHeapPriorityQueue} comparator invariants are never violated.</p>
     */
    public static final class HeadingSnapshot {

        private double dirX;
        private double dirZ;
        private double bias;
        private int innerRadiusSq;
        private boolean active;

        private double emaVelX;
        private double emaVelZ;
        private double prevPosX;
        private double prevPosZ;
        private boolean hasPrevPos;
        private long lastReprioritizeTick = Long.MIN_VALUE;

        public void updateMotion(final double posX, final double posZ) {
            if (!this.hasPrevPos) {
                this.prevPosX = posX;
                this.prevPosZ = posZ;
                this.hasPrevPos = true;
                return;
            }
            final double dx = posX - this.prevPosX;
            final double dz = posZ - this.prevPosZ;
            this.prevPosX = posX;
            this.prevPosZ = posZ;

            final double deltaSq = dx * dx + dz * dz;
            if (!Double.isFinite(deltaSq) || deltaSq > TELEPORT_DELTA_SQ) {
                this.emaVelX = 0.0D;
                this.emaVelZ = 0.0D;
                return;
            }
            this.emaVelX = (1.0D - EMA_ALPHA) * this.emaVelX + EMA_ALPHA * dx;
            this.emaVelZ = (1.0D - EMA_ALPHA) * this.emaVelZ + EMA_ALPHA * dz;
        }

        public void snapshot(final float yawDegrees, final long currentTick, final Settings settings) {
            this.lastReprioritizeTick = currentTick;
            if (!settings.enabled() || (settings.lookBias() <= 0.0D && settings.velocityBias() <= 0.0D)) {
                this.dirX = 0.0D;
                this.dirZ = 0.0D;
                this.bias = 0.0D;
                this.innerRadiusSq = 0;
                this.active = false;
                return;
            }
            final double yawRad = Math.toRadians(yawDegrees);
            final double lookX = -Math.sin(yawRad);
            final double lookZ = Math.cos(yawRad);

            final double speed = Math.hypot(this.emaVelX, this.emaVelZ);
            final double t;
            if (speed <= SPEED_BLEND_LOW) {
                t = 0.0D;
            } else if (speed >= SPEED_BLEND_HIGH) {
                t = 1.0D;
            } else {
                t = (speed - SPEED_BLEND_LOW) / (SPEED_BLEND_HIGH - SPEED_BLEND_LOW);
            }

            double hx = lookX;
            double hz = lookZ;
            if (t > 0.0D && speed > 1.0E-6D) {
                final double velX = this.emaVelX / speed;
                final double velZ = this.emaVelZ / speed;
                final double velWeight = 0.85D * t;
                final double lookWeight = 1.0D - velWeight;
                final double bx = velWeight * velX + lookWeight * lookX;
                final double bz = velWeight * velZ + lookWeight * lookZ;
                final double len = Math.hypot(bx, bz);
                if (len > 1.0E-6D) {
                    hx = bx / len;
                    hz = bz / len;
                } else {
                    hx = velX;
                    hz = velZ;
                }
            }

            final double effectiveBias = settings.lookBias() + t * (settings.velocityBias() - settings.lookBias());
            this.dirX = hx;
            this.dirZ = hz;
            this.bias = Math.max(0.0D, Math.min(0.90D, effectiveBias));
            this.innerRadiusSq = settings.innerRadialRadius() * settings.innerRadialRadius();
            this.active = this.bias > 1.0E-6D;
        }

        public boolean shouldReprioritizeOnTurn(
            final float yawDegrees,
            final long currentTick,
            final Settings settings
        ) {
            if (!settings.enabled()) {
                return this.active;
            }
            if (this.lastReprioritizeTick != Long.MIN_VALUE
                && (currentTick - this.lastReprioritizeTick) < REPRIORITIZE_COOLDOWN_TICKS) {
                return false;
            }
            if (!this.active) {
                return settings.lookBias() > 0.0D || settings.velocityBias() > 0.0D;
            }
            final double yawRad = Math.toRadians(yawDegrees);
            final double lookX = -Math.sin(yawRad);
            final double lookZ = Math.cos(yawRad);
            final double cosDelta = this.dirX * lookX + this.dirZ * lookZ;
            return cosDelta < settings.yawReprioritizeCosThreshold();
        }

        public double dirX() {
            return this.dirX;
        }

        public double dirZ() {
            return this.dirZ;
        }

        public double bias() {
            return this.bias;
        }

        public int innerRadiusSq() {
            return this.innerRadiusSq;
        }

        public boolean isActive() {
            return this.active;
        }
    }

    /**
     * Strict total-order comparator for two chunk offsets relative to a player's chunk center.
     *
     * <p>When {@code snapshot.isActive()} is false, reduces identically to stock Moonrise
     * {@code Integer.compare(distSq1, distSq2)}.</p>
     */
    public static int compareOffsets(
        final int diff1X,
        final int diff1Z,
        final long key1,
        final int diff2X,
        final int diff2Z,
        final long key2,
        final HeadingSnapshot snapshot
    ) {
        final int distSq1 = (diff1X * diff1X) + (diff1Z * diff1Z);
        final int distSq2 = (diff2X * diff2X) + (diff2Z * diff2Z);
        if (!snapshot.active) {
            return Integer.compare(distSq1, distSq2);
        }
        final int innerSq = snapshot.innerRadiusSq;
        final boolean in1 = distSq1 <= innerSq;
        final boolean in2 = distSq2 <= innerSq;
        if (in1 || in2) {
            if (in1 && in2) {
                final int cmp = Integer.compare(distSq1, distSq2);
                return cmp != 0 ? cmp : Long.compare(key1, key2);
            }
            return in1 ? -1 : 1;
        }
        final double score1 = Math.sqrt(distSq1) - snapshot.bias * (diff1X * snapshot.dirX + diff1Z * snapshot.dirZ);
        final double score2 = Math.sqrt(distSq2) - snapshot.bias * (diff2X * snapshot.dirX + diff2Z * snapshot.dirZ);
        final int scoreCmp = Double.compare(score1, score2);
        if (scoreCmp != 0) {
            return scoreCmp;
        }
        final int distCmp = Integer.compare(distSq1, distSq2);
        return distCmp != 0 ? distCmp : Long.compare(key1, key2);
    }

    public static Settings get() {
        return current;
    }

    public static void register(final @Nullable Settings settings) {
        current = settings != null ? settings : Settings.DISABLED;
    }

    public static void resetDefault() {
        current = Settings.DISABLED;
    }
}
