package dev.leafcraft.goldleaf.worldgen;

import java.nio.ByteBuffer;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Parallel CPU writeback applier for {@link CompactVanillaSurfaceWritebackBuffer} outputs produced
 * by the fused vanilla-exact GPU {@code fill_vanilla_surface_batch_u8} kernel.
 *
 * <p>Hydrates each {@link LevelChunkSection} from the {@code uint8} block palette and updates
 * {@code WORLD_SURFACE_WG} and {@code OCEAN_FLOOR_WG} heightmaps.</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017
 * @trace S-002
 */
public final class CompactVanillaSurfaceWritebackApplier {

    private static final class ApplyScratch {
        final int[] surfPendingRow = new int[16];
        final int[] oceanPendingRow = new int[16];
        final java.util.ArrayList<BlockState> localPalette = new java.util.ArrayList<>(16);
        final boolean[] localSurfOpaque = new boolean[16];
        final boolean[] localOceanOpaque = new boolean[16];
        byte[] sectionVoxels;
        int[] globalToLocal;
    }

    private static final ThreadLocal<ApplyScratch> APPLY_SCRATCH = ThreadLocal.withInitial(ApplyScratch::new);

    private static final class PaletteMeta {
        final BlockState[] palette;
        final boolean[] surfOpaque;
        final boolean[] oceanOpaque;
        final boolean[] motionDiff;
        final boolean[] isFluidArr;
        final boolean[] isTickingFluidArr;

        PaletteMeta(final BlockState[] palette) {
            this.palette = palette;
            final int len = palette.length;
            this.surfOpaque = new boolean[len];
            this.oceanOpaque = new boolean[len];
            this.motionDiff = new boolean[len];
            this.isFluidArr = new boolean[len];
            this.isTickingFluidArr = new boolean[len];
            final var surfPred = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
            final var oceanPred = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
            final var mbPred = Heightmap.Types.MOTION_BLOCKING.isOpaque();
            final var mbNoLeavesPred = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque();
            for (int i = 1; i < len; i++) {
                final BlockState st = palette[i];
                if (!st.isAir()) {
                    final boolean surf = surfPred.test(st);
                    this.surfOpaque[i] = surf;
                    this.oceanOpaque[i] = oceanPred.test(st);
                    this.motionDiff[i] = (mbPred.test(st) != surf) || (mbNoLeavesPred.test(st) != surf);
                    final var fs = st.getFluidState();
                    if (!fs.isEmpty()) {
                        this.isFluidArr[i] = true;
                        this.isTickingFluidArr[i] = fs.isRandomlyTicking();
                    }
                }
            }
        }
    }

    private static volatile PaletteMeta cachedPaletteMeta;

    private static PaletteMeta getPaletteMeta(final BlockState[] blockPalette) {
        final PaletteMeta cached = cachedPaletteMeta;
        if (cached != null && cached.palette == blockPalette) {
            return cached;
        }
        final PaletteMeta fresh = new PaletteMeta(blockPalette);
        cachedPaletteMeta = fresh;
        return fresh;
    }

    private CompactVanillaSurfaceWritebackApplier() {
    }

    public static void applyToChunk(
        final ChunkAccess chunk,
        final CompactVanillaSurfaceWritebackBuffer buffer,
        final int batchIndex,
        final int bottomSectionIndex,
        final int sectionCount,
        final BlockState[] blockPalette
    ) {
        applyToChunk(chunk, buffer, batchIndex, bottomSectionIndex, sectionCount, blockPalette, false);
    }

    public static void applyToChunk(
        final ChunkAccess chunk,
        final CompactVanillaSurfaceWritebackBuffer buffer,
        final int batchIndex,
        final int bottomSectionIndex,
        final int sectionCount,
        final BlockState[] blockPalette,
        final boolean primePostTerrainHeightmaps
    ) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(blockPalette, "blockPalette");

        final Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        final Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        final ByteBuffer voxelsBuf = buffer.voxelsDirectBuffer();
        final java.nio.LongBuffer voxelsLongBuf = voxelsBuf.asLongBuffer();
        final int baseSection = batchIndex * sectionCount;
        final ApplyScratch scratch = APPLY_SCRATCH.get();
        byte[] sectionVoxels = scratch.sectionVoxels;
        int[] globalToLocal = scratch.globalToLocal;

        final PaletteMeta meta = getPaletteMeta(blockPalette);
        final int palLen = blockPalette.length;
        final boolean[] surfOpaque = meta.surfOpaque;
        final boolean[] oceanOpaque = meta.oceanOpaque;
        final boolean[] motionDiff = meta.motionDiff;
        final boolean[] isFluidArr = meta.isFluidArr;
        final boolean[] isTickingFluidArr = meta.isTickingFluidArr;
        boolean chunkHasMotionDiff = false;
        final int[] surfPendingRow = scratch.surfPendingRow;
        final int[] oceanPendingRow = scratch.oceanPendingRow;
        int surfRemaining;
        int oceanRemaining;
        if (buffer.hasGpuHeightmaps() && palLen <= 64) {
            final ByteBuffer hmBuf = buffer.heightmapsDirectBuffer();
            final int hmOff = batchIndex * CompactVanillaSurfaceWritebackBuffer.HEIGHTMAP_BYTES_PER_CHUNK;
            worldSurface.goldleaf$installFromGpuRelHeights(hmBuf, hmOff);
            oceanFloor.goldleaf$installFromGpuRelHeights(hmBuf, hmOff + 512);
            surfRemaining = 0;
            oceanRemaining = 0;
        } else {
            java.util.Arrays.fill(surfPendingRow, 0xFFFF);
            java.util.Arrays.fill(oceanPendingRow, 0xFFFF);
            surfRemaining = 256;
            oceanRemaining = 256;
        }
        final java.util.ArrayList<BlockState> localPalette = scratch.localPalette;
        final boolean[] localSurfOpaque = scratch.localSurfOpaque;
        final boolean[] localOceanOpaque = scratch.localOceanOpaque;
        final boolean useDirectFlatColdBacking = isDirectFlatColdBackingEnabled();
        final dev.leafcraft.goldleaf.storage.WaveChunkArena waveArena = dev.leafcraft.goldleaf.storage.WaveChunkArena.global();
        final int waveSlotId = (useDirectFlatColdBacking && !waveArena.shouldDetachLongLived())
            ? waveArena.acquireForGpuGen(
                ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(chunk.getPos().x(), chunk.getPos().z())
            )
            : -1;
        boolean waveSlotUsed = false;

        for (int s = sectionCount - 1; s >= 0; s--) {
            final int globalSection = baseSection + s;
            final int nonEmpty = buffer.readNonEmptyCount(globalSection);
            if (nonEmpty == 0) {
                continue;
            }
            final LevelChunkSection section = chunk.getSection(bottomSectionIndex + s);
            final int sectionMinY = chunk.getSectionYFromSectionIndex(bottomSectionIndex + s) << 4;
            final int sectionMode = buffer.readSectionMode(globalSection);

            if (sectionMode == CompactVanillaSurfaceWritebackBuffer.MODE_UNIFORM) {
                final int code = buffer.readUniformCode(globalSection);
                if (code == 0 || code >= palLen) {
                    continue;
                }
                final BlockState state = blockPalette[code];
                if (state.isAir()) {
                    continue;
                }
                final boolean isFluid = isFluidArr[code];
                if (motionDiff[code]) {
                    chunkHasMotionDiff = true;
                }
                final short nonEmptyCnt = (short) 4096;
                final short fluidCnt = isFluid ? (short) 4096 : (short) 0;
                final short tickingFluidCnt = isTickingFluidArr[code] ? (short) 4096 : (short) 0;
                if (!section.paper$installUniformBlockSection(state, nonEmptyCnt, fluidCnt, tickingFluidCnt)) {
                    for (int ly = 15; ly >= 0; ly--) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                section.setBlockState(lx, ly, lz, state, false);
                            }
                        }
                    }
                }
                final int topHeight = sectionMinY + 16;
                if (surfRemaining > 0 && surfOpaque[code]) {
                    for (int lz = 0; lz < 16 && surfRemaining > 0; lz++) {
                        int p = surfPendingRow[lz];
                        while (p != 0) {
                            final int lx = Integer.numberOfTrailingZeros(p);
                            p ^= (1 << lx);
                            worldSurface.paper$setHeightBulk(lx, lz, topHeight);
                            surfRemaining--;
                        }
                        surfPendingRow[lz] = 0;
                    }
                }
                if (oceanRemaining > 0 && oceanOpaque[code]) {
                    for (int lz = 0; lz < 16 && oceanRemaining > 0; lz++) {
                        int p = oceanPendingRow[lz];
                        while (p != 0) {
                            final int lx = Integer.numberOfTrailingZeros(p);
                            p ^= (1 << lx);
                            oceanFloor.paper$setHeightBulk(lx, lz, topHeight);
                            oceanRemaining--;
                        }
                        oceanPendingRow[lz] = 0;
                    }
                }
                continue;
            }

            final int byteOffset = globalSection * CompactVanillaSurfaceWritebackBuffer.VOXEL_BYTES_PER_SECTION;
            final int fluidCount = buffer.readFluidCount(globalSection);

            if (sectionMode == CompactVanillaSurfaceWritebackBuffer.MODE_PACKED_4BIT) {
                // Zero-voxel-loop GPU-compacted 1/2/3/4-bit Mode A section install
                final int longBase = globalSection << 9;
                final long usedMask = voxelsLongBuf.get(longBase + 256);
                final int tickingFluidCnt = Short.toUnsignedInt(voxelsBuf.getShort(byteOffset + 2056));
                final int rawFlatBits = Byte.toUnsignedInt(voxelsBuf.get(byteOffset + 2058));
                final int flatBits = (rawFlatBits >= 1 && rawFlatBits <= 4) ? rawFlatBits : 4;
                final long octreeMask64 = voxelsBuf.getLong(byteOffset + 2064);
                localPalette.clear();
                long m = usedMask;
                while (m != 0L) {
                    final int code = Long.numberOfTrailingZeros(m);
                    m &= m - 1L;
                    final int localIdx = localPalette.size();
                    localPalette.add(code < palLen ? blockPalette[code] : blockPalette[0]);
                    localSurfOpaque[localIdx] = code < palLen && surfOpaque[code];
                    localOceanOpaque[localIdx] = code < palLen && oceanOpaque[code];
                    if (code < palLen && motionDiff[code]) {
                        chunkHasMotionDiff = true;
                    }
                }
                final int localPalSize = localPalette.size();
                if (useDirectFlatColdBacking && localPalSize >= 2 && localPalSize <= 16 && surfRemaining == 0 && oceanRemaining == 0) {
                    final BlockState[] flatPalette = localPalette.toArray(BlockState[]::new);
                    final int flatByteLen = (4096 * flatBits) >>> 3;
                    final byte[] flatBytes = (waveSlotId >= 0 && s < waveArena.sectionsPerSlot())
                        ? waveArena.sectionFlatBuffer(waveSlotId, s)
                        : new byte[flatByteLen];
                    voxelsBuf.get(byteOffset, flatBytes, 0, flatByteLen);
                    int[] preResolvedDictIds = null;
                    final int firstDictId = voxelsBuf.getInt(byteOffset + 2072);
                    if (firstDictId >= 0) {
                        preResolvedDictIds = new int[localPalSize];
                        preResolvedDictIds[0] = firstDictId;
                        boolean allValid = true;
                        for (int k = 1; k < localPalSize; k++) {
                            final int did = voxelsBuf.getInt(byteOffset + 2072 + (k << 2));
                            if (did < 0) {
                                allValid = false;
                                break;
                            }
                            preResolvedDictIds[k] = did;
                        }
                        if (!allValid) {
                            preResolvedDictIds = null;
                        }
                    }
                    final dev.leafcraft.goldleaf.storage.GoldLeafFlatBlockSectionBacking flatBacking =
                        dev.leafcraft.goldleaf.storage.GoldLeafFlatBlockSectionBacking.fromGpuPackedFlatBytes(
                            flatBytes,
                            flatBits,
                            flatPalette,
                            octreeMask64,
                            preResolvedDictIds,
                            null,
                            (waveSlotId >= 0 && s < waveArena.sectionsPerSlot()) ? waveSlotId : -1
                        );
                    if (section.paper$installBulkColdBacking(
                        flatBacking,
                        flatPalette,
                        (short) nonEmpty,
                        (short) fluidCount,
                        (short) tickingFluidCnt
                    )) {
                        if (waveSlotId >= 0 && s < waveArena.sectionsPerSlot()) {
                            waveSlotUsed = true;
                        }
                        continue;
                    }
                }
                final long[] packed4Bit = new long[256];
                if (flatBits == 4) {
                    voxelsLongBuf.get(longBase, packed4Bit, 0, 256);
                } else {
                    final int flatByteLen = (4096 * flatBits) >>> 3;
                    final byte[] tmpFlat = new byte[flatByteLen];
                    voxelsBuf.get(byteOffset, tmpFlat, 0, flatByteLen);
                    final int bitMask = (1 << flatBits) - 1;
                    for (int row = 0; row < 256; row++) {
                        long row4 = 0L;
                        final int rowBitBase = (row << 4) * flatBits;
                        for (int px = 0; px < 16; px++) {
                            final int bitPos = rowBitBase + px * flatBits;
                            final int byteIdx = bitPos >>> 3;
                            final int shift = bitPos & 7;
                            int raw = Byte.toUnsignedInt(tmpFlat[byteIdx]) >>> shift;
                            if (shift + flatBits > 8 && byteIdx + 1 < flatByteLen) {
                                raw |= Byte.toUnsignedInt(tmpFlat[byteIdx + 1]) << (8 - shift);
                            }
                            row4 |= ((long) (raw & bitMask)) << (px << 2);
                        }
                        packed4Bit[row] = row4;
                    }
                }
                if (!section.paper$installBulk4BitSection(
                    localPalette,
                    packed4Bit,
                    (short) nonEmpty,
                    (short) fluidCount,
                    (short) tickingFluidCnt
                )) {
                    for (int ly = 15; ly >= 0; ly--) {
                        for (int lz = 0; lz < 16; lz++) {
                            long word = packed4Bit[(ly << 4) | lz];
                            for (int lx = 0; lx < 16; lx++, word >>>= 4) {
                                final BlockState st = localPalette.get((int) (word & 0xFL));
                                if (!st.isAir()) {
                                    section.setBlockState(lx, ly, lz, st, false);
                                }
                            }
                        }
                    }
                }
                if (surfRemaining > 0 || oceanRemaining > 0) {
                    for (int ly = 15; ly >= 0 && (surfRemaining > 0 || oceanRemaining > 0); ly--) {
                        final int yPlusOne = sectionMinY + ly + 1;
                        final int yRowBase = ly << 4;
                        for (int lz = 0; lz < 16; lz++) {
                            int sPending = surfPendingRow[lz];
                            int oPending = oceanPendingRow[lz];
                            int active = sPending | oPending;
                            if (active == 0) {
                                continue;
                            }
                            final long word = packed4Bit[yRowBase | lz];
                            while (active != 0) {
                                final int lx = Integer.numberOfTrailingZeros(active);
                                final int bit = 1 << lx;
                                active ^= bit;
                                final int lid = (int) ((word >>> (lx << 2)) & 0xFL);
                                if ((sPending & bit) != 0 && localSurfOpaque[lid]) {
                                    worldSurface.paper$setHeightBulk(lx, lz, yPlusOne);
                                    sPending ^= bit;
                                    surfRemaining--;
                                }
                                if ((oPending & bit) != 0 && localOceanOpaque[lid]) {
                                    oceanFloor.paper$setHeightBulk(lx, lz, yPlusOne);
                                    oPending ^= bit;
                                    oceanRemaining--;
                                }
                            }
                            surfPendingRow[lz] = sPending;
                            oceanPendingRow[lz] = oPending;
                        }
                    }
                }
                continue;
            }

            if (sectionVoxels == null) {
                sectionVoxels = new byte[CompactVanillaSurfaceWritebackBuffer.VOXEL_BYTES_PER_SECTION];
                globalToLocal = new int[256];
                scratch.sectionVoxels = sectionVoxels;
                scratch.globalToLocal = globalToLocal;
            }
            voxelsBuf.get(byteOffset, sectionVoxels);
            final boolean hasFluids = fluidCount > 0;

            // Single-pass fused localPalette + 4-bit packing + tickingFluid count
            java.util.Arrays.fill(globalToLocal, -1);
            localPalette.clear();
            boolean fits4Bit = true;
            int tickingFluidCnt = 0;
            final long[] packed4Bit = new long[256];
            for (int yz = 0; yz < 256; yz++) {
                final int base = yz << 4;
                long word = 0L;
                for (int lx = 0; lx < 16; lx++) {
                    final int code = sectionVoxels[base | lx] & 0xFF;
                    int localId = globalToLocal[code];
                    if (localId < 0) {
                        localId = localPalette.size();
                        if (localId >= 16) {
                            fits4Bit = false;
                            break;
                        }
                        globalToLocal[code] = localId;
                        localPalette.add(code < palLen ? blockPalette[code] : blockPalette[0]);
                        if (code < palLen && motionDiff[code]) {
                            chunkHasMotionDiff = true;
                        }
                    }
                    word |= ((long) localId) << (lx << 2);
                    if (hasFluids && code < palLen && isTickingFluidArr[code]) {
                        tickingFluidCnt++;
                    }
                }
                if (!fits4Bit) {
                    break;
                }
                packed4Bit[yz] = word;
            }

            boolean bulkInstalled = false;
            if (fits4Bit && !localPalette.isEmpty()) {
                bulkInstalled = section.paper$installBulk4BitSection(
                    localPalette,
                    packed4Bit,
                    (short) nonEmpty,
                    (short) fluidCount,
                    (short) tickingFluidCnt
                );
            }

            if (!bulkInstalled) {
                chunkHasMotionDiff = true;
                for (int ly = 15; ly >= 0; ly--) {
                    final int yBase = ly << 8;
                    for (int lz = 0; lz < 16; lz++) {
                        final int yzBase = yBase | (lz << 4);
                        for (int lx = 0; lx < 16; lx++) {
                            final int code = sectionVoxels[yzBase | lx] & 0xFF;
                            if (code > 0 && code < palLen) {
                                final BlockState st = blockPalette[code];
                                if (!st.isAir()) {
                                    section.setBlockState(lx, ly, lz, st, false);
                                }
                            }
                        }
                    }
                }
            }

            // Fast bitmask top-down heightmap resolution (O(1) per column, exits immediately once resolved!)
            if (surfRemaining > 0 || oceanRemaining > 0) {
                for (int ly = 15; ly >= 0 && (surfRemaining > 0 || oceanRemaining > 0); ly--) {
                    final int yPlusOne = sectionMinY + ly + 1;
                    final int yBase = ly << 8;
                    for (int lz = 0; lz < 16; lz++) {
                        int sPending = surfPendingRow[lz];
                        int oPending = oceanPendingRow[lz];
                        int active = sPending | oPending;
                        if (active == 0) {
                            continue;
                        }
                        final int yzBase = yBase | (lz << 4);
                        while (active != 0) {
                            final int lx = Integer.numberOfTrailingZeros(active);
                            final int bit = 1 << lx;
                            active ^= bit;
                            final int code = sectionVoxels[yzBase | lx] & 0xFF;
                            if (code == 0 || code >= palLen) {
                                continue;
                            }
                            if ((sPending & bit) != 0 && surfOpaque[code]) {
                                worldSurface.paper$setHeightBulk(lx, lz, yPlusOne);
                                sPending ^= bit;
                                surfRemaining--;
                            }
                            if ((oPending & bit) != 0 && oceanOpaque[code]) {
                                oceanFloor.paper$setHeightBulk(lx, lz, yPlusOne);
                                oPending ^= bit;
                                oceanRemaining--;
                            }
                        }
                        surfPendingRow[lz] = sPending;
                        oceanPendingRow[lz] = oPending;
                    }
                }
            }
        }

        if (waveSlotId >= 0) {
            if (waveSlotUsed) {
                waveArena.markLive(waveSlotId);
            } else {
                waveArena.markUnloaded(waveSlotId);
            }
        }

        if (primePostTerrainHeightmaps) {
            chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).goldleaf$copyFrom(worldSurface);
            chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR).goldleaf$copyFrom(oceanFloor);
            if (!chunkHasMotionDiff) {
                chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING).goldleaf$copyFrom(worldSurface);
                chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES).goldleaf$copyFrom(worldSurface);
            }
        }
    }

    private static boolean isDirectFlatColdBackingEnabled() {
        final dev.leafcraft.goldleaf.config.GoldLeafConfig cfg = dev.leafcraft.goldleaf.config.GoldLeafConfig.get();
        if (cfg == null || cfg.storage == null || !cfg.storage.lcaNativeSectionBacking) {
            return false;
        }
        return !("mode-b".equalsIgnoreCase(cfg.storage.lcaMode)
            || "archival".equalsIgnoreCase(cfg.storage.lcaMode)
            || cfg.storage.lcaModeBLcd2);
    }
}
