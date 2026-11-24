package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.nbt_rw.FastNBTParser;
import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import ca.spottedleaf.moonrise.patches.starlight.storage.StarlightSectionData;
import ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil;
import com.mojang.serialization.Codec;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import io.github.dailystruggle.anvil.LcaDecodedChunkMeta;
import io.github.dailystruggle.anvil.LcaModeABlockSection;
import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.LcaRegionReader;
import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.SavedTick;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 2 default {@link DirectSectionSerializer} implementation for {@code .lca} storage
 * ({@code goldleaf-ADR-001} Stage 2 Tier 2, {@code goldleaf-ADR-005}).
 *
 * <p>Fast-paths current-version {@link SerializableChunkData} serialization by pre-sizing section
 * containers, bypassing empty air-only block-state codec trees when untouched, preserving Folia
 * {@code redstoneGameTime} ticks, Starlight light-state metadata, and {@code ChunkBukkitValues}
 * PDC identically to vanilla, and tracking serialized chunks in {@link LcaResidencyManager}.</p>
 */
public final class LcaDirectSectionSerializer implements DirectSectionSerializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(LcaDirectSectionSerializer.class);
    private static volatile @Nullable Codec<List<SavedTick<Block>>> blockTicksCodec;
    private static volatile @Nullable Codec<List<SavedTick<Fluid>>> fluidTicksCodec;

    /** Marker byte tag recording direct-section fast-path provenance without affecting vanilla readers. */
    public static final String DIRECT_SECTION_TAG = "goldleaf_direct_sections";
    static final String DIRECT_COW_WORLD_TAG = "leafanvil_direct_cow_world";

    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> LAZY_CANONICAL_TO_RUNTIME_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>(256);
    private static final java.util.concurrent.ConcurrentHashMap<BlockState, String> CANONICAL_BLOCK_STATE_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>(512);
    private static final String[] SINGLE_AIR_PALETTE = new String[] {"minecraft:air"};
    private static final Heightmap.Types[] STANDARD_LCA_HEIGHTMAP_TYPES = {
        Heightmap.Types.MOTION_BLOCKING,
        Heightmap.Types.OCEAN_FLOOR,
        Heightmap.Types.WORLD_SURFACE,
        Heightmap.Types.OCEAN_FLOOR_WG,
        Heightmap.Types.WORLD_SURFACE_WG,
        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
    };
    private static volatile @Nullable BiomeHolderCache biomeHolderCache;

    private static final class BiomeHolderCache {
        final WeakReference<Registry<Biome>> registryRef;
        final java.util.concurrent.ConcurrentHashMap<String, net.minecraft.core.Holder<Biome>> map =
            new java.util.concurrent.ConcurrentHashMap<>(64);

        BiomeHolderCache(final Registry<Biome> registry) {
            this.registryRef = new WeakReference<>(registry);
        }
    }

    private final LcaResidencyManager residencyManager;
    private final AtomicLong directChunksSerialized = new AtomicLong();
    private final AtomicLong directChunksDeserialized = new AtomicLong();
    private final AtomicLong coldSectionsAttached = new AtomicLong();
    private final AtomicLong uniformAirSectionsSkipped = new AtomicLong();
    private final AtomicLong zeroNbtCoWSplices = new AtomicLong();
    private final AtomicLong zeroNbtFreshDirectWrites = new AtomicLong();

    public LcaDirectSectionSerializer(final LcaResidencyManager residencyManager) {
        this.residencyManager = Objects.requireNonNull(residencyManager, "residencyManager");
    }

    public static LcaDirectSectionSerializer createDefault() {
        return new LcaDirectSectionSerializer(LcaResidencyManager.global());
    }

    @Override
    public @Nullable CompoundTag trySerializeDirect(final SerializableChunkData data) {
        return trySerializeDirect(data, null);
    }

    @Override
    public @Nullable CompoundTag trySerializeDirect(
        final SerializableChunkData data,
        final @Nullable String explicitWorldId
    ) {
        if (data == null) {
            return null;
        }
        final CompoundTag zeroNbtSentinel = tryCommitZeroNbtCoW(data, explicitWorldId);
        if (zeroNbtSentinel != null) {
            this.directChunksSerialized.incrementAndGet();
            onChunkSerialized(data, zeroNbtSentinel);
            return zeroNbtSentinel;
        }
        final CompoundTag tag = NbtUtils.addCurrentDataVersion(new CompoundTag());
        tag.putInt("xPos", data.chunkPos().x());
        tag.putInt("yPos", data.minSectionY());
        tag.putInt("zPos", data.chunkPos().z());
        tag.putLong("LastUpdate", data.lastUpdateTime());
        tag.putLong("InhabitedTime", data.inhabitedTime());
        tag.putString("Status", BuiltInRegistries.CHUNK_STATUS.getKey(data.chunkStatus()).toString());
        tag.storeNullable("blending_data", BlendingData.Packed.CODEC, data.blendingData());
        tag.storeNullable("below_zero_retrogen", BelowZeroRetrogen.CODEC, data.belowZeroRetrogen());
        if (!data.upgradeData().isEmpty()) {
            tag.put("UpgradeData", data.upgradeData().write());
        }

        final ListTag sectionTags = new ListTag();
        final Codec<PalettedContainer<BlockState>> blockStatesCodec = data.containerFactory().blockStatesContainerCodec();
        final Codec<PalettedContainerRO<net.minecraft.core.Holder<Biome>>> biomeCodec = data.containerFactory().biomeContainerCodec();

        for (final SerializableChunkData.SectionData section : data.sectionData()) {
            final CompoundTag sectionTag = new CompoundTag();
            final LevelChunkSection chunkSection = section.chunkSection();
            if (chunkSection != null) {
                if (chunkSection.hasOnlyAir()) {
                    // Preserve lossless single-entry air palette directly in MC 26.3 BlockState.CODEC format (StringTag for default states)
                    final CompoundTag airBlockStates = new CompoundTag();
                    final ListTag airPalette = new ListTag();
                    airPalette.add(StringTag.valueOf("minecraft:air"));
                    airBlockStates.put("palette", airPalette);
                    sectionTag.put("block_states", airBlockStates);
                    this.uniformAirSectionsSkipped.incrementAndGet();
                } else {
                    sectionTag.store("block_states", blockStatesCodec, chunkSection.getStates());
                }
                sectionTag.store("biomes", biomeCodec, chunkSection.getBiomes());
            }

            if (section.blockLight() != null) {
                sectionTag.putByteArray("BlockLight", section.blockLight().getData());
            }
            if (section.skyLight() != null) {
                sectionTag.putByteArray("SkyLight", section.skyLight().getData());
            }

            final int blockLightState = ((StarlightSectionData) (Object) section).starlight$getBlockLightState();
            final int skyLightState = ((StarlightSectionData) (Object) section).starlight$getSkyLightState();
            if (blockLightState > 0) {
                sectionTag.putInt(SaveUtil.BLOCKLIGHT_STATE_TAG, blockLightState);
            }
            if (skyLightState > 0) {
                sectionTag.putInt(SaveUtil.SKYLIGHT_STATE_TAG, skyLightState);
            }

            if (!sectionTag.isEmpty()) {
                sectionTag.putByte("Y", (byte) section.y());
                sectionTags.add(sectionTag);
            }
        }

        tag.put("sections", sectionTags);
        if (data.lightCorrect()) {
            tag.putBoolean("isLightOn", true);
        }

        final ListTag blockEntityTags = new ListTag();
        blockEntityTags.addAll(data.blockEntities());
        tag.put("block_entities", blockEntityTags);
        if (data.chunkStatus().getChunkType() == ChunkType.PROTOCHUNK) {
            final ListTag entityTags = new ListTag();
            entityTags.addAll(data.entities());
            tag.put("entities", entityTags);
        }

        SerializableChunkData.saveTicks(tag, data.packedTicks());
        tag.put("PostProcessing", SerializableChunkData.packOffsets(data.postProcessingSections()));

        final CompoundTag heightmapsTag = new CompoundTag();
        data.heightmaps().forEach((type, longs) -> heightmapsTag.put(type.getSerializationKey(), new LongArrayTag(longs)));
        tag.put("Heightmaps", heightmapsTag);
        tag.put("structures", data.structureData());

        if (data.persistentDataContainer() != null) {
            tag.put("ChunkBukkitValues", data.persistentDataContainer());
        }

        if (data.lightCorrect() && !data.chunkStatus().isBefore(ChunkStatus.LIGHT)) {
            tag.putBoolean("isLightOn", false);
            tag.putInt(SaveUtil.STARLIGHT_VERSION_TAG, SaveUtil.STARLIGHT_LIGHT_VERSION);
        }

        this.directChunksSerialized.incrementAndGet();
        onChunkSerialized(data, tag);
        return tag;
    }

    /**
     * Attempts a true Stage 2 Tier 2 zero-NBT Copy-on-Write section splice directly into the
     * resident chunk's {@code .lca} archive ({@code goldleaf-ADR-001} Stage 2 Tier 2,
     * {@code goldleaf-ADR-005}). Untouched sections are copied verbatim as binary {@code .lca}
     * slices, dirty sections are encoded directly from {@link PalettedContainer} into region
     * dictionary voxel arrays with zero section {@code CompoundTag} allocations, and a sentinel
     * {@link LcaRegionFileStorage#DIRECT_COW_COMMITTED_TAG} tag is returned so
     * {@link LcaRegionStorageProvider#startWrite} skips {@code NbtIo.write()}.
     */
    private static boolean isRetainRawChunkFrameRequired() {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        return cfg != null && cfg.network != null && cfg.network.lcaNetV1Enabled;
    }

    private @Nullable CompoundTag tryCommitZeroNbtCoW(
        final SerializableChunkData data,
        final @Nullable String explicitWorldId
    ) {
        final int cx = data.chunkPos().x();
        final int cz = data.chunkPos().z();
        String worldId = (explicitWorldId != null && !explicitWorldId.isBlank()) ? explicitWorldId : null;
        if (worldId == null) {
            for (final SerializableChunkData.SectionData section : data.sectionData()) {
                if (section.chunkSection() != null) {
                    final var cold = section.chunkSection().getStates().paper$getColdBacking();
                    if (cold instanceof GoldLeafBlockSectionBacking gl && gl.worldId() != null) {
                        worldId = gl.worldId();
                        break;
                    }
                    if (cold instanceof GoldLeafFlatBlockSectionBacking flat && flat.worldId() != null) {
                        worldId = flat.worldId();
                        break;
                    }
                    if (cold instanceof GoldLeafModeABlockSectionBacking modeA && modeA.worldId() != null) {
                        worldId = modeA.worldId();
                        break;
                    }
                }
            }
        }
        if (worldId == null) {
            worldId = this.residencyManager.findUniqueResidentWorldWithStorage(cx, cz);
        }
        if (worldId == null) {
            return null;
        }
        final LcaRegionStorageProvider provider = this.residencyManager.getStorageProvider(worldId);
        if (provider == null) {
            return null;
        }
        final LcaChunkResidencyCache cache = this.residencyManager.getCacheIfPresent(worldId);
        final long chunkKey = CoordinateUtils.getChunkKey(cx, cz);
        final long commitEpoch = cache != null ? cache.beginCommitEpoch(chunkKey) : -1L;
        final LcaChunkResidencyCache.FrozenReadSnapshot snapshot = cache != null ? cache.getReadSnapshot(chunkKey) : null;
        final Function<String, Integer> runtimeLookup = canonicalToRuntimeStateLookup();
        final RawChunkFrameSnapshot baseSnapshot;
        final byte[] baseFrameBytes;
        int dirtySectionMask;
        if (commitEpoch >= 0L && snapshot != null) {
            baseSnapshot = snapshot.baseFrame();
            baseFrameBytes = baseSnapshot.chunkFrame();
            dirtySectionMask = snapshot.dirtySectionMask();
        } else {
            // Never perform blocking .lca disk I/O on Moonrise's saveExecutor worker threads (S-005):
            // build a lock-free DirectFreshLcaWritePayload envelope and let LcaRegionStorageProvider
            // commit it directly on Moonrise's dedicated I/O threads with zero NBT section allocation.
            return tryBuildDirectFreshLcaEnvelope(data, worldId, provider);
        }

        final int minSectionY = data.minSectionY();
        final SerializableChunkData.SectionData[] sectionsByIndex = new SerializableChunkData.SectionData[32];
        int[] baseRelStarts = null;
        int[] basePalMap = null;
        for (final SerializableChunkData.SectionData section : data.sectionData()) {
            final int idx = section.y() - minSectionY;
            if (idx >= 0 && idx < sectionsByIndex.length) {
                sectionsByIndex[idx] = section;
                if (section.chunkSection() != null) {
                    final var cold = section.chunkSection().getStates().paper$getColdBacking();
                    if (cold != null) {
                        if (cold.dirtyCount() > 0) {
                            dirtySectionMask |= (1 << idx);
                        }
                    } else if (snapshot == null) {
                        final int entry16 = idx < baseSnapshot.sectionCount()
                            ? baseSnapshot.sectionDirectoryEntry16(idx)
                            : -1;
                        if (section.chunkSection().getStates().bitsPerEntry() == 0 && entry16 >= 0) {
                            if (entry16 == 0 && section.chunkSection().hasOnlyAir()) {
                                // Unchanged empty air section.
                            } else if ((entry16 & 0x8000) != 0) {
                                final int uniformId = baseSnapshot.resolveInlineUniformRuntimeStateId(idx, runtimeLookup);
                                final BlockState baseUniform = uniformId >= 0 ? Block.stateById(uniformId) : null;
                                if (baseUniform == null || baseUniform != section.chunkSection().getStates().get(0, 0, 0)) {
                                    dirtySectionMask |= (1 << idx);
                                }
                            } else {
                                if (baseRelStarts == null) {
                                    baseRelStarts = baseSnapshot.computeSectionRelStarts();
                                    basePalMap = baseSnapshot.resolveEffectivePaletteToRuntimeIdMap(runtimeLookup);
                                }
                                final BlockState baseUniform = resolveNonInlineUniformState(
                                    baseSnapshot, baseRelStarts, basePalMap, idx
                                );
                                if (baseUniform == null || baseUniform != section.chunkSection().getStates().get(0, 0, 0)) {
                                    dirtySectionMask |= (1 << idx);
                                }
                            }
                        } else {
                            dirtySectionMask |= (1 << idx);
                        }
                    }
                }
            }
        }

        try {
            final byte[] residualNbt = encodeResidualMetadataNbt(data);
            final int rx = cx >> 5;
            final int rz = cz >> 5;
            final IntFunction<int[]> dirtyVoxelsFn = sectionIndex -> {
                if (sectionIndex < 0 || sectionIndex >= sectionsByIndex.length) {
                    return null;
                }
                final SerializableChunkData.SectionData secData = sectionsByIndex[sectionIndex];
                if (secData == null || secData.chunkSection() == null) {
                    return null;
                }
                try {
                    return extractSectionRegionVoxels(secData.chunkSection(), provider, rx, rz);
                } catch (final IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            };
            final IntFunction<byte[]> skyLightFn = sectionIndex -> {
                if (sectionIndex < 0 || sectionIndex >= sectionsByIndex.length) {
                    return null;
                }
                final SerializableChunkData.SectionData secData = sectionsByIndex[sectionIndex];
                return secData != null && secData.skyLight() != null ? secData.skyLight().getData() : null;
            };
            final IntFunction<byte[]> blockLightFn = sectionIndex -> {
                if (sectionIndex < 0 || sectionIndex >= sectionsByIndex.length) {
                    return null;
                }
                final SerializableChunkData.SectionData secData = sectionsByIndex[sectionIndex];
                return secData != null && secData.blockLight() != null ? secData.blockLight().getData() : null;
            };

            final byte[] committedFrame = provider.commitAndGetSplicedChunkFrameCoW(
                cx,
                cz,
                baseFrameBytes,
                dirtySectionMask,
                dirtyVoxelsFn,
                skyLightFn,
                blockLightFn,
                residualNbt
            );
            if (committedFrame == null) {
                return null;
            }
            final boolean retainFrame = isRetainRawChunkFrameRequired();
            if (dirtySectionMask != 0 || (retainFrame && commitEpoch >= 0L && cache != null)) {
                final RawChunkFrameSnapshot refreshed = provider.readRawChunkFrame(cx, cz, runtimeLookup);
                if (refreshed != null) {
                    if (retainFrame && commitEpoch >= 0L && cache != null) {
                        cache.completeCommitSwap(chunkKey, commitEpoch, refreshed);
                    }
                    flushColdSectionBackingsFromFrame(sectionsByIndex, dirtySectionMask, refreshed, runtimeLookup);
                }
            }
            this.zeroNbtCoWSplices.incrementAndGet();
            final String statusName = BuiltInRegistries.CHUNK_STATUS.getKey(data.chunkStatus()).toString();
            return createDirectCoWCommittedSentinel(worldId, cx, cz, statusName);
        } catch (final IOException | java.io.UncheckedIOException ex) {
            LOGGER.warn(
                "Zero-NBT CoW splice failed for {} ({}, {}); falling back to full direct serialization",
                worldId, cx, cz, ex
            );
            return null;
        }
    }

    record DirectFreshLcaWritePayload(
        SerializableChunkData sourceData,
        int dataVersion,
        long lastUpdate,
        long inhabitedTime,
        String statusName,
        String[][] blockPalettes,
        LcaModeABlockSection[] preEncodedModeA,
        byte[][] preEncodedFlatBytes,
        int[] preEncodedFlatBits,
        int[][] preResolvedDictIds,
        long[] precomputedOctreeMasks,
        boolean[] hasPrecomputedOctreeMask,
        long[][] blockData,
        String[][] biomePalettes,
        long[][] biomeData,
        byte[][] skyLights,
        byte[][] blockLights,
        byte[] residualTail,
        int waveSlotId
    ) {
    }

    private @Nullable CompoundTag tryBuildDirectFreshLcaEnvelope(
        final SerializableChunkData data,
        final String worldId,
        final LcaRegionStorageProvider provider
    ) {
        if (provider.delegate().storageKind() != 0) {
            return null;
        }
        final int cx = data.chunkPos().x();
        final int cz = data.chunkPos().z();
        final int archiveMinSectionY = -4;
        final int archiveSectionCount = 24;
        final String[][] blockPalettes = new String[archiveSectionCount][];
        final LcaModeABlockSection[] preEncodedModeA = new LcaModeABlockSection[archiveSectionCount];
        final byte[][] preEncodedFlatBytes = new byte[archiveSectionCount][];
        final int[] preEncodedFlatBits = new int[archiveSectionCount];
        final int[][] preResolvedDictIds = new int[archiveSectionCount][];
        final long[] precomputedOctreeMasks = new long[archiveSectionCount];
        final boolean[] hasPrecomputedOctreeMask = new boolean[archiveSectionCount];
        final long[][] blockData = new long[archiveSectionCount][];
        final String[][] biomePalettes = new String[archiveSectionCount][];
        final long[][] biomeData = new long[archiveSectionCount][];
        final byte[][] skyLights = new byte[archiveSectionCount][];
        final byte[][] blockLights = new byte[archiveSectionCount][];
        int waveSlotId = -1;
        ListTag lowBoundary = null;
        ListTag highBoundary = null;

        for (final SerializableChunkData.SectionData section : data.sectionData()) {
            final int sy = section.y();
            final int idx = sy - archiveMinSectionY;
            final LevelChunkSection chunkSection = section.chunkSection();
            final int blockLightState = ((StarlightSectionData) (Object) section).starlight$getBlockLightState();
            final int skyLightState = ((StarlightSectionData) (Object) section).starlight$getSkyLightState();

            if (chunkSection != null && idx >= 0 && idx < archiveSectionCount) {
                if (chunkSection.hasOnlyAir()) {
                    blockPalettes[idx] = SINGLE_AIR_PALETTE;
                    blockData[idx] = null;
                    this.uniformAirSectionsSkipped.incrementAndGet();
                } else {
                    final PalettedContainer<BlockState> states = chunkSection.getStates();
                    final var coldBacking = states.paper$getColdBacking();
                    if (coldBacking instanceof GoldLeafModeABlockSectionBacking modeA) {
                        if (modeA.dirtyCount() == 0) {
                            blockPalettes[idx] = canonicalPaletteStrings(modeA.palette());
                            preEncodedModeA[idx] = modeA.baseSection();
                        } else {
                            final GoldLeafModeABlockSectionBacking.CompactionResult compacted = modeA.compactDirtyOffThread();
                            if (compacted == null) {
                                blockPalettes[idx] = canonicalPaletteStrings(modeA.palette());
                                preEncodedModeA[idx] = modeA.baseSection();
                            } else if (compacted.compactedBacking() == null) {
                                blockPalettes[idx] = new String[] {canonicalBlockStateString(compacted.uniformFallback())};
                            } else {
                                blockPalettes[idx] = canonicalPaletteStrings(compacted.compactedBacking().palette());
                                preEncodedModeA[idx] = compacted.compactedBacking().baseSection();
                            }
                        }
                    } else if (coldBacking instanceof GoldLeafFlatBlockSectionBacking flat) {
                        if (waveSlotId < 0 && flat.waveSlotId() >= 0) {
                            waveSlotId = flat.waveSlotId();
                        }
                        if (flat.dirtyCount() == 0) {
                            blockPalettes[idx] = canonicalPaletteStrings(flat.palette());
                            preEncodedFlatBytes[idx] = flat.flatBytes();
                            preEncodedFlatBits[idx] = flat.bitsPerVoxel();
                            preResolvedDictIds[idx] = flat.preResolvedDictIds();
                            if (flat.hasPrecomputedOctreeMask()) {
                                precomputedOctreeMasks[idx] = flat.precomputedOctreeMask64();
                                hasPrecomputedOctreeMask[idx] = true;
                            }
                        } else {
                            final GoldLeafFlatBlockSectionBacking.CompactionResult compacted = flat.compactDirtyOffThread();
                            if (compacted == null) {
                                blockPalettes[idx] = canonicalPaletteStrings(flat.palette());
                                preEncodedFlatBytes[idx] = flat.flatBytes();
                                preEncodedFlatBits[idx] = flat.bitsPerVoxel();
                                preResolvedDictIds[idx] = flat.preResolvedDictIds();
                                if (flat.hasPrecomputedOctreeMask()) {
                                    precomputedOctreeMasks[idx] = flat.precomputedOctreeMask64();
                                    hasPrecomputedOctreeMask[idx] = true;
                                }
                            } else if (compacted.compactedBacking() == null) {
                                blockPalettes[idx] = new String[] {canonicalBlockStateString(compacted.uniformFallback())};
                            } else {
                                blockPalettes[idx] = canonicalPaletteStrings(compacted.compactedBacking().palette());
                                preEncodedFlatBytes[idx] = compacted.compactedBacking().flatBytes();
                                preEncodedFlatBits[idx] = compacted.compactedBacking().bitsPerVoxel();
                            }
                        }
                    } else if (coldBacking != null) {
                        return null;
                    } else {
                        final PalettedContainer.Data<BlockState> bd = states.data;
                        if (bd.configuration() instanceof net.minecraft.world.level.chunk.Configuration.Global) {
                            return null;
                        }
                        final int bits = bd.storage().getBits();
                        final int palSize = bd.palette().getSize();
                        if (palSize <= 0) {
                            return null;
                        }
                        if (bits == 0 || palSize == 1) {
                            blockPalettes[idx] = new String[] {canonicalBlockStateString(bd.palette().valueFor(0))};
                            blockData[idx] = null;
                        } else {
                            final int expectedBits = Math.max(4, net.minecraft.util.Mth.ceillog2(palSize));
                            if (bits != expectedBits) {
                                return null;
                            }
                            final String[] pal = new String[palSize];
                            for (int p = 0; p < palSize; p++) {
                                pal[p] = canonicalBlockStateString(bd.palette().valueFor(p));
                            }
                            blockPalettes[idx] = pal;
                            blockData[idx] = bd.storage().getRaw();
                        }
                    }
                }

                final PalettedContainerRO<net.minecraft.core.Holder<Biome>> biomesRo = chunkSection.getBiomes();
                if (!(biomesRo instanceof PalettedContainer<net.minecraft.core.Holder<Biome>> biomeContainer)) {
                    return null;
                }
                final PalettedContainer.Data<net.minecraft.core.Holder<Biome>> biomeD = biomeContainer.data;
                if (biomeD.configuration() instanceof net.minecraft.world.level.chunk.Configuration.Global) {
                    return null;
                }
                final int bBits = biomeD.storage().getBits();
                final int bPalSize = biomeD.palette().getSize();
                if (bPalSize <= 0) {
                    return null;
                }
                if (bBits == 0 || bPalSize == 1) {
                    biomePalettes[idx] = new String[] {canonicalBiomeString(biomeD.palette().valueFor(0))};
                    biomeData[idx] = null;
                } else {
                    final int expectedBBits = net.minecraft.util.Mth.ceillog2(bPalSize);
                    if (bBits != expectedBBits) {
                        return null;
                    }
                    final String[] bPal = new String[bPalSize];
                    for (int p = 0; p < bPalSize; p++) {
                        bPal[p] = canonicalBiomeString(biomeD.palette().valueFor(p));
                    }
                    biomePalettes[idx] = bPal;
                    biomeData[idx] = biomeD.storage().getRaw();
                }

                skyLights[idx] = section.skyLight() != null
                    ? section.skyLight().getData()
                    : (skyLightState == 1 ? LcaRegionFileStorage.allZeroLight2048() : null);
                blockLights[idx] = section.blockLight() != null
                    ? section.blockLight().getData()
                    : (blockLightState == 1 ? LcaRegionFileStorage.allZeroLight2048() : null);
            } else {
                final CompoundTag bSec = new CompoundTag();
                if (section.blockLight() != null) {
                    bSec.putByteArray("BlockLight", section.blockLight().getData());
                }
                if (section.skyLight() != null) {
                    bSec.putByteArray("SkyLight", section.skyLight().getData());
                }
                if (blockLightState > 0) {
                    bSec.putInt(SaveUtil.BLOCKLIGHT_STATE_TAG, blockLightState);
                }
                if (skyLightState > 0) {
                    bSec.putInt(SaveUtil.SKYLIGHT_STATE_TAG, skyLightState);
                }
                if (!bSec.isEmpty()) {
                    bSec.putByte("Y", (byte) sy);
                    if (sy < archiveMinSectionY) {
                        if (lowBoundary == null) {
                            lowBoundary = new ListTag();
                        }
                        lowBoundary.add(bSec);
                    } else {
                        if (highBoundary == null) {
                            highBoundary = new ListTag();
                        }
                        highBoundary.add(bSec);
                    }
                }
            }
        }

        boolean pinnedWaveSave = false;
        try {
            final byte[] residualTail = encodeResidualMetadataNbt(data, lowBoundary, highBoundary);
            final String statusName = BuiltInRegistries.CHUNK_STATUS.getKey(data.chunkStatus()).toString();
            final int dataVersion = net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
            if (waveSlotId >= 0) {
                pinnedWaveSave = WaveChunkArena.global().tryBeginSave(waveSlotId);
            }
            final DirectFreshLcaWritePayload payload = new DirectFreshLcaWritePayload(
                data,
                dataVersion,
                data.lastUpdateTime(),
                data.inhabitedTime(),
                statusName,
                blockPalettes,
                preEncodedModeA,
                preEncodedFlatBytes,
                preEncodedFlatBits,
                preResolvedDictIds,
                precomputedOctreeMasks,
                hasPrecomputedOctreeMask,
                blockData,
                biomePalettes,
                biomeData,
                skyLights,
                blockLights,
                residualTail,
                pinnedWaveSave ? waveSlotId : -1
            );
            final CompoundTag envelope = NbtUtils.addCurrentDataVersion(new CompoundTag());
            envelope.putInt("xPos", cx);
            envelope.putInt("zPos", cz);
            envelope.putString("Status", statusName);
            envelope.paper$setDirectLoadAttachment(payload);
            this.zeroNbtFreshDirectWrites.incrementAndGet();
            return envelope;
        } catch (final IOException | RuntimeException ex) {
            if (pinnedWaveSave) {
                WaveChunkArena.global().completeSave(waveSlotId);
            }
            LOGGER.warn(
                "Zero-NBT fresh direct .lca envelope build failed for {} ({}, {}); falling back to full direct serialization",
                worldId, cx, cz, ex
            );
            return null;
        }
    }

    private static String[] canonicalPaletteStrings(final BlockState[] states) {
        final String[] out = new String[states.length];
        for (int i = 0; i < states.length; i++) {
            out[i] = canonicalBlockStateString(states[i]);
        }
        return out;
    }

    private static String canonicalBiomeString(final net.minecraft.core.Holder<Biome> holder) {
        return holder.unwrapKey()
            .map(key -> key.identifier().toString())
            .orElse("minecraft:plains");
    }

    public long zeroNbtFreshDirectWrites() {
        return this.zeroNbtFreshDirectWrites.get();
    }

    private static @Nullable BlockState resolveNonInlineUniformState(
        final RawChunkFrameSnapshot baseSnapshot,
        final int[] relStarts,
        final int[] palMap,
        final int sectionIdx
    ) {
        if (relStarts == null || palMap == null || sectionIdx < 0 || sectionIdx >= relStarts.length) {
            return null;
        }
        final int relStart = relStarts[sectionIdx];
        if (relStart < 0) {
            return null;
        }
        final byte[] buf = baseSnapshot.chunkFrame();
        int pos = baseSnapshot.sectionDirectoryEnd() + relStart;
        if (pos + 2 >= buf.length) {
            return null;
        }
        final int sectionHeader = buf[pos++] & 0xFF;
        final int blockMode = (sectionHeader >>> 4) & 3;
        if (blockMode != 0) { // 0 == BLOCK_MODE_UNIFORM
            return null;
        }
        int paletteLen = 0;
        int shift = 0;
        while (pos < buf.length && shift <= 28) {
            final int b = buf[pos++] & 0xFF;
            paletteLen |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        if (paletteLen != 1) {
            return null;
        }
        int diskId = 0;
        shift = 0;
        while (pos < buf.length && shift <= 28) {
            final int b = buf[pos++] & 0xFF;
            diskId |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        if (diskId < 0 || diskId >= palMap.length) {
            return null;
        }
        final int runtimeId = palMap[diskId];
        return runtimeId >= 0 ? Block.stateById(runtimeId) : null;
    }

    private static void flushColdSectionBackingsFromFrame(
        final SerializableChunkData.SectionData[] sectionsByIndex,
        final int dirtySectionMask,
        final RawChunkFrameSnapshot refreshed,
        final Function<String, Integer> runtimeLookup
    ) {
        if (dirtySectionMask == 0) {
            return;
        }
        final int[] dictToRuntime = refreshed.dictIdToRuntimeId();
        if (dictToRuntime == null || dictToRuntime.length == 0) {
            return;
        }
        for (int idx = 0; idx < sectionsByIndex.length && idx < refreshed.sectionCount(); idx++) {
            if ((dirtySectionMask & (1 << idx)) == 0) {
                continue;
            }
            final SerializableChunkData.SectionData sd = sectionsByIndex[idx];
            if (sd == null || sd.chunkSection() == null) {
                continue;
            }
            final var existingCold = sd.chunkSection().getStates().paper$getColdBacking();
            if (existingCold instanceof GoldLeafModeABlockSectionBacking || existingCold instanceof GoldLeafFlatBlockSectionBacking) {
                final String existingWorldId = existingCold instanceof GoldLeafModeABlockSectionBacking m
                    ? m.worldId()
                    : ((GoldLeafFlatBlockSectionBacking) existingCold).worldId();
                final int entry16 = refreshed.sectionDirectoryEntry16(idx);
                if (entry16 == 0 || (entry16 & 0x8000) != 0) {
                    final int uniformId = refreshed.resolveInlineUniformRuntimeStateId(idx, runtimeLookup);
                    final BlockState uniformState = uniformId >= 0 ? Block.stateById(uniformId) : null;
                    if (uniformState != null) {
                        sd.chunkSection().getStates().paper$installUniform(uniformState);
                    }
                    continue;
                }
                final LcaModeABlockSection modeASec = refreshed.tryExtractModeABlockSection(idx, runtimeLookup);
                final GoldLeafModeABlockSectionBacking cleanModeA =
                    modeASec != null ? GoldLeafModeABlockSectionBacking.fromExtracted(modeASec, existingWorldId) : null;
                if (cleanModeA != null) {
                    sd.chunkSection().getStates().paper$attachColdBacking(cleanModeA, cleanModeA.uniformFallback());
                }
                continue;
            }
            if (!(existingCold instanceof GoldLeafBlockSectionBacking coldBacking)) {
                continue;
            }
            final byte[] sectionFrame = refreshed.sliceSectionFrameWithRuntimePalette(idx, runtimeLookup);
            final int uniformId = refreshed.resolveInlineUniformRuntimeStateId(idx, runtimeLookup);
            if (uniformId < 0) {
                continue;
            }
            final LcaSectionBacking compacted = LcaSectionBacking.fromColdSectionFrameHydrated(
                sectionFrame,
                uniformId,
                id -> {
                    final BlockState s = Block.stateById(id);
                    return s != null && !s.isAir();
                },
                id -> {
                    final BlockState s = Block.stateById(id);
                    return s != null && s.isRandomlyTicking();
                },
                id -> {
                    final BlockState s = Block.stateById(id);
                    return s != null && s.getFluidState().isRandomlyTicking();
                }
            );
            coldBacking.replaceCompactedBacking(compacted);
        }
    }

    /**
     * Direct zero-NBT CoW splice entry point for callers/tests with explicit world ID and dirty
     * section voxel arrays, bypassing section {@code CompoundTag} construction and {@code NbtIo.write()}.
     */
    public @Nullable CompoundTag commitDirectCoW(
        final String worldId,
        final int chunkX,
        final int chunkZ,
        final int dirtySectionMask,
        final IntFunction<int[]> dirtySectionVoxels,
        final @Nullable IntFunction<byte[]> updatedSkyLight,
        final @Nullable IntFunction<byte[]> updatedBlockLight,
        final byte @Nullable [] residualMetadataNbt
    ) throws IOException {
        Objects.requireNonNull(worldId, "worldId");
        final LcaChunkResidencyCache cache = this.residencyManager.getCacheIfPresent(worldId);
        final LcaRegionStorageProvider provider = this.residencyManager.getStorageProvider(worldId);
        if (cache == null || provider == null) {
            return null;
        }
        final long chunkKey = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        final long commitEpoch = cache.beginCommitEpoch(chunkKey);
        final LcaChunkResidencyCache.FrozenReadSnapshot snapshot = cache.getReadSnapshot(chunkKey);
        if (commitEpoch < 0L || snapshot == null) {
            return null;
        }
        final byte[] committedFrame = provider.commitAndGetSplicedChunkFrameCoW(
            chunkX,
            chunkZ,
            snapshot.baseFrame().chunkFrame(),
            dirtySectionMask,
            dirtySectionVoxels,
            updatedSkyLight,
            updatedBlockLight,
            residualMetadataNbt
        );
        if (committedFrame == null) {
            return null;
        }
        final RawChunkFrameSnapshot refreshed = provider.readRawChunkFrame(
            chunkX,
            chunkZ,
            canonicalToRuntimeStateLookup()
        );
        if (refreshed != null) {
            cache.completeCommitSwap(chunkKey, commitEpoch, refreshed);
        }
        this.zeroNbtCoWSplices.incrementAndGet();
        return createDirectCoWCommittedSentinel(worldId, chunkX, chunkZ);
    }

    static CompoundTag createDirectCoWCommittedSentinel(
        final @Nullable String worldId,
        final int chunkX,
        final int chunkZ
    ) {
        return createDirectCoWCommittedSentinel(worldId, chunkX, chunkZ, "minecraft:full");
    }

    static CompoundTag createDirectCoWCommittedSentinel(
        final @Nullable String worldId,
        final int chunkX,
        final int chunkZ,
        final String statusName
    ) {
        final CompoundTag sentinel = NbtUtils.addCurrentDataVersion(new CompoundTag());
        sentinel.putBoolean(LcaRegionFileStorage.DIRECT_COW_COMMITTED_TAG, true);
        sentinel.putInt("xPos", chunkX);
        sentinel.putInt("zPos", chunkZ);
        sentinel.putString("Status", statusName != null && !statusName.isBlank() ? statusName : "minecraft:full");
        if (worldId != null && !worldId.isBlank()) {
            sentinel.putString(DIRECT_COW_WORLD_TAG, worldId);
        }
        return sentinel;
    }

    static byte[] encodeResidualMetadataNbt(final SerializableChunkData data) throws IOException {
        return encodeResidualMetadataNbt(data, null, null);
    }

    static byte[] encodeResidualMetadataNbt(
        final SerializableChunkData data,
        final @Nullable ListTag lowBoundarySections,
        final @Nullable ListTag highBoundarySections
    ) throws IOException {
        final CompoundTag tag = new CompoundTag();
        if (lowBoundarySections != null && !lowBoundarySections.isEmpty()) {
            tag.put("_lca_boundary_low", lowBoundarySections);
        }
        if (highBoundarySections != null && !highBoundarySections.isEmpty()) {
            tag.put("_lca_boundary_high", highBoundarySections);
        }
        tag.storeNullable("blending_data", BlendingData.Packed.CODEC, data.blendingData());
        tag.storeNullable("below_zero_retrogen", BelowZeroRetrogen.CODEC, data.belowZeroRetrogen());
        if (!data.upgradeData().isEmpty()) {
            tag.put("UpgradeData", data.upgradeData().write());
        }
        final boolean hasStarlightVersion = data.lightCorrect() && !data.chunkStatus().isBefore(ChunkStatus.LIGHT);
        if (hasStarlightVersion) {
            tag.putBoolean("isLightOn", false);
        } else if (data.lightCorrect()) {
            tag.putBoolean("isLightOn", true);
        }
        final ListTag blockEntityTags = new ListTag();
        blockEntityTags.addAll(data.blockEntities());
        tag.put("block_entities", blockEntityTags);
        if (data.chunkStatus().getChunkType() == ChunkType.PROTOCHUNK) {
            final ListTag entityTags = new ListTag();
            entityTags.addAll(data.entities());
            tag.put("entities", entityTags);
        }
        SerializableChunkData.saveTicks(tag, data.packedTicks());
        tag.put("PostProcessing", SerializableChunkData.packOffsets(data.postProcessingSections()));
        final CompoundTag heightmapsTag = new CompoundTag();
        data.heightmaps().forEach((type, longs) -> heightmapsTag.put(type.getSerializationKey(), new LongArrayTag(longs)));
        tag.put("Heightmaps", heightmapsTag);
        tag.put("structures", data.structureData());
        if (data.persistentDataContainer() != null) {
            tag.put("ChunkBukkitValues", data.persistentDataContainer());
        }
        final ByteArrayOutputStream rawOut = new ByteArrayOutputStream(1024);
        // .lca residual tail header: uint16 bpFlags (bit 10 = raw NBT root follows, bit 6 = starlight.light_version varint)
        final int bpFlags = 1024 | (hasStarlightVersion ? 64 : 0);
        rawOut.write((bpFlags >>> 8) & 0xFF);
        rawOut.write(bpFlags & 0xFF);
        if (hasStarlightVersion) {
            int v = SaveUtil.STARLIGHT_LIGHT_VERSION;
            while ((v & ~0x7F) != 0) {
                rawOut.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            rawOut.write(v);
        }
        try (DataOutputStream dataOut = new DataOutputStream(rawOut)) {
            NbtIo.write(tag, dataOut);
        }
        return rawOut.toByteArray();
    }

    private static int[] extractSectionRegionVoxels(
        final LevelChunkSection section,
        final LcaRegionStorageProvider provider,
        final int regionX,
        final int regionZ
    ) throws IOException {
        final int[] voxels = new int[4096];
        if (section.hasOnlyAir()) {
            final int airId = provider.delegate().registerBlockStateInRegion(regionX, regionZ, "minecraft:air");
            if (airId != 0) {
                java.util.Arrays.fill(voxels, airId);
            }
            return voxels;
        }
        final PalettedContainer<BlockState> states = section.getStates();
        final var cold = states.paper$getColdBacking();
        if (cold instanceof GoldLeafModeABlockSectionBacking modeA && modeA.dirtyCount() == 0) {
            final BlockState[] pal = modeA.palette();
            final int[] palDictIds = new int[pal.length];
            for (int p = 0; p < pal.length; p++) {
                palDictIds[p] = provider.delegate().registerBlockStateInRegion(
                    regionX, regionZ, canonicalBlockStateString(pal[p])
                );
            }
            final int[] fast = modeA.extractCleanDictVoxels(palDictIds);
            if (fast != null) {
                return fast;
            }
        }
        if (cold instanceof GoldLeafFlatBlockSectionBacking flat && flat.dirtyCount() == 0) {
            final BlockState[] pal = flat.palette();
            final int[] palDictIds = new int[pal.length];
            for (int p = 0; p < pal.length; p++) {
                palDictIds[p] = provider.delegate().registerBlockStateInRegion(
                    regionX, regionZ, canonicalBlockStateString(pal[p])
                );
            }
            final int[] fast = flat.extractCleanDictVoxels(palDictIds);
            if (fast != null) {
                return fast;
            }
        }
        final IdentityHashMap<BlockState, Integer> localStateCache = new IdentityHashMap<>(32);
        for (int y = 0; y < 16; y++) {
            final int yBase = y << 8;
            for (int z = 0; z < 16; z++) {
                final int yzBase = yBase | (z << 4);
                for (int x = 0; x < 16; x++) {
                    final BlockState state = states.get(x, y, z);
                    Integer dictId = localStateCache.get(state);
                    if (dictId == null) {
                        dictId = provider.delegate().registerBlockStateInRegion(
                            regionX, regionZ, canonicalBlockStateString(state)
                        );
                        localStateCache.put(state, dictId);
                    }
                    voxels[yzBase | x] = dictId;
                }
            }
        }
        return voxels;
    }

    /**
     * Formats a {@link BlockState} into {@code anvil-api}'s canonical sorted-property dictionary key
     * (e.g., {@code minecraft:stone} or {@code minecraft:oak_stairs[facing=north,half=bottom]}).
     */
    public static String canonicalBlockStateString(final BlockState state) {
        final String cached = CANONICAL_BLOCK_STATE_CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        final String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        final var properties = state.getProperties();
        if (properties.isEmpty()) {
            CANONICAL_BLOCK_STATE_CACHE.putIfAbsent(state, name);
            return name;
        }
        final TreeMap<String, String> sorted = new TreeMap<>();
        for (final Property<?> prop : properties) {
            sorted.put(prop.getName(), propertyValueName(prop, state.getValue(prop)));
        }
        final StringBuilder sb = new StringBuilder(name.length() + sorted.size() * 16);
        sb.append(name).append('[');
        boolean first = true;
        for (final Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        sb.append(']');
        final String formatted = sb.toString();
        CANONICAL_BLOCK_STATE_CACHE.putIfAbsent(state, formatted);
        return formatted;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String propertyValueName(final Property<T> prop, final Comparable<?> value) {
        return prop.getName((T) value);
    }

    /**
     * Direct zero-decode deserialization for loaded {@code .lca} chunks ({@code goldleaf-ADR-005}
     * Stage 4): bypasses {@code PalettedContainer.codecRW.parse(NbtOps.INSTANCE, ...)} and attaches
     * immutable {@link GoldLeafBlockSectionBacking} ({@link LcaSectionBacking}) shells directly onto
     * {@code ZeroBitStorage}-evicted {@link PalettedContainer}s while preserving biomes, Starlight
     * light states, Folia ticks, entities, block entities, structures, heightmaps, and PDC.
     */
    @Override
    public @Nullable SerializableChunkData tryDeserializeDirect(
        final ServerLevel level,
        final PalettedContainerFactory containerFactory,
        final CompoundTag chunkData
    ) {
        if (level == null || containerFactory == null || chunkData == null) {
            return null;
        }
        final Object directAttachment = chunkData.paper$getDirectLoadAttachment();
        if (directAttachment instanceof DirectFreshLcaWritePayload freshPayload) {
            // In-flight chunk reload before Moonrise's I/O thread flushes the direct fresh-write
            // envelope: return the already-copied SerializableChunkData directly without clearing
            // the attachment so the pending I/O write still commits it cleanly.
            return freshPayload.sourceData();
        }
        if (directAttachment instanceof LcaRegionStorageProvider.DirectLcaLoadPayload payload) {
            chunkData.paper$setDirectLoadAttachment(null);
            final SerializableChunkData directResult = deserializeFromDirectPayload(
                level, containerFactory, chunkData, payload
            );
            if (directResult != null) {
                return directResult;
            }
            return fallbackFullNbtDeserialize(level, containerFactory, payload);
        }
        if (chunkData.getBooleanOr(LcaRegionFileStorage.DIRECT_COW_COMMITTED_TAG, false)) {
            return deserializeFromDirectCoWSentinel(level, containerFactory, chunkData);
        }
        if (!GoldLeafConfig.get().storage.lcaNativeSectionBacking) {
            return null;
        }
        final int chunkX = chunkData.getIntOr("xPos", 0);
        final int chunkZ = chunkData.getIntOr("zPos", 0);
        final String worldId = level.dimension().identifier().toString();
        final LcaChunkResidencyCache cache = this.residencyManager.getCacheIfPresent(worldId);
        if (cache == null) {
            return null;
        }
        final long chunkKey = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        final LcaChunkResidencyCache.FrozenReadSnapshot snapshot = cache.getReadSnapshot(chunkKey);
        if (snapshot == null) {
            return null;
        }
        final RawChunkFrameSnapshot frame = snapshot.baseFrame();
        final int[] dictToRuntime = frame.dictIdToRuntimeId();
        if (dictToRuntime == null || dictToRuntime.length == 0) {
            return null;
        }
        for (final int runtimeId : dictToRuntime) {
            if (runtimeId < 0) {
                return null;
            }
        }
        try {
            final ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
            final long lastUpdateTime = chunkData.getLongOr("LastUpdate", 0L);
            final long inhabitedTime = chunkData.getLongOr("InhabitedTime", 0L);
            final ChunkStatus status = chunkData.read("Status", ChunkStatus.CODEC).orElse(ChunkStatus.EMPTY);
            final UpgradeData upgradeData = chunkData.getCompound("UpgradeData")
                .map(tag -> new UpgradeData(tag, level))
                .orElse(UpgradeData.EMPTY);
            final boolean lightCorrect = status.isOrAfter(ChunkStatus.LIGHT)
                && chunkData.get("isLightOn") != null
                && chunkData.getIntOr(SaveUtil.STARLIGHT_VERSION_TAG, -1) == SaveUtil.STARLIGHT_LIGHT_VERSION;
            final BlendingData.Packed blendingData = chunkData.read("blending_data", BlendingData.Packed.CODEC).orElse(null);
            final BelowZeroRetrogen belowZeroRetrogen = chunkData.read("below_zero_retrogen", BelowZeroRetrogen.CODEC).orElse(null);
            final Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
            chunkData.getCompound("Heightmaps").ifPresent(heightmapsTag -> {
                for (final Heightmap.Types type : Heightmap.Types.values()) {
                    heightmapsTag.getLongArray(type.getSerializationKey()).ifPresent(longs -> heightmaps.put(type, longs));
                }
            });
            final List<SavedTick<Block>> blockTicks = SavedTick.filterTickListForChunk(
                chunkData.read("block_ticks", blockTicksCodec()).orElse(List.of()),
                chunkPos
            );
            final List<SavedTick<Fluid>> fluidTicks = SavedTick.filterTickListForChunk(
                chunkData.read("fluid_ticks", fluidTicksCodec()).orElse(List.of()),
                chunkPos
            );
            final ChunkAccess.PackedTicks packedTicks = new ChunkAccess.PackedTicks(blockTicks, fluidTicks);
            final ListTag postProcessTags = chunkData.getListOrEmpty("PostProcessing");
            final ShortList[] postProcessingSections = new ShortList[postProcessTags.size()];
            for (int sectionIndex = 0; sectionIndex < postProcessTags.size(); sectionIndex++) {
                final ListTag offsetsTag = postProcessTags.getList(sectionIndex).orElse(null);
                if (offsetsTag != null && !offsetsTag.isEmpty()) {
                    final ShortList packedOffsets = new ShortArrayList(offsetsTag.size());
                    for (int i = 0; i < offsetsTag.size(); i++) {
                        packedOffsets.add(offsetsTag.getShortOr(i, (short) 0));
                    }
                    postProcessingSections[sectionIndex] = packedOffsets;
                }
            }
            final List<CompoundTag> entities = chunkData.getList("entities").stream().flatMap(ListTag::compoundStream).toList();
            final List<CompoundTag> blockEntities = chunkData.getList("block_entities").stream().flatMap(ListTag::compoundStream).toList();
            final CompoundTag structureData = chunkData.getCompoundOrEmpty("structures");
            final ListTag sectionTags = chunkData.getListOrEmpty("sections");
            final List<SerializableChunkData.SectionData> sectionData = new ArrayList<>(sectionTags.size());
            final Codec<PalettedContainer<net.minecraft.core.Holder<Biome>>> biomesCodec = containerFactory.biomeContainerRWCodec();
            final Function<String, Integer> runtimeLookup = canonicalToRuntimeStateLookup();
            long attachedInChunk = 0L;
            int[] frameRelStarts = null;
            int[] framePalMap = null;

            for (int i = 0; i < sectionTags.size(); i++) {
                final Optional<CompoundTag> maybeSectionTag = sectionTags.getCompound(i);
                if (maybeSectionTag.isEmpty()) {
                    continue;
                }
                final CompoundTag sectionTag = maybeSectionTag.get();
                final int y = sectionTag.getByteOr("Y", (byte) 0);
                final LevelChunkSection section;
                if (y >= level.getMinSectionY() && y <= level.getMaxSectionY()) {
                    final int frameSectionIdx = y - frame.minSectionY();
                    if (frameSectionIdx < 0 || frameSectionIdx >= frame.sectionCount()) {
                        return null;
                    }
                    final int entry16 = frame.sectionDirectoryEntry16(frameSectionIdx);
                    final PalettedContainer<BlockState> blocks = containerFactory.createForBlockStates(level, chunkPos, y);
                    final PalettedContainer<net.minecraft.core.Holder<Biome>> biomes = sectionTag.getCompound("biomes")
                        .map(container -> biomesCodec.parse(NbtOps.INSTANCE, container)
                            .getOrThrow(SerializableChunkData.ChunkReadException::new))
                        .orElseGet(containerFactory::createForBiomes);
                    if (entry16 == 0 || (entry16 & 0x8000) != 0) {
                        // Uniform or empty section (~66.7% of terrain): keep native ZeroBitStorage + SingleValuePalette
                        // with zero LcaSectionBacking / GoldLeafBlockSectionBacking wrapper allocations.
                        final int uniformId = frame.resolveInlineUniformRuntimeStateId(frameSectionIdx, runtimeLookup);
                        if (uniformId < 0) {
                            return null;
                        }
                        final BlockState uniformState = Block.stateById(uniformId);
                        if (uniformState == null) {
                            return null;
                        }
                        blocks.paper$installUniform(uniformState);
                        section = new LevelChunkSection(blocks, biomes);
                    } else {
                        final BlockState airFallback = Block.stateById(0);
                        if (airFallback == null) {
                            return null;
                        }
                        if (frameRelStarts == null) {
                            frameRelStarts = frame.computeSectionRelStarts();
                            framePalMap = frame.resolveEffectivePaletteToRuntimeIdMap(runtimeLookup);
                        }
                        final LcaModeABlockSection modeASec =
                            frame.tryExtractModeABlockSection(frameSectionIdx, frameRelStarts, framePalMap);
                        final GoldLeafModeABlockSectionBacking modeABacking =
                            modeASec != null ? GoldLeafModeABlockSectionBacking.fromExtracted(modeASec, worldId) : null;
                        if (modeABacking != null) {
                            blocks.paper$attachColdBacking(modeABacking, airFallback);
                            attachedInChunk++;
                            section = new LevelChunkSection(blocks, biomes);
                        } else {
                            final byte[] sectionFrame = frame.sliceBlockOnlySectionFrameWithRuntimePalette(frameSectionIdx, runtimeLookup);
                            if (sectionFrame == null || sectionFrame.length == 0) {
                                return null;
                            }
                            final LcaSectionBacking lcaBacking = LcaSectionBacking.fromColdSectionFrameWithBaseAndIndex(
                                sectionFrame,
                                0,
                                id -> {
                                    final BlockState s = Block.stateById(id);
                                    return s != null && !s.isAir();
                                },
                                id -> {
                                    final BlockState s = Block.stateById(id);
                                    return s != null && s.isRandomlyTicking();
                                },
                                id -> {
                                    final BlockState s = Block.stateById(id);
                                    return s != null && s.getFluidState().isRandomlyTicking();
                                }
                            );
                            final GoldLeafBlockSectionBacking coldBacking = new GoldLeafBlockSectionBacking(lcaBacking, worldId);
                            blocks.paper$attachColdBacking(coldBacking, airFallback);
                            attachedInChunk++;
                            section = new LevelChunkSection(blocks, biomes);
                        }
                    }
                } else {
                    section = null;
                }

                final DataLayer blockLight = sectionTag.getByteArray("BlockLight").map(DataLayer::new).orElse(null);
                final DataLayer skyLight = sectionTag.getByteArray("SkyLight").map(DataLayer::new).orElse(null);
                final SerializableChunkData.SectionData sd = new SerializableChunkData.SectionData(y, section, blockLight, skyLight);
                if (sectionTag.contains(SaveUtil.BLOCKLIGHT_STATE_TAG)) {
                    ((StarlightSectionData) (Object) sd).starlight$setBlockLightState(
                        sectionTag.getIntOr(SaveUtil.BLOCKLIGHT_STATE_TAG, 0)
                    );
                }
                if (sectionTag.contains(SaveUtil.SKYLIGHT_STATE_TAG)) {
                    ((StarlightSectionData) (Object) sd).starlight$setSkyLightState(
                        sectionTag.getIntOr(SaveUtil.SKYLIGHT_STATE_TAG, 0)
                    );
                }
                sectionData.add(sd);
            }

            if (!isRetainRawChunkFrameRequired()) {
                this.residencyManager.releaseBaseFrameIfClean(worldId, chunkX, chunkZ);
            }
            this.directChunksDeserialized.incrementAndGet();
            this.coldSectionsAttached.addAndGet(attachedInChunk);
            return new SerializableChunkData(
                containerFactory,
                chunkPos,
                level.getMinSectionY(),
                lastUpdateTime,
                inhabitedTime,
                status,
                blendingData,
                belowZeroRetrogen,
                upgradeData,
                heightmaps,
                packedTicks,
                postProcessingSections,
                lightCorrect,
                sectionData,
                entities,
                blockEntities,
                structureData,
                chunkData.get("ChunkBukkitValues")
            );
        } catch (final RuntimeException ex) {
            LOGGER.warn(
                "Direct .lca cold-section deserialization failed for {} ({}, {}); falling back to standard NBT parse",
                worldId, chunkX, chunkZ, ex
            );
            return null;
        }
    }

    static boolean isDirectPayloadLightCorrect(
        final ChunkStatus status,
        final LcaDecodedChunkMeta meta,
        final @Nullable CompoundTag residualTag
    ) {
        // Starlight writes isLightOn=0 alongside starlight.light_version=10; LCA stores isLightOn=0
        // in residual NBT while bpFlags bit 3 only marks isLightOn=1. Match SerializableChunkData#parse
        // by checking tag presence in either meta.hasIsLightOn() or residualTag.
        final boolean hasIsLightOn = meta.hasIsLightOn()
            || (meta.hasResidualNbt() && residualTag != null && residualTag.get("isLightOn") != null);
        return status.isOrAfter(ChunkStatus.LIGHT)
            && hasIsLightOn
            && meta.hasStarlightVersion()
            && meta.starlightLightVersion() == SaveUtil.STARLIGHT_LIGHT_VERSION;
    }

    private @Nullable SerializableChunkData deserializeFromDirectCoWSentinel(
        final ServerLevel level,
        final PalettedContainerFactory containerFactory,
        final CompoundTag sentinel
    ) {
        final int chunkX = sentinel.getIntOr("xPos", 0);
        final int chunkZ = sentinel.getIntOr("zPos", 0);
        final String levelWorldId = level.dimension().identifier().toString();
        final String worldId = sentinel.getStringOr(DIRECT_COW_WORLD_TAG, levelWorldId);
        LcaRegionStorageProvider provider = this.residencyManager.getStorageProvider(worldId);
        if (provider == null && !worldId.equals(levelWorldId)) {
            provider = this.residencyManager.getStorageProvider(levelWorldId);
        }
        if (provider == null) {
            LOGGER.warn(
                "Direct CoW sentinel read for {} ({}, {}) could not resolve LcaRegionStorageProvider",
                worldId, chunkX, chunkZ
            );
            return null;
        }
        try {
            final LcaRegionReader.RawChunkFrameWithDirectMeta paired =
                provider.delegate().readRawChunkFrameWithDirectMeta(chunkX, chunkZ, canonicalToRuntimeStateLookup());
            if (paired != null && paired.frame() != null && paired.directMeta() != null) {
                final LcaDecodedChunkMeta meta = paired.directMeta();
                final CompoundTag residualTag;
                if (meta.hasResidualNbt()) {
                    try (DataInputStream residualIn = new DataInputStream(new ByteArrayInputStream(meta.residualNbtBytes()))) {
                        final CompoundTag parsed = FastNBTParser.nbtIoRead(residualIn);
                        residualTag = parsed != null ? parsed : new CompoundTag();
                    }
                } else {
                    residualTag = new CompoundTag();
                }
                residualTag.putInt("DataVersion", meta.dataVersion());
                residualTag.putInt("xPos", chunkX);
                residualTag.putInt("zPos", chunkZ);
                if (meta.statusName() != null && !meta.statusName().isEmpty()) {
                    residualTag.putString("Status", meta.statusName());
                }
                final LcaRegionStorageProvider.DirectLcaLoadPayload payload =
                    new LcaRegionStorageProvider.DirectLcaLoadPayload(
                        paired.frame(), meta, provider.delegate(), chunkX, chunkZ
                    );
                final SerializableChunkData directResult = deserializeFromDirectPayload(
                    level, containerFactory, residualTag, payload
                );
                if (directResult != null) {
                    return directResult;
                }
                return fallbackFullNbtDeserialize(level, containerFactory, payload);
            }
            final CompoundTag fullTag = provider.read(chunkX, chunkZ);
            if (fullTag == null) {
                return null;
            }
            return SerializableChunkData.parse(level, containerFactory, fullTag);
        } catch (final IOException ex) {
            LOGGER.warn(
                "Direct CoW sentinel read failed for {} ({}, {})",
                worldId, chunkX, chunkZ, ex
            );
            return null;
        }
    }

    private @Nullable SerializableChunkData deserializeFromDirectPayload(
        final ServerLevel level,
        final PalettedContainerFactory containerFactory,
        final CompoundTag residualTag,
        final LcaRegionStorageProvider.DirectLcaLoadPayload payload
    ) {
        final RawChunkFrameSnapshot frame = payload.frame();
        final LcaDecodedChunkMeta meta = payload.directMeta();
        final int[] dictToRuntime = frame.dictIdToRuntimeId();
        if (dictToRuntime == null || dictToRuntime.length == 0) {
            return null;
        }
        for (final int runtimeId : dictToRuntime) {
            if (runtimeId < 0) {
                return null;
            }
        }
        final int chunkX = payload.chunkX();
        final int chunkZ = payload.chunkZ();
        final String worldId = level.dimension().identifier().toString();
        try {
            final ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
            final long lastUpdateTime = meta.lastUpdateTick();
            final long inhabitedTime = meta.inhabitedTimeTicks();
            final String statusName = meta.statusName();
            final ChunkStatus status = "minecraft:full".equals(statusName)
                ? ChunkStatus.FULL
                : (statusName != null && !statusName.isEmpty() ? ChunkStatus.byName(statusName) : ChunkStatus.EMPTY);
            final boolean hasResidual = meta.hasResidualNbt();
            final UpgradeData upgradeData = hasResidual
                ? residualTag.getCompound("UpgradeData").map(tag -> new UpgradeData(tag, level)).orElse(UpgradeData.EMPTY)
                : UpgradeData.EMPTY;
            final boolean lightCorrect = isDirectPayloadLightCorrect(status, meta, residualTag);
            final BlendingData.Packed blendingData = hasResidual && residualTag.contains("blending_data")
                ? residualTag.read("blending_data", BlendingData.Packed.CODEC).orElse(null)
                : null;
            final BelowZeroRetrogen belowZeroRetrogen = hasResidual && residualTag.contains("below_zero_retrogen")
                ? residualTag.read("below_zero_retrogen", BelowZeroRetrogen.CODEC).orElse(null)
                : null;

            final Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
            final int[] hmCodes = meta.heightmapCodes();
            final String[] customHmNames = meta.customHeightmapNames();
            final long[][] hmLongs = meta.heightmapLongs();
            for (int i = 0; i < hmCodes.length; i++) {
                final int code = hmCodes[i];
                if (code >= 0 && code < STANDARD_LCA_HEIGHTMAP_TYPES.length) {
                    heightmaps.put(STANDARD_LCA_HEIGHTMAP_TYPES[code], hmLongs[i]);
                } else if (customHmNames[i] != null) {
                    for (final Heightmap.Types type : Heightmap.Types.values()) {
                        if (type.getSerializationKey().equals(customHmNames[i])) {
                            heightmaps.put(type, hmLongs[i]);
                            break;
                        }
                    }
                }
            }
            if (heightmaps.isEmpty() && hasResidual && residualTag.contains("Heightmaps")) {
                final CompoundTag residualHms = residualTag.getCompoundOrEmpty("Heightmaps");
                for (final Heightmap.Types type : Heightmap.Types.values()) {
                    residualHms.getLongArray(type.getSerializationKey()).ifPresent(arr -> heightmaps.put(type, arr));
                }
            }

            final List<SavedTick<Block>> blockTicks = hasResidual && residualTag.contains("block_ticks")
                ? SavedTick.filterTickListForChunk(residualTag.read("block_ticks", blockTicksCodec()).orElse(List.of()), chunkPos)
                : List.of();
            final List<SavedTick<Fluid>> fluidTicks = hasResidual && residualTag.contains("fluid_ticks")
                ? SavedTick.filterTickListForChunk(residualTag.read("fluid_ticks", fluidTicksCodec()).orElse(List.of()), chunkPos)
                : List.of();
            final ChunkAccess.PackedTicks packedTicks = new ChunkAccess.PackedTicks(blockTicks, fluidTicks);

            final ShortList[] postProcessingSections;
            if (hasResidual && residualTag.contains("PostProcessing")) {
                final ListTag postProcessTags = residualTag.getListOrEmpty("PostProcessing");
                postProcessingSections = new ShortList[postProcessTags.size()];
                for (int sectionIndex = 0; sectionIndex < postProcessTags.size(); sectionIndex++) {
                    final ListTag offsetsTag = postProcessTags.getList(sectionIndex).orElse(null);
                    if (offsetsTag != null && !offsetsTag.isEmpty()) {
                        final ShortList packedOffsets = new ShortArrayList(offsetsTag.size());
                        for (int i = 0; i < offsetsTag.size(); i++) {
                            packedOffsets.add(offsetsTag.getShortOr(i, (short) 0));
                        }
                        postProcessingSections[sectionIndex] = packedOffsets;
                    }
                }
            } else if ((meta.bpFlags() & 32) != 0) {
                postProcessingSections = new ShortList[meta.postProcessingCount()];
            } else {
                postProcessingSections = new ShortList[0];
            }

            final List<CompoundTag> entities = hasResidual && residualTag.contains("entities")
                ? residualTag.getList("entities").stream().flatMap(ListTag::compoundStream).toList()
                : List.of();
            final List<CompoundTag> blockEntities = hasResidual && residualTag.contains("block_entities")
                ? residualTag.getList("block_entities").stream().flatMap(ListTag::compoundStream).toList()
                : List.of();
            final CompoundTag structureData = hasResidual && residualTag.contains("structures")
                ? residualTag.getCompoundOrEmpty("structures")
                : new CompoundTag();

            final Registry<Biome> biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
            final net.minecraft.core.Holder<Biome> defaultBiome = containerFactory.defaultBiome();
            final List<String> biomeKeys = meta.biomeKeys();
            final LcaDecodedChunkMeta.DecodedSectionMeta[] decodedSections = meta.sections();
            final List<SerializableChunkData.SectionData> sectionData = new ArrayList<>(decodedSections.length);
            final Function<String, Integer> runtimeLookup = canonicalToRuntimeStateLookup();
            final BlockState airFallback = Block.stateById(0);
            if (airFallback == null) {
                return null;
            }
            final int minSecY = level.getMinSectionY();
            final int maxSecY = level.getMaxSectionY();
            long attachedInChunk = 0L;
            int[] frameRelStarts = null;
            int[] framePalMap = null;

            for (final LcaDecodedChunkMeta.DecodedSectionMeta dsec : decodedSections) {
                final int y = dsec.sectionY();
                final LevelChunkSection section;
                if (y >= minSecY && y <= maxSecY) {
                    final int frameSectionIdx = y - frame.minSectionY();
                    if (frameSectionIdx < 0 || frameSectionIdx >= frame.sectionCount()) {
                        return null;
                    }
                    final PalettedContainer<net.minecraft.core.Holder<Biome>> biomes = buildDirectBiomeContainer(
                        containerFactory, biomeRegistry, biomeKeys, dsec.biomeGlobalDictIds(), dsec.biomePackedLongs(), defaultBiome
                    );
                    final PalettedContainer<BlockState> blocks = containerFactory.createForBlockStates(level, chunkPos, y);
                    final int entry16 = frame.sectionDirectoryEntry16(frameSectionIdx);
                    if (entry16 == 0 || (entry16 & 0x8000) != 0) {
                        final int uniformId = frame.resolveInlineUniformRuntimeStateId(frameSectionIdx, runtimeLookup);
                        if (uniformId < 0) {
                            return null;
                        }
                        final BlockState uniformState = Block.stateById(uniformId);
                        if (uniformState == null) {
                            return null;
                        }
                        blocks.paper$installUniform(uniformState);
                        section = new LevelChunkSection(blocks, biomes);
                    } else {
                        if (frameRelStarts == null) {
                            frameRelStarts = frame.computeSectionRelStarts();
                            framePalMap = frame.resolveEffectivePaletteToRuntimeIdMap(runtimeLookup);
                        }
                        final LcaModeABlockSection modeASec =
                            frame.tryExtractModeABlockSection(frameSectionIdx, frameRelStarts, framePalMap);
                        final GoldLeafModeABlockSectionBacking modeABacking =
                            modeASec != null ? GoldLeafModeABlockSectionBacking.fromExtracted(modeASec, worldId) : null;
                        if (modeABacking != null) {
                            // Unified Disk = Memory standard LCA Mode A zero-unpack path:
                            // zero VarInt palette rewrite, zero int[4096] unpack, zero CompactBaseIndex.
                            blocks.paper$attachColdBacking(modeABacking, airFallback);
                            attachedInChunk++;
                            section = new LevelChunkSection(blocks, biomes);
                        } else {
                            final byte[] sectionFrame = frame.sliceBlockOnlySectionFrameWithRuntimePalette(frameSectionIdx, runtimeLookup);
                            if (sectionFrame == null || sectionFrame.length == 0) {
                                return null;
                            }
                            final LcaSectionBacking lcaBacking = LcaSectionBacking.fromColdSectionFrameWithBaseAndIndex(
                                sectionFrame,
                                0,
                                id -> {
                                    final BlockState s = Block.stateById(id);
                                    return s != null && !s.isAir();
                                },
                                id -> {
                                    final BlockState s = Block.stateById(id);
                                    return s != null && s.isRandomlyTicking();
                                },
                                id -> {
                                    final BlockState s = Block.stateById(id);
                                    return s != null && s.getFluidState().isRandomlyTicking();
                                }
                            );
                            final GoldLeafBlockSectionBacking coldBacking = new GoldLeafBlockSectionBacking(lcaBacking, worldId);
                            blocks.paper$attachColdBacking(coldBacking, airFallback);
                            attachedInChunk++;
                            section = new LevelChunkSection(blocks, biomes);
                        }
                    }
                } else {
                    section = null;
                }

                final DataLayer blockLight = decodeDirectDataLayer(dsec.blockLightKind(), dsec.blockLightBytes());
                final DataLayer skyLight = decodeDirectDataLayer(dsec.skyLightKind(), dsec.skyLightBytes());
                final SerializableChunkData.SectionData sd = new SerializableChunkData.SectionData(y, section, blockLight, skyLight);
                if (dsec.blockLightStarlightState() >= 0) {
                    ((StarlightSectionData) (Object) sd).starlight$setBlockLightState(dsec.blockLightStarlightState());
                }
                if (dsec.skyLightStarlightState() >= 0) {
                    ((StarlightSectionData) (Object) sd).starlight$setSkyLightState(dsec.skyLightStarlightState());
                }
                sectionData.add(sd);
            }

            this.directChunksDeserialized.incrementAndGet();
            this.coldSectionsAttached.addAndGet(attachedInChunk);
            return new SerializableChunkData(
                containerFactory,
                chunkPos,
                minSecY,
                lastUpdateTime,
                inhabitedTime,
                status,
                blendingData,
                belowZeroRetrogen,
                upgradeData,
                heightmaps,
                packedTicks,
                postProcessingSections,
                lightCorrect,
                sectionData,
                entities,
                blockEntities,
                structureData,
                hasResidual ? residualTag.get("ChunkBukkitValues") : null
            );
        } catch (final RuntimeException ex) {
            LOGGER.warn(
                "Zero-NBT direct .lca deserialization failed for {} ({}, {}); falling back to full NBT read",
                worldId, chunkX, chunkZ, ex
            );
            return null;
        }
    }

    private SerializableChunkData fallbackFullNbtDeserialize(
        final ServerLevel level,
        final PalettedContainerFactory containerFactory,
        final LcaRegionStorageProvider.DirectLcaLoadPayload payload
    ) {
        if (payload.storage() == null) {
            throw new IllegalStateException(
                "Direct .lca load aborted with null storage fallback for (" + payload.chunkX() + ", " + payload.chunkZ() + ")"
            );
        }
        try (DataInputStream in = payload.storage().getChunkDataInputStream(payload.chunkX(), payload.chunkZ())) {
            if (in == null) {
                throw new IllegalStateException(
                    "Full NBT fallback stream missing for (" + payload.chunkX() + ", " + payload.chunkZ() + ")"
                );
            }
            final CompoundTag fullTag = FastNBTParser.nbtIoRead(in);
            if (fullTag == null) {
                throw new IllegalStateException(
                    "Full NBT fallback decoded null tag for (" + payload.chunkX() + ", " + payload.chunkZ() + ")"
                );
            }
            final SerializableChunkData parsed = SerializableChunkData.parse(level, containerFactory, fullTag);
            if (parsed == null) {
                throw new IllegalStateException(
                    "Full NBT fallback returned null SerializableChunkData for (" + payload.chunkX() + ", " + payload.chunkZ() + ")"
                );
            }
            return parsed;
        } catch (final Exception ex) {
            LOGGER.warn(
                "Full NBT fallback failed for ({}, {}) after direct .lca load abort",
                payload.chunkX(), payload.chunkZ(), ex
            );
            throw new IllegalStateException(
                "Full NBT fallback failed for (" + payload.chunkX() + ", " + payload.chunkZ() + ")", ex
            );
        }
    }

    private static @Nullable DataLayer decodeDirectDataLayer(final byte kind, final byte @Nullable [] bytes) {
        if (kind == LcaDecodedChunkMeta.LIGHT_FULL_15) {
            return SWMRNibbleArray.createSharedFullDataLayer();
        }
        if (kind == LcaDecodedChunkMeta.LIGHT_ARRAY) {
            if (bytes == null || bytes.length != 2048) {
                throw new IllegalArgumentException("Invalid direct .lca light array length: " + (bytes == null ? -1 : bytes.length));
            }
            return new DataLayer(bytes);
        }
        return null;
    }

    static PalettedContainer<net.minecraft.core.Holder<Biome>> buildDirectBiomeContainer(
        final PalettedContainerFactory containerFactory,
        final Registry<Biome> biomeRegistry,
        final List<String> biomeKeys,
        final int @Nullable [] biomeGlobalDictIds,
        final long @Nullable [] biomePackedLongs,
        final net.minecraft.core.Holder<Biome> defaultBiome
    ) {
        final PalettedContainer<net.minecraft.core.Holder<Biome>> container = containerFactory.createForBiomes();
        if (biomeGlobalDictIds == null || biomeGlobalDictIds.length == 0) {
            return container;
        }
        if (biomeGlobalDictIds.length == 1 || biomePackedLongs == null) {
            final net.minecraft.core.Holder<Biome> single = resolveBiomeHolder(biomeRegistry, biomeKeys, biomeGlobalDictIds[0], defaultBiome);
            container.paper$installUniform(single);
            return container;
        }
        final List<net.minecraft.core.Holder<Biome>> palette = new ArrayList<>(biomeGlobalDictIds.length);
        for (final int dictId : biomeGlobalDictIds) {
            palette.add(resolveBiomeHolder(biomeRegistry, biomeKeys, dictId, defaultBiome));
        }
        container.paper$installPackedBiomes(palette, biomePackedLongs);
        return container;
    }

    private static net.minecraft.core.Holder<Biome> resolveBiomeHolder(
        final Registry<Biome> biomeRegistry,
        final List<String> biomeKeys,
        final int globalBiomeId,
        final net.minecraft.core.Holder<Biome> defaultBiome
    ) {
        if (globalBiomeId < 0 || globalBiomeId >= biomeKeys.size()) {
            return defaultBiome;
        }
        final String key = biomeKeys.get(globalBiomeId);
        if (key == null || key.isEmpty()) {
            return defaultBiome;
        }
        BiomeHolderCache cache = biomeHolderCache;
        if (cache == null || cache.registryRef.get() != biomeRegistry) {
            cache = new BiomeHolderCache(biomeRegistry);
            biomeHolderCache = cache;
        }
        final net.minecraft.core.Holder<Biome> cached = cache.map.get(key);
        if (cached != null) {
            return cached;
        }
        final Identifier id = Identifier.tryParse(key);
        if (id == null) {
            return defaultBiome;
        }
        final Optional<net.minecraft.core.Holder.Reference<Biome>> found = biomeRegistry.get(ResourceKey.create(Registries.BIOME, id));
        final net.minecraft.core.Holder<Biome> resolved = found.isPresent() ? found.get() : defaultBiome;
        cache.map.putIfAbsent(key, resolved);
        return resolved;
    }

    /**
     * Returns a lookup function mapping canonical {@code anvil-api} block-state keys to live
     * {@link Block#getId(BlockState)} runtime IDs (or {@code -1} if unknown or registry unbooted).
     */
    public static Function<String, Integer> canonicalToRuntimeStateLookup() {
        return LcaDirectSectionSerializer::lookupRuntimeStateId;
    }

    public static int lookupRuntimeStateId(final String canonicalState) {
        if (canonicalState == null || canonicalState.isEmpty()) {
            return -1;
        }
        final Integer cached = LAZY_CANONICAL_TO_RUNTIME_CACHE.get(canonicalState);
        if (cached != null) {
            return cached;
        }
        if (Block.BLOCK_STATE_REGISTRY.size() <= 0) {
            return -1;
        }
        final int resolved = resolveBlockStateKeyOnDemand(canonicalState);
        if (resolved >= 0) {
            LAZY_CANONICAL_TO_RUNTIME_CACHE.putIfAbsent(canonicalState, resolved);
        }
        return resolved;
    }

    /**
     * Resolves a canonical, unsorted-property, partial-property, or bare block-state key on demand
     * against {@link BuiltInRegistries#BLOCK} without materializing a static 36,000-entry string map.
     */
    private static int resolveBlockStateKeyOnDemand(final String stateKey) {
        final int open = stateKey.indexOf('[');
        final String blockName;
        if (open < 0) {
            blockName = stateKey;
        } else {
            if (open == 0 || stateKey.charAt(stateKey.length() - 1) != ']') {
                return -1;
            }
            blockName = stateKey.substring(0, open);
        }
        final net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(blockName);
        if (id == null) {
            return -1;
        }
        final Optional<Block> maybeBlock = BuiltInRegistries.BLOCK.getOptional(id);
        if (maybeBlock.isEmpty()) {
            return -1;
        }
        final Block block = maybeBlock.get();
        BlockState state = block.defaultBlockState();
        if (open > 0) {
            final String propsStr = stateKey.substring(open + 1, stateKey.length() - 1);
            if (!propsStr.isEmpty()) {
                final var definition = block.getStateDefinition();
                for (final String part : propsStr.split(",")) {
                    final int eq = part.indexOf('=');
                    if (eq <= 0 || eq >= part.length() - 1) {
                        return -1;
                    }
                    final Property<?> prop = definition.getProperty(part.substring(0, eq));
                    if (prop == null) {
                        return -1;
                    }
                    final BlockState updated = applyPropertyValue(state, prop, part.substring(eq + 1));
                    if (updated == null) {
                        return -1;
                    }
                    state = updated;
                }
            }
        }
        return Block.getId(state);
    }

    private static <T extends Comparable<T>> @Nullable BlockState applyPropertyValue(
        final BlockState state,
        final Property<T> prop,
        final String rawValue
    ) {
        final Optional<T> parsed = prop.getValue(rawValue);
        return parsed.map(val -> state.setValue(prop, val)).orElse(null);
    }

    /**
     * Normalizes a block-state key whose NBT {@code Properties} may appear in unsorted order
     * (e.g., from {@code LcaStreamingNbtEncoder}) into sorted canonical property order matching
     * {@link #canonicalBlockStateString(BlockState)}.
     */
    static String normalizeCanonicalBlockStateKey(final String stateKey) {
        final int open = stateKey.indexOf('[');
        if (open < 0 || stateKey.charAt(stateKey.length() - 1) != ']') {
            return stateKey;
        }
        final int comma = stateKey.indexOf(',', open + 1);
        if (comma < 0) {
            return stateKey;
        }
        final String propsStr = stateKey.substring(open + 1, stateKey.length() - 1);
        final String[] parts = propsStr.split(",");
        boolean alreadySorted = true;
        for (int i = 1; i < parts.length; i++) {
            if (parts[i - 1].compareTo(parts[i]) > 0) {
                alreadySorted = false;
                break;
            }
        }
        if (alreadySorted) {
            return stateKey;
        }
        java.util.Arrays.sort(parts);
        final StringBuilder sb = new StringBuilder(stateKey.length());
        sb.append(stateKey, 0, open + 1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parts[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static Codec<List<SavedTick<Block>>> blockTicksCodec() {
        Codec<List<SavedTick<Block>>> codec = blockTicksCodec;
        if (codec == null) {
            codec = SavedTick.codec(BuiltInRegistries.BLOCK.byNameCodec()).listOf();
            blockTicksCodec = codec;
        }
        return codec;
    }

    private static Codec<List<SavedTick<Fluid>>> fluidTicksCodec() {
        Codec<List<SavedTick<Fluid>>> codec = fluidTicksCodec;
        if (codec == null) {
            codec = SavedTick.codec(BuiltInRegistries.FLUID.byNameCodec()).listOf();
            fluidTicksCodec = codec;
        }
        return codec;
    }

    public LcaResidencyManager residencyManager() {
        return this.residencyManager;
    }

    public long directChunksSerialized() {
        return this.directChunksSerialized.get();
    }

    public long directChunksDeserialized() {
        return this.directChunksDeserialized.get();
    }

    public long coldSectionsAttached() {
        return this.coldSectionsAttached.get();
    }

    public long uniformAirSectionsSkipped() {
        return this.uniformAirSectionsSkipped.get();
    }

    public long zeroNbtCoWSplices() {
        return this.zeroNbtCoWSplices.get();
    }
}
