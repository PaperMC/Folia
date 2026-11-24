package dev.leafcraft.goldleaf.network;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1 off-tick chunk packet &amp; Anti-Xray controller SPI hooked into
 * {@code PlayerChunkSender#sendChunk} ({@code goldleaf-ADR-009} §1.3).
 *
 * <p>Captures immutable section metadata + {@code LevelChunkSection#paper$getSectionEpoch()}
 * snapshots on the owning region thread, offloads heavy packet assembly / Anti-Xray obfuscation
 * onto a dedicated non-tick daemon worker pool ({@code S-005}), and invalidates stale plans
 * immediately if any section epoch advanced before transmission ({@code S-004}).</p>
 */
public interface OffTickChunkPacketController {

    /**
     * Immutable per-section descriptor captured on the owning region thread.
     */
    record SectionSnapshot(
        int sectionIndex,
        int capturedSectionEpoch,
        boolean hasOnlyAir,
        int paletteSize,
        long lcaOctreeOccupancyMask64
    ) {
        public boolean isUniformOrEmpty() {
            return this.hasOnlyAir || this.paletteSize <= 1;
        }
    }

    /**
     * Immutable chunk-level snapshot plan guarded by per-section mutation epochs.
     */
    final class ChunkPacketPlan {

        private final int chunkX;
        private final int chunkZ;
        private final boolean shouldModifyAntiXray;
        private final SectionSnapshot[] sections;

        public ChunkPacketPlan(
            final int chunkX,
            final int chunkZ,
            final boolean shouldModifyAntiXray,
            final SectionSnapshot[] sections
        ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.shouldModifyAntiXray = shouldModifyAntiXray;
            this.sections = Arrays.copyOf(sections, sections.length);
        }

        public int chunkX() {
            return this.chunkX;
        }

        public int chunkZ() {
            return this.chunkZ;
        }

        public boolean shouldModifyAntiXray() {
            return this.shouldModifyAntiXray;
        }

        public int sectionCount() {
            return this.sections.length;
        }

        public SectionSnapshot section(final int index) {
            return this.sections[index];
        }

        /**
         * Returns {@code true} if any section in {@code currentEpochs} no longer matches the
         * snapshot's captured {@code sectionEpoch}, indicating a concurrent block mutation.
         */
        public boolean isStale(final int[] currentEpochs) {
            Objects.requireNonNull(currentEpochs, "currentEpochs");
            if (currentEpochs.length != this.sections.length) {
                return true;
            }
            for (int i = 0; i < this.sections.length; i++) {
                if (this.sections[i].capturedSectionEpoch() != currentEpochs[i]) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns {@code true} if any {@link LevelChunkSection} in {@code chunk} has mutated since
         * this plan was captured.
         */
        public boolean isStale(final LevelChunk chunk) {
            Objects.requireNonNull(chunk, "chunk");
            final LevelChunkSection[] live = chunk.getSections();
            if (live.length != this.sections.length) {
                return true;
            }
            for (int i = 0; i < live.length; i++) {
                if (live[i].paper$getSectionEpoch() != this.sections[i].capturedSectionEpoch()) {
                    return true;
                }
            }
            return false;
        }

        public static ChunkPacketPlan capture(final LevelChunk chunk, final boolean shouldModifyAntiXray) {
            Objects.requireNonNull(chunk, "chunk");
            final LevelChunkSection[] live = chunk.getSections();
            final SectionSnapshot[] snaps = new SectionSnapshot[live.length];
            for (int i = 0; i < live.length; i++) {
                final LevelChunkSection sec = live[i];
                final boolean onlyAir = sec.hasOnlyAir();
                snaps[i] = new SectionSnapshot(
                    i,
                    sec.paper$getSectionEpoch(),
                    onlyAir,
                    sec.getStates().data.palette().getSize(),
                    onlyAir ? 0L : ~0L
                );
            }
            return new ChunkPacketPlan(chunk.getPos().x(), chunk.getPos().z(), shouldModifyAntiXray, snaps);
        }
    }

    /**
     * Attempts to handle chunk packet assembly / Anti-Xray off-tick for {@code connection}.
     *
     * @return {@code true} if the controller accepted delivery, or {@code false} to fall back
     *         synchronously to vanilla {@code ClientboundLevelChunkWithLightPacket}
     */
    boolean trySendOffTick(
        ServerGamePacketListenerImpl connection,
        ServerLevel level,
        LevelChunk chunk,
        boolean shouldModifyAntiXray
    );

    /**
     * Submits pure off-tick encoding work on GoldLeaf's bounded daemon worker pool ({@code S-005})
     * with fail-closed exception logging ({@code S-004}).
     */
    static <T> CompletableFuture<T> submitOffTick(
        final ChunkPacketPlan plan,
        final Function<ChunkPacketPlan, T> encoder
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(encoder, "encoder");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return encoder.apply(plan);
            } catch (final Throwable t) {
                Holder.LOGGER.warn(
                    "OffTickChunkPacketController worker failed for chunk ({}, {})",
                    plan.chunkX(),
                    plan.chunkZ(),
                    t
                );
                throw t;
            }
        }, Holder.WORKER_POOL);
    }

    static boolean isEnabled() {
        return GoldLeafConfig.get().network.antiXrayOffTickEncoding && Holder.current != null;
    }

    static @Nullable OffTickChunkPacketController get() {
        return Holder.current;
    }

    static void register(final @Nullable OffTickChunkPacketController controller) {
        Holder.current = controller;
    }

    static void resetDefault() {
        Holder.current = null;
    }

    final class Holder {
        private static final Logger LOGGER = LoggerFactory.getLogger(OffTickChunkPacketController.class);
        private static final AtomicInteger THREAD_ID = new AtomicInteger(1);
        private static final ExecutorService WORKER_POOL = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
            runnable -> {
                final Thread t = new Thread(runnable, "GoldLeaf-ChunkEncode-" + THREAD_ID.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        );
        private static volatile @Nullable OffTickChunkPacketController current;

        private Holder() {
        }
    }
}
