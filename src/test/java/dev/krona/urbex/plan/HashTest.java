package dev.krona.urbex.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashTest {

    @Test
    void sameInputsGiveTheSameHash() {
        assertEquals(Hash.at(1L, 2, 3, 4L), Hash.at(1L, 2, 3, 4L));
        assertEquals(Hash.atPos(1L, 2, 3, 4, 5L), Hash.atPos(1L, 2, 3, 4, 5L));
    }

    @Test
    void eachArgumentChangesTheHash() {
        long base = Hash.at(1L, 2, 3, 4L);
        assertNotEquals(base, Hash.at(9L, 2, 3, 4L));
        assertNotEquals(base, Hash.at(1L, 9, 3, 4L));
        assertNotEquals(base, Hash.at(1L, 2, 9, 4L));
        assertNotEquals(base, Hash.at(1L, 2, 3, 9L));
    }

    @Test
    void xAndZAreNotInterchangeable() {
        assertNotEquals(Hash.at(1L, 5, 9, 1L), Hash.at(1L, 9, 5, 1L));
    }

    @Test
    void negativeCoordinatesDoNotAliasOntoPositiveOnes() {
        assertNotEquals(Hash.at(1L, -3, 7, 1L), Hash.at(1L, 3, 7, 1L));
        assertNotEquals(Hash.atPos(1L, -3, 7, -11, 1L), Hash.atPos(1L, 3, 7, 11, 1L));
    }

    @Test
    void indexStaysInBounds() {
        for (int i = 0; i < 5000; i++) {
            int v = Hash.index(Hash.at(7L, i, -i, 3L), 16);
            assertTrue(v >= 0 && v < 16, "index out of bounds: " + v);
        }
    }

    @Test
    void indexReachesBothEnds() {
        boolean sawLow = false;
        boolean sawHigh = false;
        for (int i = 0; i < 5000 && !(sawLow && sawHigh); i++) {
            int v = Hash.index(Hash.at(7L, i, 0, 3L), 8);
            sawLow |= v == 0;
            sawHigh |= v == 7;
        }
        assertTrue(sawLow && sawHigh, "index never reached both ends of its range");
    }

    @Test
    void unitStaysInRange() {
        for (int i = 0; i < 5000; i++) {
            float v = Hash.unit(Hash.at(7L, i, i, 2L));
            assertTrue(v >= 0.0f && v < 1.0f, "unit out of range: " + v);
        }
    }

    @Test
    void unitIsRoughlyUniform() {
        int[] buckets = new int[10];
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            buckets[Math.min(9, (int) (Hash.unit(Hash.at(11L, i, 0, 1L)) * 10))]++;
        }
        for (int b = 0; b < 10; b++) {
            // Each bucket should hold ~10%. A 3x tolerance catches a broken extractor without
            // being flaky: a correct one lands within a fraction of a percent at this n.
            assertTrue(buckets[b] > n / 30 && buckets[b] < n / 3,
                    "bucket " + b + " held " + buckets[b] + " of " + n);
        }
    }
}
