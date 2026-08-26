package io.papermc.paper.threadedregions;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("Normal")
class FluidTickBudgetTest {
    @Test
    void disabledBudgetAllocatesNothing() {
        final int[] allocations = FluidTickBudget.allocate(
            settings(false, 2_000, 50, 75),
            0L,
            demands(1_000, 1_000)
        );

        assertArrayEquals(new int[] {0, 0}, allocations);
    }

    @Test
    void allocationNeverExceedsProcessOrRegionalDemand() {
        final List<FluidTickBudget.Demand> demands = List.of(
            new FluidTickBudget.Demand(0L, 10, 0L),
            new FluidTickBudget.Demand(1L, 1_000, 20L),
            new FluidTickBudget.Demand(2L, 5_000, Long.MAX_VALUE)
        );
        final int[] allocations = FluidTickBudget.allocate(settings(true, 2_000, 50, 75), 0L, demands);

        assertTrue(sum(allocations) <= 2_000);
        for (int index = 0; index < allocations.length; ++index) {
            assertTrue(allocations[index] >= 0);
            assertTrue(allocations[index] <= demands.get(index).ticks());
        }
    }

    @Test
    void equallyLoadedRegionsReceiveEqualShares() {
        final int[] allocations = FluidTickBudget.allocate(
            settings(true, 2_000, 50, 75),
            0L,
            demands(65_536, 65_536, 65_536, 65_536)
        );

        assertEquals(2_000, sum(allocations));
        final int minimum = Math.min(Math.min(allocations[0], allocations[1]), Math.min(allocations[2], allocations[3]));
        final int maximum = Math.max(Math.max(allocations[0], allocations[1]), Math.max(allocations[2], allocations[3]));
        assertTrue(maximum - minimum <= 1);
    }

    @Test
    void ageOnlyWeightsTheReservedRemainder() {
        final List<FluidTickBudget.Demand> demands = List.of(
            new FluidTickBudget.Demand(0L, 10_000, 0L),
            new FluidTickBudget.Demand(1L, 10_000, 200L)
        );
        final int[] allocations = FluidTickBudget.allocate(settings(true, 2_000, 50, 75), 0L, demands);

        assertEquals(2_000, sum(allocations));
        assertTrue(allocations[1] > allocations[0]);
        // The 75% fair pool plus the floor prevents age from monopolising the epoch.
        assertTrue(allocations[0] >= 800);
    }

    @Test
    void unusedDemandIsNotInvented() {
        final int[] allocations = FluidTickBudget.allocate(
            settings(true, 2_000, 50, 75),
            0L,
            demands(7, 11, 13)
        );

        assertArrayEquals(new int[] {7, 11, 13}, allocations);
    }

    @Test
    void rotatingTieOrderIsFairAcrossEpochs() {
        final int[] totals = new int[3];
        for (long epoch = 0L; epoch < 3L; ++epoch) {
            final int[] allocations = FluidTickBudget.allocate(
                settings(true, 2, 0, 100),
                epoch,
                demands(10, 10, 10)
            );
            for (int index = 0; index < totals.length; ++index) {
                totals[index] += allocations[index];
            }
        }

        assertArrayEquals(new int[] {2, 2, 2}, totals);
    }

    @Test
    void ageWeightSaturatesAtConfiguredMaximum() {
        final List<FluidTickBudget.Demand> demands = List.of(
            new FluidTickBudget.Demand(0L, 10_000, 20L),
            new FluidTickBudget.Demand(1L, 10_000, Long.MAX_VALUE)
        );
        final int[] allocations = FluidTickBudget.allocate(settings(true, 2_000, 0, 0), 0L, demands);

        assertEquals(2_000, sum(allocations));
        assertTrue(allocations[1] > allocations[0]);
    }

    @Test
    void randomizedAllocationsRespectAllBounds() {
        final Random random = new Random(0x5EEDBEEFL);
        for (int iteration = 0; iteration < 10_000; ++iteration) {
            final int processMaximum = random.nextInt(1, 20_001);
            final FluidTickBudget.Settings settings = new FluidTickBudget.Settings(
                true,
                processMaximum,
                random.nextInt(0, 501),
                random.nextInt(1, 101),
                random.nextInt(1, 17),
                random.nextInt(0, 101)
            );
            final int regionCount = random.nextInt(0, 65);
            final List<FluidTickBudget.Demand> demands = new ArrayList<>(regionCount);
            long totalDemand = 0L;
            for (int region = 0; region < regionCount; ++region) {
                final int demand = random.nextInt(0, 65_537);
                final long age = random.nextBoolean() ? random.nextLong(0L, 10_001L) : Long.MAX_VALUE;
                demands.add(new FluidTickBudget.Demand(region, demand, age));
                totalDemand += demand;
            }

            final int[] allocations = FluidTickBudget.allocate(settings, random.nextLong(), demands);
            assertEquals(regionCount, allocations.length);
            assertEquals(Math.min((long)processMaximum, totalDemand), sum(allocations));
            for (int region = 0; region < regionCount; ++region) {
                assertTrue(allocations[region] >= 0);
                assertTrue(allocations[region] <= demands.get(region).ticks());
            }
        }
    }

    private static FluidTickBudget.Settings settings(
        final boolean enabled,
        final int processMaximum,
        final int regionMinimum,
        final int fairSharePercent
    ) {
        return new FluidTickBudget.Settings(enabled, processMaximum, regionMinimum, 20, 8, fairSharePercent);
    }

    private static List<FluidTickBudget.Demand> demands(final int... ticks) {
        final java.util.ArrayList<FluidTickBudget.Demand> result = new java.util.ArrayList<>(ticks.length);
        for (int index = 0; index < ticks.length; ++index) {
            result.add(new FluidTickBudget.Demand(index, ticks[index], 0L));
        }
        return result;
    }

    private static int sum(final int[] values) {
        int result = 0;
        for (final int value : values) {
            result += value;
        }
        return result;
    }
}
