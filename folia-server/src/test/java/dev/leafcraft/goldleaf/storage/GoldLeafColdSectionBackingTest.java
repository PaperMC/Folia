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
        assertTrue(GoldLeafConfig.defaults().storage.lcaCompactGeneratedChunks);

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
}
