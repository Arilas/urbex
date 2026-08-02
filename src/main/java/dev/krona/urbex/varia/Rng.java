package dev.krona.urbex.varia;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * The only source of randomness during world generation.
 * <p>
 * Randomness is <em>addressed</em>, not sequential: a stream is a pure function of the world
 * seed, a chunk coordinate and a {@link Purpose}. Two consequences matter. Generation order can
 * never influence output, so a world is reproducible from its seed; and adding a new consumer
 * can never perturb an existing one, because it takes a new {@link Purpose} rather than a
 * further draw from a shared stream.
 * <p>
 * There must be no other way to obtain randomness inside generation. A {@code new Random(...)}
 * or a shared {@code RandomSource} field is a bug.
 */
public final class Rng {

    private Rng() {
    }

    /** One independent stream per consumer. Never reorder or remove constants: doing so changes every world. */
    public enum Purpose {
        BUILDING,
        STREET,
        MULTI,
        PARTS,
        RUINS,
        RUBBLE,
        LEAVES,
        DEBRIS,
        STUFF,
        SPAWNERS,
        LOOT,
        VEGETATION,
        DAMAGE,
        VINES,
        CITY_CENTER,
        CITY_RADIUS,
        CITY_STYLE,
        HIGHWAY,
        RAILWAY,
        SPHERE,
        SCATTERED,
        // Added after the first release of this enum. New consumers append here; they never
        // reorder what is above them, so an existing world keeps generating what it did.
        PALETTE,
        NOISE,
        SHAPE,
        TERRAIN_L1,
        TERRAIN_L2
    }

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long X_MULTIPLIER = 0x9E3779B97F4A7C15L;
    private static final long Z_MULTIPLIER = 0xC2B2AE3D27D4EB4FL;
    private static final long PURPOSE_MULTIPLIER = 0x165667B19E3779F9L;

    /**
     * A fresh stream for {@code purpose} at chunk {@code (chunkX, chunkZ)} in the world with
     * {@code worldSeed}. Cheap enough to call per use site; do not cache the result across chunks.
     */
    public static RandomSource at(long worldSeed, int chunkX, int chunkZ, Purpose purpose) {
        long h = mix(worldSeed);
        h = mix(h ^ (chunkX * X_MULTIPLIER));
        h = mix(h ^ (chunkZ * Z_MULTIPLIER));
        h = mix(h ^ ((purpose.ordinal() + 1L) * PURPOSE_MULTIPLIER));
        return new XoroshiroRandomSource(h);
    }

    /**
     * As {@link #at} but keyed on a block position, for consumers that vary within a chunk
     * (per-block vine placement, per-spawner mob choice).
     */
    public static RandomSource atPos(long worldSeed, int x, int y, int z, Purpose purpose) {
        long h = mix(worldSeed);
        h = mix(h ^ (x * X_MULTIPLIER));
        h = mix(h ^ (y * GOLDEN_GAMMA));
        h = mix(h ^ (z * Z_MULTIPLIER));
        h = mix(h ^ ((purpose.ordinal() + 1L) * PURPOSE_MULTIPLIER));
        return new XoroshiroRandomSource(h);
    }

    /** splitmix64 finalizer. */
    private static long mix(long z) {
        z += GOLDEN_GAMMA;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
