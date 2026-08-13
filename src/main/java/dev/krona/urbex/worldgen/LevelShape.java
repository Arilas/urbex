package dev.krona.urbex.worldgen;

import dev.krona.urbex.varia.Tools;
import net.minecraft.world.level.LevelReader;

/**
 * The three numbers planning and generation ask a dimension for: how deep it goes, how high it goes,
 * and where its water sits.
 *
 * <p>All three are fixed for the life of a dimension - {@code minY} and {@code maxY} come from its
 * dimension type, {@code seaLevel} from its chunk generator - so they are a value resolved once when
 * the level's runtime is built, not three calls made per block.</p>
 *
 * <p>They are also the whole of what the planning path wanted {@code IDimensionInfo.getWorld()} for.
 * Roughly thirty call sites read {@code provider.getWorld().getMaxY()} or
 * {@code Tools.getSeaLevel(provider.getWorld())}, each of them holding a whole
 * {@link net.minecraft.world.level.WorldGenLevel} to reach one {@code int}; and each of them a place
 * the world-creation preview would have thrown, because its {@code getWorld()} answers {@code null}.
 * Separating the numbers from the level is what lets the preview answer them honestly instead of
 * being kept away from the code that asks (issue #129).</p>
 *
 * @param minY      the lowest block Y this dimension has
 * @param maxY      the highest block Y this dimension has, inclusive
 * @param seaLevel  the chunk generator's sea level
 */
public record LevelShape(int minY, int maxY, int seaLevel) {

    /**
     * The vanilla overworld's shape, which the world-creation preview uses.
     *
     * <p>A preview runs before any level exists, so there is nothing to ask. This is a guess, but a
     * defensible one - the preview draws the overworld - and it is the same guess for every preview,
     * which is what matters: the alternative is not a better number but an exception, which is what
     * a null level produced.</p>
     */
    public static final LevelShape VANILLA_OVERWORLD = new LevelShape(-64, 319, 63);

    public LevelShape {
        if (maxY < minY) {
            throw new IllegalArgumentException("Level shape has maxY " + maxY + " below minY " + minY);
        }
    }

    /** Resolves the shape of a real level. */
    public static LevelShape of(LevelReader level) {
        return new LevelShape(level.getMinY(), level.getMaxY(), Tools.getSeaLevel(level));
    }

    /**
     * One past the highest block: the exclusive upper bound most call sites actually want, and which
     * every one of them used to spell {@code getMaxY() + 1}.
     */
    public int maxBuildHeight() {
        return maxY + 1;
    }

    /** The lowest section index, for a loop over sections rather than blocks. */
    public int minSection() {
        return minY >> 4;
    }

    /** The highest section index, inclusive. */
    public int maxSection() {
        return maxY >> 4;
    }
}
