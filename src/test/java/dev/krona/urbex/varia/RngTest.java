package dev.krona.urbex.varia;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void indexAtPosStaysInBounds() {
        for (int y = 0; y < 512; y++) {
            int i = Rng.indexAtPos(9L, 3, y, -7, Rng.Purpose.PALETTE, 128);
            assertTrue(i >= 0 && i < 128, "out of bounds: " + i);
        }
    }

    @Test
    void indexAtPosIsAddressedNotSequential() {
        // The same address always resolves the same way, however many other addresses were
        // resolved in between. This is the property a per-chunk sequential stream lacked.
        int first = Rng.indexAtPos(9L, 3, 64, -7, Rng.Purpose.PALETTE, 128);
        for (int y = 0; y < 100; y++) {
            Rng.indexAtPos(9L, 3, y, -7, Rng.Purpose.PALETTE, 128);
        }
        assertEquals(first, Rng.indexAtPos(9L, 3, 64, -7, Rng.Purpose.PALETTE, 128));
    }

    @Test
    void indexAtPosSpreadsOverItsRange() {
        boolean[] seen = new boolean[16];
        for (int y = 0; y < 4096; y++) {
            seen[Rng.indexAtPos(9L, 3, y, -7, Rng.Purpose.PALETTE, 16)] = true;
        }
        for (int i = 0; i < seen.length; i++) {
            assertTrue(seen[i], "index " + i + " never produced");
        }
    }

    @Test
    void floatAtPosIsAUnitInterval() {
        for (int y = 0; y < 512; y++) {
            float f = Rng.floatAtPos(9L, 3, y, -7, Rng.Purpose.DAMAGE);
            assertTrue(f >= 0.0f && f < 1.0f, "out of range: " + f);
        }
    }

    @Test
    void pairedRollsAtOnePositionAreIndependent() {
        // damageBlock rolls twice on one block; the two purposes must not hand back the same value.
        assertNotEquals(Rng.floatAtPos(9L, 3, 64, -7, Rng.Purpose.DAMAGE),
                        Rng.floatAtPos(9L, 3, 64, -7, Rng.Purpose.DAMAGE_VARIANT));
        assertNotEquals(Rng.floatAtPos(9L, 3, 64, -7, Rng.Purpose.RUINS),
                        Rng.floatAtPos(9L, 3, 64, -7, Rng.Purpose.RUINS_BARS));
    }

    @Test
    void differentSlotsDiffer() {
        assertNotEquals(Rng.atSlot(1L, 4, 5, 0, Rng.Purpose.STUFF).nextLong(),
                        Rng.atSlot(1L, 4, 5, 1, Rng.Purpose.STUFF).nextLong());
        // and the chunk still separates two identical slots
        assertNotEquals(Rng.atSlot(1L, 4, 5, 7, Rng.Purpose.STUFF).nextLong(),
                        Rng.atSlot(1L, 5, 4, 7, Rng.Purpose.STUFF).nextLong());
    }

    private static final long[] GOLDEN = {
            -9164405306304841749L, 7151656282857621996L, -5080990405395573686L, 7700290050221519842L
    };
}
