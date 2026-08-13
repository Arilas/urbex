package dev.krona.urbex.setup;

import dev.krona.urbex.Urbex;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How the create-world screen tells the integrated server what the player chose.
 *
 * <p>One slot, one writer (the screen), one reader (the first level load of the server it starts).
 * What it replaces is three {@code public static} fields on {@code Config} - {@code presetFromClient},
 * {@code worldStyleMixFromClient}, {@code overridesFromClient} - written individually from the GUI,
 * read individually from a worldgen worker, and cleared individually in three places. Nothing said
 * they belonged together, so "is a selection pending" was spelled three ways and a clear that missed
 * one field left a partial selection behind (issues #73, #130).</p>
 *
 * <h2>What this is not</h2>
 *
 * <p><strong>It is not client/server synchronization.</strong> It works because the create-world
 * screen and the integrated server it starts are the same process. A dedicated server has no client
 * to hear from and never reads this: its operator names a preset in
 * {@code <world>/serverconfig/urbex.json}, and the world records it on first load like any other.
 * A client connecting to a dedicated server neither publishes nor learns the server's selection.</p>
 *
 * <p>That gap is what issue #73 is about, and closing it needs a configuration-phase handshake -
 * a protocol, with a version on the wire and a compatibility answer for a client that does not have
 * the mod. The version below is the first half of that: it is checked on read, so a future
 * handshake can be introduced knowing that a stale in-process publication cannot be mistaken for
 * one. Until then, this boundary is honest about being singleplayer-only rather than looking like
 * a sync that quietly does not work.</p>
 */
public final class WorldSelectionHandoff {

    /**
     * The shape of what is handed over. Bump when {@link WorldSelection}'s meaning changes in a way
     * a reader must not guess about - a new component whose absence is not the same as a default, or
     * a changed interpretation of the patch.
     */
    public static final int VERSION = 1;

    private record Handoff(int version, WorldSelection selection) {}

    /**
     * Written on the render thread by the create-world screen, read on a worldgen worker by the
     * level load. Atomic so a reader sees a whole selection or none - which is exactly what three
     * separate fields could not promise.
     */
    private static final AtomicReference<Handoff> SLOT = new AtomicReference<>();

    private WorldSelectionHandoff() {
    }

    /** Publishes the screen's choice for the server it is about to start. */
    public static void publish(WorldSelection selection) {
        SLOT.set(new Handoff(VERSION, selection));
    }

    /**
     * Drops whatever was published.
     * <p>
     * Called when the screen closes without creating a world. A publication that outlives its screen
     * is how an abandoned world creation used to reach the <em>next</em> world loaded in the same
     * session (issue #113); {@link WorldSelectionResolver} refuses to apply one to a world that
     * already has a record, but this is where it stops existing.
     */
    public static void discard() {
        SLOT.set(null);
    }

    /**
     * What the screen published, or null. Reading does not consume it: the preset cache it feeds is
     * rebuilt when a world's config overrides are applied, and a selection that vanished on first
     * read would not survive that.
     */
    @Nullable
    public static WorldSelection pending() {
        Handoff handoff = SLOT.get();
        if (handoff == null) {
            return null;
        }
        if (handoff.version() != VERSION) {
            // Unreachable in one process, where both sides are the same build. It is checked anyway
            // because that stops being true the moment this becomes a wire protocol, and a version
            // nobody checks is a version that does not exist.
            Urbex.getLogger().error("Ignoring a world selection published at version {}; this build "
                    + "speaks version {}.", handoff.version(), VERSION);
            return null;
        }
        return handoff.selection();
    }

    /** Whether anything is published. */
    public static boolean isPending() {
        return SLOT.get() != null;
    }
}
