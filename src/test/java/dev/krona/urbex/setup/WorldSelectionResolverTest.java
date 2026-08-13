package dev.krona.urbex.setup;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of the three sources of a world's selection wins.
 * <p>
 * The precedence is unchanged; what is new is that it can be exercised at all. It used to be
 * interleaved with reading saved data, writing saved data, parsing identifiers, gating world-style
 * mixes and resolving ids against the live registries, inside one method reached from a worldgen
 * worker - so the only way to see it was to run a server (issue #130).
 */
class WorldSelectionResolverTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final WorldSelection PUBLISHED = selection("urbex:published");
    private static final WorldSelection SAVED = selection("urbex:saved");
    private static final WorldSelection CONFIGURED = selection("urbex:configured");

    @Test
    void aNewWorldTakesWhatTheCreateWorldScreenPublished() {
        WorldSelectionResolver.Resolution resolution =
                resolve(PUBLISHED, null, false, CONFIGURED, true).orElseThrow();

        assertEquals(PUBLISHED, resolution.selection());
        assertTrue(resolution.persist(), "and records it, so the next load does not depend on the "
                + "screen's leftovers");
    }

    @Test
    void aWorldThatRecordedAChoiceKeepsIt() {
        WorldSelectionResolver.Resolution resolution =
                resolve(PUBLISHED, SAVED, true, CONFIGURED, true).orElseThrow();

        assertEquals(SAVED, resolution.selection());
        assertFalse(resolution.persist(), "it is already where it would be written");
    }

    /**
     * The #113 corner, and the reason {@code hasRecord} is a separate argument. A world whose
     * recorded selection will not parse is still a world that was created once; treating it as
     * unrecorded would let a publication that outlived the create-world screen be written over it -
     * permanently, because a publication is persisted - or let the global default silently replace
     * the preset that world's terrain was written from.
     */
    @Test
    void aWorldWithACorruptRecordGetsNoSelectionRatherThanSomebodyElses() {
        assertTrue(resolve(PUBLISHED, null, true, CONFIGURED, true).isEmpty(),
                "not the stale publication, which would be written into the save");
        assertTrue(resolve(null, null, true, CONFIGURED, true).isEmpty(),
                "and not the global default either - the log line naming the malformed id is what "
                        + "the player can act on");
    }

    @Test
    void theConfigsOwnSelectionIsTheOverworldsDefault() {
        WorldSelectionResolver.Resolution resolution =
                resolve(null, null, false, CONFIGURED, true).orElseThrow();

        assertEquals(CONFIGURED, resolution.selection());
        assertFalse(resolution.persist(),
                "a default a player can change between sessions must not be frozen into the world");
    }

    @Test
    void theConfigsOwnSelectionDoesNotReachOtherDimensions() {
        assertTrue(resolve(null, null, false, CONFIGURED, false).isEmpty(),
                "selectedPreset is the overworld's; other dimensions are named by "
                        + "dimensionsWithPresets");
    }

    @Test
    void aWorldWithNoSelectionAnywhereHasNone() {
        assertTrue(resolve(null, null, false, null, true).isEmpty());
    }

    @Test
    void aSavedSelectionReachesEveryDimensionNotJustTheOverworld() {
        // Unlike the config's own selection: a saved choice is this world's, and the nether entry
        // derived from GENERATE_NETHER depends on resolving it outside the overworld too.
        assertEquals(SAVED, resolve(null, SAVED, true, null, false).orElseThrow().selection());
    }

    private static Optional<WorldSelectionResolver.Resolution> resolve(
            WorldSelection published, WorldSelection saved, boolean hasRecord,
            WorldSelection configured, boolean overworld) {
        return WorldSelectionResolver.resolve(published, saved, hasRecord, configured, overworld);
    }

    private static WorldSelection selection(String preset) {
        return new WorldSelection(Identifier.parse(preset), Config.DEFAULT_WORLD_STYLE_MIX);
    }
}
