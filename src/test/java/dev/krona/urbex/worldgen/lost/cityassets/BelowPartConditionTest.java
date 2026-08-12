package dev.krona.urbex.worldgen.lost.cityassets;

import com.mojang.datafixers.util.Either;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.regassets.BuildingRE;
import dev.krona.urbex.worldgen.lost.regassets.data.ConditionTest;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartRef;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BelowPartConditionTest {

    private static Predicate<ConditionContext> testWith(Set<String> belowPart, Set<String> inpart) {
        ConditionTest test = new ConditionTest(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.ofNullable(belowPart), Optional.ofNullable(inpart),
                Optional.empty(), Optional.empty(), Optional.empty());
        return ConditionContext.parseTest(test);
    }

    private static ConditionContext context(String part, String belowPart) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        ChunkCoord coord = new ChunkCoord(dimension, 0, 0);
        return new ConditionContext(0, 1, 0, 5, part, belowPart, "somebuilding", coord) {
            @Override
            public boolean isBuilding() {
                return true;
            }

            @Override
            public Identifier getBiome() {
                return Identifier.fromNamespaceAndPath("minecraft", "plains");
            }
        };
    }

    @Test
    public void belowpartMatchesThePartBelow() {
        Predicate<ConditionContext> pred = testWith(Set.of("top_floor"), null);
        assertTrue(pred.test(context("roof", "top_floor")));
    }

    @Test
    public void belowpartDoesNotMatchTheCurrentPart() {
        // The old implementation read context.getPart(), making belowpart an exact duplicate
        // of inpart (issue #58): this predicate would wrongly match a part named "roof" that
        // sits on anything.
        Predicate<ConditionContext> pred = testWith(Set.of("roof"), null);
        assertFalse(pred.test(context("roof", "top_floor")));
    }

    @Test
    public void inpartStillMatchesTheCurrentPart() {
        Predicate<ConditionContext> pred = testWith(null, Set.of("roof"));
        assertTrue(pred.test(context("roof", "top_floor")));
    }

    // ------------------------------------------------------------ the writing side
    //
    // Everything above is the reading side: given a context, does parseTest ask it the right
    // question. That was issue #58's fix. The same defect survived on the writing side - all three
    // floor loops (BuildingInfo's two copies and Scattered) advanced their belowPart local to the
    // part just chosen *before* building the context that selects parts2[], so parts2[] saw
    // getBelowPart() == getPart() and its "belowpart" was an exact duplicate of its "inpart".
    // Scattered had the third variant: it reused the parts[] context outright, so parts2[]'s
    // "inpart" was matched against NO_PART and could never fire at all.
    //
    // No golden can catch a revert of either: nothing in the bundled pack writes "belowpart", and
    // no scattered-reachable building declares parts2. These pin it instead. Building.getRandomPart2
    // now takes the chosen part and derives its own context, so a caller cannot supply a poisoned
    // one - the assertions below are on that production path, not on a copy of the loop.

    private static final String FLOOR = "urbex:floor";
    private static final String BELOW = "urbex:below_floor";

    /** A building whose single {@code parts2} entry fires only on the given condition. */
    private static Building buildingWithParts2Condition(Set<String> belowPart, Set<String> inpart) {
        PartRef floor = partRef(FLOOR, null, null);
        PartRef second = partRef("urbex:second_part", belowPart, inpart);
        return new Building(TestAssetId.ANY, BuiltInRegistries.BLOCK, null, AssetIndex.empty("urbex:palettes"), List.of(new BuildingRE(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of('#'), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new Mergeable<>(true, List.of(floor))),
                Optional.of(new Mergeable<>(true, List.of(second))))));
    }

    private static PartRef partRef(String part, Set<String> belowPart, Set<String> inpart) {
        return new PartRef(part, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                belowPart == null ? Optional.empty() : Optional.of(Either.left(List.copyOf(belowPart))),
                inpart == null ? Optional.empty() : Optional.of(Either.left(List.copyOf(inpart))),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** The context a floor loop hands to {@code getRandomPart}: no current part yet, the floor below known. */
    private static ConditionContext floorContext() {
        return context(ConditionContext.NO_PART, BELOW);
    }

    @Test
    public void parts2SeesTheFloorsOwnPartAsTheCurrentPart() {
        // Scattered used to reuse the parts[] context, whose part is NO_PART, so this never fired.
        Building building = buildingWithParts2Condition(null, Set.of(FLOOR));

        assertEquals("urbex:second_part",
                building.getRandomPart2(RandomSource.create(1L), floorContext(), FLOOR));
    }

    @Test
    public void parts2StillSeesTheFloorBelowAsTheBelowPart() {
        Building building = buildingWithParts2Condition(Set.of(BELOW), null);

        assertEquals("urbex:second_part",
                building.getRandomPart2(RandomSource.create(1L), floorContext(), FLOOR));
    }

    @Test
    public void parts2BelowpartIsNotADuplicateOfItsInpart() {
        // The defect: with belowPart advanced early, getBelowPart() returned FLOOR too and this
        // "belowpart": FLOOR predicate matched a floor sitting on urbex:below_floor.
        Building building = buildingWithParts2Condition(Set.of(FLOOR), null);

        assertNull(building.getRandomPart2(RandomSource.create(1L), floorContext(), FLOOR));
    }

    @Test
    public void withPartReplacesOnlyTheCurrentPartAndDelegatesTheRest() {
        ConditionContext floor = floorContext();
        ConditionContext parts2 = floor.withPart(FLOOR);

        assertEquals(FLOOR, parts2.getPart());
        assertEquals(BELOW, parts2.getBelowPart(), "the part below is carried over, never overwritten");
        assertEquals(floor.getBuilding(), parts2.getBuilding());
        assertEquals(floor.getLevel(), parts2.getLevel());
        assertEquals(floor.getFloor(), parts2.getFloor());
        assertEquals(floor.getFloorsBelowGround(), parts2.getFloorsBelowGround());
        assertEquals(floor.getFloorsAboveGround(), parts2.getFloorsAboveGround());
        assertEquals(floor.getChunkX(), parts2.getChunkX());
        assertEquals(floor.getChunkZ(), parts2.getChunkZ());
        // isBuilding() and getBiome() are overridden per call site, so they must delegate rather
        // than be recomputed from the copied fields.
        assertTrue(parts2.isBuilding());
        assertEquals(floor.getBiome(), parts2.getBiome());
    }
}
