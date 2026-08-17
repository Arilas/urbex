package dev.krona.urbex.api;

import net.minecraft.server.level.ServerLevel;

/**
 * Builds a caller's {@link SiteSpec} for a level, on demand.
 *
 * <p><strong>Experimental.</strong> See {@link UrbexApi} for what that means.</p>
 *
 * <p>This exists because of a timing problem with one honest answer. A spawn claim has to be
 * registered at mod initialisation - before any world exists, because the world's spawn is chosen
 * while it loads - but a {@link SiteField} is almost always seeded from the world, so the spec
 * cannot be built that early. Handing Urbex a factory instead of a spec lets the claim be made
 * before the seed is known and resolved once the level is in hand.</p>
 *
 * <p>Return the <em>same</em> spec your generation path uses, id included. Sites are memoised per
 * {@code (level, spec id)}, so a matching id means the spawn search and the chunks the player walks
 * out into are the same site rather than two that merely look alike.</p>
 */
@FunctionalInterface
public interface SiteSpecFactory {

    /**
     * The spec for {@code level}. Called on the server thread while the level is loading, after its
     * assets are compiled.
     */
    SiteSpec specFor(ServerLevel level);
}
