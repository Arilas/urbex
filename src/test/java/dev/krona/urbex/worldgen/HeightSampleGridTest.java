package dev.krona.urbex.worldgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample grid has to be a partition of the chunk grid, because the heightmap cache is filled a
 * whole block at a time by whichever chunk in it asked first. A chunk covered by two blocks gets
 * whichever of the two answers won that race, which is generation order deciding what the world
 * looks like (issue #126).
 */
class HeightSampleGridTest {

    /** Wide enough to cross the origin, which is the only place the old arithmetic overlapped. */
    private static final int FROM = -40;
    private static final int TO = 40;

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 7, 16, 100})
    void everyChunkOfABlockAgreesOnThatBlock(int size) {
        // The property that failed: chunk (-1)'s block used to contain key 0, while chunk 0 claimed
        // a different block with a different sampler. Asking it of every chunk in every block covers
        // that without naming it.
        for (int c = FROM; c <= TO; c++) {
            int anchor = HeightSampleGrid.anchor(c, size);
            assertTrue(anchor <= c && c < anchor + size,
                    "chunk " + c + " is outside its own block [" + anchor + ", " + (anchor + size) + ")");
            for (int member = anchor; member < anchor + size; member++) {
                assertEquals(anchor, HeightSampleGrid.anchor(member, size),
                        "chunk " + member + " is in the block anchored at " + anchor
                                + " but claims a different one, so the two blocks overlap");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 7, 16, 100})
    void theSampledCoordinateBelongsToTheBlockItSpeaksFor(int size) {
        for (int c = FROM; c <= TO; c++) {
            int anchor = HeightSampleGrid.anchor(c, size);
            int sampler = HeightSampleGrid.sampler(anchor, size);
            assertTrue(anchor <= sampler && sampler < anchor + size,
                    "block [" + anchor + ", " + (anchor + size) + ") samples " + sampler
                            + ", which is terrain outside the block");
        }
    }

    @Test
    void blocksDoNotSeamAtTheOrigin() {
        // The measured case. With the default sample size of 3, chunk z=-1 used to anchor at 0 and
        // lay its block out downwards - {0, -1, -2} - while z=0 anchored at 0 and laid it upwards -
        // {0, 1, 2}. Both wrote key 0, from samplers at z=-1 and z=+1 respectively, and the digest
        // window centred on the origin returned one hash or the other depending on which worker got
        // there first.
        assertEquals(HeightSampleGrid.anchor(-1, 3), HeightSampleGrid.anchor(-3, 3));
        assertEquals(-3, HeightSampleGrid.anchor(-1, 3));
        assertEquals(0, HeightSampleGrid.anchor(0, 3));
        assertEquals(0, HeightSampleGrid.anchor(2, 3));
    }

    @Test
    void nonNegativeCoordinatesAreUnchangedFromTheOldArithmetic() {
        // Why the three digest windows sited on positive chunks did not move when this was fixed.
        for (int size = 2; size <= 16; size++) {
            for (int c = 0; c <= TO; c++) {
                assertEquals((c / size) * size, HeightSampleGrid.anchor(c, size),
                        "anchor moved at non-negative chunk " + c + " for sample size " + size);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void aSampleSizeOfOneOrLessIsThePerChunkIdentity(int size) {
        for (int c = FROM; c <= TO; c++) {
            assertEquals(c, HeightSampleGrid.anchor(c, size));
            assertEquals(c, HeightSampleGrid.sampler(c, size));
        }
    }
}
