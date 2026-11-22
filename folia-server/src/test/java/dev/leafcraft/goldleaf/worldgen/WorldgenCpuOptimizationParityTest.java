package dev.leafcraft.goldleaf.worldgen;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.condition.ConditionEvaluator;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.condition.NotCondition;
import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity and regression tests for GoldLeaf CPU worldgen optimizations:
 * surface rule constant folding/flattening, Aquifer 12-cell Voronoi band caching,
 * and lazy {@link XoroshiroRandomSource} Gaussian state allocation.
 */
final class WorldgenCpuOptimizationParityTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static MaterialCondition fixedCondition(final ConditionEvaluator evaluator) {
        return new MaterialCondition() {
            @Override
            public MapCodec<? extends MaterialCondition> codec() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ConditionEvaluator compile(final MaterialRuleContext context) {
                return evaluator;
            }
        };
    }

    private static MaterialRule fixedRule(final RuleEvaluator evaluator, final AtomicInteger compileCalls) {
        return new MaterialRule() {
            @Override
            public MapCodec<? extends MaterialRule> codec() {
                throw new UnsupportedOperationException();
            }

            @Override
            public RuleEvaluator compile(final MaterialRuleContext context) {
                compileCalls.incrementAndGet();
                return evaluator;
            }
        };
    }

    @Test
    void xoroshiroRandomSourceDefersGaussianAllocationAndPreservesParity() throws Exception {
        final Field gaussianField = XoroshiroRandomSource.class.getDeclaredField("gaussianSource");
        gaussianField.setAccessible(true);

        final XoroshiroRandomSource rng = new XoroshiroRandomSource(123456789L);
        assertNull(gaussianField.get(rng), "gaussianSource must stay null until nextGaussian() is called");

        // Exercise non-Gaussian calls and setSeed while gaussianSource is still null
        final int i1 = rng.nextInt(100);
        final float f1 = rng.nextFloat();
        final double d1 = rng.nextDouble();
        rng.setSeed(123456789L);
        assertNull(gaussianField.get(rng), "setSeed must not eagerly allocate gaussianSource");
        assertEquals(i1, rng.nextInt(100));
        assertEquals(f1, rng.nextFloat());
        assertEquals(d1, rng.nextDouble());

        // First nextGaussian() initializes lazily and stays deterministic across setSeed
        final double g1 = rng.nextGaussian();
        final double g2 = rng.nextGaussian();
        assertNotNull(gaussianField.get(rng), "gaussianSource must be allocated on first nextGaussian()");

        rng.setSeed(123456789L);
        rng.nextInt(100);
        rng.nextFloat();
        rng.nextDouble();
        assertEquals(g1, rng.nextGaussian());
        assertEquals(g2, rng.nextGaussian());

        // Positional factory instances also avoid Gaussian allocation
        final PositionalRandomFactory positional = rng.forkPositional();
        final RandomSource at = positional.at(-42, 17, 99);
        assertEquals(XoroshiroRandomSource.class, at.getClass());
        at.nextInt(10);
        at.nextFloat();
        assertNull(gaussianField.get(at));
    }

    @Test
    void surfaceRuleConstantFoldingPrunesDeadBranchesAndFlattensSequences() {
        final AtomicInteger deadCompiles = new AtomicInteger();
        final AtomicInteger liveCompiles = new AtomicInteger();
        final AtomicInteger liveCalls = new AtomicInteger();

        final RuleEvaluator liveLeaf = (x, y, z) -> {
            liveCalls.incrementAndGet();
            return null;
        };

        // NotCondition folds ALWAYS_TRUE <-> ALWAYS_FALSE
        assertSame(
            ConditionEvaluator.ALWAYS_FALSE,
            new NotCondition(fixedCondition(ConditionEvaluator.ALWAYS_TRUE)).compile(null)
        );
        assertSame(
            ConditionEvaluator.ALWAYS_TRUE,
            new NotCondition(fixedCondition(ConditionEvaluator.ALWAYS_FALSE)).compile(null)
        );

        // ConditionRule with ALWAYS_FALSE skips thenRun.compile() completely
        final RuleEvaluator pruned = new ConditionRule(
            fixedCondition(ConditionEvaluator.ALWAYS_FALSE),
            fixedRule(liveLeaf, deadCompiles)
        ).compile(null);
        assertSame(RuleEvaluator.NEVER, pruned);
        assertEquals(0, deadCompiles.get(), "Dead branch thenRun must not be compiled");

        // ConditionRule with ALWAYS_TRUE unwraps directly to thenRun
        final RuleEvaluator unwrapped = new ConditionRule(
            fixedCondition(ConditionEvaluator.ALWAYS_TRUE),
            fixedRule(liveLeaf, liveCompiles)
        ).compile(null);
        assertSame(liveLeaf, unwrapped);
        assertEquals(1, liveCompiles.get());

        // Nested SequenceRule with only dead branches collapses to RuleEvaluator.NEVER
        final SequenceRule allDead = new SequenceRule(List.of(
            new ConditionRule(fixedCondition(ConditionEvaluator.ALWAYS_FALSE), fixedRule(liveLeaf, deadCompiles)),
            new SequenceRule(List.of(
                new ConditionRule(
                    new NotCondition(fixedCondition(ConditionEvaluator.ALWAYS_TRUE)),
                    fixedRule(liveLeaf, deadCompiles)
                )
            ))
        ));
        assertSame(RuleEvaluator.NEVER, allDead.compile(null));
        assertEquals(0, deadCompiles.get());

        // Nested SequenceRule with one live leaf collapses directly to that leaf
        final SequenceRule oneLive = new SequenceRule(List.of(
            new ConditionRule(fixedCondition(ConditionEvaluator.ALWAYS_FALSE), fixedRule(liveLeaf, deadCompiles)),
            new SequenceRule(List.of(
                new ConditionRule(fixedCondition(ConditionEvaluator.ALWAYS_TRUE), fixedRule(liveLeaf, liveCompiles)),
                new ConditionRule(fixedCondition(ConditionEvaluator.ALWAYS_FALSE), fixedRule(liveLeaf, deadCompiles))
            ))
        ));
        final RuleEvaluator collapsed = oneLive.compile(null);
        assertSame(liveLeaf, collapsed);
        collapsed.tryApply(1, 2, 3);
        assertEquals(1, liveCalls.get());
    }

    @Test
    void aquiferVoronoiBandCacheMatchesReferenceLoopAcrossDescendingAndRandomQueries() {
        final int minGridX = -2;
        final int minGridY = -8;
        final int minGridZ = -2;
        final int gridSizeX = 5;
        final int gridSizeY = 32;
        final int gridSizeZ = 5;
        final long[] locations = new long[gridSizeX * gridSizeY * gridSizeZ];
        final PositionalRandomFactory factory = new XoroshiroRandomSource(987654321L).forkPositional();

        for (int gy = 0; gy < gridSizeY; gy++) {
            for (int gz = 0; gz < gridSizeZ; gz++) {
                for (int gx = 0; gx < gridSizeX; gx++) {
                    final int idx = (gy * gridSizeZ + gz) * gridSizeX + gx;
                    final int sgx = minGridX + gx;
                    final int sgy = minGridY + gy;
                    final int sgz = minGridZ + gz;
                    final RandomSource rng = factory.at(sgx, sgy, sgz);
                    locations[idx] = BlockPos.asLong(
                        (sgx << 4) + rng.nextInt(10),
                        sgy * 12 + rng.nextInt(9),
                        (sgz << 4) + rng.nextInt(10)
                    );
                }
            }
        }

        // Cached state mirroring Aquifer.NoiseBasedAquifer
        int lastBlockX = Integer.MIN_VALUE;
        int lastAnchorY = Integer.MIN_VALUE;
        int lastBlockZ = Integer.MIN_VALUE;
        final int[] cachedIdx = new int[12];
        final int[] cachedY = new int[12];
        final int[] cachedDistXZ = new int[12];

        // 1) Descending column sweep (same order as SectionBatchedTerrainFill)
        for (int bx = -16; bx < 16; bx += 5) {
            for (int bz = -16; bz < 16; bz += 5) {
                for (int by = 63; by >= -64; by--) {
                    final int[] ref = referenceVoronoi(locations, minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ, bx, by, bz);

                    final int xAnchor = (bx - 5) >> 4;
                    final int yAnchor = Math.floorDiv(by + 1, 12);
                    final int zAnchor = (bz - 5) >> 4;
                    final int[] fast;
                    if (bx == lastBlockX && yAnchor == lastAnchorY && bz == lastBlockZ) {
                        fast = fastVoronoiHit(cachedIdx, cachedY, cachedDistXZ, by);
                    } else {
                        lastBlockX = bx;
                        lastAnchorY = yAnchor;
                        lastBlockZ = bz;
                        fast = fastVoronoiFill(locations, minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ, xAnchor, yAnchor, zAnchor, bx, by, bz, cachedIdx, cachedY, cachedDistXZ);
                    }
                    assertArrayEquals(ref, fast, "Mismatch at (" + bx + "," + by + "," + bz + ")");
                }
            }
        }

        // 2) Random out-of-order queries (verifies cache invalidation across coordinates)
        final RandomSource probeRng = new XoroshiroRandomSource(42L);
        for (int i = 0; i < 500; i++) {
            final int bx = probeRng.nextInt(32) - 16;
            final int by = probeRng.nextInt(128) - 64;
            final int bz = probeRng.nextInt(32) - 16;
            final int[] ref = referenceVoronoi(locations, minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ, bx, by, bz);

            final int xAnchor = (bx - 5) >> 4;
            final int yAnchor = Math.floorDiv(by + 1, 12);
            final int zAnchor = (bz - 5) >> 4;
            final int[] fast;
            if (bx == lastBlockX && yAnchor == lastAnchorY && bz == lastBlockZ) {
                fast = fastVoronoiHit(cachedIdx, cachedY, cachedDistXZ, by);
            } else {
                lastBlockX = bx;
                lastAnchorY = yAnchor;
                lastBlockZ = bz;
                fast = fastVoronoiFill(locations, minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ, xAnchor, yAnchor, zAnchor, bx, by, bz, cachedIdx, cachedY, cachedDistXZ);
            }
            assertArrayEquals(ref, fast, "Random probe mismatch at (" + bx + "," + by + "," + bz + ")");
        }
    }

    private static int[] referenceVoronoi(
        final long[] locations, final int minGX, final int minGY, final int minGZ,
        final int sizeX, final int sizeZ, final int bx, final int by, final int bz
    ) {
        final int xAnchor = (bx - 5) >> 4;
        final int yAnchor = Math.floorDiv(by + 1, 12);
        final int zAnchor = (bz - 5) >> 4;
        int d1 = Integer.MAX_VALUE, d2 = Integer.MAX_VALUE, d3 = Integer.MAX_VALUE, d4 = Integer.MAX_VALUE;
        int i1 = 0, i2 = 0, i3 = 0, i4 = 0;
        for (int x1 = 0; x1 <= 1; x1++) {
            for (int y1 = -1; y1 <= 1; y1++) {
                for (int z1 = 0; z1 <= 1; z1++) {
                    final int idx = ((yAnchor + y1 - minGY) * sizeZ + (zAnchor + z1 - minGZ)) * sizeX + (xAnchor + x1 - minGX);
                    final long loc = locations[idx];
                    final int dx = BlockPos.getX(loc) - bx;
                    final int dy = BlockPos.getY(loc) - by;
                    final int dz = BlockPos.getZ(loc) - bz;
                    final int dist = dx * dx + dy * dy + dz * dz;
                    if (d1 >= dist) {
                        i4 = i3; i3 = i2; i2 = i1; i1 = idx;
                        d4 = d3; d3 = d2; d2 = d1; d1 = dist;
                    } else if (d2 >= dist) {
                        i4 = i3; i3 = i2; i2 = idx;
                        d4 = d3; d3 = d2; d2 = dist;
                    } else if (d3 >= dist) {
                        i4 = i3; i3 = idx;
                        d4 = d3; d3 = dist;
                    } else if (d4 >= dist) {
                        i4 = idx;
                        d4 = dist;
                    }
                }
            }
        }
        return new int[]{i1, i2, i3, i4, d1, d2, d3, d4};
    }

    private static int[] fastVoronoiHit(final int[] idxArr, final int[] yArr, final int[] xzArr, final int by) {
        int d1 = Integer.MAX_VALUE, d2 = Integer.MAX_VALUE, d3 = Integer.MAX_VALUE, d4 = Integer.MAX_VALUE;
        int i1 = 0, i2 = 0, i3 = 0, i4 = 0;
        for (int i = 0; i < 12; i++) {
            final int dy = yArr[i] - by;
            final int dist = xzArr[i] + dy * dy;
            final int idx = idxArr[i];
            if (d1 >= dist) {
                i4 = i3; i3 = i2; i2 = i1; i1 = idx;
                d4 = d3; d3 = d2; d2 = d1; d1 = dist;
            } else if (d2 >= dist) {
                i4 = i3; i3 = i2; i2 = idx;
                d4 = d3; d3 = d2; d2 = dist;
            } else if (d3 >= dist) {
                i4 = i3; i3 = idx;
                d4 = d3; d3 = dist;
            } else if (d4 >= dist) {
                i4 = idx;
                d4 = dist;
            }
        }
        return new int[]{i1, i2, i3, i4, d1, d2, d3, d4};
    }

    private static int[] fastVoronoiFill(
        final long[] locations, final int minGX, final int minGY, final int minGZ,
        final int sizeX, final int sizeZ,
        final int xAnchor, final int yAnchor, final int zAnchor,
        final int bx, final int by, final int bz,
        final int[] idxArr, final int[] yArr, final int[] xzArr
    ) {
        int d1 = Integer.MAX_VALUE, d2 = Integer.MAX_VALUE, d3 = Integer.MAX_VALUE, d4 = Integer.MAX_VALUE;
        int i1 = 0, i2 = 0, i3 = 0, i4 = 0;
        int slot = 0;
        for (int x1 = 0; x1 <= 1; x1++) {
            for (int y1 = -1; y1 <= 1; y1++) {
                for (int z1 = 0; z1 <= 1; z1++) {
                    final int idx = ((yAnchor + y1 - minGY) * sizeZ + (zAnchor + z1 - minGZ)) * sizeX + (xAnchor + x1 - minGX);
                    final long loc = locations[idx];
                    final int dx = BlockPos.getX(loc) - bx;
                    final int cellY = BlockPos.getY(loc);
                    final int dz = BlockPos.getZ(loc) - bz;
                    final int distXZ = dx * dx + dz * dz;
                    idxArr[slot] = idx;
                    yArr[slot] = cellY;
                    xzArr[slot] = distXZ;
                    slot++;
                    final int dy = cellY - by;
                    final int dist = distXZ + dy * dy;
                    if (d1 >= dist) {
                        i4 = i3; i3 = i2; i2 = i1; i1 = idx;
                        d4 = d3; d3 = d2; d2 = d1; d1 = dist;
                    } else if (d2 >= dist) {
                        i4 = i3; i3 = i2; i2 = idx;
                        d4 = d3; d3 = d2; d2 = dist;
                    } else if (d3 >= dist) {
                        i4 = i3; i3 = idx;
                        d4 = d3; d3 = dist;
                    } else if (d4 >= dist) {
                        i4 = idx;
                        d4 = dist;
                    }
                }
            }
        }
        return new int[]{i1, i2, i3, i4, d1, d2, d3, d4};
    }

    @Test
    void climateRTreeQuantizedMatchesVanillaAndBruteForce() {
        final Random rng = new Random(0xC11AA7EL);
        final List<Pair<Climate.ParameterPoint, String>> entries = new ArrayList<>();
        // Build 80 7D parameter boxes (multi-level R-tree with > 19 children per node)
        for (int i = 0; i < 80; i++) {
            final float t0 = -1.0f + rng.nextFloat() * 1.6f;
            final float h0 = -1.0f + rng.nextFloat() * 1.6f;
            final float c0 = -1.0f + rng.nextFloat() * 1.6f;
            final float e0 = -1.0f + rng.nextFloat() * 1.6f;
            final float d0 = -1.0f + rng.nextFloat() * 1.6f;
            final float w0 = -1.0f + rng.nextFloat() * 1.6f;
            final float offset = (i % 4 == 0) ? 0.0f : rng.nextFloat() * 0.25f;
            final Climate.ParameterPoint point = Climate.parameters(
                Climate.Parameter.span(t0, t0 + 0.35f),
                Climate.Parameter.span(h0, h0 + 0.35f),
                Climate.Parameter.span(c0, c0 + 0.35f),
                Climate.Parameter.span(e0, e0 + 0.35f),
                Climate.Parameter.span(d0, d0 + 0.35f),
                Climate.Parameter.span(w0, w0 + 0.35f),
                offset
            );
            entries.add(Pair.of(point, "biome_" + i));
        }

        final Climate.ParameterList<String> list = new Climate.ParameterList<>(entries);

        // 1. Random probes + repeated in-box probes (exercises prevDist == 0L O(1) fast-path & early-exit pruning)
        for (int q = 0; q < 500; q++) {
            final float t = -1.2f + rng.nextFloat() * 2.4f;
            final float h = -1.2f + rng.nextFloat() * 2.4f;
            final float c = -1.2f + rng.nextFloat() * 2.4f;
            final float e = -1.2f + rng.nextFloat() * 2.4f;
            final float d = -1.2f + rng.nextFloat() * 2.4f;
            final float w = -1.2f + rng.nextFloat() * 2.4f;

            final Climate.TargetPoint target = Climate.target(t, h, c, e, d, w);
            final String expected = list.findValueIndex(target);
            final String actualQuantized = list.findValueQuantized(
                target.temperature(),
                target.humidity(),
                target.continentalness(),
                target.erosion(),
                target.depth(),
                target.weirdness()
            );
            final String actualFindValue = list.findValue(target);

            // Both fast paths must match each other and achieve the exact minimum fitness distance
            assertEquals(actualFindValue, actualQuantized);
            final long expectedFitness = fitnessOf(entries, expected, target);
            final long actualFitness = fitnessOf(entries, actualQuantized, target);
            assertEquals(expectedFitness, actualFitness, "Fitness mismatch at probe " + q);
        }

        // 2. Repeated exact-center hits on a zero-offset box (exercises prevDist == 0L short-circuit)
        final Pair<Climate.ParameterPoint, String> zeroOffsetEntry = entries.get(0);
        final Climate.ParameterPoint p0 = zeroOffsetEntry.getFirst();
        final long mt = (p0.temperature().min() + p0.temperature().max()) / 2L;
        final long mh = (p0.humidity().min() + p0.humidity().max()) / 2L;
        final long mc = (p0.continentalness().min() + p0.continentalness().max()) / 2L;
        final long me = (p0.erosion().min() + p0.erosion().max()) / 2L;
        final long md = (p0.depth().min() + p0.depth().max()) / 2L;
        final long mw = (p0.weirdness().min() + p0.weirdness().max()) / 2L;
        final String firstHit = list.findValueQuantized(mt, mh, mc, me, md, mw);
        final String secondHit = list.findValueQuantized(mt, mh, mc, me, md, mw);
        assertEquals(firstHit, secondHit);
        assertEquals(0L, fitnessOf(entries, secondHit, new Climate.TargetPoint(mt, mh, mc, me, md, mw)));
    }

    private static long fitnessOf(
        final List<Pair<Climate.ParameterPoint, String>> entries,
        final String value,
        final Climate.TargetPoint target
    ) {
        for (final Pair<Climate.ParameterPoint, String> entry : entries) {
            if (entry.getSecond().equals(value)) {
                return entry.getFirst().fitness(target);
            }
        }
        throw new AssertionError("Unknown biome value: " + value);
    }

    @Test
    void oreFeatureInteriorAndBoundaryNeighborFastPathMatchesVanilla() {
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState air = Blocks.AIR.defaultBlockState();

        // Interior voxel (5, 7, 9): reads directly from LevelChunkSection, zero BulkSectionAccess calls
        final LevelChunkSection section = Mockito.mock(LevelChunkSection.class);
        final BulkSectionAccess bulk = Mockito.mock(BulkSectionAccess.class);
        Mockito.when(section.getBlockState(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenReturn(stone);

        final BlockPos orePos = new BlockPos(21, 7, 25);
        final BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();

        assertFalse(AbstractOreFeature.isAdjacentToAirFast(section, 5, 7, 9, bulk, orePos, neighborPos));
        Mockito.verifyNoInteractions(bulk);

        for (final Direction dir : Direction.values()) {
            Mockito.reset(section, bulk);
            Mockito.when(section.getBlockState(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenReturn(stone);
            Mockito.when(section.getBlockState(5 + dir.getStepX(), 7 + dir.getStepY(), 9 + dir.getStepZ())).thenReturn(air);

            assertTrue(
                AbstractOreFeature.isAdjacentToAirFast(section, 5, 7, 9, bulk, orePos, neighborPos),
                "Interior fast-path must detect air in direction " + dir
            );
            Mockito.verifyNoInteractions(bulk);
        }

        // Boundary voxel (rx == 0): must delegate to BulkSectionAccess using reusable neighborPos
        Mockito.reset(section, bulk);
        final BlockPos boundaryPos = new BlockPos(16, 7, 25);
        Mockito.when(bulk.getBlockState(Mockito.any(BlockPos.class))).thenAnswer(inv -> {
            final BlockPos p = inv.getArgument(0);
            return (p.getX() == 15 && p.getY() == 7 && p.getZ() == 25) ? air : stone;
        });
        assertTrue(AbstractOreFeature.isAdjacentToAirFast(section, 0, 7, 9, bulk, boundaryPos, neighborPos));
        Mockito.verifyNoInteractions(section);
    }
}
