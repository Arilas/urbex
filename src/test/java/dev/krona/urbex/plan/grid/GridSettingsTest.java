package dev.krona.urbex.plan.grid;

import dev.krona.urbex.config.UrbexProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link GridSettings#fromProfile} is the only adapter between the loosely-checked profile and the
 * strictly-validated settings record, and it is called on every settings-screen keystroke as well as
 * at world load. These pin the two things that makes it safe there.
 */
class GridSettingsTest {

    @Test
    void aFreshProfileProducesUpstreamsDefaults() {
        assertEquals(GridSettings.defaults(), GridSettings.fromProfile(new UrbexProfile("test", true)));
    }

    @Test
    void aMinimumDraggedPastItsMaximumIsWidenedRatherThanRejected() {
        // Each of these pairs is two independent sliders, so crossing them is one drag away. The
        // record's constructor rightly refuses a crossed pair; throwing out of here would take the
        // settings screen down while the player is still dragging.
        UrbexProfile profile = new UrbexProfile("test", true);
        profile.SECONDARY_ROAD_MIN_COUNT_X = 5;
        profile.SECONDARY_ROAD_MAX_COUNT_X = 1;
        profile.SECONDARY_ROAD_MIN_COUNT_Z = 7;
        profile.SECONDARY_ROAD_MAX_COUNT_Z = 0;
        profile.TERTIARY_ROAD_MIN_LENGTH = 9;
        profile.TERTIARY_ROAD_MAX_LENGTH = 2;

        GridSettings settings = assertDoesNotThrow(() -> GridSettings.fromProfile(profile));
        assertEquals(5, settings.secondaryMaxCountX(), "a crossed pair reads as exactly the minimum");
        assertEquals(7, settings.secondaryMaxCountZ());
        assertEquals(9, settings.tertiaryMaxLength());
    }
}
