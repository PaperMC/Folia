package dev.leafcraft.goldleaf.worldgen;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Reusable off-heap direct {@link ByteBuffer} container for compact GPU section-palette DMA
 * writeback ({@code goldleaf-ADR-017}).
 *
 * <p>Stores {@code 256} little-endian {@code uint32} words ({@code 1,024 B}) and {@code 4}
 * little-endian {@code uint16} metadata shorts ({@code 8 B}) per {@code 16x16x16} section
 * ({@code 24.19 KiB} for a 24-section Overworld chunk vs. {@code 384 KiB} for dense
 * {@code float[]}). Reusing one instance per worker/wave eliminates per-wave JVM heap allocations
 * and JNI array copies.</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017
 * @trace S-002, S-004
 */
public final class CompactSectionWritebackBuffer {

    public static final int CODE_AIR = 0;
    public static final int CODE_DEFAULT_BLOCK = 1;
    public static final int CODE_DEFAULT_FLUID = 2;
    public static final int CODE_DEEP_FLUID = 3;

    public static final int WORDS_PER_SECTION = 256;
    public static final int WORD_BYTES_PER_SECTION = WORDS_PER_SECTION * Integer.BYTES;
    public static final int META_SHORTS_PER_SECTION = 4;
    public static final int META_BYTES_PER_SECTION = META_SHORTS_PER_SECTION * Short.BYTES;
    public static final int TOTAL_BYTES_PER_SECTION = WORD_BYTES_PER_SECTION + META_BYTES_PER_SECTION;

    private int capacitySections;
    private ByteBuffer wordsDirectBuffer;
    private IntBuffer wordsIntView;
    private ByteBuffer metaDirectBuffer;
    private ShortBuffer metaShortView;

    public CompactSectionWritebackBuffer(final int initialSectionCapacity) {
        if (initialSectionCapacity <= 0) {
            throw new IllegalArgumentException("initialSectionCapacity must be > 0");
        }
        allocate(initialSectionCapacity);
    }

    /**
     * Ensures off-heap capacity for {@code batchCount * sectionCount} sections, growing lazily
     * only when a larger wave is dispatched.
     */
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
        this.wordsIntView = this.wordsDirectBuffer.asIntBuffer();
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

    public int codeMask(final int globalSectionIndex) {
        return this.metaShortView.get((globalSectionIndex << 2) + 2) & 0xFFFF;
    }

    /**
     * Returns the single uniform 2-bit palette code ({@code 0..3}) if every voxel in the section
     * shares that code, or {@code -1} if the section is mixed.
     */
    public int uniformCode(final int globalSectionIndex) {
        final int mask = codeMask(globalSectionIndex);
        if (Integer.bitCount(mask) == 1) {
            return Integer.numberOfTrailingZeros(mask);
        }
        return -1;
    }

    /**
     * Reads the packed 16-voxel X-row word ({@code lx = 0..15}, 2 bits per voxel) at
     * {@code (ly, lz)} inside {@code globalSectionIndex}.
     */
    public int packedRowWord(final int globalSectionIndex, final int ly, final int lz) {
        return this.wordsIntView.get((globalSectionIndex << 8) + (ly << 4) + lz);
    }

    /**
     * Decodes the 2-bit palette code ({@code 0..3}) at local section coordinates {@code (lx, ly, lz)}.
     */
    public int voxelCode(final int globalSectionIndex, final int lx, final int ly, final int lz) {
        return (packedRowWord(globalSectionIndex, ly, lz) >>> (lx << 1)) & 0x3;
    }

    /**
     * Copies the raw {@code 1,024 B} little-endian 2-bit YZX payload for {@code globalSectionIndex}
     * directly from off-heap DMA memory into {@code dst[dstOffset .. dstOffset + 1024)} without
     * mutating buffer position or unpacking voxels ({@code LCA-ICD-3.0} {@code mode 10} layout).
     */
    public void copySectionWordsBytes(final int globalSectionIndex, final byte[] dst, final int dstOffset) {
        this.wordsDirectBuffer.get(globalSectionIndex * WORD_BYTES_PER_SECTION, dst, dstOffset, WORD_BYTES_PER_SECTION);
    }
}
