package dev.krona.urbex.varia;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RngTest {

    @org.junit.jupiter.api.Test
    void posSeedReseedsToTheSameStreamAtPosAllocates() {
        // posSeed exists so hot loops can setSeed a reused XoroshiroRandomSource instead of
        // allocating one per block; the reseeded stream must match atPos exactly.
        var reused = new net.minecraft.world.level.levelgen.XoroshiroRandomSource(0);
        reused.setSeed(Rng.posSeed(1337L, 5, 64, -9, Rng.Purpose.SHAPE));
        var allocated = Rng.atPos(1337L, 5, 64, -9, Rng.Purpose.SHAPE);
        for (int i = 0; i < 8; i++) {
            org.junit.jupiter.api.Assertions.assertEquals(allocated.nextLong(), reused.nextLong());
        }
    }

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
        // Golden vector, pinned so a change to the mixing function shows up as a test failure
        // rather than a silently different world. To regenerate after an intended change: print
        // take(Rng.at(42L, 100, -100, Rng.Purpose.RUINS), 4) and paste the four values below.
        assertArrayEquals(GOLDEN, take(Rng.at(42L, 100, -100, Rng.Purpose.RUINS), 4));
    }

    @Test
    void streamIsStableAcrossRunsForTheLastPurpose() {
        // A second golden vector, over the tail of the enum. GOLDEN pins RUINS, ordinal 4, so it
        // survives an insertion or reorder anywhere below it - which is all but the first five
        // constants, including everything recent commits appended. This one moves whenever the
        // tail moves. Regenerate the same way as GOLDEN, over LAST_PURPOSE.
        assertArrayEquals(GOLDEN_LAST, take(Rng.at(42L, 100, -100, LAST_PURPOSE), 4));
    }

    @Test
    void theEnumIsAppendOnly() {
        // purpose.ordinal() feeds the hash, so inserting, removing or reordering a constant
        // reseeds every consumer from that ordinal on, in every world that already exists.
        // Appending is the only safe edit. A failure here is either a genuine append - update
        // PURPOSE_COUNT, LAST_PURPOSE, GOLDEN_LAST and PURPOSE_ORDER, and say so in the commit -
        // or a reorder that must be undone.
        assertEquals(PURPOSE_COUNT, Rng.Purpose.values().length, "Purpose constant count changed");
        assertEquals(PURPOSE_COUNT - 1, LAST_PURPOSE.ordinal(), LAST_PURPOSE + " is no longer last");

        // The count and the last constant alone would still miss a swap in the middle, so pin the
        // whole order. This is the only assertion that catches every reorder.
        StringJoiner actual = new StringJoiner(",");
        for (Rng.Purpose purpose : Rng.Purpose.values()) {
            actual.add(purpose.name());
        }
        assertEquals(PURPOSE_ORDER, actual.toString(), "Purpose order changed");
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

    private static final Rng.Purpose LAST_PURPOSE = Rng.Purpose.LOOT_DENSITY;

    private static final long[] GOLDEN_LAST = {
            -8452086439569127134L, 5133209234212060231L, -2993523536662716498L, -3335748431786222750L
    };

    private static final int PURPOSE_COUNT = 50;

    private static final String PURPOSE_ORDER =
            "BUILDING,STREET,MULTI,PARTS,RUINS,RUBBLE,LEAVES,DEBRIS,STUFF,SPAWNERS,LOOT,VEGETATION,"
                    + "DAMAGE,VINES,CITY_CENTER,CITY_RADIUS,CITY_STYLE,HIGHWAY,RAILWAY,SPHERE,SCATTERED,"
                    + "PALETTE,NOISE,SHAPE,TERRAIN_L1,TERRAIN_L2,EXPLOSION,EXPLOSION_MINI,RUINS_BARS,"
                    + "DAMAGE_VARIANT,SPHERE_BLOCKS,SPHERE_CITY_LEVEL,VINES_CONTINUE,TERRAIN_FIX_LOWER,"
                    + "TERRAIN_FIX_UPPER,CITY_STYLE_LOCAL,VEGETATION_GROWTH,BUILDING_FLOORS,BUILDING_LAYOUT,"
                    + "VEGETATION_XMAX,VEGETATION_ZMIN,VEGETATION_ZMAX,EXPLOSION_ACCEPT,EXPLOSION_MINI_ACCEPT,"
                    + "VINES_EAST,VINES_NORTH,VINES_SOUTH,LIGHTING_DENSITY,LIGHTING_VARIANT,LOOT_DENSITY";
}
