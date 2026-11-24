package dev.leafcraft.goldleaf.worldgen;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reusable off-heap DMA writeback buffer for the fused vanilla-exact GPU terrain + {@code MaterialRule}
 * kernel ({@code fill_vanilla_surface_batch_u8}).
 *
 * <p>Layout per section:
 * <ul>
 *   <li>{@code voxels}: {@code 4096} bytes ({@code uint8} palette index per voxel in
 *       {@code (ly << 8) | (lz << 4) | lx} order, supporting up to 256 distinct {@code BlockState}s).</li>
 *   <li>{@code meta}: {@code 4 × uint16} ({@code 8 bytes}): {@code [nonEmptyCount, uniformFlag, uniformCode, fluidCount]}.</li>
 * </ul>
 * Plus {@code chunkFlags}: {@code 1 byte} per chunk ({@code bit 0 = needsCpuSpecialExtension}).</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017
 * @trace S-002
 */
public final class CompactVanillaSurfaceWritebackBuffer {

    public static final int VOXEL_BYTES_PER_SECTION = 4096;
    public static final int META_SHORTS_PER_SECTION = 4;
    public static final int META_BYTES_PER_SECTION = META_SHORTS_PER_SECTION * Short.BYTES;
    public static final int HEIGHTMAP_BYTES_PER_CHUNK = 512 * Short.BYTES;
    public static final int MODE_RAW_U8 = 0;
    public static final int MODE_UNIFORM = 1;
    public static final int MODE_PACKED_4BIT = 2;

    private ByteBuffer voxelsDirectBuf;
    private ByteBuffer metaDirectBuf;
    private ByteBuffer heightmapsDirectBuf;
    private int capacitySections;
    private int capacityChunks;
    private boolean gpuHeightmapsValid;

    public CompactVanillaSurfaceWritebackBuffer(final int initialSections) {
        if (initialSections <= 0) {
            throw new IllegalArgumentException("initialSections must be > 0, got " + initialSections);
        }
        allocate(initialSections, Math.max(1, (initialSections + 23) / 24));
    }

    public void ensureCapacity(final int batchCount, final int sectionCount) {
        final int required = Math.multiplyExact(batchCount, sectionCount);
        if (required > this.capacitySections || batchCount > this.capacityChunks) {
            allocate(Math.max(required, this.capacitySections), Math.max(batchCount, this.capacityChunks));
        }
    }

    private void allocate(final int sections, final int chunks) {
        if (sections > this.capacitySections || this.voxelsDirectBuf == null) {
            this.voxelsDirectBuf = ByteBuffer.allocateDirect(Math.multiplyExact(sections, VOXEL_BYTES_PER_SECTION))
                .order(ByteOrder.LITTLE_ENDIAN);
            this.metaDirectBuf = ByteBuffer.allocateDirect(Math.multiplyExact(sections, META_BYTES_PER_SECTION))
                .order(ByteOrder.LITTLE_ENDIAN);
            this.capacitySections = sections;
        }
        if (chunks > this.capacityChunks || this.heightmapsDirectBuf == null) {
            this.heightmapsDirectBuf = ByteBuffer.allocateDirect(Math.multiplyExact(chunks, HEIGHTMAP_BYTES_PER_CHUNK))
                .order(ByteOrder.LITTLE_ENDIAN);
            this.capacityChunks = chunks;
        }
    }

    public int capacitySections() {
        return this.capacitySections;
    }

    public ByteBuffer voxelsDirectBuffer() {
        return this.voxelsDirectBuf;
    }

    public ByteBuffer metaDirectBuffer() {
        return this.metaDirectBuf;
    }

    public ByteBuffer heightmapsDirectBuffer() {
        return this.heightmapsDirectBuf;
    }

    public boolean hasGpuHeightmaps() {
        return this.gpuHeightmapsValid;
    }

    public void setGpuHeightmapsValid(final boolean valid) {
        this.gpuHeightmapsValid = valid;
    }

    public int readNonEmptyCount(final int globalSectionIndex) {
        return Short.toUnsignedInt(this.metaDirectBuf.getShort(globalSectionIndex * META_BYTES_PER_SECTION));
    }

    public int readSectionMode(final int globalSectionIndex) {
        return Short.toUnsignedInt(this.metaDirectBuf.getShort(globalSectionIndex * META_BYTES_PER_SECTION + 2));
    }

    public boolean isUniform(final int globalSectionIndex) {
        return readSectionMode(globalSectionIndex) == MODE_UNIFORM;
    }

    public int readUniformCode(final int globalSectionIndex) {
        return Short.toUnsignedInt(this.metaDirectBuf.getShort(globalSectionIndex * META_BYTES_PER_SECTION + 4));
    }

    public int readFluidCount(final int globalSectionIndex) {
        return Short.toUnsignedInt(this.metaDirectBuf.getShort(globalSectionIndex * META_BYTES_PER_SECTION + 6));
    }
}
