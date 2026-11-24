package dev.leafcraft.goldleaf.security;

import com.mojang.logging.LogUtils;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Opt-in 1024-bit cryptographic secure-seed engine for GoldLeaf ({@code goldleaf-ADR-018} §3).
 *
 * <p>Replaces predictable 48-bit/64-bit worldgen PRNG seed derivations with domain-separated
 * SHA-512 outputs keyed by a per-world 1024-bit ({@code long[16]}) master seed persisted in
 * {@link WorldOptions}. Uses a lock-free volatile fast-path ({@link #isActive()}) so default-off
 * servers incur near-zero overhead and zero allocations, and enforces fail-closed world
 * immutability ({@code S-004}) so toggling the feature on an existing world never silently
 * corrupts chunk borders.
 */
public final class GoldLeafSecureSeed {

    public static final int SEED_LONGS = 16;
    private static final int SEED_BYTES = SEED_LONGS * Long.BYTES; // 128 bytes = 1024 bits
    private static final int DIGEST_BYTES = 64; // SHA-512
    private static final int SCRATCH_BYTES = SEED_BYTES + 40; // 128B key + domain + up to 4 longs/ints

    // Domain separation constants (unique per PRNG seam)
    private static final int DOMAIN_DECORATION = 0x474C_0001;
    private static final int DOMAIN_FEATURE = 0x474C_0002;
    private static final int DOMAIN_LARGE_FEATURE = 0x474C_0003;
    private static final int DOMAIN_LARGE_FEATURE_SALT = 0x474C_0004;
    private static final int DOMAIN_SLIME_CHUNK = 0x474C_0005;
    private static final int DOMAIN_BIOME_ZOOM = 0x474C_0006;
    private static final int DOMAIN_END_SPIKES = 0x474C_0007;
    private static final int DOMAIN_PILLAGER_OUTPOST = 0x474C_0008;
    private static final int DOMAIN_CONCENTRIC_RINGS = 0x474C_0009;
    private static final int DOMAIN_NOISE_ROOT = 0x474C_000A;
    private static final int DOMAIN_NOISE_LEGACY = 0x474C_000B;
    private static final int DOMAIN_END_ISLAND = 0x474C_000C;

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<Long, long[]> WORLD_SEEDS = new ConcurrentHashMap<>();

    // Fast-path state: volatile boolean + single-entry cache for the common shared-seed case
    private static volatile boolean active;
    private static volatile long fastWorldSeed;
    private static volatile long @Nullable [] fastSecureSeed;

    private static final ThreadLocal<DigestState> THREAD_DIGEST = ThreadLocal.withInitial(DigestState::new);

    private GoldLeafSecureSeed() {}

    public static boolean isActive() {
        return active;
    }

    /**
     * Generates a cryptographically strong 1024-bit ({@code long[16]}) master seed.
     */
    public static long[] generateSecureSeed() {
        final long[] seed = new long[SEED_LONGS];
        boolean nonZero = false;
        while (!nonZero) {
            for (int i = 0; i < SEED_LONGS; i++) {
                final long next = SECURE_RANDOM.nextLong();
                seed[i] = next;
                if (next != 0L) {
                    nonZero = true;
                }
            }
        }
        return seed;
    }

    /**
     * Validates a 1024-bit secure seed array, failing closed on null, wrong length, or all-zero input.
     */
    public static long[] validateAndCopy(final long @Nullable [] seed) {
        if (seed == null || seed.length != SEED_LONGS) {
            throw new IllegalArgumentException(
                "GoldLeaf secure seed must contain exactly " + SEED_LONGS + " longs (1024 bits), got "
                    + (seed == null ? "null" : seed.length)
            );
        }
        boolean nonZero = false;
        for (final long v : seed) {
            if (v != 0L) {
                nonZero = true;
                break;
            }
        }
        if (!nonZero) {
            throw new IllegalArgumentException("GoldLeaf secure seed must not be all zeros");
        }
        return Arrays.copyOf(seed, SEED_LONGS);
    }

    public static long[] fromLongList(final List<Long> longs) {
        if (longs == null || longs.size() != SEED_LONGS) {
            throw new IllegalArgumentException(
                "GoldLeaf secure seed list must contain exactly " + SEED_LONGS + " longs, got "
                    + (longs == null ? "null" : longs.size())
            );
        }
        final long[] arr = new long[SEED_LONGS];
        for (int i = 0; i < SEED_LONGS; i++) {
            final Long value = longs.get(i);
            if (value == null) {
                throw new IllegalArgumentException("GoldLeaf secure seed list contains null element at index " + i);
            }
            arr[i] = value;
        }
        return validateAndCopy(arr);
    }

    public static List<Long> toLongList(final long[] seed) {
        final long[] validated = validateAndCopy(seed);
        final List<Long> out = new ArrayList<>(SEED_LONGS);
        for (final long v : validated) {
            out.add(v);
        }
        return out;
    }

    /**
     * Registers a world's 1024-bit secure seed under its 64-bit {@code WorldOptions#seed()}.
     */
    public static void registerWorldSeed(final long worldSeed, final long[] secureSeed) {
        final long[] copy = validateAndCopy(secureSeed);
        final long[] previous = WORLD_SEEDS.put(worldSeed, copy);
        if (previous != null && !Arrays.equals(previous, copy)) {
            LOGGER.warn(
                "[GoldLeaf-SecureSeed] Conflicting 1024-bit secure seed registered for duplicate 64-bit world seed {}; using latest registration.",
                worldSeed
            );
        }
        fastWorldSeed = worldSeed;
        fastSecureSeed = copy;
        active = true;
    }

    /**
     * Looks up the 1024-bit secure seed for the given 64-bit world seed, or {@code null} if
     * secure seed protection is disabled or the seed is not a registered secure-seed world.
     */
    public static long @Nullable [] lookup(final long worldSeed) {
        if (!active) {
            return null;
        }
        final long[] cached = fastSecureSeed;
        if (cached != null && fastWorldSeed == worldSeed) {
            return cached;
        }
        return WORLD_SEEDS.get(worldSeed);
    }

    /**
     * Clears all registered world seeds (for unit tests only).
     */
    public static void clearForTests() {
        active = false;
        fastSecureSeed = null;
        fastWorldSeed = 0L;
        WORLD_SEEDS.clear();
    }

    /**
     * Enforces fail-closed world immutability ({@code S-004}, {@code goldleaf-ADR-018} §3.2) and
     * returns the resolved {@link WorldOptions} to persist and register.
     *
     * @param worldName human-readable world or dimension name for operator diagnostics
     * @param isExistingWorld {@code true} if the world's {@code world_gen_settings.dat} was loaded from disk
     * @param options the current {@link WorldOptions}
     * @param config the active {@code goldleaf.yml} {@code worldgen.secure-seed} configuration
     * @return resolved {@link WorldOptions} (with newly generated 1024-bit seed if enabled on a new world)
     * @throws IllegalStateException if an existing world's secure-seed mode disagrees with config and
     *     {@code allow-unsafe-migration} is {@code false}
     */
    public static WorldOptions enforceAndResolveWorldOptions(
        final String worldName,
        final boolean isExistingWorld,
        final WorldOptions options,
        final GoldLeafConfig.SecureSeed config
    ) {
        final boolean enabled = config != null && config.enabled;
        final boolean allowUnsafeMigration = config != null && config.allowUnsafeMigration;
        final boolean worldHasSecureSeed = options.goldleaf$hasSecureSeed();

        if (!isExistingWorld) {
            if (!enabled) {
                return options;
            }
            final WorldOptions resolved;
            if (worldHasSecureSeed) {
                resolved = options;
            } else {
                final long[] shared = lookup(options.seed());
                resolved = options.goldleaf$withSecureSeed(shared != null ? shared : generateSecureSeed());
            }
            registerWorldSeed(resolved.seed(), resolved.goldleaf$secureSeed());
            LOGGER.info("[GoldLeaf-SecureSeed] Initialized 1024-bit cryptographic secure seed for new world '{}'.", worldName);
            return resolved;
        }

        if (enabled && worldHasSecureSeed) {
            registerWorldSeed(options.seed(), options.goldleaf$secureSeed());
            return options;
        }
        if (!enabled && !worldHasSecureSeed) {
            return options;
        }

        if (enabled) {
            if (allowUnsafeMigration) {
                LOGGER.warn(
                    "[GoldLeaf-SecureSeed] allow-unsafe-migration=true: attaching new 1024-bit secure seed to existing world '{}' (chunk border discontinuities may occur).",
                    worldName
                );
                final WorldOptions migrated = options.goldleaf$withSecureSeed(generateSecureSeed());
                registerWorldSeed(migrated.seed(), migrated.goldleaf$secureSeed());
                return migrated;
            }
            throw new IllegalStateException(
                "[GoldLeaf-SecureSeed S-004] Fail-closed world immutability guard: goldleaf.yml has "
                    + "worldgen.secure-seed.enabled=true, but existing world '" + worldName
                    + "' was created without a 1024-bit secure seed. Enabling secure-seed on an existing world "
                    + "causes chunk-border cliffs and breaks structure/slime consistency. Set "
                    + "worldgen.secure-seed.enabled=false for this world, or explicitly set "
                    + "worldgen.secure-seed.allow-unsafe-migration=true if you accept chunk discontinuities."
            );
        }

        // !enabled && worldHasSecureSeed
        if (allowUnsafeMigration) {
            LOGGER.warn(
                "[GoldLeaf-SecureSeed] allow-unsafe-migration=true: loading secure-seed world '{}' with worldgen.secure-seed.enabled=false (chunk border discontinuities may occur).",
                worldName
            );
            return options;
        }
        throw new IllegalStateException(
            "[GoldLeaf-SecureSeed S-004] Fail-closed world immutability guard: existing world '" + worldName
                + "' was created with a 1024-bit secure seed, but goldleaf.yml has "
                + "worldgen.secure-seed.enabled=false. Disabling secure-seed on a secure-seed world causes "
                + "chunk-border cliffs and breaks structure/slime consistency. Set "
                + "worldgen.secure-seed.enabled=true, or explicitly set "
                + "worldgen.secure-seed.allow-unsafe-migration=true if you accept chunk discontinuities."
        );
    }

    // ---- Domain-separated SHA-512 derivations ----

    public static long deriveDecorationSeed(final long[] secureSeed, final int chunkX, final int chunkZ) {
        return hashToLong(secureSeed, DOMAIN_DECORATION, chunkX, chunkZ, 0L, 0L, 2);
    }

    public static long deriveFeatureSeed(final long[] secureSeed, final long decorationSeed, final int index, final int step) {
        return hashToLong(secureSeed, DOMAIN_FEATURE, index, step, decorationSeed, 0L, 3);
    }

    public static long deriveLargeFeatureSeed(final long[] secureSeed, final int chunkX, final int chunkZ) {
        return hashToLong(secureSeed, DOMAIN_LARGE_FEATURE, chunkX, chunkZ, 0L, 0L, 2);
    }

    public static long deriveLargeFeatureWithSalt(final long[] secureSeed, final int x, final int z, final int salt) {
        return hashToLong(secureSeed, DOMAIN_LARGE_FEATURE_SALT, x, z, salt, 0L, 3);
    }

    public static long deriveSlimeChunkSeed(final long[] secureSeed, final int chunkX, final int chunkZ, final long salt) {
        return hashToLong(secureSeed, DOMAIN_SLIME_CHUNK, chunkX, chunkZ, salt, 0L, 3);
    }

    public static long deriveBiomeZoomSeed(final long[] secureSeed) {
        return hashToLong(secureSeed, DOMAIN_BIOME_ZOOM, 0, 0, 0L, 0L, 0);
    }

    public static long deriveEndSpikesSeed(final long[] secureSeed) {
        return hashToLong(secureSeed, DOMAIN_END_SPIKES, 0, 0, 0L, 0L, 0);
    }

    public static long derivePillagerOutpostSeed(final long[] secureSeed, final int regionX, final int regionZ) {
        return hashToLong(secureSeed, DOMAIN_PILLAGER_OUTPOST, regionX, regionZ, 0L, 0L, 2);
    }

    public static long deriveConcentricRingsSeed(final long[] secureSeed) {
        return hashToLong(secureSeed, DOMAIN_CONCENTRIC_RINGS, 0, 0, 0L, 0L, 0);
    }

    public static RandomSupport.Seed128bit deriveNoiseRootSeed128(final long[] secureSeed, final boolean useLegacyRandom) {
        final DigestState state = THREAD_DIGEST.get();
        final int len = writeHeader(state.input, secureSeed, DOMAIN_NOISE_ROOT);
        putInt(state.input, len, useLegacyRandom ? 1 : 0);
        digestInto(state, len + 4);
        final long lo = readLong(state.output, 0);
        final long hi = readLong(state.output, 8);
        return new RandomSupport.Seed128bit(lo, hi);
    }

    public static long deriveNoiseLegacySeed(final long[] secureSeed, final long seedOffset) {
        return hashToLong(secureSeed, DOMAIN_NOISE_LEGACY, 0, 0, seedOffset, 0L, 3);
    }

    public static long deriveEndIslandSeed(final long[] secureSeed) {
        return hashToLong(secureSeed, DOMAIN_END_ISLAND, 0, 0, 0L, 0L, 0);
    }

    private static long hashToLong(
        final long[] secureSeed,
        final int domain,
        final int a,
        final int b,
        final long c,
        final long d,
        final int arity
    ) {
        final DigestState state = THREAD_DIGEST.get();
        int offset = writeHeader(state.input, secureSeed, domain);
        if (arity >= 1) {
            putInt(state.input, offset, a);
            offset += 4;
        }
        if (arity >= 2) {
            putInt(state.input, offset, b);
            offset += 4;
        }
        if (arity >= 3) {
            putLong(state.input, offset, c);
            offset += 8;
        }
        if (arity >= 4) {
            putLong(state.input, offset, d);
            offset += 8;
        }
        digestInto(state, offset);
        return readLong(state.output, 0);
    }

    private static int writeHeader(final byte[] buf, final long[] secureSeed, final int domain) {
        int offset = 0;
        for (int i = 0; i < SEED_LONGS; i++) {
            putLong(buf, offset, secureSeed[i]);
            offset += 8;
        }
        putInt(buf, offset, domain);
        return offset + 4;
    }

    private static void digestInto(final DigestState state, final int inputLen) {
        state.sha512.reset();
        state.sha512.update(state.input, 0, inputLen);
        try {
            state.sha512.digest(state.output, 0, DIGEST_BYTES);
        } catch (final DigestException ex) {
            throw new IllegalStateException("SHA-512 digest failed", ex);
        }
    }

    private static void putInt(final byte[] buf, final int off, final int v) {
        buf[off] = (byte) (v >>> 24);
        buf[off + 1] = (byte) (v >>> 16);
        buf[off + 2] = (byte) (v >>> 8);
        buf[off + 3] = (byte) v;
    }

    private static void putLong(final byte[] buf, final int off, final long v) {
        buf[off] = (byte) (v >>> 56);
        buf[off + 1] = (byte) (v >>> 48);
        buf[off + 2] = (byte) (v >>> 40);
        buf[off + 3] = (byte) (v >>> 32);
        buf[off + 4] = (byte) (v >>> 24);
        buf[off + 5] = (byte) (v >>> 16);
        buf[off + 6] = (byte) (v >>> 8);
        buf[off + 7] = (byte) v;
    }

    private static long readLong(final byte[] buf, final int off) {
        return ((buf[off] & 0xFFL) << 56)
            | ((buf[off + 1] & 0xFFL) << 48)
            | ((buf[off + 2] & 0xFFL) << 40)
            | ((buf[off + 3] & 0xFFL) << 32)
            | ((buf[off + 4] & 0xFFL) << 24)
            | ((buf[off + 5] & 0xFFL) << 16)
            | ((buf[off + 6] & 0xFFL) << 8)
            | (buf[off + 7] & 0xFFL);
    }

    private static final class DigestState {
        final MessageDigest sha512;
        final byte[] input = new byte[SCRATCH_BYTES];
        final byte[] output = new byte[DIGEST_BYTES];

        DigestState() {
            try {
                this.sha512 = MessageDigest.getInstance("SHA-512");
            } catch (final NoSuchAlgorithmException ex) {
                throw new IllegalStateException("JVM is missing mandatory SHA-512 MessageDigest", ex);
            }
        }
    }
}
