package dev.krona.urbex.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EffectiveRoadTest {

    @Test
    void onlyAllThreeConditionsTogetherKeepTheRoad() {
        assertEquals(RoadType.PRIMARY, EffectiveRoad.resolve(RoadType.PRIMARY, true, true, false));
        assertEquals(RoadType.NONE, EffectiveRoad.resolve(RoadType.PRIMARY, false, true, false));
        assertEquals(RoadType.NONE, EffectiveRoad.resolve(RoadType.PRIMARY, true, false, false));
        assertEquals(RoadType.NONE, EffectiveRoad.resolve(RoadType.PRIMARY, true, true, true));
    }

    @Test
    void aNoneFieldStaysNoneUnderEveryCombination() {
        for (boolean city : new boolean[]{false, true}) {
            for (boolean neighbour : new boolean[]{false, true}) {
                for (boolean overridden : new boolean[]{false, true}) {
                    assertEquals(RoadType.NONE,
                            EffectiveRoad.resolve(RoadType.NONE, city, neighbour, overridden));
                }
            }
        }
    }

    @Test
    void roadClassIsPreservedNotPromoted() {
        assertEquals(RoadType.TERTIARY, EffectiveRoad.resolve(RoadType.TERTIARY, true, true, false));
        assertEquals(RoadType.SECONDARY, EffectiveRoad.resolve(RoadType.SECONDARY, true, true, false));
    }
}
