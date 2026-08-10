package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.LandscapeType;
import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.plan.RoadType;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.regassets.data.CitySphereSettings;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterizes the order in {@link ChunkContentResolver}: which claim beats which, which world
 * facts are consulted and in what sequence, and exactly how many draws each path takes. These are
 * the properties a new branch inserted into the middle of the order must not disturb - the digest
 * would catch a break too, but only after several minutes and only if one of its 49 sampled chunks
 * happens to differ.
 * <p>
 * Bootstrapped because {@code ChunkCoord}/{@code Level.OVERWORLD} need the vanilla registries.
 */
class ChunkContentResolverTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static UrbexProfile profileWithBuildingChance(float chance) {
        UrbexProfile profile = TestProfiles.dense();
        profile.BUILDING_CHANCE = chance;
        return profile;
    }

    /**
     * Pass one: the candidate verdict, and the chain of full stops that produces it.
     * <p>
     * Every world fact records itself in {@link Facts#consulted} as it is asked, so a test can assert
     * not just the verdict but that the chain stopped where it should have - the short-circuiting is
     * behaviour here, not an optimisation.
     */
    @Nested
    class PassOne {

        private static final class Facts {
            boolean predefinedBuilding;
            boolean predefinedStreet;
            CityStyle cityStyle = TestProfiles.cityStyle();
            RoadType effectiveRoad = RoadType.NONE;
            boolean highway;
            int highwayLevel;
            boolean railway;
            Railway.RailChunkInfo railInfo = Railway.RailChunkInfo.NOTHING;
            float sphereDistance;
            final List<String> consulted = new ArrayList<>();

            ChunkContentResolver.ChunkFacts build() {
                return new ChunkContentResolver.ChunkFacts(
                        () -> record("predefinedBuilding", predefinedBuilding),
                        () -> record("predefinedStreet", predefinedStreet),
                        () -> record("cityStyle", cityStyle),
                        () -> record("effectiveRoad", effectiveRoad),
                        () -> record("hasHighway", highway),
                        () -> record("highwayLevel", highwayLevel),
                        () -> record("hasRailway", railway),
                        () -> record("railInfo", railInfo),
                        () -> record("sphereDistance", sphereDistance));
            }

            private <T> T record(String fact, T value) {
                consulted.add(fact);
                return value;
            }
        }

        private boolean resolve(UrbexProfile profile, MultiPos section, int cityLevel, Facts facts) {
            return ChunkContentResolver.couldHaveBuilding(profile, true, section, cityLevel,
                    new XoroshiroRandomSource(1234), facts.build());
        }

        @Test
        void aPredefinedBuildingWinsAndStopsTheChain() {
            Facts facts = new Facts();
            facts.predefinedBuilding = true;
            // Everything that could say "no" is set, and none of it may even be asked.
            facts.predefinedStreet = true;
            facts.highway = true;
            facts.railway = true;

            assertTrue(resolve(profileWithBuildingChance(0.0f), MultiPos.SINGLE, 0, facts));
            assertEquals(List.of("predefinedBuilding"), facts.consulted,
                    "a predefined building needs no other test");
        }

        @Test
        void aPredefinedStreetBeatsAMultiBuildingSection() {
            // The one ordering that is easy to get backwards: a predefined street returns before the
            // multi-building section is even looked at, so a multi cannot overrule it.
            Facts facts = new Facts();
            facts.predefinedStreet = true;

            assertFalse(resolve(profileWithBuildingChance(1.0f), new MultiPos(0, 0, 2, 2), 5, facts));
            assertEquals(List.of("predefinedBuilding", "predefinedStreet"), facts.consulted,
                    "a predefined street decides before the multi-building section is considered");
        }

        @Test
        void aMultiBuildingSectionSkipsTheBuildingChanceRoll() {
            Facts facts = new Facts();
            facts.highway = true;

            assertTrue(resolve(profileWithBuildingChance(0.0f), new MultiPos(1, 0, 2, 2), 0, facts),
                    "a multi-building section is accepted even when the chance roll could never pass");
            assertFalse(facts.consulted.contains("hasHighway"),
                    "an accepted multi-building section stops the chain before the constraints");
        }

        @Test
        void anAcceptedMultiBuildingBeatsAPlannedRoad() {
            // Whether a road under the footprint should have stopped the multi is settled earlier, by
            // MULTI_BUILDING_STREET_CONFLICT in MultiChunk. By the time the section exists here it has
            // already won, so the road is not even asked about.
            Facts facts = new Facts();
            facts.effectiveRoad = RoadType.PRIMARY;

            assertTrue(resolve(profileWithBuildingChance(1.0f), new MultiPos(1, 0, 2, 2), 0, facts));
            assertFalse(facts.consulted.contains("effectiveRoad"),
                    "an accepted multi-building section decides before the road field is consulted");
        }

        @Test
        void aPlannedRoadBeatsTheBuildingChanceRoll() {
            // A chance of 1.0 would accept every chunk, so a rejection here can only come from the
            // road branch sitting ahead of the roll.
            for (RoadType road : new RoadType[]{RoadType.PRIMARY, RoadType.SECONDARY, RoadType.TERTIARY}) {
                Facts facts = new Facts();
                facts.effectiveRoad = road;
                facts.highway = true;
                facts.highwayLevel = 0;

                assertFalse(resolve(profileWithBuildingChance(1.0f), MultiPos.SINGLE, 9, facts),
                        "a " + road + " road leaves no room for a building");
                assertFalse(facts.consulted.contains("hasHighway"),
                        "the road branch decides, so the constraints below it are never reached");
            }
        }

        @Test
        void aChunkWithNoRoadFallsThroughToTheRoll() {
            Facts facts = new Facts();
            facts.effectiveRoad = RoadType.NONE;

            assertFalse(resolve(profileWithBuildingChance(0.0f), MultiPos.SINGLE, 0, facts));
            assertTrue(facts.consulted.contains("effectiveRoad"),
                    "the road is consulted even when it turns out not to claim the chunk");
        }

        @Test
        void theBuildingChanceRollIsCheckedBeforeHighwayHeadroom() {
            Facts facts = new Facts();
            facts.highway = true;
            facts.highwayLevel = 0;

            // The city level clears the highway easily, so a reordered chain would answer true here.
            assertFalse(resolve(profileWithBuildingChance(0.0f), MultiPos.SINGLE, 9, facts));
            assertFalse(facts.consulted.contains("hasHighway"),
                    "a failed chance roll decides before the highway is consulted");
        }

        @Test
        void highwayHeadroomNeedsTheCityLevelToClearTheHighway() {
            Facts tooLow = new Facts();
            tooLow.highway = true;
            tooLow.highwayLevel = 1;
            assertFalse(resolve(profileWithBuildingChance(1.0f), MultiPos.SINGLE, 2, tooLow),
                    "a city level equal to the highway + 1 has no room");

            Facts highEnough = new Facts();
            highEnough.highway = true;
            highEnough.highwayLevel = 1;
            assertTrue(resolve(profileWithBuildingChance(1.0f), MultiPos.SINGLE, 3, highEnough),
                    "one level above the highway + 1 is enough");
        }

        @Test
        void theRailwayIsOnlyConsultedWhenThereIsNoHighway() {
            Facts facts = new Facts();
            facts.highway = true;
            facts.highwayLevel = 0;
            facts.railway = true;

            resolve(profileWithBuildingChance(1.0f), MultiPos.SINGLE, 9, facts);
            assertFalse(facts.consulted.contains("hasRailway"),
                    "the highway branch decides, so the railway is never asked about");
        }

        @Test
        void noBuildingSitsAboveAnUndergroundStation() {
            Facts facts = new Facts();
            facts.railway = true;
            facts.railInfo = new Railway.RailChunkInfo(RailChunkType.STATION_UNDERGROUND, Railway.RailDirection.BI, 0, 1);

            assertFalse(resolve(profileWithBuildingChance(1.0f), MultiPos.SINGLE, 9, facts),
                    "an underground station blocks a building however high the city sits");
        }

        @Test
        void railwayHeadroomNeedsTheCityLevelToClearTheRails() {
            Facts tooLow = new Facts();
            tooLow.railway = true;
            tooLow.railInfo = new Railway.RailChunkInfo(RailChunkType.HORIZONTAL, Railway.RailDirection.BI, 1, 1);
            assertFalse(resolve(profileWithBuildingChance(1.0f), MultiPos.SINGLE, 2, tooLow));

            Facts highEnough = new Facts();
            highEnough.railway = true;
            highEnough.railInfo = new Railway.RailChunkInfo(RailChunkType.HORIZONTAL, Railway.RailDirection.BI, 1, 1);
            assertTrue(resolve(profileWithBuildingChance(1.0f), MultiPos.SINGLE, 3, highEnough));
        }

        @Test
        void theGeneralCaseIsABuilding() {
            // Nothing claims the chunk and no constraint fires: the chain's fallback is a building,
            // not a street.
            assertTrue(resolve(profileWithBuildingChance(1.0f), MultiPos.SINGLE, 0, new Facts()));
        }

        @Test
        void theChanceDrawIsTakenBeforeAnyBranch() {
            // The rule that makes this chain safe to insert into: the draw happens up front, so a new
            // stop placed anywhere in the chain cannot move the stream.
            Facts facts = new Facts();
            facts.predefinedBuilding = true;    // the earliest possible exit

            RandomSource used = new XoroshiroRandomSource(42);
            ChunkContentResolver.couldHaveBuilding(profileWithBuildingChance(1.0f), true,
                    MultiPos.SINGLE, 0, used, facts.build());

            RandomSource replay = new XoroshiroRandomSource(42);
            replay.nextFloat();
            assertEquals(replay.nextLong(), used.nextLong(),
                    "even the earliest exit must consume exactly the one building-chance draw");
        }

        @Test
        void aChunkOutsideACityTakesNoDrawAtAll() {
            Facts facts = new Facts();

            RandomSource used = new XoroshiroRandomSource(42);
            assertFalse(ChunkContentResolver.couldHaveBuilding(profileWithBuildingChance(1.0f), false,
                    MultiPos.SINGLE, 0, used, facts.build()));

            assertEquals(new XoroshiroRandomSource(42).nextLong(), used.nextLong(),
                    "a chunk outside a city short-circuits before the draw");
            assertEquals(List.of(), facts.consulted, "and consults nothing");
        }

        @Test
        void theSphereEdgeClampOverridesAnAcceptedCandidate() {
            UrbexProfile space = profileWithBuildingChance(1.0f);
            space.LANDSCAPE_TYPE = LandscapeType.SPACE;

            Facts nearTheEdge = new Facts();
            nearTheEdge.sphereDistance = 0.8f;
            assertFalse(resolve(space, MultiPos.SINGLE, 0, nearTheEdge),
                    "the clamp overrules the chain's accepted building");

            Facts wellInside = new Facts();
            wellInside.sphereDistance = 0.6f;
            assertTrue(resolve(space, MultiPos.SINGLE, 0, wellInside));
        }

        @Test
        void theSphereEdgeClampDoesNotApplyToAMultiBuildingSection() {
            UrbexProfile space = profileWithBuildingChance(1.0f);
            space.LANDSCAPE_TYPE = LandscapeType.SPACE;

            Facts facts = new Facts();
            facts.sphereDistance = 0.9f;
            assertTrue(resolve(space, new MultiPos(1, 0, 2, 2), 0, facts),
                    "a multi-building section is not clamped at the sphere edge");
            assertFalse(facts.consulted.contains("sphereDistance"));
        }
    }

    /** Pass two: the veto, the sphere-centre override, and the street-type roll. */
    @Nested
    class PassTwo {

        private static final ChunkCoord COORD = new ChunkCoord(Level.OVERWORLD, 12, -34);

        /** No neighbour wants to stand alone, so the lonely veto never fires. */
        private static final ChunkContentResolver.PrefersLonely SOCIABLE = neighbour -> 0.0f;

        /** Every neighbour insists on standing alone, so the veto fires on the first draw. */
        private static final ChunkContentResolver.PrefersLonely LONELY = neighbour -> 1.0f;

        private static final long SEED = 987654321L;

        private ChunkContent resolve(boolean couldHaveBuilding, MultiPos section,
                                     ChunkContentResolver.PrefersLonely prefersLonely,
                                     RandomSource rand) {
            return ChunkContentResolver.resolve(TestProfiles.dense(), SEED, rand, true,
                    couldHaveBuilding, RoadType.NONE, section, COORD, prefersLonely, null, "testbuilding");
        }

        /** As {@link #resolve} but with the road and the open-lot park chance under the test's control. */
        private ChunkContent resolveOn(RoadType road, float openLotParkChance, boolean isCity,
                                       boolean couldHaveBuilding) {
            UrbexProfile profile = TestProfiles.dense();
            profile.OPEN_LOT_PARK_CHANCE = openLotParkChance;
            return ChunkContentResolver.resolve(profile, SEED, new XoroshiroRandomSource(5), isCity,
                    couldHaveBuilding, road, MultiPos.SINGLE, COORD, SOCIABLE, null, "testbuilding");
        }

        @Test
        void aRejectedCandidateNeverProducesABuilding() {
            // A predefined street is the strongest of the non-building claims: it rejects the
            // candidate in pass one, and nothing later in the order may hand the chunk back.
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
        void theStreetTypeCostsNoLayoutDraw() {
            // The street type is settled from an addressed hash, so the lonely veto is the only thing
            // in this method that touches the stream. Asserting the stream position is the only way to
            // see that: a non-null street type would also be produced by an implementation that drew.
            RandomSource used = new XoroshiroRandomSource(7);
            ChunkContent content = resolve(true, MultiPos.SINGLE, SOCIABLE, used);
            assertTrue(content.hasBuilding());
            assertNotNull(content.streetType(), "a building chunk settles a street type it never renders");

            RandomSource replay = new XoroshiroRandomSource(7);
            for (int i = 0; i < 4; i++) {
                replay.nextFloat();     // the four lonely-veto draws, none of which fires
            }
            assertEquals(replay.nextLong(), used.nextLong(),
                    "the street type must not consume a layout draw");
        }

        @Test
        void aChunkWithNoBuildingConsumesNothingAtAll() {
            // The veto only runs for a candidate that has a building to lose, so a street chunk leaves
            // the layout stream exactly where it found it.
            RandomSource used = new XoroshiroRandomSource(11);
            assertNotNull(resolve(false, MultiPos.SINGLE, LONELY, used).streetType());
            assertEquals(new XoroshiroRandomSource(11).nextLong(), used.nextLong());
        }

        @Test
        void aPlannedRoadIsNeverAPark() {
            // Even with the park chance at its maximum, paving wins on a road: the chance furnishes an
            // open lot and has no say over any other chunk's surface.
            for (RoadType road : new RoadType[]{RoadType.PRIMARY, RoadType.SECONDARY, RoadType.TERTIARY}) {
                ChunkContent content = resolveOn(road, 1.0f, true, false);
                assertEquals(BuildingInfo.StreetType.NORMAL, content.streetType(), "road " + road);
                assertFalse(content.openLot(), "a road chunk is not an open lot, road " + road);
                assertFalse(content.parkPart(), "a road chunk gets no park part, road " + road);
            }
        }

        @Test
        void everyOpenLotIsGrassWhateverTheParkChanceSays() {
            // The surface is not up for negotiation. Scattering street parts through the middle of a
            // city block is the artefact the road field exists to remove, so an open lot renders as a
            // park at a chance of 0 exactly as it does at 1.
            for (float chance : new float[]{0.0f, 0.5f, 1.0f}) {
                ChunkContent content = resolveOn(RoadType.NONE, chance, true, false);
                assertEquals(BuildingInfo.StreetType.PARK, content.streetType(), "chance " + chance);
                assertTrue(content.openLot(), "chance " + chance);
            }
        }

        @Test
        void theParkChanceDecidesOnlyWhetherTheLotIsFurnished() {
            assertTrue(resolveOn(RoadType.NONE, 1.0f, true, false).parkPart());
            assertFalse(resolveOn(RoadType.NONE, 0.0f, true, false).parkPart());
        }

        @Test
        void onlyACityChunkIsAnOpenLot() {
            ChunkContent wilderness = resolveOn(RoadType.NONE, 1.0f, false, false);
            assertFalse(wilderness.openLot(), "wilderness is not a vacant lot");
            assertFalse(wilderness.parkPart(), "and nothing furnishes it");
        }

        @Test
        void aBuildingChunkIsNotAnOpenLot() {
            ChunkContent content = resolveOn(RoadType.NONE, 1.0f, true, true);
            assertFalse(content.openLot());
            assertFalse(content.parkPart(), "a park part under a building would never be seen");
        }

        @Test
        void aVetoedBuildingLeavesAnOpenLotBehind() {
            // openLot reads the settled verdict, not the pass-one candidate: a chunk whose building a
            // lonely neighbour took away is as empty as one that failed the roll.
            ChunkContent content = resolve(true, MultiPos.SINGLE, LONELY, new XoroshiroRandomSource(3));
            assertFalse(content.hasBuilding());
            assertTrue(content.openLot());
        }

        @Test
        void aMultiBuildingSectionInheritsInsteadOfRolling() {
            // A non-top-left section of a multi-building copies everything from the top-left chunk.
            // It must touch neither the lonely veto nor the street roll, or its layout stream
            // desyncs.
            RandomSource used = new XoroshiroRandomSource(99);
            ChunkContent content = resolve(true, new MultiPos(1, 0, 2, 2), LONELY, used);

            assertTrue(content.hasBuilding(), "the veto does not apply to a multi-building section");
            assertNull(content.streetType(), "a multi-building section inherits its street type");
            assertEquals(new XoroshiroRandomSource(99).nextLong(), used.nextLong(),
                    "a multi-building section must consume no layout draws at all");
        }

        @Test
        void theSphereCentreOverridesTheBuildingRoll() {
            ChunkContent street = ChunkContentResolver.resolve(TestProfiles.dense(), SEED,
                    new XoroshiroRandomSource(3), true, true, RoadType.NONE, MultiPos.SINGLE, COORD, SOCIABLE,
                    CitySphereSettings.CitySphereCenterType.STREET, "testbuilding");
            assertFalse(street.hasBuilding(), "a STREET sphere centre removes the building");

            ChunkContent building = ChunkContentResolver.resolve(TestProfiles.dense(), SEED,
                    new XoroshiroRandomSource(3), true, false, RoadType.NONE, MultiPos.SINGLE, COORD, LONELY,
                    CitySphereSettings.CitySphereCenterType.BUILDING, "testbuilding");
            assertTrue(building.hasBuilding(), "a BUILDING sphere centre forces a building");
        }
    }
}
