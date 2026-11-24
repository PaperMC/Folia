package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.common.list.ShortList;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldLeafColdSectionBackingTest {

    private static Strategy<BlockState> blockStrategy;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
    }

    private static int[] buildMixedSectionVoxels(
        final BlockState air,
        final BlockState stone,
        final BlockState grassBlock,
        final BlockState water,
        final BlockState fence
    ) {
        final int airId = Block.getId(air);
        final int stoneId = Block.getId(stone);
        final int grassId = Block.getId(grassBlock);
        final int waterId = Block.getId(water);
        final int fenceId = Block.getId(fence);

        final int[] voxels = new int[4096];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    final int pos12 = LcaSectionBacking.pos12(x, y, z);
                    if (y < 4) {
                        voxels[pos12] = stoneId;
                    } else if (y == 4 && x < 4 && z < 4) {
                        voxels[pos12] = grassId;
                    } else if (y == 4 && x >= 12 && z >= 12) {
                        voxels[pos12] = waterId;
                    } else if (y == 5 && x == 2 && z == 3) {
                        voxels[pos12] = fenceId;
                    } else {
                        voxels[pos12] = airId;
                    }
                }
            }
        }
        return voxels;
    }

    private static LcaSectionBacking buildBacking(final int[] voxels) {
        return LcaSectionBacking.fromDirectVoxels(
            voxels,
            15,
            0,
            -1,
            -1,
            -1,
            id -> !Block.stateById(id).isAir(),
            id -> Block.stateById(id).isRandomlyTicking(),
            id -> Block.stateById(id).getFluidState().isRandomlyTicking()
        );
    }

    @Test
    void attachingColdBackingEvictsSimpleBitStorageToZeroBitShellAndReadsParity() {
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        final BlockState water = Blocks.WATER.defaultBlockState();
        final BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        final int[] voxels = buildMixedSectionVoxels(air, stone, grass, water, fence);

        final LcaSectionBacking lcaBacking = buildBacking(voxels);
        final GoldLeafBlockSectionBacking coldBacking = new GoldLeafBlockSectionBacking(lcaBacking);

        final PalettedContainer<BlockState> container = new PalettedContainer<>(air, blockStrategy);
        container.paper$attachColdBacking(coldBacking, air);

        assertInstanceOf(ZeroBitStorage.class, container.data.storage());
        assertEquals(0, container.data.storage().getRaw().length);
        assertEquals(0, container.bitsPerEntry());
        assertNotNull(container.paper$getColdBacking());

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    final int pos12 = LcaSectionBacking.pos12(x, y, z);
                    final BlockState expected = Block.stateById(voxels[pos12]);
                    assertEquals(expected, container.get(x, y, z));
                    assertEquals(expected, container.get(pos12));
                }
            }
        }
    }

    @Test
    void writesLandInSparseDirtyOverlayAndFlushClearsDirtyCacheWithoutInflatingBitStorage() {
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState dirt = Blocks.DIRT.defaultBlockState();
        final BlockState diamond = Blocks.DIAMOND_BLOCK.defaultBlockState();

        final int[] voxels = new int[4096];
        java.util.Arrays.fill(voxels, Block.getId(stone));
        voxels[LcaSectionBacking.pos12(1, 2, 3)] = Block.getId(dirt);

        final LcaSectionBacking lcaBacking = buildBacking(voxels);
        final GoldLeafBlockSectionBacking coldBacking = new GoldLeafBlockSectionBacking(lcaBacking);
        final PalettedContainer<BlockState> container = new PalettedContainer<>(stone, blockStrategy);
        container.paper$attachColdBacking(coldBacking, stone);

        assertEquals(0, coldBacking.dirtyCount());

        final BlockState prev1 = container.getAndSet(1, 2, 3, diamond);
        assertEquals(dirt, prev1);
        assertEquals(diamond, container.get(1, 2, 3));

        final BlockState prev2 = container.getAndSetUnchecked(4, 5, 6, air);
        assertEquals(stone, prev2);
        assertEquals(air, container.get(4, 5, 6));

        container.set(7, 8, 9, dirt);
        assertEquals(dirt, container.get(7, 8, 9));

        final int idx = LcaSectionBacking.pos12(10, 11, 12);
        container.set(idx, diamond);
        assertEquals(diamond, container.get(idx));

        assertEquals(4, coldBacking.dirtyCount());
        assertInstanceOf(ZeroBitStorage.class, container.data.storage());

        // Simulate CoW compaction flush: build new immutable base frame from current state and swap
        final int[] compactedVoxels = lcaBacking.materializeEphemeralStateIds(null, null);
        coldBacking.replaceCompactedBacking(buildBacking(compactedVoxels));
        assertEquals(0, coldBacking.dirtyCount());
        assertEquals(diamond, container.get(1, 2, 3));
        assertEquals(air, container.get(4, 5, 6));
        assertEquals(dirt, container.get(7, 8, 9));
        assertEquals(diamond, container.get(10, 11, 12));
        assertInstanceOf(ZeroBitStorage.class, container.data.storage());
    }

    @Test
    void copyPackAndVanillaWireRoundTripPreserveAllVoxelsAndStayNonPinning() {
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        final BlockState water = Blocks.WATER.defaultBlockState();
        final BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        final int[] voxels = buildMixedSectionVoxels(air, stone, grass, water, fence);

        final LcaSectionBacking lcaBacking = buildBacking(voxels);
        final GoldLeafBlockSectionBacking coldBacking = new GoldLeafBlockSectionBacking(lcaBacking);
        final PalettedContainer<BlockState> container = new PalettedContainer<>(air, blockStrategy);
        container.paper$attachColdBacking(coldBacking, air);

        // Mutate one voxel in the dirty overlay before copying & serializing
        container.set(0, 15, 0, stone);
        voxels[LcaSectionBacking.pos12(0, 15, 0)] = Block.getId(stone);

        // copy() produces an independent non-pinning ephemeral container
        final PalettedContainer<BlockState> copy = container.copy();
        assertNull(copy.paper$getColdBacking());
        assertInstanceOf(ZeroBitStorage.class, container.data.storage());
        for (int pos12 = 0; pos12 < 4096; pos12++) {
            assertEquals(Block.stateById(voxels[pos12]), copy.get(pos12));
        }
        copy.set(0, 0, 0, air);
        assertEquals(stone, container.get(0, 0, 0));

        // pack() works cleanly via ephemeral snapshot
        final PalettedContainerRO.PackedData<BlockState> packed = container.pack(blockStrategy);
        assertNotNull(packed);
        assertFalse(packed.paletteEntries().isEmpty());

        // Vanilla wire write + serialized size parity
        final int expectedSize = container.getSerializedSize();
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            container.write(buf);
            assertEquals(expectedSize, buf.readableBytes());

            final PalettedContainer<BlockState> decoded = new PalettedContainer<>(air, blockStrategy);
            decoded.paper$attachColdBacking(coldBacking, air);
            decoded.read(buf);
            assertNull(decoded.paper$getColdBacking());
            for (int pos12 = 0; pos12 < 4096; pos12++) {
                assertEquals(Block.stateById(voxels[pos12]), decoded.get(pos12));
            }
        } finally {
            buf.release();
        }
    }

    @Test
    void levelChunkSectionRecalcBlockCountsMatchesVanillaBlockCountingChunkSection() {
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        final BlockState water = Blocks.WATER.defaultBlockState();
        final BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        final int[] voxels = buildMixedSectionVoxels(air, stone, grass, water, fence);

        final LcaSectionBacking lcaBacking = buildBacking(voxels);
        final GoldLeafBlockSectionBacking coldBacking = new GoldLeafBlockSectionBacking(lcaBacking);
        final PalettedContainer<BlockState> coldStates = new PalettedContainer<>(air, blockStrategy);
        coldStates.paper$attachColdBacking(coldBacking, air);

        final PalettedContainer<BlockState> vanillaStates = new PalettedContainer<>(air, blockStrategy);
        for (int pos12 = 0; pos12 < 4096; pos12++) {
            vanillaStates.set(pos12, Block.stateById(voxels[pos12]));
        }

        final LevelChunkSection coldSection = new LevelChunkSection(coldStates, null);
        final LevelChunkSection vanillaSection = new LevelChunkSection(vanillaStates, null);

        assertEquals(vanillaSection.hasOnlyAir(), coldSection.hasOnlyAir());
        assertEquals(vanillaSection.hasFluid(), coldSection.hasFluid());
        assertEquals(vanillaSection.isRandomlyTicking(), coldSection.isRandomlyTicking());
        assertEquals(vanillaSection.isRandomlyTickingBlocks(), coldSection.isRandomlyTickingBlocks());
        assertEquals(vanillaSection.isRandomlyTickingFluids(), coldSection.isRandomlyTickingFluids());
        assertEquals(vanillaSection.moonrise$hasSpecialCollidingBlocks(), coldSection.moonrise$hasSpecialCollidingBlocks());

        final ShortList vanillaTicking = vanillaSection.moonrise$getTickingBlockList();
        final ShortList coldTicking = coldSection.moonrise$getTickingBlockList();
        assertEquals(vanillaTicking.size(), coldTicking.size());
        final Set<Short> vanillaCoords = new HashSet<>();
        final Set<Short> coldCoords = new HashSet<>();
        for (int i = 0; i < vanillaTicking.size(); i++) {
            vanillaCoords.add(vanillaTicking.getRaw(i));
            coldCoords.add(coldTicking.getRaw(i));
        }
        assertEquals(vanillaCoords, coldCoords);

        // Also verify maybeHas, count, and getAll parity
        assertTrue(coldStates.maybeHas(s -> s == fence));
        assertFalse(coldStates.maybeHas(s -> s == Blocks.DIAMOND_BLOCK.defaultBlockState()));
        final Map<BlockState, Integer> coldCounts = new HashMap<>();
        coldStates.count(coldCounts::put);
        final Map<BlockState, Integer> vanillaCounts = new HashMap<>();
        vanillaStates.count(vanillaCounts::put);
        assertEquals(vanillaCounts, coldCounts);
    }

    @Test
    void configDefaultsToNativeSectionBackingAndCanonicalLookupMapsLiveStates() {
        assertTrue(GoldLeafConfig.defaults().storage.lcaNativeSectionBacking);
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final int stoneId = Block.getId(stone);
        assertEquals(stoneId, LcaDirectSectionSerializer.lookupRuntimeStateId("minecraft:stone"));
        assertEquals(-1, LcaDirectSectionSerializer.lookupRuntimeStateId("minecraft:nonexistent_block_xyz"));
        assertNull(LcaDirectSectionSerializer.createDefault().tryDeserializeDirect(null, null, null));

        // Unsorted multi-property block-state keys from LcaStreamingNbtEncoder must resolve identically
        final BlockState oakLeaves = Blocks.OAK_LEAVES.defaultBlockState();
        final int leavesId = Block.getId(oakLeaves);
        final String sortedKey = LcaDirectSectionSerializer.canonicalBlockStateString(oakLeaves);
        assertEquals(leavesId, LcaDirectSectionSerializer.lookupRuntimeStateId(sortedKey));
        assertEquals(
            leavesId,
            LcaDirectSectionSerializer.lookupRuntimeStateId(
                "minecraft:oak_leaves[waterlogged=false,persistent=false,distance=7]"
            )
        );
        assertEquals(
            sortedKey,
            LcaDirectSectionSerializer.normalizeCanonicalBlockStateKey(
                "minecraft:oak_leaves[waterlogged=false,persistent=false,distance=7]"
            )
        );
        // Bare default-state keys (omitted default Properties) and partial-property keys must resolve
        assertEquals(
            Block.getId(Blocks.WATER.defaultBlockState()),
            LcaDirectSectionSerializer.lookupRuntimeStateId("minecraft:water")
        );
        assertEquals(
            Block.getId(Blocks.LAVA.defaultBlockState()),
            LcaDirectSectionSerializer.lookupRuntimeStateId("minecraft:lava")
        );
        assertEquals(
            Block.getId(Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState()),
            LcaDirectSectionSerializer.lookupRuntimeStateId("minecraft:deepslate_redstone_ore")
        );
        assertEquals(
            Block.getId(oakLeaves.setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 1)),
            LcaDirectSectionSerializer.lookupRuntimeStateId("minecraft:oak_leaves[distance=1]")
        );

        // Modern 26.x dimensions/<namespace>/<path>/region layout world-ID inference
        assertEquals(
            "minecraft:overworld",
            LcaRegionStorageProvider.inferWorldId(java.nio.file.Path.of("world/dimensions/minecraft/overworld/region"))
        );
        assertEquals(
            "minecraft:the_nether",
            LcaRegionStorageProvider.inferWorldId(java.nio.file.Path.of("world/dimensions/minecraft/the_nether/region"))
        );
        assertEquals(
            "minecraft:the_end",
            LcaRegionStorageProvider.inferWorldId(java.nio.file.Path.of("world/DIM1/region"))
        );
    }

    @Test
    void releaseBaseFrameIfCleanEvictsOnlyCleanEntriesAndLazyEntityHydrationSpiWorks() {
        assertTrue(GoldLeafConfig.defaults().storage.lazyEntityHydration);
        final LcaChunkResidencyCache cache = new LcaChunkResidencyCache(16);
        final io.github.dailystruggle.anvil.RawChunkFrameSnapshot dummy =
            new io.github.dailystruggle.anvil.RawChunkFrameSnapshot(
                new byte[32], 4786, -4, 1, -1, new int[]{0}, 3
            );
        cache.installBaseFrame(100L, dummy);
        assertEquals(1, cache.residentCount());
        assertTrue(cache.releaseBaseFrameIfClean(100L));
        assertEquals(0, cache.residentCount());
        assertNull(cache.getReadSnapshot(100L));

        cache.installBaseFrame(200L, dummy);
        cache.recordDirtyVoxel(200L, 0, 5, Block.getId(Blocks.STONE.defaultBlockState()));
        cache.freezeTickEndSnapshot(200L);
        assertFalse(cache.releaseBaseFrameIfClean(200L), "Dirty entries must not be evicted by releaseBaseFrameIfClean");
        assertEquals(1, cache.residentCount());

        final ca.spottedleaf.moonrise.spi.LazyEntityHydrationSpi prev = ca.spottedleaf.moonrise.spi.LazyEntityHydrationSpi.get();
        try {
            ca.spottedleaf.moonrise.spi.LazyEntityHydrationSpi.resetDefault();
            assertFalse(ca.spottedleaf.moonrise.spi.LazyEntityHydrationSpi.shouldDeferEntityHydration(null, 0, 0));
            ca.spottedleaf.moonrise.spi.LazyEntityHydrationSpi.register((w, cx, cz) -> true);
            assertTrue(ca.spottedleaf.moonrise.spi.LazyEntityHydrationSpi.shouldDeferEntityHydration(null, 0, 0));
        } finally {
            ca.spottedleaf.moonrise.spi.LazyEntityHydrationSpi.register(prev);
        }
    }

    @Test
    void randomTickBlockAndStateMutationsPreserveTickingListsAndCounts() {
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockState dirt = Blocks.DIRT.defaultBlockState();
        final BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        final BlockState wheat0 = Blocks.WHEAT.defaultBlockState();
        final BlockState wheat3 = wheat0.setValue(net.minecraft.world.level.block.CropBlock.AGE, 3);
        final BlockState sapling = Blocks.OAK_SAPLING.defaultBlockState();
        final BlockState log = Blocks.OAK_LOG.defaultBlockState();

        final int[] voxels = new int[4096];
        final int airId = Block.getId(air);
        final int dirtId = Block.getId(dirt);
        final int grassId = Block.getId(grass);
        final int wheat0Id = Block.getId(wheat0);
        final int saplingId = Block.getId(sapling);
        java.util.Arrays.fill(voxels, airId);
        voxels[0] = dirtId;
        voxels[1] = grassId;
        voxels[2] = wheat0Id;
        voxels[3] = saplingId;

        final LcaSectionBacking lcaBacking = buildBacking(voxels);
        final GoldLeafBlockSectionBacking coldBacking = new GoldLeafBlockSectionBacking(lcaBacking);
        final PalettedContainer<BlockState> coldStates = new PalettedContainer<>(air, blockStrategy);
        coldStates.paper$attachColdBacking(coldBacking, air);

        final PalettedContainer<BlockState> vanillaStates = new PalettedContainer<>(air, blockStrategy);
        for (int pos12 = 0; pos12 < 4096; pos12++) {
            vanillaStates.set(pos12, Block.stateById(voxels[pos12]));
        }

        final LevelChunkSection coldSection = new LevelChunkSection(coldStates, null);
        final LevelChunkSection vanillaSection = new LevelChunkSection(vanillaStates, null);

        // Simulate random ticks: grass spreads to dirt (pos 0), grass dies to dirt (pos 1),
        // wheat ages 0->3 (pos 2), sapling grows into non-ticking oak log (pos 3)
        assertEquals(dirt, coldSection.setBlockState(0, 0, 0, grass, false));
        assertEquals(dirt, vanillaSection.setBlockState(0, 0, 0, grass, false));

        assertEquals(grass, coldSection.setBlockState(1, 0, 0, dirt, false));
        assertEquals(grass, vanillaSection.setBlockState(1, 0, 0, dirt, false));

        assertEquals(wheat0, coldSection.setBlockState(2, 0, 0, wheat3, false));
        assertEquals(wheat0, vanillaSection.setBlockState(2, 0, 0, wheat3, false));

        assertEquals(sapling, coldSection.setBlockState(3, 0, 0, log, false));
        assertEquals(sapling, vanillaSection.setBlockState(3, 0, 0, log, false));

        assertEquals(grass, coldSection.getBlockState(0, 0, 0));
        assertEquals(dirt, coldSection.getBlockState(1, 0, 0));
        assertEquals(wheat3, coldSection.getBlockState(2, 0, 0));
        assertEquals(log, coldSection.getBlockState(3, 0, 0));

        assertEquals(vanillaSection.isRandomlyTicking(), coldSection.isRandomlyTicking());
        assertEquals(vanillaSection.isRandomlyTickingBlocks(), coldSection.isRandomlyTickingBlocks());
        assertEquals(vanillaSection.hasOnlyAir(), coldSection.hasOnlyAir());
        assertEquals(vanillaSection.moonrise$getTickingBlockList().size(), coldSection.moonrise$getTickingBlockList().size());

        final Set<Short> vanillaTicking = new HashSet<>();
        final Set<Short> coldTicking = new HashSet<>();
        for (int i = 0; i < vanillaSection.moonrise$getTickingBlockList().size(); i++) {
            vanillaTicking.add(vanillaSection.moonrise$getTickingBlockList().getRaw(i));
            coldTicking.add(coldSection.moonrise$getTickingBlockList().getRaw(i));
        }
        assertEquals(vanillaTicking, coldTicking, "Ticking block coordinates must match vanilla after random-tick mutations");
        assertTrue(coldBacking.dirtyCount() >= 4, "Mutations must remain in sparse dirty overlay without inflating SimpleBitStorage");
        assertNotNull(coldStates.paper$getColdBacking());
    }

    @Test
    void fourStateLifecyclePromotionHysteresisAndEpochFencing() {
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState dirt = Blocks.DIRT.defaultBlockState();
        final BlockState oakPlanks = Blocks.OAK_PLANKS.defaultBlockState();

        // 1. Hysteresis quiet-window computation
        assertEquals(10_000L, GoldLeafSectionLifecycleManager.computeEffectiveQuietWindowMillis(200, 40, 0.70, 0.50));
        assertEquals(2_000L, GoldLeafSectionLifecycleManager.computeEffectiveQuietWindowMillis(200, 40, 0.70, 0.75));
        assertEquals(64, GoldLeafConfig.defaults().storage.lcaHotPromoteDirtyThreshold);
        assertFalse(GoldLeafConfig.defaults().storage.lcaCompactGeneratedChunks);

        // 2. Start in State 1 (COLD_LCA: baseFrame-only, compactIndex == null)
        final int[] voxels = new int[4096];
        java.util.Arrays.fill(voxels, Block.getId(air));
        voxels[0] = Block.getId(stone);
        voxels[15] = Block.getId(dirt);

        final GoldLeafBlockSectionBacking initialCold =
            GoldLeafSectionLifecycleManager.encodeVoxelsToColdBacking(voxels, "minecraft:overworld");
        assertTrue(initialCold.backing().isColdBaseFrameOnly());
        assertFalse(initialCold.backing().hasHydratedCompactBaseIndex());

        // Attach with low promotion threshold (4) to test WARM_SPARSE -> HOT_MUTABLE
        final GoldLeafBlockSectionBacking coldBacking = new GoldLeafBlockSectionBacking(
            initialCold.backing(), "minecraft:overworld", 4, Block::stateById, Block::getId
        );
        final PalettedContainer<BlockState> states = new PalettedContainer<>(air, blockStrategy);
        states.paper$attachColdBacking(coldBacking, air);
        final LevelChunkSection section = new LevelChunkSection(states, null);

        // First read hydrates to WARM_SPARSE, then O(1) clean demotion returns to COLD_LCA
        assertEquals(stone, section.getBlockState(0, 0, 0));
        assertTrue(coldBacking.backing().hasHydratedCompactBaseIndex());
        assertTrue(coldBacking.tryDemoteCleanToCold());
        assertTrue(coldBacking.backing().isColdBaseFrameOnly());
        assertFalse(coldBacking.backing().hasHydratedCompactBaseIndex());

        // 3. Light edits stay in WARM_SPARSE; 4th unique dirty cell promotes to HOT_MUTABLE
        section.setBlockState(1, 0, 0, oakPlanks, false);
        section.setBlockState(2, 0, 0, oakPlanks, false);
        section.setBlockState(3, 0, 0, oakPlanks, false);
        assertNotNull(states.paper$getColdBacking(), "Below threshold must remain in WARM_SPARSE");
        assertEquals(3, coldBacking.dirtyCount());

        section.setBlockState(4, 0, 0, oakPlanks, false);
        assertNull(states.paper$getColdBacking(), "At threshold must promote this section to HOT_MUTABLE");
        assertEquals(stone, section.getBlockState(0, 0, 0));
        assertEquals(oakPlanks, section.getBlockState(1, 0, 0));
        assertEquals(oakPlanks, section.getBlockState(4, 0, 0));
        assertEquals(dirt, section.getBlockState(15, 0, 0));

        // 4. Epoch-fenced compaction back to COLD_LCA: concurrent edit aborts swap losslessly
        final int staleEpoch = section.paper$getSectionEpoch();
        final int[] staleSnap = GoldLeafSectionLifecycleManager.snapshotSectionVoxels(section);
        final GoldLeafBlockSectionBacking staleEncoded =
            GoldLeafSectionLifecycleManager.encodeVoxelsToColdBacking(staleSnap, "minecraft:overworld");

        section.setBlockState(5, 0, 0, dirt, false); // concurrent edit bumps sectionEpoch
        assertFalse(
            GoldLeafSectionLifecycleManager.tryAttachEncodedSection(null, null, section, staleEpoch, staleEncoded, air),
            "Concurrent edit must fence off stale off-tick compaction"
        );
        assertNull(states.paper$getColdBacking());
        assertEquals(dirt, section.getBlockState(5, 0, 0));

        // 5. Fresh snapshot with matching epoch compacts HOT_MUTABLE -> COLD_LCA losslessly
        final int freshEpoch = section.paper$getSectionEpoch();
        final int[] freshSnap = GoldLeafSectionLifecycleManager.snapshotSectionVoxels(section);
        final GoldLeafBlockSectionBacking freshEncoded =
            GoldLeafSectionLifecycleManager.encodeVoxelsToColdBacking(freshSnap, "minecraft:overworld");
        assertTrue(freshEncoded.backing().isColdBaseFrameOnly());
        assertTrue(GoldLeafSectionLifecycleManager.tryAttachEncodedSection(null, null, section, freshEpoch, freshEncoded, air));
        assertNotNull(states.paper$getColdBacking());
        assertEquals(stone, section.getBlockState(0, 0, 0));
        assertEquals(oakPlanks, section.getBlockState(4, 0, 0));
        assertEquals(dirt, section.getBlockState(5, 0, 0));
        assertEquals(dirt, section.getBlockState(15, 0, 0));
    }

    @Test
    void sharedFullSkyLightSingletonIsCopyOnWriteAndLazyEntityRefreezeDefaultsTrue() {
        assertTrue(GoldLeafConfig.defaults().storage.lazyEntityRefreeze);

        final byte[] fullA = new byte[ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.ARRAY_SIZE];
        java.util.Arrays.fill(fullA, (byte) 0xFF);
        final byte[] fullB = fullA.clone();

        final var nibbleA = ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.fromLoadedBytes(fullA, 2);
        final var nibbleB = ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.fromLoadedBytes(fullB, 2);
        assertTrue(ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.isSharedFullBacking(nibbleA));
        assertTrue(ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.isSharedFullBacking(nibbleB));
        assertEquals(15, nibbleA.getVisible(0, 0, 0));
        assertEquals(15, nibbleB.getVisible(15, 15, 15));

        // Mutating nibbleA must isolate via CoW and never corrupt SHARED_FULL_BYTES or nibbleB
        nibbleA.set(3, 4, 5, 7);
        assertTrue(nibbleA.updateVisible());
        assertFalse(ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.isSharedFullBacking(nibbleA));
        assertTrue(ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.isSharedFullBacking(nibbleB));
        assertEquals(7, nibbleA.getVisible(3, 4, 5));
        assertEquals(15, nibbleA.getVisible(0, 0, 0));
        assertEquals(15, nibbleB.getVisible(3, 4, 5));

        // Non-uniform array must not use the shared full singleton
        final byte[] mixed = new byte[ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.ARRAY_SIZE];
        mixed[0] = (byte) 0x7A;
        final var nibbleMixed = ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.fromLoadedBytes(mixed, 2);
        assertFalse(ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.isSharedFullBacking(nibbleMixed));
        assertEquals(10, nibbleMixed.getVisible(0, 0, 0));
        assertEquals(7, nibbleMixed.getVisible(1, 0, 0));
    }

    @Test
    void weakReferenceUnloadGuardAndLoadedChunkFullSpiRouteCleanly() {
        assertTrue(GoldLeafSectionLifecycleManager.isChunkStillLoaded(null, null));

        final var prev = ca.spottedleaf.moonrise.spi.SectionLifecycleSpi.get();
        final java.util.concurrent.atomic.AtomicInteger loadedCalls = new java.util.concurrent.atomic.AtomicInteger();
        try {
            ca.spottedleaf.moonrise.spi.SectionLifecycleSpi.register(new ca.spottedleaf.moonrise.spi.SectionLifecycleSpi() {
                @Override
                public void onChunkDowngraded(
                    final net.minecraft.server.level.ServerLevel world,
                    final net.minecraft.world.level.chunk.LevelChunk chunk
                ) {
                }

                @Override
                public void onGeneratedChunkFull(
                    final net.minecraft.server.level.ServerLevel world,
                    final net.minecraft.world.level.chunk.LevelChunk chunk
                ) {
                }

                @Override
                public void onLoadedChunkFull(
                    final net.minecraft.server.level.ServerLevel world,
                    final net.minecraft.world.level.chunk.LevelChunk chunk
                ) {
                    loadedCalls.incrementAndGet();
                }
            });
            // Null world or chunk must be ignored safely
            ca.spottedleaf.moonrise.spi.SectionLifecycleSpi.notifyLoadedChunkFull(null, null);
            assertEquals(0, loadedCalls.get());
        } finally {
            ca.spottedleaf.moonrise.spi.SectionLifecycleSpi.register(prev);
        }
    }

    @Test
    void directZeroNbtBiomeInstallAndSharedFullDataLayerMatchVanillaAcross1To20Biomes() {
        // 1. CompoundTag transient attachment round-trip and clear
        final net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        assertNull(tag.paper$getDirectLoadAttachment());
        final Object marker = new Object();
        tag.paper$setDirectLoadAttachment(marker);
        assertEquals(marker, tag.paper$getDirectLoadAttachment());
        tag.paper$setDirectLoadAttachment(null);
        assertNull(tag.paper$getDirectLoadAttachment());

        // 2. Shared full DataLayer identity fast-path + CoW isolation
        final net.minecraft.world.level.chunk.DataLayer fullLayer =
            ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.createSharedFullDataLayer();
        final var nibble = ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.fromLoadedBytes(
            fullLayer.getData(), 2
        );
        assertTrue(ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.isSharedFullBacking(nibble));
        assertEquals(15, nibble.getVisible(7, 8, 9));
        nibble.set(7, 8, 9, 4);
        assertTrue(nibble.updateVisible());
        assertFalse(ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.isSharedFullBacking(nibble));
        assertEquals(4, nibble.getVisible(7, 8, 9));
        assertEquals((byte) 0xFF, fullLayer.getData()[0]);

        // 3. PalettedContainer#paper$installPackedBiomes parity across 1, 2, 3, 4, 8, 9, 20 distinct biomes
        final java.util.List<String> fakeRegistryList = new java.util.ArrayList<>();
        for (int i = 0; i < 32; i++) {
            fakeRegistryList.add("biome_" + i);
        }
        final net.minecraft.core.IdMap<String> idMap = new net.minecraft.core.IdMap<>() {
            @Override
            public int getId(final String value) {
                return fakeRegistryList.indexOf(value);
            }

            @Override
            public String byId(final int id) {
                return (id >= 0 && id < fakeRegistryList.size()) ? fakeRegistryList.get(id) : null;
            }

            @Override
            public int size() {
                return fakeRegistryList.size();
            }

            @Override
            public java.util.Iterator<String> iterator() {
                return fakeRegistryList.iterator();
            }
        };
        final Strategy<String> biomeStrategy = Strategy.createForBiomes(idMap);
        final int[] sizes = { 1, 2, 3, 4, 8, 9, 20 };
        for (final int count : sizes) {
            final java.util.List<String> palette = fakeRegistryList.subList(0, count);
            final PalettedContainer<String> direct = new PalettedContainer<>("biome_0", biomeStrategy);
            final PalettedContainer<String> expected = new PalettedContainer<>("biome_0", biomeStrategy);
            long[] packedLongs = null;
            if (count > 1) {
                final int bits = net.minecraft.util.Mth.ceillog2(count);
                final int perLong = 64 / bits;
                final int numLongs = (64 + perLong - 1) / perLong;
                packedLongs = new long[numLongs];
                for (int cell = 0; cell < 64; cell++) {
                    final int palIdx = (cell * 7 + 3) % count;
                    packedLongs[cell / perLong] |= ((long) palIdx) << ((cell % perLong) * bits);
                    final int x = cell & 3;
                    final int z = (cell >>> 2) & 3;
                    final int y = (cell >>> 4) & 3;
                    expected.set(x, y, z, palette.get(palIdx));
                }
            } else {
                expected.paper$installUniform(palette.get(0));
            }
            direct.paper$installPackedBiomes(palette, packedLongs);
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        assertEquals(
                            expected.get(x, y, z),
                            direct.get(x, y, z),
                            "Biome mismatch for count=" + count + " at (" + x + "," + y + "," + z + ")"
                        );
                    }
                }
            }
        }
    }

    @Test
    void flatBitPackUnifiedSectionBackingMatchesVanillaAcross1To5BitsAndRandomTicks() {
        final BlockState[] allStates = {
            Blocks.AIR.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
            Blocks.DEEPSLATE.defaultBlockState(),
            Blocks.DIRT.defaultBlockState(),
            Blocks.GRASS_BLOCK.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            Blocks.SAND.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
            Blocks.OAK_LOG.defaultBlockState(),
            Blocks.OAK_LEAVES.defaultBlockState(),
            Blocks.COAL_ORE.defaultBlockState(),
            Blocks.IRON_ORE.defaultBlockState(),
            Blocks.GOLD_ORE.defaultBlockState(),
            Blocks.DIAMOND_ORE.defaultBlockState(),
            Blocks.BEDROCK.defaultBlockState(),
            Blocks.CLAY.defaultBlockState(),
            Blocks.SNOW_BLOCK.defaultBlockState(),
            Blocks.ICE.defaultBlockState(),
            Blocks.COBBLESTONE.defaultBlockState(),
            Blocks.OAK_SAPLING.defaultBlockState()
        };
        final int[] counts = {2, 4, 7, 12, 20};
        final int[] expectedBits = {1, 2, 3, 4, 5};
        final Strategy<BlockState> strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);

        for (int c = 0; c < counts.length; c++) {
            final int pCount = counts[c];
            final int[] voxels = new int[4096];
            final PalettedContainer<BlockState> expected = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy);
            for (int i = 0; i < 4096; i++) {
                final BlockState st = allStates[(i * 13 + 5) % pCount];
                voxels[i] = Block.getId(st);
                expected.set(i, st);
            }
            final GoldLeafFlatBlockSectionBacking flat =
                GoldLeafFlatBlockSectionBacking.fromRuntimeVoxels(voxels, Block::stateById, Block::getId);
            assertNotNull(flat);
            assertEquals(expectedBits[c], flat.bitsPerVoxel());
            assertEquals((4096 * expectedBits[c] + 7) >>> 3, flat.flatBytes().length);

            final PalettedContainer<BlockState> actual = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy);
            actual.paper$attachColdBacking(flat, Blocks.AIR.defaultBlockState());
            assertTrue(actual.data.storage() instanceof net.minecraft.util.ZeroBitStorage);

            // 1. Verify all 4,096 voxels match vanilla
            for (int i = 0; i < 4096; i++) {
                assertEquals(expected.get(i), actual.get(i), "Mismatch at pCount=" + pCount + " idx=" + i);
            }

            // 2. Verify vanilla wire serialization parity
            final FriendlyByteBuf expBuf = new FriendlyByteBuf(Unpooled.buffer());
            final FriendlyByteBuf actBuf = new FriendlyByteBuf(Unpooled.buffer());
            expected.write(expBuf);
            actual.write(actBuf);
            assertEquals(actBuf.readableBytes(), actual.getSerializedSize());
            final PalettedContainer<BlockState> wireDecoded = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy);
            wireDecoded.read(actBuf);
            for (int i = 0; i < 4096; i++) {
                assertEquals(expected.get(i), wireDecoded.get(i), "Wire mismatch at pCount=" + pCount + " idx=" + i);
            }

            // 3. Verify sparse dirty edit + random-tick block counting parity
            final BlockState sapling = Blocks.OAK_SAPLING.defaultBlockState();
            expected.set(3, 5, 7, sapling);
            actual.set(3, 5, 7, sapling);
            assertEquals(1, flat.dirtyCount());
            assertEquals(sapling, actual.get(3, 5, 7));

            final java.util.Map<BlockState, Integer> expCounts = new java.util.HashMap<>();
            final java.util.Map<BlockState, Integer> actCounts = new java.util.HashMap<>();
            expected.count((st, cnt) -> expCounts.merge(st, cnt, Integer::sum));
            actual.count((st, cnt) -> actCounts.merge(st, cnt, Integer::sum));
            assertEquals(expCounts, actCounts, "Block count mismatch at pCount=" + pCount);
        }
    }

    @Test
    void flatBitPackGpuDmaFactoriesDirtyCompactionAndBulkColdInstallWorkEndToEnd() {
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState water = Blocks.WATER.defaultBlockState();
        final BlockState dirt = Blocks.DIRT.defaultBlockState();

        // 1. 2-bit GPU DMA factory (1,024 B) + paper$installBulkColdBacking
        final BlockState[] pal2 = {air, stone, water};
        final int[] words256 = new int[256];
        int expectedNonEmpty = 0;
        int expectedFluid = 0;
        for (int w = 0; w < 256; w++) {
            int word = 0;
            for (int lx = 0; lx < 16; lx++) {
                final int code = (w + lx) % 3;
                word |= (code << (lx << 1));
                if (code != 0) {
                    expectedNonEmpty++;
                }
                if (code == 2) {
                    expectedFluid++;
                }
            }
            words256[w] = word;
        }
        final GoldLeafFlatBlockSectionBacking gpu2Bit =
            GoldLeafFlatBlockSectionBacking.fromPacked2BitWords(words256, pal2, "minecraft:overworld");
        assertEquals(2, gpu2Bit.bitsPerVoxel());
        assertEquals(1024, gpu2Bit.flatBytes().length);
        assertEquals("minecraft:overworld", gpu2Bit.worldId());

        final Strategy<BlockState> blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        final PalettedContainer<BlockState> blocks = new PalettedContainer<>(air, blockStrategy);
        @SuppressWarnings("unchecked")
        final PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes =
            (PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>)
                (PalettedContainer<?>) new PalettedContainer<>("plains", Strategy.createForBiomes(new net.minecraft.core.IdMap<>() {
                    @Override public int getId(final String value) { return 0; }
                    @Override public String byId(final int index) { return "plains"; }
                    @Override public int size() { return 1; }
                    @Override public java.util.Iterator<String> iterator() { return java.util.List.of("plains").iterator(); }
                }));
        final LevelChunkSection section = new LevelChunkSection(blocks, biomes);
        assertTrue(section.paper$installBulkColdBacking(
            gpu2Bit, pal2, (short) expectedNonEmpty, (short) expectedFluid, (short) 0
        ));
        org.junit.jupiter.api.Assertions.assertSame(gpu2Bit, section.getStates().paper$getColdBacking());
        for (int i = 0; i < 4096; i++) {
            final int w = i >>> 4;
            final int lx = i & 15;
            assertEquals(pal2[(w + lx) % 3], section.getStates().get(i), "2-bit GPU DMA voxel mismatch at " + i);
        }

        // Fast clean palette-to-dict voxel extraction
        final int[] dictVoxels = gpu2Bit.extractCleanDictVoxels(new int[]{10, 20, 30});
        assertNotNull(dictVoxels);
        assertEquals(10, dictVoxels[0]);
        assertEquals(20, dictVoxels[1]);
        assertEquals(30, dictVoxels[2]);

        // 2. Dirty edit -> compactDirtyOffThread merges cleanly to dirtyCount == 0
        section.setBlockState(0, 0, 0, dirt, false);
        assertEquals(1, gpu2Bit.dirtyCount());
        assertNull(gpu2Bit.extractCleanDictVoxels(new int[]{10, 20, 30}));
        final GoldLeafFlatBlockSectionBacking.CompactionResult compacted = gpu2Bit.compactDirtyOffThread();
        assertNotNull(compacted);
        assertNotNull(compacted.compactedBacking());
        assertEquals(0, compacted.compactedBacking().dirtyCount());
        assertEquals("minecraft:overworld", compacted.compactedBacking().worldId());
        assertEquals(dirt, compacted.compactedBacking().getByIndex(0));
        assertEquals(stone, compacted.compactedBacking().getByIndex(1));

        // 3. 4-bit GPU DMA factory (2,048 B)
        final BlockState[] pal4 = {air, stone, water, dirt};
        final long[] longs256 = new long[256];
        for (int w = 0; w < 256; w++) {
            long row = 0L;
            for (int lx = 0; lx < 16; lx++) {
                row |= ((long) ((w + lx) & 3)) << (lx << 2);
            }
            longs256[w] = row;
        }
        final GoldLeafFlatBlockSectionBacking gpu4Bit =
            GoldLeafFlatBlockSectionBacking.fromPacked4BitLongs(longs256, pal4, null);
        assertEquals(4, gpu4Bit.bitsPerVoxel());
        assertEquals(2048, gpu4Bit.flatBytes().length);
        for (int i = 0; i < 4096; i++) {
            assertEquals(pal4[((i >>> 4) + (i & 15)) & 3], gpu4Bit.getByIndex(i));
        }

        // 4. Fail-closed guard: ticking block in palette rejects paper$installBulkColdBacking
        final LevelChunkSection airSec = new LevelChunkSection(new PalettedContainer<>(air, blockStrategy), biomes);
        final BlockState[] tickingPal = {air, Blocks.GRASS_BLOCK.defaultBlockState()};
        assertFalse(airSec.paper$installBulkColdBacking(gpu2Bit, tickingPal, (short) 10, (short) 0, (short) 0));
    }

    @Test
    void modeABlockSectionBackingDirectReadsCountsWireAndCompactionWorkEndToEnd() {
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState dirt = Blocks.DIRT.defaultBlockState();
        final BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        final BlockState sapling = Blocks.OAK_SAPLING.defaultBlockState();

        final int airId = Block.getId(air);
        final int stoneId = Block.getId(stone);
        final int dirtId = Block.getId(dirt);
        final int grassId = Block.getId(grass);

        final int[] voxels = new int[4096];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    final int yzx = (y << 8) | (z << 4) | x;
                    if (y < 8) {
                        voxels[yzx] = stoneId;
                    } else if (y < 11) {
                        voxels[yzx] = dirtId;
                    } else if (y == 11) {
                        voxels[yzx] = grassId;
                    } else {
                        voxels[yzx] = airId;
                    }
                }
            }
        }

        final GoldLeafModeABlockSectionBacking modeA =
            GoldLeafModeABlockSectionBacking.fromRuntimeVoxels(voxels, "minecraft:overworld", Block::stateById, Block::getId);
        assertNotNull(modeA);
        assertEquals("minecraft:overworld", modeA.worldId());
        assertTrue(modeA.baseSection().payloadByteLength() < 128,
            "Layered Mode A section should stay ultra-compact in memory: " + modeA.baseSection().payloadByteLength());

        final Strategy<BlockState> strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        final PalettedContainer<BlockState> expected = new PalettedContainer<>(air, strategy);
        for (int i = 0; i < 4096; i++) {
            expected.set(i, Block.stateById(voxels[i]));
        }
        final PalettedContainer<BlockState> actual = new PalettedContainer<>(air, strategy);
        actual.paper$attachColdBacking(modeA, air);

        // 1. Voxel read parity across all 4,096 voxels
        for (int i = 0; i < 4096; i++) {
            assertEquals(expected.get(i), actual.get(i), "Mode A read mismatch at " + i);
        }

        // 2. LevelChunkSection#recalcBlockCounts() parity (count + tickingBlocks)
        final LevelChunkSection expSec = new LevelChunkSection(expected, null);
        final LevelChunkSection actSec = new LevelChunkSection(actual, null);
        assertEquals(expSec.hasOnlyAir(), actSec.hasOnlyAir());
        assertEquals(expSec.isRandomlyTickingBlocks(), actSec.isRandomlyTickingBlocks());
        assertEquals(expSec.moonrise$getTickingBlockList().size(), actSec.moonrise$getTickingBlockList().size());

        // 3. Vanilla wire serialization parity (clean)
        final FriendlyByteBuf wireBuf = new FriendlyByteBuf(Unpooled.buffer());
        actual.write(wireBuf);
        assertEquals(wireBuf.readableBytes(), actual.getSerializedSize());
        final PalettedContainer<BlockState> decoded = new PalettedContainer<>(air, strategy);
        decoded.read(wireBuf);
        for (int i = 0; i < 4096; i++) {
            assertEquals(expected.get(i), decoded.get(i), "Clean wire mismatch at " + i);
        }

        // 4. Sparse dirty edit + count + wire + off-tick compaction
        expected.set(3, 12, 5, sapling);
        actual.set(3, 12, 5, sapling);
        assertEquals(1, modeA.dirtyCount());
        assertEquals(sapling, actual.get(3, 12, 5));

        final java.util.Map<BlockState, Integer> expCounts = new java.util.HashMap<>();
        final java.util.Map<BlockState, Integer> actCounts = new java.util.HashMap<>();
        expected.count((st, cnt) -> expCounts.merge(st, cnt, Integer::sum));
        actual.count((st, cnt) -> actCounts.merge(st, cnt, Integer::sum));
        assertEquals(expCounts, actCounts);

        final GoldLeafModeABlockSectionBacking.CompactionResult compacted = modeA.compactDirtyOffThread();
        assertNotNull(compacted);
        assertNotNull(compacted.compactedBacking());
        assertEquals(0, compacted.compactedBacking().dirtyCount());
        assertEquals(sapling, compacted.compactedBacking().get(3, 12, 5));
        assertEquals(grass, compacted.compactedBacking().get(0, 11, 0));
    }

    @Test
    void directPayloadLightCorrectHonorsResidualIsLightOnFalseForStarlightChunks() {
        final int starlightVersion = ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.STARLIGHT_LIGHT_VERSION;
        final io.github.dailystruggle.anvil.LcaDecodedChunkMeta starlightResidualMeta =
            new io.github.dailystruggle.anvil.LcaDecodedChunkMeta(
                4786, 0, 0, -4, "minecraft:full", 0L, 0L,
                64, // hasStarlightVersion=true, hasIsLightOn=false (isLightOn=0 lives in residual NBT)
                0, starlightVersion,
                java.util.List.of(),
                new int[0], new String[0], new long[0][],
                new io.github.dailystruggle.anvil.LcaDecodedChunkMeta.DecodedSectionMeta[0],
                new byte[]{1}
            );

        final net.minecraft.nbt.CompoundTag residualWithIsLightOnFalse = new net.minecraft.nbt.CompoundTag();
        residualWithIsLightOnFalse.putBoolean("isLightOn", false);
        assertTrue(
            LcaDirectSectionSerializer.isDirectPayloadLightCorrect(
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
                starlightResidualMeta,
                residualWithIsLightOnFalse
            ),
            "Starlight-lit .lca chunk with isLightOn=0 in residual NBT must report lightCorrect=true"
        );

        final net.minecraft.nbt.CompoundTag residualWithoutIsLightOn = new net.minecraft.nbt.CompoundTag();
        assertFalse(
            LcaDirectSectionSerializer.isDirectPayloadLightCorrect(
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
                starlightResidualMeta,
                residualWithoutIsLightOn
            ),
            "Chunk missing isLightOn in both bpFlags and residual NBT must report lightCorrect=false"
        );

        assertFalse(
            LcaDirectSectionSerializer.isDirectPayloadLightCorrect(
                net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES,
                starlightResidualMeta,
                residualWithIsLightOnFalse
            ),
            "Chunk before ChunkStatus.LIGHT must report lightCorrect=false"
        );

        final io.github.dailystruggle.anvil.LcaDecodedChunkMeta bpFlagLightOnMeta =
            new io.github.dailystruggle.anvil.LcaDecodedChunkMeta(
                4786, 0, 0, -4, "minecraft:full", 0L, 0L,
                64 | 8, // hasStarlightVersion=true, hasIsLightOn=true
                0, starlightVersion,
                java.util.List.of(),
                new int[0], new String[0], new long[0][],
                new io.github.dailystruggle.anvil.LcaDecodedChunkMeta.DecodedSectionMeta[0],
                null
            );
        assertTrue(
            LcaDirectSectionSerializer.isDirectPayloadLightCorrect(
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
                bpFlagLightOnMeta,
                null
            ),
            "Chunk with bpFlags isLightOn=1 and matching Starlight version must report lightCorrect=true"
        );
    }

    @Test
    void directCoWCommittedSentinelStampsCurrentDataVersionAndStorageDefaultsMatchMcaCloseSync(
        @org.junit.jupiter.api.io.TempDir final java.nio.file.Path tempDir
    ) throws Exception {
        final net.minecraft.nbt.CompoundTag sentinel =
            LcaDirectSectionSerializer.createDirectCoWCommittedSentinel("minecraft:overworld", 144, 139);
        final int currentVersion = net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
        assertTrue(sentinel.getBooleanOr(io.github.dailystruggle.anvil.LcaRegionFileStorage.DIRECT_COW_COMMITTED_TAG, false));
        assertEquals(currentVersion, net.minecraft.nbt.NbtUtils.getDataVersion(sentinel, -1),
            "CoW sentinel must carry current DataVersion so SimpleRegionStorage#upgradeChunkTag bypasses V2842");
        assertEquals(144, sentinel.getIntOr("xPos", -1));
        assertEquals(139, sentinel.getIntOr("zPos", -1));
        assertEquals("minecraft:overworld", sentinel.getStringOr(LcaDirectSectionSerializer.DIRECT_COW_WORLD_TAG, ""));

        final java.nio.file.Path regionDir = tempDir.resolve("region");
        java.nio.file.Files.createDirectories(regionDir);
        final GoldLeafConfig.Storage storageCfg = GoldLeafConfig.defaults().storage;
        final String prevProp = System.getProperty("leafanvil.fsync.onClose");
        try {
            System.clearProperty("leafanvil.fsync.onClose");
            try (LcaRegionStorageProvider provider = LcaRegionStorageProvider.open(regionDir, storageCfg, "minecraft:overworld")) {
                assertFalse(provider.delegate().isForceSyncOnClose(),
                    "GoldLeaf LcaRegionStorageProvider must default to forceSyncOnClose=false to match vanilla .mca");
            }
            System.setProperty("leafanvil.fsync.onClose", "true");
            try (LcaRegionStorageProvider provider = LcaRegionStorageProvider.open(regionDir, storageCfg, "minecraft:overworld")) {
                assertTrue(provider.delegate().isForceSyncOnClose(),
                    "GoldLeaf LcaRegionStorageProvider must honor -Dleafanvil.fsync.onClose=true");
            }
        } finally {
            if (prevProp == null) {
                System.clearProperty("leafanvil.fsync.onClose");
            } else {
                System.setProperty("leafanvil.fsync.onClose", prevProp);
            }
        }
    }

    @Test
    void palettedContainerCopyPreservesColdBackingsWithIsolationAndNonRecursivePack() {
        final Strategy<BlockState> strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();
        final BlockState dirt = Blocks.DIRT.defaultBlockState();
        final BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        final BlockState oakPlanks = Blocks.OAK_PLANKS.defaultBlockState();

        // 1. GoldLeafModeABlockSectionBacking: clean copy preserves cold backing + worldId, isolates future edits, pack() non-recursive
        final int[] voxels = new int[4096];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    final int pos12 = (y << 8) | (z << 4) | x;
                    voxels[pos12] = Block.getId(y < 8 ? stone : (y == 8 ? grass : air));
                }
            }
        }
        final GoldLeafModeABlockSectionBacking modeA =
            GoldLeafModeABlockSectionBacking.fromRuntimeVoxels(voxels, "minecraft:overworld", Block::stateById, Block::getId);
        assertNotNull(modeA);
        final PalettedContainer<BlockState> modeAContainer = new PalettedContainer<>(air, strategy);
        modeAContainer.paper$attachColdBacking(modeA, modeA.uniformFallback());

        final PalettedContainer<BlockState> cleanCopy = modeAContainer.copy();
        org.junit.jupiter.api.Assertions.assertInstanceOf(GoldLeafModeABlockSectionBacking.class, cleanCopy.paper$getColdBacking());
        org.junit.jupiter.api.Assertions.assertNotSame(modeA, cleanCopy.paper$getColdBacking());
        assertEquals("minecraft:overworld", ((GoldLeafModeABlockSectionBacking) cleanCopy.paper$getColdBacking()).worldId());
        assertEquals(0, cleanCopy.paper$getColdBacking().dirtyCount());

        // Mutating original after copy() must NOT leak into cleanCopy
        modeAContainer.set(1, 9, 2, dirt);
        assertEquals(dirt, modeAContainer.get(1, 9, 2));
        assertEquals(air, cleanCopy.get(1, 9, 2));
        assertEquals(0, cleanCopy.paper$getColdBacking().dirtyCount());

        // Dirty copy preserves dirty edits and isolates further edits
        final PalettedContainer<BlockState> dirtyCopy = modeAContainer.copy();
        org.junit.jupiter.api.Assertions.assertInstanceOf(GoldLeafModeABlockSectionBacking.class, dirtyCopy.paper$getColdBacking());
        assertEquals(1, dirtyCopy.paper$getColdBacking().dirtyCount());
        assertEquals(dirt, dirtyCopy.get(1, 9, 2));
        modeAContainer.set(1, 9, 2, oakPlanks);
        assertEquals(oakPlanks, modeAContainer.get(1, 9, 2));
        assertEquals(dirt, dirtyCopy.get(1, 9, 2));

        // pack() must materialize ephemerally and not infinitely recurse
        final var packedModeA = dirtyCopy.pack(strategy);
        assertNotNull(packedModeA);
        assertTrue(packedModeA.paletteEntries().contains(dirt));
        assertTrue(packedModeA.paletteEntries().contains(stone));

        // 2. GoldLeafFlatBlockSectionBacking: clean + dirty snapshot isolation + pack() non-recursive
        final int[] flatWords = new int[256];
        for (int i = 0; i < 256; i++) {
            flatWords[i] = 0x5555_5555; // all ordinal 1 (stone)
        }
        flatWords[0] = 0x5555_5554; // voxel 0 -> ordinal 0 (air)
        final GoldLeafFlatBlockSectionBacking flat = GoldLeafFlatBlockSectionBacking.fromPacked2BitWords(
            flatWords,
            new BlockState[]{air, stone},
            "minecraft:the_nether"
        );
        final PalettedContainer<BlockState> flatContainer = new PalettedContainer<>(air, strategy);
        flatContainer.paper$attachColdBacking(flat, flat.uniformFallback());

        final PalettedContainer<BlockState> flatCopy = flatContainer.copy();
        org.junit.jupiter.api.Assertions.assertInstanceOf(GoldLeafFlatBlockSectionBacking.class, flatCopy.paper$getColdBacking());
        org.junit.jupiter.api.Assertions.assertNotSame(flat, flatCopy.paper$getColdBacking());
        assertEquals("minecraft:the_nether", ((GoldLeafFlatBlockSectionBacking) flatCopy.paper$getColdBacking()).worldId());
        assertEquals(air, flatCopy.get(0, 0, 0));
        assertEquals(stone, flatCopy.get(1, 0, 0));

        flatContainer.set(0, 0, 0, dirt);
        assertEquals(dirt, flatContainer.get(0, 0, 0));
        assertEquals(air, flatCopy.get(0, 0, 0));

        final PalettedContainer<BlockState> flatDirtyCopy = flatContainer.copy();
        assertEquals(1, flatDirtyCopy.paper$getColdBacking().dirtyCount());
        assertEquals(dirt, flatDirtyCopy.get(0, 0, 0));
        flatContainer.set(0, 0, 0, oakPlanks);
        assertEquals(dirt, flatDirtyCopy.get(0, 0, 0));

        final var packedFlat = flatDirtyCopy.pack(strategy);
        assertNotNull(packedFlat);
        assertTrue(packedFlat.paletteEntries().contains(dirt));
        assertTrue(packedFlat.paletteEntries().contains(stone));
    }

    @Test
    void compoundTagCopyPreservesDirectLoadAttachmentForMultiWaiterReads() {
        final net.minecraft.nbt.CompoundTag original = new net.minecraft.nbt.CompoundTag();
        original.putInt("xPos", 129);
        original.putInt("zPos", 150);
        final Object markerAttachment = new Object();
        original.paper$setDirectLoadAttachment(markerAttachment);

        final net.minecraft.nbt.CompoundTag copied = original.copy();
        org.junit.jupiter.api.Assertions.assertSame(
            markerAttachment,
            copied.paper$getDirectLoadAttachment(),
            "CompoundTag#copy() must preserve paper$directLoadAttachment for MoonriseRegionFileIO multi-waiter reads"
        );
        copied.paper$setDirectLoadAttachment(null);
        org.junit.jupiter.api.Assertions.assertSame(
            markerAttachment,
            original.paper$getDirectLoadAttachment(),
            "Clearing attachment on a copied tag must not clear the original tag"
        );
    }
}
