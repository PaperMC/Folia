package dev.leafcraft.goldleaf.worldgen;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight, lock-free per-stage worldgen profiler capturing cumulative wall/CPU nanoseconds and
 * chunk counts across all {@code EMPTY -> FULL} {@code ChunkStatus} stages and {@code buildTerrain}
 * sub-phases ({@code goldleaf-ADR-017}, {@code goldleaf-ADR-019}).
 *
 * @governedBy goldleaf-ADR-017, goldleaf-ADR-019
 * @trace S-004, S-005
 */
public final class WorldgenStageProfiler {

    private static final Logger LOGGER = LoggerFactory.getLogger("GoldLeaf-StageProfiler");

    public static final int STAGE_STRUCTURE_STARTS = 0;
    public static final int STAGE_STRUCTURE_REFERENCES = 1;
    public static final int STAGE_BIOMES = 2;
    public static final int STAGE_TERRAIN_TOTAL = 3;
    public static final int STAGE_TERRAIN_DO_FILL = 4;
    public static final int STAGE_TERRAIN_BUILD_SURFACE = 5;
    public static final int STAGE_TERRAIN_CARVERS = 6;
    public static final int STAGE_TERRAIN_HEIGHTMAPS = 7;
    public static final int STAGE_FEATURES = 8;
    public static final int STAGE_INITIALIZE_LIGHT = 9;
    public static final int STAGE_LIGHT = 10;
    public static final int STAGE_SPAWN = 11;
    public static final int STAGE_FULL = 12;
    public static final int STAGE_CARVER_MASK = 13;
    public static final int STAGE_CARVER_APPLY = 14;
    public static final int STAGE_FEAT_STEP_BASE = 15; // 15..25 = GenerationStep.Decoration[0..10]
    public static final int STAGE_FEAT_FIX_SNOW = 26;

    public static final String[] STAGE_NAMES = {
        "STRUCTURE_STARTS",
        "STRUCTURE_REFERENCES",
        "BIOMES",
        "TERRAIN_TOTAL",
        "TERRAIN_DO_FILL",
        "TERRAIN_BUILD_SURFACE",
        "TERRAIN_CARVERS",
        "TERRAIN_HEIGHTMAPS",
        "FEATURES",
        "INITIALIZE_LIGHT",
        "LIGHT",
        "SPAWN",
        "FULL",
        "CARVER_MASK",
        "CARVER_APPLY",
        "FEAT_RAW_GEN",
        "FEAT_LAKES",
        "FEAT_LOCAL_MOD",
        "FEAT_UG_STRUCT",
        "FEAT_SURF_STRUCT",
        "FEAT_STRONGHOLD",
        "FEAT_UG_ORES",
        "FEAT_UG_DEC",
        "FEAT_SPRINGS",
        "FEAT_VEGETAL",
        "FEAT_TOP_LAYER",
        "FEAT_FIX_SNOW"
    };

    public static final WorldgenStageProfiler GLOBAL = new WorldgenStageProfiler();

    private final LongAdder[] totalNanos = new LongAdder[STAGE_NAMES.length];
    private final LongAdder[] counts = new LongAdder[STAGE_NAMES.length];

    public WorldgenStageProfiler() {
        for (int i = 0; i < STAGE_NAMES.length; i++) {
            this.totalNanos[i] = new LongAdder();
            this.counts[i] = new LongAdder();
        }
    }

    public void record(final int stageOrdinal, final long elapsedNanos) {
        if (stageOrdinal < 0 || stageOrdinal >= STAGE_NAMES.length || elapsedNanos < 0L) {
            return;
        }
        this.totalNanos[stageOrdinal].add(elapsedNanos);
        this.counts[stageOrdinal].increment();
    }

    public long totalNanos(final int stageOrdinal) {
        return (stageOrdinal >= 0 && stageOrdinal < STAGE_NAMES.length) ? this.totalNanos[stageOrdinal].sum() : 0L;
    }

    public long count(final int stageOrdinal) {
        return (stageOrdinal >= 0 && stageOrdinal < STAGE_NAMES.length) ? this.counts[stageOrdinal].sum() : 0L;
    }

    public boolean hasSamples() {
        for (int i = 0; i < STAGE_NAMES.length; i++) {
            if (this.counts[i].sum() > 0L) {
                return true;
            }
        }
        return false;
    }

    public void reset() {
        for (int i = 0; i < STAGE_NAMES.length; i++) {
            this.totalNanos[i].reset();
            this.counts[i].reset();
        }
    }

    public String formatSummary(final String phase) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("[GoldLeaf-StageProfiler] Summary (").append(phase).append("):");
        for (int i = 0; i < STAGE_NAMES.length; i++) {
            final long c = this.counts[i].sum();
            if (c <= 0L) {
                continue;
            }
            final double totalMs = this.totalNanos[i].sum() / 1_000_000.0;
            final double avgMs = totalMs / c;
            sb.append(String.format(
                Locale.ROOT,
                " %s=%.1fms(%d,avg=%.3fms)",
                STAGE_NAMES[i],
                totalMs,
                c,
                avgMs
            ));
        }
        return sb.toString();
    }

    public void logSummary(final String phase) {
        if (hasSamples()) {
            LOGGER.info("{}", formatSummary(phase));
        }
    }
}
