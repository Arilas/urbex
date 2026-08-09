package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.worldgen.lost.cityassets.ScatteredBuilding.TerrainHeight;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ScatteredTerrainLevelTest {

    // The old switch had AVERAGE -> maximum and HIGHEST -> average (issue #38).

    @Test
    public void lowestPicksMinimum() {
        assertEquals(60, Scattered.pickLevel(TerrainHeight.LOWEST, 60, 90, 75, 63));
    }

    @Test
    public void averagePicksAverage() {
        assertEquals(75, Scattered.pickLevel(TerrainHeight.AVERAGE, 60, 90, 75, 63));
    }

    @Test
    public void highestPicksMaximum() {
        assertEquals(90, Scattered.pickLevel(TerrainHeight.HIGHEST, 60, 90, 75, 63));
    }

    @Test
    public void oceanPicksSeaLevel() {
        assertEquals(63, Scattered.pickLevel(TerrainHeight.OCEAN, 60, 90, 75, 63));
    }
}
