package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.geom.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Hand-authored cases for {@link WaterFrontage}: every expectation here is worked out by hand from
 * the terrain and the footprint, so nothing restates the implementation back at itself.
 * <p>
 * The footprint is 21x21 at the origin and the probe distance is 6, which puts the north probe line
 * at z = -6 spanning x = 0..20, and the three samples the old probe took at x = 4, 10 and 16.
 */
class WaterFrontageTest {

    private static final Rect FOOTPRINT = new Rect(0, 0, 20, 20);
    private static final int PROBE = 6;

    /** Water exactly on the listed blocks, dry everywhere else. */
    private record SpotWater(int[][] wet) implements TerrainSampler {
        @Override
        public int heightAt(int x, int z) {
            return 64;
        }

        @Override
        public boolean isWaterAt(int x, int z) {
            for (int[] w : wet) {
                if (w[0] == x && w[1] == z) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    void dryGroundHasNoWaterSides() {
        TerrainSampler dry = new SpotWater(new int[0][]);
        assertEquals(0, WaterFrontage.sidesOf(FOOTPRINT, dry, PROBE));
        assertEquals(WaterShape.INLAND, WaterShape.of(WaterFrontage.sidesOf(FOOTPRINT, dry, PROBE)));
    }

    /**
     * The defect this class exists for: a wet strip at x = 6..8 on the north probe line falls in the
     * gap between the old probe's samples at x = 4 and x = 10, so it saw dry ground where a river
     * clips the corner of the lot's frontage.
     */
    @Test
    void aWetStripBetweenTheOldSampleFractionsIsStillFound() {
        TerrainSampler t = new SpotWater(new int[][]{{6, -6}, {7, -6}, {8, -6}});
        assertEquals(WaterShape.NORTH, WaterFrontage.sidesOf(FOOTPRINT, t, PROBE));
    }

    /** A single wet block anywhere on the line is frontage; x = 20 is the far end of the north side. */
    @Test
    void oneWetBlockAtTheEndOfASideCounts() {
        TerrainSampler t = new SpotWater(new int[][]{{20, -6}});
        assertEquals(WaterShape.NORTH, WaterFrontage.sidesOf(FOOTPRINT, t, PROBE));
    }

    @Test
    void eachSideGetsItsOwnBit() {
        assertEquals(WaterShape.NORTH, WaterFrontage.sidesOf(FOOTPRINT, new SpotWater(
                new int[][]{{7, -6}}), PROBE));
        assertEquals(WaterShape.SOUTH, WaterFrontage.sidesOf(FOOTPRINT, new SpotWater(
                new int[][]{{7, 26}}), PROBE));
        assertEquals(WaterShape.EAST, WaterFrontage.sidesOf(FOOTPRINT, new SpotWater(
                new int[][]{{26, 7}}), PROBE));
        assertEquals(WaterShape.WEST, WaterFrontage.sidesOf(FOOTPRINT, new SpotWater(
                new int[][]{{-6, 7}}), PROBE));
    }

    /** Two adjacent sides is a CORNER - the shape the old probe most often downgraded to STRAIGHT. */
    @Test
    void waterOnTwoAdjacentSidesIsACorner() {
        TerrainSampler t = new SpotWater(new int[][]{{7, -6}, {26, 7}});
        assertEquals(WaterShape.CORNER,
                WaterShape.of(WaterFrontage.sidesOf(FOOTPRINT, t, PROBE)));
    }

    /**
     * The deliberate limit, pinned so it reads as a choice rather than an oversight: a side is probed
     * at exactly {@code probeDistance}, not swept from 1 to it, so water nearer than the probe line
     * but absent from it is not frontage. Sweeping the whole band was measured against this scan and
     * moved 2 lots in 835; see {@link WaterFrontage}'s doc.
     */
    @Test
    void waterNearerThanTheProbeLineButNotOnItIsNotFrontage() {
        TerrainSampler t = new SpotWater(new int[][]{{7, -3}});
        assertEquals(0, WaterFrontage.sidesOf(FOOTPRINT, t, PROBE));
    }

    /** A 1x1 footprint still probes one block per side, not zero. */
    @Test
    void aSingleBlockFootprintStillProbesEverySide() {
        Rect tiny = new Rect(5, 5, 5, 5);
        assertEquals(WaterShape.WEST,
                WaterFrontage.sidesOf(tiny, new SpotWater(new int[][]{{-1, 5}}), PROBE));
    }
}
