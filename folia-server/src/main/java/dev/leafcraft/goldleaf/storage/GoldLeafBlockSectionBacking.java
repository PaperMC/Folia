package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.spi.ColdSectionBackingSpi;
import io.github.dailystruggle.anvil.LcaSectionBacking;
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

/**
 * Layer 2 {@link ColdSectionBackingSpi} implementation backed by an immutable {@code .lca}
 * {@link LcaSectionBacking} base frame plus an O(1) 4-byte sparse dirty overlay
 * ({@code goldleaf-ADR-005} Stage 4, {@code leafanvil-ADR-002}, {@code leafanvil-ADR-006}).
 *
 * <p>The underlying {@code .lca} section frame is immutable until flushed/compacted via
 * {@link #replaceCompactedBacking(LcaSectionBacking)}. Reads check the sparse dirty overlay first
 * and fall back to the immutable {@code .lca} frame without inflating vanilla's
 * {@code SimpleBitStorage} {@code long[]} array onto the live {@link PalettedContainer}.</p>
 */
public final class GoldLeafBlockSectionBacking implements ColdSectionBackingSpi<BlockState> {

    private static volatile int cachedGlobalBits;

    private volatile LcaSectionBacking backing;
    private volatile boolean indexChecked;
    private final @org.jspecify.annotations.Nullable String worldId;
    private final int hotPromoteDirtyThreshold;
    private final IntFunction<BlockState> stateById;
    private final ToIntFunction<BlockState> idByState;

    public GoldLeafBlockSectionBacking(final LcaSectionBacking backing) {
        this(backing, null, resolveConfiguredHotPromoteThreshold(), Block::stateById, Block::getId);
    }

    public GoldLeafBlockSectionBacking(
        final LcaSectionBacking backing,
        final @org.jspecify.annotations.Nullable String worldId
    ) {
        this(backing, worldId, resolveConfiguredHotPromoteThreshold(), Block::stateById, Block::getId);
    }

    public GoldLeafBlockSectionBacking(
        final LcaSectionBacking backing,
        final IntFunction<BlockState> stateById,
        final ToIntFunction<BlockState> idByState
    ) {
        this(backing, null, resolveConfiguredHotPromoteThreshold(), stateById, idByState);
    }

    public GoldLeafBlockSectionBacking(
        final LcaSectionBacking backing,
        final @org.jspecify.annotations.Nullable String worldId,
        final IntFunction<BlockState> stateById,
        final ToIntFunction<BlockState> idByState
    ) {
        this(backing, worldId, resolveConfiguredHotPromoteThreshold(), stateById, idByState);
    }

    public GoldLeafBlockSectionBacking(
        final LcaSectionBacking backing,
        final @org.jspecify.annotations.Nullable String worldId,
        final int hotPromoteDirtyThreshold,
        final IntFunction<BlockState> stateById,
        final ToIntFunction<BlockState> idByState
    ) {
        this.backing = Objects.requireNonNull(backing, "backing");
        this.indexChecked = backing.hasHydratedCompactBaseIndex() || backing.isCleanUniform();
        this.worldId = worldId;
        this.hotPromoteDirtyThreshold = Math.max(0, hotPromoteDirtyThreshold);
        this.stateById = Objects.requireNonNull(stateById, "stateById");
        this.idByState = Objects.requireNonNull(idByState, "idByState");
    }

    private static int resolveConfiguredHotPromoteThreshold() {
        final dev.leafcraft.goldleaf.config.GoldLeafConfig cfg = dev.leafcraft.goldleaf.config.GoldLeafConfig.get();
        if (cfg != null && cfg.storage != null) {
            return Math.max(0, cfg.storage.lcaHotPromoteDirtyThreshold);
        }
        return 64;
    }

    public @org.jspecify.annotations.Nullable String worldId() {
        return this.worldId;
    }

    public LcaSectionBacking backing() {
        return this.backing;
    }

    /**
     * Atomically swaps the underlying {@link LcaSectionBacking} after a CoW compaction flush so
     * readers immediately observe the freshly compacted immutable base frame with a cleared dirty
     * overlay.
     */
    public void replaceCompactedBacking(final LcaSectionBacking compacted) {
        this.backing = Objects.requireNonNull(compacted, "compacted");
        this.indexChecked = compacted.hasHydratedCompactBaseIndex() || compacted.isCleanUniform();
    }

    private LcaSectionBacking ensureIndexedForVoxelAccess() {
        final LcaSectionBacking current = this.backing;
        if (!this.indexChecked) {
            current.hydrateCompactBaseIndexIfNeeded();
            this.indexChecked = true;
        }
        return current;
    }

    @Override
    public BlockState get(final int x, final int y, final int z) {
        return this.stateById.apply(ensureIndexedForVoxelAccess().getStateId(x & 15, y & 15, z & 15));
    }

    @Override
    public BlockState getByIndex(final int index) {
        final int x = index & 15;
        final int z = (index >>> 4) & 15;
        final int y = (index >>> 8) & 15;
        return this.stateById.apply(ensureIndexedForVoxelAccess().getStateId(x, y, z));
    }

    @Override
    public void set(final int x, final int y, final int z, final BlockState value) {
        ensureIndexedForVoxelAccess().setAndGetPreviousStateId(
            x & 15,
            y & 15,
            z & 15,
            this.idByState.applyAsInt(value),
            0L,
            null
        );
    }

    @Override
    public void setByIndex(final int index, final BlockState value) {
        final int x = index & 15;
        final int z = (index >>> 4) & 15;
        final int y = (index >>> 8) & 15;
        ensureIndexedForVoxelAccess().setAndGetPreviousStateId(
            x,
            y,
            z,
            this.idByState.applyAsInt(value),
            0L,
            null
        );
    }

    @Override
    public BlockState getAndSet(final int x, final int y, final int z, final BlockState value) {
        final int prevId = ensureIndexedForVoxelAccess().setAndGetPreviousStateId(
            x & 15,
            y & 15,
            z & 15,
            this.idByState.applyAsInt(value),
            0L,
            null
        );
        return this.stateById.apply(prevId);
    }

    @Override
    public boolean maybeHas(final Predicate<BlockState> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return this.backing.testMaybeHas(id -> predicate.test(this.stateById.apply(id)));
    }

    @Override
    public void count(final PalettedContainer.CountConsumer<BlockState> output) {
        Objects.requireNonNull(output, "output");
        this.backing.forEachStateCount((stateId, cnt) -> {
            if (cnt > 0) {
                output.accept(this.stateById.apply(stateId), cnt);
            }
        });
    }

    @Override
    public void forEachDistinct(final Consumer<BlockState> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        this.backing.forEachStateCount((stateId, cnt) -> {
            if (cnt > 0) {
                consumer.accept(this.stateById.apply(stateId));
            }
        });
    }

    @Override
    public int getSerializedSize(final IdMap<BlockState> globalMap) {
        final int globalBits = resolveGlobalPaletteBits(globalMap);
        final LcaSectionBacking current = this.backing;
        final byte[] prebuilt = current.peekPrebuiltCleanVanillaWireSlice(globalBits);
        if (prebuilt != null) {
            return prebuilt.length;
        }
        return current.computeVanillaSerializedSize(globalBits);
    }

    @Override
    public void writeVanilla(final FriendlyByteBuf buffer, final IdMap<BlockState> globalMap) {
        final int globalBits = resolveGlobalPaletteBits(globalMap);
        final LcaSectionBacking current = this.backing;
        final byte[] prebuilt = current.peekPrebuiltCleanVanillaWireSlice(globalBits);
        if (prebuilt != null) {
            buffer.writeBytes(prebuilt);
            return;
        }
        current.writeVanillaPalettedContainer(globalBits, new LcaSectionBacking.VanillaWireSink() {
            @Override
            public void writeByte(final int b) {
                buffer.writeByte(b);
            }

            @Override
            public void writeVarInt(final int v) {
                buffer.writeVarInt(v);
            }

            @Override
            public void writeLong(final long word) {
                buffer.writeLong(word);
            }
        });
    }

    @Override
    public boolean isCleanUniform() {
        return this.backing.isCleanUniform();
    }

    @Override
    public BlockState uniformFallback() {
        return this.stateById.apply(Math.max(0, this.backing.uniformStateId()));
    }

    @Override
    public void copyInto(final PalettedContainer<BlockState> destination) {
        final LcaSectionBacking current = this.backing;
        if (current.isCleanUniform()) {
            destination.paper$installUniform(this.stateById.apply(Math.max(0, current.uniformStateId())));
            return;
        }
        final int[] voxels = current.materializeEphemeralStateIds(null, null);
        int lastId = -1;
        BlockState lastState = null;
        for (int pos12 = 0; pos12 < LcaSectionBacking.BULK_4096_CAPACITY; pos12++) {
            final int id = voxels[pos12];
            if (id != lastId) {
                lastId = id;
                lastState = this.stateById.apply(id);
            }
            destination.set(pos12, lastState);
        }
    }

    @Override
    public void forEachMatchingCoordinate(final Predicate<BlockState> predicate, final IntConsumer pos12Consumer) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(pos12Consumer, "pos12Consumer");
        this.backing.forEachMatchingCoordinate(id -> predicate.test(this.stateById.apply(id)), pos12Consumer);
    }

    @Override
    public int dirtyCount() {
        return this.backing.dirtyCount();
    }

    @Override
    public boolean shouldPromoteToHotMutable() {
        final int threshold = this.hotPromoteDirtyThreshold;
        return threshold > 0 && this.backing.dirtyCount() >= threshold;
    }

    @Override
    public boolean tryDemoteCleanToCold() {
        final LcaSectionBacking current = this.backing;
        if (current.isCleanUniform() || current.dirtyCount() > 0 || !current.hasHydratedCompactBaseIndex()) {
            return false;
        }
        final boolean demoted = current.evictCompactBaseIndexToColdBaseFrame();
        if (demoted) {
            this.indexChecked = false;
        }
        return demoted;
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
