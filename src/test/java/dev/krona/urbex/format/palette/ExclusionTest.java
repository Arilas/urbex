package dev.krona.urbex.format.palette;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which alternatives survive {@code when} and an absent block, and what is refused when none do.
 * <p>
 * Every test here drives a real document through decode and link first, so what is excluded is what a
 * file could actually write. The presence oracle is a stub in most of them, because
 * {@code WEIGHT.022} makes this a load-time decision and a test that depended on which mods happen to be
 * installed would assert one thing on a developer's machine and another in CI - the exception is
 * {@link #theGamesOwnPresenceAnswersTheTwoQuestionsWeight023Defines}, which is the one place the real
 * implementations are exercised.
 */
class ExclusionTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Nothing is installed and no block exists: the harshest environment a pack can meet. */
    private static final Exclusion.Presence NOTHING = presence(Set.of(), Set.of());

    /**
     * {@code WEIGHT.020} and {@code WEIGHT.021}: a choice whose condition does not hold leaves the list,
     * and what it was taking goes to the survivors.
     */
    @Test
    @Rule("WEIGHT.020")
    @Rule("WEIGHT.021")
    void aChoiceWhoseConditionDoesNotHoldLeavesTheListAndTheSurvivorsDivideItsSize() {
        List<ResolvedNode.Choice> kept = prune("""
                { "version": 2, "palette": { "c": { "kind": "weighted", "choices": [
                    { "weight": 6, "block": "minecraft:andesite", "when": { "mod": "create" } },
                    { "weight": 4, "block": "minecraft:stone_bricks" } ] } } }
                """, presence(Set.of(), Set.of("minecraft")));

        assertEquals(List.of("minecraft:stone_bricks"), blocks(kept));
        // WEIGHT.021: the survivor divides the same fraction, so it is now the whole node - which is
        // the apportionment of the shorter list and not a redistribution step of its own.
        assertEquals(List.of(Fraction.ONE),
                Apportion.flatten(kept).stream().map(Apportion.Leaf::share).toList());
    }

    /**
     * {@code WEIGHT.021}, the other half: a removed {@code share} is redistributed to the {@code weight}
     * choices, and to the remaining shares in proportion when there are none.
     */
    @Test
    @Rule("WEIGHT.021")
    void aRemovedShareGoesToTheWeightChoicesOrProportionallyToTheSharesThatAreLeft() {
        List<ResolvedNode.Choice> toWeights = prune("""
                { "version": 2, "palette": { "c": { "kind": "weighted", "choices": [
                    { "share": 0.5, "block": "minecraft:andesite", "when": { "mod": "create" } },
                    { "weight": 1, "block": "minecraft:stone_bricks" },
                    { "weight": 1, "block": "minecraft:cobweb" } ] } } }
                """, presence(Set.of(), Set.of("minecraft")));
        assertEquals(List.of(Fraction.of(1, 2), Fraction.of(1, 2)),
                Apportion.flatten(toWeights).stream().map(Apportion.Leaf::share).toList(),
                "the half the removed share held went to the weights, which still divide it 1:1");

        List<ResolvedNode.Choice> toShares = prune("""
                { "version": 2, "palette": { "c": { "kind": "weighted", "choices": [
                    { "share": 0.5, "block": "minecraft:andesite", "when": { "mod": "create" } },
                    { "share": 0.4, "block": "minecraft:stone_bricks" },
                    { "share": 0.1, "block": "minecraft:cobweb" } ] } } }
                """, presence(Set.of(), Set.of("minecraft")));
        assertEquals(List.of(Fraction.of(4, 5), Fraction.of(1, 5)),
                Apportion.flatten(toShares).stream().map(Apportion.Leaf::share).toList(),
                "0.4 and 0.1 keep their 4:1 ratio across the whole node");
    }

    /**
     * {@code WEIGHT.030} and {@code WEIGHT.031}: a choice naming a block no installed mod provides is
     * dropped, after {@code when} and before any share is computed.
     * <p>
     * The ordering is asserted rather than assumed: the same choice carries both an unsatisfiable
     * {@code when} and an absent block, and {@code DIAG.043}'s two counts say which rule took it. If the
     * order were the other way the counts would read {@code 0 by 'when', 1 by absent blocks}, which is a
     * different sentence about the same file.
     */
    @Test
    @Rule("WEIGHT.030")
    @Rule("WEIGHT.031")
    void anAbsentBlockIsDroppedAfterWhenAndBeforeAnyShareIsComputed() {
        List<ResolvedNode.Choice> kept = prune("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "weight": 6, "block": "nosuchmod:nosuchblock" },
                    { "weight": 4, "block": "minecraft:stone_bricks" } ] } } }
                """, presence(Set.of(), Set.of("minecraft")));
        assertEquals(List.of("minecraft:stone_bricks"), blocks(kept));

        Diagnostics diagnostics = new Diagnostics();
        assertTrue(pruneNode("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "weight": 1, "block": "nosuchmod:a", "when": { "mod": "nosuchmod" } } ] } } }
                """, NOTHING, diagnostics).isEmpty());
        String message = diagnostics.asError().orElseThrow();
        assertTrue(message.contains("1 by 'when', 0 by absent blocks"),
                () -> "'when' is evaluated first, so the absent block never gets to count it: "
                        + message);
    }

    /**
     * {@code WEIGHT.024} and {@code WEIGHT.032}: a weighted node with nothing left is refused, by one
     * diagnostic that says how many went each way.
     */
    @Test
    @Rule("WEIGHT.024")
    @Rule("WEIGHT.032")
    void aWeightedNodeWithNoChoiceLeftIsRefusedNamingHowManyWentEachWay() {
        Diagnostics diagnostics = new Diagnostics();
        assertTrue(pruneNode("""
                { "version": 2, "palette": { "c": { "kind": "weighted", "choices": [
                    { "weight": 1, "block": "minecraft:stone", "when": { "mod": "create" } },
                    { "weight": 1, "block": "minecraft:stone", "when": { "mod": "ae2" } },
                    { "weight": 1, "block": "nosuchmod:sky_stone_block" } ] } } }
                """, NOTHING, diagnostics).isEmpty());
        String message = diagnostics.asError().orElseThrow();
        assertTrue(Diag.DIAG_043.matches(message), message);
        assertTrue(message.contains("2 by 'when', 1 by absent blocks"), message);
    }

    /**
     * {@code WEIGHT.022}: {@code when} is evaluated once, so every position sees the same reduced list.
     * <p>
     * Proved as an identity rather than by generating positions: the reduced list is a value produced at
     * load and held, and nothing that resolves a position afterwards is handed the condition at all -
     * {@code Exclusion.Presence} appears in no signature past this stage. Two prunings of one resolved
     * node against one presence therefore produce equal lists, and a position cannot ask again because
     * there is nothing left to ask.
     */
    @Test
    @Rule("WEIGHT.022")
    void whenIsEvaluatedOnceSoEveryPositionSeesTheSameReducedList() {
        String json = """
                { "version": 2, "palette": { "c": { "kind": "weighted", "choices": [
                    { "share": 0.1, "block": "minecraft:andesite", "when": { "mod": "create" } },
                    { "rest": true, "block": "minecraft:stone_bricks" } ] } } }
                """;
        Exclusion.Presence presence = presence(Set.of(), Set.of("minecraft"));
        assertEquals(prune(json, presence), prune(json, presence));
        assertEquals(1, prune(json, presence).size());
    }

    /**
     * {@code WEIGHT.023}: {@code when} accepts {@code mod} and {@code pack}, and nothing else - the two
     * questions that already have implementations.
     */
    @Test
    @Rule("WEIGHT.023")
    void whenAcceptsAModIdAndAPackNamespaceAndNoOtherCondition() {
        assertEquals(Set.of("mod", "pack"), When.KEYS);

        Exclusion.Presence hasPackNotMod = presence(Set.of(), Set.of("someotherpack", "minecraft"));
        assertEquals(List.of("minecraft:stone_bricks", "minecraft:cobweb"), blocks(prune("""
                { "version": 2, "palette": { "c": { "kind": "weighted", "choices": [
                    { "weight": 1, "block": "minecraft:stone", "when": { "mod": "somemod" } },
                    { "weight": 1, "block": "minecraft:stone_bricks",
                      "when": { "pack": "someotherpack" } },
                    { "weight": 1, "block": "minecraft:cobweb" } ] } } }
                """, hasPackNotMod)));
    }

    /**
     * The one test that uses the game's own answers, so that {@code WEIGHT.023}'s "both already have
     * implementations" is a checked claim rather than a quoted one.
     * <p>
     * {@code minecraft} is loaded whatever the environment - {@code ReferenceProvider.modIsInstalled}
     * makes that explicit, and outside a game the loader alone would answer false - and an id no
     * registry holds is absent. Neither assertion depends on which mods are installed beside them.
     */
    @Test
    @Rule("WEIGHT.023")
    @Rule("WEIGHT.030")
    void theGamesOwnPresenceAnswersTheTwoQuestionsWeight023Defines() {
        Exclusion.Presence installed =
                Exclusion.installed(BuiltInRegistries.BLOCK, Set.of("urbex"));
        assertTrue(installed.modIsLoaded("minecraft"));
        assertFalse(installed.modIsLoaded("a_mod_nobody_has"));
        assertTrue(installed.packRegistersAssets("urbex"));
        assertFalse(installed.packRegistersAssets("a_pack_nobody_has"));
        assertTrue(installed.blockExists("minecraft:stone_bricks"));
        assertTrue(installed.blockExists("minecraft:oak_stairs[facing=north]"),
                "a property expression is MODEL.043's business, not this rule's");
        assertFalse(installed.blockExists("nosuchmod:nosuchblock"));
        assertFalse(installed.blockExists("minecraft:no_such_block"),
                "an installed mod that does not provide the id is still an id nobody provides");
    }

    /**
     * {@code WEIGHT.025}: {@code when} and {@code urbex:optional} are not interchangeable, and the table
     * in {@code 05-weights.md} §3.1 is the difference made structural.
     * <p>
     * Two of the table's five rows are checkable here and both are: a {@code when} choice leaves the
     * list, where a node carrying a trait keeps its size whatever the trait says, because nothing in
     * this pass reads a trait at all. The other three rows are about generation.
     */
    @Test
    @Rule("WEIGHT.025")
    void aChoiceCarryingATraitKeepsItsSizeWhereAChoiceCarryingAWhenLeavesTheList() {
        List<ResolvedNode.Choice> kept = prune("""
                { "version": 2, "palette": { "c": { "kind": "weighted", "choices": [
                    { "weight": 1, "block": "minecraft:stone", "when": { "mod": "create" } },
                    { "weight": 1, "block": "minecraft:cobweb", "traits": {
                        "urbex:optional": { "replacement": "minecraft:air" } } } ] } } }
                """, presence(Set.of(), Set.of("minecraft")));
        assertEquals(List.of("minecraft:cobweb"), blocks(kept));
        assertEquals(List.of(Fraction.ONE),
                Apportion.flatten(kept).stream().map(Apportion.Leaf::share).toList(),
                "the optional choice is at full weight; the trait decides per position, not here");
    }

    /**
     * A socket's candidates are excluded by the same rules ({@code MODEL.076}), and a list emptied
     * beside a list that survives is not a refusal ({@code MODEL.072}).
     */
    @Test
    @Rule("MODEL.076")
    @Rule("MODEL.072")
    void aSocketsCandidatesAreExcludedTooAndOneEmptiedListIsNotARefusal() {
        ResolvedNode pruned = pruneNode("""
                { "version": 2, "palette": { "T": { "kind": "light_socket",
                    "floor": [ { "weight": 1, "block": "nosuchmod:lamp" } ],
                    "ceiling": [ { "weight": 1, "block": "minecraft:lantern" } ] } } }
                """, presence(Set.of(), Set.of("minecraft")), new Diagnostics()).orElseThrow();
        ResolvedNode.Source.Socket socket =
                assertInstanceOf(ResolvedNode.Source.Socket.class, pruned.source());
        assertTrue(socket.placements().get(Kind.Placement.FLOOR).isEmpty());
        assertEquals(1, socket.placements().get(Kind.Placement.CEILING).size());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /**
     * A presence that answers from two fixed sets, and calls a block present when it is a
     * {@code minecraft:} id the registry actually holds.
     */
    private static Exclusion.Presence presence(Set<String> mods, Set<String> packs) {
        Exclusion.Presence real = Exclusion.installed(BuiltInRegistries.BLOCK, Set.of());
        return new Exclusion.Presence() {
            @Override
            public boolean modIsLoaded(String modId) {
                return mods.contains(modId);
            }

            @Override
            public boolean packRegistersAssets(String namespace) {
                return packs.contains(namespace);
            }

            @Override
            public boolean blockExists(String block) {
                return packs.contains("minecraft") && real.blockExists(block);
            }
        };
    }

    private static List<ResolvedNode.Choice> prune(String json, Exclusion.Presence presence) {
        ResolvedNode.Source.Weighted weighted = assertInstanceOf(ResolvedNode.Source.Weighted.class,
                pruneNode(json, presence, new Diagnostics()).orElseThrow().source());
        return weighted.choices();
    }

    private static Optional<ResolvedNode> pruneNode(String json, Exclusion.Presence presence,
                                                     Diagnostics diagnostics) {
        PaletteV2Definition file = PaletteV2Definition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        NodeResolver.ResolvedPalette resolved = NodeResolver.resolve(file, diagnostics)
                .orElseThrow(() -> new AssertionError(
                        "expected the palette to link: " + diagnostics.asError().orElse("?")));
        Marker marker = resolved.palette().keySet().iterator().next();
        return Exclusion.prune(resolved.palette().get(marker), presence,
                ApportionTest.siteOf(marker), diagnostics);
    }

    private static List<String> blocks(List<ResolvedNode.Choice> choices) {
        return choices.stream()
                .map(choice -> assertInstanceOf(ResolvedNode.Source.Block.class,
                        choice.node().source()).block())
                .toList();
    }
}
