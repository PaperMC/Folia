package dev.leafcraft.goldleaf.telemetry;

import ca.spottedleaf.moonrise.common.util.TickThread;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.Objects;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Bitmask flags and exemption checks for pre-tick / pre-event chunk execution gating
 * ({@code goldleaf-ADR-006} §1.3).
 *
 * <p>When {@code executionMask == 0}, vanilla execution proceeds identically. Specialist hopper
 * topologies ({@code REQ-SLEUTH-GOV-SPECIALIST}) and moving/slime/honey piston contraptions
 * ({@code REQ-SLEUTH-GOV-PISTON}) bypass suppression automatically via {@code exemptionFlags}.</p>
 */
public final class ChunkExecutionMask {

    public static final int NONE = 0;
    public static final int SUPPRESS_REDSTONE = 1 << 0;
    public static final int SUPPRESS_HOPPERS = 1 << 1;
    public static final int SUPPRESS_PISTONS = 1 << 2;
    public static final int DAMPEN_ENTITY_AI = 1 << 3;
    public static final int VIRTUALIZED_BLACKBOX = 1 << 4;

    public static final int ALL_VALID_BITS =
        SUPPRESS_REDSTONE | SUPPRESS_HOPPERS | SUPPRESS_PISTONS | DAMPEN_ENTITY_AI | VIRTUALIZED_BLACKBOX;

    /** Exemption flag for specialist hopper topologies (furnace, crafter, brewing, storage, rail-cart). */
    public static final byte EXEMPT_SPECIALIST = 1 << 0;
    /** Exemption flag for moving / slime / honey piston contraptions. */
    public static final byte EXEMPT_PISTON = 1 << 1;

    private ChunkExecutionMask() {
    }

    /**
     * Pure bitmask predicate evaluating whether {@code flag} is suppressed given {@code executionMask}
     * and {@code exemptionFlags}.
     */
    public static boolean isSuppressed(final int executionMask, final byte exemptionFlags, final int flag) {
        if (executionMask == NONE) {
            return false;
        }
        if ((executionMask & (flag | VIRTUALIZED_BLACKBOX)) == 0) {
            return false;
        }
        if (flag == SUPPRESS_HOPPERS && (exemptionFlags & EXEMPT_SPECIALIST) != 0) {
            return false;
        }
        if (flag == SUPPRESS_PISTONS && (exemptionFlags & EXEMPT_PISTON) != 0) {
            return false;
        }
        return true;
    }

    /**
     * Fast-path hot-loop test on a {@link LevelChunk}. Single branch when mask is {@code 0};
     * checks monotonic {@link ChunkTickTelemetry#currentMaskEpoch()} so unregistered/quarantined
     * consumers never leave stale suppression bits active ({@code [DSN-1]}).
     */
    public static boolean isSuppressed(final LevelChunk chunk, final int flag) {
        final int mask = chunk.paper$getExecutionMask();
        if (mask == NONE) {
            return false;
        }
        if (chunk.paper$getMaskEpoch() != ChunkTickTelemetry.currentMaskEpoch()) {
            return false;
        }
        return isSuppressed(mask, chunk.paper$resolveExemptionFlags(), flag);
    }

    /**
     * Mutates {@code chunk}'s execution mask. Enforces {@code diagnostics.chunk-telemetry-spi}
     * gating and owning-region execution ({@code F-001}..{@code F-003}).
     */
    public static boolean setMask(final LevelChunk chunk, final int mask) {
        Objects.requireNonNull(chunk, "chunk");
        if (!GoldLeafConfig.get().diagnostics.chunkTelemetrySpi) {
            return false;
        }
        TickThread.ensureTickThread(chunk.getLevel(), chunk.getPos(), "Cannot mutate ChunkExecutionMask off owning region thread");
        chunk.paper$setMaskEpoch(ChunkTickTelemetry.currentMaskEpoch());
        chunk.paper$setExecutionMask(mask & ALL_VALID_BITS);
        return true;
    }

    /**
     * Clears {@code chunk}'s execution mask back to vanilla {@code 0} on the owning region thread.
     */
    public static void clearMask(final LevelChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        TickThread.ensureTickThread(chunk.getLevel(), chunk.getPos(), "Cannot clear ChunkExecutionMask off owning region thread");
        chunk.paper$setExecutionMask(NONE);
        chunk.paper$setMaskEpoch(0L);
    }
}
