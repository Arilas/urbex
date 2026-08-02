package dev.krona.urbex.varia;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RngTest {

    private static long[] take(RandomSource source, int n) {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = source.nextLong();
        }
        return out;
    }

    private static long first(long seed, int x, int z, Rng.Purpose purpose) {
        return Rng.at(seed, x, z, purpose).nextLong();
    }

    @Test
    void sameInputsProduceTheSameStream() {
        long[] a = take(Rng.at(12345L, 10, -20, Rng.Purpose.RUINS), 16);
        long[] b = take(Rng.at(12345L, 10, -20, Rng.Purpose.RUINS), 16);
        assertArrayEquals(a, b);
    }

    @Test
    void differentPurposesAtTheSameCoordinateDiffer() {
        assertNotEquals(first(1L, 0, 0, Rng.Purpose.RUINS),
                        first(1L, 0, 0, Rng.Purpose.RUBBLE));
    }

    @Test
    void everyPurposePairIsDistinct() {
        Rng.Purpose[] purposes = Rng.Purpose.values();
        for (int i = 0; i < purposes.length; i++) {
            for (int j = i + 1; j < purposes.length; j++) {
                assertNotEquals(first(7L, 3, 4, purposes[i]),
                                first(7L, 3, 4, purposes[j]),
                                purposes[i] + " collides with " + purposes[j]);
            }
        }
    }

    @Test
    void differentCoordinatesDiffer() {
        assertNotEquals(first(1L, 0, 0, Rng.Purpose.BUILDING),
                        first(1L, 0, 1, Rng.Purpose.BUILDING));
        assertNotEquals(first(1L, 0, 0, Rng.Purpose.BUILDING),
                        first(1L, 1, 0, Rng.Purpose.BUILDING));
        // x and z must not be interchangeable
        assertNotEquals(first(1L, 5, 9, Rng.Purpose.BUILDING),
                        first(1L, 9, 5, Rng.Purpose.BUILDING));
        // negative coordinates must not alias onto positive ones
        assertNotEquals(first(1L, -3, 7, Rng.Purpose.BUILDING),
                        first(1L, 3, 7, Rng.Purpose.BUILDING));
    }

    @Test
    void differentSeedsDiffer() {
        assertNotEquals(first(1L, 0, 0, Rng.Purpose.BUILDING),
                        first(2L, 0, 0, Rng.Purpose.BUILDING));
    }

    @Test
    void streamIsStableAcrossRuns() {
        // Golden vector. Generated once by GOLDEN_VECTOR_PRINTER below and pinned here so a
        // change to the mixing function shows up as a test failure rather than a silently
        // different world.
        assertArrayEquals(GOLDEN, take(Rng.at(42L, 100, -100, Rng.Purpose.RUINS), 4));
    }

    private static final long[] GOLDEN = {
            -9164405306304841749L, 7151656282857621996L, -5080990405395573686L, 7700290050221519842L
    };
}
