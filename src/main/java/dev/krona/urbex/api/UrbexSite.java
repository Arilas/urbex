package dev.krona.urbex.api;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * One site, live in one level. Obtained from {@link UrbexApi#site}, cheap to hold, and safe to call
 * from the worldgen worker pool.
 *
 * <p><strong>Experimental.</strong> See {@link UrbexApi} for what that means.</p>
 */
public interface UrbexSite {

    /**
     * Builds this site's content into {@code chunk}, if the site covers it.
     *
     * <p>Where {@link SiteField#isSite} answers {@code false} for the chunk, this writes nothing at
     * all and returns {@code false} - not a street, not ground cover, not a terrain correction. A
     * caller may therefore call it for every chunk it sees rather than filtering first, though
     * filtering with the same field is cheaper and is what the example mod does.</p>
     *
     * <h2>Where this can be called from</h2>
     *
     * <p>Anywhere a {@link WorldGenRegion} exists for the chunk: a {@code Feature}, a
     * {@code StructurePiece} that has one, or - the case this API was built for - an injection at
     * the tail of {@code ChunkGenerator.applyCarvers}, which is the stage Urbex itself generates at
     * and the point where the terrain is a pure function of the seed.</p>
     *
     * <p><strong>Not from a {@code WorldCarver}.</strong> A carver is handed a
     * {@code CarvingContext} and a {@code ChunkAccess} and has no region to write through, so it
     * cannot call this. Carve in the carver and fill from the carver tail; both read the same
     * {@link SiteField}, so they agree without having to be ordered.</p>
     *
     * <h2>Calling it twice</h2>
     *
     * <p>Nothing stops you. Urbex's own two dispatch routes are made mutually exclusive by a mark on
     * the chunk, because generating twice means the second pass plans against terrain the first has
     * already rewritten - but that mark is about Urbex's routes, and this method deliberately
     * neither reads nor sets it. A caller that fills one chunk twice has decided to.</p>
     *
     * @return whether anything was generated.
     * @throws dev.krona.urbex.worldgen.ChunkGenerationFailure if generation failed partway. The
     *         chunk must not continue through the pipeline; let it propagate.
     */
    boolean fill(WorldGenRegion region, ChunkAccess chunk);

    /** The spec this site was built from. */
    SiteSpec spec();
}
