package dev.krona.urbex.worldgen;

import dev.krona.urbex.api.SiteField;
import net.minecraft.resources.Identifier;

/**
 * What makes a {@link PlanningContext} a caller's rather than the level's.
 *
 * <p>A <em>site</em> is a patch of Urbex city that another mod asked for: its own preset, its own
 * world style, its own ground level and a vertical window it may not leave. The motivating case is
 * a bunker in a cave - the mod carves the cavity, then asks Urbex to fill it, in a different world
 * style from the ruins overhead.</p>
 *
 * <p>Everything about a site that planning needs is here, and it is deliberately small. A site is
 * not a new kind of generation: it is the ordinary planning path with three of its inputs answered
 * by the caller instead of by the dimension.</p>
 *
 * <ul>
 *   <li><em>Where the city is</em> - {@link CityField#isCityRaw} asks the field instead of the
 *       perlin city rarity map.</li>
 *   <li><em>How high it sits</em> - {@code ChunkPlan}'s ground level comes from the field instead of
 *       the preset's fixed one, and there are no terrain height bands underground, so
 *       {@code cityLevel} is always 0.</li>
 *   <li><em>How far it may reach</em> - {@link #minY} and {@link #maxY}, enforced twice: the
 *       {@link LevelShape} a site plans against is clamped to them, so buildings are planned to fit
 *       rather than cut off; and {@link ChunkBuffer} refuses every write outside them, so what is
 *       planned is not what the guarantee rests on.</li>
 * </ul>
 *
 * <p>A null binding on a {@code PlanningContext} is a level generating for itself, which is every
 * chunk Urbex has ever generated. Each of the six places that consult this is a guarded branch whose
 * null side is exactly what the code did before sites existed.</p>
 *
 * @param id    the site's identity, as the caller named it. Diagnostics only - what separates one
 *              site's cached plans from another's is that they hold different
 *              {@link DimensionCaches}, not this.
 * @param field where the caller's sites are. Pure, total and thread-safe; see {@link SiteField}.
 * @param minY  the lowest block Y this site may write at, inclusive.
 * @param maxY  the highest block Y this site may write at, inclusive.
 */
public record SiteBinding(Identifier id, SiteField field, int minY, int maxY) {

    public SiteBinding {
        if (maxY < minY) {
            throw new IllegalArgumentException(
                    "Urbex site '" + id + "' has a window whose top (" + maxY
                            + ") is below its bottom (" + minY + ")");
        }
    }

    /**
     * Which six-block band above {@link #minY} this chunk's ground sits in.
     *
     * <p><strong>This, and not the ground level, is how a site's height reaches planning.</strong>
     * The distinction is not a detail. {@code cityLevel} is the number every height comparison in
     * Urbex is written against - whether a doorway can be cut through a shared wall, whether a
     * street may slope, which of two stairs wins, whether a bridge has anything to land on. A
     * dimension's {@code groundLevel} is one constant for the whole world, so all of the variation
     * lives in {@code cityLevel} and those comparisons are total.</p>
     *
     * <p>Putting a site's per-chunk height in {@code groundLevel} instead left every one of them
     * reading {@code cityLevel == 0} for two chunks twenty blocks apart, concluding they were level,
     * and cutting doors between them accordingly. The height has to travel down the channel built to
     * carry it.</p>
     *
     * <p>The cost is that a site's ground is quantised to {@link CityGenerator#FLOORHEIGHT}, which
     * is what {@link SiteField#groundY} documents. That is not an arbitrary rounding: six blocks is
     * a storey, and a city whose grounds differ by less than one storey has no way to say so.</p>
     */
    public int cityLevelAt(int chunkX, int chunkZ) {
        return Math.floorDiv(field.groundY(chunkX, chunkZ) - minY, CityGenerator.FLOORHEIGHT);
    }

    /**
     * The Y this chunk's ground floor actually sits at: {@link SiteField#groundY} snapped down to
     * the storey lattice rising from {@link #minY}.
     *
     * <p>What the terrain sampler reports and what the buildings stand on, so the two cannot
     * disagree - a heightmap saying one thing while {@code getCityGroundLevel()} says another is a
     * building floating over its own corrected terrain, or buried in it.</p>
     */
    public int effectiveGroundY(int chunkX, int chunkZ) {
        return minY + cityLevelAt(chunkX, chunkZ) * CityGenerator.FLOORHEIGHT;
    }

    /** Whether the site covers a coordinate; see {@link SiteField#isSite}. */
    public boolean covers(int chunkX, int chunkZ) {
        return field.isSite(chunkX, chunkZ);
    }
}
