package dev.leafcraft.goldleaf.bot;

import java.util.Objects;

/**
 * Minimal, composable action primitives for region-safe synthetic players
 * ({@code goldleaf-ADR-015} §1.3).
 */
public record SyntheticPlayerAction(
    Kind kind,
    float yaw,
    float pitch,
    double deltaX,
    double deltaY,
    double deltaZ,
    int intervalTicks,
    boolean continuous
) {
    public enum Kind {
        LOOK,
        MOVE,
        ATTACK,
        USE_ITEM,
        SNEAK,
        SPRINT,
        DROP_STACK
    }

    public SyntheticPlayerAction {
        Objects.requireNonNull(kind, "kind");
        if (intervalTicks < 0) {
            throw new IllegalArgumentException("intervalTicks must be >= 0, got " + intervalTicks);
        }
    }

    public static SyntheticPlayerAction look(final float yaw, final float pitch) {
        return new SyntheticPlayerAction(Kind.LOOK, yaw, pitch, 0.0, 0.0, 0.0, 0, false);
    }

    public static SyntheticPlayerAction move(final double dx, final double dy, final double dz) {
        return new SyntheticPlayerAction(Kind.MOVE, 0.0f, 0.0f, dx, dy, dz, 0, false);
    }

    public static SyntheticPlayerAction attack(final int intervalTicks, final boolean continuous) {
        return new SyntheticPlayerAction(Kind.ATTACK, 0.0f, 0.0f, 0.0, 0.0, 0.0, intervalTicks, continuous);
    }

    public static SyntheticPlayerAction useItem(final int intervalTicks, final boolean continuous) {
        return new SyntheticPlayerAction(Kind.USE_ITEM, 0.0f, 0.0f, 0.0, 0.0, 0.0, intervalTicks, continuous);
    }

    public static SyntheticPlayerAction sneak(final boolean continuous) {
        return new SyntheticPlayerAction(Kind.SNEAK, 0.0f, 0.0f, 0.0, 0.0, 0.0, 0, continuous);
    }

    public static SyntheticPlayerAction sprint(final boolean continuous) {
        return new SyntheticPlayerAction(Kind.SPRINT, 0.0f, 0.0f, 0.0, 0.0, 0.0, 0, continuous);
    }

    public static SyntheticPlayerAction dropStack() {
        return new SyntheticPlayerAction(Kind.DROP_STACK, 0.0f, 0.0f, 0.0, 0.0, 0.0, 0, false);
    }
}
