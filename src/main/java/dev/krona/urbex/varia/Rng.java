package dev.krona.urbex.varia;

import dev.krona.urbex.plan.Hash;
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

    /**
     * One independent stream per consumer. Reordering or removing a constant reseeds every
     * consumer from that ordinal on, changing every generated world - do it only deliberately,
     * with both {@code RngTest} golden vectors re-pinned in the same commit. While this mod is
     * unreleased that is an accepted cost; once worlds exist in the wild it becomes a breaking
     * change.
     */
    public enum Purpose {
        BUILDING,
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
        CITY_CENTER,
        CITY_RADIUS,
        CITY_STYLE,
        RAILWAY,
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
        LIGHTING_DENSITY,
        LIGHTING_VARIANT,
        LOOT_DENSITY,
        // The deck a planned primary bridge uses. Addressed at the span's lower endpoint rather
        // than at the chunk being generated, so every chunk of one span draws the same part.
        LARGE_BRIDGE,
        // Which world style a city, scatter area or multichunk area draws from a weighted mix.
        // Only reached when the mix has more than one entry: a world created with a single style
        // never draws here at all, which is what keeps its generation identical to what it was
        // before mixing existed.
        WORLD_STYLE,
        // Whether a marked spawner position keeps its spawner.
        //
        // Last, and that is the whole of why it is here rather than beside LOOT_DENSITY where it
        // belongs by subject. A purpose's ordinal feeds the hash, so a constant added anywhere but
        // the end reseeds every stream below it and rewrites every world that ever generated. Added
        // here, nothing that existed before it moves.
        SPAWNER_DENSITY,
        // Which replacement an unlit light source writes, when it names a weighted list of them.
        //
        // Separate from LIGHTING_VARIANT because one position can consume both: a socket the
        // density roll accepted, whose every placement opportunity then failed, draws a candidate
        // and falls through to its replacement. Derived from one hash they would be the same
        // address twice, tying which replacement appears to which candidate was tried.
        LIGHTING_UNLIT
    }

    /**
     * A fresh stream for {@code purpose} at chunk {@code (chunkX, chunkZ)} in the world with
     * {@code worldSeed}. Cheap enough to call per use site; do not cache the result across chunks.
     */
    public static RandomSource at(long worldSeed, int chunkX, int chunkZ, Purpose purpose) {
        return new XoroshiroRandomSource(Hash.at(worldSeed, chunkX, chunkZ, key(purpose)));
    }

    /**
     * As {@link #at} but keyed on a block position, for consumers that vary within a chunk
     * (deferred light placement, per-spawner mob choice).
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
        return Hash.index(hashPos(worldSeed, x, y, z, purpose), bound);
    }

    /** A value in {@code [0, 1)} addressed by a block position, without allocating a stream. */
    public static float floatAtPos(long worldSeed, int x, int y, int z, Purpose purpose) {
        return Hash.unit(hashPos(worldSeed, x, y, z, purpose));
    }

    /**
     * A stream addressed by a chunk and an arbitrary slot within it, for consumers whose draws
     * decide a <em>position</em> and so cannot be addressed by one. The slot must be derived from
     * loop indices, never from a running counter that stops early - the point is that attempt
     * (j, i) draws the same values however many attempts before it were abandoned.
     */
    public static RandomSource atSlot(long worldSeed, int chunkX, int chunkZ, long slot, Purpose purpose) {
        return new XoroshiroRandomSource(Hash.atSlot(worldSeed, chunkX, chunkZ, slot, key(purpose)));
    }

    /**
     * The seed {@link #atPos} would construct its source from, for hot loops that
     * {@code setSeed} one reused {@code XoroshiroRandomSource} instead of allocating per block.
     */
    public static long posSeed(long worldSeed, int x, int y, int z, Purpose purpose) {
        return hashPos(worldSeed, x, y, z, purpose);
    }

    private static long hashPos(long worldSeed, int x, int y, int z, Purpose purpose) {
        return Hash.atPos(worldSeed, x, y, z, key(purpose));
    }

    /**
     * {@link Hash}'s addressing methods take a raw {@code long} key; ordinals start at zero and
     * zero is not a useful address (it collides with an unaddressed hash), so every purpose is
     * offset by one before reaching {@link Hash}.
     */
    private static long key(Purpose purpose) {
        return purpose.ordinal() + 1L;
    }
}
