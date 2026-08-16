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

    /** The site's ground level at a coordinate; see {@link SiteField#groundY}. */
    public int groundY(int chunkX, int chunkZ) {
        return field.groundY(chunkX, chunkZ);
    }

    /** Whether the site covers a coordinate; see {@link SiteField#isSite}. */
    public boolean covers(int chunkX, int chunkZ) {
        return field.isSite(chunkX, chunkZ);
    }
}
