package dev.krona.urbex.gui.settings;

import dev.krona.urbex.config.Configuration;
import dev.krona.urbex.config.UrbexProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PercentageSliderMathTest {

    @Test
    void snapsToWholePercentAndClamps() {
        assertEquals(0.00f, PercentageSliderMath.snap(-0.4));
        assertEquals(0.37f, PercentageSliderMath.snap(0.374));
        assertEquals(0.38f, PercentageSliderMath.snap(0.376));
        assertEquals(1.00f, PercentageSliderMath.snap(1.4));
    }

    @Test
    void formatsNormalizedValuesAsPercent() {
        assertEquals(0, PercentageSliderMath.percent(0.0f));
        assertEquals(65, PercentageSliderMath.percent(0.65f));
        assertEquals(100, PercentageSliderMath.percent(1.0f));
    }

    @Test
    void productionBindingAppliesAndSynchronizesSnappedProfileValue() {
        UrbexProfile profile = new UrbexProfile("slider-binding", true);

        float applied = PercentageSliderMath.apply(profile, "lostcity.lightingDensity", 0.376);
        float synchronizedValue = PercentageSliderMath.read(
                profile.toConfiguration(), "lostcity.lightingDensity");

        assertEquals(0.38f, applied);
        assertEquals(0.38f, profile.LIGHTING_DENSITY);
        assertEquals(0.38f, synchronizedValue);
    }

    @Test
    void densityTooltipsComeFromApprovedProfileComments() {
        Configuration configuration = new UrbexProfile("slider-tooltips", true).toConfiguration();

        assertEquals("Chance that an optional decorative-light marker places a light",
                PercentageSliderMath.comment(configuration, "lostcity.lightingDensity").getString());
        assertEquals("Chance that a marked container receives a loot table",
                PercentageSliderMath.comment(configuration, "lostcity.lootDensity").getString());
    }
}
