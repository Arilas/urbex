package dev.krona.urbex.setup;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Which of the three places a world's selection can come from wins.
 *
 * <p>A pure function of its three arguments: no level, no saved data, no registries, no static
 * state. The precedence itself is unchanged - what changes is that it can be read in one place and
 * exercised without a server (issue #130). It used to be interleaved with reading saved data,
 * writing saved data, parsing identifiers, gating world-style mixes and resolving against the live
 * registries, in one method reached from a worldgen worker.</p>
 */
public final class WorldSelectionResolver {

    private WorldSelectionResolver() {
    }

    /**
     * A resolved selection, and whether the world should record it.
     *
     * <p>Everything a world is <em>given</em> is recorded - both a client publication and the global
     * config's own selection. Only a selection read back out of the world's own saved data is not,
     * because that is already where the record would go.</p>
     *
     * <p>The configured selection used to be exempt, on the grounds that the global config is "a
     * default a player can change between sessions and expect to take effect" (issue #203). That
     * reasoning holds for a runtime setting like {@code todoQueueSize}. It does not hold for a
     * worldgen selection: terrain is written once, so a config edit reaching a world that already
     * exists leaves it half one preset and half another. It also left a world created on a modpack's
     * {@code selectedPreset} with an empty {@code preset} key, which is what the vanilla Re-Create
     * flow reads - so re-creating such a world restored nothing and dropped the player on Disabled
     * (issue #202).</p>
     */
    public record Resolution(WorldSelection selection, boolean persist) {}

    /**
     * @param published    what the create-world screen handed the integrated server, or null
     * @param saved        what this world already recorded, or null if it recorded nothing
     *                     <em>or</em> if what it recorded will not parse
     * @param hasRecord    whether this world recorded anything at all. Separate from
     *                     {@code saved != null} on purpose: a world whose recorded selection is
     *                     corrupt is still a world that was created once, and must not have a stale
     *                     publication written over it - see below.
     * @param configured   the global config's own selection, or null. Overworld-only: it is the
     *                     default for installs that never open the Cities tab.
     * @param overworld    whether the level being resolved is the overworld
     */
    public static Optional<Resolution> resolve(@Nullable WorldSelection published,
                                               @Nullable WorldSelection saved,
                                               boolean hasRecord,
                                               @Nullable WorldSelection configured,
                                               boolean overworld) {
        // A world that already recorded a choice keeps it, even with a client selection published.
        // The published selection is how the create-world screen hands its choice to the integrated
        // server, so it is only ever meant for a world being created - which by definition has no
        // saved choice yet. Applying it to a world that has one is only reachable when it outlived
        // the screen that set it (issue #113: abandon world creation, then load an existing world),
        // and the cost of that was not a wrong preset for one session but a permanent one, because
        // a published selection is written into the world's saved data. PresetSelection
        // .discardPublication clears it at the source; this makes the overwrite unreachable even if
        // some future path forgets to.
        if (published != null && !hasRecord) {
            return Optional.of(new Resolution(published, true));
        }
        if (saved != null) {
            return Optional.of(new Resolution(saved, false));
        }
        // Gated on hasRecord as well, not only on saved: a world that recorded a selection which
        // will not parse gets no selection at all rather than the global default. Substituting a
        // different preset into a world that was created with one is the expensive kind of wrong -
        // the terrain is written once - and the log line naming the malformed id is the thing the
        // player can act on. An unrecorded world has nothing to contradict.
        //
        // Recorded, like a publication: this is the one and only load at which the config's default
        // reaches this world, and freezing it here is what stops the next config edit reaching it
        // too. See Resolution.
        if (overworld && !hasRecord && configured != null) {
            return Optional.of(new Resolution(configured, true));
        }
        return Optional.empty();
    }
}
