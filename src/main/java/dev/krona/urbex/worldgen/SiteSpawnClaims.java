package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.api.SiteSpawn;
import dev.krona.urbex.api.SiteSpec;
import dev.krona.urbex.api.SiteSpecFactory;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mods that have asked for a world's spawn to land inside one of their sites.
 *
 * <p>Registered at mod initialisation and never cleared, because a claim is configuration rather
 * than world state: the same mod wants the same thing of the next world it loads. What <em>is</em>
 * world state - the site, its planning context, its caches - is built per level through
 * {@link SiteRuntimes} and dies with that level, so a claim outliving a world costs nothing.</p>
 *
 * <p>Off unless a caller asks. A site that has not claimed spawn changes nothing about where a world
 * puts its players, which is why there is no "none" to configure: the claim either exists or it does
 * not.</p>
 *
 * <h2>Why the search is cheap</h2>
 *
 * <p>A {@link dev.krona.urbex.api.SiteField} is a pure function of the coordinate and is required to
 * be cheap, so the outward scan can ask it about tens of thousands of chunks for the price of some
 * hash arithmetic. Only the handful of coordinates it answers yes to get a {@link ChunkPlan} built,
 * and only the first that matches the caller's preference gets a block read.</p>
 */
public final class SiteSpawnClaims {

    /**
     * How far out to look, in chunks. A site dense enough to be worth spawning in is normally found
     * within a few dozen; this is the point at which "your field does not put anything near the
     * origin" is the more useful answer than searching forever.
     */
    private static final int SEARCH_RADIUS_CHUNKS = 512;

    private static final List<Claim> CLAIMS = new CopyOnWriteArrayList<>();

    /** @param where which kind of site chunk the caller wants to wake up in */
    private record Claim(SiteSpecFactory factory, SiteSpawn where) {}

    private SiteSpawnClaims() {
    }

    /** @see dev.krona.urbex.api.UrbexApi#spawnIn */
    public static void add(SiteSpecFactory factory, SiteSpawn where) {
        CLAIMS.add(new Claim(factory, where));
    }

    public static boolean isEmpty() {
        return CLAIMS.isEmpty();
    }

    /**
     * The chunk-centre position a claimed site offers for {@code level}'s spawn, at the height its
     * ground floor sits, or {@code null} if nothing claimed one or nothing was found.
     *
     * <p>The Y is where the plan says the floor is, not where a block is. Finding somewhere to
     * actually stand from there is {@code SpawnPlacement}'s job - it already owns that check, and it
     * is the only step here that reads a block and so forces a chunk to generate.</p>
     *
     * <p>First claim wins. Two mods both asking to own a world's spawn is a modpack conflict rather
     * than something to resolve by a rule, so it is logged and the first is used.</p>
     */
    @Nullable
    public static Anchor findAnchor(ServerLevel level) {
        if (CLAIMS.isEmpty()) {
            return null;
        }
        GenerationSession session = GenerationSession.current();
        AssetSnapshot assets = session == null ? null : session.assets();
        if (assets == null) {
            return null;
        }
        warnIfContested();
        Claim claim = CLAIMS.get(0);
        SiteSpec spec = claim.factory().specFor(level);
        PlanningContext planning = SiteRuntimes.planningFor(session.sites().site(level, spec, assets));
        Anchor found = search(spec, claim.where(), planning);
        if (found == null) {
            Urbex.getLogger().warn(
                    "Urbex site '{}' claimed the spawn of '{}', but no {} chunk was found within {} "
                            + "chunks of the origin. The world keeps the spawn it would have had.",
                    spec.id(), level.dimension().identifier(), claim.where(), SEARCH_RADIUS_CHUNKS);
        }
        return found;
    }

    /**
     * Where a claimed spawn wants to be.
     *
     * @param pos  the chunk's centre, at the Y the plan puts its ground floor
     * @param site the site that offered it, so a log line can name who moved the spawn
     */
    public record Anchor(BlockPos pos, Identifier site) {}

    private static void warnIfContested() {
        if (CLAIMS.size() > 1) {
            Urbex.getLogger().warn("{} mods have claimed this world's spawn for an Urbex site. The "
                    + "first registered wins; the rest are ignored.", CLAIMS.size());
        }
    }

    /**
     * Spirals out from the origin chunk, asking the caller's field first and the plan only where it
     * says yes.
     */
    @Nullable
    private static Anchor search(SiteSpec spec, SiteSpawn where, PlanningContext planning) {
        for (int ring = 0; ring <= SEARCH_RADIUS_CHUNKS; ring++) {
            for (int cx = -ring; cx <= ring; cx++) {
                for (int cz = -ring; cz <= ring; cz++) {
                    // Only the ring's edge; everything inside it was covered by a smaller ring.
                    if (ring != 0 && Math.abs(cx) != ring && Math.abs(cz) != ring) {
                        continue;
                    }
                    if (!spec.field().isSite(cx, cz)) {
                        continue;
                    }
                    ChunkPlan plan = ChunkPlan.getChunkPlan(planning.coord(cx, cz), planning);
                    if (!matches(plan, where)) {
                        continue;
                    }
                    return new Anchor(
                            new BlockPos((cx << 4) + 8, plan.getCityGroundLevel(), (cz << 4) + 8),
                            spec.id());
                }
            }
        }
        return null;
    }

    private static boolean matches(ChunkPlan plan, SiteSpawn where) {
        if (!plan.isCity()) {
            return false;
        }
        return switch (where) {
            case STREET -> !plan.hasBuilding;
            case BUILDING -> plan.hasBuilding;
        };
    }
}
