package dev.krona.urbex.api;

/**
 * Where a mod's sites are, and how high each one sits.
 *
 * <p><strong>Experimental.</strong> See {@link UrbexApi} for what that means.</p>
 *
 * <h2>This must be a pure function of the coordinate</h2>
 *
 * <p>It is tempting to read this as "the mod tells Urbex about the chunk it is filling". It is not.
 * Urbex plans a chunk by reading its <em>neighbours'</em> plans - whether a street continues across
 * the border, whether a doorway is cut through a shared wall, which of two competing stairs wins,
 * whether a multi-chunk building may be accepted here. Those reads reach coordinates the caller has
 * not asked about and may never ask about, on threads the caller does not own, in an order nobody
 * controls.</p>
 *
 * <p>So an implementation must be:</p>
 * <ul>
 *   <li><strong>Pure.</strong> The same coordinate answers the same thing forever. Not "until the
 *       carver runs", not "once the cave is known" - forever, from the first chunk of the world to
 *       the last. An implementation that reads a block, a chunk, a level or any state a generation
 *       pass writes is wrong, and wrong in a way that shows up as streets ending in rock.</li>
 *   <li><strong>Total.</strong> It answers for every coordinate in the dimension, including ones
 *       hundreds of chunks from anything the caller cares about.</li>
 *   <li><strong>Thread-safe.</strong> Called concurrently from the worldgen worker pool.</li>
 *   <li><strong>Cheap.</strong> Called for a neighbourhood per planned chunk, and planned chunks are
 *       cached but neighbours are not asked once. Hash arithmetic and noise are fine; anything that
 *       allocates per call is not.</li>
 * </ul>
 *
 * <p>The natural shape is a deterministic field derived from the world seed - a region grid, a noise
 * threshold, a hashed lattice. The caller's own carver reads the same field, so what gets carved and
 * what gets built agree by construction rather than by timing.</p>
 */
@FunctionalInterface
public interface SiteField {

    /**
     * Whether this mod's site covers the chunk at {@code (chunkX, chunkZ)}.
     *
     * <p>This replaces Urbex's own city noise wholesale. Where it answers {@code false}, a
     * {@link UrbexSite#fill} of that chunk writes nothing at all - not a street, not ground cover,
     * not a terrain correction. Where it answers {@code true}, the chunk is planned exactly as a
     * city chunk is planned, with buildings, roads and everything the preset asks for.</p>
     */
    boolean isSite(int chunkX, int chunkZ);

    /**
     * The Y the site's ground sits at in this chunk, in world coordinates.
     *
     * <p>The floor the first storey stands on; cellars go below it and floors above it, six blocks
     * apart, as everywhere else in Urbex. The default is
     * {@link UrbexApi#DEFAULT_GROUND_Y}, which suits a fixed-depth layer; override it for a field
     * whose depth varies.</p>
     *
     * <p>Only consulted where {@link #isSite} answers {@code true}, but it must still be pure and
     * total: a chunk on the edge of a site reads its neighbours' ground to decide whether a street
     * can slope and where a stair goes.</p>
     */
    default int groundY(int chunkX, int chunkZ) {
        return UrbexApi.DEFAULT_GROUND_Y;
    }
}
