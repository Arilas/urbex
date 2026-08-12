package dev.krona.urbex.worldgen;

/**
 * The one block-tag snapshot a running server generates against, and the only thing a
 * {@code /reload} replaces.
 *
 * <p>One slot, one writer. The writer is {@code GenerationSession} on the server thread, at world
 * load and after each successful data-pack reload; every reader is a chunk generation taking
 * {@link #current()} once, at its start, into its {@link ChunkGenContext}. A generation that spans a
 * swap therefore sees one coherent epoch - the one it captured - because the swap replaces what the
 * <em>next</em> chunk picks up and cannot reach into a {@link TagSnapshot} already handed out.</p>
 *
 * <p>That is the same rule {@code RuntimeRepository} used to keep for whole {@link DimensionRuntime}s
 * on a reload, narrowed to the only state a reload can actually change. Rebuilding a level's runtime
 * to refresh a tag also threw away its road field, its heightmaps and every chunk plan it had cached,
 * none of which a tag can affect (issue #128).</p>
 *
 * <p>Shared by every level of a server rather than held per level: block tags come from the server's
 * reloadable resources, so all of its levels are always on the same epoch, and a per-level copy would
 * be a chance for them not to be.</p>
 */
public final class TagEpoch {

    private volatile TagSnapshot current;

    TagEpoch(TagSnapshot initial) {
        this.current = initial;
    }

    /**
     * The epoch to generate against. Read <em>once</em> per chunk, not per block: two reads in one
     * generation are two chances to straddle a reload.
     */
    public TagSnapshot current() {
        return current;
    }

    /** Swaps in a freshly captured epoch. Whatever already holds the old one keeps it. */
    void publish(TagSnapshot snapshot) {
        current = snapshot;
    }
}
