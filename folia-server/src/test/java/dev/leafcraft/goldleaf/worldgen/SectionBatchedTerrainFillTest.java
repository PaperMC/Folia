package dev.leafcraft.goldleaf.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SectionBatchedTerrainFillTest {

    private record WriteOp(
        String sectionId,
        int x,
        int localY,
        int z,
        int blockX,
        int blockY,
        int blockZ,
        String state
    ) {
    }

    private static List<WriteOp> runVanillaReference(
        final DensityVolume volume,
        final float[] densities,
        final int minWorldSectionCoord,
        final SectionBatchedTerrainFill.SectionLookup<String> sectionLookup,
        final SectionBatchedTerrainFill.SubstanceResolver<String> resolver,
        final String defaultState,
        final String airState
    ) {
        final List<WriteOp> ops = new ArrayList<>();
        for (int z = 0; z < volume.sizeZ(); z++) {
            final int blockZ = volume.blockZ(z);
            for (int x = 0; x < volume.sizeX(); x++) {
                final int blockX = volume.blockX(x);
                for (int y = volume.sizeY() - 1; y >= 0; y--) {
                    final int blockY = volume.blockY(y);
                    final int sectionIndex = SectionPos.blockToSectionCoord(blockY) - minWorldSectionCoord;
                    final String section = sectionLookup.getSection(sectionIndex);
                    final float density = densities[volume.indexUnchecked(x, y, z)];
                    String state = resolver.resolve(blockX, blockY, blockZ, density);
                    if (state == null) {
                        state = defaultState;
                    }
                    if (state != airState) {
                        ops.add(new WriteOp(section, x, SectionPos.sectionRelative(blockY), z, blockX, blockY, blockZ, state));
                    }
                }
            }
        }
        return ops;
    }

    @Test
    void matchesVanillaOrderAndHoistsSectionLookupsForOverworldVolume() {
        final DensityVolume volume = new DensityVolume(16, 384, 16, 32, -64, -48);
        final float[] densities = new float[volume.size()];
        for (int i = 0; i < densities.length; i++) {
            densities[i] = ((i * 31) % 100) - 50.0f;
        }
        final int minWorldSectionCoord = -4;
        final String air = "AIR";
        final String stone = "STONE";
        final String water = "WATER";

        final SectionBatchedTerrainFill.SubstanceResolver<String> resolver = (bx, by, bz, d) -> {
            if (d > 10.0f) {
                return null; // triggers defaultState
            }
            if (d > 0.0f) {
                return water;
            }
            return air;
        };

        final AtomicInteger vanillaLookups = new AtomicInteger();
        final List<WriteOp> expected = runVanillaReference(
            volume,
            densities,
            minWorldSectionCoord,
            idx -> {
                vanillaLookups.incrementAndGet();
                return "sec-" + idx;
            },
            resolver,
            stone,
            air
        );

        final AtomicInteger batchedLookups = new AtomicInteger();
        final List<WriteOp> actual = new ArrayList<>(expected.size());
        SectionBatchedTerrainFill.fillSections(
            volume,
            densities,
            minWorldSectionCoord,
            idx -> {
                batchedLookups.incrementAndGet();
                return "sec-" + idx;
            },
            resolver,
            stone,
            air,
            (sec, x, localY, z, bx, by, bz, state) ->
                actual.add(new WriteOp(sec, x, localY, z, bx, by, bz, state))
        );

        assertEquals(98_304, vanillaLookups.get());
        assertEquals(24, batchedLookups.get());
        assertEquals(expected, actual);
    }

    @Test
    void matchesVanillaForNonSectionAlignedHeightRange() {
        final DensityVolume volume = new DensityVolume(4, 20, 4, 0, -60, 0);
        final float[] densities = new float[volume.size()];
        for (int i = 0; i < densities.length; i++) {
            densities[i] = (i % 3 == 0) ? 1.0f : -1.0f;
        }
        final int minWorldSectionCoord = -4;
        final String air = "AIR";
        final String stone = "STONE";

        final SectionBatchedTerrainFill.SubstanceResolver<String> resolver = (bx, by, bz, d) -> d > 0.0f ? null : air;

        final List<WriteOp> expected = runVanillaReference(
            volume,
            densities,
            minWorldSectionCoord,
            idx -> "sec-" + idx,
            resolver,
            stone,
            air
        );

        final AtomicInteger batchedLookups = new AtomicInteger();
        final List<WriteOp> actual = new ArrayList<>();
        SectionBatchedTerrainFill.fillSections(
            volume,
            densities,
            minWorldSectionCoord,
            idx -> {
                batchedLookups.incrementAndGet();
                return "sec-" + idx;
            },
            resolver,
            stone,
            air,
            (sec, x, localY, z, bx, by, bz, state) ->
                actual.add(new WriteOp(sec, x, localY, z, bx, by, bz, state))
        );

        assertEquals(2, batchedLookups.get());
        assertEquals(expected, actual);
    }

    @Test
    void matchesVanillaForNonUnitStepY() {
        final DensityVolume volume = new DensityVolume(2, 10, 2, 0, -16, 0, 1, 2, 1);
        final float[] densities = new float[volume.size()];
        for (int i = 0; i < densities.length; i++) {
            densities[i] = 1.0f;
        }
        final int minWorldSectionCoord = -4;
        final String air = "AIR";
        final String stone = "STONE";
        final SectionBatchedTerrainFill.SubstanceResolver<String> resolver = (bx, by, bz, d) -> stone;

        final List<WriteOp> expected = runVanillaReference(
            volume,
            densities,
            minWorldSectionCoord,
            idx -> "sec-" + idx,
            resolver,
            stone,
            air
        );

        final List<WriteOp> actual = new ArrayList<>();
        SectionBatchedTerrainFill.fillSections(
            volume,
            densities,
            minWorldSectionCoord,
            idx -> "sec-" + idx,
            resolver,
            stone,
            air,
            (sec, x, localY, z, bx, by, bz, state) ->
                actual.add(new WriteOp(sec, x, localY, z, bx, by, bz, state))
        );

        assertEquals(expected, actual);
    }

    @Test
    void failsClosedOnUndersizedDensityBuffer() {
        final DensityVolume volume = new DensityVolume(4, 16, 4, 0, 0, 0);
        assertThrows(
            IllegalArgumentException.class,
            () -> SectionBatchedTerrainFill.fillSections(
                volume,
                new float[volume.size() - 1],
                0,
                idx -> "sec-" + idx,
                (bx, by, bz, d) -> "STONE",
                "STONE",
                "AIR",
                (sec, x, localY, z, bx, by, bz, state) -> {
                }
            )
        );
    }
}
