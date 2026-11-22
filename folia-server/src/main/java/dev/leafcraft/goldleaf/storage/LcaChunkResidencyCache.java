package dev.leafcraft.goldleaf.storage;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 per-world cold {@code .lca} {@link RawChunkFrameSnapshot} residency cache and bounded
 * 4-byte packed sparse dirty-cell overlay ({@code goldleaf-ADR-005} Stage 2,
 * {@code leafanvil-ADR-006}, {@code goldleaf-ADR-002} Tiers 0–1).
 *
 * <p>Clean loaded chunks retain only their immutable {@link RawChunkFrameSnapshot} and serve
 * Tier 0 async reads with zero copying and zero lock contention. Active block edits land in an
 * O(1) 4-byte packed sparse cell array {@code ((pos12 + 1) << 19) | (runtimeStateId & 0x7FFFF)}
 * on the owning region thread, and tick-end {@link #freezeTickEndSnapshot(long)} publishes an
 * immutable {@link FrozenReadSnapshot} for Tier 1 reads without MESI cache-line bouncing.</p>
 *
 * <p><b>Folia region-thread ownership ({@code F-001..F-003}):</b> All mutating operations
 * ({@link #recordDirtyVoxel}, {@link #freezeTickEndSnapshot}, {@link #markUnloadedAndEvict}) and
 * {@link #readLiveRuntimeStateId} are owned by the chunk's owning region thread. Cross-region and
 * async readers must use {@link #getReadSnapshot(long)} (immutable {@link FrozenReadSnapshot}),
 * and off-tick {@link #compactDirtySnapshot(long)} operates strictly on a frozen snapshot and
 * commits via {@code UNLOADED_BIT}-fenced epoch CAS ({@code S-002}).</p>
 *
 * <p>Unload safety ({@code S-002}): every entry carries a monotonic epoch with {@link #UNLOADED_BIT}
 * fencing so an in-flight async CoW commit can never resurrect an evicted chunk entry.</p>
 */
public final class LcaChunkResidencyCache {

    /** Sentinel bit set on the entry epoch when a chunk unloads ({@code S-002}). */
    public static final long UNLOADED_BIT = Long.MIN_VALUE;

    /** Mask for the 19-bit runtime block-state ID inside a packed 4-byte dirty cell. */
    public static final int RUNTIME_STATE_MASK = 0x7FFFF;

    /** Default cap on resident chunk entries per world cache to bound memory. */
    public static final int DEFAULT_MAX_RESIDENT_CHUNKS = 8192;

    private static final int CHUNK_HEADER_FIXED_BYTES = 24;
    private static final int SECTION_VOXELS = 4096;
    private static final int SECTION_HEADER_MODE10_YZX = 0x20;
    private static final int CHUNK_FLAG_SCALE_4B = 0x04;
    private static final long[][] OCTANT_BIT_BASE = new long[4][4];

    static {
        for (int oz = 0; oz < 4; oz++) {
            for (int ox = 0; ox < 4; ox++) {
                OCTANT_BIT_BASE[oz][ox] = 1L << xyToHilbert2D4(ox, oz);
            }
        }
    }

    private final int maxResidentChunks;
    private final ConcurrentHashMap<Long, ChunkResidencyEntry> entries = new ConcurrentHashMap<>();

    public LcaChunkResidencyCache() {
        this(DEFAULT_MAX_RESIDENT_CHUNKS);
    }

    public LcaChunkResidencyCache(final int maxResidentChunks) {
        if (maxResidentChunks <= 0) {
            throw new IllegalArgumentException("maxResidentChunks must be > 0, got " + maxResidentChunks);
        }
        this.maxResidentChunks = maxResidentChunks;
    }

    /**
     * Packs a 12-bit section-local voxel position {@code [0, 4095]} and 19-bit runtime state ID
     * into a single 4-byte {@code int} ({@code ((pos12 + 1) << 19) | (runtimeStateId & 0x7FFFF)}).
     */
    public static int packDirtyCell(final int localPos12, final int runtimeStateId) {
        if ((localPos12 & ~0xFFF) != 0) {
            throw new IllegalArgumentException("localPos12 out of range [0, 4095]: " + localPos12);
        }
        if ((runtimeStateId & ~RUNTIME_STATE_MASK) != 0) {
            throw new IllegalArgumentException("runtimeStateId out of 19-bit range: " + runtimeStateId);
        }
        return ((localPos12 + 1) << 19) | (runtimeStateId & RUNTIME_STATE_MASK);
    }

    public static int unpackLocalPos12(final int packedCell) {
        return (packedCell >>> 19) - 1;
    }

    public static int unpackRuntimeStateId(final int packedCell) {
        return packedCell & RUNTIME_STATE_MASK;
    }

    /**
     * Installs or replaces the cold baseline {@link RawChunkFrameSnapshot} for a loaded chunk.
     */
    public void installBaseFrame(final long chunkKey, final RawChunkFrameSnapshot baseFrame) {
        Objects.requireNonNull(baseFrame, "baseFrame");
        if (this.entries.size() >= this.maxResidentChunks && !this.entries.containsKey(chunkKey)) {
            return;
        }
        this.entries.compute(chunkKey, (key, existing) -> {
            if (existing == null || !existing.resetBaseFrame(baseFrame)) {
                return new ChunkResidencyEntry(baseFrame);
            }
            return existing;
        });
    }

    /**
     * Records a single voxel edit on the owning region thread in O(1) without allocating a
     * full {@code int[4096]} slab.
     *
     * @return {@code true} if the chunk is resident and the edit was recorded
     */
    public boolean recordDirtyVoxel(
        final long chunkKey,
        final int sectionIndex,
        final int localPos12,
        final int runtimeStateId
    ) {
        final ChunkResidencyEntry entry = this.entries.get(chunkKey);
        if (entry == null) {
            return false;
        }
        final int packed = packDirtyCell(localPos12, runtimeStateId);
        return entry.recordDirtyCell(sectionIndex, localPos12, packed);
    }

    /**
     * Reads the current runtime block-state ID at {@code (lx, ly, lz)} in {@code sectionIndex}
     * on the owning region thread, checking the live sparse dirty overlay before falling back to
     * an O(1) cold {@code .lca} {@link RawChunkFrameSnapshot} lookup ({@code -1} if absent/unloaded).
     */
    public int readLiveRuntimeStateId(
        final long chunkKey,
        final int sectionIndex,
        final int lx,
        final int ly,
        final int lz
    ) {
        final ChunkResidencyEntry entry = this.entries.get(chunkKey);
        if (entry == null) {
            return -1;
        }
        final int pos12 = ((ly & 15) << 8) | ((lz & 15) << 4) | (lx & 15);
        return entry.readLiveRuntimeState(sectionIndex, pos12);
    }

    /**
     * Performs section-spliced Copy-on-Write (CoW) compaction of the chunk's frozen dirty overlay
     * into a new immutable {@link RawChunkFrameSnapshot}: untouched sections are copied verbatim
     * via {@link System#arraycopy}, dirty sections are re-encoded in {@code LCA-ICD-3.0}
     * {@code mode 10} (or folded to inline uniform if uniform), and the result is swapped atomically
     * under {@link #UNLOADED_BIT} epoch fencing ({@code S-002}, {@code S-005}).
     *
     * @return {@code true} if dirty edits were compacted and committed, {@code false} if clean,
     *         absent, unloaded, or superseded by a newer epoch
     */
    public boolean compactDirtySnapshot(final long chunkKey) {
        final ChunkResidencyEntry entry = this.entries.get(chunkKey);
        if (entry == null) {
            return false;
        }
        final FrozenReadSnapshot snap = entry.freezeTickEnd();
        if (snap == null || snap.isClean()) {
            return false;
        }
        final long expectedEpoch = snap.epoch();
        final RawChunkFrameSnapshot compacted = spliceFrozenSnapshotToCompactFrame(snap);
        return entry.tryCommitSwap(expectedEpoch, compacted);
    }

    /**
     * Publishes an immutable tick-end {@link FrozenReadSnapshot} so async Tier 1 readers observe
     * consistent committed state without contending with the region thread's live dirty map.
     */
    public @Nullable FrozenReadSnapshot freezeTickEndSnapshot(final long chunkKey) {
        final ChunkResidencyEntry entry = this.entries.get(chunkKey);
        if (entry == null) {
            return null;
        }
        return entry.freezeTickEnd();
    }

    /**
     * Returns the current immutable {@link FrozenReadSnapshot} for lock-free Tier 0/1 async reads,
     * or {@code null} if the chunk is not resident or has been unloaded.
     */
    public @Nullable FrozenReadSnapshot getReadSnapshot(final long chunkKey) {
        final ChunkResidencyEntry entry = this.entries.get(chunkKey);
        return entry == null ? null : entry.publishedSnapshot();
    }

    /**
     * Captures the current monotonic epoch before launching an async CoW commit.
     *
     * @return non-negative epoch token, or {@code -1L} if the chunk is absent or already unloaded
     */
    public long beginCommitEpoch(final long chunkKey) {
        final ChunkResidencyEntry entry = this.entries.get(chunkKey);
        return entry == null ? -1L : entry.captureCommitEpoch();
    }

    /**
     * Atomically swaps the committed {@link RawChunkFrameSnapshot} if and only if the chunk has
     * not been unloaded and no newer epoch superseded {@code expectedEpoch} ({@code S-002}).
     */
    public boolean completeCommitSwap(
        final long chunkKey,
        final long expectedEpoch,
        final RawChunkFrameSnapshot committedBaseFrame
    ) {
        Objects.requireNonNull(committedBaseFrame, "committedBaseFrame");
        if (expectedEpoch < 0L) {
            return false;
        }
        final ChunkResidencyEntry entry = this.entries.get(chunkKey);
        if (entry == null) {
            return false;
        }
        return entry.tryCommitSwap(expectedEpoch, committedBaseFrame);
    }

    /**
     * Fences the chunk with {@link #UNLOADED_BIT} and evicts it from the residency cache so no
     * in-flight async CoW commit can resurrect stale memory ({@code S-002}).
     */
    public void markUnloadedAndEvict(final long chunkKey) {
        final ChunkResidencyEntry removed = this.entries.remove(chunkKey);
        if (removed != null) {
            removed.markUnloaded();
        }
    }

    /**
     * Releases a clean transient {@link RawChunkFrameSnapshot} once its sections have been
     * attached to native {@link GoldLeafBlockSectionBacking}s so unedited chunks do not retain
     * duplicate raw {@code byte[]} copies in heap ({@code goldleaf-ADR-005} Stage 4).
     */
    public boolean releaseBaseFrameIfClean(final long chunkKey) {
        final boolean[] released = new boolean[1];
        this.entries.computeIfPresent(chunkKey, (key, existing) -> {
            final FrozenReadSnapshot snap = existing.publishedSnapshot();
            if (snap != null && snap.isClean()) {
                existing.markUnloaded();
                released[0] = true;
                return null;
            }
            return existing;
        });
        return released[0];
    }

    public int residentCount() {
        return this.entries.size();
    }

    public void clear() {
        for (final ChunkResidencyEntry entry : this.entries.values()) {
            entry.markUnloaded();
        }
        this.entries.clear();
    }

    /**
     * Immutable cache-line-isolated read snapshot published for Tier 0 (clean) and Tier 1
     * (dirty frozen overlay) async reads ({@code goldleaf-ADR-002} §2).
     */
    public static final class FrozenReadSnapshot {

        private final RawChunkFrameSnapshot baseFrame;
        private final int dirtySectionMask;
        private final int @Nullable [][] frozenDirtyCells;
        private final long snapshotEpoch;

        FrozenReadSnapshot(
            final RawChunkFrameSnapshot baseFrame,
            final int dirtySectionMask,
            final int @Nullable [][] frozenDirtyCells,
            final long snapshotEpoch
        ) {
            this.baseFrame = baseFrame;
            this.dirtySectionMask = dirtySectionMask;
            this.frozenDirtyCells = frozenDirtyCells;
            this.snapshotEpoch = snapshotEpoch;
        }

        public RawChunkFrameSnapshot baseFrame() {
            return this.baseFrame;
        }

        public int dirtySectionMask() {
            return this.dirtySectionMask;
        }

        public boolean isClean() {
            return this.dirtySectionMask == 0;
        }

        public long epoch() {
            return this.snapshotEpoch;
        }

        /**
         * Looks up a dirty overlay runtime state ID for {@code (sectionIndex, localPos12)},
         * returning {@code -1} if untouched in the frozen overlay.
         */
        public int lookupDirtyRuntimeState(final int sectionIndex, final int localPos12) {
            if (this.dirtySectionMask == 0
                || this.frozenDirtyCells == null
                || sectionIndex < 0
                || sectionIndex >= this.frozenDirtyCells.length) {
                return -1;
            }
            final int[] cells = this.frozenDirtyCells[sectionIndex];
            if (cells == null) {
                return -1;
            }
            final int targetPrefix = (localPos12 + 1) << 19;
            for (final int packed : cells) {
                if ((packed & ~RUNTIME_STATE_MASK) == targetPrefix) {
                    return packed & RUNTIME_STATE_MASK;
                }
            }
            return -1;
        }

        /**
         * Returns a defensive copy of the frozen 4-byte packed dirty cells for {@code sectionIndex},
         * or an empty array when the section has no dirty edits.
         */
        public int[] copyDirtyCellsForSection(final int sectionIndex) {
            if (this.dirtySectionMask == 0
                || this.frozenDirtyCells == null
                || sectionIndex < 0
                || sectionIndex >= this.frozenDirtyCells.length) {
                return new int[0];
            }
            final int[] cells = this.frozenDirtyCells[sectionIndex];
            return cells == null || cells.length == 0 ? new int[0] : cells.clone();
        }

        /**
         * Lock-free Tier 0/1 voxel lookup: checks the immutable frozen dirty overlay first, then
         * reads directly from the cold {@code .lca} {@link RawChunkFrameSnapshot} in O(1) for
         * uniform and {@code mode 10} FlatBitPack sections without hydrating {@code PalettedContainer}.
         */
        public int readRuntimeStateId(final int sectionIndex, final int lx, final int ly, final int lz) {
            final int pos12 = ((ly & 15) << 8) | ((lz & 15) << 4) | (lx & 15);
            final int dirty = lookupDirtyRuntimeState(sectionIndex, pos12);
            if (dirty >= 0) {
                return dirty;
            }
            return readRuntimeStateFromBaseFrame(this.baseFrame, sectionIndex, pos12);
        }

        int @Nullable [][] frozenDirtyCellsInternal() {
            return this.frozenDirtyCells;
        }
    }

    /**
     * Reads the runtime block-state ID at {@code pos12 = (ly << 8) | (lz << 4) | lx} directly from
     * an immutable {@link RawChunkFrameSnapshot} without full-section decompression for uniform
     * ({@code 0x0000} / {@code 0x8000 | id}) and {@code mode 10} ({@code FlatBitPack}) sections.
     */
    public static int readRuntimeStateFromBaseFrame(
        final RawChunkFrameSnapshot baseFrame,
        final int sectionIndex,
        final int pos12
    ) {
        final int entry16 = baseFrame.sectionDirectoryEntry16(sectionIndex);
        if (entry16 == 0) {
            return 0;
        }
        if ((entry16 & 0x8000) != 0) {
            return baseFrame.resolveInlineUniformRuntimeStateId(sectionIndex, null);
        }
        final byte[] frame = baseFrame.chunkFrame();
        final int unitShift = baseFrame.isScale4B() ? 2 : 0;
        final int relStart = baseFrame.sectionDirectoryEnd() + ((entry16 - 1) << unitShift);
        if (relStart < 0 || relStart >= frame.length) {
            return 0;
        }
        final int sectionHeader = frame[relStart] & 0xFF;
        final int blockMode = (sectionHeader >>> 4) & 3;
        if (blockMode == 2 && !baseFrame.hasSlabLocalPalette()) {
            // Fast O(1) direct read for LCA-ICD-3.0 mode 10 (FlatBitPack)
            int p = relStart + 1 + 8; // skip sectionHeader (1B) + octreeOccupancyMask64 (8B)
            int paletteLen = readVarInt(frame, p);
            p += varIntLen(paletteLen);
            if (paletteLen == 0) {
                p++; // skip secExtFlags
                paletteLen = readVarInt(frame, p);
                p += varIntLen(paletteLen);
            }
            final int paletteStart = p;
            for (int i = 0; i < paletteLen; i++) {
                final int dictId = readVarInt(frame, p);
                p += varIntLen(dictId);
            }
            final int payloadStart = p;
            final int flatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteLen - 1)));
            final int palIdx = readFlatBitPackOrdinal(frame, payloadStart, pos12, flatBits);
            final int dictId = readNthVarInt(frame, paletteStart, palIdx);
            final int[] dictMap = baseFrame.dictIdToRuntimeId();
            return (dictMap != null && dictId >= 0 && dictId < dictMap.length) ? dictMap[dictId] : dictId;
        }
        if (blockMode == 0 && !baseFrame.hasSlabLocalPalette()) {
            int p = relStart + 1;
            int paletteLen = readVarInt(frame, p);
            p += varIntLen(paletteLen);
            if (paletteLen == 0) {
                p++;
                paletteLen = readVarInt(frame, p);
                p += varIntLen(paletteLen);
            }
            final int dictId = readVarInt(frame, p);
            final int[] dictMap = baseFrame.dictIdToRuntimeId();
            return (dictMap != null && dictId >= 0 && dictId < dictMap.length) ? dictMap[dictId] : dictId;
        }
        // Fallback for Delta-Roaring (mode 01/11) or slab-local palette frames from disk
        final byte[] runtimeSlice = baseFrame.sliceSectionFrameWithRuntimePalette(sectionIndex, null);
        if (runtimeSlice.length == 0) {
            return 0;
        }
        final LcaSectionBacking backing = LcaSectionBacking.fromColdSectionFrame(
            runtimeSlice,
            0,
            -1,
            0,
            0,
            id -> id != 0,
            id -> false,
            id -> false
        );
        return backing.getStateId(pos12 & 15, (pos12 >>> 8) & 15, (pos12 >>> 4) & 15);
    }

    private static RawChunkFrameSnapshot spliceFrozenSnapshotToCompactFrame(final FrozenReadSnapshot snap) {
        final RawChunkFrameSnapshot base = snap.baseFrame();
        final byte[] baseBytes = base.chunkFrame();
        final int sectionCount = base.sectionCount();
        final int[][] frozenDirty = snap.frozenDirtyCellsInternal();
        final int[] baseRelStarts = base.computeSectionRelStarts();
        final int baseResidualStart = base.residualRelStart();

        // Build append-only dictIdToRuntimeId so clean sections keep exact verbatim dictIds
        final int[] origDict = base.dictIdToRuntimeId();
        int[] dictIds = origDict != null ? Arrays.copyOf(origDict, Math.max(4, origDict.length)) : new int[4];
        int dictSize = origDict != null ? origDict.length : 0;

        final int[] newEntries = new int[sectionCount];
        final byte[][] encodedSections = new byte[sectionCount][];
        final int[] copySrcStarts = new int[sectionCount];
        final int[] copySrcLens = new int[sectionCount];
        Arrays.fill(copySrcStarts, -1);

        for (int s = 0; s < sectionCount; s++) {
            final int[] dirtyCells = (frozenDirty != null && s < frozenDirty.length) ? frozenDirty[s] : null;
            if (dirtyCells == null || dirtyCells.length == 0) {
                final int entry16 = base.sectionDirectoryEntry16(s);
                if (entry16 == 0 || (entry16 & 0x8000) != 0) {
                    newEntries[s] = entry16;
                } else {
                    final int start = baseRelStarts[s];
                    final int end = findSectionEnd(baseRelStarts, s, sectionCount, baseResidualStart, baseBytes.length);
                    copySrcStarts[s] = start;
                    copySrcLens[s] = Math.max(0, end - start);
                }
                continue;
            }

            // Decode base section into 4096 runtime state IDs, then apply frozen dirty cells
            final int[] voxels = new int[SECTION_VOXELS];
            decodeSectionRuntimeStatesInto(base, s, voxels);
            for (final int packed : dirtyCells) {
                voxels[unpackLocalPos12(packed)] = unpackRuntimeStateId(packed);
            }

            // Check if the updated section folded to uniform
            final int firstState = voxels[0];
            boolean uniform = true;
            for (int i = 1; i < SECTION_VOXELS; i++) {
                if (voxels[i] != firstState) {
                    uniform = false;
                    break;
                }
            }
            if (uniform) {
                if (firstState == 0) {
                    newEntries[s] = 0;
                } else {
                    final int dIdx = findOrAppendDictId(dictIds, dictSize, firstState);
                    if (dIdx >= dictSize) {
                        if (dIdx >= dictIds.length) {
                            dictIds = Arrays.copyOf(dictIds, dictIds.length * 2);
                        }
                        dictIds[dIdx] = firstState;
                        dictSize = dIdx + 1;
                    }
                    newEntries[s] = 0x8000 | (dIdx & 0x1FFF);
                }
                continue;
            }

            // Collect distinct runtime states for this dirty section and map to dictIds
            final int[] localStates = new int[16];
            int localCount = 0;
            int[] stateArr = localStates;
            final int[] yzxOrd = new int[SECTION_VOXELS];
            for (int i = 0; i < SECTION_VOXELS; i++) {
                final int st = voxels[i];
                int ord = -1;
                for (int k = 0; k < localCount; k++) {
                    if (stateArr[k] == st) {
                        ord = k;
                        break;
                    }
                }
                if (ord < 0) {
                    if (localCount == stateArr.length) {
                        stateArr = Arrays.copyOf(stateArr, stateArr.length * 2);
                    }
                    ord = localCount;
                    stateArr[localCount++] = st;
                }
                yzxOrd[i] = ord;
            }

            final int[] sectionDictIds = new int[localCount];
            for (int k = 0; k < localCount; k++) {
                final int st = stateArr[k];
                final int dIdx = findOrAppendDictId(dictIds, dictSize, st);
                if (dIdx >= dictSize) {
                    if (dIdx >= dictIds.length) {
                        dictIds = Arrays.copyOf(dictIds, dictIds.length * 2);
                    }
                    dictIds[dIdx] = st;
                    dictSize = dIdx + 1;
                }
                sectionDictIds[k] = dIdx;
            }

            encodedSections[s] = encodeMode10SectionFrame(sectionDictIds, yzxOrd);
        }

        final int residualLen = base.hasResidualNbt() ? Math.max(0, baseBytes.length - baseResidualStart) : 0;
        final int sectionDirEnd = CHUNK_HEADER_FIXED_BYTES + (sectionCount << 1);

        int rawBodyLen = 0;
        for (int s = 0; s < sectionCount; s++) {
            if (copySrcStarts[s] >= 0) {
                rawBodyLen += copySrcLens[s];
            } else if (encodedSections[s] != null) {
                rawBodyLen += encodedSections[s].length;
            }
        }
        final boolean scale4B = rawBodyLen > 0x7FFE;
        int totalBodyLen = 0;
        for (int s = 0; s < sectionCount; s++) {
            final int len = copySrcStarts[s] >= 0
                ? copySrcLens[s]
                : (encodedSections[s] != null ? encodedSections[s].length : 0);
            if (len > 0) {
                totalBodyLen += scale4B ? ((len + 3) & ~3) : len;
            }
        }

        final byte[] out = new byte[sectionDirEnd + totalBodyLen + residualLen];
        // Copy 24-byte ChunkFrame header verbatim, then update flags + residual offset + section directory
        System.arraycopy(baseBytes, 0, out, 0, CHUNK_HEADER_FIXED_BYTES);
        int flags = baseBytes[21] & 0xFF;
        if (scale4B) {
            flags |= CHUNK_FLAG_SCALE_4B;
        } else {
            flags &= ~CHUNK_FLAG_SCALE_4B;
        }
        out[21] = (byte) flags;

        int pos = sectionDirEnd;
        for (int s = 0; s < sectionCount; s++) {
            final int dirOff = CHUNK_HEADER_FIXED_BYTES + (s << 1);
            if (copySrcStarts[s] >= 0) {
                final int rel = pos - sectionDirEnd;
                writeUint16BE(out, dirOff, (scale4B ? (rel >>> 2) : rel) + 1);
                System.arraycopy(baseBytes, copySrcStarts[s], out, pos, copySrcLens[s]);
                pos += scale4B ? ((copySrcLens[s] + 3) & ~3) : copySrcLens[s];
            } else if (encodedSections[s] != null) {
                final int rel = pos - sectionDirEnd;
                writeUint16BE(out, dirOff, (scale4B ? (rel >>> 2) : rel) + 1);
                System.arraycopy(encodedSections[s], 0, out, pos, encodedSections[s].length);
                pos += scale4B ? ((encodedSections[s].length + 3) & ~3) : encodedSections[s].length;
            } else {
                writeUint16BE(out, dirOff, newEntries[s]);
            }
        }

        final int newResidualOffset;
        if (residualLen > 0) {
            final int rel = pos - sectionDirEnd;
            newResidualOffset = scale4B ? (rel >>> 2) : rel;
            writeUint16BE(out, 22, newResidualOffset);
            System.arraycopy(baseBytes, baseResidualStart, out, pos, residualLen);
        } else {
            newResidualOffset = 0;
            writeUint16BE(out, 22, 0);
        }

        return new RawChunkFrameSnapshot(
            out,
            base.dataVersion(),
            base.minSectionY(),
            sectionCount,
            newResidualOffset,
            Arrays.copyOf(dictIds, dictSize),
            base.formatVersion()
        );
    }

    private static void decodeSectionRuntimeStatesInto(
        final RawChunkFrameSnapshot base,
        final int sectionIndex,
        final int[] outVoxels4096
    ) {
        final int entry16 = base.sectionDirectoryEntry16(sectionIndex);
        if (entry16 == 0) {
            Arrays.fill(outVoxels4096, 0);
            return;
        }
        if ((entry16 & 0x8000) != 0) {
            Arrays.fill(outVoxels4096, base.resolveInlineUniformRuntimeStateId(sectionIndex, null));
            return;
        }
        final byte[] frame = base.chunkFrame();
        final int unitShift = base.isScale4B() ? 2 : 0;
        final int relStart = base.sectionDirectoryEnd() + ((entry16 - 1) << unitShift);
        final int blockMode = ((frame[relStart] & 0xFF) >>> 4) & 3;
        if (blockMode == 2 && !base.hasSlabLocalPalette()) {
            int p = relStart + 1 + 8;
            int paletteLen = readVarInt(frame, p);
            p += varIntLen(paletteLen);
            if (paletteLen == 0) {
                p++;
                paletteLen = readVarInt(frame, p);
                p += varIntLen(paletteLen);
            }
            final int[] dictMap = base.dictIdToRuntimeId();
            final int[] runtimePal = new int[paletteLen];
            for (int i = 0; i < paletteLen; i++) {
                final int dId = readVarInt(frame, p);
                p += varIntLen(dId);
                runtimePal[i] = (dictMap != null && dId >= 0 && dId < dictMap.length) ? dictMap[dId] : dId;
            }
            final int flatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteLen - 1)));
            if (flatBits == 2) {
                for (int i = 0; i < SECTION_VOXELS; i += 4) {
                    final int b = frame[p++] & 0xFF;
                    outVoxels4096[i] = runtimePal[b & 0x3];
                    outVoxels4096[i + 1] = runtimePal[(b >>> 2) & 0x3];
                    outVoxels4096[i + 2] = runtimePal[(b >>> 4) & 0x3];
                    outVoxels4096[i + 3] = runtimePal[(b >>> 6) & 0x3];
                }
            } else {
                for (int i = 0; i < SECTION_VOXELS; i++) {
                    outVoxels4096[i] = runtimePal[readFlatBitPackOrdinal(frame, p, i, flatBits)];
                }
            }
            return;
        }
        final byte[] runtimeSlice = base.sliceSectionFrameWithRuntimePalette(sectionIndex, null);
        LcaSectionBacking.decodeCompactBaseFrameInto(runtimeSlice, 0, outVoxels4096);
    }

    private static byte[] encodeMode10SectionFrame(final int[] sectionDictIds, final int[] yzxOrd) {
        final int paletteLen = sectionDictIds.length;
        final int flatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteLen - 1)));
        final int flatBytes = (SECTION_VOXELS * flatBits + 7) >>> 3;
        int paletteBytes = varIntLen(paletteLen);
        for (final int dId : sectionDictIds) {
            paletteBytes += varIntLen(dId);
        }
        final byte[] out = new byte[1 + 8 + paletteBytes + flatBytes + 1];
        int p = 0;
        out[p++] = (byte) SECTION_HEADER_MODE10_YZX;
        writeLongBE(out, p, computeOctreeMask64FromOrdinals(yzxOrd));
        p += 8;
        p = writeVarInt(out, p, paletteLen);
        for (final int dId : sectionDictIds) {
            p = writeVarInt(out, p, dId);
        }
        if (flatBits == 2) {
            for (int i = 0; i < SECTION_VOXELS; i += 4) {
                out[p++] = (byte) ((yzxOrd[i] & 0x3)
                    | ((yzxOrd[i + 1] & 0x3) << 2)
                    | ((yzxOrd[i + 2] & 0x3) << 4)
                    | ((yzxOrd[i + 3] & 0x3) << 6));
            }
        } else {
            int bitPos = 0;
            for (int i = 0; i < SECTION_VOXELS; i++) {
                final int val = yzxOrd[i];
                for (int b = 0; b < flatBits; b++) {
                    if (((val >>> b) & 1) != 0) {
                        out[p + (bitPos >>> 3)] |= (byte) (1 << (bitPos & 7));
                    }
                    bitPos++;
                }
            }
            p += flatBytes;
        }
        out[p] = 0; // biome inherit bLen = 0
        return out;
    }

    private static long computeOctreeMask64FromOrdinals(final int[] yzxOrd) {
        long mask = 0L;
        for (int ly = 0; ly < 16; ly++) {
            final int octYShift = (ly >>> 2) << 4;
            final int yBase = ly << 8;
            for (int lz = 0; lz < 16; lz++) {
                final long[] rowBits = OCTANT_BIT_BASE[lz >>> 2];
                final int rowBase = yBase | (lz << 4);
                for (int lx = 0; lx < 16; lx++) {
                    if (yzxOrd[rowBase | lx] != 0) {
                        mask |= rowBits[lx >>> 2] << octYShift;
                    }
                }
            }
        }
        return mask;
    }

    private static int readFlatBitPackOrdinal(final byte[] buf, final int payloadStart, final int pos12, final int flatBits) {
        if (flatBits == 2) {
            return (buf[payloadStart + (pos12 >>> 2)] >>> ((pos12 & 3) << 1)) & 0x3;
        }
        if (flatBits == 1) {
            return (buf[payloadStart + (pos12 >>> 3)] >>> (pos12 & 7)) & 0x1;
        }
        if (flatBits == 4) {
            return (buf[payloadStart + (pos12 >>> 1)] >>> ((pos12 & 1) << 2)) & 0xF;
        }
        int bitPos = pos12 * flatBits;
        int val = 0;
        for (int b = 0; b < flatBits; b++) {
            if (((buf[payloadStart + (bitPos >>> 3)] & 0xFF) & (1 << (bitPos & 7))) != 0) {
                val |= (1 << b);
            }
            bitPos++;
        }
        return val;
    }

    private static int findOrAppendDictId(final int[] dictIds, final int dictSize, final int runtimeStateId) {
        for (int i = 0; i < dictSize; i++) {
            if (dictIds[i] == runtimeStateId) {
                return i;
            }
        }
        return dictSize;
    }

    private static int findSectionEnd(
        final int[] relStarts,
        final int sectionIndex,
        final int sectionCount,
        final int residualRelStart,
        final int frameLen
    ) {
        final int start = relStarts[sectionIndex];
        int end = (residualRelStart > start) ? Math.min(residualRelStart, frameLen) : frameLen;
        for (int j = 0; j < sectionCount; j++) {
            final int other = relStarts[j];
            if (other > start && other < end) {
                end = other;
            }
        }
        return end;
    }

    private static int readVarInt(final byte[] buf, int pos) {
        int value = 0;
        int shift = 0;
        while (pos < buf.length && shift <= 28) {
            final int b = buf[pos++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        return value;
    }

    private static int readNthVarInt(final byte[] buf, int pos, final int targetIndex) {
        for (int i = 0; i < targetIndex; i++) {
            final int v = readVarInt(buf, pos);
            pos += varIntLen(v);
        }
        return readVarInt(buf, pos);
    }

    private static int varIntLen(int v) {
        int len = 1;
        while ((v & ~0x7F) != 0) {
            v >>>= 7;
            len++;
        }
        return len;
    }

    private static int writeVarInt(final byte[] buf, int pos, int v) {
        while ((v & ~0x7F) != 0) {
            buf[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[pos++] = (byte) v;
        return pos;
    }

    private static void writeUint16BE(final byte[] buf, final int off, final int v) {
        buf[off] = (byte) (v >>> 8);
        buf[off + 1] = (byte) v;
    }

    private static void writeLongBE(final byte[] buf, final int off, final long v) {
        buf[off] = (byte) (v >>> 56);
        buf[off + 1] = (byte) (v >>> 48);
        buf[off + 2] = (byte) (v >>> 40);
        buf[off + 3] = (byte) (v >>> 32);
        buf[off + 4] = (byte) (v >>> 24);
        buf[off + 5] = (byte) (v >>> 16);
        buf[off + 6] = (byte) (v >>> 8);
        buf[off + 7] = (byte) v;
    }

    private static int xyToHilbert2D4(int x, int z) {
        int d = 0;
        for (int s = 2; s > 0; s >>= 1) {
            final int rx = (x & s) != 0 ? 1 : 0;
            final int rz = (z & s) != 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ rz);
            if (rz == 0) {
                if (rx == 1) {
                    x = s - 1 - x;
                    z = s - 1 - z;
                }
                final int t = x;
                x = z;
                z = t;
            }
        }
        return d;
    }

    /**
     * 64-byte cache-line-isolated holder for the immutable {@link FrozenReadSnapshot} reference
     * ({@code goldleaf-ADR-002} §2 Tier 0/1).
     *
     * <p>Pre- and post-padding (64 bytes each) guarantees that owning region-thread mutations to
     * {@link ChunkResidencyEntry}'s {@code writeLock}, {@code epoch}, and dirty overlay fields
     * never share a CPU cache line with lock-free async readers, preventing MESI
     * {@code Modified <-> Shared} bouncing without requiring internal {@code @Contended} JVM flags.</p>
     */
    private static final class PaddedSnapshotSlot {
        @SuppressWarnings("unused")
        private long p0, p1, p2, p3, p4, p5, p6, p7;

        volatile @Nullable FrozenReadSnapshot snapshot;

        @SuppressWarnings("unused")
        private long q0, q1, q2, q3, q4, q5, q6, q7;

        PaddedSnapshotSlot(final @Nullable FrozenReadSnapshot initial) {
            this.snapshot = initial;
        }
    }

    private static final class ChunkResidencyEntry {

        private final Object writeLock = new Object();

        private long epoch = 1L;
        private RawChunkFrameSnapshot baseFrame;
        private int dirtySectionMask;
        private int @Nullable [][] dirtyCellsBySection;
        private int[] dirtyCountsBySection;

        // Cache-line-isolated published reference for lock-free Tier 0 / Tier 1 readers (null once unloaded)
        private final PaddedSnapshotSlot publishedSlot;

        ChunkResidencyEntry(final RawChunkFrameSnapshot baseFrame) {
            this.baseFrame = baseFrame;
            final int sectionCount = Math.max(1, baseFrame.sectionCount());
            this.dirtyCellsBySection = new int[sectionCount][];
            this.dirtyCountsBySection = new int[sectionCount];
            this.publishedSlot = new PaddedSnapshotSlot(new FrozenReadSnapshot(baseFrame, 0, null, 1L));
        }

        @Nullable FrozenReadSnapshot publishedSnapshot() {
            return this.publishedSlot.snapshot;
        }

        long captureCommitEpoch() {
            synchronized (this.writeLock) {
                return (this.epoch & UNLOADED_BIT) != 0L ? -1L : this.epoch;
            }
        }

        void markUnloaded() {
            synchronized (this.writeLock) {
                this.epoch = (this.epoch + 1L) | UNLOADED_BIT;
                this.publishedSlot.snapshot = null;
            }
        }

        boolean resetBaseFrame(final RawChunkFrameSnapshot newBase) {
            synchronized (this.writeLock) {
                if ((this.epoch & UNLOADED_BIT) != 0L) {
                    return false;
                }
                final long nextEpoch = ++this.epoch;
                this.baseFrame = newBase;
                this.dirtySectionMask = 0;
                final int sectionCount = Math.max(1, newBase.sectionCount());
                this.dirtyCellsBySection = new int[sectionCount][];
                this.dirtyCountsBySection = new int[sectionCount];
                this.publishedSlot.snapshot = new FrozenReadSnapshot(newBase, 0, null, nextEpoch);
                return true;
            }
        }

        int readLiveRuntimeState(final int sectionIndex, final int localPos12) {
            synchronized (this.writeLock) {
                if ((this.epoch & UNLOADED_BIT) != 0L) {
                    return -1;
                }
                if (sectionIndex < 0 || sectionIndex >= this.dirtyCellsBySection.length) {
                    return -1;
                }
                final int[] cells = this.dirtyCellsBySection[sectionIndex];
                final int count = this.dirtyCountsBySection[sectionIndex];
                if (cells != null && count > 0) {
                    final int targetPrefix = (localPos12 + 1) << 19;
                    for (int i = 0; i < count; i++) {
                        if ((cells[i] & ~RUNTIME_STATE_MASK) == targetPrefix) {
                            return cells[i] & RUNTIME_STATE_MASK;
                        }
                    }
                }
                return readRuntimeStateFromBaseFrame(this.baseFrame, sectionIndex, localPos12);
            }
        }

        boolean recordDirtyCell(final int sectionIndex, final int localPos12, final int packedCell) {
            synchronized (this.writeLock) {
                if ((this.epoch & UNLOADED_BIT) != 0L) {
                    return false;
                }
                if (sectionIndex < 0 || sectionIndex >= this.dirtyCellsBySection.length) {
                    return false;
                }
                int[] cells = this.dirtyCellsBySection[sectionIndex];
                final int count = this.dirtyCountsBySection[sectionIndex];
                final int targetPrefix = (localPos12 + 1) << 19;
                if (cells != null) {
                    for (int i = 0; i < count; i++) {
                        if ((cells[i] & ~RUNTIME_STATE_MASK) == targetPrefix) {
                            cells[i] = packedCell;
                            return true;
                        }
                    }
                    if (count == cells.length) {
                        cells = Arrays.copyOf(cells, cells.length * 2);
                        this.dirtyCellsBySection[sectionIndex] = cells;
                    }
                } else {
                    cells = new int[16];
                    this.dirtyCellsBySection[sectionIndex] = cells;
                }
                cells[count] = packedCell;
                this.dirtyCountsBySection[sectionIndex] = count + 1;
                if (sectionIndex < 32) {
                    this.dirtySectionMask |= (1 << sectionIndex);
                }
                return true;
            }
        }

        @Nullable FrozenReadSnapshot freezeTickEnd() {
            synchronized (this.writeLock) {
                if ((this.epoch & UNLOADED_BIT) != 0L) {
                    return null;
                }
                final long currentEpoch = this.epoch;
                if (this.dirtySectionMask == 0) {
                    final FrozenReadSnapshot clean = new FrozenReadSnapshot(this.baseFrame, 0, null, currentEpoch);
                    this.publishedSlot.snapshot = clean;
                    return clean;
                }
                final int sectionCount = this.dirtyCellsBySection.length;
                final int[][] copied = new int[sectionCount][];
                for (int i = 0; i < sectionCount; i++) {
                    final int count = this.dirtyCountsBySection[i];
                    if (count > 0 && this.dirtyCellsBySection[i] != null) {
                        copied[i] = Arrays.copyOf(this.dirtyCellsBySection[i], count);
                    }
                }
                final FrozenReadSnapshot frozen = new FrozenReadSnapshot(
                    this.baseFrame,
                    this.dirtySectionMask,
                    copied,
                    currentEpoch
                );
                this.publishedSlot.snapshot = frozen;
                return frozen;
            }
        }

        boolean tryCommitSwap(final long expectedEpoch, final RawChunkFrameSnapshot committedBaseFrame) {
            synchronized (this.writeLock) {
                if ((this.epoch & UNLOADED_BIT) != 0L || this.epoch != expectedEpoch) {
                    return false;
                }
                final long nextEpoch = expectedEpoch + 1L;
                this.epoch = nextEpoch;
                this.baseFrame = committedBaseFrame;
                this.dirtySectionMask = 0;
                final int sectionCount = Math.max(1, committedBaseFrame.sectionCount());
                this.dirtyCellsBySection = new int[sectionCount][];
                this.dirtyCountsBySection = new int[sectionCount];
                this.publishedSlot.snapshot = new FrozenReadSnapshot(committedBaseFrame, 0, null, nextEpoch);
                return true;
            }
        }
    }
}
