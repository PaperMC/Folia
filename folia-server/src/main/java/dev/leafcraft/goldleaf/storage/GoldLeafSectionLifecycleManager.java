package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.spi.ColdSectionBackingSpi;
import ca.spottedleaf.moonrise.spi.SectionLifecycleSpi;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import io.github.dailystruggle.anvil.LcaSectionBacking;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 2 {@link SectionLifecycleSpi} implementation managing the 4-state per-section lifecycle
 * ({@code UNIFORM / COLD_LCA / WARM_SPARSE / HOT_MUTABLE}) with time + heap-pressure hysteresis
 * and off-tick compaction ({@code goldleaf-ADR-005} Stage 4, {@code leafanvil-ADR-002},
 * {@code leafanvil-ADR-006}).
 *
 * <p>All region-thread hooks are O(1) or non-blocking handoffs ({@code S-005}), all compression
 * runs on a single low-priority bounded daemon worker so compaction never starves region ticks
 * or worldgen workers, and every {@code HOT_MUTABLE -> COLD_LCA} swap is fenced on both
 * {@code chunk.loaded} and {@code section.paper$getSectionEpoch()} on the owning region thread
 * ({@code S-002}, {@code F-001..F-003}).</p>
 */
public final class GoldLeafSectionLifecycleManager implements SectionLifecycleSpi {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoldLeafSectionLifecycleManager.class);
    private static final int MAX_PENDING_TASKS = 4096;
    private static final int SECTION_VOXELS = LcaSectionBacking.BULK_4096_CAPACITY;

    private static final GoldLeafSectionLifecycleManager GLOBAL = new GoldLeafSectionLifecycleManager();

    private final ScheduledThreadPoolExecutor worker;
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AtomicLong cleanDemotions = new AtomicLong();
    private final AtomicLong dirtyCompactions = new AtomicLong();
    private final AtomicLong hotMutableCompactions = new AtomicLong();
    private final AtomicLong generatedSectionsCompacted = new AtomicLong();
    private final AtomicLong epochFenceAborts = new AtomicLong();
    private final AtomicLong entityChunksRefrozen = new AtomicLong();

    private GoldLeafSectionLifecycleManager() {
        this.worker = new ScheduledThreadPoolExecutor(1, runnable -> {
            final Thread t = new Thread(runnable, "GoldLeaf-Section-Compactor");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
        this.worker.setRemoveOnCancelPolicy(true);
    }

    public static GoldLeafSectionLifecycleManager global() {
        return GLOBAL;
    }

    @Override
    public void onChunkDowngraded(final ServerLevel world, final LevelChunk chunk) {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        if (cfg == null || cfg.storage == null) {
            return;
        }
        final long quietDelayMs = effectiveQuietWindowMillis(cfg.storage);
        final int chunkX = chunk.locX;
        final int chunkZ = chunk.locZ;

        if (cfg.storage.lazyEntityHydration && cfg.storage.lazyEntityRefreeze) {
            scheduleEntityRefreeze(world, chunk, chunkX, chunkZ, quietDelayMs);
        }

        if (!cfg.storage.lcaNativeSectionBacking) {
            return;
        }
        final LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return;
        }
        final String worldId = world.dimension().identifier().toString();

        for (final LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            final PalettedContainer<BlockState> states = section.getStates();
            final ColdSectionBackingSpi<BlockState> cold = states.paper$getColdBacking();
            if (cold != null) {
                if (cold.dirtyCount() == 0) {
                    if (cold.tryDemoteCleanToCold()) {
                        this.cleanDemotions.incrementAndGet();
                    }
                } else if (cold instanceof GoldLeafBlockSectionBacking gl) {
                    scheduleDirtyWarmCompaction(world, chunk, section, gl, quietDelayMs);
                }
            } else if (states.data.storage() instanceof SimpleBitStorage) {
                scheduleHotMutableCompaction(world, worldId, chunk, chunkX, chunkZ, section, quietDelayMs);
            }
        }
    }

    @Override
    public void onGeneratedChunkFull(final ServerLevel world, final LevelChunk chunk) {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        if (cfg == null || cfg.storage == null
            || !cfg.storage.lcaNativeSectionBacking
            || !cfg.storage.lcaCompactGeneratedChunks) {
            return;
        }
        final LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return;
        }
        if (this.pendingTasks.get() >= MAX_PENDING_TASKS) {
            return;
        }
        final List<SectionSnapshotTask> snapshots = new ArrayList<>(8);
        for (final LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            final PalettedContainer<BlockState> states = section.getStates();
            if (states.paper$getColdBacking() != null) {
                continue;
            }
            if (!(states.data.storage() instanceof SimpleBitStorage)) {
                continue;
            }
            final int epoch = section.paper$getSectionEpoch();
            final int[] voxels = snapshotSectionVoxels(section);
            snapshots.add(new SectionSnapshotTask(section, epoch, voxels));
        }
        if (snapshots.isEmpty()) {
            return;
        }
        final String worldId = world.dimension().identifier().toString();
        final int chunkX = chunk.locX;
        final int chunkZ = chunk.locZ;
        if (!tryAcquireTaskSlot()) {
            return;
        }
        this.worker.execute(() -> {
            try {
                if (!isChunkStillLoaded(world, chunk)) {
                    return;
                }
                final List<EncodedSectionAttachment> encoded = new ArrayList<>(snapshots.size());
                for (final SectionSnapshotTask task : snapshots) {
                    if (!isChunkStillLoaded(world, chunk) || task.section.paper$getSectionEpoch() != task.epoch) {
                        this.epochFenceAborts.incrementAndGet();
                        continue;
                    }
                    final GoldLeafBlockSectionBacking coldBacking = encodeVoxelsToColdBacking(task.voxels, worldId);
                    final BlockState uniformState = coldBacking.uniformFallback();
                    encoded.add(new EncodedSectionAttachment(task.section, task.epoch, coldBacking, uniformState));
                }
                if (encoded.isEmpty() || !isChunkStillLoaded(world, chunk)) {
                    return;
                }
                ((ChunkSystemServerLevel) world).moonrise$getChunkTaskScheduler().scheduleChunkTask(
                    chunkX,
                    chunkZ,
                    () -> {
                        if (!isChunkStillLoaded(world, chunk)) {
                            return;
                        }
                        for (final EncodedSectionAttachment att : encoded) {
                            if (tryAttachEncodedSection(world, chunk, att.section, att.epoch, att.backing, att.uniformState)) {
                                this.generatedSectionsCompacted.incrementAndGet();
                            } else {
                                this.epochFenceAborts.incrementAndGet();
                            }
                        }
                    },
                    Priority.LOW
                );
            } catch (final Throwable t) {
                LOGGER.warn("Off-tick generated-chunk section compaction failed for {} ({}, {})", worldId, chunkX, chunkZ, t);
            } finally {
                this.pendingTasks.decrementAndGet();
            }
        });
    }

    private void scheduleEntityRefreeze(
        final ServerLevel world,
        final LevelChunk chunk,
        final int chunkX,
        final int chunkZ,
        final long delayMs
    ) {
        if (!tryAcquireTaskSlot()) {
            return;
        }
        this.worker.schedule(() -> {
            try {
                if (!isChunkStillLoaded(world, chunk)) {
                    return;
                }
                ((ChunkSystemServerLevel) world).moonrise$getChunkTaskScheduler().scheduleChunkTask(
                    chunkX,
                    chunkZ,
                    () -> {
                        if (!isChunkStillLoaded(world, chunk)) {
                            return;
                        }
                        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder holder =
                            ((ChunkSystemServerLevel) world).moonrise$getChunkTaskScheduler()
                                .chunkHolderManager.getChunkHolder(chunkX, chunkZ);
                        if (holder != null && holder.paper$tryRefreezeEntityChunk()) {
                            this.entityChunksRefrozen.incrementAndGet();
                        }
                    },
                    Priority.LOW
                );
            } catch (final Throwable t) {
                LOGGER.warn("Off-tick entity refreeze scheduling failed for ({}, {})", chunkX, chunkZ, t);
            } finally {
                this.pendingTasks.decrementAndGet();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public long entityChunksRefrozen() {
        return this.entityChunksRefrozen.get();
    }

    private void scheduleDirtyWarmCompaction(
        final ServerLevel world,
        final LevelChunk chunk,
        final LevelChunkSection section,
        final GoldLeafBlockSectionBacking gl,
        final long delayMs
    ) {
        if (!tryAcquireTaskSlot()) {
            return;
        }
        final int observedEpoch = section.paper$getSectionEpoch();
        this.worker.schedule(() -> {
            try {
                if (!isChunkStillLoaded(world, chunk) || section.paper$getSectionEpoch() != observedEpoch) {
                    this.epochFenceAborts.incrementAndGet();
                    return;
                }
                if (gl.dirtyCount() <= 0) {
                    if (gl.tryDemoteCleanToCold()) {
                        this.cleanDemotions.incrementAndGet();
                    }
                    return;
                }
                final int regSize = Math.max(2, Block.BLOCK_STATE_REGISTRY.size());
                final int globalBits = 32 - Integer.numberOfLeadingZeros(regSize - 1);
                if (gl.backing().compactFrozenOverlayOffThread(globalBits)) {
                    gl.tryDemoteCleanToCold();
                    this.dirtyCompactions.incrementAndGet();
                }
            } catch (final Throwable t) {
                LOGGER.warn("Off-tick WARM_SPARSE section compaction failed", t);
            } finally {
                this.pendingTasks.decrementAndGet();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleHotMutableCompaction(
        final ServerLevel world,
        final String worldId,
        final LevelChunk chunk,
        final int chunkX,
        final int chunkZ,
        final LevelChunkSection section,
        final long delayMs
    ) {
        if (!tryAcquireTaskSlot()) {
            return;
        }
        final int observedEpoch = section.paper$getSectionEpoch();
        this.worker.schedule(() -> {
            try {
                if (!isChunkStillLoaded(world, chunk) || section.paper$getSectionEpoch() != observedEpoch) {
                    this.epochFenceAborts.incrementAndGet();
                    return;
                }
                ((ChunkSystemServerLevel) world).moonrise$getChunkTaskScheduler().scheduleChunkTask(
                    chunkX,
                    chunkZ,
                    () -> {
                        if (!isChunkStillLoaded(world, chunk)
                            || section.paper$getSectionEpoch() != observedEpoch
                            || section.getStates().paper$getColdBacking() != null) {
                            this.epochFenceAborts.incrementAndGet();
                            return;
                        }
                        if (!tryAcquireTaskSlot()) {
                            return;
                        }
                        final int[] voxels = snapshotSectionVoxels(section);
                        this.worker.execute(() -> {
                            try {
                                if (!isChunkStillLoaded(world, chunk) || section.paper$getSectionEpoch() != observedEpoch) {
                                    this.epochFenceAborts.incrementAndGet();
                                    return;
                                }
                                final GoldLeafBlockSectionBacking coldBacking = encodeVoxelsToColdBacking(voxels, worldId);
                                final BlockState uniformState = coldBacking.uniformFallback();
                                ((ChunkSystemServerLevel) world).moonrise$getChunkTaskScheduler().scheduleChunkTask(
                                    chunkX,
                                    chunkZ,
                                    () -> {
                                        if (tryAttachEncodedSection(world, chunk, section, observedEpoch, coldBacking, uniformState)) {
                                            this.hotMutableCompactions.incrementAndGet();
                                        } else {
                                            this.epochFenceAborts.incrementAndGet();
                                        }
                                    },
                                    Priority.LOW
                                );
                            } catch (final Throwable t) {
                                LOGGER.warn("Off-tick HOT_MUTABLE section compaction failed for {} ({}, {})", worldId, chunkX, chunkZ, t);
                            } finally {
                                this.pendingTasks.decrementAndGet();
                            }
                        });
                    },
                    Priority.LOW
                );
            } catch (final Throwable t) {
                LOGGER.warn("Quiet-window check failed for HOT_MUTABLE section in {} ({}, {})", worldId, chunkX, chunkZ, t);
            } finally {
                this.pendingTasks.decrementAndGet();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static boolean isChunkStillLoaded(final @Nullable ServerLevel world, final @Nullable LevelChunk chunk) {
        if (world == null || chunk == null) {
            return true;
        }
        return world.getChunkIfLoadedImmediately(chunk.locX, chunk.locZ) == chunk;
    }

    /**
     * Epoch-fenced region-thread attachment helper: installs {@code coldBacking} onto
     * {@code section} if and only if {@code chunk} is still loaded, {@code section.paper$getSectionEpoch()}
     * matches {@code expectedEpoch}, and no cold backing is already attached ({@code S-002}, {@code F-001}).
     */
    static boolean tryAttachEncodedSection(
        final @Nullable ServerLevel world,
        final @Nullable LevelChunk chunk,
        final LevelChunkSection section,
        final int expectedEpoch,
        final GoldLeafBlockSectionBacking coldBacking,
        final BlockState uniformFallback
    ) {
        if (!isChunkStillLoaded(world, chunk)) {
            return false;
        }
        if (section.paper$getSectionEpoch() != expectedEpoch) {
            return false;
        }
        final PalettedContainer<BlockState> states = section.getStates();
        if (states.paper$getColdBacking() != null) {
            return false;
        }
        states.paper$attachColdBacking(coldBacking, uniformFallback);
        return true;
    }

    /**
     * Captures a 4,096-voxel YZX runtime block-state ID snapshot from a live {@link LevelChunkSection}.
     */
    public static int[] snapshotSectionVoxels(final LevelChunkSection section) {
        final PalettedContainer<BlockState> states = section.getStates();
        final int[] voxels = new int[SECTION_VOXELS];
        for (int pos12 = 0; pos12 < SECTION_VOXELS; pos12++) {
            voxels[pos12] = Block.getId(states.get(pos12));
        }
        return voxels;
    }

    /**
     * Encodes a 4,096-voxel YZX snapshot into a {@code UNIFORM} ({@code 0 B}) or {@code COLD_LCA}
     * ({@code baseFrame}-only, {@code compactIndex == null}) {@link GoldLeafBlockSectionBacking}.
     */
    public static GoldLeafBlockSectionBacking encodeVoxelsToColdBacking(
        final int[] voxels4096,
        final @Nullable String worldId
    ) {
        final LcaSectionBacking lcaBacking = LcaSectionBacking.fromRuntimeVoxelsCold(
            voxels4096,
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
        return new GoldLeafBlockSectionBacking(lcaBacking, worldId);
    }

    /**
     * Computes the hysteresis quiet-window delay in milliseconds from config and live JVM heap occupancy.
     */
    public static long computeEffectiveQuietWindowMillis(
        final int normalQuietTicks,
        final int pressureQuietTicks,
        final double heapPressureRatio,
        final double currentHeapOccupancy
    ) {
        final double clampedThreshold = Math.max(0.10, Math.min(0.95, heapPressureRatio));
        final int ticks = currentHeapOccupancy >= clampedThreshold
            ? Math.max(0, pressureQuietTicks)
            : Math.max(0, normalQuietTicks);
        return ticks * 50L;
    }

    private static long effectiveQuietWindowMillis(final GoldLeafConfig.Storage storage) {
        final Runtime rt = Runtime.getRuntime();
        final long maxMem = Math.max(1L, rt.maxMemory());
        final long usedMem = Math.max(0L, rt.totalMemory() - rt.freeMemory());
        final double occupancy = (double) usedMem / (double) maxMem;
        return computeEffectiveQuietWindowMillis(
            storage.lcaCompactQuietWindowTicks,
            storage.lcaCompactPressureQuietWindowTicks,
            storage.lcaCompactHeapPressureRatio,
            occupancy
        );
    }

    private boolean tryAcquireTaskSlot() {
        while (true) {
            final int current = this.pendingTasks.get();
            if (current >= MAX_PENDING_TASKS) {
                return false;
            }
            if (this.pendingTasks.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public long cleanDemotions() {
        return this.cleanDemotions.get();
    }

    public long dirtyCompactions() {
        return this.dirtyCompactions.get();
    }

    public long hotMutableCompactions() {
        return this.hotMutableCompactions.get();
    }

    public long generatedSectionsCompacted() {
        return this.generatedSectionsCompacted.get();
    }

    public long epochFenceAborts() {
        return this.epochFenceAborts.get();
    }

    private record SectionSnapshotTask(LevelChunkSection section, int epoch, int[] voxels) {
    }

    private record EncodedSectionAttachment(
        LevelChunkSection section,
        int epoch,
        GoldLeafBlockSectionBacking backing,
        BlockState uniformState
    ) {
    }
}
