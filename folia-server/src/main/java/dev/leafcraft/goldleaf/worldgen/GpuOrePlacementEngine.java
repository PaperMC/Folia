package dev.leafcraft.goldleaf.worldgen;

import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeatureCountTracker;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vanilla-exact OpenCL GPU ore-vein rasterizer and zero-allocation writeback applier for
 * {@code GenerationStep.Decoration.UNDERGROUND_ORES}.
 *
 * <p>Offloads {@link OreFeature} sphere generation (exact {@code Xoroshiro128++}
 * {@code WorldgenRandom#nextDouble}), parallel {@code O(size)} sphere subsumption, and 3D ellipsoid
 * voxel rasterization to OpenCL ({@code rasterize_ore_veins_batch}), returning per-vein voxel
 * slices in exact vanilla feature-over-feature order while using compiled O(1) {@code BlockState}
 * target lookup and cached {@code 3x3} chunk sections during writeback.</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017
 * @trace S-002, S-004, S-005
 */
public final class GpuOrePlacementEngine implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GpuOrePlacementEngine.class);
    public static final String KERNEL_NAME = "rasterize_ore_veins_batch";
    private static final String BUILD_OPTS_STRICT = "-cl-std=CL1.2";
    private static final String CACHE_KEY_PREFIX = "goldleaf-ore-vein-raster-v2";
    private static final int MIN_VEINS_FOR_GPU = 8;
    private static final int MIN_SPHERES_FOR_GPU = 1200;
    private static final int MAX_VEINS_PER_BATCH = 512;
    private static final int MAX_VEINS_PER_WAVE = 2048;
    private static final int MAX_VOXELS_PER_WAVE = 524288;
    private static final int ORE_WAVE_MAX_CHUNKS = 8;
    private static final long ORE_WAVE_FLUSH_DEADLINE_NANOS = TimeUnit.MICROSECONDS.toNanos(150L);

    private static final BlockPos SAMPLE_POS_NEG = new BlockPos(0, -1, 0);
    private static final BlockPos SAMPLE_POS_MID = new BlockPos(0, 0, 0);
    private static final BlockPos SAMPLE_POS_HIGH = new BlockPos(0, 9, 0);

    private final OpenClKernelCache kernelCache;
    private final int deviceIndex;
    private final ExecutorService compilerExecutor;
    private final ExecutorService oreWaveDispatchExecutor;
    private final WorldgenWaveCoalescer<OreChunkWaveTask, OreWaveResult> oreWaveCoalescer;
    private final ConcurrentHashMap<Integer, WorldgenWaveCoalescer.WaveKey> oreWaveKeys = new ConcurrentHashMap<>(4);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean compileStarted = new AtomicBoolean(false);
    private volatile GoldLeafOpenClBridge.@Nullable CompiledKernel compiledKernel;

    private final ConcurrentHashMap<OreFeature, CompiledOreTargets> targetCache = new ConcurrentHashMap<>(64);
    private final ConcurrentHashMap<PlacedFeature, CompiledOrePlacement> placementCache = new ConcurrentHashMap<>(64);
    private static final ConcurrentHashMap<OreFeature, CompiledOreTargets> SHARED_TARGET_CACHE = new ConcurrentHashMap<>(64);
    private static final ConcurrentHashMap<PlacedFeature, CompiledOrePlacement> SHARED_PLACEMENT_CACHE = new ConcurrentHashMap<>(64);
    private static final ThreadLocal<WaveDispatchScratch> TL_WAVE_SCRATCH = ThreadLocal.withInitial(WaveDispatchScratch::new);
    private final LongAdder gpuBatches = new LongAdder();
    private final LongAdder gpuVeins = new LongAdder();
    private final LongAdder gpuVoxels = new LongAdder();
    private final LongAdder gpuKernelNanos = new LongAdder();
    private final LongAdder gpuReadbackNanos = new LongAdder();
    private final LongAdder cpuOverflowFallbackVeins = new LongAdder();
    private final LongAdder profCollectNanos = new LongAdder();
    private final LongAdder profDispatchWallNanos = new LongAdder();
    private final LongAdder profGpuApplyNanos = new LongAdder();
    private final LongAdder profCpuFallbackNanos = new LongAdder();
    private final LongAdder profCpuNonEligibleNanos = new LongAdder();

    private static final ThreadLocal<Scratch> TL_SCRATCH = ThreadLocal.withInitial(Scratch::new);

    public GpuOrePlacementEngine(
        final OpenClKernelCache kernelCache,
        final int deviceIndex,
        final ExecutorService compilerExecutor
    ) {
        this.kernelCache = kernelCache;
        this.deviceIndex = deviceIndex;
        this.compilerExecutor = compilerExecutor;
        final java.util.concurrent.atomic.AtomicInteger waveThreadSeq = new java.util.concurrent.atomic.AtomicInteger();
        this.oreWaveDispatchExecutor = Executors.newFixedThreadPool(2, r -> {
            final Thread t = new Thread(r, "goldleaf-opencl-ore-wave-" + waveThreadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.oreWaveCoalescer = new WorldgenWaveCoalescer<>(
            ORE_WAVE_MAX_CHUNKS,
            ORE_WAVE_FLUSH_DEADLINE_NANOS,
            this::dispatchOreWaveBatch,
            this.oreWaveDispatchExecutor
        );
    }

    /**
     * Triggers off-tick OpenCL compilation of {@code rasterize_ore_veins_batch} if not already started.
     */
    public void ensureCompileTriggered() {
        if (this.closed.get() || !this.compileStarted.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(this::compileKernelBlocking, this.compilerExecutor);
    }

    public boolean isReady() {
        return !this.closed.get() && this.compiledKernel != null;
    }

    public long gpuBatches() {
        return this.gpuBatches.sum();
    }

    public long gpuVeins() {
        return this.gpuVeins.sum();
    }

    public long gpuVoxels() {
        return this.gpuVoxels.sum();
    }

    public long gpuKernelNanos() {
        return this.gpuKernelNanos.sum();
    }

    public long gpuReadbackNanos() {
        return this.gpuReadbackNanos.sum();
    }

    public long cpuOverflowFallbackVeins() {
        return this.cpuOverflowFallbackVeins.sum();
    }

    public String profileBreakdown() {
        return String.format(
            java.util.Locale.ROOT,
            "collect=%.1fms dispatchWall=%.1fms gpuApply=%.1fms cpuFallback=%.1fms cpuOther=%.1fms",
            this.profCollectNanos.sum() / 1_000_000.0,
            this.profDispatchWallNanos.sum() / 1_000_000.0,
            this.profGpuApplyNanos.sum() / 1_000_000.0,
            this.profCpuFallbackNanos.sum() / 1_000_000.0,
            this.profCpuNonEligibleNanos.sum() / 1_000_000.0
        );
    }

    private void compileKernelBlocking() {
        if (this.closed.get()) {
            return;
        }
        final long startNs = System.nanoTime();
        try {
            final String source = buildOreVeinRasterKernelSource();
            final String digest = sha256Hex(CACHE_KEY_PREFIX + "\n" + source);
            final OpenClBackendEmitter.DensityVolumeProgram prog = new OpenClBackendEmitter.DensityVolumeProgram(
                KERNEL_NAME,
                source,
                digest,
                new int[0],
                0,
                0,
                0
            );
            final GoldLeafOpenClBridge.CompiledKernel kernel = this.kernelCache.getOrCompile(
                this.deviceIndex,
                prog,
                BUILD_OPTS_STRICT
            );
            if (kernel == null || this.closed.get()) {
                return;
            }
            this.compiledKernel = kernel;
            LOGGER.info(
                "GoldLeaf GPU ore-vein rasterizer kernel ready in {} ms (device={})",
                String.format("%.1f", (System.nanoTime() - startNs) / 1_000_000.0),
                this.deviceIndex
            );
        } catch (final Throwable t) {
            LOGGER.warn("GoldLeaf GPU ore-vein kernel compile failed; UNDERGROUND_ORES will stay on CPU: {}", t.getMessage());
        }
    }

    /**
     * Accelerates {@code GenerationStep.Decoration.UNDERGROUND_ORES} for a chunk while preserving
     * exact vanilla feature-over-feature ordering and RNG progression.
     *
     * @return {@code true} if the entire step was executed; {@code false} if GPU is not ready
     */
    public boolean accelerateUndergroundOres(
        final WorldGenLevel level,
        final ChunkGenerator generator,
        final ChunkAccess centerChunk,
        final WorldgenRandom random,
        final long decorationSeed,
        final Reference2LongMap<Holder<Feature>> configuredFeatureSeeds,
        final boolean hasConfiguredFeatureSeeds,
        final BlockPos origin,
        final int stepIndex,
        final List<PlacedFeature> stepFeatures,
        final BitSet activeFeatureMask,
        final FeaturePlacer placer
    ) {
        final dev.leafcraft.goldleaf.config.GoldLeafConfig cfg = dev.leafcraft.goldleaf.config.GoldLeafConfig.get();
        if (cfg != null && cfg.worldgen != null && cfg.worldgen.hilbertOreVeins) {
            final long t0 = System.nanoTime();
            final boolean ok = accelerateUndergroundOresHilbertInternal(
                level, generator, centerChunk, random, decorationSeed,
                configuredFeatureSeeds, hasConfiguredFeatureSeeds, origin,
                stepIndex, stepFeatures, activeFeatureMask, placer,
                this.targetCache, this.placementCache
            );
            this.profGpuApplyNanos.add(System.nanoTime() - t0);
            return ok;
        }
        final GoldLeafOpenClBridge.CompiledKernel kernel = this.compiledKernel;
        if (kernel == null || this.closed.get()) {
            ensureCompileTriggered();
            return false;
        }
        if (!(random.goldleaf$randomSource() instanceof XoroshiroRandomSource xoro)) {
            return false;
        }

        final Scratch s = TL_SCRATCH.get();
        final ChunkPos centerPos = centerChunk.getPos();
        final int centerCx = centerPos.x();
        final int centerCz = centerPos.z();
        final int regionMinX = (centerCx - 1) << 4;
        final int regionMinZ = (centerCz - 1) << 4;
        final int minBuildY = level.getMinY();
        final int maxBuildY = level.getMaxY();

        // Reuse 3x3 region chunks already fetched by ChunkGenerator#addVanillaDecorations when available
        final ChunkAccess[] placerChunks = placer.goldleaf$regionChunks();
        final ChunkAccess[] regionChunks;
        if (placerChunks != null && placerChunks.length == 9) {
            regionChunks = placerChunks;
        } else {
            regionChunks = s.regionChunks;
            for (int dz = -1; dz <= 1; dz++) {
                final int rowBase = (dz + 1) * 3;
                for (int dx = -1; dx <= 1; dx++) {
                    regionChunks[rowBase + (dx + 1)] = (dx == 0 && dz == 0)
                        ? centerChunk
                        : level.getChunk(centerCx + dx, centerCz + dz);
                }
            }
        }
        final BitSet allBiomesMask = placer.goldleaf$allBiomesFeatureMask();
        final PlacementContext sharedHeightCtx = new PlacementContext(level, generator, Optional.empty());

        s.veinCount = 0;

        for (int globalIdx = activeFeatureMask.nextSetBit(0);
             globalIdx >= 0;
             globalIdx = activeFeatureMask.nextSetBit(globalIdx + 1)) {
            final PlacedFeature placed = stepFeatures.get(globalIdx);
            final boolean allBiomesHaveFeature = allBiomesMask != null && allBiomesMask.get(globalIdx);
            long featurePopSeed = decorationSeed;
            if (hasConfiguredFeatureSeeds) {
                final long cfgSeed = configuredFeatureSeeds.getLong(placed.feature());
                if (cfgSeed != -1L) {
                    featurePopSeed = random.setDecorationSeed(cfgSeed, origin.getX(), origin.getZ());
                }
            }
            random.setFeatureSeed(featurePopSeed, globalIdx, stepIndex);

            final Feature feat = placed.feature().value();
            if (feat.getClass() == OreFeature.class) {
                final OreFeature ore = (OreFeature) feat;
                final float discard = ore.discardChanceOnAirExposure();
                if ((discard <= 0.0F || discard >= 1.0F) && ore.size() > 0 && ore.size() <= 64) {
                    final long t0 = System.nanoTime();
                    final CompiledOreTargets compiled = this.targetCache.computeIfAbsent(ore, CompiledOreTargets::compile);
                    final CompiledOrePlacement compiledPlacement = this.placementCache.computeIfAbsent(placed, CompiledOrePlacement::compile);
                    if (compiledPlacement != CompiledOrePlacement.GENERIC) {
                        collectOreVeinsFast(
                            level, generator, placed, ore, compiled, compiledPlacement, allBiomesHaveFeature,
                            discard >= 1.0F, random, xoro, origin, sharedHeightCtx, regionChunks, regionMinX, regionMinZ, s
                        );
                    } else {
                        collectOreVeinsForFeature(
                            level, generator, placed, ore, compiled, allBiomesHaveFeature, discard >= 1.0F,
                            random, xoro, origin, regionChunks, regionMinX, regionMinZ, s
                        );
                    }
                    this.profCollectNanos.add(System.nanoTime() - t0);
                    if (s.veinCount >= MAX_VEINS_PER_BATCH - 64) {
                        flushGpuBatch(kernel, level, regionChunks, regionMinX, regionMinZ, minBuildY, maxBuildY, s);
                    }
                    continue;
                }
            }

            // Flush any queued GPU veins before running a non-GPU feature to preserve strict vanilla order
            if (s.veinCount > 0) {
                flushGpuBatch(kernel, level, regionChunks, regionMinX, regionMinZ, minBuildY, maxBuildY, s);
            }
            final long tOther0 = System.nanoTime();
            placer.placeWithBiomeCheck(placed, random, origin, allBiomesHaveFeature);
            this.profCpuNonEligibleNanos.add(System.nanoTime() - tOther0);
        }

        if (s.veinCount > 0) {
            flushGpuBatch(kernel, level, regionChunks, regionMinX, regionMinZ, minBuildY, maxBuildY, s);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void collectOreVeinsFast(
        final WorldGenLevel level,
        final ChunkGenerator generator,
        final PlacedFeature placed,
        final OreFeature ore,
        final CompiledOreTargets compiled,
        final CompiledOrePlacement placement,
        final boolean allBiomesHaveFeature,
        final boolean requireBuried,
        final WorldgenRandom random,
        final XoroshiroRandomSource xoro,
        final BlockPos origin,
        final PlacementContext heightCtx,
        final ChunkAccess[] regionChunks,
        final int regionMinX,
        final int regionMinZ,
        final Scratch s
    ) {
        final int count;
        if (placement.countProvider != null) {
            count = placement.countProvider.sample(random);
        } else {
            count = random.nextFloat() < placement.rarityInvChance ? 1 : 0;
        }
        if (count <= 0) {
            return;
        }
        final int ox = origin.getX();
        final int oz = origin.getZ();
        final HeightProvider heightProvider = placement.heightProvider;
        final BlockPos.MutableBlockPos biomePos = s.orePos;
        for (int i = 0; i < count; i++) {
            final int vx = random.nextInt(16) + ox;
            final int vz = random.nextInt(16) + oz;
            final int vy = heightProvider.sample(random, heightCtx);
            if (!allBiomesHaveFeature) {
                Holder<Biome> biome = null;
                final int lx = vx & 15;
                final int lz = vz & 15;
                final int ly = vy & 15;
                if (lx >= 2 && lx <= 13 && lz >= 2 && lz <= 13 && ly >= 2 && ly <= 13) {
                    final ChunkAccess c = regionChunks[4];
                    final int secIdx = c.getSectionIndex(vy);
                    final LevelChunkSection[] secs = c.getSections();
                    if (secIdx >= 0 && secIdx < secs.length) {
                        final PalettedContainer<Holder<Biome>> bCont = (PalettedContainer<Holder<Biome>>) secs[secIdx].getBiomes();
                        if (bCont.data.palette() instanceof SingleValuePalette<Holder<Biome>> single) {
                            biome = single.valueFor(0);
                        }
                    }
                }
                if (biome == null) {
                    biomePos.set(vx, vy, vz);
                    biome = level.getBiome(biomePos);
                }
                if (!generator.getBiomeGenerationSettings(biome).hasFeature(placed)) {
                    continue;
                }
            }
            tryQueueSingleVeinCoords(ore, compiled, requireBuried, random, xoro, vx, vy, vz, regionChunks, regionMinX, regionMinZ, s);
            if (SharedConstants.DEBUG_FEATURE_COUNT) {
                FeatureCountTracker.featurePlaced(level.getLevel(), ore, Optional.of(placed));
            }
        }
    }

    private static void collectOreVeinsForFeature(
        final WorldGenLevel level,
        final ChunkGenerator generator,
        final PlacedFeature placed,
        final OreFeature ore,
        final CompiledOreTargets compiled,
        final boolean allBiomesHaveFeature,
        final boolean requireBuried,
        final WorldgenRandom random,
        final XoroshiroRandomSource xoro,
        final BlockPos origin,
        final ChunkAccess[] regionChunks,
        final int regionMinX,
        final int regionMinZ,
        final Scratch s
    ) {
        final List<PlacementModifier> placement = placed.placement();
        if (placement.isEmpty()) {
            if (SharedConstants.DEBUG_FEATURE_COUNT) {
                FeatureCountTracker.featurePlaced(level.getLevel(), ore, Optional.of(placed));
            }
            tryQueueSingleVein(ore, compiled, requireBuried, random, xoro, origin, regionChunks, regionMinX, regionMinZ, s);
            return;
        }
        final PlacementContext context = new PlacementContext(
            level, generator, Optional.of(placed), allBiomesHaveFeature, regionChunks, regionMinX, regionMinZ
        );
        final ArrayList<BlockPos> positions = s.positions;
        final IntArrayList modIndices = s.modifierIndices;
        final ArrayList<BlockPos> modOut = s.modifiedPositions;
        positions.clear();
        modIndices.clear();
        modOut.clear();
        positions.add(origin);
        modIndices.add(0);

        final int modCount = placement.size();
        while (!positions.isEmpty()) {
            final BlockPos pos = positions.remove(positions.size() - 1);
            final int modIdx = modIndices.removeInt(modIndices.size() - 1);
            placement.get(modIdx).modify(context, random, pos, modOut::add);
            final int nextIdx = modIdx + 1;
            if (nextIdx < modCount) {
                for (int i = modOut.size() - 1; i >= 0; i--) {
                    positions.add(modOut.get(i));
                    modIndices.add(nextIdx);
                }
            } else {
                for (int i = 0, len = modOut.size(); i < len; i++) {
                    final BlockPos nextPos = modOut.get(i);
                    tryQueueSingleVein(ore, compiled, requireBuried, random, xoro, nextPos, regionChunks, regionMinX, regionMinZ, s);
                    if (SharedConstants.DEBUG_FEATURE_COUNT) {
                        FeatureCountTracker.featurePlaced(level.getLevel(), ore, Optional.of(placed));
                    }
                }
            }
            modOut.clear();
        }
    }

    private static void tryQueueSingleVein(
        final OreFeature ore,
        final CompiledOreTargets compiled,
        final boolean requireBuried,
        final WorldgenRandom random,
        final XoroshiroRandomSource xoro,
        final BlockPos veinOrigin,
        final ChunkAccess[] regionChunks,
        final int regionMinX,
        final int regionMinZ,
        final Scratch s
    ) {
        tryQueueSingleVeinCoords(
            ore, compiled, requireBuried, random, xoro,
            veinOrigin.getX(), veinOrigin.getY(), veinOrigin.getZ(),
            regionChunks, regionMinX, regionMinZ, s
        );
    }

    private static void tryQueueSingleVeinCoords(
        final OreFeature ore,
        final CompiledOreTargets compiled,
        final boolean requireBuried,
        final WorldgenRandom random,
        final XoroshiroRandomSource xoro,
        final int ox,
        final int oy,
        final int oz,
        final ChunkAccess[] regionChunks,
        final int regionMinX,
        final int regionMinZ,
        final Scratch s
    ) {
        final int size = ore.size();
        final float dir = random.nextFloat() * (float) Math.PI;
        final float spreadXY = size / 8.0F;
        final int maxRadius = Mth.ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);
        final double sinDir = Math.sin(dir) * spreadXY;
        final double cosDir = Math.cos(dir) * spreadXY;
        final double x0 = ox + sinDir;
        final double x1 = ox - sinDir;
        final double z0 = oz + cosDir;
        final double z1 = oz - cosDir;
        final double y0 = oy + random.nextInt(3) - 2;
        final double y1 = oy + random.nextInt(3) - 2;
        final int ceilSpread = Mth.ceil(spreadXY);
        final int xStart = ox - ceilSpread - maxRadius;
        final int yStart = oy - 2 - maxRadius;
        final int zStart = oz - ceilSpread - maxRadius;
        final int sizeXZ = 2 * (ceilSpread + maxRadius);
        final int sizeY = 2 * (2 + maxRadius);

        if (!passesHeightmapProbe(regionChunks, regionMinX, regionMinZ, ox, oz, xStart, yStart, zStart, sizeXZ)) {
            return;
        }

        final Xoroshiro128PlusPlus rng = xoro.goldleaf$rng();
        final long seedLo = rng.goldleaf$seedLo();
        final long seedHi = rng.goldleaf$seedHi();
        // Advance exact Xoroshiro128++ state by size * 2 longs (matching size calls to WorldgenRandom#nextDouble)
        xoro.consumeCount(size << 1);

        final int v = s.veinCount;
        if (v >= s.veinTargets.length) {
            s.growVeins(v + 64);
        }
        s.veinMeta4[v * 4] = xStart;
        s.veinMeta4[v * 4 + 1] = yStart;
        s.veinMeta4[v * 4 + 2] = zStart;
        s.veinMeta4[v * 4 + 3] = (size & 0xFF) | ((sizeXZ & 0xFF) << 8) | ((sizeY & 0xFF) << 16);

        s.veinRel6[v * 6] = (float) (x0 - xStart);
        s.veinRel6[v * 6 + 1] = (float) (x1 - xStart);
        s.veinRel6[v * 6 + 2] = (float) (y0 - yStart);
        s.veinRel6[v * 6 + 3] = (float) (y1 - yStart);
        s.veinRel6[v * 6 + 4] = (float) (z0 - zStart);
        s.veinRel6[v * 6 + 5] = (float) (z1 - zStart);

        s.veinSeeds2[v * 2] = seedLo;
        s.veinSeeds2[v * 2 + 1] = seedHi;

        s.veinTargets[v] = compiled;
        s.veinFeatures[v] = ore;
        s.veinRequireBuried[v] = requireBuried;
        s.veinCount = v + 1;
    }

    private static boolean passesHeightmapProbe(
        final ChunkAccess[] regionChunks,
        final int regionMinX,
        final int regionMinZ,
        final int ox,
        final int oz,
        final int xStart,
        final int yStart,
        final int zStart,
        final int sizeXZ
    ) {
        // Center-first fast probe
        final int crx = ox - regionMinX;
        final int crz = oz - regionMinZ;
        if ((crx | crz) >= 0 && crx < 48 && crz < 48) {
            final ChunkAccess c = regionChunks[(crz >> 4) * 3 + (crx >> 4)];
            if (yStart <= c.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, ox & 15, oz & 15)) {
                return true;
            }
        }
        final int xEnd = xStart + sizeXZ;
        final int zEnd = zStart + sizeXZ;
        for (int xp = xStart; xp <= xEnd; xp++) {
            final int rx = xp - regionMinX;
            if (rx < 0 || rx >= 48) {
                continue;
            }
            final int cxIdx = rx >> 4;
            final int lx = xp & 15;
            for (int zp = zStart; zp <= zEnd; zp++) {
                final int rz = zp - regionMinZ;
                if (rz < 0 || rz >= 48) {
                    continue;
                }
                final ChunkAccess c = regionChunks[(rz >> 4) * 3 + cxIdx];
                if (yStart <= c.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, lx, zp & 15)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void flushGpuBatch(
        final GoldLeafOpenClBridge.CompiledKernel kernel,
        final WorldGenLevel level,
        final ChunkAccess[] regionChunks,
        final int regionMinX,
        final int regionMinZ,
        final int minBuildY,
        final int maxBuildY,
        final Scratch s
    ) {
        final int veinCount = s.veinCount;
        s.veinCount = 0;
        if (veinCount <= 0) {
            return;
        }
        if (veinCount < MIN_VEINS_FOR_GPU) {
            final long tFb = System.nanoTime();
            for (int v = 0; v < veinCount; v++) {
                runSingleVeinCpuFallback(level, regionChunks, regionMinX, regionMinZ, minBuildY, maxBuildY, s, v);
            }
            this.profCpuFallbackNanos.add(System.nanoTime() - tFb);
            return;
        }
        int totalSpheres = 0;
        for (int v = 0; v < veinCount; v++) {
            totalSpheres += s.veinMeta4[v * 4 + 3] & 0xFF;
        }
        if (totalSpheres < MIN_SPHERES_FOR_GPU || this.closed.get()) {
            final long tFb = System.nanoTime();
            for (int v = 0; v < veinCount; v++) {
                runSingleVeinCpuFallback(level, regionChunks, regionMinX, regionMinZ, minBuildY, maxBuildY, s, v);
            }
            this.profCpuFallbackNanos.add(System.nanoTime() - tFb);
            return;
        }

        // Pack region-relative xStart/zStart so veins from up to 16 chunks coalesce into 1 OpenCL dispatch
        final int[] relMeta4 = new int[veinCount * 4];
        for (int v = 0; v < veinCount; v++) {
            final int b = v * 4;
            relMeta4[b] = s.veinMeta4[b] - regionMinX;
            relMeta4[b + 1] = s.veinMeta4[b + 1];
            relMeta4[b + 2] = s.veinMeta4[b + 2] - regionMinZ;
            relMeta4[b + 3] = s.veinMeta4[b + 3];
        }
        final float[] rel6Copy = java.util.Arrays.copyOf(s.veinRel6, veinCount * 6);
        final long[] seeds2Copy = java.util.Arrays.copyOf(s.veinSeeds2, veinCount * 2);
        final int dimKeyInt = (minBuildY << 16) | (maxBuildY & 0xFFFF);
        final WorldgenWaveCoalescer.WaveKey waveKey = this.oreWaveKeys.computeIfAbsent(
            dimKeyInt,
            k -> new WorldgenWaveCoalescer.WaveKey("ore", k)
        );

        final long tDisp0 = System.nanoTime();
        OreWaveResult waveResult;
        try {
            waveResult = this.oreWaveCoalescer.submit(
                waveKey,
                new OreChunkWaveTask(kernel, relMeta4, rel6Copy, seeds2Copy, veinCount, minBuildY, maxBuildY)
            ).join();
        } catch (final Throwable t) {
            LOGGER.warn("GoldLeaf GPU ore wave failed; falling back to CPU for {} veins: {}", veinCount, t.getMessage());
            waveResult = OreWaveResult.FALLBACK;
        }
        this.profDispatchWallNanos.add(System.nanoTime() - tDisp0);

        if (waveResult == null || waveResult.totalVoxels < 0) {
            final long tFb = System.nanoTime();
            for (int v = 0; v < veinCount; v++) {
                runSingleVeinCpuFallback(level, regionChunks, regionMinX, regionMinZ, minBuildY, maxBuildY, s, v);
            }
            this.profCpuFallbackNanos.add(System.nanoTime() - tFb);
            return;
        }

        final long tApply0 = System.nanoTime();

        final int[] slices = waveResult.slices2;
        final int[] voxels = waveResult.voxels;
        final int totalVoxels = waveResult.totalVoxels;
        final int veinBaseOffset = waveResult.veinOffset;
        final BlockPos.MutableBlockPos orePos = s.orePos;
        final int minSectionY = minBuildY >> 4;
        final int sectionCount = regionChunks[4].getSections().length;

        for (int v = 0; v < veinCount; v++) {
            final int sv = veinBaseOffset + v;
            final int base = slices[sv * 2];
            final int count = slices[sv * 2 + 1];
            if (count == 0) {
                continue;
            }
            if (count < 0 || base < 0 || base + count > totalVoxels) {
                this.cpuOverflowFallbackVeins.increment();
                runSingleVeinCpuFallback(level, regionChunks, regionMinX, regionMinZ, minBuildY, maxBuildY, s, v);
                continue;
            }

            final CompiledOreTargets targets = s.veinTargets[v];
            final boolean requireBuried = s.veinRequireBuried[v];
            int cachedChunkIdx = -1;
            int cachedSecIdx = -1;
            LevelChunkSection cachedSection = null;

            final int end = base + count;
            for (int i = base; i < end; i++) {
                final int packed = voxels[i];
                final int rx = packed & 63;
                final int rz = (packed >>> 6) & 63;
                final int ry = (packed >>> 12) & 511;
                final int wy = minBuildY + ry;
                final int chunkIdx = (rz >> 4) * 3 + (rx >> 4);
                final int secIdx = (wy >> 4) - minSectionY;
                if (secIdx < 0 || secIdx >= sectionCount) {
                    continue;
                }
                if (chunkIdx != cachedChunkIdx || secIdx != cachedSecIdx) {
                    cachedChunkIdx = chunkIdx;
                    cachedSecIdx = secIdx;
                    cachedSection = regionChunks[chunkIdx].getSection(secIdx);
                }
                if (cachedSection == null || cachedSection.hasOnlyAir()) {
                    continue;
                }
                final int lx = rx & 15;
                final int ly = wy & 15;
                final int lz = rz & 15;
                final BlockState current = cachedSection.getBlockState(lx, ly, lz);
                if (current.isAir()) {
                    continue;
                }
                final BlockState replacement = targets.resolve(current, wy, regionMinX + rx, regionMinZ + rz, orePos);
                if (replacement == null) {
                    continue;
                }
                if (requireBuried && isAdjacentToAirFast(regionChunks, cachedSection, chunkIdx, secIdx, sectionCount, rx, lx, ly, rz, lz)) {
                    continue;
                }
                if (targets.allTargetsSolidNonTicking && (current == targets.stoneState || current == targets.deepslateState || isSolidNonTicking(current))) {
                    cachedSection.paper$setSolidNonTickingBlockState(lx, ly, lz, replacement);
                } else {
                    cachedSection.setBlockState(lx, ly, lz, replacement, false);
                }
            }
        }
        this.profGpuApplyNanos.add(System.nanoTime() - tApply0);
    }

    /**
     * CPU-accessible entry point for opt-in {@code worldgen.hilbert-ore-veins} acceleration.
     */
    public static boolean accelerateUndergroundOresHilbertStatic(
        final WorldGenLevel level,
        final ChunkGenerator generator,
        final ChunkAccess centerChunk,
        final WorldgenRandom random,
        final long decorationSeed,
        final Reference2LongMap<Holder<Feature>> configuredFeatureSeeds,
        final boolean hasConfiguredFeatureSeeds,
        final BlockPos origin,
        final int stepIndex,
        final List<PlacedFeature> stepFeatures,
        final BitSet activeFeatureMask,
        final FeaturePlacer placer
    ) {
        return accelerateUndergroundOresHilbertInternal(
            level, generator, centerChunk, random, decorationSeed,
            configuredFeatureSeeds, hasConfiguredFeatureSeeds, origin,
            stepIndex, stepFeatures, activeFeatureMask, placer,
            SHARED_TARGET_CACHE, SHARED_PLACEMENT_CACHE
        );
    }

    @SuppressWarnings("unchecked")
    private static boolean accelerateUndergroundOresHilbertInternal(
        final WorldGenLevel level,
        final ChunkGenerator generator,
        final ChunkAccess centerChunk,
        final WorldgenRandom random,
        final long decorationSeed,
        final Reference2LongMap<Holder<Feature>> configuredFeatureSeeds,
        final boolean hasConfiguredFeatureSeeds,
        final BlockPos origin,
        final int stepIndex,
        final List<PlacedFeature> stepFeatures,
        final BitSet activeFeatureMask,
        final FeaturePlacer placer,
        final ConcurrentHashMap<OreFeature, CompiledOreTargets> targetsMap,
        final ConcurrentHashMap<PlacedFeature, CompiledOrePlacement> placementMap
    ) {
        final Scratch s = TL_SCRATCH.get();
        final ChunkPos centerPos = centerChunk.getPos();
        final int centerCx = centerPos.x();
        final int centerCz = centerPos.z();
        final int regionMinX = (centerCx - 1) << 4;
        final int regionMinZ = (centerCz - 1) << 4;
        final int minBuildY = level.getMinY();
        final int maxBuildY = level.getMaxY();

        final ChunkAccess[] placerChunks = placer.goldleaf$regionChunks();
        final ChunkAccess[] regionChunks;
        if (placerChunks != null && placerChunks.length == 9) {
            regionChunks = placerChunks;
        } else {
            regionChunks = s.regionChunks;
            for (int dz = -1; dz <= 1; dz++) {
                final int rowBase = (dz + 1) * 3;
                for (int dx = -1; dx <= 1; dx++) {
                    regionChunks[rowBase + (dx + 1)] = (dx == 0 && dz == 0)
                        ? centerChunk
                        : level.getChunk(centerCx + dx, centerCz + dz);
                }
            }
        }
        final BitSet allBiomesMask = placer.goldleaf$allBiomesFeatureMask();
        final PlacementContext sharedHeightCtx = new PlacementContext(level, generator, Optional.empty());
        final BlockPos.MutableBlockPos scratchPos = s.orePos;
        final int ox = origin.getX();
        final int oz = origin.getZ();

        for (int globalIdx = activeFeatureMask.nextSetBit(0);
             globalIdx >= 0;
             globalIdx = activeFeatureMask.nextSetBit(globalIdx + 1)) {
            final PlacedFeature placed = stepFeatures.get(globalIdx);
            final boolean allBiomesHaveFeature = allBiomesMask != null && allBiomesMask.get(globalIdx);
            long featurePopSeed = decorationSeed;
            if (hasConfiguredFeatureSeeds) {
                final long cfgSeed = configuredFeatureSeeds.getLong(placed.feature());
                if (cfgSeed != -1L) {
                    featurePopSeed = random.setDecorationSeed(cfgSeed, ox, oz);
                }
            }
            random.setFeatureSeed(featurePopSeed, globalIdx, stepIndex);

            final Feature feat = placed.feature().value();
            if (feat.getClass() == OreFeature.class) {
                final OreFeature ore = (OreFeature) feat;
                final int size = ore.size();
                if (size > 0 && size <= 64) {
                    final CompiledOreTargets compiled = targetsMap.computeIfAbsent(ore, CompiledOreTargets::compile);
                    final CompiledOrePlacement compiledPlacement = placementMap.computeIfAbsent(placed, CompiledOrePlacement::compile);
                    final float discard = ore.discardChanceOnAirExposure();
                    if (compiledPlacement != CompiledOrePlacement.GENERIC) {
                        final int count = compiledPlacement.countProvider != null
                            ? compiledPlacement.countProvider.sample(random)
                            : (random.nextFloat() < compiledPlacement.rarityInvChance ? 1 : 0);
                        if (count > 0) {
                            final HeightProvider hp = compiledPlacement.heightProvider;
                            for (int i = 0; i < count; i++) {
                                final int vx = random.nextInt(16) + ox;
                                final int vz = random.nextInt(16) + oz;
                                final int vy = hp.sample(random, sharedHeightCtx);
                                if (!allBiomesHaveFeature) {
                                    Holder<Biome> biome = null;
                                    final int lx = vx & 15;
                                    final int lz = vz & 15;
                                    final int ly = vy & 15;
                                    if (lx >= 2 && lx <= 13 && lz >= 2 && lz <= 13 && ly >= 2 && ly <= 13) {
                                        final ChunkAccess c = regionChunks[4];
                                        final int secIdx = c.getSectionIndex(vy);
                                        final LevelChunkSection[] secs = c.getSections();
                                        if (secIdx >= 0 && secIdx < secs.length) {
                                            final PalettedContainer<Holder<Biome>> bCont = (PalettedContainer<Holder<Biome>>) secs[secIdx].getBiomes();
                                            if (bCont.data.palette() instanceof SingleValuePalette<Holder<Biome>> single) {
                                                biome = single.valueFor(0);
                                            }
                                        }
                                    }
                                    if (biome == null) {
                                        scratchPos.set(vx, vy, vz);
                                        biome = level.getBiome(scratchPos);
                                    }
                                    if (!generator.getBiomeGenerationSettings(biome).hasFeature(placed)) {
                                        continue;
                                    }
                                }
                                HilbertOreVeinWalker.placeVein(
                                    vx, vy, vz, size, discard, compiled, random,
                                    regionChunks, regionMinX, regionMinZ, minBuildY, maxBuildY, scratchPos
                                );
                                if (SharedConstants.DEBUG_FEATURE_COUNT) {
                                    FeatureCountTracker.featurePlaced(level.getLevel(), ore, Optional.of(placed));
                                }
                            }
                        }
                        continue;
                    }
                }
            }
            placer.placeWithBiomeCheck(placed, random, origin, allBiomesHaveFeature);
        }
        return true;
    }

    static boolean isAdjacentToAirFast(
        final ChunkAccess[] regionChunks,
        final LevelChunkSection section,
        final int chunkIdx,
        final int secIdx,
        final int sectionCount,
        final int rx,
        final int lx,
        final int ly,
        final int rz,
        final int lz
    ) {
        if (lx >= 1 && lx <= 14 && ly >= 1 && ly <= 14 && lz >= 1 && lz <= 14) {
            return section.getBlockState(lx, ly - 1, lz).isAir()
                || section.getBlockState(lx, ly + 1, lz).isAir()
                || section.getBlockState(lx, ly, lz - 1).isAir()
                || section.getBlockState(lx, ly, lz + 1).isAir()
                || section.getBlockState(lx - 1, ly, lz).isAir()
                || section.getBlockState(lx + 1, ly, lz).isAir();
        }
        // Y - 1
        if (ly > 0) {
            if (section.getBlockState(lx, ly - 1, lz).isAir()) return true;
        } else if (secIdx > 0) {
            if (regionChunks[chunkIdx].getSection(secIdx - 1).getBlockState(lx, 15, lz).isAir()) return true;
        } else {
            return true;
        }
        // Y + 1
        if (ly < 15) {
            if (section.getBlockState(lx, ly + 1, lz).isAir()) return true;
        } else if (secIdx + 1 < sectionCount) {
            if (regionChunks[chunkIdx].getSection(secIdx + 1).getBlockState(lx, 0, lz).isAir()) return true;
        } else {
            return true;
        }
        // X - 1
        if (lx > 0) {
            if (section.getBlockState(lx - 1, ly, lz).isAir()) return true;
        } else if (rx > 0) {
            if (regionChunks[chunkIdx - 1].getSection(secIdx).getBlockState(15, ly, lz).isAir()) return true;
        }
        // X + 1
        if (lx < 15) {
            if (section.getBlockState(lx + 1, ly, lz).isAir()) return true;
        } else if (rx < 47) {
            if (regionChunks[chunkIdx + 1].getSection(secIdx).getBlockState(0, ly, lz).isAir()) return true;
        }
        // Z - 1
        if (lz > 0) {
            if (section.getBlockState(lx, ly, lz - 1).isAir()) return true;
        } else if (rz > 0) {
            if (regionChunks[chunkIdx - 3].getSection(secIdx).getBlockState(lx, ly, 15).isAir()) return true;
        }
        // Z + 1
        if (lz < 15) {
            if (section.getBlockState(lx, ly, lz + 1).isAir()) return true;
        } else if (rz < 47) {
            if (regionChunks[chunkIdx + 3].getSection(secIdx).getBlockState(lx, ly, 0).isAir()) return true;
        }
        return false;
    }

    private static void runSingleVeinCpuFallback(
        final WorldGenLevel level,
        final ChunkAccess[] regionChunks,
        final int regionMinX,
        final int regionMinZ,
        final int minBuildY,
        final int maxBuildY,
        final Scratch s,
        final int v
    ) {
        final int xStart = s.veinMeta4[v * 4];
        final int yStart = s.veinMeta4[v * 4 + 1];
        final int zStart = s.veinMeta4[v * 4 + 2];
        final int packedMeta = s.veinMeta4[v * 4 + 3];
        final int size = packedMeta & 0xFF;
        final int sizeXZ = (packedMeta >>> 8) & 0xFF;
        final int sizeY = (packedMeta >>> 16) & 0xFF;
        final double x0 = xStart + s.veinRel6[v * 6];
        final double x1 = xStart + s.veinRel6[v * 6 + 1];
        final double y0 = yStart + s.veinRel6[v * 6 + 2];
        final double y1 = yStart + s.veinRel6[v * 6 + 3];
        final double z0 = zStart + s.veinRel6[v * 6 + 4];
        final double z1 = zStart + s.veinRel6[v * 6 + 5];
        long s0 = s.veinSeeds2[v * 2];
        long s1 = s.veinSeeds2[v * 2 + 1];
        final CompiledOreTargets targets = s.veinTargets[v];
        final boolean requireBuried = s.veinRequireBuried[v];
        final double[] data = s.fallbackSpheres;
        final double sizeDiv16 = size / 16.0;
        for (int i = 0; i < size; i++) {
            final float step = (float) i / size;
            final double xx = Mth.lerp(step, x0, x1);
            final double yy = Mth.lerp(step, y0, y1);
            final double zz = Mth.lerp(step, z0, z1);
            // Exact inline Xoroshiro128++ WorldgenRandom#nextDouble (zero allocation)
            long r0 = Long.rotateLeft(s0 + s1, 17) + s0;
            s1 ^= s0;
            s0 = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
            s1 = Long.rotateLeft(s1, 28);
            long r1 = Long.rotateLeft(s0 + s1, 17) + s0;
            s1 ^= s0;
            s0 = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
            s1 = Long.rotateLeft(s1, 28);
            final double nextDbl = (((r0 >>> 38) << 27) + (r1 >>> 37)) * 1.1102230246251565E-16;
            final double ss = nextDbl * sizeDiv16;
            final double r = ((Mth.sin(Mth.PI * step) + 1.0F) * ss + 1.0) / 2.0;
            data[i * 4] = xx;
            data[i * 4 + 1] = yy;
            data[i * 4 + 2] = zz;
            data[i * 4 + 3] = r;
        }
        for (int i1 = 0; i1 < size - 1; i1++) {
            if (data[i1 * 4 + 3] <= 0.0) continue;
            for (int i2 = i1 + 1; i2 < size; i2++) {
                if (data[i2 * 4 + 3] <= 0.0) continue;
                final double dx = data[i1 * 4] - data[i2 * 4];
                final double dy = data[i1 * 4 + 1] - data[i2 * 4 + 1];
                final double dz = data[i1 * 4 + 2] - data[i2 * 4 + 2];
                final double dr = data[i1 * 4 + 3] - data[i2 * 4 + 3];
                if (dr * dr > dx * dx + dy * dy + dz * dz) {
                    if (dr > 0.0) data[i2 * 4 + 3] = -1.0;
                    else data[i1 * 4 + 3] = -1.0;
                }
            }
        }
        final BitSet tested = s.fallbackTested;
        tested.clear();
        final int zStride = sizeXZ * sizeY;
        final int minSectionY = minBuildY >> 4;
        final int sectionCount = regionChunks[4].getSections().length;
        final BlockPos.MutableBlockPos orePos = s.orePos;
        for (int i = 0; i < size; i++) {
            final double r = data[i * 4 + 3];
            if (r < 0.0) continue;
            final double xx = data[i * 4];
            final double yy = data[i * 4 + 1];
            final double zz = data[i * 4 + 2];
            final double invR = 1.0 / r;
            final int xMin = Math.max(Mth.floor(xx - r), xStart);
            final int yMin = Math.max(Mth.floor(yy - r), yStart);
            final int zMin = Math.max(Mth.floor(zz - r), zStart);
            final int xMax = Math.max(Mth.floor(xx + r), xMin);
            final int yMax = Math.max(Mth.floor(yy + r), yMin);
            final int zMax = Math.max(Mth.floor(zz + r), zMin);
            for (int x = xMin; x <= xMax; x++) {
                final int rx = x - regionMinX;
                if (rx < 0 || rx >= 48) continue;
                final double xd = (x + 0.5 - xx) * invR;
                final double xdSq = xd * xd;
                if (xdSq >= 1.0) continue;
                final int xBitBase = x - xStart;
                for (int z = zMin; z <= zMax; z++) {
                    final int rz = z - regionMinZ;
                    if (rz < 0 || rz >= 48) continue;
                    final double zd = (z + 0.5 - zz) * invR;
                    final double xzdSq = xdSq + zd * zd;
                    if (xzdSq >= 1.0) continue;
                    final int chunkIdx = (rz >> 4) * 3 + (rx >> 4);
                    final int lx = rx & 15;
                    final int lz = rz & 15;
                    final int xzBitBase = xBitBase + (z - zStart) * zStride;
                    int cachedSecIdx = -1;
                    LevelChunkSection section = null;
                    for (int y = yMin; y <= yMax; y++) {
                        if (y < minBuildY || y > maxBuildY) continue;
                        final double yd = (y + 0.5 - yy) * invR;
                        if (xzdSq + yd * yd >= 1.0) continue;
                        final int bitIdx = xzBitBase + (y - yStart) * sizeXZ;
                        if (bitIdx >= 0 && tested.get(bitIdx)) continue;
                        if (bitIdx >= 0) tested.set(bitIdx);
                        final int secIdx = (y >> 4) - minSectionY;
                        if (secIdx < 0 || secIdx >= sectionCount) continue;
                        if (secIdx != cachedSecIdx) {
                            cachedSecIdx = secIdx;
                            section = regionChunks[chunkIdx].getSection(secIdx);
                        }
                        if (section == null || section.hasOnlyAir()) continue;
                        final int ly = y & 15;
                        final BlockState current = section.getBlockState(lx, ly, lz);
                        if (current.isAir()) continue;
                        final BlockState replacement = targets.resolve(current, y, x, z, orePos);
                        if (replacement == null) continue;
                        if (requireBuried && isAdjacentToAirFast(regionChunks, section, chunkIdx, secIdx, sectionCount, rx, lx, ly, rz, lz)) {
                            continue;
                        }
                        if (targets.allTargetsSolidNonTicking && (current == targets.stoneState || current == targets.deepslateState || isSolidNonTicking(current))) {
                            section.paper$setSolidNonTickingBlockState(lx, ly, lz, replacement);
                        } else {
                            section.setBlockState(lx, ly, lz, replacement, false);
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds the OpenCL 1.2 {@code rasterize_ore_veins_batch} kernel source (zero FP64 instructions,
     * exact {@code Xoroshiro128++} {@code WorldgenRandom#nextDouble}, parallel {@code O(size)}
     * sphere subsumption, and workgroup-compacted voxel slices).
     */
    public static String buildOreVeinRasterKernelSource() {
        final StringBuilder sb = new StringBuilder(6144);
        sb.append("#pragma OPENCL FP_CONTRACT OFF\n\n");
        sb.append("static inline ulong gl_rotl64(const ulong x, const int k) {\n");
        sb.append("    return (x << k) | (x >> (64 - k));\n");
        sb.append("}\n\n");
        sb.append("static inline ulong gl_xoro_next_long(ulong* s0p, ulong* s1p) {\n");
        sb.append("    const ulong s0 = *s0p;\n");
        sb.append("    ulong s1 = *s1p;\n");
        sb.append("    const ulong result = gl_rotl64(s0 + s1, 17) + s0;\n");
        sb.append("    s1 ^= s0;\n");
        sb.append("    *s0p = gl_rotl64(s0, 49) ^ s1 ^ (s1 << 21);\n");
        sb.append("    *s1p = gl_rotl64(s1, 28);\n");
        sb.append("    return result;\n");
        sb.append("}\n\n");
        sb.append("static inline float gl_wg_next_double_f32(ulong* s0p, ulong* s1p) {\n");
        sb.append("    const ulong upper = gl_xoro_next_long(s0p, s1p) >> 38;\n");
        sb.append("    const ulong lower = gl_xoro_next_long(s0p, s1p) >> 37;\n");
        sb.append("    const ulong combined = (upper << 27) + lower;\n");
        sb.append("    return (float)combined * 1.1102230246251565e-16f;\n");
        sb.append("}\n\n");
        sb.append("__kernel void ").append(KERNEL_NAME).append("(\n");
        sb.append("    __global const int4* restrict vein_meta,\n");
        sb.append("    __global const float* restrict vein_rel6,\n");
        sb.append("    __global const ulong2* restrict vein_seeds,\n");
        sb.append("    __global int2* restrict out_slices,\n");
        sb.append("    __global uint* restrict out_voxels,\n");
        sb.append("    __global uint* restrict out_counter,\n");
        sb.append("    const int vein_count,\n");
        sb.append("    const int region_min_x,\n");
        sb.append("    const int region_min_z,\n");
        sb.append("    const int min_build_y,\n");
        sb.append("    const int max_build_y,\n");
        sb.append("    const int out_max_voxels)\n");
        sb.append("{\n");
        sb.append("    const int v = (int)get_group_id(0);\n");
        sb.append("    if (v >= vein_count) return;\n");
        sb.append("    const int lid = (int)get_local_id(0);\n");
        sb.append("    const int4 meta = vein_meta[v];\n");
        sb.append("    const int x_start = meta.x;\n");
        sb.append("    const int y_start = meta.y;\n");
        sb.append("    const int z_start = meta.z;\n");
        sb.append("    const int size = meta.w & 0xFF;\n");
        sb.append("    __local float4 l_spheres[64];\n");
        sb.append("    __local uchar l_killed[64];\n");
        sb.append("    __local float4 l_surv[64];\n");
        sb.append("    __local int l_surv_count;\n");
        sb.append("    __local int l_bounds[6];\n");
        sb.append("    __local int l_thread_cnt[128];\n");
        sb.append("    __local int l_thread_off[128];\n");
        sb.append("    __local uint l_voxels[4096];\n");
        sb.append("    __local int l_voxel_count;\n");
        sb.append("    __local int l_global_base;\n\n");
        sb.append("    if (lid == 0) {\n");
        sb.append("        const int rbase = v * 6;\n");
        sb.append("        const float rx0 = vein_rel6[rbase + 0];\n");
        sb.append("        const float rx1 = vein_rel6[rbase + 1];\n");
        sb.append("        const float ry0 = vein_rel6[rbase + 2];\n");
        sb.append("        const float ry1 = vein_rel6[rbase + 3];\n");
        sb.append("        const float rz0 = vein_rel6[rbase + 4];\n");
        sb.append("        const float rz1 = vein_rel6[rbase + 5];\n");
        sb.append("        const ulong2 seed = vein_seeds[v];\n");
        sb.append("        ulong s0 = seed.x;\n");
        sb.append("        ulong s1 = seed.y;\n");
        sb.append("        const float inv_size = 1.0f / (float)size;\n");
        sb.append("        const float size_div16 = (float)size * 0.0625f;\n");
        sb.append("        for (int i = 0; i < size; i++) {\n");
        sb.append("            const float step = (float)i * inv_size;\n");
        sb.append("            const float xx = rx0 + step * (rx1 - rx0);\n");
        sb.append("            const float yy = ry0 + step * (ry1 - ry0);\n");
        sb.append("            const float zz = rz0 + step * (rz1 - rz0);\n");
        sb.append("            const float ss = gl_wg_next_double_f32(&s0, &s1) * size_div16;\n");
        sb.append("            const float r = ((sin(3.141592653589793f * step) + 1.0f) * ss + 1.0f) * 0.5f;\n");
        sb.append("            l_spheres[i] = (float4)(xx, yy, zz, r);\n");
        sb.append("        }\n");
        sb.append("        l_voxel_count = 0;\n");
        sb.append("    }\n");
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n\n");
        sb.append("    if (lid < size) {\n");
        sb.append("        const float4 si = l_spheres[lid];\n");
        sb.append("        uchar killed = 0;\n");
        sb.append("        if (si.w > 0.0f) {\n");
        sb.append("            for (int j = 0; j < size; j++) {\n");
        sb.append("                if (j == lid) continue;\n");
        sb.append("                const float4 sj = l_spheres[j];\n");
        sb.append("                const float dr = sj.w - si.w;\n");
        sb.append("                if (dr > 0.0f) {\n");
        sb.append("                    const float dx = sj.x - si.x;\n");
        sb.append("                    const float dy = sj.y - si.y;\n");
        sb.append("                    const float dz = sj.z - si.z;\n");
        sb.append("                    if (dr * dr > dx * dx + dy * dy + dz * dz) {\n");
        sb.append("                        killed = 1;\n");
        sb.append("                        break;\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        l_killed[lid] = killed;\n");
        sb.append("    }\n");
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n\n");
        sb.append("    if (lid == 0) {\n");
        sb.append("        int sc = 0;\n");
        sb.append("        int min_x = 1000000, max_x = -1000000;\n");
        sb.append("        int min_y = 1000000, max_y = -1000000;\n");
        sb.append("        int min_z = 1000000, max_z = -1000000;\n");
        sb.append("        for (int i = 0; i < size; i++) {\n");
        sb.append("            if (l_killed[i] != 0) continue;\n");
        sb.append("            const float4 s = l_spheres[i];\n");
        sb.append("            if (s.w <= 0.0f) continue;\n");
        sb.append("            const int sx0 = max((int)floor(s.x - s.w), 0);\n");
        sb.append("            const int sy0 = max((int)floor(s.y - s.w), 0);\n");
        sb.append("            const int sz0 = max((int)floor(s.z - s.w), 0);\n");
        sb.append("            const int sx1 = max((int)floor(s.x + s.w), sx0);\n");
        sb.append("            const int sy1 = max((int)floor(s.y + s.w), sy0);\n");
        sb.append("            const int sz1 = max((int)floor(s.z + s.w), sz0);\n");
        sb.append("            if (sx0 < min_x) min_x = sx0; if (sx1 > max_x) max_x = sx1;\n");
        sb.append("            if (sy0 < min_y) min_y = sy0; if (sy1 > max_y) max_y = sy1;\n");
        sb.append("            if (sz0 < min_z) min_z = sz0; if (sz1 > max_z) max_z = sz1;\n");
        sb.append("            l_surv[sc++] = (float4)(s.x, s.y, s.z, 1.0f / s.w);\n");
        sb.append("        }\n");
        sb.append("        const int reg_lx_lo = max(0, region_min_x - x_start);\n");
        sb.append("        const int reg_lx_hi = (region_min_x + 47) - x_start;\n");
        sb.append("        const int reg_lz_lo = max(0, region_min_z - z_start);\n");
        sb.append("        const int reg_lz_hi = (region_min_z + 47) - z_start;\n");
        sb.append("        const int bld_ly_lo = max(0, min_build_y - y_start);\n");
        sb.append("        const int bld_ly_hi = max_build_y - y_start;\n");
        sb.append("        if (min_x < reg_lx_lo) min_x = reg_lx_lo;\n");
        sb.append("        if (max_x > reg_lx_hi) max_x = reg_lx_hi;\n");
        sb.append("        if (min_z < reg_lz_lo) min_z = reg_lz_lo;\n");
        sb.append("        if (max_z > reg_lz_hi) max_z = reg_lz_hi;\n");
        sb.append("        if (min_y < bld_ly_lo) min_y = bld_ly_lo;\n");
        sb.append("        if (max_y > bld_ly_hi) max_y = bld_ly_hi;\n");
        sb.append("        l_surv_count = sc;\n");
        sb.append("        l_bounds[0] = min_x; l_bounds[1] = max_x;\n");
        sb.append("        l_bounds[2] = min_y; l_bounds[3] = max_y;\n");
        sb.append("        l_bounds[4] = min_z; l_bounds[5] = max_z;\n");
        sb.append("    }\n");
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n\n");
        sb.append("    const int sc = l_surv_count;\n");
        sb.append("    const int min_x = l_bounds[0], max_x = l_bounds[1];\n");
        sb.append("    const int min_y = l_bounds[2], max_y = l_bounds[3];\n");
        sb.append("    const int min_z = l_bounds[4], max_z = l_bounds[5];\n");
        sb.append("""
                if (sc > 0 && min_x <= max_x && min_y <= max_y && min_z <= max_z) {
                    const int len_x = max_x - min_x + 1;
                    const int len_y = max_y - min_y + 1;
                    const int len_z = max_z - min_z + 1;
                    const int total = len_x * len_y * len_z;
                    const int chunk = (total + 127) >> 7;
                    const int start_idx = lid * chunk;
                    const int end_idx = min(start_idx + chunk, total);
                    int my_cnt = 0;
                    for (int idx = start_idx; idx < end_idx; idx++) {
                        const int ly = min_y + (idx % len_y);
                        const int t = idx / len_y;
                        const int lz = min_z + (t % len_z);
                        const int lx = min_x + (t / len_z);
                        const float fx = (float)lx + 0.5f;
                        const float fy = (float)ly + 0.5f;
                        const float fz = (float)lz + 0.5f;
                        for (int k = 0; k < sc; k++) {
                            const float4 sp = l_surv[k];
                            const float inv_r = sp.w;
                            const float xd = (fx - sp.x) * inv_r;
                            const float xd2 = xd * xd;
                            if (xd2 >= 1.0f) continue;
                            const float zd = (fz - sp.z) * inv_r;
                            const float xzd2 = xd2 + zd * zd;
                            if (xzd2 >= 1.0f) continue;
                            const float yd = (fy - sp.y) * inv_r;
                            if (xzd2 + yd * yd < 1.0f) { my_cnt++; break; }
                        }
                    }
                    l_thread_cnt[lid] = my_cnt;
                } else {
                    l_thread_cnt[lid] = 0;
                }
                barrier(CLK_LOCAL_MEM_FENCE);

                if (lid == 0) {
                    int sum = 0;
                    for (int i = 0; i < 128; i++) {
                        l_thread_off[i] = sum;
                        sum += l_thread_cnt[i];
                    }
                    l_voxel_count = sum;
                }
                barrier(CLK_LOCAL_MEM_FENCE);

                const int my_cnt = l_thread_cnt[lid];
                if (my_cnt > 0 && l_voxel_count <= 4096) {
                    const int len_x = max_x - min_x + 1;
                    const int len_y = max_y - min_y + 1;
                    const int len_z = max_z - min_z + 1;
                    const int total = len_x * len_y * len_z;
                    const int chunk = (total + 127) >> 7;
                    const int start_idx = lid * chunk;
                    const int end_idx = min(start_idx + chunk, total);
                    const int x_reg_off = x_start - region_min_x;
                    const int z_reg_off = z_start - region_min_z;
                    const int y_bld_off = y_start - min_build_y;
                    int out_pos = l_thread_off[lid];
                    for (int idx = start_idx; idx < end_idx; idx++) {
                        const int ly = min_y + (idx % len_y);
                        const int t = idx / len_y;
                        const int lz = min_z + (t % len_z);
                        const int lx = min_x + (t / len_z);
                        const float fx = (float)lx + 0.5f;
                        const float fy = (float)ly + 0.5f;
                        const float fz = (float)lz + 0.5f;
                        int hit = 0;
                        for (int k = 0; k < sc; k++) {
                            const float4 sp = l_surv[k];
                            const float inv_r = sp.w;
                            const float xd = (fx - sp.x) * inv_r;
                            const float xd2 = xd * xd;
                            if (xd2 >= 1.0f) continue;
                            const float zd = (fz - sp.z) * inv_r;
                            const float xzd2 = xd2 + zd * zd;
                            if (xzd2 >= 1.0f) continue;
                            const float yd = (fy - sp.y) * inv_r;
                            if (xzd2 + yd * yd < 1.0f) { hit = 1; break; }
                        }
                        if (hit != 0) {
                            const uint rx = (uint)(x_reg_off + lx);
                            const uint rz = (uint)(z_reg_off + lz);
                            const uint ry = (uint)(y_bld_off + ly);
                            l_voxels[out_pos++] = (rx & 63U) | ((rz & 63U) << 6) | ((ry & 511U) << 12);
                        }
                    }
                }
                barrier(CLK_LOCAL_MEM_FENCE);
            """);
        sb.append("    if (lid == 0) {\n");
        sb.append("        const int vc = l_voxel_count;\n");
        sb.append("        if (vc <= 0) {\n");
        sb.append("            out_slices[v] = (int2)(0, 0);\n");
        sb.append("            l_global_base = -1;\n");
        sb.append("        } else if (vc > 4096) {\n");
        sb.append("            out_slices[v] = (int2)(0, -1);\n");
        sb.append("            l_global_base = -1;\n");
        sb.append("        } else {\n");
        sb.append("            const uint base = atomic_add(out_counter, (uint)vc);\n");
        sb.append("            if ((int)base + vc > out_max_voxels) {\n");
        sb.append("                out_slices[v] = (int2)(0, -1);\n");
        sb.append("                l_global_base = -1;\n");
        sb.append("            } else {\n");
        sb.append("                out_slices[v] = (int2)((int)base, vc);\n");
        sb.append("                l_global_base = (int)base;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n\n");
        sb.append("    const int gbase = l_global_base;\n");
        sb.append("    if (gbase >= 0) {\n");
        sb.append("        const int vc = l_voxel_count;\n");
        sb.append("        for (int i = lid; i < vc; i += 128) {\n");
        sb.append("            out_voxels[gbase + i] = l_voxels[i];\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private List<OreWaveResult> dispatchOreWaveBatch(
        final WorldgenWaveCoalescer.WaveKey key,
        final List<OreChunkWaveTask> batch
    ) {
        final int taskCount = batch.size();
        final List<OreWaveResult> results = new ArrayList<>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            results.add(OreWaveResult.FALLBACK);
        }
        if (taskCount == 0 || this.closed.get()) {
            return results;
        }

        int cursor = 0;
        while (cursor < taskCount) {
            int subEnd = cursor;
            int subVeins = 0;
            final OreChunkWaveTask first = batch.get(cursor);
            final GoldLeafOpenClBridge.CompiledKernel kernel = first.kernel;
            final int minBuildY = first.minBuildY;
            final int maxBuildY = first.maxBuildY;
            while (subEnd < taskCount) {
                final OreChunkWaveTask t = batch.get(subEnd);
                if (subEnd > cursor && subVeins + t.veinCount > MAX_VEINS_PER_WAVE) {
                    break;
                }
                subVeins += t.veinCount;
                subEnd++;
            }
            if (subVeins <= 0) {
                cursor = subEnd;
                continue;
            }
            final WaveDispatchScratch ws = TL_WAVE_SCRATCH.get();
            if (ws.waveMeta4.length < subVeins * 4) {
                ws.waveMeta4 = new int[subVeins * 4];
                ws.waveRel6 = new float[subVeins * 6];
                ws.waveSeeds2 = new long[subVeins * 2];
            }
            int offset = 0;
            for (int i = cursor; i < subEnd; i++) {
                final OreChunkWaveTask t = batch.get(i);
                System.arraycopy(t.relMeta4, 0, ws.waveMeta4, offset * 4, t.veinCount * 4);
                System.arraycopy(t.rel6, 0, ws.waveRel6, offset * 6, t.veinCount * 6);
                System.arraycopy(t.seeds2, 0, ws.waveSeeds2, offset * 2, t.veinCount * 2);
                offset += t.veinCount;
            }
            final int outVoxelsCap = Math.min(MAX_VOXELS_PER_WAVE, Math.max(65536, subVeins * 768));
            final int[] outSlices2 = new int[subVeins * 2];
            final int[] outVoxels = new int[outVoxelsCap];
            final int totalVoxels = kernel.dispatchOreVeinRasterBatch(
                ws.waveMeta4,
                ws.waveRel6,
                ws.waveSeeds2,
                subVeins,
                0,
                0,
                minBuildY,
                maxBuildY,
                outSlices2,
                outVoxels,
                ws.waveTimings
            );
            if (totalVoxels >= 0) {
                this.gpuBatches.increment();
                this.gpuVeins.add(subVeins);
                this.gpuVoxels.add(totalVoxels);
                this.gpuKernelNanos.add(ws.waveTimings[0]);
                this.gpuReadbackNanos.add(ws.waveTimings[1]);
                int vOff = 0;
                for (int i = cursor; i < subEnd; i++) {
                    final OreChunkWaveTask t = batch.get(i);
                    results.set(i, new OreWaveResult(outSlices2, outVoxels, totalVoxels, vOff));
                    vOff += t.veinCount;
                }
            } else if (totalVoxels != -99) {
                LOGGER.warn("GoldLeaf GPU ore wave dispatch returned error {}, falling back to CPU for {} veins", totalVoxels, subVeins);
            }
            cursor = subEnd;
        }
        return results;
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        try {
            this.oreWaveCoalescer.close();
        } catch (final Throwable t) {
            LOGGER.warn("Error closing GoldLeaf ore wave coalescer: {}", t.getMessage());
        }
        this.oreWaveDispatchExecutor.shutdownNow();
        // CompiledKernel lifecycle is owned by OpenClKernelCache#close()
        this.compiledKernel = null;
    }

    private static String sha256Hex(final String text) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    static boolean isSolidNonTicking(final BlockState state) {
        return !state.isAir()
            && !state.isRandomlyTicking()
            && state.getFluidState().isEmpty()
            && !ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.isSpecialCollidingBlock(state);
    }

    /**
     * Compiled O(1) {@link BlockState} + height-bucket replacement lookup for an {@link OreFeature}.
     */
    static final class CompiledOreTargets {
        final boolean allTargetsSolidNonTicking;
        final BlockState stoneState;
        final BlockState deepslateState;
        private final BlockReplacement[] targets;
        private final @Nullable BlockState stoneNeg;
        private final @Nullable BlockState stoneMid;
        private final @Nullable BlockState stoneHigh;
        private final @Nullable BlockState deepNeg;
        private final @Nullable BlockState deepMid;
        private final @Nullable BlockState deepHigh;
        private final ConcurrentHashMap<BlockState, BlockState[]> otherCache = new ConcurrentHashMap<>(16);
        private static final BlockState[] NO_MATCH = new BlockState[3];

        private CompiledOreTargets(
            final boolean allTargetsSolidNonTicking,
            final BlockReplacement[] targets,
            final BlockState stoneState,
            final BlockState deepslateState,
            final @Nullable BlockState stoneNeg,
            final @Nullable BlockState stoneMid,
            final @Nullable BlockState stoneHigh,
            final @Nullable BlockState deepNeg,
            final @Nullable BlockState deepMid,
            final @Nullable BlockState deepHigh
        ) {
            this.allTargetsSolidNonTicking = allTargetsSolidNonTicking;
            this.targets = targets;
            this.stoneState = stoneState;
            this.deepslateState = deepslateState;
            this.stoneNeg = stoneNeg;
            this.stoneMid = stoneMid;
            this.stoneHigh = stoneHigh;
            this.deepNeg = deepNeg;
            this.deepMid = deepMid;
            this.deepHigh = deepHigh;
        }

        static CompiledOreTargets compile(final OreFeature ore) {
            final List<BlockReplacement> list = ore.targetStates();
            final BlockReplacement[] arr = list.toArray(new BlockReplacement[0]);
            boolean allSolid = true;
            for (final BlockReplacement br : arr) {
                if (!isSolidNonTicking(br.state())) {
                    allSolid = false;
                    break;
                }
            }
            final BlockState stone = Blocks.STONE.defaultBlockState();
            final BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState();
            return new CompiledOreTargets(
                allSolid,
                arr,
                stone,
                deepslate,
                evalSlow(arr, stone, SAMPLE_POS_NEG),
                evalSlow(arr, stone, SAMPLE_POS_MID),
                evalSlow(arr, stone, SAMPLE_POS_HIGH),
                evalSlow(arr, deepslate, SAMPLE_POS_NEG),
                evalSlow(arr, deepslate, SAMPLE_POS_MID),
                evalSlow(arr, deepslate, SAMPLE_POS_HIGH)
            );
        }

        private static @Nullable BlockState evalSlow(
            final BlockReplacement[] targets,
            final BlockState state,
            final BlockPos pos
        ) {
            for (int i = 0; i < targets.length; i++) {
                try {
                    if (targets[i].target().test(state, pos, null)) {
                        return targets[i].state();
                    }
                } catch (final NullPointerException ex) {
                    LOGGER.warn("GoldLeaf ore target RuleTest required non-null RandomSource; disabling static cache for {}: {}", state, ex.getMessage());
                    return null;
                }
            }
            return null;
        }

        @Nullable BlockState resolve(
            final BlockState state,
            final int y,
            final int wx,
            final int wz,
            final BlockPos.MutableBlockPos scratchPos
        ) {
            if (state == this.stoneState) {
                return y < 0 ? this.stoneNeg : (y <= 8 ? this.stoneMid : this.stoneHigh);
            }
            if (state == this.deepslateState) {
                return y < 0 ? this.deepNeg : (y <= 8 ? this.deepMid : this.deepHigh);
            }
            BlockState[] buckets = this.otherCache.get(state);
            if (buckets == null) {
                final BlockState b0 = evalSlow(this.targets, state, SAMPLE_POS_NEG);
                final BlockState b1 = evalSlow(this.targets, state, SAMPLE_POS_MID);
                final BlockState b2 = evalSlow(this.targets, state, SAMPLE_POS_HIGH);
                buckets = (b0 == null && b1 == null && b2 == null) ? NO_MATCH : new BlockState[]{b0, b1, b2};
                this.otherCache.putIfAbsent(state, buckets);
            }
            if (buckets == NO_MATCH) {
                return null;
            }
            return y < 0 ? buckets[0] : (y <= 8 ? buckets[1] : buckets[2]);
        }
    }

    private record OreChunkWaveTask(
        GoldLeafOpenClBridge.CompiledKernel kernel,
        int[] relMeta4,
        float[] rel6,
        long[] seeds2,
        int veinCount,
        int minBuildY,
        int maxBuildY
    ) {
    }

    private record OreWaveResult(
        int[] slices2,
        int[] voxels,
        int totalVoxels,
        int veinOffset
    ) {
        static final OreWaveResult FALLBACK = new OreWaveResult(new int[0], new int[0], -1, 0);
    }

    /**
     * Zero-allocation compiled placement chain for vanilla {@code [Count|Rarity, InSquare, HeightRange, BiomeFilter]}.
     */
    private static final class CompiledOrePlacement {
        static final CompiledOrePlacement GENERIC = new CompiledOrePlacement(null, 0.0F, null);

        final @Nullable IntProvider countProvider;
        final float rarityInvChance;
        final @Nullable HeightProvider heightProvider;

        private CompiledOrePlacement(
            final @Nullable IntProvider countProvider,
            final float rarityInvChance,
            final @Nullable HeightProvider heightProvider
        ) {
            this.countProvider = countProvider;
            this.rarityInvChance = rarityInvChance;
            this.heightProvider = heightProvider;
        }

        static CompiledOrePlacement compile(final PlacedFeature placed) {
            final List<PlacementModifier> mods = placed.placement();
            if (mods.size() != 4) {
                return GENERIC;
            }
            if (!(mods.get(1) instanceof InSquarePlacement)
                || !(mods.get(2) instanceof HeightRangePlacement hrp)
                || !(mods.get(3) instanceof BiomeFilter)) {
                return GENERIC;
            }
            final PlacementModifier first = mods.get(0);
            if (first instanceof CountPlacement cp) {
                return new CompiledOrePlacement(cp.count(), 0.0F, hrp.height());
            }
            if (first instanceof RarityFilter rf) {
                return new CompiledOrePlacement(null, 1.0F / rf.chance(), hrp.height());
            }
            return GENERIC;
        }
    }

    private static final class WaveDispatchScratch {
        int[] waveMeta4 = new int[MAX_VEINS_PER_WAVE * 4];
        float[] waveRel6 = new float[MAX_VEINS_PER_WAVE * 6];
        long[] waveSeeds2 = new long[MAX_VEINS_PER_WAVE * 2];
        final long[] waveTimings = new long[2];
    }

    private static final class Scratch {
        final ChunkAccess[] regionChunks = new ChunkAccess[9];
        final ArrayList<BlockPos> positions = new ArrayList<>(64);
        final IntArrayList modifierIndices = new IntArrayList(64);
        final ArrayList<BlockPos> modifiedPositions = new ArrayList<>(64);
        final BlockPos.MutableBlockPos orePos = new BlockPos.MutableBlockPos();
        final double[] fallbackSpheres = new double[64 * 4];
        final BitSet fallbackTested = new BitSet(4096);
        int veinCount = 0;
        int[] veinMeta4 = new int[MAX_VEINS_PER_BATCH * 4];
        float[] veinRel6 = new float[MAX_VEINS_PER_BATCH * 6];
        long[] veinSeeds2 = new long[MAX_VEINS_PER_BATCH * 2];
        CompiledOreTargets[] veinTargets = new CompiledOreTargets[MAX_VEINS_PER_BATCH];
        OreFeature[] veinFeatures = new OreFeature[MAX_VEINS_PER_BATCH];
        boolean[] veinRequireBuried = new boolean[MAX_VEINS_PER_BATCH];

        void growVeins(final int newCap) {
            this.veinMeta4 = java.util.Arrays.copyOf(this.veinMeta4, newCap * 4);
            this.veinRel6 = java.util.Arrays.copyOf(this.veinRel6, newCap * 6);
            this.veinSeeds2 = java.util.Arrays.copyOf(this.veinSeeds2, newCap * 2);
            this.veinTargets = java.util.Arrays.copyOf(this.veinTargets, newCap);
            this.veinFeatures = java.util.Arrays.copyOf(this.veinFeatures, newCap);
            this.veinRequireBuried = java.util.Arrays.copyOf(this.veinRequireBuried, newCap);
        }
    }
}
