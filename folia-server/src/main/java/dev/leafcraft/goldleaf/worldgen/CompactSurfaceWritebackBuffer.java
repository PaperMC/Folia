package dev.leafcraft.goldleaf.worldgen;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

/**
 * Reusable off-heap direct {@link ByteBuffer} container for 4-bit (16-state) fused
 * {@code TERRAIN + SURFACE} GPU section-palette DMA writeback ({@code goldleaf-ADR-017}).
 *
 * <p>Stores {@code 256} little-endian {@code uint64} ({@code long}) row words ({@code 2,048 B})
 * and {@code 4} little-endian {@code uint16} metadata shorts ({@code 8 B}) per {@code 16x16x16}
 * section ({@code 48.19 KiB} for a 24-section Overworld chunk vs. {@code 384 KiB} for dense
 * {@code float[]}). Each {@code long} holds 16 packed 4-bit palette codes matching Minecraft's
 * native {@code SimpleBitStorage(4, 4096)} layout directly with zero CPU bit-scatter.</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017
 * @trace S-002, S-004
 */
public final class CompactSurfaceWritebackBuffer {

    public static final int CODE_AIR = 0;
    public static final int CODE_STONE = 1;
    public static final int CODE_WATER = 2;
    public static final int CODE_LAVA = 3;
    public static final int CODE_BEDROCK = 4;
    public static final int CODE_DEEPSLATE = 5;
    public static final int CODE_GRASS_BLOCK = 6;
    public static final int CODE_DIRT = 7;
    public static final int CODE_SAND = 8;
    public static final int CODE_SANDSTONE = 9;
    public static final int CODE_GRAVEL = 10;
    public static final int CODE_CLAY = 11;
    public static final int CODE_SNOW_BLOCK = 12;
    public static final int CODE_PACKED_ICE = 13;
    public static final int CODE_NETHERRACK = 14;
    public static final int CODE_END_STONE = 15;
    public static final int PALETTE_CAPACITY = 16;

    public static final int LONGS_PER_SECTION = 256;
    public static final int WORD_BYTES_PER_SECTION = LONGS_PER_SECTION * Long.BYTES;
    public static final int META_SHORTS_PER_SECTION = 4;
    public static final int META_BYTES_PER_SECTION = META_SHORTS_PER_SECTION * Short.BYTES;
    public static final int TOTAL_BYTES_PER_SECTION = WORD_BYTES_PER_SECTION + META_BYTES_PER_SECTION;

    private int capacitySections;
    private ByteBuffer wordsDirectBuffer;
    private LongBuffer wordsLongView;
    private ByteBuffer metaDirectBuffer;
    private ShortBuffer metaShortView;

    public CompactSurfaceWritebackBuffer(final int initialSectionCapacity) {
        if (initialSectionCapacity <= 0) {
            throw new IllegalArgumentException("initialSectionCapacity must be > 0");
        }
        allocate(initialSectionCapacity);
    }

    public void ensureCapacity(final int batchCount, final int sectionCount) {
        if (batchCount <= 0 || sectionCount <= 0) {
            throw new IllegalArgumentException("batchCount and sectionCount must be > 0");
        }
        final int requiredSections = Math.multiplyExact(batchCount, sectionCount);
        if (requiredSections > this.capacitySections) {
            allocate(requiredSections);
        }
    }

    private void allocate(final int sections) {
        final int wordBytes = Math.multiplyExact(sections, WORD_BYTES_PER_SECTION);
        final int metaBytes = Math.multiplyExact(sections, META_BYTES_PER_SECTION);
        this.wordsDirectBuffer = ByteBuffer.allocateDirect(wordBytes).order(ByteOrder.LITTLE_ENDIAN);
        this.wordsLongView = this.wordsDirectBuffer.asLongBuffer();
        this.metaDirectBuffer = ByteBuffer.allocateDirect(metaBytes).order(ByteOrder.LITTLE_ENDIAN);
        this.metaShortView = this.metaDirectBuffer.asShortBuffer();
        this.capacitySections = sections;
    }

    public int capacitySections() {
        return this.capacitySections;
    }

    public ByteBuffer wordsDirectBuffer() {
        return this.wordsDirectBuffer;
    }

    public ByteBuffer metaDirectBuffer() {
        return this.metaDirectBuffer;
    }

    public int nonEmptyCount(final int globalSectionIndex) {
        return this.metaShortView.get(globalSectionIndex << 2) & 0xFFFF;
    }

    public int fluidCount(final int globalSectionIndex) {
        return this.metaShortView.get((globalSectionIndex << 2) + 1) & 0xFFFF;
    }

    public int codeMask16(final int globalSectionIndex) {
        return this.metaShortView.get((globalSectionIndex << 2) + 2) & 0xFFFF;
    }

    public int uniformCode(final int globalSectionIndex) {
        final int mask = codeMask16(globalSectionIndex);
        if (Integer.bitCount(mask) == 1) {
            return Integer.numberOfTrailingZeros(mask);
        }
        return -1;
    }

    public long packedRowLong(final int globalSectionIndex, final int ly, final int lz) {
        return this.wordsLongView.get((globalSectionIndex << 8) + (ly << 4) + lz);
    }

    public int voxelCode(final int globalSectionIndex, final int lx, final int ly, final int lz) {
        return (int) ((packedRowLong(globalSectionIndex, ly, lz) >>> (lx << 2)) & 0xFL);
    }

    /**
     * Bulk-copies all {@code 256} {@code SimpleBitStorage(4, 4096)} {@code long}s for
     * {@code globalSectionIndex} directly from off-heap DMA memory into {@code dst[0..256)}.
     */
    public void copySectionLongs(final int globalSectionIndex, final long[] dst) {
        this.wordsLongView.get(globalSectionIndex << 8, dst, 0, LONGS_PER_SECTION);
    }
}
