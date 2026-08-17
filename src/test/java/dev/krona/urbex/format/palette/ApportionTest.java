package dev.krona.urbex.format.palette;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.varia.Rng;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sizes, exact rationals, rounding and selection - {@code 05-weights.md} §2, §5, §6 and §7.
 * <p>
 * The specification's own fixtures decide whether a document is accepted; almost nothing here is about
 * that. What a fixture cannot state is the <em>distribution</em> a document compiles to, and every
 * invariant in this document is about one: that the arithmetic is exact ({@code WEIGHT.052}), that
 * nesting and flattening agree ({@code WEIGHT.053}), that declaration order does not decide the answer
 * ({@code WEIGHT.015}), and that selection is addressed rather than drawn ({@code WEIGHT.042}).
 */
class ApportionTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- Sizes ---------------------------------------------------------------------------------

    /**
     * {@code WEIGHT.005}: the size rules are evaluated on the list after {@code $spread} expansion, not
     * on the choices as written.
     * <p>
     * The case the rule's {@code > Why} names: "a file may write one {@code share} and take the rest of
     * its list from a spread, so the written text is not the list". Written alone the list is one share
     * of 0.25 and nothing else, which {@code WEIGHT.014} refuses; expanded it is that share and three
     * weights, which is correct. Both directions are asserted, because a check that runs on the written
     * text passes the second half and fails the first.
     */
    @Test
    @Rule("WEIGHT.005")
    @Rule("WEIGHT.017")
    @Rule("WEIGHT.018")
    void everySizeRuleIsEvaluatedOnTheListAfterASpreadHasBeenExpanded() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "walls": { "kind": "weighted", "choices": [
                      { "weight": 3, "block": "minecraft:stone_bricks" },
                      { "weight": 2, "block": "minecraft:mossy_stone_bricks" },
                      { "weight": 1, "block": "minecraft:cracked_stone_bricks" } ] } },
                  "palette": { "#": { "kind": "weighted", "choices": [
                      { "$spread": "walls#/choices" },
                      { "share": 0.25, "block": "minecraft:cobweb" } ] } } }
                """);
        Diagnostics diagnostics = new Diagnostics();
        assertTrue(Apportion.checkSizes(node(resolved, '#'), siteOf(new Marker('#')), diagnostics),
                () -> "a correct file must not be refused: " + diagnostics.asError().orElse(""));

        // WEIGHT.017 and WEIGHT.018: the added share takes exactly its fraction and the spread choices
        // divide what is left in their existing 3:2:1 proportions - the spread does not rescale it.
        assertEquals(List.of(Fraction.of(3, 8), Fraction.of(1, 4), Fraction.of(1, 8),
                        Fraction.of(1, 4)),
                shares(resolved, '#'));
    }

    /** {@code WEIGHT.013}: more than one {@code rest}, or a {@code rest} beside a {@code weight}. */
    /**
     * {@code WEIGHT.016}: a weight added to a spread list of weights takes its proportional part of the
     * combined total, whatever that total is.
     * <p>
     * This is what §2.1 says the three sizes exist for, and the assertion is that the added choice's
     * fraction is read off the <em>sum</em> rather than off anything the descendant could have known.
     * Version 1 could not express it at all: its weights were absolute slot counts filled in declaration
     * order, so the shipped workstation list - which totals 65 before its sentinel - would have given a
     * seventh choice weighted 30 exactly 8 slots, and a list already at 128 would have given it none.
     */
    @Test
    @Rule("WEIGHT.011")
    @Rule("WEIGHT.016")
    void aWeightAddedToASpreadListOfWeightsTakesItsPartOfTheCombinedTotal() {
        assertEquals(List.of(Fraction.of(3, 10), Fraction.of(2, 10), Fraction.of(1, 10),
                        Fraction.of(4, 10)),
                shares(resolve("""
                        { "version": 2,
                          "$defs": { "walls": { "kind": "weighted", "choices": [
                              { "weight": 3, "block": "minecraft:stone_bricks" },
                              { "weight": 2, "block": "minecraft:mossy_stone_bricks" },
                              { "weight": 1, "block": "minecraft:cracked_stone_bricks" } ] } },
                          "palette": { "#": { "kind": "weighted", "choices": [
                              { "$spread": "walls#/choices" },
                              { "weight": 4, "block": "minecraft:cobweb" } ] } } }
                        """), '#'),
                "four tenths of a total of ten, and the inherited three keep their 3:2:1");
    }

    @Test
    @Rule("WEIGHT.013")
    void moreThanOneRestOrARestBesideAWeightIsRefusedOnTheExpandedList() {
        assertEquals(Diag.DIAG_041, refusalOf("""
                { "version": 2,
                  "$defs": { "walls": { "kind": "weighted", "choices": [
                      { "weight": 3, "block": "minecraft:stone_bricks" } ] } },
                  "palette": { "#": { "kind": "weighted", "choices": [
                      { "$spread": "walls#/choices" },
                      { "rest": true, "block": "minecraft:cobweb" } ] } } }
                """).diag(), "a rest the file wrote beside a weight the spread brought in");

        assertEquals(Diag.DIAG_041, refusalOf("""
                { "version": 2,
                  "$defs": { "ends": { "kind": "weighted", "choices": [
                      { "rest": true, "block": "minecraft:stone_bricks" } ] } },
                  "palette": { "#": { "kind": "weighted", "choices": [
                      { "$spread": "ends#/choices" },
                      { "rest": true, "block": "minecraft:cobweb" } ] } } }
                """).diag());
    }

    /**
     * {@code WEIGHT.014}: shares reaching 1 beside something that takes the remainder, and shares not
     * reaching 1 beside nothing that does.
     */
    @Test
    @Rule("WEIGHT.014")
    void sharesMustLeaveARemainderWhenSomethingTakesItAndMustTotalOneWhenNothingDoes() {
        // Each spread source is a complete, legal list of its own - a partial one is refused at decode,
        // which is what makes a spread the only way to reach this check at all.
        Refusal reaching = refusalOf("""
                { "version": 2,
                  "$defs": { "walls": { "kind": "weighted", "choices": [
                      { "share": 0.6, "block": "minecraft:stone_bricks" },
                      { "share": 0.4, "block": "minecraft:mossy_stone_bricks" } ] } },
                  "palette": { "#": { "kind": "weighted", "choices": [
                      { "$spread": "walls#/choices" },
                      { "rest": true, "block": "minecraft:cobweb" } ] } } }
                """);
        assertEquals(Diag.DIAG_045, reaching.diag());
        assertTrue(reaching.message().contains("shares total 1 - 0 written here and 1 spread from"),
                reaching.message());
        assertTrue(reaching.message().contains("leave something for the weight choices"),
                reaching.message());

        Refusal notOne = refusalOf("""
                { "version": 2,
                  "$defs": { "walls": { "kind": "weighted", "choices": [
                      { "share": 0.5, "block": "minecraft:stone_bricks" },
                      { "share": 0.5, "block": "minecraft:mossy_stone_bricks" } ] } },
                  "palette": { "#": { "kind": "weighted", "choices": [
                      { "$spread": "walls#/choices" },
                      { "share": 0.4, "block": "minecraft:andesite" } ] } } }
                """);
        assertEquals(Diag.DIAG_045, notOne.diag());
        assertTrue(notOne.message().contains("shares total 1.4"), notOne.message());
        assertTrue(notOne.message().contains("total exactly 1"), notOne.message());
    }

    /**
     * {@code WEIGHT.019}: a spread that brings a list's shares to 1 is refused naming the incoming and
     * inherited totals separately.
     * <p>
     * The rule exists because of what the message would otherwise be. "Shares total 1.1" is a true
     * sentence about a file in which the author can see {@code 0.4} and nothing else, and it "sends an
     * author looking through their own four lines for a number that came from a file they did not
     * write". So the assertion is not only that the total is named but that <em>both halves</em> are, and
     * that the pointer the other half arrived through is named with them.
     */
    @Test
    @Rule("WEIGHT.019")
    void aSpreadThatBringsTheSharesToOneNamesTheWrittenAndInheritedTotalsSeparately() {
        Refusal refusal = refusalOf("""
                { "version": 2,
                  "$defs": { "walls": { "kind": "weighted", "choices": [
                      { "share": 0.4, "block": "minecraft:stone_bricks" },
                      { "share": 0.3, "block": "minecraft:mossy_stone_bricks" },
                      { "rest": true, "block": "minecraft:andesite" } ] } },
                  "palette": { "#": { "kind": "weighted", "choices": [
                      { "$spread": "walls#/choices" },
                      { "share": 0.4, "block": "minecraft:cobweb" } ] } } }
                """);
        assertEquals(Diag.DIAG_045, refusal.diag());
        assertTrue(refusal.message().contains("shares total 1.1"), refusal.message());
        assertTrue(refusal.message().contains("0.4 written here"), refusal.message());
        assertTrue(refusal.message().contains("0.7 spread from 'walls#/choices'"),
                refusal.message());
    }

    // ---- Exact rationals -----------------------------------------------------------------------

    /**
     * {@code WEIGHT.052}: the arithmetic is exact, and a {@code double} cannot do it.
     * <p>
     * The shipped workstation list is the measurement. Its six shares sum to {@code 0.52} as an author
     * reads them and to {@code 0.52000000000000002} in binary floating point, so a {@code rest} computed
     * as {@code 1 - sum} would be {@code 0.47999999999999998} rather than {@code 0.48} - and the
     * comparison against 1 that {@code WEIGHT.014} performs would be a statement about IEEE 754.
     */
    @Test
    @Rule("WEIGHT.010")
    @Rule("WEIGHT.012")
    @Rule("WEIGHT.052")
    void theArithmeticIsExactRatherThanFloatingPoint() {
        List<Apportion.Leaf> leaves = Apportion.flatten(weighted(resolve("""
                { "version": 2, "palette": { "F": { "kind": "weighted", "choices": [
                    { "share": 0.20, "block": "minecraft:furnace[facing=north]" },
                    { "share": 0.16, "block": "minecraft:crafting_table" },
                    { "share": 0.05, "block": "minecraft:brewing_stand" },
                    { "share": 0.05, "block": "minecraft:anvil[facing=north]" },
                    { "share": 0.04, "block": "minecraft:cauldron" },
                    { "share": 0.02, "block": "minecraft:enchanting_table" },
                    { "rest": true,  "block": "minecraft:cobweb" } ] } } }
                """), 'F'));

        assertEquals(Fraction.of(12, 25), leaves.get(6).share(), "the rest is exactly 0.48");
        Fraction total = leaves.stream().map(Apportion.Leaf::share)
                .reduce(Fraction.ZERO, Fraction::plus);
        assertEquals(Fraction.ONE, total, "and the whole list is exactly 1");

        double asDoubles = 0.20 + 0.16 + 0.05 + 0.05 + 0.04 + 0.02;
        assertNotEquals(0.52, asDoubles, 0.0,
                "the measurement this rule exists for: the same sum in binary floating point");
    }

    /**
     * {@code WEIGHT.050} and {@code WEIGHT.051}: a nested node contributes its own distribution scaled by
     * its share, and its {@code rest} is resolved against its own list.
     */
    @Test
    @Rule("WEIGHT.050")
    @Rule("WEIGHT.051")
    void aNestedNodeContributesItsOwnDistributionScaledByItsShareOfItsParent() {
        assertEquals(List.of(Fraction.of(3, 4), Fraction.of(1, 20), Fraction.of(1, 5)),
                shares(resolve("""
                        { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                            { "share": 0.75, "block": "minecraft:stone_bricks" },
                            { "rest": true, "kind": "weighted", "choices": [
                                { "share": 0.2, "block": "minecraft:cobweb" },
                                { "rest": true, "block": "minecraft:mossy_stone_bricks" } ] } ] } } }
                        """), '#'),
                "the nested rest is 0.8 of the outer rest's 0.25, not 0.8 of the whole node");
    }

    /**
     * {@code WEIGHT.053}: a nested tree's compiled distribution equals the flattened equivalent's to
     * within one slot per choice.
     * <p>
     * The two documents state the same distribution in the two spellings the format offers, and the
     * strong assertion is available because {@code WEIGHT.052} makes the flattened fractions <em>equal</em>
     * rather than close: the invariant allows one slot of drift, and there is none, because there is only
     * one rounding step either way.
     */
    @Test
    @Rule("WEIGHT.053")
    void aNestedTreesDistributionEqualsItsFlattenedEquivalentsToWithinOneSlot() {
        List<Fraction> nested = shares(resolve("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "weight": 3, "block": "minecraft:stone_bricks" },
                    { "weight": 1, "kind": "weighted", "choices": [
                        { "share": 0.3, "block": "minecraft:cobweb" },
                        { "weight": 2, "block": "minecraft:andesite" },
                        { "weight": 5, "block": "minecraft:mossy_stone_bricks" } ] } ] } } }
                """), '#');
        List<Fraction> flat = shares(resolve("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "share": 0.75, "block": "minecraft:stone_bricks" },
                    { "share": 0.075, "block": "minecraft:cobweb" },
                    { "weight": 2, "block": "minecraft:andesite" },
                    { "weight": 5, "block": "minecraft:mossy_stone_bricks" } ] } } }
                """), '#');
        assertEquals(flat, nested, "the same distribution, exactly, in the two spellings");

        int[] nestedSlots = Apportion.slots(nested, Apportion.SLOTS);
        int[] flatSlots = Apportion.slots(flat, Apportion.SLOTS);
        for (int index = 0; index < flatSlots.length; index++) {
            assertTrue(Math.abs(nestedSlots[index] - flatSlots[index]) <= 1,
                    "choice " + index + ": " + Arrays.toString(nestedSlots) + " vs "
                            + Arrays.toString(flatSlots));
        }
    }

    // ---- Rounding ------------------------------------------------------------------------------

    /** {@code WEIGHT.060} and {@code WEIGHT.061}: largest remainder, ties low, every slot assigned. */
    @Test
    @Rule("WEIGHT.060")
    @Rule("WEIGHT.061")
    void slotsGoByLargestRemainderWithTiesToTheLowestIndexAndNoneLeftOver() {
        // Three equal thirds of 128: 42 each and two slots over, whose remainders are all equal - so
        // the two go to the two lowest indices and nothing is decided by anything else.
        assertArrayEquals(new int[]{43, 43, 42},
                Apportion.slots(List.of(Fraction.of(1, 3), Fraction.of(1, 3), Fraction.of(1, 3)),
                        128));

        int[] slots = Apportion.slots(List.of(Fraction.of(1, 7), Fraction.of(2, 7),
                Fraction.of(4, 7)), 128);
        assertEquals(128, Arrays.stream(slots).sum(), "WEIGHT.061: every slot is assigned");
        // 18 + 2/7, 36 + 4/7 and 73 + 1/7: the one spare slot goes to the largest remainder, which is
        // the middle share, and not to the largest share.
        assertArrayEquals(new int[]{18, 37, 73}, slots);
    }

    /**
     * {@code WEIGHT.062}: a choice whose exact share rounds below one slot still gets one, and the
     * deficit comes from the largest.
     * <p>
     * "A choice an author wrote and weighted is a choice they want to see. Silently rounding it out of
     * existence makes a weight of 1 in a long list mean nothing, with no diagnostic." A weight of 1
     * against a weight of 100000 is {@code 0.00128} of a slot, which every rounding rule in §7 would
     * otherwise send to zero.
     */
    @Test
    @Rule("WEIGHT.062")
    void aChoiceRoundingBelowOneSlotStillGetsOneAndTheDeficitComesFromTheLargest() {
        int[] slots = Apportion.slots(
                List.of(Fraction.of(100000, 100002), Fraction.of(1, 100002),
                        Fraction.of(1, 100002)), 128);
        assertArrayEquals(new int[]{126, 1, 1}, slots);
        assertEquals(128, Arrays.stream(slots).sum());
    }

    /**
     * {@code WEIGHT.015}: the distribution of a list does not depend on the order its choices are
     * declared in.
     * <p>
     * Shuffled twenty times with a fixed seed, and each permutation's slot counts are compared back
     * against the choice they belong to. The shares are chosen so that no two remainders are equal,
     * because {@code WEIGHT.064} allows declaration order to decide exactly one thing - a tie - and a
     * list with a tie in it is the one case where "does not depend on the order" holds only to within the
     * one slot that rule permits. Both halves are asserted: exact equality with no tie, and one slot with
     * one.
     */
    @Test
    @Rule("WEIGHT.015")
    @Rule("WEIGHT.064")
    void shufflingAListsDeclarationOrderDoesNotChangeItsDistribution() {
        List<Fraction> shares = List.of(Fraction.of(37, 128), Fraction.of(11, 64),
                Fraction.of(3, 32), Fraction.of(7, 16), Fraction.of(1, 128));
        Map<Fraction, Integer> expected = slotsByShare(shares);

        Random shuffle = new Random(20260817L);
        for (int round = 0; round < 20; round++) {
            List<Fraction> permuted = new ArrayList<>(shares);
            Collections.shuffle(permuted, shuffle);
            assertEquals(expected, slotsByShare(permuted),
                    "permutation " + permuted + " changed the distribution");
        }

        // The tie case, and the bound WEIGHT.064 puts on it: three equal thirds hand their two spare
        // slots to whichever two indices are lowest, so a share's count moves by one and never more.
        int[] tied = Apportion.slots(List.of(Fraction.of(1, 3), Fraction.of(1, 3),
                Fraction.of(1, 3)), 128);
        assertEquals(1, tied[0] - tied[2]);
    }

    /**
     * {@code WEIGHT.063}: more than 128 alternatives after exclusion is refused, because
     * {@code WEIGHT.062} cannot be satisfied.
     * <p>
     * Both shapes are driven, and they are the same refusal: a single list of 129 choices, and a nested
     * tree of three lists of 50 in which no list is anywhere near 128. The second is the one the rule's
     * own wording does not reach and its {@code > Why} does - a report entry says so - since it is the
     * flattened count, not any one list's length, that decides whether every alternative can have a slot.
     */
    @Test
    @Rule("WEIGHT.063")
    void moreAlternativesThanSlotsIsRefusedBecauseEveryOneOfThemIsOwedASlot() {
        assertEquals(Diag.DIAG_044, materialisationRefusalOf(generatedList(129)).diag());

        Refusal nested = materialisationRefusalOf(generatedTree(3, 50));
        assertEquals(Diag.DIAG_044, nested.diag());
        assertTrue(nested.message().contains("150 choices"), nested.message());

        // And 128 exactly is accepted, with every one of them holding a slot: the boundary is where
        // WEIGHT.062 stops being satisfiable, not one short of it.
        ResolvedNode[] slots = materialise(generatedList(128));
        assertEquals(128, Arrays.stream(slots).distinct().count());
    }

    /**
     * {@code WEIGHT.040}: a weighted node compiles to exactly 128 slots, and every one of them holds a
     * node.
     */
    @Test
    @Rule("WEIGHT.040")
    void aWeightedNodeCompilesToExactlyOneHundredAndTwentyEightSlots() {
        ResolvedNode[] slots = materialise("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "share": 0.08, "block": "minecraft:cracked_stone_bricks" },
                    { "share": 0.07, "block": "minecraft:mossy_stone_bricks" },
                    { "rest": true,  "block": "minecraft:stone_bricks" } ] } } }
                """);
        assertEquals(128, slots.length);
        assertTrue(Arrays.stream(slots).allMatch(java.util.Objects::nonNull));

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ResolvedNode slot : slots) {
            counts.merge(((ResolvedNode.Source.Block) slot.source()).block(), 1, Integer::sum);
        }
        assertEquals(Map.of("minecraft:cracked_stone_bricks", 10,
                "minecraft:mossy_stone_bricks", 9, "minecraft:stone_bricks", 109), counts);
    }

    // ---- Selection -----------------------------------------------------------------------------

    /**
     * {@code WEIGHT.041}: the marker is part of the address, so two markers place their minority choices
     * at different offsets.
     * <p>
     * Without it "every weighted marker resolves to the same slot index at a given block, so a
     * mossy-cobble wall and a cracked-brick floor put their variants at identical offsets: one spatial
     * pattern shared by the whole palette instead of one per marker". The assertion is that shape: over a
     * volume of positions, two markers agree about as often as chance allows and not always.
     */
    @Test
    @Rule("WEIGHT.041")
    void twoMarkersPlaceTheirMinorityChoicesAtDifferentOffsets() {
        int agreements = 0;
        int positions = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    int first = Rng.paletteSlotAt(1337L, '#', x, y, z, 128);
                    int second = Rng.paletteSlotAt(1337L, 'F', x, y, z, 128);
                    agreements += first == second ? 1 : 0;
                    positions++;
                }
            }
        }
        assertTrue(agreements < positions / 8,
                "two markers shared a slot index at " + agreements + " of " + positions
                        + " positions, which is the one-pattern-per-palette failure");
    }

    /**
     * {@code WEIGHT.042}: selection draws from no sequential stream, so a position's result does not
     * depend on how many other positions were resolved first or in what order.
     */
    @Test
    @Rule("WEIGHT.042")
    void selectionIsAddressedSoResolutionOrderCannotChangeIt() {
        List<int[]> positions = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                positions.add(new int[]{x, 64, z});
            }
        }
        Map<String, Integer> inOrder = new LinkedHashMap<>();
        positions.forEach(at -> inOrder.put(Arrays.toString(at),
                Rng.paletteSlotAt(1337L, '#', at[0], at[1], at[2], 128)));

        List<int[]> shuffled = new ArrayList<>(positions);
        Collections.shuffle(shuffled, new Random(7L));
        // A prefix of the shuffled order, so that "how many other positions the chunk resolved first"
        // differs as well as which ones.
        for (int[] at : shuffled.subList(0, 30)) {
            assertEquals(inOrder.get(Arrays.toString(at)),
                    Rng.paletteSlotAt(1337L, '#', at[0], at[1], at[2], 128));
        }
    }

    /**
     * {@code WEIGHT.043}: a {@code light_socket} placement list is selected by the same rules, addressed
     * by the same position.
     * <p>
     * "The same rules" is a claim about code as much as about behaviour, and it is asserted as one: the
     * socket's list goes through the same {@link Apportion#materialise} and the same
     * {@link Rng#paletteSlotAt}, so a socket candidate list and a weighted node stating the same sizes
     * compile to the same slots and resolve to the same index at the same block.
     */
    @Test
    @Rule("WEIGHT.043")
    void aSocketPlacementListIsSelectedByTheSameRulesAtTheSamePosition() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2, "palette": {
                    "T": { "kind": "light_socket", "floor": [
                        { "share": 0.25, "block": "minecraft:lantern" },
                        { "rest": true, "block": "minecraft:torch" } ] },
                    "#": { "kind": "weighted", "choices": [
                        { "share": 0.25, "block": "minecraft:lantern" },
                        { "rest": true, "block": "minecraft:torch" } ] } } }
                """);
        ResolvedNode.Source.Socket socket = assertInstanceOf(ResolvedNode.Source.Socket.class,
                node(resolved, 'T').source());
        Diagnostics diagnostics = new Diagnostics();
        ResolvedNode[] floor = Apportion.materialise(
                socket.placements().get(Kind.Placement.FLOOR), siteOf(new Marker('T')),
                diagnostics).orElseThrow();
        ResolvedNode[] weighted = Apportion.materialise(
                weighted(resolved, '#').choices(), siteOf(new Marker('#')), diagnostics)
                .orElseThrow();
        assertArrayEquals(weighted, floor, "one set of size rules, whatever list they are written in");

        assertEquals(Rng.paletteSlotAt(1337L, 'T', 3, 64, 5, 128),
                Rng.paletteSlotAt(1337L, 'T', 3, 64, 5, 128));
        assertNotEquals(Rng.paletteSlotAt(1337L, 'T', 3, 64, 5, 128),
                Rng.paletteSlotAt(1337L, 'T', 3, 64, 6, 128),
                "addressed by the position, so a neighbouring block is a different draw");
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /**
     * A diagnostic location for a marker, without a {@link ResolutionScope} to build one from.
     * <p>
     * {@code PointerResolver.Site.marker} needs a scope, which stage 4 no longer holds - by then the
     * document has become a resolved palette. The components are the same three
     * {@code 08-errors.md} §2 names.
     */
    static PointerResolver.Site siteOf(Marker marker) {
        return new PointerResolver.Site(Diagnostics.DECODING_LOCATION, "marker " + marker,
                List.of());
    }

    /** A refusal, as the row it cites and the message it produced. */
    private record Refusal(Diag diag, String message) {
    }

    private static NodeResolver.ResolvedPalette resolve(String json) {
        Diagnostics diagnostics = new Diagnostics();
        PaletteV2Definition file = PaletteV2Definition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        return NodeResolver.resolve(file, diagnostics).orElseThrow(() -> new AssertionError(
                "expected the palette to link: " + diagnostics.asError().orElse("?")));
    }

    private static ResolvedNode node(NodeResolver.ResolvedPalette resolved, char marker) {
        return resolved.palette().get(new Marker(marker));
    }

    private static ResolvedNode.Source.Weighted weighted(NodeResolver.ResolvedPalette resolved,
                                                         char marker) {
        return assertInstanceOf(ResolvedNode.Source.Weighted.class, node(resolved, marker).source());
    }

    private static List<Fraction> shares(NodeResolver.ResolvedPalette resolved, char marker) {
        return Apportion.flatten(weighted(resolved, marker)).stream()
                .map(Apportion.Leaf::share).toList();
    }

    /** The size diagnostic {@code json} is refused with, from stage 4 rather than from decode. */
    private static Refusal refusalOf(String json) {
        Diagnostics diagnostics = new Diagnostics();
        NodeResolver.ResolvedPalette resolved = resolve(json);
        Marker marker = resolved.palette().keySet().iterator().next();
        assertFalse(Apportion.checkSizes(resolved.palette().get(marker), siteOf(marker),
                diagnostics), "expected a refusal, but the sizes were accepted");
        return new Refusal(diagnostics.all().get(0).diag(), diagnostics.asError().orElseThrow());
    }

    private static ResolvedNode[] materialise(String json) {
        NodeResolver.ResolvedPalette resolved = resolve(json);
        Marker marker = resolved.palette().keySet().iterator().next();
        Diagnostics diagnostics = new Diagnostics();
        return Apportion.materialise(weighted(resolved, marker.asString().charAt(0)).choices(),
                siteOf(marker), diagnostics).orElseThrow(() -> new AssertionError(
                        "expected 128 slots: " + diagnostics.asError().orElse("?")));
    }

    private static Refusal materialisationRefusalOf(String json) {
        NodeResolver.ResolvedPalette resolved = resolve(json);
        Marker marker = resolved.palette().keySet().iterator().next();
        Diagnostics diagnostics = new Diagnostics();
        Optional<ResolvedNode[]> slots = Apportion.materialise(
                weighted(resolved, marker.asString().charAt(0)).choices(), siteOf(marker),
                diagnostics);
        assertTrue(slots.isEmpty(), "expected a refusal, but it materialised");
        return new Refusal(diagnostics.all().get(0).diag(), diagnostics.asError().orElseThrow());
    }

    /** A weighted node of {@code count} distinct choices, which no fixture can carry by hand. */
    private static String generatedList(int count) {
        StringBuilder json = new StringBuilder(
                "{ \"version\": 2, \"palette\": { \"#\": { \"kind\": \"weighted\", \"choices\": [");
        for (int index = 0; index < count; index++) {
            json.append(index == 0 ? "" : ",")
                    .append("{ \"weight\": 1, \"block\": \"minecraft:")
                    .append(BLOCKS.get(index % BLOCKS.size()))
                    .append("[level=").append(index / BLOCKS.size()).append("]\" }");
        }
        return json.append("] } } }").toString();
    }

    /** {@code lists} nested lists of {@code each} choices: no list over 128, and the tree over it. */
    private static String generatedTree(int lists, int each) {
        StringBuilder json = new StringBuilder(
                "{ \"version\": 2, \"palette\": { \"#\": { \"kind\": \"weighted\", \"choices\": [");
        for (int list = 0; list < lists; list++) {
            json.append(list == 0 ? "" : ",")
                    .append("{ \"weight\": 1, \"kind\": \"weighted\", \"choices\": [");
            for (int index = 0; index < each; index++) {
                json.append(index == 0 ? "" : ",")
                        .append("{ \"weight\": 1, \"block\": \"minecraft:")
                        .append(BLOCKS.get(index % BLOCKS.size()))
                        .append("[level=").append(list * each + index).append("]\" }");
            }
            json.append("] }");
        }
        return json.append("] } } }").toString();
    }

    /**
     * Blocks with a wide integer property, so a generated list can hold 150 distinct alternatives.
     * <p>
     * The property expression is never resolved - stage 5 is Task 6's - so what matters is only that the
     * strings differ, which is what makes {@code distinct()} a meaningful count of slots.
     */
    private static final List<String> BLOCKS = List.of("light", "cauldron", "composter");

    private static Map<Fraction, Integer> slotsByShare(List<Fraction> shares) {
        int[] slots = Apportion.slots(shares, Apportion.SLOTS);
        Map<Fraction, Integer> byShare = new LinkedHashMap<>();
        for (int index = 0; index < shares.size(); index++) {
            byShare.put(shares.get(index), slots[index]);
        }
        return byShare;
    }
}
