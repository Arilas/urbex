package dev.krona.urbex.api;

import dev.krona.urbex.worldgen.GenerationSession;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/**
 * Ask Urbex to build somewhere it would not have chosen.
 *
 * <h2>Experimental</h2>
 *
 * <p>This package may change shape without a deprecation cycle, including between patch releases of
 * Urbex. It is published so that the design can be used and argued with, not because it is settled.
 * Pin the Urbex version you build against.</p>
 *
 * <h2>What a site is</h2>
 *
 * <p>Urbex normally decides for itself where cities go: a perlin field over the whole dimension
 * picks the centres, the terrain heightmap picks how high each chunk sits, and one world style
 * governs the level. A <em>site</em> is the same machinery with three of those answers supplied by
 * another mod instead - where, how high, and how far it may reach - so a mod that carves cave
 * bunkers can have them filled with Urbex buildings at the cavity's depth, in a different world
 * style from the ruins on the surface, without a block escaping the cavity.</p>
 *
 * <p>Everything else about a site is a preset, and a preset is a datapack file. Lighting density,
 * floor counts, cellar counts, ruin chance, corridor chance, road spacing - a site reaches all of
 * them by naming a preset, and reaches any of them individually through
 * {@link SiteSpec.Builder#presetOverrides}.</p>
 *
 * <h2>Urbex dispatches nothing</h2>
 *
 * <p>A site generates when the caller calls {@link UrbexSite#fill} and at no other time. Urbex's own
 * carver-tail hook knows nothing about sites and will not run one. That is deliberate: the caller
 * owns the cavity, so the caller owns the timing.</p>
 *
 * <h2>The shape of a caller</h2>
 *
 * <pre>{@code
 * // once, at mod init
 * static final SiteField FIELD = new BunkerField();
 * static final SiteSpec SPEC = SiteSpec
 *         .builder(Identifier.fromNamespaceAndPath("mymod", "bunkers"),
 *                  Identifier.fromNamespaceAndPath("urbex", "standard"),
 *                  FIELD)
 *         .worldStyle(Identifier.fromNamespaceAndPath("mymod", "bunker_style"))
 *         .window(-40, 20)
 *         .build();
 *
 * // per chunk, from somewhere that holds a WorldGenRegion - the tail of applyCarvers is the one
 * // this was designed for
 * if (FIELD.isSite(chunk.getPos().x(), chunk.getPos().z()) && UrbexApi.isAvailable(level)) {
 *     carveCavity(region, chunk);
 *     UrbexApi.site(level, SPEC).fill(region, chunk);
 * }
 * }</pre>
 *
 * <p>Read {@link SiteField} before writing one. Its purity contract is the one thing here that
 * cannot be relaxed.</p>
 */
public final class UrbexApi {

    private UrbexApi() {
    }

    /** The world style a spec uses when it names none: the one Urbex itself defaults to. */
    public static final Identifier DEFAULT_WORLD_STYLE =
            Identifier.fromNamespaceAndPath("urbex", "standard");

    /** The ground a {@link SiteField} sits at when it does not override {@code groundY}. */
    public static final int DEFAULT_GROUND_Y = -20;

    /** The bottom of a window a spec does not name: deep enough for anything underground. */
    public static final int DEFAULT_MIN_Y = -60;

    /**
     * The top of a window a spec does not name. Below sea level on purpose - the defaults describe
     * something underground, because a site that wanted the surface would not need this API.
     */
    public static final int DEFAULT_MAX_Y = 40;

    /**
     * The site {@code spec} names in {@code level}, built on first use and kept for the life of the
     * world.
     *
     * <p>Cheap enough to call per chunk: the lookup is two hash maps. Two calls with the same
     * {@link SiteSpec#id()} in the same level return the same site, whatever else the specs say -
     * see {@link dev.krona.urbex.worldgen.SiteRuntimes} for why, and for what is logged when they
     * disagree.</p>
     *
     * @throws IllegalStateException if this world has not compiled its assets yet, which is to say
     *         if no level has loaded. Guard with {@link #isAvailable} on any path that could run
     *         that early.
     */
    public static UrbexSite site(ServerLevel level, SiteSpec spec) {
        GenerationSession session = GenerationSession.current();
        AssetSnapshot assets = session == null ? null : session.assets();
        if (assets == null) {
            throw new IllegalStateException("Urbex site '" + spec.id() + "' was requested in '"
                    + level.dimension().identifier() + "' before this world compiled its Urbex "
                    + "assets. A site can only be built once a level has loaded; guard with "
                    + "UrbexApi.isAvailable(level).");
        }
        return session.sites().site(level, spec, assets);
    }

    /**
     * Whether {@link #site} will succeed for {@code level} right now.
     *
     * <p>False before the world's first level load has compiled the asset registries, and false for
     * a level whose runtime has been retired. Both are transient states around a world's lifecycle
     * rather than configuration, so a caller that sees false should skip the chunk rather than
     * report a problem.</p>
     */
    public static boolean isAvailable(ServerLevel level) {
        GenerationSession session = GenerationSession.current();
        return session != null && session.assets() != null
                && GenerationSession.runtimeFor(level) != null;
    }
}
