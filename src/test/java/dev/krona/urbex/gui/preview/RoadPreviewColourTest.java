package dev.krona.urbex.gui.preview;

import dev.krona.urbex.plan.RoadType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CityPreview#roadColour} is the class-to-colour mapping {@link CityPreview.Mode#ROADS} paints
 * from; pinned here so it is checked without a game. Every class must read as visually distinct (no two
 * classes sharing a colour) and {@link RoadType#NONE} must be fully transparent so a non-road chunk
 * leaves the dimmed base map showing through rather than painting a solid colour over it.
 */
class RoadPreviewColourTest {

    @Test
    void everyRoadClassIsDistinctAndNoneIsTransparent() {
        Set<Integer> seen = new HashSet<>();
        for (RoadType t : RoadType.values()) {
            assertTrue(seen.add(CityPreview.roadColour(t)), t + " duplicates another class's colour");
        }
        assertEquals(0, CityPreview.roadColour(RoadType.NONE));
    }
}
