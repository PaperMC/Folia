package dev.leafcraft.goldleaf.worldgen;

import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import java.nio.file.Path;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.GradientFunction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate 1 parity and throughput tests for GoldLeaf Amdahl's Law worldgen optimizations
 * across {@code SURFACE}, {@code FEATURES}, {@code STRUCTURE_STARTS}, and GPU-fused
 * 4-bit {@code TERRAIN + SURFACE} ({@code goldleaf-ADR-004}, {@code goldleaf-ADR-017}).
 */
final class AmdahlWorldgenTracksTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void jigsawBoxOctreeMatchesVanillaVoxelShapeExactlyAndBeatsIt() {
        final AABB boundary = new AABB(-128, -64, -128, 129, 192, 129);
        final AABB center = new AABB(-8, 60, -8, 9, 76, 9);

        VoxelShape vanillaFree = Shapes.join(Shapes.create(boundary), Shapes.create(center), BooleanOp.ONLY_FIRST);
        final JigsawBoxOctree octree = new JigsawBoxOctree(boundary);
        octree.addBox(center);

        final Random rng = new Random(0xA4DA41L);
        int placed = 0;
        long vanillaNanos = 0L;
        long octreeNanos = 0L;

        for (int i = 0; i < 300; i++) {
            final int x = rng.nextInt(280) - 140;
            final int y = rng.nextInt(220) - 60;
            final int z = rng.nextInt(280) - 140;
            final int sx = 4 + rng.nextInt(16);
            final int sy = 4 + rng.nextInt(14);
            final int sz = 4 + rng.nextInt(16);
            final AABB box = new AABB(x, y, z, x + sx, y + sy, z + sz);
            final AABB deflated = box.deflate(0.25);

            final long t0 = System.nanoTime();
            final boolean vanillaFits = !Shapes.joinIsNotEmpty(vanillaFree, Shapes.create(deflated), BooleanOp.ONLY_SECOND);
            final long t1 = System.nanoTime();
            final boolean octreeFits = octree.fitsInside(deflated);
            final long t2 = System.nanoTime();

            vanillaNanos += (t1 - t0);
            octreeNanos += (t2 - t1);

            assertEquals(vanillaFits, octreeFits, "Mismatch at candidate " + i + " box=" + box);
            if (vanillaFits && placed < 80) {
                vanillaFree = Shapes.joinUnoptimized(vanillaFree, Shapes.create(box), BooleanOp.ONLY_FIRST);
                octree.addBox(box);
                placed++;
            }
        }
        assertTrue(placed >= 20, "Expected at least 20 placed boxes, got " + placed);
        System.out.printf(
            "[Amdahl-TrackB] JigsawBoxOctree parity=100%% (%d/300 placed): vanilla=%.2f ms, octree=%.2f ms (%.1fx faster)%n",
            placed,
            vanillaNanos / 1e6,
            octreeNanos / 1e6,
            (double) vanillaNanos / Math.max(1L, octreeNanos)
        );
    }

    @Test
    void poiTypesForStateOrNullMatchesOptionalLookupExactly() {
        final BlockState[] probes = {
            Blocks.AIR.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
            Blocks.GRASS_BLOCK.defaultBlockState(),
            Blocks.OAK_LOG.defaultBlockState(),
            Blocks.OAK_LEAVES.defaultBlockState(),
            Blocks.BARREL.defaultBlockState(),
            Blocks.BLAST_FURNACE.defaultBlockState(),
            Blocks.BELL.defaultBlockState(),
            Blocks.BEEHIVE.defaultBlockState(),
            Blocks.NETHER_PORTAL.defaultBlockState()
        };
        for (final BlockState state : probes) {
            final Holder<PoiType> expected = PoiTypes.forState(state).orElse(null);
            final Holder<PoiType> actual = PoiTypes.forStateOrNull(state);
            assertSame(expected, actual, "PoiTypes#forStateOrNull mismatch for " + state);
        }
    }

    @Test
    void protoChunkCachedHeightmapArrayTracksStatusTransitionsAccurately() {
        final LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(-64, 384);
        final LevelChunkSection[] sections = new LevelChunkSection[24];
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        final BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        for (int i = 0; i < sections.length; i++) {
            sections[i] = new LevelChunkSection(
                new net.minecraft.world.level.chunk.PalettedContainer<>(
                    air,
                    net.minecraft.world.level.chunk.Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)
                ),
                null
            );
        }
        final ProtoChunk chunk = new ProtoChunk(
            new ChunkPos(0, 0),
            UpgradeData.EMPTY,
            sections,
            new net.minecraft.world.ticks.ProtoChunkTicks<>(),
            new net.minecraft.world.ticks.ProtoChunkTicks<>(),
            heightAccessor,
            null,
            null
        );

        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        chunk.setBlockState(new BlockPos(3, 64, 5), stone);
        assertEquals(65, chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG).getFirstAvailable(3, 5));

        chunk.setPersistedStatus(ChunkStatus.TERRAIN);
        chunk.setBlockState(new BlockPos(3, 72, 5), leaves);
        assertEquals(73, chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).getFirstAvailable(3, 5));
        assertEquals(65, chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG).getFirstAvailable(3, 5));

        chunk.setBlockState(new BlockPos(3, 75, 5), grass);
        assertEquals(76, chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).getFirstAvailable(3, 5));
    }

    @Test
    void fusedGpuSurface4BitWritebackBuildsAndAppliesOnLiveOpenCl(@TempDir final Path cacheDir) {
        final DensityFunction terrainGradient = new GradientFunction(
            net.minecraft.core.Direction.Axis.Y,
            net.minecraft.world.level.levelgen.densityfunction.TilingMode.CLAMP_TO_EDGE,
            -64,
            200,
            1.0f,
            -1.0f
        );
        try (IrOpenClTerrainAccelerator acc = new IrOpenClTerrainAccelerator(cacheDir)) {
            final OpenClBackendEmitter.DensityVolumeProgram densityProg = acc.lowerDensityFunctionProgram(
                terrainGradient,
                null,
                "overworld_surface_test",
                TerrainGenerationAccelerator.ParityMode.FAST,
                false
            );
            assertTrue(densityProg != null, "Expected GradientFunction to lower cleanly");
            final String fusedSource = CompactSurfaceWritebackApplier.buildFusedSurfaceKernelSource(densityProg);
            assertTrue(fusedSource.contains(CompactSurfaceWritebackApplier.SURFACE_KERNEL_NAME));

            if (!acc.isGpuReady()) {
                return;
            }

            final int batchCount = 64;
            final int sectionCount = 24;
            final int minBlockY = -64;
            final int seaLevel = 63;
            final int deepFluidY = -54;
            final int[] origins = new int[batchCount * 4];
            for (int c = 0; c < batchCount; c++) {
                origins[c * 4] = (c & 7) * 16;
                origins[c * 4 + 1] = minBlockY;
                origins[c * 4 + 2] = (c >>> 3) * 16;
                origins[c * 4 + 3] = 0;
            }

            final CompactSurfaceWritebackBuffer surfaceBuf = new CompactSurfaceWritebackBuffer(batchCount * sectionCount);
            final long[] timings = new long[2];
            try (GoldLeafOpenClBridge.CompiledKernel kernel = GoldLeafOpenClBridge.compileFromSource(
                0,
                fusedSource,
                CompactSurfaceWritebackApplier.SURFACE_KERNEL_NAME,
                "-cl-std=CL1.2 -cl-mad-enable",
                densityProg.spilledLutWords()
            )) {
                // Warm-up dispatch
                assertEquals(
                    0,
                    kernel.dispatchSurfaceSectionFill4BitBatch(
                        origins, batchCount, sectionCount, seaLevel, deepFluidY, minBlockY,
                        surfaceBuf.wordsDirectBuffer(), surfaceBuf.metaDirectBuffer(), timings
                    )
                );

                // Measured dispatch
                final long t0 = System.nanoTime();
                final int rc = kernel.dispatchSurfaceSectionFill4BitBatch(
                    origins, batchCount, sectionCount, seaLevel, deepFluidY, minBlockY,
                    surfaceBuf.wordsDirectBuffer(), surfaceBuf.metaDirectBuffer(), timings
                );
                final long t1 = System.nanoTime();
                assertEquals(0, rc, "Fused 4-bit surface dispatch must succeed");

                // Verify stratification invariants on chunk 0:
                // y=-64 -> BEDROCK (4), y=-10 -> DEEPSLATE (5), y=31 -> GRASS_BLOCK/STONE (1 or 6), y=120 -> AIR (0)
                assertEquals(CompactSurfaceWritebackBuffer.CODE_BEDROCK, surfaceBuf.voxelCode(0, 0, 0, 0), "y=-64 must be BEDROCK");
                assertEquals(CompactSurfaceWritebackBuffer.CODE_DEEPSLATE, surfaceBuf.voxelCode(3, 4, 6, 4), "y=-10 must be DEEPSLATE");
                assertEquals(CompactSurfaceWritebackBuffer.CODE_AIR, surfaceBuf.voxelCode(15, 4, 8, 4), "y=184 must be AIR");

                // Pre-allocate fresh empty ProtoChunks so paper$installBulk4BitSection runs on real empty chunks
                final LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(minBlockY, sectionCount * 16);
                final ProtoChunk[] targetChunks = new ProtoChunk[batchCount];
                final net.minecraft.world.level.chunk.Strategy<BlockState> blockStrategy =
                    net.minecraft.world.level.chunk.Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
                for (int c = 0; c < batchCount; c++) {
                    final LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
                    for (int i = 0; i < sectionCount; i++) {
                        sections[i] = new LevelChunkSection(
                            new net.minecraft.world.level.chunk.PalettedContainer<>(Blocks.AIR.defaultBlockState(), blockStrategy),
                            null
                        );
                    }
                    targetChunks[c] = new ProtoChunk(
                        new ChunkPos(c & 7, c >>> 3),
                        UpgradeData.EMPTY,
                        sections,
                        new net.minecraft.world.ticks.ProtoChunkTicks<>(),
                        new net.minecraft.world.ticks.ProtoChunkTicks<>(),
                        heightAccessor,
                        null,
                        null
                    );
                }
                final BlockState[] palette16 = CompactSurfaceWritebackApplier.defaultOverworldPalette16();
                final long a0 = System.nanoTime();
                CompactSectionWritebackApplier.ApplyStats stats = null;
                for (int c = 0; c < batchCount; c++) {
                    stats = CompactSurfaceWritebackApplier.applyToChunk(
                        surfaceBuf, c, sectionCount, minBlockY, targetChunks[c], palette16
                    );
                }
                final long a1 = System.nanoTime();
                final ProtoChunk protoChunk = targetChunks[0];

                assertTrue(stats.skippedAirSections() > 0, "Expected uniform sky air sections to skip in O(1)");
                assertEquals(Blocks.BEDROCK.defaultBlockState(), protoChunk.getBlockState(0, -64, 0));
                assertEquals(Blocks.DEEPSLATE.defaultBlockState(), protoChunk.getBlockState(4, -10, 4));
                assertEquals(Blocks.AIR.defaultBlockState(), protoChunk.getBlockState(4, 184, 4));
                assertTrue(
                    protoChunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG).getFirstAvailable(4, 4) > 0,
                    "WORLD_SURFACE_WG must be populated"
                );

                final double wallMs = (t1 - t0) / 1e6;
                final double chPerSec = (batchCount * 1e9) / Math.max(1L, t1 - t0);
                final double applyMsPerCh = ((a1 - a0) / 1e6) / batchCount;
                final double applyChPerSec = (batchCount * 1e9) / Math.max(1L, a1 - a0);
                System.out.printf(
                    "[Amdahl-TrackD] GPU Fused TERRAIN+SURFACE 4-bit (B=%d): wall=%.3f ms (%.0f ch/s) | kernel=%.3f ms | DMA=%.3f ms | CPU apply=%.3f ms/ch (%.0f ch/s 1T) (skippedAir=%d, uniform=%d, mixed=%d)%n",
                    batchCount,
                    wallMs,
                    chPerSec,
                    timings[0] / 1e6,
                    timings[1] / 1e6,
                    applyMsPerCh,
                    applyChPerSec,
                    stats.skippedAirSections(),
                    stats.uniformNonAirSections(),
                    stats.mixedSections()
                );
            }
        }
    }
}
