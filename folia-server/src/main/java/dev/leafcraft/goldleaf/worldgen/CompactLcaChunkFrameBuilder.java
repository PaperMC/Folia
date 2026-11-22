package dev.leafcraft.goldleaf.worldgen;

import io.github.dailystruggle.anvil.LcaRegionReader;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Zero-translation builder converting off-heap GPU {@link CompactSectionWritebackBuffer} payloads
 * directly into spec-compliant {@code LCA-ICD-3.0} ({@code formatVersion = 0x0003},
 * {@code blockEncodingMode = 10} FlatBitPack) {@link RawChunkFrameSnapshot} frames
 * ({@code goldleaf-ADR-005} Stage 2, {@code goldleaf-ADR-017}).
 *
 * <p>Eliminates intermediate {@code LevelChunkSection} / {@code PalettedContainer} hydration:
 * uniform air sections fold to {@code 0x0000} ({@code 0} body bytes), uniform solid/fluid sections
 * fold to {@code 0x8000 | code} ({@code 0} body bytes), and mixed sections bulk-copy the GPU's
 * {@code 1,024 B} little-endian 2-bit YZX payload directly with a {@code CBIT T6b}-exact
 * {@code octreeOccupancyMask64} syndrome.</p>
 *
 * @governedBy goldleaf-ADR-005, goldleaf-ADR-017
 * @trace S-004
 */
public final class CompactLcaChunkFrameBuilder {

    /** {@code LCA-ICD-3.0} §6.3 ordinal for Minecraft 26.3+ {@code "minecraft:terrain"}. */
    public static final int STATUS_ORDINAL_TERRAIN = 12;

    /** {@code curveId = 00 (YZX)}, {@code blockEncodingMode = 10 (FlatBitPack)}, {@code sky = 00}, {@code blk = 00}. */
    public static final int SECTION_HEADER_MODE10_YZX = 0x20;

    /** Fixed byte length of a 4-state {@code mode 10} {@code SectionFrame} with 1-byte biome inherit. */
    public static final int MIXED_SECTION_FRAME_BYTES = 1 + 8 + 1 + 4 + CompactSectionWritebackBuffer.WORD_BYTES_PER_SECTION + 1;

    private static final int CHUNK_HEADER_FIXED_BYTES = 24;
    private static final int SELF_TAG_CANARY = 0x5800_0000;
    private static final int CHUNK_FLAG_SCALE_4B = 0x04;

    /**
     * Precomputed {@code 1L << xyToHilbert2D4(octX, octZ)} lookup indexed by {@code [octZ][octX]}
     * so {@code octreeOccupancyMask64} matches {@code LcaSectionCodec.YZX_TO_OCTANT6} ({@code CBIT T6b}).
     */
    private static final long[][] OCTANT_BIT_BASE = new long[4][4];

    static {
        for (int oz = 0; oz < 4; oz++) {
            for (int ox = 0; ox < 4; ox++) {
                OCTANT_BIT_BASE[oz][ox] = 1L << xyToHilbert2D4(ox, oz);
            }
        }
    }

    public record BuildResult(
        RawChunkFrameSnapshot snapshot,
        int uniformAirSections,
        int uniformNonAirSections,
        int mixedSections,
        int @Nullable [] worldSurfaceHeightmap,
        int @Nullable [] oceanFloorHeightmap
    ) {
        public int frameByteLength() {
            return this.snapshot.chunkFrame().length;
        }
    }

    private CompactLcaChunkFrameBuilder() {
    }

    /**
     * Builds a native {@code LCA-ICD-3.0} {@link RawChunkFrameSnapshot} for chunk {@code batchIndex}
     * directly from {@code buffer} without touching {@code LevelChunkSection}.
     */
    public static BuildResult buildChunkFrame(
        final CompactSectionWritebackBuffer buffer,
        final int batchIndex,
        final int chunkX,
        final int chunkZ,
        final int minSectionY,
        final int sectionCount,
        final int dataVersion,
        final int[] runtimeStateIds4
    ) {
        return buildChunkFrame(
            buffer,
            batchIndex,
            chunkX,
            chunkZ,
            minSectionY,
            sectionCount,
            dataVersion,
            runtimeStateIds4,
            false
        );
    }

    /**
     * Builds a native {@code LCA-ICD-3.0} {@link RawChunkFrameSnapshot} for chunk {@code batchIndex}
     * directly from {@code buffer}, optionally computing {@code WORLD_SURFACE_WG} and
     * {@code OCEAN_FLOOR_WG} heightmaps ({@code 256} entries indexed by {@code (lz << 4) | lx}).
     */
    public static BuildResult buildChunkFrame(
        final CompactSectionWritebackBuffer buffer,
        final int batchIndex,
        final int chunkX,
        final int chunkZ,
        final int minSectionY,
        final int sectionCount,
        final int dataVersion,
        final int[] runtimeStateIds4,
        final boolean computeHeightmaps
    ) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(runtimeStateIds4, "runtimeStateIds4");
        if (sectionCount <= 0) {
            throw new IllegalArgumentException("sectionCount must be > 0, got " + sectionCount);
        }
        if (runtimeStateIds4.length < 4) {
            throw new IllegalArgumentException("runtimeStateIds4 must contain at least 4 entries");
        }
        final int chunkSectionBase = Math.multiplyExact(batchIndex, sectionCount);
        if (chunkSectionBase < 0 || chunkSectionBase + sectionCount > buffer.capacitySections()) {
            throw new IndexOutOfBoundsException(
                "Batch range [" + chunkSectionBase + ".." + (chunkSectionBase + sectionCount)
                    + ") exceeds buffer capacity " + buffer.capacitySections()
            );
        }

        final int[] uniformCodes = new int[sectionCount];
        int uniformAir = 0;
        int uniformNonAir = 0;
        int mixed = 0;
        for (int s = 0; s < sectionCount; s++) {
            final int u = buffer.uniformCode(chunkSectionBase + s);
            uniformCodes[s] = u;
            if (u == CompactSectionWritebackBuffer.CODE_AIR) {
                uniformAir++;
            } else if (u > 0) {
                uniformNonAir++;
            } else {
                mixed++;
            }
        }

        final int sectionDirEnd = CHUNK_HEADER_FIXED_BYTES + (sectionCount << 1);
        final int rawBodyBytes = mixed * MIXED_SECTION_FRAME_BYTES;
        final boolean scale4B = rawBodyBytes > 0x7FFE;
        final int sectionStride = scale4B
            ? ((MIXED_SECTION_FRAME_BYTES + 3) & ~3)
            : MIXED_SECTION_FRAME_BYTES;
        final int totalBodyBytes = mixed * sectionStride;
        final byte[] frame = new byte[sectionDirEnd + totalBodyBytes];

        // 24-byte LCA-ICD-3.0 ChunkFrame header (ICD.md §6.1)
        final int chunkIdx = (chunkX & 31) | ((chunkZ & 31) << 5);
        final int selfTag = SELF_TAG_CANARY | ((chunkIdx & 0x03FF) << 16) | (dataVersion & 0xFFFF);
        writeIntBE(frame, 0, selfTag);
        // lastUpdateTick (+4..+11) = 0, inhabitedTimeTicks (+12..+19) = 0
        frame[20] = (byte) STATUS_ORDINAL_TERRAIN;
        frame[21] = (byte) (scale4B ? CHUNK_FLAG_SCALE_4B : 0);
        // residualNbtRelOffset (+22..+23) = 0 (hasResidualNbt = 0)

        final boolean airIsZero = runtimeStateIds4[0] == 0;
        int bodyPos = sectionDirEnd;
        for (int s = 0; s < sectionCount; s++) {
            final int dirOff = CHUNK_HEADER_FIXED_BYTES + (s << 1);
            final int u = uniformCodes[s];
            if (u == CompactSectionWritebackBuffer.CODE_AIR) {
                writeUint16BE(frame, dirOff, airIsZero ? 0 : 0x8000);
                continue;
            }
            if (u > 0) {
                writeUint16BE(frame, dirOff, 0x8000 | (u & 0x1FFF));
                continue;
            }

            final int rel = bodyPos - sectionDirEnd;
            final int entry16 = (scale4B ? (rel >>> 2) : rel) + 1;
            writeUint16BE(frame, dirOff, entry16);

            final int globalSecIdx = chunkSectionBase + s;
            final long octreeMask64 = computeOctreeMask64FromPacked2Bit(buffer, globalSecIdx);

            int p = bodyPos;
            frame[p++] = (byte) SECTION_HEADER_MODE10_YZX;
            writeLongBE(frame, p, octreeMask64);
            p += 8;
            frame[p++] = 4; // paletteLen = 4
            frame[p++] = 0; // dictId[0] = AIR
            frame[p++] = 1; // dictId[1] = DEFAULT_BLOCK
            frame[p++] = 2; // dictId[2] = DEFAULT_FLUID
            frame[p++] = 3; // dictId[3] = DEEP_FLUID
            buffer.copySectionWordsBytes(globalSecIdx, frame, p);
            p += CompactSectionWritebackBuffer.WORD_BYTES_PER_SECTION;
            frame[p++] = 0; // biome payload bLen = 0 (inherit default biome)
            bodyPos += sectionStride;
        }

        final int[] dictIdToRuntimeId = new int[] {
            runtimeStateIds4[0],
            runtimeStateIds4[1],
            runtimeStateIds4[2],
            runtimeStateIds4[3]
        };
        final RawChunkFrameSnapshot snapshot = new RawChunkFrameSnapshot(
            frame,
            dataVersion,
            minSectionY,
            sectionCount,
            0,
            dictIdToRuntimeId,
            LcaRegionReader.LCA_FORMAT_VERSION
        );

        int[] worldSurface = null;
        int[] oceanFloor = null;
        if (computeHeightmaps) {
            final int minBlockY = minSectionY << 4;
            worldSurface = new int[256];
            oceanFloor = new int[256];
            Arrays.fill(worldSurface, minBlockY);
            Arrays.fill(oceanFloor, minBlockY);
            computeHeightmapsTopDown(buffer, chunkSectionBase, sectionCount, minBlockY, uniformCodes, worldSurface, oceanFloor);
        }

        return new BuildResult(snapshot, uniformAir, uniformNonAir, mixed, worldSurface, oceanFloor);
    }

    /**
     * Computes the exact 64-bit {@code octreeOccupancyMask64} syndrome ({@code CBIT T6b}) directly
     * from the 256 packed 2-bit {@code uint32} words without unpacking individual voxels.
     */
    public static long computeOctreeMask64FromPacked2Bit(
        final CompactSectionWritebackBuffer buffer,
        final int globalSectionIndex
    ) {
        long mask = 0L;
        for (int ly = 0; ly < 16; ly++) {
            final int octYShift = (ly >>> 2) << 4;
            for (int lz = 0; lz < 16; lz++) {
                final int word = buffer.packedRowWord(globalSectionIndex, ly, lz);
                if (word == 0) {
                    continue;
                }
                final long[] rowBits = OCTANT_BIT_BASE[lz >>> 2];
                if ((word & 0x0000_00FF) != 0) {
                    mask |= rowBits[0] << octYShift;
                }
                if ((word & 0x0000_FF00) != 0) {
                    mask |= rowBits[1] << octYShift;
                }
                if ((word & 0x00FF_0000) != 0) {
                    mask |= rowBits[2] << octYShift;
                }
                if ((word & 0xFF00_0000) != 0) {
                    mask |= rowBits[3] << octYShift;
                }
            }
        }
        return mask;
    }

    /**
     * Computes {@code octreeOccupancyMask64} ({@code CBIT T6b}) for any decoded 4,096-voxel YZX
     * palette ordinal array where {@code ord == 0} is the dominant background state.
     */
    public static long computeOctreeMask64FromOrdinals(final int[] yzxOrdinals4096) {
        long mask = 0L;
        for (int ly = 0; ly < 16; ly++) {
            final int octYShift = (ly >>> 2) << 4;
            final int yBase = ly << 8;
            for (int lz = 0; lz < 16; lz++) {
                final long[] rowBits = OCTANT_BIT_BASE[lz >>> 2];
                final int rowBase = yBase | (lz << 4);
                for (int lx = 0; lx < 16; lx++) {
                    if (yzxOrdinals4096[rowBase | lx] != 0) {
                        mask |= rowBits[lx >>> 2] << octYShift;
                    }
                }
            }
        }
        return mask;
    }

    private static void computeHeightmapsTopDown(
        final CompactSectionWritebackBuffer buffer,
        final int chunkSectionBase,
        final int sectionCount,
        final int minBlockY,
        final int[] uniformCodes,
        final int[] worldSurface,
        final int[] oceanFloor
    ) {
        int remainingSurface = 256;
        int remainingOcean = 256;
        for (int s = sectionCount - 1; s >= 0 && (remainingSurface > 0 || remainingOcean > 0); s--) {
            final int u = uniformCodes[s];
            if (u == CompactSectionWritebackBuffer.CODE_AIR) {
                continue;
            }
            final int sectionMinY = minBlockY + (s << 4);
            if (u > 0) {
                final int topY = sectionMinY + 16;
                final boolean isSolid = u == CompactSectionWritebackBuffer.CODE_DEFAULT_BLOCK;
                for (int col = 0; col < 256; col++) {
                    if (worldSurface[col] == minBlockY) {
                        worldSurface[col] = topY;
                        remainingSurface--;
                    }
                    if (isSolid && oceanFloor[col] == minBlockY) {
                        oceanFloor[col] = topY;
                        remainingOcean--;
                    }
                }
                continue;
            }
            final int globalSecIdx = chunkSectionBase + s;
            for (int ly = 15; ly >= 0 && (remainingSurface > 0 || remainingOcean > 0); ly--) {
                final int heightVal = sectionMinY + ly + 1;
                for (int lz = 0; lz < 16; lz++) {
                    final int word = buffer.packedRowWord(globalSecIdx, ly, lz);
                    if (word == 0) {
                        continue;
                    }
                    final int rowBase = lz << 4;
                    int rem = word;
                    for (int lx = 0; lx < 16 && rem != 0; lx++, rem >>>= 2) {
                        final int code = rem & 0x3;
                        if (code == CompactSectionWritebackBuffer.CODE_AIR) {
                            continue;
                        }
                        final int col = rowBase | lx;
                        if (worldSurface[col] == minBlockY) {
                            worldSurface[col] = heightVal;
                            remainingSurface--;
                        }
                        if (code == CompactSectionWritebackBuffer.CODE_DEFAULT_BLOCK && oceanFloor[col] == minBlockY) {
                            oceanFloor[col] = heightVal;
                            remainingOcean--;
                        }
                    }
                }
            }
        }
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

    private static void writeUint16BE(final byte[] buf, final int off, final int v) {
        buf[off] = (byte) (v >>> 8);
        buf[off + 1] = (byte) v;
    }

    private static void writeIntBE(final byte[] buf, final int off, final int v) {
        buf[off] = (byte) (v >>> 24);
        buf[off + 1] = (byte) (v >>> 16);
        buf[off + 2] = (byte) (v >>> 8);
        buf[off + 3] = (byte) v;
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
}
