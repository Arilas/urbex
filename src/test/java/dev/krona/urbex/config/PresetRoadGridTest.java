package dev.krona.urbex.config;

import dev.krona.urbex.plan.grid.GridSettings;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PresetRoadGrid} is the only adapter between the preset and the strictly validated settings
 * record, and it deliberately does not soften anything on the way through: a preset whose minimum
 * exceeds its maximum describes a world nobody can generate, and widening the pair here would hand
 * its author a different world with no diagnostic at all.
 * <p>
 * It sits on the configuration side of the boundary. It used to be a {@code GridSettings.fromPreset}
 * factory inside {@code dev.krona.urbex.plan}, written with a fully-qualified parameter type so the
 * package's own purity test would not see the reach (issue #129).
 */
class PresetRoadGridTest {

    private static Preset profile() {
        return new Preset(Identifier.fromNamespaceAndPath("urbex", "test"));
    }

    @Test
    void aFreshProfileProducesUpstreamsDefaults() {
        assertEquals(GridSettings.defaults(), PresetRoadGrid.of(profile()));
    }

    @Test
    void primarySpacingXOutOfRangeIsRejectedByName() {
        Preset p = profile();
        p.PRIMARY_ROAD_SPACING_X = 200;
        assertNamesTheField(p, "primaryRoadSpacingX");
    }

    @Test
    void primarySpacingZOutOfRangeIsRejectedByName() {
        Preset p = profile();
        p.PRIMARY_ROAD_SPACING_Z = 200;
        assertNamesTheField(p, "primaryRoadSpacingZ");
    }

    @Test
    void primaryOptionalChanceOutOfRangeIsRejectedByName() {
        Preset p = profile();
        p.PRIMARY_ROAD_OPTIONAL_CHANCE = 1.5f;
        assertNamesTheField(p, "primaryRoadOptionalChance");
    }

    @Test
    void primaryForceEveryOutOfRangeIsRejectedByName() {
        Preset p = profile();
        p.PRIMARY_ROAD_FORCE_EVERY = 0;
        assertNamesTheField(p, "primaryRoadForceEvery");
    }

    @Test
    void minimumRoadSeparationOutOfRangeIsRejectedByName() {
        Preset p = profile();
        p.MINIMUM_ROAD_SEPARATION = 1;
        assertNamesTheField(p, "minimumRoadSeparation");
    }

    @Test
    void minimumEdgeDistanceOutOfRangeIsRejectedByName() {
        Preset p = profile();
        p.MINIMUM_ROAD_EDGE_DISTANCE = 1;
        assertNamesTheField(p, "minimumRoadEdgeDistance");
    }

    @Test
    void tertiaryChanceOutOfRangeIsRejectedByName() {
        Preset p = profile();
        p.TERTIARY_ROAD_CHANCE = -0.1f;
        assertNamesTheField(p, "tertiaryRoadChance");
    }

    @Test
    void anInvertedSecondaryCountOnXIsRejectedByName() {
        Preset p = profile();
        p.SECONDARY_ROAD_MIN_COUNT_X = 5;
        p.SECONDARY_ROAD_MAX_COUNT_X = 2;
        assertNamesTheField(p, "secondaryRoadMinCountX");
    }

    @Test
    void anInvertedSecondaryCountOnZIsRejectedByName() {
        Preset p = profile();
        p.SECONDARY_ROAD_MIN_COUNT_Z = 7;
        p.SECONDARY_ROAD_MAX_COUNT_Z = 0;
        assertNamesTheField(p, "secondaryRoadMinCountZ");
    }

    @Test
    void anInvertedTertiaryLengthIsRejectedByName() {
        Preset p = profile();
        p.TERTIARY_ROAD_MIN_LENGTH = 9;
        p.TERTIARY_ROAD_MAX_LENGTH = 2;
        assertNamesTheField(p, "tertiaryRoadMinLength");
    }

    /**
     * The message has to name the offending setting, and name it the way its author wrote it: these
     * reach a player through a failed world load, where the profile JSON key is the only handle they
     * have on the value. The offending values are included too, so the report is actionable without
     * opening the file.
     */
    private static void assertNamesTheField(Preset p, String configKey) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PresetRoadGrid.of(p));
        assertTrue(e.getMessage().contains(configKey),
                "message must name the offending profile setting '" + configKey + "', was: " + e.getMessage());
    }
}
