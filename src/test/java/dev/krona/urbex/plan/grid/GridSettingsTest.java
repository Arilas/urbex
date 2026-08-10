package dev.krona.urbex.plan.grid;

import dev.krona.urbex.config.UrbexProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GridSettings#fromProfile} is the only adapter between the profile and the strictly validated
 * settings record, and it deliberately does not soften anything on the way through: a profile whose
 * minimum exceeds its maximum describes a world nobody can generate, and widening the pair here would
 * hand its author a different world with no diagnostic at all.
 */
class GridSettingsTest {

    private static UrbexProfile profile() {
        return new UrbexProfile("test", true);
    }

    @Test
    void aFreshProfileProducesUpstreamsDefaults() {
        assertEquals(GridSettings.defaults(), GridSettings.fromProfile(profile()));
    }

    @Test
    void anInvertedSecondaryCountOnXIsRejectedByName() {
        UrbexProfile p = profile();
        p.SECONDARY_ROAD_MIN_COUNT_X = 5;
        p.SECONDARY_ROAD_MAX_COUNT_X = 2;
        assertNamesTheField(p, "secondaryRoadMinCountX");
    }

    @Test
    void anInvertedSecondaryCountOnZIsRejectedByName() {
        UrbexProfile p = profile();
        p.SECONDARY_ROAD_MIN_COUNT_Z = 7;
        p.SECONDARY_ROAD_MAX_COUNT_Z = 0;
        assertNamesTheField(p, "secondaryRoadMinCountZ");
    }

    @Test
    void anInvertedTertiaryLengthIsRejectedByName() {
        UrbexProfile p = profile();
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
    private static void assertNamesTheField(UrbexProfile p, String configKey) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GridSettings.fromProfile(p));
        assertTrue(e.getMessage().contains(configKey),
                "message must name the offending profile setting '" + configKey + "', was: " + e.getMessage());
    }
}
