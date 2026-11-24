package dev.leafcraft.goldleaf.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.hash.Hashing;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.placement.AbstractSpreadingStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verification tests for {@code goldleaf-ADR-018} §3 (Opt-In 1024-Bit Secure Seed Protection):
 * default-off vanilla bit-for-bit parity, 1024-bit determinism across all PRNG seams,
 * cross-domain cryptographic separation, {@link WorldOptions#CODEC} round-trip &amp; validation,
 * and fail-closed world immutability ({@code S-004}).
 */
final class SecureSeedProtectionTest {

    private static final long TEST_WORLD_SEED = 1234567890123456789L;

    @AfterEach
    void cleanup() {
        GoldLeafSecureSeed.clearForTests();
        GoldLeafConfig.get().worldgen.secureSeed.enabled = false;
        GoldLeafConfig.get().worldgen.secureSeed.allowUnsafeMigration = false;
    }

    @Test
    void defaultOffConfigPreservesVanillaOutputsBitForBit(@TempDir final Path tempDir) throws Exception {
        final GoldLeafConfig loaded = GoldLeafConfig.load(tempDir.resolve("goldleaf.yml"));
        assertFalse(loaded.worldgen.secureSeed.enabled, "worldgen.secure-seed.enabled must default to false");
        assertFalse(loaded.worldgen.secureSeed.allowUnsafeMigration, "allow-unsafe-migration must default to false");
        assertFalse(GoldLeafSecureSeed.isActive());

        // Vanilla BiomeManager.obfuscateSeed exact match
        final long expectedVanillaZoom = Hashing.sha256().hashLong(TEST_WORLD_SEED).asLong();
        assertEquals(expectedVanillaZoom, BiomeManager.obfuscateSeed(TEST_WORLD_SEED));

        // Vanilla WorldgenRandom decoration + feature + large feature + slime exact match
        final WorldgenRandom rng = new WorldgenRandom(new XoroshiroRandomSource(0L));
        final long deco = rng.setDecorationSeed(TEST_WORLD_SEED, 16, -32);
        final WorldgenRandom vanillaRef = new WorldgenRandom(new XoroshiroRandomSource(TEST_WORLD_SEED));
        final long xScale = vanillaRef.nextLong() | 1L;
        final long zScale = vanillaRef.nextLong() | 1L;
        final long expectedDeco = 16L * xScale + (-32L) * zScale ^ TEST_WORLD_SEED;
        assertEquals(expectedDeco, deco);

        // Slime chunk vanilla formula
        final RandomSource slimeRng = WorldgenRandom.seedSlimeChunk(5, -7, TEST_WORLD_SEED, 987234911L);
        final RandomSource vanillaSlime = RandomSource.createThreadLocalInstance(
            TEST_WORLD_SEED + 5 * 5 * 4987142 + 5 * 5947611 + (-7) * (-7) * 4392871L + (-7) * 389711 ^ 987234911L
        );
        assertEquals(vanillaSlime.nextInt(10), slimeRng.nextInt(10));
    }

    @Test
    void identical1024BitSeedIsDeterministicAndDivergesFromVanillaAcrossAllVectors() {
        final long[] secureSeedA = fixedSeed(0x11L);
        final long[] secureSeedB = fixedSeed(0x22L);

        final long vanillaZoom = Hashing.sha256().hashLong(TEST_WORLD_SEED).asLong();
        final RandomSpreadStructurePlacement villagePlacement = new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.LINEAR, 10387312);
        final ChunkPos vanillaStructure = villagePlacement.getPotentialStructureChunk(TEST_WORLD_SEED, 12, -19);

        GoldLeafSecureSeed.registerWorldSeed(TEST_WORLD_SEED, secureSeedA);
        assertTrue(GoldLeafSecureSeed.isActive());

        // BiomeManager.obfuscateSeed breaks 48->64 bit lift
        final long secureZoomA1 = BiomeManager.obfuscateSeed(TEST_WORLD_SEED);
        final long secureZoomA2 = BiomeManager.obfuscateSeed(TEST_WORLD_SEED);
        assertEquals(secureZoomA1, secureZoomA2);
        assertNotEquals(vanillaZoom, secureZoomA1);

        // Decoration & feature seeds deterministic across runs
        final WorldgenRandom rng1 = new WorldgenRandom(new XoroshiroRandomSource(0L));
        final WorldgenRandom rng2 = new WorldgenRandom(new XoroshiroRandomSource(0L));
        final long deco1 = rng1.setDecorationSeed(TEST_WORLD_SEED, 32, 64);
        final long deco2 = rng2.setDecorationSeed(TEST_WORLD_SEED, 32, 64);
        assertEquals(deco1, deco2);
        rng1.setFeatureSeed(deco1, 3, 6);
        rng2.setFeatureSeed(deco2, 3, 6);
        final long featRollA = rng1.nextLong();
        assertEquals(featRollA, rng2.nextLong());

        // Structure placement deterministic
        final ChunkPos structureA1 = villagePlacement.getPotentialStructureChunk(TEST_WORLD_SEED, 12, -19);
        final ChunkPos structureA2 = villagePlacement.getPotentialStructureChunk(TEST_WORLD_SEED, 12, -19);
        assertEquals(structureA1, structureA2);

        // Pillager outpost reducer deterministic
        final boolean outpostA1 = AbstractSpreadingStructurePlacement.FrequencyReductionMethod.LEGACY_TYPE_1
            .shouldGenerate(TEST_WORLD_SEED, 165745296, 160, -320, 0.2F, null);
        final boolean outpostA2 = AbstractSpreadingStructurePlacement.FrequencyReductionMethod.LEGACY_TYPE_1
            .shouldGenerate(TEST_WORLD_SEED, 165745296, 160, -320, 0.2F, null);
        assertEquals(outpostA1, outpostA2);

        // Slime chunks deterministic
        final long slimeA1 = WorldgenRandom.seedSlimeChunk(11, -23, TEST_WORLD_SEED, 987234911L).nextLong();
        final long slimeA2 = WorldgenRandom.seedSlimeChunk(11, -23, TEST_WORLD_SEED, 987234911L).nextLong();
        assertEquals(slimeA1, slimeA2);

        // Same 64-bit worldSeed with a different 1024-bit secureSeed produces uncorrelated outputs
        GoldLeafSecureSeed.clearForTests();
        GoldLeafSecureSeed.registerWorldSeed(TEST_WORLD_SEED, secureSeedB);

        assertNotEquals(secureZoomA1, BiomeManager.obfuscateSeed(TEST_WORLD_SEED));
        final WorldgenRandom rngB = new WorldgenRandom(new XoroshiroRandomSource(0L));
        final long decoB = rngB.setDecorationSeed(TEST_WORLD_SEED, 32, 64);
        assertNotEquals(deco1, decoB);
        rngB.setFeatureSeed(decoB, 3, 6);
        assertNotEquals(featRollA, rngB.nextLong());
        assertNotEquals(slimeA1, WorldgenRandom.seedSlimeChunk(11, -23, TEST_WORLD_SEED, 987234911L).nextLong());
        assertNotEquals(
            GoldLeafSecureSeed.deriveEndSpikesSeed(secureSeedA),
            GoldLeafSecureSeed.deriveEndSpikesSeed(secureSeedB)
        );
        assertNotEquals(
            GoldLeafSecureSeed.deriveConcentricRingsSeed(secureSeedA),
            GoldLeafSecureSeed.deriveConcentricRingsSeed(secureSeedB)
        );
        assertNotEquals(
            GoldLeafSecureSeed.deriveNoiseRootSeed128(secureSeedA, false),
            GoldLeafSecureSeed.deriveNoiseRootSeed128(secureSeedB, false)
        );
        assertNotEquals(
            GoldLeafSecureSeed.deriveNoiseLegacySeed(secureSeedA, 0L),
            GoldLeafSecureSeed.deriveNoiseLegacySeed(secureSeedB, 0L)
        );
        assertNotEquals(
            GoldLeafSecureSeed.deriveEndIslandSeed(secureSeedA),
            GoldLeafSecureSeed.deriveEndIslandSeed(secureSeedB)
        );
        assertNotEquals(
            GoldLeafSecureSeed.deriveLargeFeatureSeed(secureSeedA, 4, 8),
            GoldLeafSecureSeed.deriveLargeFeatureSeed(secureSeedB, 4, 8)
        );
        // Verify vanilla structure is distinct from at least one of the secure seeds (probabilistically 1 - 1/676^2)
        final ChunkPos structureB = villagePlacement.getPotentialStructureChunk(TEST_WORLD_SEED, 12, -19);
        assertTrue(!vanillaStructure.equals(structureA1) || !vanillaStructure.equals(structureB));
    }

    @Test
    void worldOptionsCodecRoundTripsAndRejectsCorruptSecureSeed() {
        final long[] secureSeed = GoldLeafSecureSeed.generateSecureSeed();
        final WorldOptions plain = new WorldOptions(TEST_WORLD_SEED, true, false);
        assertFalse(plain.goldleaf$hasSecureSeed());
        assertNull(plain.goldleaf$secureSeed());

        // Plain WorldOptions must not write goldleaf_secure_seed key
        final Tag plainTag = WorldOptions.CODEC.codec().encodeStart(NbtOps.INSTANCE, plain).getOrThrow();
        assertTrue(plainTag instanceof CompoundTag compound && !compound.contains("goldleaf_secure_seed"));

        // Secure WorldOptions round-trips cleanly and survives withBonusChest/withStructures/withSeed
        final WorldOptions secured = plain.goldleaf$withSecureSeed(secureSeed);
        assertTrue(secured.goldleaf$hasSecureSeed());
        assertArrayEquals(secureSeed, secured.goldleaf$secureSeed());
        assertArrayEquals(secureSeed, secured.withBonusChest(true).goldleaf$secureSeed());
        assertArrayEquals(secureSeed, secured.withStructures(false).goldleaf$secureSeed());
        assertArrayEquals(secureSeed, secured.withSeed(OptionalLong.of(42L)).goldleaf$secureSeed());

        final Tag encoded = WorldOptions.CODEC.codec().encodeStart(NbtOps.INSTANCE, secured).getOrThrow();
        final WorldOptions decoded = WorldOptions.CODEC.codec().parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(TEST_WORLD_SEED, decoded.seed());
        assertTrue(decoded.goldleaf$hasSecureSeed());
        assertArrayEquals(secureSeed, decoded.goldleaf$secureSeed());

        // Fail closed on corrupt / truncated secure seed in NBT (8 longs instead of 16)
        final CompoundTag corrupt = new CompoundTag();
        corrupt.putLong("seed", TEST_WORLD_SEED);
        final ListTag shortList = new ListTag();
        for (int i = 0; i < 8; i++) {
            shortList.add(LongTag.valueOf(i + 1L));
        }
        corrupt.put("goldleaf_secure_seed", shortList);
        final DataResult<WorldOptions> corruptResult = WorldOptions.CODEC.codec().parse(NbtOps.INSTANCE, corrupt);
        assertTrue(corruptResult.isError(), "Truncated 8-long secure seed must fail closed during Codec parse");

        // Fail closed on all-zero seed
        assertThrows(IllegalArgumentException.class, () -> GoldLeafSecureSeed.validateAndCopy(new long[16]));
        assertThrows(IllegalArgumentException.class, () -> GoldLeafSecureSeed.validateAndCopy(new long[8]));
    }

    @Test
    void worldImmutabilityGuardFailsClosedOnModeMismatchAndReusesAcrossDimensions() {
        final GoldLeafConfig.SecureSeed cfg = new GoldLeafConfig.SecureSeed();
        final WorldOptions plain = new WorldOptions(TEST_WORLD_SEED, true, false);

        // New world with enabled=true generates and registers 1024-bit seed
        cfg.enabled = true;
        cfg.allowUnsafeMigration = false;
        final WorldOptions overworld = GoldLeafSecureSeed.enforceAndResolveWorldOptions("world", false, plain, cfg);
        assertTrue(overworld.goldleaf$hasSecureSeed());
        assertNotNull(GoldLeafSecureSeed.lookup(TEST_WORLD_SEED));

        // New Nether dimension sharing the same 64-bit seed reuses the Overworld's 1024-bit seed
        final WorldOptions nether = GoldLeafSecureSeed.enforceAndResolveWorldOptions("world_nether", false, plain, cfg);
        assertArrayEquals(overworld.goldleaf$secureSeed(), nether.goldleaf$secureSeed());

        // Reloading existing secure world with enabled=true succeeds
        GoldLeafSecureSeed.clearForTests();
        final WorldOptions reloaded = GoldLeafSecureSeed.enforceAndResolveWorldOptions("world", true, overworld, cfg);
        assertArrayEquals(overworld.goldleaf$secureSeed(), reloaded.goldleaf$secureSeed());

        // S-004: Existing secure world with enabled=false MUST fail closed
        cfg.enabled = false;
        assertThrows(
            IllegalStateException.class,
            () -> GoldLeafSecureSeed.enforceAndResolveWorldOptions("world", true, overworld, cfg)
        );

        // S-004: Existing plain world with enabled=true MUST fail closed
        cfg.enabled = true;
        assertThrows(
            IllegalStateException.class,
            () -> GoldLeafSecureSeed.enforceAndResolveWorldOptions("legacy_world", true, plain, cfg)
        );

        // Explicit unsafe migration override allows migrating existing plain world
        cfg.allowUnsafeMigration = true;
        final WorldOptions migrated = GoldLeafSecureSeed.enforceAndResolveWorldOptions("legacy_world", true, plain, cfg);
        assertTrue(migrated.goldleaf$hasSecureSeed());
    }

    private static long[] fixedSeed(final long base) {
        final long[] seed = new long[GoldLeafSecureSeed.SEED_LONGS];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = base * 0x9E3779B97F4A7C15L + (i + 1L) * 0xBF58476D1CE4E5B9L;
        }
        return seed;
    }
}
