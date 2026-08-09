package dev.krona.urbex.gui.elements;

import dev.krona.urbex.config.Configuration;
import dev.krona.urbex.config.UrbexProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PercentageSliderElementTest {

    @Test
    void snapsToWholePercentAndClamps() {
        assertEquals(0.00f, PercentageSliderElement.snap(-0.4));
        assertEquals(0.37f, PercentageSliderElement.snap(0.374));
        assertEquals(0.38f, PercentageSliderElement.snap(0.376));
        assertEquals(1.00f, PercentageSliderElement.snap(1.4));
    }

    @Test
    void formatsNormalizedValuesAsPercent() {
        assertEquals(0, PercentageSliderElement.percent(0.0f));
        assertEquals(65, PercentageSliderElement.percent(0.65f));
        assertEquals(100, PercentageSliderElement.percent(1.0f));
    }

    @Test
    void productionBindingAppliesAndSynchronizesSnappedProfileValue() {
        UrbexProfile profile = new UrbexProfile("slider-binding", true);

        float applied = PercentageSliderElement.apply(profile, "lostcity.lightingDensity", 0.376);
        float synchronizedValue = PercentageSliderElement.read(
                profile.toConfiguration(), "lostcity.lightingDensity");

        assertEquals(0.38f, applied);
        assertEquals(0.38f, profile.LIGHTING_DENSITY);
        assertEquals(0.38f, synchronizedValue);
    }

    @Test
    void densityTooltipsComeFromApprovedProfileComments() {
        Configuration configuration = new UrbexProfile("slider-tooltips", true).toConfiguration();

        assertEquals("Chance that an optional decorative-light marker places a light",
                PercentageSliderElement.comment(configuration, "lostcity.lightingDensity").getString());
        assertEquals("Chance that a marked container receives a loot table",
                PercentageSliderElement.comment(configuration, "lostcity.lootDensity").getString());
    }
}
