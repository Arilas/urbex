package dev.krona.urbex.config;

import dev.krona.urbex.plan.RoadType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiBuildingStreetConflictTest {

    @Test
    void blockAllRejectsEveryRoadButNone() {
        MultiBuildingStreetConflict p = MultiBuildingStreetConflict.BLOCK_ALL;
        assertFalse(p.roadBlocks(RoadType.NONE));
        assertTrue(p.roadBlocks(RoadType.TERTIARY));
        assertTrue(p.roadBlocks(RoadType.SECONDARY));
        assertTrue(p.roadBlocks(RoadType.PRIMARY));
    }

    @Test
    void overrideMinorRejectsOnlyPrimary() {
        MultiBuildingStreetConflict p = MultiBuildingStreetConflict.OVERRIDE_MINOR;
        assertFalse(p.roadBlocks(RoadType.NONE));
        assertFalse(p.roadBlocks(RoadType.TERTIARY));
        assertFalse(p.roadBlocks(RoadType.SECONDARY));
        assertTrue(p.roadBlocks(RoadType.PRIMARY));
    }

    @Test
    void overrideAllRejectsNothing() {
        MultiBuildingStreetConflict p = MultiBuildingStreetConflict.OVERRIDE_ALL;
        for (RoadType t : RoadType.values()) {
            assertFalse(p.roadBlocks(t), t + " should not block under OVERRIDE_ALL");
        }
    }

    @Test
    void byNameIsCaseInsensitiveAndNamesValidValuesOnFailure() {
        assertEquals(MultiBuildingStreetConflict.OVERRIDE_MINOR,
                MultiBuildingStreetConflict.byName("override_minor"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MultiBuildingStreetConflict.byName("nonsense"));
        assertTrue(e.getMessage().contains("BLOCK_ALL"), "error should list valid values");
    }
}
