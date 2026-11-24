package dev.leafcraft.goldleaf.worldgen;

import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.jspecify.annotations.Nullable;

/**
 * Flattens Mojang's 7D {@link Climate.RTree} into a contiguous {@code int32[]} buffer appended to
 * {@code gl_lut_spill} and emits the vanilla-exact {@code eval_biome_rtree_batch} OpenCL kernel.
 *
 * <p>Evaluating the 6 climate channels and traversing the exact 19-ary {@link Climate.RTree} in GPU
 * registers eliminates CPU R-tree traversal and shrinks biome DMA readback by {@code 24x}
 * ({@code 1 byte/quart} vs {@code 24 bytes/quart}).</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017, leafgen-ADR-008
 * @trace S-004
 */
public final class GpuBiomeRTree {

    public static final String RTREE_KERNEL_NAME = "eval_biome_rtree_batch";
    public static final int WORDS_PER_NODE = 17;
    public static final int MAX_PALETTE_SIZE = 256;
    private static final int MAX_STACK_DEPTH = 12;

    private GpuBiomeRTree() {
    }

    public record FlattenedRTree(
        int[] flatNodes,
        int nodeCount,
        Holder<Biome>[] biomePalette,
        int maxDepth,
        String digestSha256
    ) {
    }

    public record FusedBiomeRTreeProgram(
        OpenClBackendEmitter.DensityVolumeProgram program,
        FlattenedRTree rtree
    ) {
    }

    /**
     * Flattens {@code biomeSource}'s live {@link Climate.RTree} in breadth-first order so every
     * {@link Climate.RTree.SubTree}'s children occupy a contiguous range
     * {@code [firstChild .. firstChild + childCount - 1]} in exact vanilla child order.
     *
     * @return flattened R-tree, or {@code null} if ineligible (e.g. {@code > 256} biomes, depth
     *     {@code >= 12}, or bounds outside {@code int32})
     */
    @SuppressWarnings("unchecked")
    public static @Nullable FlattenedRTree flatten(final MultiNoiseBiomeSource biomeSource) {
        Objects.requireNonNull(biomeSource, "biomeSource");
        return flatten(biomeSource.goldleaf$parameters().goldleaf$index());
    }

    @SuppressWarnings("unchecked")
    public static @Nullable FlattenedRTree flatten(final Climate.RTree<Holder<Biome>> rtree) {
        Objects.requireNonNull(rtree, "rtree");
        final Climate.RTree.Node<Holder<Biome>> root = rtree.goldleaf$root();
        if (root == null) {
            return null;
        }

        final List<Climate.RTree.Node<Holder<Biome>>> orderedNodes = new ArrayList<>(1024);
        final List<Integer> firstChildList = new ArrayList<>(1024);
        final List<Integer> childCountList = new ArrayList<>(1024);
        final List<Integer> biomeIndexList = new ArrayList<>(1024);
        final List<Holder<Biome>> palette = new ArrayList<>(64);
        final Map<Holder<Biome>, Integer> paletteMap = new IdentityHashMap<>();

        final ArrayDeque<Climate.RTree.Node<Holder<Biome>>> queue = new ArrayDeque<>(1024);
        final ArrayDeque<Integer> depthQueue = new ArrayDeque<>(1024);
        queue.add(root);
        depthQueue.add(1);
        orderedNodes.add(root);
        firstChildList.add(0);
        childCountList.add(0);
        biomeIndexList.add(-1);

        int maxDepth = 1;
        int cursor = 0;
        while (!queue.isEmpty()) {
            final Climate.RTree.Node<Holder<Biome>> node = queue.poll();
            final int depth = depthQueue.poll();
            final int nodeIdx = cursor++;
            if (depth > maxDepth) {
                maxDepth = depth;
            }
            if (maxDepth >= MAX_STACK_DEPTH) {
                return null;
            }
            if (node instanceof Climate.RTree.Leaf<Holder<Biome>> leaf) {
                final Holder<Biome> biome = leaf.goldleaf$value();
                Integer idx = paletteMap.get(biome);
                if (idx == null) {
                    if (palette.size() >= MAX_PALETTE_SIZE) {
                        return null;
                    }
                    idx = palette.size();
                    palette.add(biome);
                    paletteMap.put(biome, idx);
                }
                firstChildList.set(nodeIdx, 0);
                childCountList.set(nodeIdx, 0);
                biomeIndexList.set(nodeIdx, idx);
            } else if (node instanceof Climate.RTree.SubTree<Holder<Biome>> sub) {
                final Climate.RTree.Node<Holder<Biome>>[] children = sub.goldleaf$children();
                final int firstChild = orderedNodes.size();
                firstChildList.set(nodeIdx, firstChild);
                childCountList.set(nodeIdx, children.length);
                biomeIndexList.set(nodeIdx, -1);
                for (final Climate.RTree.Node<Holder<Biome>> child : children) {
                    orderedNodes.add(child);
                    firstChildList.add(0);
                    childCountList.add(0);
                    biomeIndexList.add(-1);
                    queue.add(child);
                    depthQueue.add(depth + 1);
                }
            } else {
                return null;
            }
        }

        final int nodeCount = orderedNodes.size();
        final int[] flatNodes = new int[nodeCount * WORDS_PER_NODE];
        for (int i = 0; i < nodeCount; i++) {
            final Climate.Parameter[] params = orderedNodes.get(i).goldleaf$parameterSpace();
            if (params == null || params.length < 7) {
                return null;
            }
            final int base = i * WORDS_PER_NODE;
            for (int d = 0; d < 7; d++) {
                final long mn = params[d].min();
                final long mx = params[d].max();
                if (mn < Integer.MIN_VALUE || mn > Integer.MAX_VALUE || mx < Integer.MIN_VALUE || mx > Integer.MAX_VALUE) {
                    return null;
                }
                flatNodes[base + d] = (int) mn;
                flatNodes[base + 7 + d] = (int) mx;
            }
            flatNodes[base + 14] = firstChildList.get(i);
            flatNodes[base + 15] = childCountList.get(i);
            flatNodes[base + 16] = biomeIndexList.get(i);
        }

        final Holder<Biome>[] paletteArr = palette.toArray(new Holder[0]);
        final String digest = sha256IntArray(flatNodes);
        return new FlattenedRTree(flatNodes, nodeCount, paletteArr, maxDepth, digest);
    }

    /**
     * Transforms a 6-channel {@code eval_biome_climate6_batch} program into a fused
     * {@code eval_biome_rtree_batch} program that appends {@code rtree.flatNodes()} onto
     * {@code gl_lut_spill} and outputs {@code uchar} biome palette indices directly.
     */
    public static @Nullable FusedBiomeRTreeProgram buildFusedProgram(
        final OpenClBackendEmitter.DensityVolumeProgram climate6Program,
        final FlattenedRTree rtree
    ) {
        Objects.requireNonNull(climate6Program, "climate6Program");
        Objects.requireNonNull(rtree, "rtree");

        final String src = climate6Program.sourceCode();
        final int kernelIdx = src.indexOf("__kernel void eval_biome_climate6_batch(");
        if (kernelIdx < 0) {
            return null;
        }
        final String helperPrefix = src.substring(0, kernelIdx);
        final String kernelTail = src.substring(kernelIdx);

        final String[] exprs = new String[6];
        for (int c = 0; c < 6; c++) {
            final String marker = "out_values[out_base + " + c + "] = ";
            final int mIdx = kernelTail.indexOf(marker);
            if (mIdx < 0) {
                return null;
            }
            final int exprStart = mIdx + marker.length();
            final int semiIdx = kernelTail.indexOf(';', exprStart);
            if (semiIdx < 0) {
                return null;
            }
            exprs[c] = kernelTail.substring(exprStart, semiIdx).trim();
        }

        final int[] origSpill = climate6Program.spilledLutWords();
        final int rtreeBaseWord = origSpill.length;
        final int[] combinedSpill = Arrays.copyOf(origSpill, rtreeBaseWord + rtree.flatNodes().length);
        System.arraycopy(rtree.flatNodes(), 0, combinedSpill, rtreeBaseWord, rtree.flatNodes().length);

        final StringBuilder sb = new StringBuilder(helperPrefix.length() + 4096);
        sb.append(helperPrefix);
        sb.append("static inline long gl_rtree_node_dist(\n");
        sb.append("    __global const int* restrict node,\n");
        sb.append("    const long t0, const long t1, const long t2,\n");
        sb.append("    const long t3, const long t4, const long t5,\n");
        sb.append("    const long max_dist)\n");
        sb.append("{\n");
        sb.append("    long a, b, d, sum;\n");
        sb.append("    a = t0 - (long)node[7];  b = (long)node[0] - t0; d = a > 0L ? a : (b > 0L ? b : 0L); sum = d * d; if (sum >= max_dist) return sum;\n");
        sb.append("    a = t1 - (long)node[8];  b = (long)node[1] - t1; d = a > 0L ? a : (b > 0L ? b : 0L); sum += d * d; if (sum >= max_dist) return sum;\n");
        sb.append("    a = t2 - (long)node[9];  b = (long)node[2] - t2; d = a > 0L ? a : (b > 0L ? b : 0L); sum += d * d; if (sum >= max_dist) return sum;\n");
        sb.append("    a = t3 - (long)node[10]; b = (long)node[3] - t3; d = a > 0L ? a : (b > 0L ? b : 0L); sum += d * d; if (sum >= max_dist) return sum;\n");
        sb.append("    a = t4 - (long)node[11]; b = (long)node[4] - t4; d = a > 0L ? a : (b > 0L ? b : 0L); sum += d * d; if (sum >= max_dist) return sum;\n");
        sb.append("    a = t5 - (long)node[12]; b = (long)node[5] - t5; d = a > 0L ? a : (b > 0L ? b : 0L); sum += d * d; if (sum >= max_dist) return sum;\n");
        sb.append("    a = -(long)node[13];     b = (long)node[6];      d = a > 0L ? a : (b > 0L ? b : 0L); return sum + d * d;\n");
        sb.append("}\n\n");

        sb.append("static inline uchar gl_rtree_lookup_biome(\n");
        sb.append("    const long t0, const long t1, const long t2,\n");
        sb.append("    const long t3, const long t4, const long t5,\n");
        sb.append("    __global const int* restrict gl_lut_spill)\n");
        sb.append("{\n");
        sb.append("    const int rtree_base = ").append(rtreeBaseWord).append(";\n");
        sb.append("    __global const int* root = gl_lut_spill + rtree_base;\n");
        sb.append("    if (root[15] == 0) {\n");
        sb.append("        return (uchar)(root[16] & 0xFF);\n");
        sb.append("    }\n");
        sb.append("    long best_dist = 0x7FFFFFFFFFFFFFFFL;\n");
        sb.append("    int best_biome = 0;\n");
        sb.append("    int stack_child[").append(MAX_STACK_DEPTH).append("];\n");
        sb.append("    int stack_end[").append(MAX_STACK_DEPTH).append("];\n");
        sb.append("    int sp = 0;\n");
        sb.append("    stack_child[0] = root[14];\n");
        sb.append("    stack_end[0] = root[14] + root[15];\n");
        sb.append("    while (sp >= 0) {\n");
        sb.append("        int c = stack_child[sp];\n");
        sb.append("        if (c >= stack_end[sp]) {\n");
        sb.append("            sp--;\n");
        sb.append("            continue;\n");
        sb.append("        }\n");
        sb.append("        stack_child[sp] = c + 1;\n");
        sb.append("        __global const int* child = gl_lut_spill + rtree_base + c * ").append(WORDS_PER_NODE).append(";\n");
        sb.append("        long d = gl_rtree_node_dist(child, t0, t1, t2, t3, t4, t5, best_dist);\n");
        sb.append("        if (d >= best_dist) {\n");
        sb.append("            continue;\n");
        sb.append("        }\n");
        sb.append("        int child_count = child[15];\n");
        sb.append("        if (child_count == 0) {\n");
        sb.append("            best_dist = d;\n");
        sb.append("            best_biome = child[16];\n");
        sb.append("            if (d == 0L) {\n");
        sb.append("                return (uchar)(best_biome & 0xFF);\n");
        sb.append("            }\n");
        sb.append("        } else if (sp + 1 < ").append(MAX_STACK_DEPTH).append(") {\n");
        sb.append("            sp++;\n");
        sb.append("            stack_child[sp] = child[14];\n");
        sb.append("            stack_end[sp] = child[14] + child_count;\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    return (uchar)(best_biome & 0xFF);\n");
        sb.append("}\n\n");

        sb.append("__kernel void ").append(RTREE_KERNEL_NAME).append("(\n");
        sb.append("    __global uchar* restrict out_biomes,\n");
        sb.append("    __global const int4* restrict batch_origins,\n");
        sb.append("    const int batch_count,\n");
        sb.append("    const int size_x,\n");
        sb.append("    const int size_y,\n");
        sb.append("    const int size_z,\n");
        sb.append("    const int step_x,\n");
        sb.append("    const int step_y,\n");
        sb.append("    const int step_z,\n");
        sb.append("    __global const int* restrict gl_lut_spill)\n");
        sb.append("{\n");
        sb.append("    const int gid = (int)get_global_id(0);\n");
        sb.append("    const int volume_size = size_x * size_y * size_z;\n");
        sb.append("    const int total_voxels = batch_count * volume_size;\n");
        sb.append("    if (gid >= total_voxels) {\n");
        sb.append("        return;\n");
        sb.append("    }\n");
        sb.append("    const int batch_idx = gid / volume_size;\n");
        sb.append("    const int voxel_idx = gid - batch_idx * volume_size;\n");
        sb.append("    const int col_idx = voxel_idx / size_y;\n");
        sb.append("    const int ly = voxel_idx - col_idx * size_y;\n");
        sb.append("    const int lz = col_idx / size_x;\n");
        sb.append("    const int lx = col_idx - lz * size_x;\n");
        sb.append("    const int4 orig = batch_origins[batch_idx];\n");
        sb.append("    const int bx = orig.x + lx * step_x;\n");
        sb.append("    const int by = orig.y + ly * step_y;\n");
        sb.append("    const int bz = orig.z + lz * step_z;\n");
        sb.append("    const float f0 = ").append(exprs[0]).append(";\n");
        sb.append("    const float f1 = ").append(exprs[1]).append(";\n");
        sb.append("    const float f2 = ").append(exprs[2]).append(";\n");
        sb.append("    const float f3 = ").append(exprs[3]).append(";\n");
        sb.append("    const float f4 = ").append(exprs[4]).append(";\n");
        sb.append("    const float f5 = ").append(exprs[5]).append(";\n");
        sb.append("    const long t0 = (long)(f0 * 10000.0f);\n");
        sb.append("    const long t1 = (long)(f1 * 10000.0f);\n");
        sb.append("    const long t2 = (long)(f2 * 10000.0f);\n");
        sb.append("    const long t3 = (long)(f3 * 10000.0f);\n");
        sb.append("    const long t4 = (long)(f4 * 10000.0f);\n");
        sb.append("    const long t5 = (long)(f5 * 10000.0f);\n");
        sb.append("    out_biomes[gid] = gl_rtree_lookup_biome(t0, t1, t2, t3, t4, t5, gl_lut_spill);\n");
        sb.append("}\n");

        final String fusedSource = sb.toString();
        final String digest = sha256String(climate6Program.kernelDigestSha256() + ":rtree:" + rtree.digestSha256());
        final OpenClBackendEmitter.DensityVolumeProgram prog = new OpenClBackendEmitter.DensityVolumeProgram(
            RTREE_KERNEL_NAME,
            fusedSource,
            digest,
            combinedSpill,
            climate6Program.usedConstantBytes(),
            climate6Program.spilledLutCount() + 1,
            climate6Program.deduplicatedLutCount()
        );
        return new FusedBiomeRTreeProgram(prog, rtree);
    }

    private static String sha256IntArray(final int[] data) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] buf = new byte[4];
            for (final int v : data) {
                buf[0] = (byte) (v >>> 24);
                buf[1] = (byte) (v >>> 16);
                buf[2] = (byte) (v >>> 8);
                buf[3] = (byte) v;
                md.update(buf);
            }
            return toHex(md.digest());
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String sha256String(final String value) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String toHex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
