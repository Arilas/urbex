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
        // RESERVED - do not delete, even though nothing calls Rng with it today. The ordinal is
        // part of the hash (see at()/atPos()/atSlot(): purpose.ordinal() feeds the mix), so
        // removing this constant would shift RAILWAY and everything after it by one and silently
        // change every world ever generated. If highway placement ever needs an addressed stream
        // again, reuse this constant rather than appending a new one.
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
        TERRAIN_L2,
        EXPLOSION,
        EXPLOSION_MINI,
        RUINS_BARS,
        DAMAGE_VARIANT,
        // Each of these splits a second, logically independent decision off an address that
        // already had one. Two decisions sharing an address and a purpose read the same draw, so
        // one becomes a monotone function of the other - a sphere's block from its radius, a
        // vine's length from where vines start.
        SPHERE_BLOCKS,
        SPHERE_CITY_LEVEL,
        VINES_CONTINUE,
        TERRAIN_FIX_LOWER,
        TERRAIN_FIX_UPPER,
        CITY_STYLE_LOCAL,
        VEGETATION_GROWTH,
        BUILDING_FLOORS,
        BUILDING_LAYOUT,
        // generateRandomVegetation runs four wall passes whose bands overlap at the corner
        // columns. One purpose would have both passes over a corner read the same roll, so a
        // corner would get one effective try at a leaf where an edge gets one - and, since the
        // second pass only starts at the same height when the first added nothing, the two would
        // fail together every time.
        VEGETATION_XMAX,
        VEGETATION_ZMIN,
        VEGETATION_ZMAX,
        // Whether a chunk keeps an explosion it is in range of. The roll is addressed at the
        // explosion's own chunk, not at the chunk observing it: every chunk within the blast
        // radius must reach the same verdict or the crater stops at a chunk border. The two are
        // separate constants because a chunk can roll a main and a mini explosion at one address,
        // and one purpose would make accepting the one imply accepting the other.
        EXPLOSION_ACCEPT,
        EXPLOSION_MINI_ACCEPT,
        // generateVines runs four wall passes. The west pass keeps VINES; the other three take
        // their own constant because the west and north passes address the same block at a chunk's
        // NW corner column - one purpose there would make the two facings the identical roll, so
        // the corner could never have one facing without the other.
        VINES_EAST,
        VINES_NORTH,
        VINES_SOUTH,
        LIGHTING_DENSITY,
        LIGHTING_VARIANT,
        LOOT_DENSITY
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
        return new XoroshiroRandomSource(hashPos(worldSeed, x, y, z, purpose));
    }

    /**
     * A value in {@code [0, bound)} addressed by a block position, without allocating a stream.
     * <p>
     * For the per-block consumers that run in the hot loops - every weighted palette character,
     * every rubble and leaf block. Those must be addressed rather than drawn in sequence: a
     * sequential stream makes the <em>number</em> of draws upstream leak into every pick
     * downstream, so a fill loop that runs two blocks longer re-rolls the rest of the chunk.
     * Allocating a {@link RandomSource} per block to avoid that would cost more than the bug.
     */
    public static int indexAtPos(long worldSeed, int x, int y, int z, Purpose purpose, int bound) {
        long h = hashPos(worldSeed, x, y, z, purpose);
        // Multiply-shift over the top 32 bits: no division, and no modulo bias worth the name.
        return (int) (((h >>> 32) * bound) >>> 32);
    }

    /** A value in {@code [0, 1)} addressed by a block position, without allocating a stream. */
    public static float floatAtPos(long worldSeed, int x, int y, int z, Purpose purpose) {
        long h = hashPos(worldSeed, x, y, z, purpose);
        return (h >>> 40) * 0x1.0p-24f;     // top 24 bits, the same width as nextFloat()
    }

    /**
     * A stream addressed by a chunk and an arbitrary slot within it, for consumers whose draws
     * decide a <em>position</em> and so cannot be addressed by one. The slot must be derived from
     * loop indices, never from a running counter that stops early - the point is that attempt
     * (j, i) draws the same values however many attempts before it were abandoned.
     */
    public static RandomSource atSlot(long worldSeed, int chunkX, int chunkZ, long slot, Purpose purpose) {
        long h = mix(worldSeed);
        h = mix(h ^ (chunkX * X_MULTIPLIER));
        h = mix(h ^ (chunkZ * Z_MULTIPLIER));
        h = mix(h ^ (slot * GOLDEN_GAMMA));
        h = mix(h ^ ((purpose.ordinal() + 1L) * PURPOSE_MULTIPLIER));
        return new XoroshiroRandomSource(h);
    }

    private static long hashPos(long worldSeed, int x, int y, int z, Purpose purpose) {
        long h = mix(worldSeed);
        h = mix(h ^ (x * X_MULTIPLIER));
        h = mix(h ^ (y * GOLDEN_GAMMA));
        h = mix(h ^ (z * Z_MULTIPLIER));
        h = mix(h ^ ((purpose.ordinal() + 1L) * PURPOSE_MULTIPLIER));
        return h;
    }

    /** splitmix64 finalizer. */
    private static long mix(long z) {
        z += GOLDEN_GAMMA;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
