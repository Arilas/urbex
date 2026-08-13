package dev.krona.urbex.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The arithmetic every call site used to spell out for itself.
 * <p>
 * {@code getMaxY() + 1}, {@code getMinY() >> 4} and {@code getMaxY() >> 4} appeared at roughly thirty
 * places across the generator and the planners. Each was correct; the point of naming them is that
 * the next one does not have to be re-derived, and that a preview can answer them without a level
 * (issue #129).
 */
class LevelShapeTest {

    @Test
    void maxBuildHeightIsOnePastTheHighestBlock() {
        assertEquals(320, new LevelShape(-64, 319, 63).maxBuildHeight());
    }

    @Test
    void sectionBoundsRoundTowardsNegativeInfinity() {
        LevelShape shape = new LevelShape(-64, 319, 63);
        assertEquals(-4, shape.minSection(), "-64 >> 4, not -64 / 16 rounded towards zero");
        assertEquals(19, shape.maxSection());

        // The arithmetic shift is what makes a below-zero floor land in the right section: a
        // division would put -1 in section 0 rather than section -1.
        assertEquals(-1, new LevelShape(-1, 15, 0).minSection());
    }

    @Test
    void theVanillaOverworldShapeIsTheOneTheWorldCreationPreviewDraws() {
        assertEquals(-64, LevelShape.VANILLA_OVERWORLD.minY());
        assertEquals(319, LevelShape.VANILLA_OVERWORLD.maxY());
        assertEquals(63, LevelShape.VANILLA_OVERWORLD.seaLevel());
        assertEquals(384, LevelShape.VANILLA_OVERWORLD.maxBuildHeight()
                - LevelShape.VANILLA_OVERWORLD.minY(), "384 blocks tall, as the dimension type says");
    }

    @Test
    void anInvertedShapeIsRefusedWhereItIsBuiltRatherThanWhereItIsUsed() {
        // A shape with maxY below minY makes every loop over it run zero times, silently: no city
        // content, no exception, no log line. Refusing it at construction is the only place the
        // numbers are together.
        assertThrows(IllegalArgumentException.class, () -> new LevelShape(10, 9, 5));
    }
}
