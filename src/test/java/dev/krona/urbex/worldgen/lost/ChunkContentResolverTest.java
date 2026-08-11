package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.MultiBuildingStreetConflict;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.plan.EffectiveRoad;
import dev.krona.urbex.plan.RoadType;
import dev.krona.urbex.plan.grid.GridRoadField;
import dev.krona.urbex.plan.grid.GridSettings;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static Preset profileWithBuildingChance(float chance) {
        Preset profile = TestProfiles.dense();
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
                        () -> record("railInfo", railInfo));
            }

            private <T> T record(String fact, T value) {
                consulted.add(fact);
                return value;
            }
        }

        private boolean resolve(Preset profile, MultiPos section, int cityLevel, Facts facts) {
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

    }

    /** Pass two: the lonely veto, open-lot park roll, and street selection. */
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
                    couldHaveBuilding, RoadType.NONE, section, COORD, prefersLonely, "testbuilding");
        }

        /** As {@link #resolve} but with the road and the open-lot park chance under the test's control. */
        private ChunkContent resolveOn(RoadType road, float openLotParkChance, boolean isCity,
                                       boolean couldHaveBuilding) {
            Preset profile = TestProfiles.dense();
            profile.OPEN_LOT_PARK_CHANCE = openLotParkChance;
            return ChunkContentResolver.resolve(profile, SEED, new XoroshiroRandomSource(5), isCity,
                    couldHaveBuilding, road, MultiPos.SINGLE, COORD, SOCIABLE, "testbuilding");
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

    }

    /**
     * Design spec §4.1: "The planner reads nothing. {@code EffectiveRoad} reads only
     * {@code isCityRaw}, which does not depend on buildings. {@code MultiChunk} reads raw only.
     * Therefore querying roads first or multibuildings first cannot change either answer. This is
     * pinned by a test, not just documented."
     * <p>
     * An order-permutation test over {@link GridRoadField}/{@link EffectiveRoad}/
     * {@link ChunkContentResolver} was tried first here and rejected on review: all three are
     * final, field-free, static-only or otherwise stateless, so no permutation of calls into them
     * can ever disagree with itself - what such a test checks is referential transparency, not
     * cycle-freedom, and {@code GridRoadFieldTest.queryOrderCannotChangeTheAnswer} already pins
     * {@link GridRoadField}'s own order-independence far more strongly, over 6,400 real
     * {@code at()} calls in shuffled order. What actually keeps the design acyclic is a fact about
     * {@link MultiChunk}'s <em>source</em>, not about any pure function's output, so that is what
     * is pinned instead:
     * <ol>
     *   <li>{@link #multiBuildingAcceptanceWouldDifferIfItReadTheEffectiveRoadInsteadOfTheRawOne}
     *       shows the substitution the structural guard below forbids is not a no-op - there
     *       really are chunks in range where
     *       {@code MULTI_BUILDING_STREET_CONFLICT.roadBlocks} disagrees between the raw and the
     *       effective road, using the real {@link GridRoadField} and the real
     *       {@link EffectiveRoad#resolve};</li>
     *   <li>{@link #multiChunkReadsTheRawRoadFieldNotTheEffectiveOne} pins {@link MultiChunk}'s
     *       actual source text: it must call {@code roadField().typeAt(...)} and must never call
     *       {@code BuildingInfo.getEffectiveRoadType(...)} - the one substitution that would
     *       create the real cycle the comment at {@code MultiChunk.java}'s raw-road read guards
     *       by hand: multi-building acceptance depending on a content decision that itself
     *       depends on multi-building acceptance.</li>
     * </ol>
     * {@code MultiChunk}'s own random building <em>selection</em> stays out of reach either way -
     * it needs a datapack-loaded level ({@code AssetRegistries} resolves through
     * {@code CommonLevelAccessor}, and {@code NullDimensionInfo.getWorld()} is {@code null}) that
     * this suite deliberately keeps out (see the class javadoc). Both tests below reuse the real
     * conflict <em>rule</em>, {@code MULTI_BUILDING_STREET_CONFLICT.roadBlocks}, rather than
     * reimplementing it, and the second reads {@code MultiChunk}'s own file rather than asserting
     * anything about its behaviour indirectly.
     */
    @Nested
    class CycleFreedom {

        @Test
        void multiBuildingAcceptanceWouldDifferIfItReadTheEffectiveRoadInsteadOfTheRawOne() {
            GridRoadField roadField = new GridRoadField(1337L, "urbex:test", GridSettings.defaults());
            MultiBuildingStreetConflict conflict = profileWithBuildingChance(0.5f).MULTI_BUILDING_STREET_CONFLICT;

            int divergences = 0;
            for (int x = -40; x < 40; x++) {
                for (int z = -40; z < 40; z++) {
                    boolean isCity = Math.floorMod(x * 7 + z * 13, 3) != 0;
                    RoadType raw = roadField.typeAt(x, z);
                    RoadType effective = EffectiveRoad.resolve(raw, isCity, isCity, false);
                    if (conflict.roadBlocks(raw) != conflict.roadBlocks(effective)) {
                        divergences++;
                    }
                }
            }
            assertTrue(divergences > 0,
                    "expected the raw and effective road to disagree on whether a multi-building "
                            + "conflicts somewhere in range - otherwise MultiChunk reading raw instead of "
                            + "effective would be an arbitrary choice, not a load-bearing one, and the "
                            + "structural guard below would be pinning nothing that matters");
        }

        @Test
        void multiChunkReadsTheRawRoadFieldNotTheEffectiveOne() throws IOException {
            Path file = Path.of("src/main/java/dev/krona/urbex/worldgen/lost/MultiChunk.java");
            String source = stripComments(Files.readString(file));
            String body = extractMethodBody(source, "private boolean canPlaceBuilding(");
            assertTrue(body.contains("roadField().typeAt("),
                    "MultiChunk.canPlaceBuilding must read the raw road field via roadField().typeAt(...)");
            assertFalse(body.contains("getEffectiveRoadType"),
                    "MultiChunk.canPlaceBuilding must never call BuildingInfo.getEffectiveRoadType(...) - "
                            + "doing so would make multi-building acceptance depend on a content decision "
                            + "that itself depends on multi-building acceptance");
        }

        /**
         * Comments are stripped before searching, not just the code: the guarded-against read is
         * itself named in a comment right next to the real one ("The RAW road, never
         * BuildingInfo.getEffectiveRoadType()."), so a plain substring search over the raw file
         * would trip on its own warning.
         */
        private static String stripComments(String source) {
            String withoutBlockComments = source.replaceAll("(?s)/\\*.*?\\*/", "");
            return withoutBlockComments.replaceAll("//[^\n]*", "");
        }

        /**
         * Scoped to the named method's body, not the whole file: a {@code roadField().typeAt(...)}
         * call that migrated elsewhere in {@code MultiChunk.java} (leaving {@code canPlaceBuilding}
         * itself reading the effective road, or nothing at all) would still satisfy a whole-file
         * substring search vacuously. {@code declarationPrefix} must be specific enough to match the
         * declaration and not a call site - a bare method name is not, since {@code canPlaceBuilding}
         * is called (not declared) earlier in this same file. Finds that declaration, then
         * brace-matches from its opening {@code {} to the corresponding closing one.
         */
        private static String extractMethodBody(String source, String declarationPrefix) {
            int declaration = source.indexOf(declarationPrefix);
            assertTrue(declaration >= 0, "could not find a declaration matching \"" + declarationPrefix
                    + "\" to scope the search to");
            int openBrace = source.indexOf('{', declaration);
            assertTrue(openBrace >= 0, "could not find the opening brace after \"" + declarationPrefix + "\"");
            int depth = 0;
            for (int i = openBrace; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return source.substring(openBrace, i + 1);
                    }
                }
            }
            throw new AssertionError("unbalanced braces while scanning \"" + declarationPrefix + "\" for its body");
        }
    }
}
