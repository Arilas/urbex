package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.regassets.data.CitySphereSettings;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterizes the ordering properties of {@link ChunkContentResolver#resolve}: the ones that must
 * survive a new branch being inserted into the middle of the order.
 * <p>
 * Only the second pass is covered here. The first pass, {@link ChunkContentResolver#couldHaveBuilding},
 * reaches into predefined-city data, highways and railways through a live {@code IDimensionInfo}, so
 * it is exercised by the world-generation digest rather than by a unit test. What a predefined street
 * produces there is a {@code false} candidate verdict, and that is the input used below.
 * <p>
 * Bootstrapped because {@code ChunkCoord}/{@code Level.OVERWORLD} need the vanilla registries.
 */
class ChunkContentResolverTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final ChunkCoord COORD = new ChunkCoord(Level.OVERWORLD, 12, -34);

    /** No neighbour wants to stand alone, so the lonely veto never fires. */
    private static final ChunkContentResolver.PrefersLonely SOCIABLE = neighbour -> 0.0f;

    /** Every neighbour insists on standing alone, so the veto fires on the first draw. */
    private static final ChunkContentResolver.PrefersLonely LONELY = neighbour -> 1.0f;

    private static ChunkContent resolve(boolean couldHaveBuilding, MultiPos section,
                                        ChunkContentResolver.PrefersLonely prefersLonely,
                                        RandomSource rand) {
        return ChunkContentResolver.resolve(TestProfiles.dense(), TestProfiles.cityStyle(), rand,
                couldHaveBuilding, section, COORD, prefersLonely, null, "testbuilding");
    }

    @Test
    void aRejectedCandidateNeverProducesABuilding() {
        // A predefined street is the strongest of the non-building claims: it rejects the candidate
        // in pass one, and nothing later in the order may hand the chunk back to a building.
        for (long seed = 0; seed < 200; seed++) {
            ChunkContent content = resolve(false, MultiPos.SINGLE, SOCIABLE, new XoroshiroRandomSource(seed));
            assertFalse(content.hasBuilding(), "a rejected candidate must beat the building roll, seed " + seed);
            assertNull(content.buildingName(), "a chunk with no building names no building, seed " + seed);
            assertNotNull(content.streetType(), "a chunk with no claim falls through to a street, seed " + seed);
        }
    }

    @Test
    void anAcceptedCandidateWithSociableNeighboursKeepsItsBuilding() {
        for (long seed = 0; seed < 200; seed++) {
            ChunkContent content = resolve(true, MultiPos.SINGLE, SOCIABLE, new XoroshiroRandomSource(seed));
            assertTrue(content.hasBuilding(), "nothing should veto this building, seed " + seed);
            assertEquals("testbuilding", content.buildingName(), "the accepted candidate names its asset, seed " + seed);
        }
    }

    @Test
    void aLonelyNeighbourVetoesTheBuilding() {
        for (long seed = 0; seed < 200; seed++) {
            ChunkContent content = resolve(true, MultiPos.SINGLE, LONELY, new XoroshiroRandomSource(seed));
            assertFalse(content.hasBuilding(), "a lonely neighbour must veto the building, seed " + seed);
        }
    }

    @Test
    void aBuildingChunkStillRollsItsStreetType() {
        // The street type is meaningless for a building chunk, but the roll must happen anyway: it
        // sits in the middle of the chunk's layout stream and skipping it would shift every later
        // draw. A non-null street type here is the evidence the draw was taken.
        ChunkContent content = resolve(true, MultiPos.SINGLE, SOCIABLE, new XoroshiroRandomSource(7));
        assertTrue(content.hasBuilding());
        assertNotNull(content.streetType(), "a building chunk must still consume the street-type roll");
    }

    @Test
    void aMultiBuildingSectionInheritsInsteadOfRolling() {
        // A non-top-left section of a multi-building copies everything from the top-left chunk. It
        // must touch neither the lonely veto nor the street roll, or its layout stream desyncs.
        RandomSource used = new XoroshiroRandomSource(99);
        ChunkContent content = resolve(true, new MultiPos(1, 0, 2, 2), LONELY, used);

        assertTrue(content.hasBuilding(), "the veto does not apply to a multi-building section");
        assertNull(content.streetType(), "a multi-building section inherits its street type");
        assertEquals(new XoroshiroRandomSource(99).nextLong(), used.nextLong(),
                "a multi-building section must consume no layout draws at all");
    }

    @Test
    void theSphereCentreOverridesTheBuildingRoll() {
        ChunkContent street = ChunkContentResolver.resolve(TestProfiles.dense(), TestProfiles.cityStyle(),
                new XoroshiroRandomSource(3), true, MultiPos.SINGLE, COORD, SOCIABLE,
                CitySphereSettings.CitySphereCenterType.STREET, "testbuilding");
        assertFalse(street.hasBuilding(), "a STREET sphere centre removes the building");

        ChunkContent building = ChunkContentResolver.resolve(TestProfiles.dense(), TestProfiles.cityStyle(),
                new XoroshiroRandomSource(3), false, MultiPos.SINGLE, COORD, LONELY,
                CitySphereSettings.CitySphereCenterType.BUILDING, "testbuilding");
        assertTrue(building.hasBuilding(), "a BUILDING sphere centre forces a building");
    }
}
