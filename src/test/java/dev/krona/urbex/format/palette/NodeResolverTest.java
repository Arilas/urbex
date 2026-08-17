package dev.krona.urbex.format.palette;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What resolving a version 2 palette produces, and what it refuses.
 * <p>
 * The specification's own fixtures are run by {@code FormatFixtureTest}, which now links every
 * self-contained one; this covers what a fixture cannot state. Three kinds live here. A {@code MUST}
 * about the <em>shape</em> of the resolved value - that {@code $only} contributed exactly the named
 * keys, that a spread kept the positions around it - is a claim about what came out, and a fixture only
 * says whether the document was accepted. A {@code MUST NOT} is the negative of a {@code MUST}, proved
 * by exercising the situation and asserting the behaviour does not occur. And an {@code INVARIANT} is a
 * property of every resolved palette rather than of one document.
 */
class NodeResolverTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- References ----------------------------------------------------------------------------

    /**
     * {@code REF.002} and {@code REF.003}: the referenced node is the base, and a key beside the
     * {@code $ref} replaces its value for that key.
     */
    @Test
    @Rule("REF.002")
    @Rule("REF.003")
    void aKeyBesideARefReplacesTheReferencedNodesValueForThatKey() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "sconce": { "block": "minecraft:wall_torch[facing=north]",
                                         "traits": { "urbex:light": { "unlit": "minecraft:air" } } } },
                  "palette": { "t": { "$ref": "sconce" },
                               "u": { "$ref": "sconce",
                                      "block": "minecraft:wall_torch[facing=south]" } } }
                """);
        assertEquals("minecraft:wall_torch[facing=north]", blockOf(resolved, 't'));
        assertEquals("minecraft:wall_torch[facing=south]", blockOf(resolved, 'u'));
        // REF.004: the trait it did not restate is still there.
        assertEquals(Set.of(Identifier.parse("urbex:light")),
                resolved.palette().get(new Marker('u')).traits().keySet());
    }

    /** {@code REF.004}: traits beside a {@code $ref} merge into the target's by id, replacing whole. */
    @Test
    @Rule("REF.004")
    @Rule("TRAIT.006")
    void traitsBesideARefMergeByIdAndReplaceWhole() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "base": { "block": "minecraft:stone_bricks", "traits": {
                      "urbex:damaged": { "into": "minecraft:iron_bars" },
                      "urbex:rotatable": false } } },
                  "palette": { "X": { "$ref": "base", "traits": {
                      "urbex:damaged": { "into": "minecraft:cobweb" } } } } }
                """);
        Map<Identifier, Trait> traits = resolved.palette().get(new Marker('X')).traits();
        assertEquals(Set.of(Identifier.parse("urbex:damaged"), Identifier.parse("urbex:rotatable")),
                traits.keySet());
        assertTrue(traits.get(Identifier.parse("urbex:damaged")).data().toString()
                .contains("cobweb"), "the child's whole trait object replaces the parent's");
    }

    /**
     * {@code REF.030} and {@code REF.034}: nothing survives resolution.
     * <p>
     * Asserted over the resolved tree rather than at one node, because {@code REF.034} is an invariant
     * about the whole palette: "no compiled palette holds a reference, a definition name, or an
     * unresolved marker alias". {@link ResolvedNode} has nowhere to put one, so the half worth checking
     * is the {@link RawNode}s that remain - the {@code $defs} entries {@code MODEL.082} keeps raw.
     */
    @Test
    @Rule("REF.030")
    @Rule("REF.034")
    void noResolvedNodeHoldsAPointerOrADefinitionName() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "inner": { "block": "minecraft:stone" },
                             "outer": { "$ref": "inner", "traits": { "urbex:rotatable": false } },
                             "list":  { "kind": "weighted", "choices": [
                                 { "weight": 1, "$ref": "inner" },
                                 { "weight": 1, "block": "minecraft:cobweb" } ] } },
                  "palette": { "X": { "$ref": "outer" }, "#": { "$ref": "list" } } }
                """);
        for (RawNode def : resolved.defs().values()) {
            for (RawNode node : def.selfAndDescendants()) {
                assertEquals(List.of(), node.pointersWritten(),
                        () -> "a resolved node still holds a pointer: " + node);
                assertTrue(node.only().isEmpty(),
                        () -> "a resolved node still holds $only " + node.only());
                assertTrue(node.without().isEmpty(),
                        () -> "a resolved node still holds $without " + node.without());
            }
        }
        assertEquals(Set.of(new Marker('X'), new Marker('#')), resolved.palette().keySet());
    }

    /**
     * {@code REF.012}: a name resolves in exactly one tier, and a failure in it is not retried in the
     * other.
     * <p>
     * Proved from both sides, because a search order is only visible when one tier would have answered:
     * a bare name is not looked for in the registry even when the registry holds that name, and a
     * qualified one is not looked for in {@code $defs} even when {@code $defs} holds it.
     */
    @Test
    @Rule("REF.012")
    void aNameResolvesInOneTierAndTheOtherIsNeverTried() {
        DefinitionIndex registry = index("urbex:rubble", """
                { "version": 2, "block": "minecraft:cobblestone" }
                """);

        Diagnostics bare = new Diagnostics();
        NodeResolver.resolve(decode("""
                { "version": 2, "palette": { "X": { "$ref": "rubble" } } }
                """), registry, Map.of(), bare);
        assertTrue(Diag.DIAG_030.matches(bare.asError().orElseThrow()), bare.asError().orElseThrow());

        Diagnostics qualified = new Diagnostics();
        NodeResolver.resolve(decode("""
                { "version": 2, "$defs": { "rubble": "minecraft:stone" },
                  "palette": { "X": { "$ref": "urbex:nosuch" } } }
                """), DefinitionIndex.empty(), Map.of(), qualified);
        assertTrue(Diag.DIAG_030.matches(qualified.asError().orElseThrow()),
                qualified.asError().orElseThrow());
    }

    /**
     * {@code REF.032}: a cycle is refused, naming every node in it, in declaration order from the one
     * the loader reached first.
     * <p>
     * Three assertions, and the third is the one that keeps this honest. The cycle is named; it is named
     * <em>once</em>, not once per participant and not once per marker that referenced it; and a chain
     * longer than two links prints every link, which is what tells an author which edge to cut.
     */
    @Test
    @Rule("REF.032")
    void aReferenceCycleIsRefusedNamingEveryNodeInIt() {
        Diagnostics diagnostics = new Diagnostics();
        NodeResolver.resolve(decode("""
                { "version": 2,
                  "$defs": { "a": { "$ref": "b" }, "b": { "$ref": "c" }, "c": { "$ref": "a" } },
                  "palette": { "X": { "$ref": "a" }, "Y": { "$ref": "b" } } }
                """), diagnostics);

        List<Diagnostics.Entry> cycles = diagnostics.all().stream()
                .filter(entry -> entry.diag() == Diag.DIAG_032).toList();
        assertEquals(1, cycles.size(), () -> "one cycle, reported once: " + diagnostics.all());
        assertTrue(cycles.get(0).message().contains("a → b → c → a"), cycles.get(0).message());
        assertEquals(1, diagnostics.all().size() + diagnostics.nestedMessages().size(),
                () -> "a node whose dependency is in a cycle says nothing further: "
                        + diagnostics.all() + diagnostics.nestedMessages());
    }

    /** {@code REF.032}: a node that references itself is a cycle of one, not a stack overflow. */
    @Test
    @Rule("REF.032")
    void aSelfReferenceIsACycleOfOne() {
        Diagnostics diagnostics = new Diagnostics();
        NodeResolver.resolve(decode("""
                { "version": 2, "$defs": { "a": { "$ref": "a" } },
                  "palette": { "X": { "$ref": "a" } } }
                """), diagnostics);
        String message = diagnostics.asError().orElseThrow();
        assertTrue(Diag.DIAG_032.matches(message), message);
        assertTrue(message.contains("a → a"), message);
    }

    /**
     * {@code REF.031}: one topological pass, so a chain resolves whatever order it is declared in.
     * <p>
     * The definitions below are declared leaf-last, which is the order a fixpoint loop needed a second
     * iteration for. One pass in dependency order needs none, and the assertion is that the deepest
     * value reached the top.
     */
    @Test
    @Rule("REF.031")
    void aChainResolvesWhateverOrderItsLinksAreDeclaredIn() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "outer": { "$ref": "middle" },
                             "middle": { "$ref": "inner" },
                             "inner": { "block": "minecraft:deepslate_bricks" } },
                  "palette": { "X": { "$ref": "outer" } } }
                """);
        assertEquals("minecraft:deepslate_bricks", blockOf(resolved, 'X'));
    }

    // ---- Filters -------------------------------------------------------------------------------

    /**
     * {@code REF.051}: a {@code $ref} carrying {@code $only} contributes only those keys of its target.
     * <p>
     * This is the intent {@code REF.054}'s {@code > Why} says is otherwise inexpressible - "taking a
     * node's traits while supplying a different block" - so the assertion is both halves: the traits
     * arrived, and the target's {@code kind: weighted} and its {@code choices} did not.
     */
    @Test
    @Rule("REF.051")
    void onlyContributesJustTheNamedKeysOfTheTarget() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "rubble": { "kind": "weighted", "traits": {
                      "urbex:damaged": { "into": "minecraft:iron_bars" } },
                      "choices": [ { "rest": true, "block": "minecraft:stone_bricks" } ] } },
                  "palette": { "X": { "$ref": "rubble", "$only": ["traits"],
                                      "block": "minecraft:deepslate_bricks" } } }
                """);
        ResolvedNode node = resolved.palette().get(new Marker('X'));
        assertEquals(Kind.BLOCK, node.kind());
        assertEquals("minecraft:deepslate_bricks", blockOf(resolved, 'X'));
        assertEquals(Set.of(Identifier.parse("urbex:damaged")), node.traits().keySet());
    }

    /** {@code REF.052}: {@code $without} contributes every key of the target except those named. */
    @Test
    @Rule("REF.052")
    void withoutContributesEveryKeyOfTheTargetExceptThoseNamed() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "lit": { "block": "minecraft:lantern",
                                      "traits": { "urbex:rotatable": false } } },
                  "palette": { "X": { "$ref": "lit", "$without": ["traits"] } } }
                """);
        ResolvedNode node = resolved.palette().get(new Marker('X'));
        assertEquals("minecraft:lantern", blockOf(resolved, 'X'));
        assertTrue(node.traits().isEmpty(), () -> "traits were dropped, got " + node.traits());
    }

    /**
     * {@code REF.053}: a node carrying both filters is refused.
     * <p>
     * Refused at decode, by {@code RawNode.validate}, which is why the assertion is on the decode and
     * not on the resolution: a node that has not said which keys it wants is malformed before anything
     * is looked up.
     */
    @Test
    @Rule("REF.053")
    void onlyAndWithoutTogetherAreRefused() {
        DataResult<PaletteV2Definition> decoded = PaletteV2Definition.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("""
                        { "version": 2, "$defs": { "d": { "traits": {} } },
                          "palette": { "X": { "$ref": "d", "$only": ["traits"],
                                              "$without": ["kind"], "block": "minecraft:stone" } } }
                        """));
        String message = decoded.error().orElseThrow().message();
        assertTrue(Diag.DIAG_035.matches(message), message);
    }

    /**
     * {@code REF.054}: the filters name top-level keys only, so {@code traits} keeps or drops the whole
     * map.
     * <p>
     * A trait id is not addressable in a filter, and this is the assertion that says so: {@code $only}
     * naming the trait id contributes nothing at all, rather than that one trait.
     */
    @Test
    @Rule("REF.054")
    void aFilterNamesTopLevelKeysAndNotPathsIntoThem() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "both": { "block": "minecraft:stone", "traits": {
                      "urbex:damaged": { "into": "minecraft:iron_bars" },
                      "urbex:rotatable": false } } },
                  "palette": { "X": { "$ref": "both", "$only": ["urbex:damaged"],
                                      "block": "minecraft:cobblestone" } } }
                """);
        assertTrue(resolved.palette().get(new Marker('X')).traits().isEmpty(),
                "a trait id is not a top-level key, so $only naming one contributes nothing");
    }

    // ---- Spread --------------------------------------------------------------------------------

    /** {@code REF.070}: a spread element is replaced by the elements of the list it names. */
    @Test
    @Rule("REF.070")
    void aSpreadIsReplacedByTheElementsOfTheListItNames() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "base": { "kind": "weighted", "choices": [
                      { "weight": 1, "block": "minecraft:stone_bricks" },
                      { "weight": 2, "block": "minecraft:mossy_stone_bricks" } ] } },
                  "palette": { "#": { "kind": "weighted", "choices": [
                      { "$spread": "base#/choices" },
                      { "weight": 4, "block": "minecraft:cracked_stone_bricks" } ] } } }
                """);
        assertEquals(List.of("minecraft:stone_bricks", "minecraft:mossy_stone_bricks",
                        "minecraft:cracked_stone_bricks"),
                blocksOf(resolved, '#'));
    }

    /** {@code REF.071}: a spread whose pointer does not name a list is refused. */
    @Test
    @Rule("REF.071")
    void aSpreadNamingSomethingOtherThanAListIsRefused() {
        Diagnostics diagnostics = new Diagnostics();
        NodeResolver.resolve(decode("""
                { "version": 2, "$defs": { "d": { "block": "minecraft:stone" } },
                  "palette": { "#": { "kind": "weighted", "choices": [
                      { "$spread": "d#/block" } ] } } }
                """), diagnostics);
        String message = diagnostics.asError().orElseThrow();
        assertTrue(Diag.DIAG_037.matches(message), message);

        // A whole node is not a list either, and says so with the same diagnostic.
        Diagnostics wholeNode = new Diagnostics();
        NodeResolver.resolve(decode("""
                { "version": 2, "$defs": { "d": { "block": "minecraft:stone" } },
                  "palette": { "#": { "kind": "weighted", "choices": [
                      { "$spread": "d" } ] } } }
                """), wholeNode);
        assertTrue(Diag.DIAG_037.matches(wholeNode.asError().orElseThrow()),
                wholeNode.asError().orElseThrow());
    }

    /**
     * {@code REF.072}: a spread element carries no other key.
     * <p>
     * Both halves, because the two live in different places. A node key beside {@code $spread} is
     * refused by {@code RawNode.validate}; a <em>size</em> beside it is a key of the choice rather than
     * of the node, and was accepted and then dropped until this task - a spread is replaced by elements
     * that state their own sizes, so a size on the spread itself has nothing to apply to.
     */
    @Test
    @Rule("REF.072")
    void aSpreadElementCarriesNoOtherKey() {
        for (String sibling : List.of("\"block\": \"minecraft:stone\"", "\"weight\": 3",
                "\"rest\": true", "\"share\": 0.5", "\"when\": { \"mod\": \"create\" }")) {
            DataResult<PaletteV2Definition> decoded = PaletteV2Definition.CODEC.parse(
                    JsonOps.INSTANCE, JsonParser.parseString("""
                            { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                                { "$spread": "d#/choices", %s } ] } } }
                            """.formatted(sibling)));
            String message = decoded.error()
                    .orElseThrow(() -> new AssertionError(sibling + " beside a $spread was accepted"))
                    .message();
            assertTrue(Diag.DIAG_003.matches(message), sibling + ": " + message);
        }
    }

    /** {@code REF.073}: spreading is positional, and several spreads compose. */
    @Test
    @Rule("REF.073")
    void elementsBeforeAndAfterASpreadKeepTheirPlaces() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "one": { "kind": "weighted", "choices": [
                                 { "weight": 1, "block": "minecraft:b" } ] },
                             "two": { "kind": "weighted", "choices": [
                                 { "weight": 1, "block": "minecraft:d" },
                                 { "weight": 1, "block": "minecraft:e" } ] } },
                  "palette": { "#": { "kind": "weighted", "choices": [
                      { "weight": 1, "block": "minecraft:a" },
                      { "$spread": "one#/choices" },
                      { "weight": 1, "block": "minecraft:c" },
                      { "$spread": "two#/choices" },
                      { "weight": 1, "block": "minecraft:f" } ] } } }
                """);
        assertEquals(List.of("minecraft:a", "minecraft:b", "minecraft:c", "minecraft:d",
                "minecraft:e", "minecraft:f"), blocksOf(resolved, '#'));
    }

    /** {@code MODEL.076}: a placement list is a list like any other, and accepts a spread. */
    @Test
    @Rule("MODEL.076")
    void aSocketPlacementListAcceptsASpread() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "torches": { "kind": "weighted", "choices": [
                      { "weight": 1, "block": "minecraft:torch" },
                      { "weight": 1, "block": "minecraft:soul_torch" } ] } },
                  "palette": { "T": { "kind": "light_socket",
                      "floor": [ { "$spread": "torches#/choices" } ] } } }
                """);
        ResolvedNode.Source.Socket socket = assertInstanceOf(ResolvedNode.Source.Socket.class,
                resolved.palette().get(new Marker('T')).source());
        assertEquals(2, socket.placements().get(Kind.Placement.FLOOR).size());
    }

    // ---- $super --------------------------------------------------------------------------------

    /**
     * {@code REF.062}: {@code $super} in an entry that inherits nothing is refused.
     * <p>
     * The branch this task can reach is the first of {@code DIAG.036}'s two: the file declares no
     * {@code extends}, so nothing anywhere could have stood at this marker. The second - a file that does
     * declare one, whose chain does not declare this entry - needs a parent palette, which is why
     * {@code REF.062} carries {@code [NO-FIXTURE: a parent palette]} and why Task 4 covers it.
     */
    @Test
    @Rule("REF.062")
    void superInAnEntryThatInheritsNothingIsRefused() {
        Diagnostics diagnostics = new Diagnostics();
        NodeResolver.resolve(decode("""
                { "version": 2, "palette": { "X": { "$ref": "$super" } } }
                """), diagnostics);
        String message = diagnostics.asError().orElseThrow();
        assertTrue(Diag.DIAG_036.matches(message), message);
        assertTrue(message.contains("declares no extends"), message);

        // And so is a fragment on it: $super#/choices names a path inside the same absent value.
        Diagnostics fragment = new Diagnostics();
        NodeResolver.resolve(decode("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "$spread": "$super#/choices" } ] } } }
                """), fragment);
        assertTrue(Diag.DIAG_036.matches(fragment.asError().orElseThrow()),
                fragment.asError().orElseThrow());
    }

    /**
     * {@code REF.060}: {@code $super} names the inherited value, once there is one.
     * <p>
     * Driven through {@link ResolutionScope#withInherited} rather than through an {@code extends} chain,
     * because the chain is Task 4's and this is the seam it fills. What is asserted is the half this task
     * owns: given an inherited node, {@code $super} resolves to it, and a key beside the {@code $ref}
     * still replaces its own key by {@code REF.003} - which is {@code MERGE.005}'s whole mechanism.
     */
    @Test
    @Rule("REF.060")
    @Rule("MERGE.005")
    void superResolvesToTheInheritedValue() {
        PaletteV2Definition file = decode("""
                { "version": 2, "palette": { "X": { "$ref": "$super",
                    "traits": { "urbex:rotatable": false } } } }
                """);
        RawNode inherited = RawNode.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("\"minecraft:oak_door[facing=north]\"")).getOrThrow();
        ResolutionScope scope = ResolutionScope.of(file).withInherited(Optional.of(inherited));

        Diagnostics diagnostics = new Diagnostics();
        ResolvedNode resolved = NodeResolver.resolve(
                file.palette().orElseThrow().get(new Marker('X')), scope, diagnostics)
                .orElseThrow(() -> new AssertionError(diagnostics.asError().orElse("?")));
        assertEquals(new ResolvedNode.Source.Block("minecraft:oak_door[facing=north]"),
                resolved.source());
        assertEquals(Set.of(Identifier.parse("urbex:rotatable")), resolved.traits().keySet());
    }

    // ---- Completeness --------------------------------------------------------------------------

    /**
     * {@code MODEL.081}: a node in a marker position that resolves to no block source is refused.
     * <p>
     * Three positions, because the rule names three: the marker itself, a {@code choices} entry, and a
     * socket candidate. The first is {@code MODEL.081}'s own fixture; the other two are only reachable
     * from a test, and are where a partial definition used in the wrong place actually lands.
     */
    @Test
    @Rule("MODEL.081")
    void aMarkerResolvingToNoBlockSourceIsRefused() {
        assertRefused(Diag.DIAG_011, """
                { "version": 2,
                  "$defs": { "rubble": { "traits": {
                      "urbex:damaged": { "into": "minecraft:iron_bars" } } } },
                  "palette": { "X": { "$ref": "rubble" } } }
                """);
        assertRefused(Diag.DIAG_011, """
                { "version": 2,
                  "$defs": { "rubble": { "traits": {
                      "urbex:damaged": { "into": "minecraft:iron_bars" } } } },
                  "palette": { "#": { "kind": "weighted", "choices": [
                      { "weight": 1, "$ref": "rubble" } ] } } }
                """);
        assertRefused(Diag.DIAG_011, """
                { "version": 2,
                  "$defs": { "rubble": { "traits": {
                      "urbex:damaged": { "into": "minecraft:iron_bars" } } } },
                  "palette": { "T": { "kind": "light_socket", "floor": [
                      { "weight": 1, "$ref": "rubble" } ] } } }
                """);
    }

    /**
     * {@code MODEL.081}'s diagnostic names the definition the marker got its emptiness from.
     * <p>
     * {@code DIAG.011}'s row is "{@code <def>} declares only traits", and the reason it names the
     * definition rather than only the marker is that the marker is where the author is looking and the
     * definition is where the fix is. {@code DIAG.901} is the same argument generalised.
     */
    @Test
    @Rule("MODEL.081")
    @Rule("DIAG.901")
    void aCompletenessDiagnosticNamesTheMarkerAndTheDefinitionItCameFrom() {
        Diagnostics diagnostics = new Diagnostics();
        NodeResolver.resolve(decode("""
                { "version": 2,
                  "$defs": { "rubble": { "traits": {
                      "urbex:damaged": { "into": "minecraft:iron_bars" } } } },
                  "palette": { "X": { "$ref": "rubble" } } }
                """), diagnostics);
        String message = diagnostics.asError().orElseThrow();
        assertTrue(message.contains("marker 'X'"), message);
        assertTrue(message.contains("'rubble'"), message);
        assertTrue(message.contains("via 'rubble'"), () -> "the reference chain: " + message);
    }

    /**
     * {@code MODEL.082} and {@code REF.020}: a definition need not resolve to a block source, and
     * {@code REF.021} is why - completeness is checked where a definition is used, not where it is
     * declared.
     */
    @Test
    @Rule("MODEL.082")
    @Rule("REF.020")
    @Rule("REF.021")
    void aDefinitionMayCarryOnlyTraits() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "rubble":   { "traits": {
                                 "urbex:damaged": { "into": "minecraft:iron_bars" } } },
                             "lootable": { "traits": {
                                 "urbex:loot": { "pool": "urbex:chestloot" } } } },
                  "palette": { "X": { "$ref": "rubble",   "block": "minecraft:stone_bricks" },
                               "C": { "$ref": "lootable", "block": "minecraft:chest[facing=north]" } } }
                """);
        assertEquals(Set.of("rubble", "lootable"), resolved.defs().keySet());
        assertEquals("minecraft:stone_bricks", blockOf(resolved, 'X'));
        assertEquals(Set.of(Identifier.parse("urbex:loot")),
                resolved.palette().get(new Marker('C')).traits().keySet());
    }

    /** {@code MODEL.011}: the kind default is applied after the reference is resolved, not before. */
    @Test
    @Rule("MODEL.011")
    void aNodeWithNoKindTakesItsKindFromItsReferenceAndOnlyThenTheDefault() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "wall": { "kind": "weighted", "choices": [
                      { "rest": true, "block": "minecraft:stone_bricks" } ] } },
                  "palette": { "#": { "$ref": "wall" }, "X": { "block": "minecraft:cobblestone" } } }
                """);
        assertEquals(Kind.WEIGHTED, resolved.palette().get(new Marker('#')).kind());
        assertEquals(Kind.BLOCK, resolved.palette().get(new Marker('X')).kind());
    }

    /**
     * {@code MODEL.013}: the kind-specific keys of one kind are not accepted on another - checked again
     * once the reference has decided the kind.
     * <p>
     * This is the case {@code REF.054}'s {@code > Why} describes: the target's {@code kind: weighted}
     * arrives, the sibling {@code block} is declared, and the result is incoherent. It decodes, because
     * at decode a node carrying {@code $ref} may write any kind's keys; it is refused here, which is
     * what makes {@code $only} the answer rather than a nicety.
     */
    @Test
    @Rule("MODEL.013")
    void aKeyOfAnotherKindIsRefusedOnceTheReferenceHasDecidedTheKind() {
        assertRefused(Diag.DIAG_003, """
                { "version": 2,
                  "$defs": { "wall": { "kind": "weighted", "choices": [
                      { "rest": true, "block": "minecraft:stone_bricks" } ] } },
                  "palette": { "X": { "$ref": "wall", "block": "minecraft:deepslate_bricks" } } }
                """);
    }

    /**
     * A kind that arrives through a {@code $ref} without its required list is refused by the rule about
     * that list, not by {@code MODEL.081}'s message about traits.
     * <p>
     * Both are {@code MODEL.081} failures - the node has a kind and not that kind's required keys - and
     * only one of the two available sentences is true of them. {@code DIAG.011} says
     * "{@code <def>} declares only traits", which is false of a node that declared {@code kind:
     * weighted}; {@code DIAG.007} and {@code DIAG.010} name the missing list and the remedy. Neither
     * document is reachable at decode: the kind is not knowable there, so the node's own key check let
     * it through.
     */
    @Test
    @Rule("MODEL.045")
    @Rule("MODEL.072")
    void aKindArrivingWithoutItsRequiredListIsRefusedByThatListsRule() {
        assertRefused(Diag.DIAG_007, """
                { "version": 2,
                  "$defs": { "weightedNoChoices": { "kind": "weighted" } },
                  "palette": { "#": { "$ref": "weightedNoChoices" } } }
                """);
        // A socket declaring no list at all is already refused at decode, so the only way to reach the
        // resolver with one is to take a real socket's kind and leave its candidates behind - which is
        // $only, used wrongly, and is exactly the incoherent node REF.054's > Why is about.
        assertRefused(Diag.DIAG_010, """
                { "version": 2,
                  "$defs": { "socket": { "kind": "light_socket", "floor": [
                      { "weight": 1, "block": "minecraft:torch" } ] } },
                  "palette": { "T": { "$ref": "socket", "$only": ["kind"] } } }
                """);
    }

    /** {@code MODEL.045}: a weighted node whose {@code choices} spread away to nothing is refused. */
    @Test
    @Rule("MODEL.045")
    void aWeightedNodeWhoseChoicesSpreadToNothingIsRefused() {
        // 'floor' is written empty and 'wall' is not, so the socket itself is not MODEL.072 at decode
        // and the empty list survives to be spread. A list that is empty as written is DIAG.007 there.
        assertRefused(Diag.DIAG_007, """
                { "version": 2,
                  "$defs": { "socket": { "kind": "light_socket", "floor": [],
                      "wall": [ { "weight": 1, "block": "minecraft:wall_torch[facing=north]" } ] } },
                  "palette": { "#": { "kind": "weighted", "choices": [
                      { "$spread": "socket#/floor" } ] } } }
                """);
    }

    /** {@code MODEL.031}: realising a node never realises its satellites, so traits stay unresolved. */
    @Test
    @Rule("MODEL.031")
    void aSatellitesNodeIsNotCompletedAsAnAlternative() {
        // A trait payload holding a node with no block would be MODEL.081 if it were an alternative.
        // It is a satellite, so it is neither completed nor refused here - it is opaque until Task 6.
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2, "palette": { "X": { "block": "minecraft:stone_bricks",
                    "traits": { "urbex:damaged": { "into": { "traits": {} } } } } } }
                """);
        assertEquals("minecraft:stone_bricks", blockOf(resolved, 'X'));
    }

    // ---- The definitions registry --------------------------------------------------------------

    /** {@code REF.010}: a {@code $ref} with a colon names an asset of the {@code definitions} registry. */
    @Test
    @Rule("REF.010")
    @Rule("REF.016")
    void aQualifiedRefNamesADefinitionsAsset() {
        DefinitionIndex registry = index("urbex:rubble", """
                { "version": 2, "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } } }
                """);
        Diagnostics diagnostics = new Diagnostics();
        NodeResolver.ResolvedPalette resolved = NodeResolver.resolve(decode("""
                { "version": 2, "palette": { "X": { "$ref": "urbex:rubble",
                    "block": "minecraft:stone_bricks" } } }
                """), registry, Map.of(), diagnostics)
                .orElseThrow(() -> new AssertionError(diagnostics.asError().orElse("?")));
        assertEquals("minecraft:stone_bricks", blockOf(resolved, 'X'));
        assertEquals(Set.of(Identifier.parse("urbex:damaged")),
                resolved.palette().get(new Marker('X')).traits().keySet());
    }

    /**
     * {@code REF.043}: an asset id in a fragment pointer names a {@code palettes} entry unless the
     * pointer prefixes the registry it means.
     * <p>
     * The rule carries {@code [NO-FIXTURE: a second asset]} because the fixture harness holds one
     * document; this is that second asset, supplied by hand. Both halves are asserted, because the rule
     * is a {@code DEFAULT}: the prefixed pointer finds the definitions asset, and the same pointer
     * without the prefix does not - it looks in {@code palettes}, where nothing of that id is loaded.
     */
    @Test
    @Rule("REF.043")
    void aFragmentPointerWithoutARegistryPrefixNamesAPalette() {
        DefinitionIndex registry = index("urbex:rubble", """
                { "version": 2, "kind": "weighted", "choices": [
                    { "weight": 1, "block": "minecraft:cobblestone" },
                    { "weight": 1, "block": "minecraft:mossy_cobblestone" } ] }
                """);

        Diagnostics prefixed = new Diagnostics();
        NodeResolver.ResolvedPalette resolved = NodeResolver.resolve(decode("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "$spread": "definitions/urbex:rubble#/choices" },
                    { "weight": 1, "block": "minecraft:stone_bricks" } ] } } }
                """), registry, Map.of(), prefixed)
                .orElseThrow(() -> new AssertionError(prefixed.asError().orElse("?")));
        assertEquals(List.of("minecraft:cobblestone", "minecraft:mossy_cobblestone",
                "minecraft:stone_bricks"), blocksOf(resolved, '#'));

        Diagnostics unprefixed = new Diagnostics();
        NodeResolver.resolve(decode("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "$spread": "urbex:rubble#/choices" } ] } } }
                """), registry, Map.of(), unprefixed);
        String message = unprefixed.asError().orElseThrow();
        assertTrue(Diag.DIAG_034.matches(message), message);
        assertTrue(message.contains("no asset 'urbex:rubble'"),
                () -> "the same id, looked for in 'palettes': " + message);
    }

    /**
     * {@code REF.045}: a pointer naming no asset, or no node at that path, is refused - and the
     * diagnostic says which half failed.
     * <p>
     * The other rule carrying {@code [NO-FIXTURE: a second asset]}. Both halves are asserted, because
     * "which half" is the whole content of the rule: an absent asset is a missing file or a misspelt
     * namespace, and an absent path is a file that exists and does not say what the author thought.
     */
    @Test
    @Rule("REF.045")
    void aPointerNamingNoAssetOrNoNodeAtThatPathIsRefused() {
        Map<Identifier, PaletteV2Definition> palettes = Map.of(
                Identifier.parse("urbex:common"), decode("""
                        { "version": 2, "$defs": { "rubble": "minecraft:cobblestone" },
                          "palette": { "X": "minecraft:stone" } }
                        """));

        Diagnostics noAsset = new Diagnostics();
        NodeResolver.resolve(decode("""
                { "version": 2, "palette": { "X": { "$ref": "urbex:absent#/$defs/rubble" } } }
                """), DefinitionIndex.empty(), palettes, noAsset);
        String missing = noAsset.asError().orElseThrow();
        assertTrue(Diag.DIAG_034.matches(missing), missing);
        assertTrue(missing.contains("no asset 'urbex:absent'"), missing);

        Diagnostics noPath = new Diagnostics();
        NodeResolver.resolve(decode("""
                { "version": 2, "palette": { "X": { "$ref": "urbex:common#/$defs/scone" } } }
                """), DefinitionIndex.empty(), palettes, noPath);
        String misspelt = noPath.asError().orElseThrow();
        assertTrue(Diag.DIAG_034.matches(misspelt), misspelt);
        assertTrue(misspelt.contains("no node at '/$defs/scone'"), misspelt);

        // And the half that does resolve, so the two failures above are failures of the pointer and
        // not of the machinery.
        Diagnostics fine = new Diagnostics();
        assertTrue(NodeResolver.resolve(decode("""
                { "version": 2, "palette": { "X": { "$ref": "urbex:common#/$defs/rubble" } } }
                """), DefinitionIndex.empty(), palettes, fine).isPresent(),
                () -> fine.asError().orElse("?"));
    }

    /**
     * {@code REF.046}: a pointer into another asset participates in cycle detection exactly as a local
     * one does.
     */
    @Test
    @Rule("REF.046")
    void aCycleThroughAnotherAssetIsStillACycle() {
        Identifier other = Identifier.parse("urbex:other");
        Map<Identifier, PaletteV2Definition> palettes = Map.of(other, decode("""
                { "version": 2, "$defs": { "there": { "$ref": "urbex:other#/$defs/there" } } }
                """));
        Diagnostics diagnostics = new Diagnostics();
        NodeResolver.resolve(decode("""
                { "version": 2, "palette": { "X": { "$ref": "urbex:other#/$defs/there" } } }
                """), DefinitionIndex.empty(), palettes, diagnostics);
        String message = diagnostics.asError().orElseThrow();
        assertTrue(Diag.DIAG_032.matches(message), message);
        assertTrue(message.contains("urbex:other#/$defs/there"), message);
    }

    /**
     * {@code REF.044}: a fragment resolves against the target's document, so it addresses what that file
     * wrote and not how far the loader has got.
     * <p>
     * The entry it lands on is then resolved in <em>its own</em> document's scope, which is the half that
     * matters in practice: {@code urbex:common}'s {@code rubble} may itself be a {@code $ref} to another
     * of {@code urbex:common}'s definitions, and by {@code REF.011} that name means nothing in the file
     * doing the pointing.
     */
    @Test
    @Rule("REF.044")
    @Rule("REF.086")
    void aPointedAtNodeResolvesItsOwnDocumentsNamesAndImports() {
        Map<Identifier, PaletteV2Definition> palettes = Map.of(
                Identifier.parse("urbex:common"), decode("""
                        { "version": 2,
                          "$defs": { "rubble": { "$ref": "cobble" },
                                     "cobble": { "block": "minecraft:cobblestone" } } }
                        """));
        Diagnostics diagnostics = new Diagnostics();
        NodeResolver.ResolvedPalette resolved = NodeResolver.resolve(decode("""
                { "version": 2, "$defs": { "cobble": { "block": "minecraft:diamond_block" } },
                  "palette": { "X": { "$ref": "urbex:common#/$defs/rubble" } } }
                """), DefinitionIndex.empty(), palettes, diagnostics)
                .orElseThrow(() -> new AssertionError(diagnostics.asError().orElse("?")));
        assertEquals("minecraft:cobblestone", blockOf(resolved, 'X'),
                "the pointed-at definition resolved 'cobble' against its own file, not the caller's");

        // And the addressing half, through PointerResolver directly: the path finds the entry as the
        // document wrote it, with its own $ref still on it. That is what REF.044 means by resolving
        // "before any of its $refs are" - the address is of the document, not of the resolved form.
        ResolutionScope scope = ResolutionScope.of(decode("{ \"version\": 2, \"palette\": {} }"),
                DefinitionIndex.empty(), palettes);
        Pointer pointer = Pointer.parse("urbex:common#/$defs/rubble", "a test").getOrThrow();
        RawNode addressed = PointerResolver.resolve(pointer, scope)
                .orElseThrow(() -> new AssertionError("the pointer should address a node"));
        assertEquals(Optional.of("cobble"), addressed.ref());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static PaletteV2Definition decode(String json) {
        return PaletteV2Definition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow();
    }

    private static DefinitionIndex index(String id, String json) {
        return new DefinitionIndex(Map.of(Identifier.parse(id),
                dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition.CODEC
                        .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow()));
    }

    private static NodeResolver.ResolvedPalette resolve(String json) {
        Diagnostics diagnostics = new Diagnostics();
        return NodeResolver.resolve(decode(json), diagnostics)
                .orElseThrow(() -> new AssertionError(
                        "expected the palette to resolve: " + diagnostics.asError().orElse("?")));
    }

    private static void assertRefused(Diag expected, String json) {
        Diagnostics diagnostics = new Diagnostics();
        Optional<NodeResolver.ResolvedPalette> resolved =
                NodeResolver.resolve(decode(json), diagnostics);
        assertTrue(resolved.isEmpty(), () -> "expected " + expected.id() + ", but it resolved");
        String message = diagnostics.asError().orElseThrow();
        assertTrue(expected.matches(message),
                () -> "expected " + expected.id() + ", got: " + message);
    }

    private static String blockOf(NodeResolver.ResolvedPalette resolved, char marker) {
        return assertInstanceOf(ResolvedNode.Source.Block.class,
                resolved.palette().get(new Marker(marker)).source()).block();
    }

    private static List<String> blocksOf(NodeResolver.ResolvedPalette resolved, char marker) {
        ResolvedNode.Source.Weighted weighted = assertInstanceOf(ResolvedNode.Source.Weighted.class,
                resolved.palette().get(new Marker(marker)).source());
        return weighted.choices().stream()
                .map(choice -> assertInstanceOf(ResolvedNode.Source.Block.class,
                        choice.node().source()).block())
                .toList();
    }
}
