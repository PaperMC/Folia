package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.spi.ColdSectionBackingSpi;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import io.github.dailystruggle.anvil.LcaModeABlockSection;
import io.github.dailystruggle.anvil.LcaSectionBacking;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 {@link ColdSectionBackingSpi} implementation backed directly by an unmodified standard
 * LCA Mode A {@link LcaModeABlockSection} (~451 B/section average on disk and in memory) plus a
 * lazily allocated 4-byte sparse dirty overlay ({@code goldleaf-ADR-005}, {@code anvil-api-ADR-003}).
 *
 * <p>Eliminates VarInt palette rewriting, {@code int[4096]} warm-reload unpacking, and
 * {@code CompactBaseIndex} sidecar allocations while preserving standard LCA Mode A disk
 * compression ({@code ~0.59x} of {@code .mca}).</p>
 */
public final class GoldLeafModeABlockSectionBacking implements ColdSectionBackingSpi<BlockState> {

    private static final int SECTION_VOXELS = LcaModeABlockSection.SECTION_VOXELS;
    private static final int STATE_ID_MASK = 0x7FFFF;
    private static final int INITIAL_DIRTY_TABLE_CAP = 16;

    private static volatile int cachedGlobalBits;

    private final LcaModeABlockSection base;
    private final BlockState[] palette;
    private final int hotPromoteDirtyThreshold;
    private final IntFunction<BlockState> stateById;
    private final ToIntFunction<BlockState> idByState;
    private final @Nullable String worldId;

    // Lazily allocated open-addressed 4-byte overlay: ((pos12 + 1) << 19) | (stateId & 0x7FFFF).
    // Remains null (0 bytes) for 100% of unedited sections.
    private volatile int @Nullable [] dirtyTable;
    private volatile int dirtyCount;

    public record CompactionResult(
        @Nullable GoldLeafModeABlockSectionBacking compactedBacking,
        BlockState uniformFallback
    ) {
    }

    public GoldLeafModeABlockSectionBacking(
        final LcaModeABlockSection base,
        final BlockState[] palette,
        final @Nullable String worldId
    ) {
        this(base, palette, resolveConfiguredHotPromoteThreshold(), Block::stateById, Block::getId, worldId);
    }

    public GoldLeafModeABlockSectionBacking(
        final LcaModeABlockSection base,
        final BlockState[] palette,
        final int hotPromoteDirtyThreshold,
        final IntFunction<BlockState> stateById,
        final ToIntFunction<BlockState> idByState,
        final @Nullable String worldId
    ) {
        this.base = Objects.requireNonNull(base, "base");
        this.palette = Objects.requireNonNull(palette, "palette");
        if (palette.length != base.paletteLength()) {
            throw new IllegalArgumentException("palette length mismatch: " + palette.length + " vs " + base.paletteLength());
        }
        this.hotPromoteDirtyThreshold = Math.max(0, hotPromoteDirtyThreshold);
        this.stateById = Objects.requireNonNull(stateById, "stateById");
        this.idByState = Objects.requireNonNull(idByState, "idByState");
        this.worldId = worldId;
    }

    public static @Nullable GoldLeafModeABlockSectionBacking fromExtracted(
        final @Nullable LcaModeABlockSection extracted,
        final @Nullable String worldId
    ) {
        return fromExtracted(extracted, worldId, Block::stateById, Block::getId);
    }

    public static @Nullable GoldLeafModeABlockSectionBacking fromExtracted(
        final @Nullable LcaModeABlockSection extracted,
        final @Nullable String worldId,
        final IntFunction<BlockState> stateById,
        final ToIntFunction<BlockState> idByState
    ) {
        if (extracted == null) {
            return null;
        }
        final int[] runtimeIds = extracted.paletteRuntimeIds();
        final BlockState[] states = new BlockState[runtimeIds.length];
        for (int i = 0; i < runtimeIds.length; i++) {
            final BlockState st = stateById.apply(runtimeIds[i]);
            if (st == null) {
                return null;
            }
            states[i] = st;
        }
        return new GoldLeafModeABlockSectionBacking(
            extracted,
            states,
            resolveConfiguredHotPromoteThreshold(),
            stateById,
            idByState,
            worldId
        );
    }

    public static @Nullable GoldLeafModeABlockSectionBacking fromRuntimeVoxels(
        final int[] voxels4096,
        final @Nullable String worldId,
        final IntFunction<BlockState> stateById,
        final ToIntFunction<BlockState> idByState
    ) {
        final LcaModeABlockSection encoded = LcaModeABlockSection.encodeFromRuntimeVoxels(voxels4096);
        return fromExtracted(encoded, worldId, stateById, idByState);
    }

    private static int resolveConfiguredHotPromoteThreshold() {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        if (cfg != null && cfg.storage != null) {
            return Math.max(0, cfg.storage.lcaHotPromoteDirtyThreshold);
        }
        return 64;
    }

    public LcaModeABlockSection baseSection() {
        return this.base;
    }

    public BlockState[] palette() {
        return this.palette;
    }

    public @Nullable String worldId() {
        return this.worldId;
    }

    /**
     * Exports sparse dirty-table edits directly in {@code LCA_NET_V1} packed 32-bit
     * {@code dirtyOverlay} format ({@code ((pos12 + 1) << 19) | (stateId & 0x7FFFF)}) without
     * decompressing the underlying {@link LcaModeABlockSection} ({@code goldleaf-ADR-005},
     * {@code S-005}).
     */
    public int[] exportDirtyOverlayEntries() {
        final int[] table = this.dirtyTable;
        final int count = this.dirtyCount;
        if (table == null || count <= 0) {
            return new int[0];
        }
        final int[] out = new int[count];
        int w = 0;
        for (int i = 0; i < table.length && w < count; i++) {
            final int entry = table[i];
            if (entry != 0) {
                out[w++] = entry;
            }
        }
        return w == count ? out : Arrays.copyOf(out, w);
    }

    public int @Nullable [] extractCleanDictVoxels(final int[] paletteDictIds) {
        if (this.dirtyTable != null || paletteDictIds == null || paletteDictIds.length < this.palette.length) {
            return null;
        }
        final int[] ordinals = new int[SECTION_VOXELS];
        this.base.materializeOrdinals(ordinals);
        final int[] out = new int[SECTION_VOXELS];
        final int palLen = this.palette.length;
        for (int i = 0; i < SECTION_VOXELS; i++) {
            final int ord = ordinals[i];
            out[i] = paletteDictIds[ord < palLen ? ord : 0];
        }
        return out;
    }

    public @Nullable CompactionResult compactDirtyOffThread() {
        final int[] table = this.dirtyTable;
        if (table == null || this.dirtyCount == 0) {
            return null;
        }
        final int[] voxels = materializeRuntimeVoxels(table);
        final GoldLeafModeABlockSectionBacking compacted =
            fromRuntimeVoxels(voxels, this.worldId, this.stateById, this.idByState);
        if (compacted == null) {
            final BlockState uniformState = this.stateById.apply(voxels[0]);
            return new CompactionResult(null, uniformState != null ? uniformState : this.palette[0]);
        }
        return new CompactionResult(compacted, compacted.uniformFallback());
    }

    @Override
    public BlockState get(final int x, final int y, final int z) {
        return getByIndex(((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
    }

    @Override
    public BlockState getByIndex(final int index) {
        final int pos12 = index & 0xFFF;
        final int[] table = this.dirtyTable;
        if (table != null) {
            final int dirtyId = lookupDirty(table, pos12);
            if (dirtyId >= 0) {
                final BlockState st = this.stateById.apply(dirtyId);
                return st != null ? st : this.palette[0];
            }
        }
        final int ord = this.base.queryVoxelOrdinal(pos12);
        return this.palette[ord < this.palette.length ? ord : 0];
    }

    @Override
    public void set(final int x, final int y, final int z, final BlockState value) {
        setByIndex(((y & 15) << 8) | ((z & 15) << 4) | (x & 15), value);
    }

    @Override
    public void setByIndex(final int index, final BlockState value) {
        final int pos12 = index & 0xFFF;
        final int newId = this.idByState.applyAsInt(value) & STATE_ID_MASK;
        putDirty(pos12, newId);
    }

    @Override
    public BlockState getAndSet(final int x, final int y, final int z, final BlockState value) {
        final int pos12 = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        final BlockState prev = getByIndex(pos12);
        final int newId = this.idByState.applyAsInt(value) & STATE_ID_MASK;
        putDirty(pos12, newId);
        return prev;
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
                if (entry != 0) {
                    final BlockState st = this.stateById.apply(entry & STATE_ID_MASK);
                    if (st != null && predicate.test(st)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void count(final PalettedContainer.CountConsumer<BlockState> output) {
        Objects.requireNonNull(output, "output");
        final int palLen = this.palette.length;
        final int[] counts = new int[palLen];
        this.base.countOrdinals(counts);
        final int[] table = this.dirtyTable;
        if (table == null || this.dirtyCount == 0) {
            for (int i = 0; i < palLen; i++) {
                if (counts[i] > 0) {
                    output.accept(this.palette[i], counts[i]);
                }
            }
            return;
        }

        final int[] runtimeIds = this.base.paletteRuntimeIds();
        final int[] extraIds = new int[Math.max(4, this.dirtyCount)];
        final int[] extraCounts = new int[extraIds.length];
        int extraLen = 0;

        for (final int entry : table) {
            if (entry == 0) {
                continue;
            }
            final int pos12 = (entry >>> 19) - 1;
            final int newId = entry & STATE_ID_MASK;
            final int baseOrd = this.base.queryVoxelOrdinal(pos12);
            if (baseOrd < palLen) {
                counts[baseOrd]--;
            }
            int matchedPal = -1;
            for (int i = 0; i < palLen; i++) {
                if (runtimeIds[i] == newId) {
                    matchedPal = i;
                    break;
                }
            }
            if (matchedPal >= 0) {
                counts[matchedPal]++;
            } else {
                int found = -1;
                for (int i = 0; i < extraLen; i++) {
                    if (extraIds[i] == newId) {
                        found = i;
                        break;
                    }
                }
                if (found >= 0) {
                    extraCounts[found]++;
                } else {
                    extraIds[extraLen] = newId;
                    extraCounts[extraLen] = 1;
                    extraLen++;
                }
            }
        }
        for (int i = 0; i < palLen; i++) {
            if (counts[i] > 0) {
                output.accept(this.palette[i], counts[i]);
            }
        }
        for (int i = 0; i < extraLen; i++) {
            if (extraCounts[i] > 0) {
                final BlockState st = this.stateById.apply(extraIds[i]);
                if (st != null) {
                    output.accept(st, extraCounts[i]);
                }
            }
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
    public void forEachMatchingCoordinate(final Predicate<BlockState> predicate, final IntConsumer pos12Consumer) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(pos12Consumer, "pos12Consumer");
        final int[] table = this.dirtyTable;
        if (table == null || this.dirtyCount == 0) {
            final int palLen = this.palette.length;
            final boolean[] matching = new boolean[palLen];
            for (int i = 0; i < palLen; i++) {
                matching[i] = predicate.test(this.palette[i]);
            }
            this.base.forEachMatchingCoordinate(matching, pos12Consumer);
            return;
        }
        final int[] voxels = materializeRuntimeVoxels(table);
        for (int i = 0; i < SECTION_VOXELS; i++) {
            final BlockState st = this.stateById.apply(voxels[i]);
            if (st != null && predicate.test(st)) {
                pos12Consumer.accept(i);
            }
        }
    }

    @Override
    public int getSerializedSize(final IdMap<BlockState> globalMap) {
        final int globalBits = resolveGlobalPaletteBits(globalMap);
        final int[] table = this.dirtyTable;
        if (table == null || this.dirtyCount == 0) {
            return computeCleanWireSize(globalBits);
        }
        final int[] voxels = materializeRuntimeVoxels(table);
        final int[] distinct = collectDistinctIds(voxels);
        if (distinct.length == 1) {
            return 1 + varIntSize(distinct[0]);
        }
        if (distinct.length <= 256) {
            final int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(distinct.length - 1));
            final int entriesPerLong = 64 / bits;
            final int numLongs = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
            int size = 1 + varIntSize(distinct.length);
            for (final int id : distinct) {
                size += varIntSize(id);
            }
            return size + (numLongs << 3);
        }
        final int entriesPerLong = 64 / globalBits;
        final int numLongs = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
        return 1 + (numLongs << 3);
    }

    @Override
    public void writeVanilla(final FriendlyByteBuf buffer, final IdMap<BlockState> globalMap) {
        final int globalBits = resolveGlobalPaletteBits(globalMap);
        final int[] table = this.dirtyTable;
        if (table == null || this.dirtyCount == 0) {
            writeCleanWire(buffer, globalBits);
            return;
        }
        final int[] voxels = materializeRuntimeVoxels(table);
        final int[] distinct = collectDistinctIds(voxels);
        if (distinct.length == 1) {
            buffer.writeByte(0);
            buffer.writeVarInt(distinct[0]);
            return;
        }
        if (distinct.length <= 256) {
            final int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(distinct.length - 1));
            buffer.writeByte(bits);
            buffer.writeVarInt(distinct.length);
            for (final int id : distinct) {
                buffer.writeVarInt(id);
            }
            final int entriesPerLong = 64 / bits;
            final int numLongs = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
            int idx = 0;
            for (int w = 0; w < numLongs; w++) {
                long word = 0L;
                int shift = 0;
                for (int e = 0; e < entriesPerLong && idx < SECTION_VOXELS; e++, idx++) {
                    final int st = voxels[idx];
                    int ord = 0;
                    for (int p = 0; p < distinct.length; p++) {
                        if (distinct[p] == st) {
                            ord = p;
                            break;
                        }
                    }
                    word |= ((long) ord) << shift;
                    shift += bits;
                }
                buffer.writeLong(word);
            }
            return;
        }
        buffer.writeByte(globalBits);
        final int entriesPerLong = 64 / globalBits;
        final int numLongs = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
        int idx = 0;
        for (int w = 0; w < numLongs; w++) {
            long word = 0L;
            int shift = 0;
            for (int e = 0; e < entriesPerLong && idx < SECTION_VOXELS; e++, idx++) {
                word |= ((long) (voxels[idx] & STATE_ID_MASK)) << shift;
                shift += globalBits;
            }
            buffer.writeLong(word);
        }
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
        final int[] table = this.dirtyTable;
        if (table == null || this.dirtyCount == 0) {
            final int[] ordinals = new int[SECTION_VOXELS];
            this.base.materializeOrdinals(ordinals);
            final int palLen = this.palette.length;
            for (int i = 0; i < SECTION_VOXELS; i++) {
                final int ord = ordinals[i];
                destination.set(i, this.palette[ord < palLen ? ord : 0]);
            }
            return;
        }
        final int[] voxels = materializeRuntimeVoxels(table);
        int lastId = -1;
        BlockState lastState = null;
        for (int i = 0; i < SECTION_VOXELS; i++) {
            final int id = voxels[i];
            if (id != lastId) {
                lastId = id;
                lastState = this.stateById.apply(id);
            }
            destination.set(i, lastState != null ? lastState : this.palette[0]);
        }
    }

    @Override
    public int dirtyCount() {
        return this.dirtyCount;
    }

    @Override
    public boolean shouldPromoteToHotMutable() {
        final int threshold = this.hotPromoteDirtyThreshold;
        return threshold > 0 && this.dirtyCount >= threshold;
    }

    @Override
    public boolean tryDemoteCleanToCold() {
        // Already compact standard LCA Mode A in memory with zero sidecar index.
        return false;
    }

    @Override
    public synchronized ColdSectionBackingSpi<BlockState> snapshotCopy() {
        final GoldLeafModeABlockSectionBacking copy = new GoldLeafModeABlockSectionBacking(
            this.base,
            this.palette,
            this.hotPromoteDirtyThreshold,
            this.stateById,
            this.idByState,
            this.worldId
        );
        final int[] table = this.dirtyTable;
        if (table != null && this.dirtyCount > 0) {
            copy.dirtyTable = Arrays.copyOf(table, table.length);
            copy.dirtyCount = this.dirtyCount;
        }
        return copy;
    }

    private int computeCleanWireSize(final int globalBits) {
        final int[] runtimeIds = this.base.paletteRuntimeIds();
        if (runtimeIds.length <= 256) {
            final int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(runtimeIds.length - 1));
            final int entriesPerLong = 64 / bits;
            final int numLongs = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
            int size = 1 + varIntSize(runtimeIds.length);
            for (final int id : runtimeIds) {
                size += varIntSize(id);
            }
            return size + (numLongs << 3);
        }
        final int entriesPerLong = 64 / globalBits;
        final int numLongs = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
        return 1 + (numLongs << 3);
    }

    private void writeCleanWire(final FriendlyByteBuf buffer, final int globalBits) {
        final int[] runtimeIds = this.base.paletteRuntimeIds();
        final int[] ordinals = new int[SECTION_VOXELS];
        this.base.materializeOrdinals(ordinals);
        if (runtimeIds.length <= 256) {
            final int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(runtimeIds.length - 1));
            buffer.writeByte(bits);
            buffer.writeVarInt(runtimeIds.length);
            for (final int id : runtimeIds) {
                buffer.writeVarInt(id);
            }
            final int entriesPerLong = 64 / bits;
            final int numLongs = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
            int idx = 0;
            for (int w = 0; w < numLongs; w++) {
                long word = 0L;
                int shift = 0;
                for (int e = 0; e < entriesPerLong && idx < SECTION_VOXELS; e++, idx++) {
                    word |= ((long) ordinals[idx]) << shift;
                    shift += bits;
                }
                buffer.writeLong(word);
            }
            return;
        }
        buffer.writeByte(globalBits);
        final int entriesPerLong = 64 / globalBits;
        final int numLongs = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
        int idx = 0;
        for (int w = 0; w < numLongs; w++) {
            long word = 0L;
            int shift = 0;
            for (int e = 0; e < entriesPerLong && idx < SECTION_VOXELS; e++, idx++) {
                final int ord = ordinals[idx];
                final int st = runtimeIds[ord < runtimeIds.length ? ord : 0];
                word |= ((long) (st & STATE_ID_MASK)) << shift;
                shift += globalBits;
            }
            buffer.writeLong(word);
        }
    }

    private int[] materializeRuntimeVoxels(final int @Nullable [] table) {
        final int[] ordinals = new int[SECTION_VOXELS];
        this.base.materializeOrdinals(ordinals);
        final int[] runtimeIds = this.base.paletteRuntimeIds();
        final int palLen = runtimeIds.length;
        final int[] voxels = new int[SECTION_VOXELS];
        for (int i = 0; i < SECTION_VOXELS; i++) {
            final int ord = ordinals[i];
            voxels[i] = runtimeIds[ord < palLen ? ord : 0];
        }
        if (table != null) {
            for (final int entry : table) {
                if (entry != 0) {
                    final int pos12 = (entry >>> 19) - 1;
                    if (pos12 >= 0 && pos12 < SECTION_VOXELS) {
                        voxels[pos12] = entry & STATE_ID_MASK;
                    }
                }
            }
        }
        return voxels;
    }

    private static int[] collectDistinctIds(final int[] voxels) {
        int[] distinct = new int[16];
        int count = 0;
        for (int i = 0; i < SECTION_VOXELS; i++) {
            final int st = voxels[i];
            boolean found = false;
            for (int j = 0; j < count; j++) {
                if (distinct[j] == st) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (count == distinct.length) {
                    distinct = Arrays.copyOf(distinct, distinct.length << 1);
                }
                distinct[count++] = st;
            }
        }
        return Arrays.copyOf(distinct, count);
    }

    private static int lookupDirty(final int[] table, final int pos12) {
        final int key = pos12 + 1;
        final int mask = table.length - 1;
        int slot = ((pos12 * 0x9E3779B9) >>> 20) & mask;
        while (true) {
            final int entry = table[slot];
            if (entry == 0) {
                return -1;
            }
            if ((entry >>> 19) == key) {
                return entry & STATE_ID_MASK;
            }
            slot = (slot + 1) & mask;
        }
    }

    private void putDirty(final int pos12, final int stateId) {
        int[] table = this.dirtyTable;
        if (table == null) {
            table = new int[INITIAL_DIRTY_TABLE_CAP];
            this.dirtyTable = table;
        }
        if ((this.dirtyCount + 1) * 2 >= table.length) {
            table = rehashDirtyTable(table);
            this.dirtyTable = table;
        }
        final int key = pos12 + 1;
        final int encoded = (key << 19) | (stateId & STATE_ID_MASK);
        final int mask = table.length - 1;
        int slot = ((pos12 * 0x9E3779B9) >>> 20) & mask;
        while (true) {
            final int entry = table[slot];
            if (entry == 0) {
                table[slot] = encoded;
                this.dirtyCount++;
                return;
            }
            if ((entry >>> 19) == key) {
                table[slot] = encoded;
                return;
            }
            slot = (slot + 1) & mask;
        }
    }

    private static int[] rehashDirtyTable(final int[] oldTable) {
        final int newCap = oldTable.length << 1;
        final int[] newTable = new int[newCap];
        final int mask = newCap - 1;
        for (final int entry : oldTable) {
            if (entry == 0) {
                continue;
            }
            final int pos12 = (entry >>> 19) - 1;
            int slot = ((pos12 * 0x9E3779B9) >>> 20) & mask;
            while (newTable[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            newTable[slot] = entry;
        }
        return newTable;
    }

    private static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    private static int resolveGlobalPaletteBits(final IdMap<BlockState> globalMap) {
        if (globalMap != null) {
            final int size = Math.max(2, globalMap.size());
            return 32 - Integer.numberOfLeadingZeros(size - 1);
        }
        int bits = cachedGlobalBits;
        if (bits <= 0) {
            final int regSize = Math.max(2, Block.BLOCK_STATE_REGISTRY.size());
            bits = 32 - Integer.numberOfLeadingZeros(regSize - 1);
            cachedGlobalBits = bits;
        }
        return bits;
    }
}
