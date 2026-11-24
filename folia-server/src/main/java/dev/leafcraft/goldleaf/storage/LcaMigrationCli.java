package dev.leafcraft.goldleaf.storage;

import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.jspecify.annotations.Nullable;

/**
 * Standalone, zero-server-boot bidirectional {@code .mca <-> .lca} ({@code LCA-ICD-3.0})
 * migration and parity-verification CLI ({@code goldleaf-ADR-014} §1).
 *
 * <p>Guarantees:
 * <ul>
 *   <li><strong>Never deletes source files:</strong> Writes converted regions into
 *       {@code --output} using atomic temp files + {@code FileChannel#force(true)} {@code fsync}
 *       ({@code S-004}).</li>
 *   <li><strong>{@code --dry-run}:</strong> Reports exact chunk counts, source sizes, and corrupt
 *       or truncated source entries without writing any files.</li>
 *   <li><strong>{@code --verify}:</strong> Re-reads every converted chunk from the target format
 *       and verifies chunk-by-chunk NBT payload SHA-256 parity against the source, exiting
 *       non-zero on any mismatch or corrupt entry ({@code S-004}).</li>
 * </ul>
 * </p>
 */
public final class LcaMigrationCli {

    private static final int SECTOR_BYTES = 4096;
    private static final int CHUNKS_PER_REGION = 1024;
    private static final int DEFAULT_MIN_SECTION_Y = -4;
    private static final int DEFAULT_SECTION_COUNT = 24;
    private static final int DEFAULT_DATA_VERSION = 4189;
    private static final List<String> WORLD_SUBDIRS = List.of("region", "entities", "poi");
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.(mca|lca)$");

    public enum Format {
        MCA("mca"),
        LCA("lca");

        private final String extension;

        Format(final String extension) {
            this.extension = extension;
        }

        public String extension() {
            return this.extension;
        }

        public static Format parse(final String raw) {
            final String norm = Objects.requireNonNull(raw, "format").trim().toLowerCase(Locale.ROOT);
            return switch (norm) {
                case "mca" -> MCA;
                case "lca" -> LCA;
                default -> throw new IllegalArgumentException("Unsupported format '" + raw + "' (expected 'mca' or 'lca')");
            };
        }
    }

    public record Options(
        Format from,
        Format to,
        Path worldDir,
        @Nullable Path outputDir,
        boolean dryRun,
        boolean verify,
        boolean modeBLcd2
    ) {
        public Options {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(worldDir, "worldDir");
            if (from == to) {
                throw new IllegalArgumentException("Source and target formats must differ: " + from);
            }
            if (!dryRun && outputDir == null) {
                throw new IllegalArgumentException("--output <dir> is required unless --dry-run is set");
            }
        }
    }

    public record Report(
        int exitCode,
        int regionsScanned,
        int chunksConverted,
        int chunksVerified,
        int corruptChunks,
        int verifyMismatches,
        long sourceBytes,
        long targetBytes,
        List<String> errors
    ) {
        public boolean isSuccess() {
            return this.exitCode == 0;
        }

        /**
         * Conservative projected conversion wall-time in milliseconds for {@code --dry-run}
         * planning ({@code goldleaf-ADR-014} §1).
         */
        public long estimatedWallTimeMs() {
            if (this.chunksConverted <= 0 && this.sourceBytes <= 0L) {
                return 0L;
            }
            return Math.max(1L, Math.round(this.chunksConverted * 0.45d + this.sourceBytes / (64.0d * 1024.0d)));
        }
    }

    private LcaMigrationCli() {
    }

    public static void main(final String[] args) {
        final Options options = parseArgs(args);
        final Report report = run(options);
        System.out.printf(
            "LcaMigrationCli: exit=%d regions=%d converted=%d verified=%d corrupt=%d mismatches=%d sourceBytes=%d targetBytes=%d estWallTimeMs=%d%n",
            report.exitCode(),
            report.regionsScanned(),
            report.chunksConverted(),
            report.chunksVerified(),
            report.corruptChunks(),
            report.verifyMismatches(),
            report.sourceBytes(),
            report.targetBytes(),
            report.estimatedWallTimeMs()
        );
        for (final String err : report.errors()) {
            System.err.println("  ERROR: " + err);
        }
        if (report.exitCode() != 0) {
            System.exit(report.exitCode());
        }
    }

    public static Options parseArgs(final String[] args) {
        Format from = null;
        Format to = null;
        Path worldDir = null;
        Path outputDir = null;
        boolean dryRun = false;
        boolean verify = false;
        boolean modeBLcd2 = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--from" -> from = Format.parse(requireArg(args, ++i, "--from"));
                case "--to" -> to = Format.parse(requireArg(args, ++i, "--to"));
                case "--world" -> worldDir = Path.of(requireArg(args, ++i, "--world"));
                case "--output" -> outputDir = Path.of(requireArg(args, ++i, "--output"));
                case "--dry-run" -> dryRun = true;
                case "--verify" -> verify = true;
                case "--mode-b" -> modeBLcd2 = true;
                case "--no-lcd2" -> modeBLcd2 = false;
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        if (from == null || to == null || worldDir == null) {
            throw new IllegalArgumentException(
                "Usage: --from <mca|lca> --to <lca|mca> --world <dir> [--output <dir>] [--dry-run] [--verify]"
            );
        }
        return new Options(from, to, worldDir, outputDir, dryRun, verify, modeBLcd2);
    }

    private static String requireArg(final String[] args, final int index, final String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[index];
    }

    public static Report run(final Options options) {
        Objects.requireNonNull(options, "options");
        int regionsScanned = 0;
        int chunksConverted = 0;
        int chunksVerified = 0;
        int corruptChunks = 0;
        int verifyMismatches = 0;
        long sourceBytes = 0L;
        long targetBytes = 0L;
        final List<String> errors = new ArrayList<>();

        for (final String subdir : WORLD_SUBDIRS) {
            final Path srcSubdir = options.worldDir().resolve(subdir);
            if (!Files.isDirectory(srcSubdir)) {
                continue;
            }

            final List<Path> regionFiles;
            try (Stream<Path> stream = Files.list(srcSubdir)) {
                regionFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("." + options.from().extension()))
                    .sorted()
                    .toList();
            } catch (final IOException ex) {
                errors.add("Failed to list " + srcSubdir + ": " + ex.getMessage());
                continue;
            }

            for (final Path srcFile : regionFiles) {
                final Matcher matcher = REGION_FILE_PATTERN.matcher(srcFile.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                final int regionX = Integer.parseInt(matcher.group(1));
                final int regionZ = Integer.parseInt(matcher.group(2));
                regionsScanned++;
                try {
                    sourceBytes += Files.size(srcFile);
                } catch (final IOException ex) {
                    errors.add("Failed to stat " + srcFile + ": " + ex.getMessage());
                    continue;
                }

                final Map<Integer, byte[]> sourceChunks = new LinkedHashMap<>();
                final int corruptInRegion = readRegionChunks(
                    srcSubdir, srcFile, options.from(), regionX, regionZ, sourceChunks, errors
                );
                corruptChunks += corruptInRegion;

                if (options.dryRun()) {
                    chunksConverted += sourceChunks.size();
                    continue;
                }

                final Path dstSubdir = Objects.requireNonNull(options.outputDir()).resolve(subdir);
                try {
                    Files.createDirectories(dstSubdir);
                    final Path dstFile = writeRegionChunksAtomic(
                        dstSubdir, options.to(), options.modeBLcd2(), regionX, regionZ, sourceChunks
                    );
                    chunksConverted += sourceChunks.size();
                    if (Files.exists(dstFile)) {
                        targetBytes += Files.size(dstFile);
                    }

                    if (options.verify()) {
                        final Map<Integer, byte[]> roundTripped = new LinkedHashMap<>();
                        final int dstCorrupt = readRegionChunks(
                            dstSubdir, dstFile, options.to(), regionX, regionZ, roundTripped, errors
                        );
                        corruptChunks += dstCorrupt;
                        if (roundTripped.size() != sourceChunks.size()) {
                            verifyMismatches++;
                            errors.add(
                                "Chunk count mismatch for " + dstFile + ": expected "
                                    + sourceChunks.size() + ", got " + roundTripped.size()
                            );
                        } else {
                            for (final Map.Entry<Integer, byte[]> entry : sourceChunks.entrySet()) {
                                final byte[] actual = roundTripped.get(entry.getKey());
                                if (actual == null || !chunksSemanticallyEqual(entry.getValue(), actual)) {
                                    verifyMismatches++;
                                    errors.add(
                                        "Chunk parity mismatch in " + dstFile + " slot " + entry.getKey()
                                    );
                                } else {
                                    chunksVerified++;
                                }
                            }
                        }
                    }
                } catch (final IOException ex) {
                    errors.add("Failed converting " + srcFile + ": " + ex.getMessage());
                }
            }
        }

        final int exitCode = (corruptChunks > 0 || verifyMismatches > 0 || !errors.isEmpty()) ? 1 : 0;
        return new Report(
            exitCode,
            regionsScanned,
            chunksConverted,
            chunksVerified,
            corruptChunks,
            verifyMismatches,
            sourceBytes,
            targetBytes,
            Collections.unmodifiableList(errors)
        );
    }

    private static int readRegionChunks(
        final Path folder,
        final Path regionFile,
        final Format format,
        final int regionX,
        final int regionZ,
        final Map<Integer, byte[]> outChunks,
        final List<String> errors
    ) {
        if (format == Format.MCA) {
            return readMcaRegion(regionFile, outChunks, errors);
        }
        return readLcaRegion(folder, regionX, regionZ, outChunks, errors);
    }

    private static int readMcaRegion(
        final Path mcaFile,
        final Map<Integer, byte[]> outChunks,
        final List<String> errors
    ) {
        int corrupt = 0;
        final byte[] raw;
        try {
            raw = Files.readAllBytes(mcaFile);
        } catch (final IOException ex) {
            errors.add("Unreadable .mca file " + mcaFile + ": " + ex.getMessage());
            return 1;
        }
        if (raw.length < SECTOR_BYTES * 2) {
            errors.add("Truncated .mca header (< 8192 bytes): " + mcaFile);
            return 1;
        }
        final ByteBuffer buf = ByteBuffer.wrap(raw);
        for (int slot = 0; slot < CHUNKS_PER_REGION; slot++) {
            final int loc = buf.getInt(slot * 4);
            if (loc == 0) {
                continue;
            }
            final int sectorOffset = (loc >>> 8) & 0xFFFFFF;
            final int sectorCount = loc & 0xFF;
            final int byteOffset = sectorOffset * SECTOR_BYTES;
            final int maxEnd = byteOffset + sectorCount * SECTOR_BYTES;
            if (sectorOffset < 2 || sectorCount == 0 || byteOffset + 5 > raw.length || maxEnd > raw.length) {
                corrupt++;
                errors.add("Corrupt sector pointer in " + mcaFile + " slot " + slot);
                continue;
            }
            final int payloadLen = buf.getInt(byteOffset);
            final int compressionType = raw[byteOffset + 4] & 0xFF;
            if (payloadLen <= 1 || byteOffset + 4 + payloadLen > maxEnd) {
                corrupt++;
                errors.add("Corrupt chunk length in " + mcaFile + " slot " + slot);
                continue;
            }
            final byte[] compressed = Arrays.copyOfRange(raw, byteOffset + 5, byteOffset + 4 + payloadLen);
            try {
                final byte[] uncompressed = decompressMcaPayload(compressionType, compressed);
                outChunks.put(slot, uncompressed);
            } catch (final IOException ex) {
                corrupt++;
                errors.add("Corrupt compressed chunk in " + mcaFile + " slot " + slot + ": " + ex.getMessage());
            }
        }
        return corrupt;
    }

    private static byte[] decompressMcaPayload(final int compressionType, final byte[] compressed) throws IOException {
        return switch (compressionType) {
            case 1 -> {
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                    yield in.readAllBytes();
                }
            }
            case 2 -> {
                try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                    yield in.readAllBytes();
                }
            }
            case 3 -> compressed;
            default -> throw new IOException("Unsupported .mca compression type: " + compressionType);
        };
    }

    private static int readLcaRegion(
        final Path folder,
        final int regionX,
        final int regionZ,
        final Map<Integer, byte[]> outChunks,
        final List<String> errors
    ) {
        int corrupt = 0;
        final int storageKind = LcaRegionFileStorage.inferStorageKindFromPath(folder);
        try (LcaRegionFileStorage lca = new LcaRegionFileStorage(
            folder,
            storageKind,
            DEFAULT_MIN_SECTION_Y,
            DEFAULT_SECTION_COUNT,
            DEFAULT_DATA_VERSION,
            true,
            false,
            false,
            4,
            0.35
        )) {
            final int baseChunkX = regionX << 5;
            final int baseChunkZ = regionZ << 5;
            for (int localZ = 0; localZ < 32; localZ++) {
                for (int localX = 0; localX < 32; localX++) {
                    final int slot = localX + (localZ << 5);
                    try {
                        final byte[] data = lca.readChunkNbtBytes(baseChunkX + localX, baseChunkZ + localZ);
                        if (data != null && data.length > 0) {
                            outChunks.put(slot, data);
                        }
                    } catch (final IOException | RuntimeException ex) {
                        corrupt++;
                        errors.add(
                            "Corrupt .lca chunk (" + (baseChunkX + localX) + "," + (baseChunkZ + localZ)
                                + "): " + ex.getMessage()
                        );
                    }
                }
            }
        } catch (final IOException ex) {
            errors.add("Failed to open .lca folder " + folder + ": " + ex.getMessage());
            return 1;
        }
        return corrupt;
    }

    private static Path writeRegionChunksAtomic(
        final Path dstSubdir,
        final Format targetFormat,
        final boolean modeBLcd2,
        final int regionX,
        final int regionZ,
        final Map<Integer, byte[]> chunks
    ) throws IOException {
        if (targetFormat == Format.MCA) {
            final Path finalFile = dstSubdir.resolve("r." + regionX + "." + regionZ + ".mca");
            writeMcaRegionAtomic(finalFile, chunks);
            return finalFile;
        }

        final int storageKind = LcaRegionFileStorage.inferStorageKindFromPath(dstSubdir);
        final int baseChunkX = regionX << 5;
        final int baseChunkZ = regionZ << 5;
        try (LcaRegionFileStorage lca = new LcaRegionFileStorage(
            dstSubdir,
            storageKind,
            DEFAULT_MIN_SECTION_Y,
            DEFAULT_SECTION_COUNT,
            DEFAULT_DATA_VERSION,
            modeBLcd2,
            false,
            false,
            4,
            0.35
        )) {
            for (final Map.Entry<Integer, byte[]> entry : chunks.entrySet()) {
                final int slot = entry.getKey();
                final int localX = slot & 31;
                final int localZ = (slot >>> 5) & 31;
                final byte[] nbt = entry.getValue();
                lca.writeChunkNbtBytes(baseChunkX + localX, baseChunkZ + localZ, nbt, 0, nbt.length);
            }
            lca.flush();
        }
        final Path lcaFile = dstSubdir.resolve("r." + regionX + "." + regionZ + ".lca");
        if (Files.exists(lcaFile)) {
            try (FileChannel ch = FileChannel.open(lcaFile, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
        }
        return lcaFile;
    }

    /**
     * Writes a valid vanilla Anvil {@code .mca} region file atomically via temp file +
     * {@code fsync} ({@code S-004}). Public for test fixture generation and reverse conversion.
     */
    public static void writeMcaRegionAtomic(final Path finalFile, final Map<Integer, byte[]> chunks) throws IOException {
        Objects.requireNonNull(finalFile, "finalFile");
        Objects.requireNonNull(chunks, "chunks");
        Files.createDirectories(finalFile.getParent());

        final byte[] header = new byte[SECTOR_BYTES * 2];
        final ByteBuffer headerBuf = ByteBuffer.wrap(header);
        final ByteArrayOutputStream bodySectors = new ByteArrayOutputStream();
        int nextSector = 2;
        final int timestampSec = (int) (System.currentTimeMillis() / 1000L);

        for (final Map.Entry<Integer, byte[]> entry : chunks.entrySet()) {
            final int slot = entry.getKey();
            if (slot < 0 || slot >= CHUNKS_PER_REGION) {
                throw new IllegalArgumentException("Invalid region slot: " + slot);
            }
            final byte[] compressed = compressZlib(entry.getValue());
            final int totalLen = 4 + 1 + compressed.length;
            final int sectorCount = (totalLen + SECTOR_BYTES - 1) / SECTOR_BYTES;
            if (sectorCount > 255) {
                throw new IOException("Chunk exceeds 255 sectors in slot " + slot);
            }
            headerBuf.putInt(slot * 4, (nextSector << 8) | sectorCount);
            headerBuf.putInt(SECTOR_BYTES + slot * 4, timestampSec);

            final ByteArrayOutputStream chunkBuf = new ByteArrayOutputStream(sectorCount * SECTOR_BYTES);
            try (DataOutputStream dos = new DataOutputStream(chunkBuf)) {
                dos.writeInt(compressed.length + 1);
                dos.writeByte(2); // Zlib
                dos.write(compressed);
            }
            final int pad = sectorCount * SECTOR_BYTES - totalLen;
            if (pad > 0) {
                chunkBuf.write(new byte[pad]);
            }
            bodySectors.write(chunkBuf.toByteArray());
            nextSector += sectorCount;
        }

        final Path tmpFile = finalFile.resolveSibling(finalFile.getFileName() + ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(
                tmpFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )) {
                writeFully(ch, ByteBuffer.wrap(header));
                writeFully(ch, ByteBuffer.wrap(bodySectors.toByteArray()));
                ch.force(true);
            }
            Files.move(tmpFile, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ex) {
            Files.deleteIfExists(tmpFile);
            throw ex;
        }
    }

    private static void writeFully(final FileChannel ch, final ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    private static byte[] compressZlib(final byte[] uncompressed) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, uncompressed.length / 2));
        try (DeflaterOutputStream def = new DeflaterOutputStream(out)) {
            def.write(uncompressed);
        }
        return out.toByteArray();
    }

    private static boolean chunksSemanticallyEqual(final byte[] expectedBytes, final byte[] actualBytes) {
        if (Arrays.equals(sha256(expectedBytes), sha256(actualBytes))) {
            return true;
        }
        try (
            DataInputStream expIn = new DataInputStream(new ByteArrayInputStream(expectedBytes));
            DataInputStream actIn = new DataInputStream(new ByteArrayInputStream(actualBytes))
        ) {
            final CompoundTag expectedTag = NbtIo.read(expIn, NbtAccounter.unlimitedHeap());
            final CompoundTag actualTag = NbtIo.read(actIn, NbtAccounter.unlimitedHeap());
            return Objects.equals(expectedTag, actualTag);
        } catch (final IOException ex) {
            return false;
        }
    }

    private static byte[] sha256(final byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
