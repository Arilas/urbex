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
        // A second golden vector, over the tail of the enum. GOLDEN pins RUINS, ordinal 3, so it
        // survives an insertion or reorder anywhere below it - which is all but the first four
        // constants, including everything recent commits appended. This one moves whenever the
        // tail moves. Regenerate the same way as GOLDEN, over LAST_PURPOSE.
        assertArrayEquals(GOLDEN_LAST, take(Rng.at(42L, 100, -100, LAST_PURPOSE), 4));
    }

    /**
     * A third golden vector, over the palette addressing both formats now share.
     * <p>
     * {@code paletteSlotAt} decides which block every weighted palette marker places at every position,
     * so a change to it rewrites every generated world exactly as a change to the mixing function would.
     * It used to be an expression inside {@code CompiledPalette.getAt} with nothing pinning it; it moved
     * here when the version 2 format needed the same addressing, and this is what makes the move
     * checkable rather than asserted. Regenerate the same way as {@code GOLDEN}, after a deliberate
     * change, and say so in the commit.
     */
    @Test
    void thePaletteSlotAddressIsStableAcrossRuns() {
        assertArrayEquals(new int[]{93, 127, 1, 104},
                new int[]{
                        Rng.paletteSlotAt(42L, '#', 100, 64, -100, 128),
                        Rng.paletteSlotAt(42L, '#', 100, 65, -100, 128),
                        Rng.paletteSlotAt(42L, 'F', 100, 64, -100, 128),
                        Rng.paletteSlotAt(1337L, '#', 100, 64, -100, 128)});
    }

    @Test
    void theEnumLayoutIsPinned() {
        // purpose.ordinal() feeds the hash, so inserting, removing or reordering a constant
        // reseeds every consumer from that ordinal on, changing every generated world. That is
        // sometimes an intentional edit rather than a forbidden one - while this mod is
        // unreleased it is an accepted cost, and this test exists so it is never an accidental
        // one. A failure here is either a genuine, deliberate layout change - update
        // PURPOSE_COUNT, LAST_PURPOSE, GOLDEN_LAST and PURPOSE_ORDER, and say so in the commit -
        // or an unintended reorder that must be undone. Once worlds exist in the wild, a layout
        // change stops being an available option and becomes a breaking change.
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
            968677072947688043L, 7783050954197339180L, 5127804395627648118L, -7549811660803165866L
    };

    private static final Rng.Purpose LAST_PURPOSE = Rng.Purpose.LIGHTING_UNLIT;

    // Regenerated when LIGHTING_UNLIT was appended and became the last purpose. SPAWNER_DENSITY's
    // own stream is unchanged - the constant went after it, so no ordinal that existed before moved
    // and no world that ever generated draws differently.
    private static final long[] GOLDEN_LAST = {
            7558796984515213366L, -4942667091505927747L, 7332033667757005157L, 8579002919567590727L
    };

    private static final int PURPOSE_COUNT = 44;

    private static final String PURPOSE_ORDER =
            "BUILDING,MULTI,PARTS,RUINS,RUBBLE,LEAVES,DEBRIS,STUFF,SPAWNERS,LOOT,VEGETATION,"
                    + "DAMAGE,CITY_CENTER,CITY_RADIUS,CITY_STYLE,RAILWAY,SCATTERED,"
                    + "PALETTE,NOISE,SHAPE,TERRAIN_L1,TERRAIN_L2,EXPLOSION,EXPLOSION_MINI,RUINS_BARS,"
                    + "DAMAGE_VARIANT,TERRAIN_FIX_LOWER,"
                    + "TERRAIN_FIX_UPPER,CITY_STYLE_LOCAL,VEGETATION_GROWTH,BUILDING_FLOORS,BUILDING_LAYOUT,"
                    + "VEGETATION_XMAX,VEGETATION_ZMIN,VEGETATION_ZMAX,EXPLOSION_ACCEPT,EXPLOSION_MINI_ACCEPT,"
                    + "LIGHTING_DENSITY,LIGHTING_VARIANT,LOOT_DENSITY,"
                    + "LARGE_BRIDGE,WORLD_STYLE,SPAWNER_DENSITY,LIGHTING_UNLIT";
}
