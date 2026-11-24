package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LcaRegionStorageProviderTest {

    private static CompoundTag sampleChunkTag(final int xPos, final int zPos) {
        final CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 4189);
        root.putInt("xPos", xPos);
        root.putInt("zPos", zPos);
        root.putInt("yPos", -4);
        root.putString("Status", "minecraft:full");
        root.putLong("LastUpdate", 123456L);
        root.putLong("InhabitedTime", 789L);

        final ListTag sections = new ListTag();
        final CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        final CompoundTag blockStates = new CompoundTag();
        final ListTag palette = new ListTag();
        final CompoundTag stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        palette.add(stone);
        blockStates.put("palette", palette);
        section.put("block_states", blockStates);
        sections.add(section);
        root.put("sections", sections);
        return root;
    }

    private static CompoundTag sampleEntityOrPoiTag(final String key, final int x, final int z) {
        final CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 4189);
        root.putIntArray("Position", new int[]{x, z});
        root.putString("GoldLeafMarker", key + ":" + x + "," + z);
        return root;
    }

    @Test
    void roundTripsRegionEntitiesAndPoiLosslesslyAcrossColdReopen(@TempDir final Path worldDir) throws Exception {
        final GoldLeafConfig.Storage cfg = new GoldLeafConfig.Storage();
        cfg.format = "lca";
        cfg.lcaMode = "mode-a";
        cfg.lcaModeBLcd2 = false;
        cfg.autoMigrateMca = false;

        final String[] folders = {"region", "entities", "poi"};
        for (final String folderName : folders) {
            final Path folder = worldDir.resolve(folderName);
            Files.createDirectories(folder);

            final CompoundTag expected = "region".equals(folderName)
                ? sampleChunkTag(3, -5)
                : sampleEntityOrPoiTag(folderName, 3, -5);

            try (LcaRegionStorageProvider writer = LcaRegionStorageProvider.open(folder, cfg)) {
                final MoonriseRegionFileIO.RegionDataController.WriteData writeData =
                    writer.startWrite(3, -5, expected);
                assertEquals(MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE, writeData.result());
                writer.finishWrite(3, -5, writeData);
                writer.flush();
            }

            // Confirm native .lca file was written and no .mca file was created
            assertTrue(Files.isRegularFile(folder.resolve("r.0.-1.lca")), "Expected .lca archive in " + folderName);
            assertFalse(Files.exists(folder.resolve("r.0.-1.mca")), "Did not expect .mca archive in " + folderName);

            // Cold reopen and verify bit-exact NBT equality (L_inf = 0)
            try (LcaRegionStorageProvider reader = LcaRegionStorageProvider.open(folder, cfg)) {
                final MoonriseRegionFileIO.RegionDataController.ReadData readData = reader.readData(3, -5);
                assertEquals(MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.HAS_DATA, readData.result());
                final CompoundTag actual = reader.finishRead(3, -5, readData);
                assertNotNull(actual);
                assertEquals(expected, actual, "NBT mismatch after cold reopen in " + folderName);

                // Delete path via Moonrise WriteData.DELETE
                final MoonriseRegionFileIO.RegionDataController.WriteData deleteData =
                    reader.startWrite(3, -5, null);
                assertEquals(MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.DELETE, deleteData.result());
                reader.finishWrite(3, -5, deleteData);

                final MoonriseRegionFileIO.RegionDataController.ReadData afterDelete = reader.readData(3, -5);
                assertEquals(MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.NO_DATA, afterDelete.result());
                assertNull(reader.read(3, -5));

                // S-002: idempotent close
                reader.close();
                reader.close();
            }
        }
    }

    @Test
    void storageProviderHoldsZeroTickThreadOrServerLevelReferences() {
        for (final Field field : LcaRegionStorageProvider.class.getDeclaredFields()) {
            assertFalse(Level.class.isAssignableFrom(field.getType()),
                "LcaRegionStorageProvider must not hold Level/ServerLevel reference: " + field.getName());
            assertFalse(ServerLevel.class.isAssignableFrom(field.getType()),
                "LcaRegionStorageProvider must not hold ServerLevel reference: " + field.getName());
        }
    }

    @Test
    void goldLeafConfigValidatesStorageFormatFailClosed(@TempDir final Path tempDir) throws Exception {
        final Path validLca = tempDir.resolve("valid-lca.yml");
        Files.writeString(validLca, """
            storage:
              format: LCA
              lca-mode-b-lcd2: true
              max-open-regions: 64
            """);
        final GoldLeafConfig loaded = GoldLeafConfig.load(validLca);
        assertEquals("lca", loaded.storage.format);
        assertTrue(loaded.storage.lcaModeBLcd2);
        assertEquals(64, loaded.storage.maxOpenRegions);

        final Path invalidFormat = tempDir.resolve("invalid-format.yml");
        Files.writeString(invalidFormat, """
            storage:
              format: invalid-backend
            """);
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(invalidFormat));

        final Path invalidOpenLimit = tempDir.resolve("invalid-limit.yml");
        Files.writeString(invalidOpenLimit, """
            storage:
              format: lca
              max-open-regions: 0
            """);
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(invalidOpenLimit));

        final Path invalidMode = tempDir.resolve("invalid-mode.yml");
        Files.writeString(invalidMode, """
            storage:
              format: lca
              lca-mode: turbo-invalid
            """);
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.load(invalidMode));
    }

    @Test
    void nonDestructiveMcaAutoDetectionPreservesExistingMcaWorlds(@TempDir final Path worldDir) throws Exception {
        final GoldLeafConfig.Storage cfg = new GoldLeafConfig.Storage();
        cfg.format = "lca";
        cfg.autoMigrateMca = false;

        // Fresh empty folder -> uses .lca
        final Path freshRegion = worldDir.resolve("fresh_region");
        Files.createDirectories(freshRegion);
        assertTrue(RegionStorageProvider.shouldUseLcaForFolder(freshRegion, cfg));

        // Existing vanilla .mca folder with no .lca -> preserves .mca non-destructively
        final Path legacyRegion = worldDir.resolve("legacy_region");
        Files.createDirectories(legacyRegion);
        Files.write(legacyRegion.resolve("r.0.0.mca"), new byte[]{0});
        assertFalse(RegionStorageProvider.shouldUseLcaForFolder(legacyRegion, cfg));

        // Enabling auto-migrate-mca switches legacy folder to .lca provider
        cfg.autoMigrateMca = true;
        assertTrue(RegionStorageProvider.shouldUseLcaForFolder(legacyRegion, cfg));
    }

    @Test
    void modeAAndModeBKeepStandardLcaCompressionWithoutForcingFlatBitPack(@TempDir final Path worldDir) throws Exception {
        final Path regionDirA = worldDir.resolve("mode_a").resolve("region");
        final Path regionDirB = worldDir.resolve("mode_b").resolve("region");
        final Path entitiesDirA = worldDir.resolve("mode_a").resolve("entities");
        Files.createDirectories(regionDirA);
        Files.createDirectories(regionDirB);
        Files.createDirectories(entitiesDirA);

        final GoldLeafConfig.Storage cfgA = new GoldLeafConfig.Storage();
        cfgA.format = "lca";
        cfgA.lcaMode = "mode-a";
        cfgA.lcaModeBLcd2 = false;
        try (LcaRegionStorageProvider providerA = LcaRegionStorageProvider.open(regionDirA, cfgA)) {
            assertFalse(providerA.delegate().isDirectFlatBitPack(), "Standard Mode A must preserve Delta-Roaring/H3/v3 disk compression");
        }
        try (LcaRegionStorageProvider entitiesA = LcaRegionStorageProvider.open(entitiesDirA, cfgA)) {
            assertFalse(entitiesA.delegate().isDirectFlatBitPack(), "Entities folder must not enable voxel directFlatBitPack");
        }

        final GoldLeafConfig.Storage cfgB = new GoldLeafConfig.Storage();
        cfgB.format = "lca";
        cfgB.lcaMode = "mode-b";
        cfgB.lcaModeBLcd2 = true;
        try (LcaRegionStorageProvider providerB = LcaRegionStorageProvider.open(regionDirB, cfgB)) {
            assertFalse(providerB.delegate().isDirectFlatBitPack(), "Mode B region storage must keep directFlatBitPack disabled");
        }
    }
}
