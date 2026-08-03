package dev.krona.urbex.plan;

/**
 * The pure mixing behind every addressed random value in Urbex.
 * <p>
 * This lives in {@code plan} rather than in {@code varia} on purpose: dependencies must point
 * <em>into</em> the pure module, never out of it. {@code Rng} is Minecraft-coupled and depends on
 * this; this depends on nothing. Putting it beside {@code Rng} would mean the plan module imported
 * from a package that is not itself pure, and the purity test would keep passing while the property
 * it protects quietly stopped holding.
 * <p>
 * The constants and the order of operations are load-bearing. They reproduce what {@code Rng}
 * produced before the extraction, and {@code RngTest}'s pinned golden vectors fail if a single bit
 * moves.
 */
public final class Hash {

    private Hash() {
    }

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long X_MULTIPLIER = 0x9E3779B97F4A7C15L;
    private static final long Z_MULTIPLIER = 0xC2B2AE3D27D4EB4FL;
    private static final long KEY_MULTIPLIER = 0x165667B19E3779F9L;

    /** Addressed by a 2D cell or chunk coordinate. */
    public static long at(long seed, int x, int z, long key) {
        long h = mix(seed);
        h = mix(h ^ (x * X_MULTIPLIER));
        h = mix(h ^ (z * Z_MULTIPLIER));
        h = mix(h ^ (key * KEY_MULTIPLIER));
        return h;
    }

    /** Addressed by a 3D block position. */
    public static long atPos(long seed, int x, int y, int z, long key) {
        long h = mix(seed);
        h = mix(h ^ (x * X_MULTIPLIER));
        h = mix(h ^ (y * GOLDEN_GAMMA));
        h = mix(h ^ (z * Z_MULTIPLIER));
        h = mix(h ^ (key * KEY_MULTIPLIER));
        return h;
    }

    /** Addressed by a 2D coordinate plus an arbitrary slot within it. */
    public static long atSlot(long seed, int x, int z, long slot, long key) {
        long h = mix(seed);
        h = mix(h ^ (x * X_MULTIPLIER));
        h = mix(h ^ (z * Z_MULTIPLIER));
        h = mix(h ^ (slot * GOLDEN_GAMMA));
        h = mix(h ^ (key * KEY_MULTIPLIER));
        return h;
    }

    /** A value in {@code [0, bound)}. Multiply-shift over the top 32 bits: no division, no bias worth the name. */
    public static int index(long h, int bound) {
        return (int) (((h >>> 32) * bound) >>> 32);
    }

    /** A value in {@code [0, 1)}, using the top 24 bits — the same width as {@code nextFloat()}. */
    public static float unit(long h) {
        return (h >>> 40) * 0x1.0p-24f;
    }

    /** splitmix64 finalizer. */
    public static long mix(long z) {
        z += GOLDEN_GAMMA;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
