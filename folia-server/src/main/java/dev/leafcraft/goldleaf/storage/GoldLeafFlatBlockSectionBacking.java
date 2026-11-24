package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.spi.ColdSectionBackingSpi;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.worldgen.CompactSectionWritebackApplier;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/**
 * Unified Disk = Memory = GPU {@link ColdSectionBackingSpi} backed directly by
 * {@code LCA-ICD-3.0} {@code BLOCK_MODE_FLAT_BITPACK} ({@code mode 10}, little-endian YZX
 * {@code 1/2/3/4+}-bit packed) bytes plus a lazily allocated O(1) 4-byte sparse dirty overlay
 * ({@code goldleaf-ADR-005}, {@code goldleaf-ADR-017} Phase D).
 *
 * <p>Eliminates all intermediate decode/re-encode passes between disk, memory, and GPU DMA:
 * {@code 1}-bit sections occupy {@code 512 B}, {@code 2}-bit sections occupy {@code 1,024 B}
 * (byte-for-byte identical to {@code CompactSectionWritebackBuffer}), {@code 3}-bit sections
 * occupy {@code 1,536 B}, and {@code 4}-bit sections occupy {@code 2,048 B} (byte-for-byte
 * identical to {@code CompactSurfaceWritebackBuffer} and {@code SimpleBitStorage(4, 4096)}).</p>
 *
 * @governedBy goldleaf-ADR-005, goldleaf-ADR-017, anvil-api-ADR-003
 * @trace S-004, S-005
 */
public final class GoldLeafFlatBlockSectionBacking implements ColdSectionBackingSpi<BlockState> {

    private static final int SECTION_VOXELS = 4096;
    private static final int STATE_ID_MASK = 0x7_FFFF;
    private static final long EVEN_BITS_MASK = 0x5555_5555_5555_5555L;
    private static final VarHandle LONG_LE_VIEW =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_LE_VIEW =
        MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private final byte[] flatBytes;
    private final int bitsPerVoxel;
    private final int bitMask;
    private final BlockState[] palette;
    private final int[] paletteRuntimeIds;
    private final int hotPromoteDirtyThreshold;
    private final IntFunction<BlockState> stateById;
    private final ToIntFunction<BlockState> idByState;
    private final @Nullable String worldId;
    private final int waveSlotId;
    private final boolean hasPrecomputedOctreeMask;
    private final long precomputedOctreeMask64;
    private final int @Nullable [] preResolvedDictIds;

    // Lazily allocated open-addressed 4-byte overlay: ((pos12 + 1) << 19) | (stateId & 0x7FFFF).
    // Remains null (0 bytes) for 100% of unedited sections.
    private volatile int @Nullable [] dirtyTable;
    private volatile int dirtyCount;

    public record CompactionResult(
        @Nullable GoldLeafFlatBlockSectionBacking compactedBacking,
        BlockState uniformFallback
    ) {
    }

    public GoldLeafFlatBlockSectionBacking(
        final byte[] flatBytes,
        final int bitsPerVoxel,
        final BlockState[] palette,
        final int[] paletteRuntimeIds
    ) {
        this(
            flatBytes,
            bitsPerVoxel,
            palette,
            paletteRuntimeIds,
            resolveConfiguredHotPromoteThreshold(),
            Block::stateById,
            Block::getId,
            null
        );
    }

    public GoldLeafFlatBlockSectionBacking(
        final byte[] flatBytes,
        final int bitsPerVoxel,
        final BlockState[] palette,
        final int[] paletteRuntimeIds,
        final @Nullable String worldId
    ) {
        this(
            flatBytes,
            bitsPerVoxel,
            palette,
            paletteRuntimeIds,
            resolveConfiguredHotPromoteThreshold(),
            Block::stateById,
            Block::getId,
            worldId
        );
    }

    public GoldLeafFlatBlockSectionBacking(
        final byte[] flatBytes,
        final int bitsPerVoxel,
        final BlockState[] palette,
        final int[] paletteRuntimeIds,
        final int hotPromoteDirtyThreshold,
        final IntFunction<BlockState> stateById,
        final ToIntFunction<BlockState> idByState
    ) {
        this(flatBytes, bitsPerVoxel, palette, paletteRuntimeIds, hotPromoteDirtyThreshold, stateById, idByState, null);
    }

    public GoldLeafFlatBlockSectionBacking(
        final byte[] flatBytes,
        final int bitsPerVoxel,
        final BlockState[] palette,
        final int[] paletteRuntimeIds,
        final int hotPromoteDirtyThreshold,
        final IntFunction<BlockState> stateById,
        final ToIntFunction<BlockState> idByState,
        final @Nullable String worldId
    ) {
        this(flatBytes, bitsPerVoxel, palette, paletteRuntimeIds, hotPromoteDirtyThreshold, stateById, idByState, worldId, -1);
    }

    public GoldLeafFlatBlockSectionBacking(
        final byte[] flatBytes,
        final int bitsPerVoxel,
        final BlockState[] palette,
        final int[] paletteRuntimeIds,
        final int hotPromoteDirtyThreshold,
        final IntFunction<BlockState> stateById,
        final ToIntFunction<BlockState> idByState,
        final @Nullable String worldId,
        final int waveSlotId
    ) {
        this(
            flatBytes, bitsPerVoxel, palette, paletteRuntimeIds, hotPromoteDirtyThreshold,
            stateById, idByState, worldId, waveSlotId, false, 0L, null
        );
    }

    public GoldLeafFlatBlockSectionBacking(
        final byte[] flatBytes,
        final int bitsPerVoxel,
        final BlockState[] palette,
        final int[] paletteRuntimeIds,
        final int hotPromoteDirtyThreshold,
        final IntFunction<BlockState> stateById,
        final ToIntFunction<BlockState> idByState,
        final @Nullable String worldId,
        final int waveSlotId,
        final boolean hasPrecomputedOctreeMask,
        final long precomputedOctreeMask64,
        final int @Nullable [] preResolvedDictIds
    ) {
        this.flatBytes = Objects.requireNonNull(flatBytes, "flatBytes");
        if (bitsPerVoxel < 1 || bitsPerVoxel > 12) {
            throw new IllegalArgumentException("bitsPerVoxel out of [1..12]: " + bitsPerVoxel);
        }
        final int expectedBytes = (SECTION_VOXELS * bitsPerVoxel + 7) >>> 3;
        if (flatBytes.length < expectedBytes) {
            throw new IllegalArgumentException(
                "flatBytes length " + flatBytes.length + " < expected " + expectedBytes
            );
        }
        this.bitsPerVoxel = bitsPerVoxel;
        this.bitMask = (1 << bitsPerVoxel) - 1;
        this.palette = Objects.requireNonNull(palette, "palette");
        this.paletteRuntimeIds = Objects.requireNonNull(paletteRuntimeIds, "paletteRuntimeIds");
        if (palette.length < 2 || palette.length != paletteRuntimeIds.length) {
            throw new IllegalArgumentException("palette length must be >= 2 and match paletteRuntimeIds");
        }
        this.hotPromoteDirtyThreshold = Math.max(0, hotPromoteDirtyThreshold);
        this.stateById = Objects.requireNonNull(stateById, "stateById");
        this.idByState = Objects.requireNonNull(idByState, "idByState");
        this.worldId = worldId;
        this.waveSlotId = waveSlotId;
        this.hasPrecomputedOctreeMask = hasPrecomputedOctreeMask;
        this.precomputedOctreeMask64 = precomputedOctreeMask64;
        this.preResolvedDictIds = preResolvedDictIds;
    }

    public boolean hasPrecomputedOctreeMask() {
        return this.hasPrecomputedOctreeMask;
    }

    public long precomputedOctreeMask64() {
        return this.precomputedOctreeMask64;
    }

    public int @Nullable [] preResolvedDictIds() {
        return this.preResolvedDictIds;
    }

    /**
     * Wraps a zero-decode {@link RawChunkFrameSnapshot.FlatBitPackSection} extracted directly from
     * an {@code .lca} Mode A slab, returning {@code null} if any runtime state ID cannot be resolved.
     */
    public static @Nullable GoldLeafFlatBlockSectionBacking fromExtracted(
        final RawChunkFrameSnapshot.FlatBitPackSection extracted
    ) {
        return fromExtracted(extracted, null);
    }

    public static @Nullable GoldLeafFlatBlockSectionBacking fromExtracted(
        final RawChunkFrameSnapshot.FlatBitPackSection extracted,
        final @Nullable String worldId
    ) {
        if (extracted == null) {
            return null;
        }
        final int[] runtimeIds = extracted.paletteRuntimeIds();
        final BlockState[] states = new BlockState[runtimeIds.length];
        for (int i = 0; i < runtimeIds.length; i++) {
            final BlockState st = Block.stateById(runtimeIds[i]);
            if (st == null) {
                return null;
            }
            states[i] = st;
        }
        return new GoldLeafFlatBlockSectionBacking(
            extracted.flatBytes(),
            extracted.bitsPerVoxel(),
            states,
            runtimeIds,
            worldId
        );
    }

    /**
     * Builds a 2-bit ({@code 1,024 B}) Mode A flat backing directly from GPU
     * {@code CompactSectionWritebackBuffer} little-endian 32-bit YZX row words (zero bit-scatter).
     */
    public static GoldLeafFlatBlockSectionBacking fromPacked2BitWords(
        final int[] words256,
        final BlockState[] palette,
        final @Nullable String worldId
    ) {
        Objects.requireNonNull(words256, "words256");
        Objects.requireNonNull(palette, "palette");
        if (words256.length != 256 || palette.length < 2 || palette.length > 4) {
            throw new IllegalArgumentException("Expected 256 2-bit words and palette size in [2..4]");
        }
        final byte[] flat = new byte[1024];
        for (int w = 0; w < 256; w++) {
            INT_LE_VIEW.set(flat, w << 2, words256[w]);
        }
        final int[] runtimeIds = new int[palette.length];
        for (int i = 0; i < palette.length; i++) {
            runtimeIds[i] = Block.getId(palette[i]);
        }
        return new GoldLeafFlatBlockSectionBacking(flat, 2, palette.clone(), runtimeIds, worldId);
    }

    /**
     * Builds a 4-bit ({@code 2,048 B}) Mode A flat backing directly from GPU
     * {@code CompactSurfaceWritebackBuffer} little-endian 64-bit YZX row longs (zero bit-scatter).
     */
    public static GoldLeafFlatBlockSectionBacking fromPacked4BitLongs(
        final long[] longs256,
        final BlockState[] palette,
        final @Nullable String worldId
    ) {
        Objects.requireNonNull(longs256, "longs256");
        Objects.requireNonNull(palette, "palette");
        if (longs256.length != 256 || palette.length < 2 || palette.length > 16) {
            throw new IllegalArgumentException("Expected 256 4-bit longs and palette size in [2..16]");
        }
        final byte[] flat = new byte[2048];
        for (int w = 0; w < 256; w++) {
            LONG_LE_VIEW.set(flat, w << 3, longs256[w]);
        }
        final int[] runtimeIds = new int[palette.length];
        for (int i = 0; i < palette.length; i++) {
            runtimeIds[i] = Block.getId(palette[i]);
        }
        return new GoldLeafFlatBlockSectionBacking(flat, 4, palette.clone(), runtimeIds, worldId);
    }

    /**
     * Wraps a GPU-compacted {@code 1/2/3/4}-bit YZX Mode A flat section buffer alongside its
     * GPU-computed Hilbert {@code octreeMask64} and DMA-resolved {@code .lca} dictionary IDs.
     */
    public static GoldLeafFlatBlockSectionBacking fromGpuPackedFlatBytes(
        final byte[] flatBytes,
        final int bitsPerVoxel,
        final BlockState[] palette,
        final long octreeMask64,
        final int @Nullable [] preResolvedDictIds,
        final @Nullable String worldId,
        final int waveSlotId
    ) {
        Objects.requireNonNull(flatBytes, "flatBytes");
        Objects.requireNonNull(palette, "palette");
        final int expectedBytes = (SECTION_VOXELS * bitsPerVoxel + 7) >>> 3;
        if (bitsPerVoxel < 1 || bitsPerVoxel > 4 || flatBytes.length < expectedBytes || palette.length < 2 || palette.length > 16) {
            throw new IllegalArgumentException("Invalid GPU packed flat section params: bits=" + bitsPerVoxel + ", pal=" + palette.length);
        }
        final int[] runtimeIds = new int[palette.length];
        for (int i = 0; i < palette.length; i++) {
            runtimeIds[i] = Block.getId(palette[i]);
        }
        return new GoldLeafFlatBlockSectionBacking(
            flatBytes, bitsPerVoxel, palette.clone(), runtimeIds, resolveConfiguredHotPromoteThreshold(),
            Block::stateById, Block::getId, worldId, waveSlotId, true, octreeMask64, preResolvedDictIds
        );
    }

    /**
     * Wraps a caller-provided (e.g. {@link WaveChunkArena}-owned) 2,048-byte 4-bit YZX flat
     * section buffer directly with zero extra array allocation.
     */
    public static GoldLeafFlatBlockSectionBacking fromPacked4BitBytes(
        final byte[] flat2048,
        final BlockState[] palette,
        final @Nullable String worldId,
        final int waveSlotId
    ) {
        Objects.requireNonNull(flat2048, "flat2048");
        Objects.requireNonNull(palette, "palette");
        if (flat2048.length < 2048 || palette.length < 2 || palette.length > 16) {
            throw new IllegalArgumentException("Expected >=2048 flat bytes and palette size in [2..16]");
        }
        final int[] runtimeIds = new int[palette.length];
        for (int i = 0; i < palette.length; i++) {
            runtimeIds[i] = Block.getId(palette[i]);
        }
        return new GoldLeafFlatBlockSectionBacking(
            flat2048, 4, palette.clone(), runtimeIds, resolveConfiguredHotPromoteThreshold(),
            Block::stateById, Block::getId, worldId, waveSlotId
        );
    }

    /**
     * Encodes 4,096 runtime {@code BlockState} IDs directly into a unified {@code BLOCK_MODE_FLAT_BITPACK}
     * backing for off-tick {@code HOT_MUTABLE -> FLAT} or generated-chunk compaction. Returns
     * {@code null} when the section is uniform (caller should install {@code paper$installUniform}).
     */
    public static @Nullable GoldLeafFlatBlockSectionBacking fromRuntimeVoxels(
        final int[] voxels4096,
        final IntFunction<BlockState> stateById,
        final ToIntFunction<BlockState> idByState
    ) {
        return fromRuntimeVoxels(voxels4096, null, stateById, idByState);
    }

    public static @Nullable GoldLeafFlatBlockSectionBacking fromRuntimeVoxels(
        final int[] voxels4096,
        final @Nullable String worldId,
        final IntFunction<BlockState> stateById,
        final ToIntFunction<BlockState> idByState
    ) {
        if (voxels4096 == null || voxels4096.length < SECTION_VOXELS) {
            return null;
        }
        final int first = voxels4096[0];
        boolean uniform = true;
        for (int i = 1; i < SECTION_VOXELS; i++) {
            if (voxels4096[i] != first) {
                uniform = false;
                break;
            }
        }
        if (uniform) {
            return null;
        }
        int[] palIds = new int[16];
        int palSize = 0;
        final int[] ordinals = new int[SECTION_VOXELS];
        for (int i = 0; i < SECTION_VOXELS; i++) {
            final int id = voxels4096[i];
            int ord = -1;
            for (int p = 0; p < palSize; p++) {
                if (palIds[p] == id) {
                    ord = p;
                    break;
                }
            }
            if (ord < 0) {
                if (palSize == palIds.length) {
                    palIds = Arrays.copyOf(palIds, palIds.length << 1);
                }
                ord = palSize;
                palIds[palSize++] = id;
            }
            ordinals[i] = ord;
        }
        if (palSize < 2) {
            return null;
        }
        final int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(palSize - 1));
        final int byteLen = (SECTION_VOXELS * bits + 7) >>> 3;
        final byte[] flatBytes = new byte[byteLen];
        packOrdinalsYzx(ordinals, bits, flatBytes);
        final int[] trimmedIds = Arrays.copyOf(palIds, palSize);
        final BlockState[] states = new BlockState[palSize];
        for (int i = 0; i < palSize; i++) {
            final BlockState st = stateById.apply(trimmedIds[i]);
            if (st == null) {
                return null;
            }
            states[i] = st;
        }
        return new GoldLeafFlatBlockSectionBacking(
            flatBytes, bits, states, trimmedIds, resolveConfiguredHotPromoteThreshold(), stateById, idByState, worldId
        );
    }

    private static void packOrdinalsYzx(final int[] ordinals, final int bits, final byte[] dst) {
        if (bits == 1) {
            for (int i = 0, out = 0; i < SECTION_VOXELS; i += 8, out++) {
                dst[out] = (byte) ((ordinals[i] & 1)
                    | ((ordinals[i + 1] & 1) << 1)
                    | ((ordinals[i + 2] & 1) << 2)
                    | ((ordinals[i + 3] & 1) << 3)
                    | ((ordinals[i + 4] & 1) << 4)
                    | ((ordinals[i + 5] & 1) << 5)
                    | ((ordinals[i + 6] & 1) << 6)
                    | ((ordinals[i + 7] & 1) << 7));
            }
            return;
        }
        if (bits == 2) {
            for (int i = 0, out = 0; i < SECTION_VOXELS; i += 4, out++) {
                dst[out] = (byte) ((ordinals[i] & 3)
                    | ((ordinals[i + 1] & 3) << 2)
                    | ((ordinals[i + 2] & 3) << 4)
                    | ((ordinals[i + 3] & 3) << 6));
            }
            return;
        }
        if (bits == 4) {
            for (int i = 0, out = 0; i < SECTION_VOXELS; i += 2, out++) {
                dst[out] = (byte) ((ordinals[i] & 0xF) | ((ordinals[i + 1] & 0xF) << 4));
            }
            return;
        }
        long acc = 0L;
        int accBits = 0;
        int outPos = 0;
        final long mask = (1L << bits) - 1L;
        for (int i = 0; i < SECTION_VOXELS; i++) {
            acc |= (ordinals[i] & mask) << accBits;
            accBits += bits;
            while (accBits >= 8) {
                dst[outPos++] = (byte) acc;
                acc >>>= 8;
                accBits -= 8;
            }
        }
        if (accBits > 0 && outPos < dst.length) {
            dst[outPos] = (byte) acc;
        }
    }

    private static int resolveConfiguredHotPromoteThreshold() {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        if (cfg != null && cfg.storage != null) {
            return Math.max(0, cfg.storage.lcaHotPromoteDirtyThreshold);
        }
        return 64;
    }

    public byte[] flatBytes() {
        return this.flatBytes;
    }

    public int bitsPerVoxel() {
        return this.bitsPerVoxel;
    }

    public BlockState[] palette() {
        return this.palette;
    }

    public int[] paletteRuntimeIds() {
        return this.paletteRuntimeIds;
    }

    public @Nullable String worldId() {
        return this.worldId;
    }

    public int waveSlotId() {
        return this.waveSlotId;
    }

    /**
     * Exports packed sparse dirty overlay entries {@code ((pos12 + 1) << 19) | (stateId19 & 0x7FFFF)}
     * for {@code LCA_NET_V1} wire streaming.
     */
    public int[] exportDirtyOverlayEntries() {
        final int[] table = this.dirtyTable;
        final int count = this.dirtyCount;
        if (table == null || count <= 0) {
            return new int[0];
        }
        final int[] out = new int[count];
        int written = 0;
        for (final int entry : table) {
            if (entry != 0 && written < count) {
                out[written++] = entry;
            }
        }
        return written == count ? out : Arrays.copyOf(out, written);
    }

    /**
     * Emits a standalone {@code LCA-ICD-3.0} {@code BLOCK_MODE_FLAT_BITPACK} ({@code mode 10})
     * {@code SectionFrame} for {@code LCA_NET_V1} clients when {@code bitsPerVoxel} matches the
     * palette's canonical flat width; returns {@code null} otherwise so the caller falls back cleanly.
     */
    public byte @Nullable [] toWireSectionFrameBytes() {
        final int palLen = this.paletteRuntimeIds.length;
        final int expectedFlatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(palLen - 1));
        if (this.bitsPerVoxel != expectedFlatBits) {
            return null;
        }
        final int flatByteCount = (SECTION_VOXELS * expectedFlatBits + 7) >>> 3;
        if (this.flatBytes.length < flatByteCount) {
            return null;
        }
        int palVarIntBytes = varIntSize(palLen);
        for (int i = 0; i < palLen; i++) {
            final int id = this.paletteRuntimeIds[i];
            if (id < 0) {
                return null;
            }
            palVarIntBytes += varIntSize(id);
        }
        final byte[] out = new byte[1 + 8 + palVarIntBytes + flatByteCount];
        out[0] = (byte) ((0 << 6) | (2 << 4)); // CURVE_YZX (00) | BLOCK_MODE_FLAT_BITPACK (10)
        // octreeMask64 = -1L (all octants active; LcaModeABlockSection flat reader ignores octreeMask64)
        for (int i = 1; i <= 8; i++) {
            out[i] = (byte) 0xFF;
        }
        int pos = 9;
        pos = writeVarInt(out, pos, palLen);
        for (int i = 0; i < palLen; i++) {
            pos = writeVarInt(out, pos, this.paletteRuntimeIds[i]);
        }
        System.arraycopy(this.flatBytes, 0, out, pos, flatByteCount);
        return out;
    }

    private static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    private static int writeVarInt(final byte[] dst, int pos, int value) {
        while ((value & ~0x7F) != 0) {
            dst[pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        dst[pos++] = (byte) value;
        return pos;
    }

    /**
     * Extracts all 4,096 voxels mapped through {@code paletteDictIds} when clean (no dirty overlay),
     * avoiding 4,096 {@code PalettedContainer#get} and {@code IdentityHashMap} lookups on CoW save.
     */
    public int @Nullable [] extractCleanDictVoxels(final int[] paletteDictIds) {
        if (this.dirtyTable != null || paletteDictIds == null || paletteDictIds.length < this.palette.length) {
            return null;
        }
        final int[] out = new int[SECTION_VOXELS];
        final int palLen = this.palette.length;
        for (int i = 0; i < SECTION_VOXELS; i++) {
            final int ord = readBaseOrdinal(i);
            out[i] = paletteDictIds[ord < palLen ? ord : 0];
        }
        return out;
    }

    /**
     * Merges the sparse 4-byte dirty overlay into a clean {@code BLOCK_MODE_FLAT_BITPACK} backing
     * off-tick. Returns {@code null} if already clean; otherwise returns a {@link CompactionResult}
     * whose {@code compactedBacking} is {@code null} when the merged section became uniform.
     */
    public @Nullable CompactionResult compactDirtyOffThread() {
        final int[] table = this.dirtyTable;
        if (table == null || this.dirtyCount == 0) {
            return null;
        }
        final int[] voxels = new int[SECTION_VOXELS];
        final int palLen = this.paletteRuntimeIds.length;
        for (int i = 0; i < SECTION_VOXELS; i++) {
            final int ord = readBaseOrdinal(i);
            voxels[i] = this.paletteRuntimeIds[ord < palLen ? ord : 0];
        }
        for (final int entry : table) {
            if (entry == 0) {
                continue;
            }
            final int pos12 = (entry >>> 19) - 1;
            if (pos12 >= 0 && pos12 < SECTION_VOXELS) {
                voxels[pos12] = entry & STATE_ID_MASK;
            }
        }
        final GoldLeafFlatBlockSectionBacking compacted =
            fromRuntimeVoxels(voxels, this.worldId, this.stateById, this.idByState);
        if (compacted == null) {
            final BlockState uniformState = this.stateById.apply(voxels[0]);
            return new CompactionResult(null, uniformState != null ? uniformState : this.palette[0]);
        }
        return new CompactionResult(compacted, compacted.uniformFallback());
    }

    private int readBaseOrdinal(final int index) {
        if (this.bitsPerVoxel == 1) {
            return (this.flatBytes[index >>> 3] >>> (index & 7)) & 1;
        }
        if (this.bitsPerVoxel == 2) {
            return (this.flatBytes[index >>> 2] >>> ((index & 3) << 1)) & 3;
        }
        if (this.bitsPerVoxel == 4) {
            return (this.flatBytes[index >>> 1] >>> ((index & 1) << 2)) & 0xF;
        }
        final int bitPos = index * this.bitsPerVoxel;
        final int byteIdx = bitPos >>> 3;
        final int b0 = this.flatBytes[byteIdx] & 0xFF;
        final int b1 = (byteIdx + 1 < this.flatBytes.length) ? (this.flatBytes[byteIdx + 1] & 0xFF) : 0;
        final int b2 = (byteIdx + 2 < this.flatBytes.length) ? (this.flatBytes[byteIdx + 2] & 0xFF) : 0;
        return ((b0 | (b1 << 8) | (b2 << 16)) >>> (bitPos & 7)) & this.bitMask;
    }

    private int lookupDirty(final int pos12) {
        final int[] table = this.dirtyTable;
        if (table == null) {
            return -1;
        }
        final int keyTag = pos12 + 1;
        final int mask = table.length - 1;
        int slot = mixPos(pos12) & mask;
        while (true) {
            final int entry = table[slot];
            if (entry == 0) {
                return -1;
            }
            if ((entry >>> 19) == keyTag) {
                return entry & STATE_ID_MASK;
            }
            slot = (slot + 1) & mask;
        }
    }

    private synchronized int putDirtyAndGetPrevious(final int pos12, final int newStateId) {
        final int baseOrd = readBaseOrdinal(pos12);
        final int baseStateId = (baseOrd < this.paletteRuntimeIds.length)
            ? this.paletteRuntimeIds[baseOrd]
            : this.paletteRuntimeIds[0];
        final int existingDirty = lookupDirty(pos12);
        final int prevStateId = (existingDirty >= 0) ? existingDirty : baseStateId;
        if (prevStateId == newStateId) {
            return prevStateId;
        }
        int[] table = this.dirtyTable;
        if (table == null) {
            table = new int[16];
        } else if ((this.dirtyCount + 1) * 4 >= table.length * 3) {
            table = growDirtyTable(table);
        }
        final int keyTag = pos12 + 1;
        final int packed = (keyTag << 19) | (newStateId & STATE_ID_MASK);
        final int mask = table.length - 1;
        int slot = mixPos(pos12) & mask;
        while (true) {
            final int entry = table[slot];
            if (entry == 0) {
                table[slot] = packed;
                this.dirtyCount++;
                this.dirtyTable = table;
                return prevStateId;
            }
            if ((entry >>> 19) == keyTag) {
                table[slot] = packed;
                this.dirtyTable = table;
                return prevStateId;
            }
            slot = (slot + 1) & mask;
        }
    }

    private static int[] growDirtyTable(final int[] old) {
        final int[] next = new int[old.length << 1];
        final int mask = next.length - 1;
        for (final int entry : old) {
            if (entry == 0) {
                continue;
            }
            final int pos12 = (entry >>> 19) - 1;
            int slot = mixPos(pos12) & mask;
            while (next[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            next[slot] = entry;
        }
        return next;
    }

    private static int mixPos(final int pos12) {
        int h = pos12 * 0x9E37_79B9;
        return h ^ (h >>> 16);
    }

    @Override
    public BlockState get(final int x, final int y, final int z) {
        return getByIndex(((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
    }

    @Override
    public BlockState getByIndex(final int index) {
        final int dirtyId = lookupDirty(index);
        if (dirtyId >= 0) {
            return this.stateById.apply(dirtyId);
        }
        final int ord = readBaseOrdinal(index);
        return (ord < this.palette.length) ? this.palette[ord] : this.palette[0];
    }

    @Override
    public void set(final int x, final int y, final int z, final BlockState value) {
        setByIndex(((y & 15) << 8) | ((z & 15) << 4) | (x & 15), value);
    }

    @Override
    public void setByIndex(final int index, final BlockState value) {
        putDirtyAndGetPrevious(index, this.idByState.applyAsInt(value));
    }

    @Override
    public BlockState getAndSet(final int x, final int y, final int z, final BlockState value) {
        final int prevId = putDirtyAndGetPrevious(
            ((y & 15) << 8) | ((z & 15) << 4) | (x & 15),
            this.idByState.applyAsInt(value)
        );
        return this.stateById.apply(prevId);
    }

    @Override
    public boolean maybeHas(final Predicate<BlockState> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        for (final BlockState st : this.palette) {
            if (predicate.test(st)) {
                return true;
            }
        }
        final int[] table = this.dirtyTable;
        if (table != null) {
            for (final int entry : table) {
                if (entry != 0 && predicate.test(this.stateById.apply(entry & STATE_ID_MASK))) {
                    return true;
                }
            }
        }
        return false;
    }

    private int[] countBaseOrdinals() {
        final int palLen = this.palette.length;
        final int[] counts = new int[palLen];
        if (this.bitsPerVoxel == 1) {
            int ones = 0;
            for (int w = 0; w < 64; w++) {
                ones += Long.bitCount((long) LONG_LE_VIEW.get(this.flatBytes, w << 3));
            }
            counts[0] = SECTION_VOXELS - ones;
            counts[1] = ones;
            return counts;
        }
        if (this.bitsPerVoxel == 2) {
            int c0 = 0;
            int c1 = 0;
            int c2 = 0;
            int c3 = 0;
            for (int w = 0; w < 128; w++) {
                final long word = (long) LONG_LE_VIEW.get(this.flatBytes, w << 3);
                final long lo = word & EVEN_BITS_MASK;
                final long hi = (word >>> 1) & EVEN_BITS_MASK;
                c0 += Long.bitCount((~lo) & (~hi) & EVEN_BITS_MASK);
                c1 += Long.bitCount(lo & (~hi));
                c2 += Long.bitCount((~lo) & hi);
                c3 += Long.bitCount(lo & hi);
            }
            counts[0] = c0;
            counts[1] = c1;
            if (palLen > 2) counts[2] = c2;
            if (palLen > 3) counts[3] = c3;
            return counts;
        }
        if (this.bitsPerVoxel == 4) {
            for (int i = 0; i < 2048; i++) {
                final int b = this.flatBytes[i] & 0xFF;
                final int o0 = b & 0xF;
                final int o1 = b >>> 4;
                counts[o0 < palLen ? o0 : 0]++;
                counts[o1 < palLen ? o1 : 0]++;
            }
            return counts;
        }
        for (int i = 0; i < SECTION_VOXELS; i++) {
            final int ord = readBaseOrdinal(i);
            counts[ord < palLen ? ord : 0]++;
        }
        return counts;
    }

    @Override
    public void count(final PalettedContainer.CountConsumer<BlockState> output) {
        Objects.requireNonNull(output, "output");
        final int[] baseCounts = countBaseOrdinals();
        final int[] table = this.dirtyTable;
        if (table == null) {
            for (int i = 0; i < baseCounts.length; i++) {
                if (baseCounts[i] > 0) {
                    output.accept(this.palette[i], baseCounts[i]);
                }
            }
            return;
        }
        final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap extra = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        for (final int entry : table) {
            if (entry == 0) {
                continue;
            }
            final int pos12 = (entry >>> 19) - 1;
            final int newStateId = entry & STATE_ID_MASK;
            final int baseOrd = readBaseOrdinal(pos12);
            baseCounts[baseOrd < baseCounts.length ? baseOrd : 0]--;
            extra.addTo(newStateId, 1);
        }
        for (int i = 0; i < baseCounts.length; i++) {
            final int cnt = baseCounts[i] + extra.remove(this.paletteRuntimeIds[i]);
            if (cnt > 0) {
                output.accept(this.palette[i], cnt);
            }
        }
        if (!extra.isEmpty()) {
            extra.int2IntEntrySet().fastForEach(e -> {
                if (e.getIntValue() > 0) {
                    output.accept(this.stateById.apply(e.getIntKey()), e.getIntValue());
                }
            });
        }
    }

    @Override
    public void forEachDistinct(final Consumer<BlockState> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        count((state, cnt) -> {
            if (cnt > 0) {
                consumer.accept(state);
            }
        });
    }

    @Override
    public int getSerializedSize(final IdMap<BlockState> globalMap) {
        if (this.dirtyTable == null && this.palette.length <= 16) {
            int size = 1 + VarInt.getByteSize(this.palette.length);
            for (final BlockState st : this.palette) {
                size += VarInt.getByteSize(globalMap.getId(st));
            }
            return size + (256 * Long.BYTES);
        }
        final PalettedContainer<BlockState> tmp = materializeEphemeral();
        return tmp.getSerializedSize();
    }

    @Override
    public void writeVanilla(final FriendlyByteBuf buffer, final IdMap<BlockState> globalMap) {
        if (this.dirtyTable == null && this.palette.length <= 16) {
            buffer.writeByte(4);
            buffer.writeVarInt(this.palette.length);
            for (final BlockState st : this.palette) {
                buffer.writeVarInt(globalMap.getId(st));
            }
            if (this.bitsPerVoxel == 4) {
                for (int w = 0; w < 256; w++) {
                    buffer.writeLong((long) LONG_LE_VIEW.get(this.flatBytes, w << 3));
                }
                return;
            }
            if (this.bitsPerVoxel == 2) {
                for (int w = 0; w < 256; w++) {
                    final int word32 = (int) INT_LE_VIEW.get(this.flatBytes, w << 2);
                    buffer.writeLong(CompactSectionWritebackApplier.spread2To4(word32));
                }
                return;
            }
            if (this.bitsPerVoxel == 1) {
                for (int w = 0; w < 256; w++) {
                    final int b0 = this.flatBytes[w << 1] & 0xFF;
                    final int b1 = this.flatBytes[(w << 1) | 1] & 0xFF;
                    final int bits16 = b0 | (b1 << 8);
                    buffer.writeLong(spread1To4(bits16));
                }
                return;
            }
            // bits == 3
            for (int w = 0; w < 256; w++) {
                final int base = w << 4;
                long out = 0L;
                for (int k = 0; k < 16; k++) {
                    out |= ((long) (readBaseOrdinal(base + k) & 0xF)) << (k << 2);
                }
                buffer.writeLong(out);
            }
            return;
        }
        final PalettedContainer<BlockState> tmp = materializeEphemeral();
        tmp.write(buffer);
    }

    private static long spread1To4(final int bits16) {
        long x = bits16 & 0xFFFFL;
        x = (x | (x << 24)) & 0x0000_00FF_0000_00FFL;
        x = (x | (x << 12)) & 0x000F_000F_000F_000FL;
        x = (x | (x << 6)) & 0x0303_0303_0303_0303L;
        x = (x | (x << 3)) & 0x1111_1111_1111_1111L;
        return x;
    }

    private PalettedContainer<BlockState> materializeEphemeral() {
        final PalettedContainer<BlockState> tmp = new PalettedContainer<>(
            this.palette[0],
            net.minecraft.world.level.chunk.Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)
        );
        copyInto(tmp);
        return tmp;
    }

    @Override
    public boolean isCleanUniform() {
        return false;
    }

    @Override
    public BlockState uniformFallback() {
        return this.palette[0];
    }

    @Override
    public void copyInto(final PalettedContainer<BlockState> destination) {
        Objects.requireNonNull(destination, "destination");
        if (this.dirtyTable == null) {
            for (int i = 0; i < SECTION_VOXELS; i++) {
                final int ord = readBaseOrdinal(i);
                destination.set(i, ord < this.palette.length ? this.palette[ord] : this.palette[0]);
            }
            return;
        }
        for (int i = 0; i < SECTION_VOXELS; i++) {
            destination.set(i, getByIndex(i));
        }
    }

    @Override
    public void forEachMatchingCoordinate(final Predicate<BlockState> predicate, final IntConsumer pos12Consumer) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(pos12Consumer, "pos12Consumer");
        final boolean[] matchOrd = new boolean[this.palette.length];
        boolean anyBaseMatch = false;
        for (int i = 0; i < this.palette.length; i++) {
            if (predicate.test(this.palette[i])) {
                matchOrd[i] = true;
                anyBaseMatch = true;
            }
        }
        final int[] table = this.dirtyTable;
        if (table == null) {
            if (!anyBaseMatch) {
                // O(1) skip for ~80% of sections with no matching states in palette.
                return;
            }
            if (this.bitsPerVoxel == 1) {
                final boolean m0 = matchOrd[0];
                final boolean m1 = matchOrd[1];
                if (m0 && m1) {
                    for (int i = 0; i < SECTION_VOXELS; i++) pos12Consumer.accept(i);
                    return;
                }
                for (int w = 0; w < 64; w++) {
                    long word = (long) LONG_LE_VIEW.get(this.flatBytes, w << 3);
                    if (m0) word = ~word;
                    final int base = w << 6;
                    while (word != 0L) {
                        pos12Consumer.accept(base + Long.numberOfTrailingZeros(word));
                        word &= (word - 1L);
                    }
                }
                return;
            }
            for (int i = 0; i < SECTION_VOXELS; i++) {
                final int ord = readBaseOrdinal(i);
                if (ord < matchOrd.length && matchOrd[ord]) {
                    pos12Consumer.accept(i);
                }
            }
            return;
        }
        for (int i = 0; i < SECTION_VOXELS; i++) {
            if (predicate.test(getByIndex(i))) {
                pos12Consumer.accept(i);
            }
        }
    }

    @Override
    public int dirtyCount() {
        return this.dirtyCount;
    }

    @Override
    public boolean shouldPromoteToHotMutable() {
        return this.hotPromoteDirtyThreshold > 0 && this.dirtyCount >= this.hotPromoteDirtyThreshold;
    }

    @Override
    public synchronized ColdSectionBackingSpi<BlockState> snapshotCopy() {
        // Detach wave-arena-backed flatBytes on snapshot so async save serialization never races
        // with LevelChunk unload reclaiming the WaveChunkArena slot for the next GPU wave (S-002).
        final int activeBytes = (SECTION_VOXELS * this.bitsPerVoxel + 7) >>> 3;
        final byte[] snapshotFlat = this.waveSlotId >= 0
            ? Arrays.copyOf(this.flatBytes, Math.min(activeBytes, this.flatBytes.length))
            : this.flatBytes;
        final GoldLeafFlatBlockSectionBacking copy = new GoldLeafFlatBlockSectionBacking(
            snapshotFlat,
            this.bitsPerVoxel,
            this.palette,
            this.paletteRuntimeIds,
            this.hotPromoteDirtyThreshold,
            this.stateById,
            this.idByState,
            this.worldId,
            -1,
            this.hasPrecomputedOctreeMask,
            this.precomputedOctreeMask64,
            this.preResolvedDictIds
        );
        final int[] table = this.dirtyTable;
        if (table != null && this.dirtyCount > 0) {
            copy.dirtyTable = Arrays.copyOf(table, table.length);
            copy.dirtyCount = this.dirtyCount;
        }
        return copy;
    }
}
