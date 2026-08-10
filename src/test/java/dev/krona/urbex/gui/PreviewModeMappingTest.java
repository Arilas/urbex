package dev.krona.urbex.gui;

import dev.krona.urbex.gui.preview.CityPreview;
import dev.krona.urbex.gui.settings.SettingCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CustomizeScreen#modeForCategory} is the pure wiring that picks which preview view the editor
 * shows for the category being edited (the widgets that call it are GL code, exercised only manually).
 * Transport -&gt; the highway/rail overlay; Roads -&gt; the road-class grid; Buildings and Damage -&gt; the
 * combined city-elevation-plus-damage close-up; everything else -&gt; the region map.
 */
class PreviewModeMappingTest {

    @Test
    void transportCategoryShowsTheTransportOverlay() {
        assertEquals(CityPreview.Mode.TRANSPORT, CustomizeScreen.modeForCategory(SettingCategory.TRANSPORT));
    }

    @Test
    void roadsCategoryShowsTheRoadPreview() {
        assertEquals(CityPreview.Mode.ROADS, CustomizeScreen.modeForCategory(SettingCategory.ROADS));
    }

    @Test
    void buildingsAndDamageShareTheCombinedCityView() {
        assertEquals(CityPreview.Mode.CITY, CustomizeScreen.modeForCategory(SettingCategory.BUILDINGS));
        assertEquals(CityPreview.Mode.CITY, CustomizeScreen.modeForCategory(SettingCategory.DAMAGE));
    }

    @Test
    void everyOtherCategoryKeepsTheRegionMap() {
        for (SettingCategory category : SettingCategory.values()) {
            if (category == SettingCategory.TRANSPORT
                    || category == SettingCategory.ROADS
                    || category == SettingCategory.BUILDINGS
                    || category == SettingCategory.DAMAGE) {
                continue;
            }
            assertEquals(CityPreview.Mode.MAP, CustomizeScreen.modeForCategory(category),
                    category + " should keep the region map");
        }
    }
}
