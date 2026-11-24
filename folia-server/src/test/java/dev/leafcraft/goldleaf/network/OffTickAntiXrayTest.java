package dev.leafcraft.goldleaf.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OffTickAntiXrayTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int DIAMOND_ORE = 2;

    @Test
    void chunkPacketPlanDetectsSectionEpochInvalidation() {
        final OffTickChunkPacketController.SectionSnapshot[] snaps = new OffTickChunkPacketController.SectionSnapshot[] {
            new OffTickChunkPacketController.SectionSnapshot(0, 10, false, 4, 0xFFFFL),
            new OffTickChunkPacketController.SectionSnapshot(1, 3, true, 1, 0L)
        };
        final OffTickChunkPacketController.ChunkPacketPlan plan =
            new OffTickChunkPacketController.ChunkPacketPlan(5, -7, true, snaps);

        assertFalse(plan.isStale(new int[] {10, 3}));
        assertTrue(plan.isStale(new int[] {11, 3}), "Mutated section epoch must invalidate plan");
        assertTrue(plan.isStale(new int[] {10}), "Mismatched section count must invalidate plan");
        assertTrue(plan.section(1).isUniformOrEmpty());
        assertFalse(plan.section(0).isUniformOrEmpty());
    }

    @Test
    void offTickSubmissionRunsOnDedicatedDaemonWorkerThread() throws Exception {
        final OffTickChunkPacketController.ChunkPacketPlan plan =
            new OffTickChunkPacketController.ChunkPacketPlan(
                1,
                2,
                true,
                new OffTickChunkPacketController.SectionSnapshot[0]
            );
        final String workerThreadName = OffTickChunkPacketController.submitOffTick(
            plan,
            p -> Thread.currentThread().getName()
        ).get(5, TimeUnit.SECONDS);

        assertTrue(
            workerThreadName.startsWith("GoldLeaf-ChunkEncode-"),
            "Expected GoldLeaf-ChunkEncode-* worker thread, got " + workerThreadName
        );
    }

    @Test
    void lcaAntiXrayFastPathClassifiesUniformOccludedAndOreSections() {
        final boolean[] hiddenOres = new boolean[4];
        hiddenOres[DIAMOND_ORE] = true;

        assertEquals(
            LcaAntiXrayFastPath.SectionAction.SKIP_UNIFORM_OR_NO_ORES,
            LcaAntiXrayFastPath.classifySection(true, new int[] {AIR}, hiddenOres, 1, false, false)
        );
        assertEquals(
            LcaAntiXrayFastPath.SectionAction.SKIP_UNIFORM_OR_NO_ORES,
            LcaAntiXrayFastPath.classifySection(false, new int[] {AIR, STONE}, hiddenOres, 1, false, false)
        );
        assertEquals(
            LcaAntiXrayFastPath.SectionAction.BYPASS_NEGOTIATED_OCCLUSION,
            LcaAntiXrayFastPath.classifySection(false, new int[] {STONE, DIAMOND_ORE}, hiddenOres, 2, true, true)
        );
        assertEquals(
            LcaAntiXrayFastPath.SectionAction.OBFUSCATE_WITH_OCTREE,
            LcaAntiXrayFastPath.classifySection(false, new int[] {STONE, DIAMOND_ORE}, hiddenOres, 1, false, false)
        );
    }

    @Test
    void lcaAntiXrayObfuscatesOccludedOresSkipsEmptySubCubesAndRevealsOnBlockBreak() {
        final boolean[] hiddenOres = new boolean[4];
        hiddenOres[DIAMOND_ORE] = true;
        final boolean[] opaque = new boolean[4];
        opaque[STONE] = true;
        opaque[DIAMOND_ORE] = true;

        final int[] blocks = new int[LcaAntiXrayFastPath.SECTION_BLOCKS];
        Arrays.fill(blocks, AIR);

        // Build a solid 3x3x3 stone cube around (6, 6, 6) with DIAMOND_ORE at center (6, 6, 6)
        for (int y = 5; y <= 7; y++) {
            for (int z = 5; z <= 7; z++) {
                for (int x = 5; x <= 7; x++) {
                    blocks[(y << 8) | (z << 4) | x] = STONE;
                }
            }
        }
        final int occludedOreIdx = (6 << 8) | (6 << 4) | 6;
        blocks[occludedOreIdx] = DIAMOND_ORE;

        // Place an exposed DIAMOND_ORE at (10, 10, 10) surrounded by air
        final int exposedOreIdx = (10 << 8) | (10 << 4) | 10;
        blocks[exposedOreIdx] = DIAMOND_ORE;

        final long octreeMask = LcaAntiXrayFastPath.computeOctreeOccupancyMask64(blocks, AIR);
        final int[] obfuscated = new int[LcaAntiXrayFastPath.SECTION_BLOCKS];
        final LcaAntiXrayFastPath.ObfuscationStats stats = LcaAntiXrayFastPath.obfuscateEngineMode1(
            blocks,
            obfuscated,
            octreeMask,
            hiddenOres,
            opaque,
            STONE,
            false
        );

        assertEquals(1, stats.blocksObfuscated(), "Only the fully occluded ore should be masked");
        assertTrue(stats.subCubesSkipped() >= 60, "Empty 4x4x4 sub-cubes should be skipped via octreeOccupancyMask64");
        assertEquals(STONE, obfuscated[occludedOreIdx], "Occluded ore must be replaced by stone");
        assertEquals(DIAMOND_ORE, obfuscated[exposedOreIdx], "Exposed ore must remain visible");

        // Break adjacent stone at (7, 6, 6): verify neighbor reveal finds (6, 6, 6) immediately
        final int[] revealed = LcaAntiXrayFastPath.findRevealedOreNeighborsOnBreak(blocks, 7, 6, 6, hiddenOres);
        assertEquals(1, revealed.length);
        assertEquals(occludedOreIdx, revealed[0]);

        // After breaking (7, 6, 6) to AIR, re-obfuscating must NOT hide (6, 6, 6)
        blocks[(6 << 8) | (6 << 4) | 7] = AIR;
        final int[] afterBreak = new int[LcaAntiXrayFastPath.SECTION_BLOCKS];
        LcaAntiXrayFastPath.obfuscateEngineMode1(
            blocks,
            afterBreak,
            LcaAntiXrayFastPath.computeOctreeOccupancyMask64(blocks, AIR),
            hiddenOres,
            opaque,
            STONE,
            false
        );
        assertEquals(DIAMOND_ORE, afterBreak[occludedOreIdx], "Ore exposed by block break must not be hidden");
    }

    @Test
    void defaultControllerDispatchesFreshPacketInOrderAndFallsBackOnStaleOrError() {
        final DefaultOffTickChunkPacketController controller = new DefaultOffTickChunkPacketController();
        final ServerGamePacketListenerImpl connection = Mockito.mock(ServerGamePacketListenerImpl.class);
        final LevelChunk chunk = Mockito.mock(LevelChunk.class);
        final LevelChunkSection section = Mockito.mock(LevelChunkSection.class);
        final ClientboundLevelChunkWithLightPacket packet = Mockito.mock(ClientboundLevelChunkWithLightPacket.class);

        Mockito.when(connection.isAcceptingMessages()).thenReturn(true);
        Mockito.when(chunk.getSections()).thenReturn(new LevelChunkSection[] {section});
        Mockito.when(section.paper$getSectionEpoch()).thenReturn(7);

        final OffTickChunkPacketController.ChunkPacketPlan plan =
            new OffTickChunkPacketController.ChunkPacketPlan(
                3,
                -4,
                true,
                new OffTickChunkPacketController.SectionSnapshot[] {
                    new OffTickChunkPacketController.SectionSnapshot(0, 7, false, 4, 1L)
                }
            );

        // Fresh plan: sends packet immediately in FIFO order and returns true
        assertTrue(controller.dispatchPreparedPacket(connection, chunk, plan, packet));
        Mockito.verify(connection, Mockito.times(1)).send(packet);
        assertEquals(1L, controller.offTickSentCount());
        assertEquals(0L, controller.staleFallbackCount());

        // Stale section epoch: must return false so PlayerChunkSender falls back synchronously (never drop chunk)
        Mockito.when(section.paper$getSectionEpoch()).thenReturn(8);
        assertFalse(controller.dispatchPreparedPacket(connection, chunk, plan, packet));
        Mockito.verify(connection, Mockito.times(1)).send(packet);
        assertEquals(1L, controller.offTickSentCount());
        assertEquals(1L, controller.staleFallbackCount());

        // Send exception: must return false so PlayerChunkSender falls back synchronously (S-004)
        Mockito.when(section.paper$getSectionEpoch()).thenReturn(7);
        Mockito.doThrow(new IllegalStateException("simulated send fault")).when(connection).send(packet);
        assertFalse(controller.dispatchPreparedPacket(connection, chunk, plan, packet));
        assertEquals(1L, controller.offTickSentCount());
        assertEquals(2L, controller.staleFallbackCount());

        // Closed connection: returns false without sending
        Mockito.when(connection.isAcceptingMessages()).thenReturn(false);
        assertFalse(controller.dispatchPreparedPacket(connection, chunk, plan, packet));
        assertEquals(2L, controller.staleFallbackCount());
    }

    @Test
    void prepareAndDispatchAsyncOffloadsToWorkerPoolAndFallsBackOnStaleOrWorkerError() throws Exception {
        final DefaultOffTickChunkPacketController controller = new DefaultOffTickChunkPacketController();
        final ServerGamePacketListenerImpl connection = Mockito.mock(ServerGamePacketListenerImpl.class);
        final LevelChunk chunk = Mockito.mock(LevelChunk.class);
        final LevelChunkSection section = Mockito.mock(LevelChunkSection.class);
        final ClientboundLevelChunkWithLightPacket packet = Mockito.mock(ClientboundLevelChunkWithLightPacket.class);

        Mockito.when(connection.isAcceptingMessages()).thenReturn(true);
        Mockito.when(chunk.getSections()).thenReturn(new LevelChunkSection[] {section});
        Mockito.when(section.paper$getSectionEpoch()).thenReturn(12);

        final OffTickChunkPacketController.ChunkPacketPlan plan =
            new OffTickChunkPacketController.ChunkPacketPlan(
                4,
                -2,
                true,
                new OffTickChunkPacketController.SectionSnapshot[] {
                    new OffTickChunkPacketController.SectionSnapshot(0, 12, false, 3, ~0L)
                }
            );

        final java.util.concurrent.atomic.AtomicReference<String> workerThread = new java.util.concurrent.atomic.AtomicReference<>();
        final boolean sent = controller.prepareAndDispatchAsync(connection, chunk, plan, p -> {
            workerThread.set(Thread.currentThread().getName());
            return packet;
        }).get(5, TimeUnit.SECONDS);

        assertTrue(sent);
        assertTrue(workerThread.get() != null && workerThread.get().startsWith("GoldLeaf-ChunkEncode-"));
        assertEquals(1L, controller.offTickSentCount());

        // Worker exception must fail closed (false + staleFallbackCount++)
        final boolean failed = controller.prepareAndDispatchAsync(connection, chunk, plan, p -> {
            throw new IllegalStateException("simulated encoder error");
        }).get(5, TimeUnit.SECONDS);
        assertFalse(failed);
        assertEquals(1L, controller.staleFallbackCount());

        // Stale epoch detected after async preparation
        Mockito.when(section.paper$getSectionEpoch()).thenReturn(13);
        final boolean stale = controller.prepareAndDispatchAsync(connection, chunk, plan, p -> packet)
            .get(5, TimeUnit.SECONDS);
        assertFalse(stale);
        assertEquals(2L, controller.staleFallbackCount());
    }
}
