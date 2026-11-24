package dev.leafcraft.goldleaf.worldgen;

import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Compiles opt-in in-VRAM GPU Pass 4 features ({@code worldgen.gpu-in-vram-features}) into an
 * OpenCL 1.2 kernel ({@code apply_gpu_features_u8}, zero FP64) that runs directly in-place on
 * {@code slot->compact_words_buf} immediately after Pass 3B ({@code apply_carver_aquifer_u8}):
 * <ol>
 *   <li><b>3D Hilbert-Curve Underground Ores ({@code UNDERGROUND_ORES}):</b> Places Overworld
 *       ores and stone pockets across all 256 threads in parallel using {@link HilbertOreVeinWalker}'s
 *       exact order-3 ({@code 8x8x8}) 3D Hilbert curve and deterministic {@code Xoroshiro128++}.</li>
 *   <li><b>{@code FreezeTopLayer} ({@code TOP_LAYER_MODIFICATION}):</b> Converts exposed water
 *       to {@code ICE} and places {@code SNOW} layers on cold biomes in parallel per column.</li>
 *   <li><b>Intra-Chunk Ground-Cover Vegetation ({@code VEGETAL_DECORATION}):</b> Places
 *       {@code SHORT_GRASS}, {@code FERN}, {@code DANDELION}, {@code POPPY}, {@code DEAD_BUSH},
 *       and underwater {@code SEAGRASS} using deterministic per-column PRNG.</li>
 *   <li><b>Final 4-bit Section Packing &amp; GPU Heightmaps:</b> Packs non-uniform sections
 *       in-place and emits final {@code WORLD_SURFACE} and {@code OCEAN_FLOOR} heightmaps
 *       reflecting all Pass 4 feature placements.</li>
 * </ol>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017, leafgen-ADR-002
 * @trace S-002, S-004
 */
public final class GpuFeaturePassCompiler {

    public static final String KERNEL_NAME = "apply_gpu_features_u8";

    private static final BlockState[] FEATURE_BLOCKS = {
        Blocks.COAL_ORE.defaultBlockState(),
        Blocks.DEEPSLATE_COAL_ORE.defaultBlockState(),
        Blocks.IRON_ORE.defaultBlockState(),
        Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(),
        Blocks.COPPER_ORE.defaultBlockState(),
        Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState(),
        Blocks.GOLD_ORE.defaultBlockState(),
        Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState(),
        Blocks.REDSTONE_ORE.defaultBlockState(),
        Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState(),
        Blocks.LAPIS_ORE.defaultBlockState(),
        Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState(),
        Blocks.DIAMOND_ORE.defaultBlockState(),
        Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(),
        Blocks.GRANITE.defaultBlockState(),
        Blocks.DIORITE.defaultBlockState(),
        Blocks.ANDESITE.defaultBlockState(),
        Blocks.TUFF.defaultBlockState(),
        Blocks.DIRT.defaultBlockState(),
        Blocks.GRAVEL.defaultBlockState(),
        Blocks.SNOW.defaultBlockState(),
        Blocks.ICE.defaultBlockState(),
        Blocks.SHORT_GRASS.defaultBlockState(),
        Blocks.FERN.defaultBlockState(),
        Blocks.DANDELION.defaultBlockState(),
        Blocks.POPPY.defaultBlockState(),
        Blocks.DEAD_BUSH.defaultBlockState(),
        Blocks.SEAGRASS.defaultBlockState()
    };

    private static final ResourceKey<Biome>[] FEATURE_BIOMES = new ResourceKey[]{
        Biomes.SNOWY_PLAINS, Biomes.ICE_SPIKES, Biomes.SNOWY_TAIGA, Biomes.SNOWY_BEACH,
        Biomes.GROVE, Biomes.SNOWY_SLOPES, Biomes.FROZEN_PEAKS, Biomes.JAGGED_PEAKS,
        Biomes.FROZEN_RIVER, Biomes.FROZEN_OCEAN, Biomes.DEEP_FROZEN_OCEAN,
        Biomes.DESERT, Biomes.BADLANDS, Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS,
        Biomes.TAIGA, Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA,
        Biomes.JUNGLE, Biomes.SPARSE_JUNGLE, Biomes.BAMBOO_JUNGLE
    };

    private GpuFeaturePassCompiler() {
    }

    public static BlockState[] featureBlocks() {
        return FEATURE_BLOCKS;
    }

    public static ResourceKey<Biome>[] featureBiomes() {
        return FEATURE_BIOMES;
    }

    private record OreVeinSpec(
        int count,
        int size,
        int minY,
        int maxY,
        boolean trapezoid,
        int stoneCode,
        int deepslateCode,
        int airDiscardPct
    ) {
    }

    public static OpenClBackendEmitter.@Nullable DensityVolumeProgram compile(
        final BlockState[] blockPalette,
        final Map<ResourceKey<Biome>, Integer> biomeKeyToId,
        final long worldSeed,
        final String moduleBaseName
    ) {
        Objects.requireNonNull(blockPalette, "blockPalette");
        Objects.requireNonNull(biomeKeyToId, "biomeKeyToId");
        Objects.requireNonNull(moduleBaseName, "moduleBaseName");

        if (blockPalette.length > 64) {
            return null;
        }
        final int codeStone = findBlock(blockPalette, Blocks.STONE.defaultBlockState());
        final int codeDeepslate = findBlock(blockPalette, Blocks.DEEPSLATE.defaultBlockState());
        final int codeGrassBlock = findBlock(blockPalette, Blocks.GRASS_BLOCK.defaultBlockState());
        final int codeSnow = findBlock(blockPalette, Blocks.SNOW.defaultBlockState());
        final int codeIce = findBlock(blockPalette, Blocks.ICE.defaultBlockState());
        final int codeShortGrass = findBlock(blockPalette, Blocks.SHORT_GRASS.defaultBlockState());
        final int codeFern = findBlock(blockPalette, Blocks.FERN.defaultBlockState());
        final int codeDandelion = findBlock(blockPalette, Blocks.DANDELION.defaultBlockState());
        final int codePoppy = findBlock(blockPalette, Blocks.POPPY.defaultBlockState());
        final int codeDeadBush = findBlock(blockPalette, Blocks.DEAD_BUSH.defaultBlockState());
        final int codeSeagrass = findBlock(blockPalette, Blocks.SEAGRASS.defaultBlockState());
        final int codeSand = findBlock(blockPalette, Blocks.SAND.defaultBlockState());
        final int codeRedSand = findBlock(blockPalette, Blocks.RED_SAND.defaultBlockState());
        final int codeGravel = findBlock(blockPalette, Blocks.GRAVEL.defaultBlockState());
        final int codeDirt = findBlock(blockPalette, Blocks.DIRT.defaultBlockState());
        final int codeClay = findBlock(blockPalette, Blocks.CLAY.defaultBlockState());
        if (codeStone < 0 || codeDeepslate < 0 || codeSnow < 0 || codeIce < 0 || codeShortGrass < 0) {
            return null;
        }

        final List<OreVeinSpec> ores = buildOverworldOreSpecs(blockPalette, codeStone, codeDeepslate);
        if (ores.isEmpty()) {
            return null;
        }
        int totalVeinAttempts = 0;
        for (final OreVeinSpec spec : ores) {
            totalVeinAttempts += spec.count;
        }

        // Build per-palette classification masks (exact match with CompactVanillaSurfaceWritebackApplier).
        long tickingFluidMask = 0L;
        long surfOpaqueMask = 0L;
        long oceanOpaqueMask = 0L;
        long snowSupportMask = 0L;
        final var surfPred = net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
        final var oceanPred = net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
        for (int i = 0; i < blockPalette.length; i++) {
            final BlockState st = blockPalette[i];
            if (st.isRandomlyTicking() || !st.getFluidState().isEmpty()) {
                tickingFluidMask |= (1L << i);
            }
            if (surfPred.test(st)) {
                surfOpaqueMask |= (1L << i);
            }
            if (oceanPred.test(st)) {
                oceanOpaqueMask |= (1L << i);
                if (!st.is(Blocks.ICE) && !st.is(Blocks.PACKED_ICE) && !st.is(Blocks.BARRIER) && !st.is(Blocks.BEDROCK)) {
                    snowSupportMask |= (1L << i);
                }
            }
        }

        final long coldBiomeMask = buildBiomeMask(biomeKeyToId,
            Biomes.SNOWY_PLAINS, Biomes.ICE_SPIKES, Biomes.SNOWY_TAIGA, Biomes.SNOWY_BEACH,
            Biomes.GROVE, Biomes.SNOWY_SLOPES, Biomes.FROZEN_PEAKS, Biomes.JAGGED_PEAKS,
            Biomes.FROZEN_RIVER, Biomes.FROZEN_OCEAN, Biomes.DEEP_FROZEN_OCEAN
        );
        final long desertBiomeMask = buildBiomeMask(biomeKeyToId,
            Biomes.DESERT, Biomes.BADLANDS, Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS
        );
        final long fernBiomeMask = buildBiomeMask(biomeKeyToId,
            Biomes.TAIGA, Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA,
            Biomes.JUNGLE, Biomes.SPARSE_JUNGLE, Biomes.BAMBOO_JUNGLE
        );

        final StringBuilder sb = new StringBuilder(24576);
        sb.append("// GoldLeaf opt-in OpenCL 1.2 Pass 4 in-VRAM features (zero FP64)\n");
        sb.append("#pragma OPENCL FP_CONTRACT OFF\n\n");

        // Bake order-3 3D Hilbert curve LUT from HilbertOreVeinWalker
        sb.append("__constant ushort FP_HILBERT_3D[512] = {\n");
        for (int i = 0; i < 512; i++) {
            if ((i & 15) == 0) sb.append("    ");
            sb.append(Short.toUnsignedInt(HilbertOreVeinWalker.packedPoint(i)));
            if (i < 511) sb.append(',');
            if ((i & 15) == 15) sb.append('\n');
        }
        sb.append("};\n\n");

        // Bake flattened per-vein spec table: [specIdx] for each attempt 0..totalVeinAttempts-1
        sb.append("__constant int FP_ORE_SIZE[").append(ores.size()).append("] = {");
        for (int i = 0; i < ores.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ores.get(i).size);
        }
        sb.append("};\n");
        sb.append("__constant int FP_ORE_MIN_Y[").append(ores.size()).append("] = {");
        for (int i = 0; i < ores.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ores.get(i).minY);
        }
        sb.append("};\n");
        sb.append("__constant int FP_ORE_MAX_Y[").append(ores.size()).append("] = {");
        for (int i = 0; i < ores.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ores.get(i).maxY);
        }
        sb.append("};\n");
        sb.append("__constant uchar FP_ORE_TRAP[").append(ores.size()).append("] = {");
        for (int i = 0; i < ores.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ores.get(i).trapezoid ? 1 : 0);
        }
        sb.append("};\n");
        sb.append("__constant uchar FP_ORE_STONE[").append(ores.size()).append("] = {");
        for (int i = 0; i < ores.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ores.get(i).stoneCode);
        }
        sb.append("};\n");
        sb.append("__constant uchar FP_ORE_DEEP[").append(ores.size()).append("] = {");
        for (int i = 0; i < ores.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ores.get(i).deepslateCode);
        }
        sb.append("};\n");
        sb.append("__constant uchar FP_ORE_DISCARD[").append(ores.size()).append("] = {");
        for (int i = 0; i < ores.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ores.get(i).airDiscardPct);
        }
        sb.append("};\n");

        sb.append("__constant uchar FP_VEIN_SPEC[").append(totalVeinAttempts).append("] = {\n");
        int flatIdx = 0;
        for (int si = 0; si < ores.size(); si++) {
            for (int c = 0; c < ores.get(si).count; c++) {
                if ((flatIdx & 31) == 0) sb.append("    ");
                sb.append(si);
                flatIdx++;
                if (flatIdx < totalVeinAttempts) sb.append(',');
                if ((flatIdx & 31) == 0) sb.append('\n');
            }
        }
        if ((flatIdx & 31) != 0) sb.append('\n');
        sb.append("};\n\n");

        emitHelpers(sb);
        emitKernel(
            sb,
            worldSeed,
            totalVeinAttempts,
            codeStone,
            codeDeepslate,
            codeGrassBlock,
            codeSnow,
            codeIce,
            codeShortGrass,
            codeFern,
            codeDandelion,
            codePoppy,
            codeDeadBush,
            codeSeagrass,
            codeSand,
            codeRedSand,
            codeGravel,
            codeDirt,
            codeClay,
            tickingFluidMask,
            surfOpaqueMask,
            oceanOpaqueMask,
            snowSupportMask,
            coldBiomeMask,
            desertBiomeMask,
            fernBiomeMask
        );

        final String src = sb.toString();
        final int[] emptySpill = new int[0];
        return new OpenClBackendEmitter.DensityVolumeProgram(
            KERNEL_NAME,
            src,
            sha256(src),
            emptySpill,
            0,
            0,
            0
        );
    }

    private static List<OreVeinSpec> buildOverworldOreSpecs(
        final BlockState[] palette,
        final int codeStone,
        final int codeDeepslate
    ) {
        final List<OreVeinSpec> list = new ArrayList<>(24);
        addOre(list, palette, 7, 33, 0, 160, false, Blocks.DIRT.defaultBlockState(), Blocks.DIRT.defaultBlockState(), 0);
        addOre(list, palette, 14, 33, -64, 320, false, Blocks.GRAVEL.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), 0);
        addOre(list, palette, 2, 64, 0, 60, false, Blocks.GRANITE.defaultBlockState(), null, 0);
        addOre(list, palette, 1, 64, 64, 128, false, Blocks.GRANITE.defaultBlockState(), null, 0);
        addOre(list, palette, 2, 64, 0, 60, false, Blocks.DIORITE.defaultBlockState(), null, 0);
        addOre(list, palette, 1, 64, 64, 128, false, Blocks.DIORITE.defaultBlockState(), null, 0);
        addOre(list, palette, 2, 64, 0, 60, false, Blocks.ANDESITE.defaultBlockState(), null, 0);
        addOre(list, palette, 1, 64, 64, 128, false, Blocks.ANDESITE.defaultBlockState(), null, 0);
        addOre(list, palette, 2, 64, -64, 0, false, Blocks.TUFF.defaultBlockState(), Blocks.TUFF.defaultBlockState(), 0);

        addOre(list, palette, 30, 17, 136, 320, false, Blocks.COAL_ORE.defaultBlockState(), Blocks.DEEPSLATE_COAL_ORE.defaultBlockState(), 0);
        addOre(list, palette, 20, 17, 0, 192, true, Blocks.COAL_ORE.defaultBlockState(), Blocks.DEEPSLATE_COAL_ORE.defaultBlockState(), 50);

        addOre(list, palette, 90, 9, 80, 384, true, Blocks.IRON_ORE.defaultBlockState(), Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), 0);
        addOre(list, palette, 10, 9, -24, 56, true, Blocks.IRON_ORE.defaultBlockState(), Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), 0);
        addOre(list, palette, 10, 4, -64, 72, false, Blocks.IRON_ORE.defaultBlockState(), Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), 0);

        addOre(list, palette, 16, 10, -16, 112, true, Blocks.COPPER_ORE.defaultBlockState(), Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState(), 0);

        addOre(list, palette, 4, 9, -64, 32, true, Blocks.GOLD_ORE.defaultBlockState(), Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState(), 50);
        addOre(list, palette, 1, 9, -64, -48, false, Blocks.GOLD_ORE.defaultBlockState(), Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState(), 50);

        addOre(list, palette, 4, 8, -64, 15, false, Blocks.REDSTONE_ORE.defaultBlockState(), Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState(), 0);
        addOre(list, palette, 8, 8, -64, 15, true, Blocks.REDSTONE_ORE.defaultBlockState(), Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState(), 0);

        addOre(list, palette, 2, 7, -32, 32, true, Blocks.LAPIS_ORE.defaultBlockState(), Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState(), 0);
        addOre(list, palette, 4, 7, -64, 64, false, Blocks.LAPIS_ORE.defaultBlockState(), Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState(), 100);

        addOre(list, palette, 7, 4, -64, 16, true, Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 50);
        addOre(list, palette, 2, 8, -64, 16, true, Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 50);
        addOre(list, palette, 1, 12, -64, 16, true, Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 70);
        addOre(list, palette, 4, 8, -64, 16, true, Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 100);
        return list;
    }

    private static void addOre(
        final List<OreVeinSpec> list,
        final BlockState[] palette,
        final int count,
        final int size,
        final int minY,
        final int maxY,
        final boolean trap,
        final @Nullable BlockState stoneOre,
        final @Nullable BlockState deepslateOre,
        final int airDiscardPct
    ) {
        final int sc = stoneOre != null ? findBlock(palette, stoneOre) : 255;
        final int dc = deepslateOre != null ? findBlock(palette, deepslateOre) : 255;
        if (sc == 255 && dc == 255) {
            return;
        }
        list.add(new OreVeinSpec(count, size, minY, maxY, trap, sc >= 0 ? sc : 255, dc >= 0 ? dc : 255, airDiscardPct));
    }

    private static int findBlock(final BlockState[] palette, final BlockState target) {
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == target || palette[i].equals(target)) {
                return i;
            }
        }
        return -1;
    }

    @SafeVarargs
    static long buildBiomeMask(
        final Map<ResourceKey<Biome>, Integer> biomeKeyToId,
        final ResourceKey<Biome>... keys
    ) {
        long mask = 0L;
        for (final ResourceKey<Biome> key : keys) {
            final Integer id = biomeKeyToId.get(key);
            if (id == null || id < 0 || id >= 64) {
                throw new IllegalStateException(
                    "Unmapped or out-of-range biome in GpuFeaturePassCompiler mask (S-004): " + key + " -> " + id
                );
            }
            mask |= (1L << id);
        }
        return mask;
    }

    private static void emitHelpers(final StringBuilder sb) {
        sb.append("static inline ulong fp_splitmix64(ulong x) {\n");
        sb.append("    x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9UL;\n");
        sb.append("    x = (x ^ (x >> 27)) * 0x94D049BB133111EBUL;\n");
        sb.append("    return x ^ (x >> 31);\n");
        sb.append("}\n\n");

        sb.append("static inline ulong fp_rotl64(const ulong x, const int k) {\n");
        sb.append("    return (x << k) | (x >> (64 - k));\n");
        sb.append("}\n\n");

        sb.append("static inline uint fp_xoro_next_u32(ulong* s0, ulong* s1) {\n");
        sb.append("    const ulong a = *s0;\n");
        sb.append("    ulong b = *s1;\n");
        sb.append("    const ulong res = fp_rotl64(a + b, 17) + a;\n");
        sb.append("    b ^= a;\n");
        sb.append("    *s0 = fp_rotl64(a, 49) ^ b ^ (b << 21);\n");
        sb.append("    *s1 = fp_rotl64(b, 28);\n");
        sb.append("    return (uint)(res >> 32);\n");
        sb.append("}\n\n");

        sb.append("static inline int fp_xoro_next_int(ulong* s0, ulong* s1, const int bound) {\n");
        sb.append("    if (bound <= 1) return 0;\n");
        sb.append("    return (int)(fp_xoro_next_u32(s0, s1) % (uint)bound);\n");
        sb.append("}\n\n");

        sb.append("static inline int fp_transform_coord(const int rx, const int ry, const int rz, const int perm, const int axis, const int flip) {\n");
        sb.append("    int v;\n");
        sb.append("    if (perm == 0) v = (axis == 0) ? rx : ((axis == 1) ? ry : rz);\n");
        sb.append("    else if (perm == 1) v = (axis == 0) ? rx : ((axis == 1) ? rz : ry);\n");
        sb.append("    else if (perm == 2) v = (axis == 0) ? ry : ((axis == 1) ? rx : rz);\n");
        sb.append("    else if (perm == 3) v = (axis == 0) ? ry : ((axis == 1) ? rz : rx);\n");
        sb.append("    else if (perm == 4) v = (axis == 0) ? rz : ((axis == 1) ? rx : ry);\n");
        sb.append("    else v = (axis == 0) ? rz : ((axis == 1) ? ry : rx);\n");
        sb.append("    return v ^ flip;\n");
        sb.append("}\n\n");

        // Exact Mojang BiomeManager zoom lookup in 6x6 biome halo (matching Pass 2).
        sb.append("static inline long fp_lcg_next(const long seed, const long salt) {\n");
        sb.append("    return seed * (seed * 6364136223846793005L + 1442695040888963407L) + salt;\n");
        sb.append("}\n\n");

        sb.append("static inline float fp_fiddle(const long rval) {\n");
        sb.append("    const int m = (int)((rval >> 24) & 1023L);\n");
        sb.append("    return ((float)m * (1.0f / 1024.0f) - 0.5f) * 0.9f;\n");
        sb.append("}\n\n");

        sb.append("static inline uchar fp_lookup_biome(\n");
        sb.append("    __global const uchar* halo,\n");
        sb.append("    const int bx, const int by, const int bz,\n");
        sb.append("    const int orig_x, const int orig_z, const int min_block_y,\n");
        sb.append("    const int section_count, const long zoom_seed\n");
        sb.append(") {\n");
        sb.append("    const int ix = bx - 2;\n");
        sb.append("    const int iy = by - 2;\n");
        sb.append("    const int iz = bz - 2;\n");
        sb.append("    const int qx0 = ix >> 2;\n");
        sb.append("    const int qy0 = iy >> 2;\n");
        sb.append("    const int qz0 = iz >> 2;\n");
        sb.append("    const float fx = (float)(ix & 3) * 0.25f;\n");
        sb.append("    const float fy = (float)(iy & 3) * 0.25f;\n");
        sb.append("    const float fz = (float)(iz & 3) * 0.25f;\n");
        sb.append("    int best_i = 0;\n");
        sb.append("    float best_d = 1e30f;\n");
        sb.append("    for (int i = 0; i < 8; i++) {\n");
        sb.append("        const int ox = (i & 4) == 0 ? 0 : 1;\n");
        sb.append("        const int oy = (i & 2) == 0 ? 0 : 1;\n");
        sb.append("        const int oz = (i & 1) == 0 ? 0 : 1;\n");
        sb.append("        const int cx = qx0 + ox;\n");
        sb.append("        const int cy = qy0 + oy;\n");
        sb.append("        const int cz = qz0 + oz;\n");
        sb.append("        long r = fp_lcg_next(zoom_seed, (long)cx);\n");
        sb.append("        r = fp_lcg_next(r, (long)cy);\n");
        sb.append("        r = fp_lcg_next(r, (long)cz);\n");
        sb.append("        r = fp_lcg_next(r, (long)cx);\n");
        sb.append("        r = fp_lcg_next(r, (long)cy);\n");
        sb.append("        r = fp_lcg_next(r, (long)cz);\n");
        sb.append("        const float dx = (fx - (float)ox) + fp_fiddle(r);\n");
        sb.append("        r = fp_lcg_next(r, zoom_seed);\n");
        sb.append("        const float dy = (fy - (float)oy) + fp_fiddle(r);\n");
        sb.append("        r = fp_lcg_next(r, zoom_seed);\n");
        sb.append("        const float dz = (fz - (float)oz) + fp_fiddle(r);\n");
        sb.append("        const float d = dx * dx + dy * dy + dz * dz;\n");
        sb.append("        if (d < best_d) { best_d = d; best_i = i; }\n");
        sb.append("    }\n");
        sb.append("    const int bqx = clamp((qx0 + ((best_i & 4) == 0 ? 0 : 1)) - ((orig_x >> 2) - 1), 0, 5);\n");
        sb.append("    const int bqz = clamp((qz0 + ((best_i & 1) == 0 ? 0 : 1)) - ((orig_z >> 2) - 1), 0, 5);\n");
        sb.append("    const int qy_count = section_count * 4;\n");
        sb.append("    const int bqy = clamp((qy0 + ((best_i & 2) == 0 ? 0 : 1)) - (min_block_y >> 2), 0, qy_count - 1);\n");
        sb.append("    return halo[(bqy * 6 + bqz) * 6 + bqx];\n");
        sb.append("}\n\n");
    }

    private static void emitKernel(
        final StringBuilder sb,
        final long worldSeed,
        final int totalVeinAttempts,
        final int codeStone,
        final int codeDeepslate,
        final int codeGrassBlock,
        final int codeSnow,
        final int codeIce,
        final int codeShortGrass,
        final int codeFern,
        final int codeDandelion,
        final int codePoppy,
        final int codeDeadBush,
        final int codeSeagrass,
        final int codeSand,
        final int codeRedSand,
        final int codeGravel,
        final int codeDirt,
        final int codeClay,
        final long tickingFluidMask,
        final long surfOpaqueMask,
        final long oceanOpaqueMask,
        final long snowSupportMask,
        final long coldBiomeMask,
        final long desertBiomeMask,
        final long fernBiomeMask
    ) {
        sb.append("__kernel void ").append(KERNEL_NAME).append("(\n");
        sb.append("    __global uchar* out_voxels,\n");
        sb.append("    __global ushort* out_meta,\n");
        sb.append("    __global const int4* batch_origins,\n");
        sb.append("    __global const uchar* biomes_halo,\n");
        sb.append("    __global ushort* out_heightmaps,\n");
        sb.append("    const int batch_count,\n");
        sb.append("    const int section_count,\n");
        sb.append("    const int min_block_y,\n");
        sb.append("    const int sea_level,\n");
        sb.append("    const long biome_zoom_seed\n");
        sb.append(") {\n");
        sb.append("    const int wg_id = (int)get_group_id(0);\n");
        sb.append("    const int batch_idx = wg_id >> 2;\n");
        sb.append("    const int strip = wg_id & 3;\n");
        sb.append("    const int tid = (int)get_local_id(0);\n");
        sb.append("    if (batch_idx >= batch_count) return;\n");
        sb.append("    const int lx = tid & 15;\n");
        sb.append("    const int lz = (strip << 2) + (tid >> 4);\n");
        sb.append("    const int col_idx = (lz << 4) | lx;\n");
        sb.append("    const int4 orig = batch_origins[batch_idx];\n");
        sb.append("    const int chunk_base_sec = batch_idx * section_count;\n");
        sb.append("    const int total_height = section_count * 16;\n");
        sb.append("    const int max_block_y = min_block_y + total_height - 1;\n");
        sb.append("    const ulong chunk_seed = fp_splitmix64(((ulong)(long)orig.x * 0x9E3779B97F4A7C15UL) ^ ((ulong)(long)orig.z * 0xBF58476D1CE4E5B9UL) ^ ").append(toUnsignedHex(worldSeed)).append(");\n\n");

        // Stage 4A: 3D Hilbert-Curve Underground Ores across 4 WGs/chunk x 64 threads/WG
        sb.append("    for (int vi = (strip << 6) + tid; vi < ").append(totalVeinAttempts).append("; vi += 256) {\n");
        sb.append("        const int sp = (int)FP_VEIN_SPEC[vi];\n");
        sb.append("        ulong s0 = fp_splitmix64(chunk_seed ^ ((ulong)(vi + 1) * 0x94D049BB133111EBUL));\n");
        sb.append("        ulong s1 = fp_splitmix64(s0 + 0x9E3779B97F4A7C15UL);\n");
        sb.append("        const int ox = fp_xoro_next_int(&s0, &s1, 16);\n");
        sb.append("        const int oz = fp_xoro_next_int(&s0, &s1, 16);\n");
        sb.append("        const int y_lo = FP_ORE_MIN_Y[sp];\n");
        sb.append("        const int y_hi = FP_ORE_MAX_Y[sp];\n");
        sb.append("        const int y_span = max(1, y_hi - y_lo + 1);\n");
        sb.append("        int oy;\n");
        sb.append("        if (FP_ORE_TRAP[sp] != 0) {\n");
        sb.append("            const int half_s = max(1, y_span >> 1);\n");
        sb.append("            oy = y_lo + fp_xoro_next_int(&s0, &s1, half_s) + fp_xoro_next_int(&s0, &s1, y_span - half_s);\n");
        sb.append("        } else {\n");
        sb.append("            oy = y_lo + fp_xoro_next_int(&s0, &s1, y_span);\n");
        sb.append("        }\n");
        sb.append("        if (oy < min_block_y + 1 || oy > max_block_y - 1) continue;\n");
        sb.append("        const int sz = FP_ORE_SIZE[sp];\n");
        sb.append("        const int base_cnt = (sz <= 16) ? sz : (sz + ((sz - 16) * (sz - 16)) / 18);\n");
        sb.append("        const int spread = max(1, base_cnt >> 3);\n");
        sb.append("        const int cnt = clamp(base_cnt - (spread >> 1) + fp_xoro_next_int(&s0, &s1, spread + 1), 1, 512);\n");
        sb.append("        const int orient = fp_xoro_next_int(&s0, &s1, 48);\n");
        sb.append("        const int perm = orient >> 3;\n");
        sb.append("        const int fx = (orient & 1) != 0 ? 7 : 0;\n");
        sb.append("        const int fy = (orient & 2) != 0 ? 7 : 0;\n");
        sb.append("        const int fz = (orient & 4) != 0 ? 7 : 0;\n");
        sb.append("        const int start_i = (cnt < 512) ? fp_xoro_next_int(&s0, &s1, 513 - cnt) : 0;\n");
        sb.append("        const uint p0 = (uint)FP_HILBERT_3D[start_i];\n");
        sb.append("        const int bx0 = ox - fp_transform_coord((int)(p0 & 7u), (int)((p0 >> 3) & 7u), (int)((p0 >> 6) & 7u), perm, 0, fx);\n");
        sb.append("        const int by0 = oy - fp_transform_coord((int)(p0 & 7u), (int)((p0 >> 3) & 7u), (int)((p0 >> 6) & 7u), perm, 1, fy);\n");
        sb.append("        const int bz0 = oz - fp_transform_coord((int)(p0 & 7u), (int)((p0 >> 3) & 7u), (int)((p0 >> 6) & 7u), perm, 2, fz);\n");
        sb.append("        const uchar sc = FP_ORE_STONE[sp];\n");
        sb.append("        const uchar dc = FP_ORE_DEEP[sp];\n");
        sb.append("        const int disc_pct = (int)FP_ORE_DISCARD[sp];\n");
        sb.append("        for (int i = start_i; i < start_i + cnt; i++) {\n");
        sb.append("            const uint pt = (uint)FP_HILBERT_3D[i];\n");
        sb.append("            const int vx = bx0 + fp_transform_coord((int)(pt & 7u), (int)((pt >> 3) & 7u), (int)((pt >> 6) & 7u), perm, 0, fx);\n");
        sb.append("            const int vy = by0 + fp_transform_coord((int)(pt & 7u), (int)((pt >> 3) & 7u), (int)((pt >> 6) & 7u), perm, 1, fy);\n");
        sb.append("            const int vz = bz0 + fp_transform_coord((int)(pt & 7u), (int)((pt >> 3) & 7u), (int)((pt >> 6) & 7u), perm, 2, fz);\n");
        sb.append("            if ((vx | vz) < 0 || vx > 15 || vz > 15) continue;\n");
        sb.append("            const int ry = vy - min_block_y;\n");
        sb.append("            if (ry <= 0 || ry >= total_height - 1) continue;\n");
        sb.append("            const int v_off = (chunk_base_sec + (ry >> 4)) * 4096 + (((ry & 15) << 8) | (vz << 4) | vx);\n");
        sb.append("            const uchar cur = out_voxels[v_off];\n");
        sb.append("            uchar rep = (uchar)255;\n");
        sb.append("            if (cur == ").append(codeStone).append(" && sc != 255) rep = sc;\n");
        sb.append("            else if (cur == ").append(codeDeepslate).append(" && dc != 255) rep = dc;\n");
        sb.append("            if (rep == 255) continue;\n");
        sb.append("            if (disc_pct > 0) {\n");
        sb.append("                int adj_air = 0;\n");
        sb.append("                if (vx > 0 && out_voxels[v_off - 1] == 0) adj_air = 1;\n");
        sb.append("                else if (vx < 15 && out_voxels[v_off + 1] == 0) adj_air = 1;\n");
        sb.append("                else if (vz > 0 && out_voxels[v_off - 16] == 0) adj_air = 1;\n");
        sb.append("                else if (vz < 15 && out_voxels[v_off + 16] == 0) adj_air = 1;\n");
        sb.append("                else if (out_voxels[(chunk_base_sec + ((ry + 1) >> 4)) * 4096 + ((((ry + 1) & 15) << 8) | (vz << 4) | vx)] == 0) adj_air = 1;\n");
        sb.append("                else if (out_voxels[(chunk_base_sec + ((ry - 1) >> 4)) * 4096 + ((((ry - 1) & 15) << 8) | (vz << 4) | vx)] == 0) adj_air = 1;\n");
        sb.append("                if (adj_air != 0 && (disc_pct >= 100 || fp_xoro_next_int(&s0, &s1, 100) < disc_pct)) continue;\n");
        sb.append("            }\n");
        sb.append("            out_voxels[v_off] = rep;\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    barrier(CLK_GLOBAL_MEM_FENCE);\n\n");

        // Stage 4B: Intra-Chunk Ground-Cover Vegetation (1 thread per column; SnowAndFreezeFeature runs on CPU after trees)
        sb.append("    int top_ry = -1;\n");
        sb.append("    uchar top_code = (uchar)0;\n");
        sb.append("    for (int ry = total_height - 1; ry >= 0; ry--) {\n");
        sb.append("        const int v_off = (chunk_base_sec + (ry >> 4)) * 4096 + (((ry & 15) << 8) | (lz << 4) | lx);\n");
        sb.append("        const uchar c = out_voxels[v_off];\n");
        sb.append("        if (c != 0) { top_ry = ry; top_code = c; break; }\n");
        sb.append("    }\n");
        sb.append("    if (top_ry >= 0) {\n");
        sb.append("        const int top_y = min_block_y + top_ry;\n");
        sb.append("        __global const uchar* chunk_halo = biomes_halo + batch_idx * (36 * section_count * 4);\n");
        sb.append("        const uchar bid = fp_lookup_biome(chunk_halo, orig.x + lx, top_y, orig.z + lz, orig.x, orig.z, min_block_y, section_count, biome_zoom_seed);\n");
        sb.append("        const ulong bbit = (bid < 64) ? (1UL << (uint)bid) : 0UL;\n");
        sb.append("        const int is_cold = ((").append(toUnsignedHex(coldBiomeMask)).append(" & bbit) != 0UL) ? 1 : 0;\n");
        sb.append("        if (is_cold == 0) {\n");
        sb.append("            ulong cs0 = fp_splitmix64(chunk_seed ^ ((ulong)(col_idx + 1024) * 0xBF58476D1CE4E5B9UL));\n");
        sb.append("            ulong cs1 = fp_splitmix64(cs0 + 0x9E3779B97F4A7C15UL);\n");
        sb.append("            const uint rv = fp_xoro_next_u32(&cs0, &cs1);\n");
        if (codeGrassBlock >= 0) {
            sb.append("            if (top_code == ").append(codeGrassBlock).append(" && top_ry + 1 < total_height) {\n");
            sb.append("                const uint bucket = rv % 100u;\n");
            sb.append("                uchar plant = (uchar)0;\n");
            sb.append("                if (bucket < 14u) {\n");
            if (codeFern >= 0) {
                sb.append("                    plant = (((").append(toUnsignedHex(fernBiomeMask)).append(" & bbit) != 0UL) && ((rv & 3u) == 0u)) ? (uchar)").append(codeFern).append(" : (uchar)").append(codeShortGrass).append(";\n");
            } else {
                sb.append("                    plant = (uchar)").append(codeShortGrass).append(";\n");
            }
            sb.append("                }");
            if (codeDandelion >= 0) {
                sb.append(" else if (bucket == 14u) { plant = (uchar)").append(codeDandelion).append("; }");
            }
            if (codePoppy >= 0) {
                sb.append(" else if (bucket == 15u) { plant = (uchar)").append(codePoppy).append("; }");
            }
            sb.append("\n                if (plant != 0) {\n");
            sb.append("                    const int ary = top_ry + 1;\n");
            sb.append("                    const int a_off = (chunk_base_sec + (ary >> 4)) * 4096 + (((ary & 15) << 8) | (lz << 4) | lx);\n");
            sb.append("                    out_voxels[a_off] = plant;\n");
            sb.append("                }\n");
            sb.append("            }");
        }
        if (codeDeadBush >= 0 && (codeSand >= 0 || codeRedSand >= 0)) {
            sb.append(" else if (((").append(toUnsignedHex(desertBiomeMask)).append(" & bbit) != 0UL) && top_ry + 1 < total_height && (");
            if (codeSand >= 0 && codeRedSand >= 0) {
                sb.append("top_code == ").append(codeSand).append(" || top_code == ").append(codeRedSand);
            } else if (codeSand >= 0) {
                sb.append("top_code == ").append(codeSand);
            } else {
                sb.append("top_code == ").append(codeRedSand);
            }
            sb.append(")) {\n");
            sb.append("                if ((rv % 160u) == 0u) {\n");
            sb.append("                    const int ary = top_ry + 1;\n");
            sb.append("                    const int a_off = (chunk_base_sec + (ary >> 4)) * 4096 + (((ary & 15) << 8) | (lz << 4) | lx);\n");
            sb.append("                    out_voxels[a_off] = (uchar)").append(codeDeadBush).append(";\n");
            sb.append("                }\n");
            sb.append("            }");
        }
        if (codeSeagrass >= 0) {
            sb.append(" else if (top_code == 2 && (rv % 12u) == 0u) {\n");
            sb.append("                for (int wy = top_ry - 1; wy >= 1 && wy >= top_ry - 48; wy--) {\n");
            sb.append("                    const int w_off = (chunk_base_sec + (wy >> 4)) * 4096 + (((wy & 15) << 8) | (lz << 4) | lx);\n");
            sb.append("                    const uchar wc = out_voxels[w_off];\n");
            sb.append("                    if (wc != 2) {\n");
            sb.append("                        if (");
            boolean firstFloor = true;
            if (codeSand >= 0) { sb.append("wc == ").append(codeSand); firstFloor = false; }
            if (codeGravel >= 0) { if (!firstFloor) sb.append(" || "); sb.append("wc == ").append(codeGravel); firstFloor = false; }
            if (codeDirt >= 0) { if (!firstFloor) sb.append(" || "); sb.append("wc == ").append(codeDirt); firstFloor = false; }
            if (codeClay >= 0) { if (!firstFloor) sb.append(" || "); sb.append("wc == ").append(codeClay); firstFloor = false; }
            if (firstFloor) sb.append("0");
            sb.append(") {\n");
            sb.append("                            const int sry = wy + 1;\n");
            sb.append("                            const int s_off = (chunk_base_sec + (sry >> 4)) * 4096 + (((sry & 15) << 8) | (lz << 4) | lx);\n");
            sb.append("                            out_voxels[s_off] = (uchar)").append(codeSeagrass).append(";\n");
            sb.append("                        }\n");
            sb.append("                        break;\n");
            sb.append("                    }\n");
            sb.append("                }\n");
            sb.append("            }\n");
        }
        sb.append("        }\n");
        sb.append("    }\n\n");

        // Stage 4C: Fast top-down GPU WORLD_SURFACE / OCEAN_FLOOR heightmap scan (4-bit packing runs in Pass 5)
        sb.append("    int col_hm_surf = 0;\n");
        sb.append("    int col_hm_ocean = 0;\n");
        sb.append("    for (int ry = total_height - 1; ry >= 0; ry--) {\n");
        sb.append("        const uint c = (uint)out_voxels[(chunk_base_sec + (ry >> 4)) * 4096 + (((ry & 15) << 8) | col_idx)];\n");
        sb.append("        if (c != 0u) {\n");
        sb.append("            const ulong c_bit = (1UL << c);\n");
        sb.append("            if (col_hm_surf == 0 && (").append(toUnsignedHex(surfOpaqueMask)).append(" & c_bit) != 0UL) {\n");
        sb.append("                col_hm_surf = ry + 1;\n");
        sb.append("            }\n");
        sb.append("            if ((").append(toUnsignedHex(oceanOpaqueMask)).append(" & c_bit) != 0UL) {\n");
        sb.append("                col_hm_ocean = ry + 1;\n");
        sb.append("                if (col_hm_surf == 0) col_hm_surf = ry + 1;\n");
        sb.append("                break;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    out_heightmaps[batch_idx * 512 + col_idx] = (ushort)col_hm_surf;\n");
        sb.append("    out_heightmaps[batch_idx * 512 + 256 + col_idx] = (ushort)col_hm_ocean;\n");
        sb.append("}\n");
    }

    private static String toUnsignedHex(final long v) {
        return "0x" + Long.toHexString(v) + "UL";
    }

    private static String sha256(final String source) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(source.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
