package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.varia.ChunkCoord;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression guard for the worldgen infinite-loop freeze: when {@code HIGHWAY_PERLIN_FACTOR} is set
 * below the perlin's output range, {@code hasHighway} is true for EVERY chunk, so the highway extent
 * scan used to walk forever, hanging whatever thread called it (real chunk generation, and the
 * synchronous GUI preview). {@link Highway#scanHighwayExtent} is now bounded; these tests prove an
 * always-true predicate terminates at the cap (degenerate => {@code null}) while normal predicates
 * still find the exact extent.
 * <p>
 * Bootstrapped because {@code ChunkCoord}/{@code Level.OVERWORLD} need the vanilla registries. The
 * {@code @Timeout} turns any regression back to an unbounded loop into a fast failure rather than a
 * CI hang.
 */
class HighwayScanExtentTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ChunkCoord at(int x, int z) {
        return new ChunkCoord(Level.OVERWORLD, x, z);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void alwaysTruePredicateTerminatesAtCapAndReportsDegenerate() {
        Function<ChunkCoord, Boolean> everyChunkIsHighway = cp -> true;

        // Both scan directions must bail with null (degenerate) instead of looping forever.
        assertNull(Highway.scanHighwayExtent(everyChunkIsHighway, at(0, 0), Orientation.X, true, Highway.MAX_HIGHWAY_SCAN),
                "always-true predicate must hit the cap and return null in the higher direction");
        assertNull(Highway.scanHighwayExtent(everyChunkIsHighway, at(0, 0), Orientation.X, false, Highway.MAX_HIGHWAY_SCAN),
                "always-true predicate must hit the cap and return null in the lower direction");
        assertNull(Highway.scanHighwayExtent(everyChunkIsHighway, at(0, 0), Orientation.Z, true, Highway.MAX_HIGHWAY_SCAN),
                "always-true predicate must hit the cap and return null along Z too");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void normalPredicateFindsExactExtentUnchanged() {
        // Highway occupies chunkX in [3, 7] along the X orientation; everything else is not a highway.
        Function<ChunkCoord, Boolean> highwayThreeToSeven = cp -> cp.chunkX() >= 3 && cp.chunkX() <= 7;

        // Walking higher from x=5 must stop at the first non-highway chunk, x=8.
        ChunkCoord higherEnd = Highway.scanHighwayExtent(highwayThreeToSeven, at(5, 0), Orientation.X, true, Highway.MAX_HIGHWAY_SCAN);
        assertEquals(8, higherEnd.chunkX(), "higher scan must stop just past the run's high end");

        // Walking lower from x=5 must stop at the first non-highway chunk, x=2.
        ChunkCoord lowerEnd = Highway.scanHighwayExtent(highwayThreeToSeven, at(5, 0), Orientation.X, false, Highway.MAX_HIGHWAY_SCAN);
        assertEquals(2, lowerEnd.chunkX(), "lower scan must stop just past the run's low end");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void startNotOnHighwayReturnsStartImmediately() {
        // If the start chunk is not a highway, the scan returns it untouched (zero steps).
        Function<ChunkCoord, Boolean> noHighway = cp -> false;
        ChunkCoord end = Highway.scanHighwayExtent(noHighway, at(9, 0), Orientation.X, true, Highway.MAX_HIGHWAY_SCAN);
        assertEquals(9, end.chunkX(), "a start chunk with no highway must be returned as-is");
    }
}
