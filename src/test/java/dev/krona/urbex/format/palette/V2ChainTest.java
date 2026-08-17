package dev.krona.urbex.format.palette;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an {@code extends} chain merges into, and what {@code $super} names once there is something to
 * inherit.
 * <p>
 * Every test here is a chain, which is why none of them is a fixture: a fixture is one document, and
 * the rules of {@code 04-merging.md} are all about what two documents do together. That is what the
 * {@code [NO-FIXTURE: a parent palette]} marker on {@code REF.062} says, and it is why {@code MERGE.005}
 * and {@code MERGE.006} carry fixtures that {@code FormatFixtureTest} can only decode - the parent they
 * name is not in the file. Both of those fixtures are written out in full below, over the parent they
 * were always about.
 * <p>
 * The parent is called {@code urbex:bricks_standard} throughout, because the specification's fixtures
 * do; nothing looks it up by name, since a merge takes the chain its caller already walked.
 */
class V2ChainTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- What merges, and how --------------------------------------------------------------------

    /**
     * {@code MERGE.001} and {@code MERGE.002}: the chain is applied root-first, and {@code palette}
     * merges by marker.
     * <p>
     * "A file redefining two markers out of thirty replaces exactly those two and leaves the other
     * twenty-eight untouched." Replacing the whole map would silently drop everything the child did not
     * restate; appending would register a marker twice. This is the version 1 fold's rule too, which is
     * the point - it is the behaviour packs already rely on, stated.
     */
    @Test
    @Rule("MERGE.001")
    @Rule("MERGE.002")
    void aChildRepaintsTheMarkersItDeclaresAndLeavesTheRestAlone() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2, "palette": {
                    "X": "minecraft:stone_bricks",
                    "b": "minecraft:bricks",
                    "g": "minecraft:glass" } }
                """, """
                { "version": 2, "extends": "urbex:bricks_standard",
                  "palette": { "X": "minecraft:deepslate_bricks", "w": "minecraft:oak_planks" } }
                """);

        assertEquals("minecraft:deepslate_bricks", blockOf(resolved, 'X'), "the child's marker wins");
        assertEquals("minecraft:bricks", blockOf(resolved, 'b'), "markers it never mentions survive");
        assertEquals("minecraft:glass", blockOf(resolved, 'g'));
        assertEquals("minecraft:oak_planks", blockOf(resolved, 'w'), "and a marker it adds joins them");
        assertEquals(4, resolved.palette().size(), "four markers, not five: 'X' was replaced, not appended");
    }

    /** {@code MERGE.003}: {@code $defs} merges by definition name, by the same rule. */
    @Test
    @Rule("MERGE.003")
    void definitionsMergeByNameTheSameWayMarkersDo() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "wall": "minecraft:stone_bricks", "roof": "minecraft:deepslate_tiles" },
                  "palette": { "W": { "$ref": "wall" }, "R": { "$ref": "roof" } } }
                """, """
                { "version": 2, "extends": "urbex:bricks_standard",
                  "$defs": { "wall": "minecraft:mossy_stone_bricks" } }
                """);

        assertEquals(Set.of("wall", "roof"), resolved.defs().keySet());
        assertEquals("minecraft:mossy_stone_bricks", blockOf(resolved, 'W'));
        assertEquals("minecraft:deepslate_tiles", blockOf(resolved, 'R'),
                "the definition the child never mentions is still there");
    }

    /**
     * {@code MERGE.004} and {@code MERGE.008}: a declared entry replaces what it inherits, traits
     * included.
     * <p>
     * {@code MERGE.008} spells out the consequence that would otherwise be a surprise - "a marker a
     * child repaints does not keep its ancestor's {@code urbex:damaged}" - and it is free under
     * replacement, which is exactly why it is tested: the code that makes it true is a
     * {@link java.util.Map#put}, and nothing about a {@code put} says it must stay one. The rule that a
     * reader could not otherwise predict is the alternative: an entry that extended what it inherited
     * would leave a reader unable to tell, from the entry alone, whether it adds or supplants.
     */
    @Test
    @Rule("MERGE.004")
    @Rule("MERGE.008")
    void anOverriddenMarkerTakesItsTraitsWithIt() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2, "palette": { "X": {
                    "block": "minecraft:stone_bricks",
                    "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } } } } }
                """, """
                { "version": 2, "extends": "urbex:bricks_standard",
                  "palette": { "X": "minecraft:deepslate_bricks" } }
                """);

        assertEquals("minecraft:deepslate_bricks", blockOf(resolved, 'X'));
        assertEquals(Map.of(), resolved.palette().get(new Marker('X')).traits(),
                "the ancestor's traits went with the entry the child replaced");
    }

    /**
     * {@code MERGE.007}: {@code palette} is required somewhere in the chain, not in every file.
     * <p>
     * Both directions, because the rule is two claims. A chain where no file declares one is
     * {@code DIAG.002} - which is also {@code MERGE.007}'s own fixture, run by {@code FormatFixtureTest}
     * as a chain of one. A file that declares none but extends one that does is {@code MERGE.006}'s
     * whole idiom and must load.
     */
    @Test
    @Rule("MERGE.007")
    void aPaletteIsRequiredSomewhereInTheChainAndNotInEveryFile() {
        Diagnostics diagnostics = new Diagnostics();
        assertTrue(V2Chain.merge(List.of(
                        decode("{ \"version\": 2, \"$defs\": { \"wall\": \"minecraft:stone_bricks\" } }"),
                        decode("{ \"version\": 2, \"extends\": \"urbex:bricks_standard\","
                                + " \"$defs\": { \"roof\": \"minecraft:deepslate_tiles\" } }")),
                Optional.of(Identifier.parse("urbex:bricks_mossy")), diagnostics).isEmpty(),
                "a chain where nothing declares a palette has no markers to generate with");
        String message = diagnostics.asError().orElseThrow();
        assertTrue(Diag.DIAG_002.matches(message), message);
        assertTrue(message.contains("urbex:bricks_mossy"),
                () -> "the diagnostic names the asset, as DIAG.900 requires: " + message);

        NodeResolver.ResolvedPalette inherited = resolve("""
                { "version": 2, "palette": { "X": { "$ref": "wall" } },
                  "$defs": { "wall": "minecraft:stone_bricks" } }
                """, """
                { "version": 2, "extends": "urbex:bricks_standard",
                  "$defs": { "wall": "minecraft:mossy_stone_bricks" } }
                """);
        assertEquals("minecraft:mossy_stone_bricks", blockOf(inherited, 'X'),
                "a file that declares no palette of its own inherits its ancestor's");
    }

    /**
     * {@code MERGE.006}: redefining a definition repaints every marker that references it, including
     * markers this file does not mention.
     * <p>
     * The specification's own fixture for the rule, over the parent it names. This is the mechanism that
     * makes "the same layout in different materials" a short file - Modern Tweaks ships 460 palette-file
     * pairs sharing 90% or more of their markers - and it is the reason a bare name in an ancestor's
     * node resolves against the <em>merged</em> {@code $defs} rather than against the file that wrote
     * the node.
     */
    @Test
    @Rule("MERGE.006")
    void redefiningADefinitionRepaintsEveryMarkerThatReferencesIt() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2,
                  "$defs": { "wall": { "kind": "weighted", "choices": [
                      { "share": 0.1, "block": "minecraft:stone_bricks" },
                      { "rest": true, "block": "minecraft:cracked_stone_bricks" } ] } },
                  "palette": { "X": { "$ref": "wall" }, "Y": { "$ref": "wall" } } }
                """, """
                {
                  "version": 2,
                  "extends": "urbex:bricks_standard",
                  "$defs": {
                    "wall": {
                      "kind": "weighted",
                      "choices": [
                        { "share": 0.1, "block": "minecraft:gray_concrete" },
                        { "rest": true, "block": "minecraft:light_gray_concrete" }
                      ]
                    }
                  }
                }
                """);

        assertEquals(List.of("minecraft:gray_concrete", "minecraft:light_gray_concrete"),
                blocksOf(resolved, 'X'));
        assertEquals(List.of("minecraft:gray_concrete", "minecraft:light_gray_concrete"),
                blocksOf(resolved, 'Y'), "and the marker the child never mentions is repainted too");
    }

    // ---- $super ----------------------------------------------------------------------------------

    /**
     * {@code MERGE.005} and {@code REF.060}: extension is written with {@code $super}, and replacement
     * is what happens without it.
     * <p>
     * The specification's {@code extend-vs-replace} fixture, over the parent it names, and the payoff of
     * {@code MERGE.004}: both markers below are legible without opening the ancestor. {@code X} is
     * replaced outright - "whatever the ancestor said about it is gone, traits included" - and
     * {@code $} keeps everything it inherited and adds one trait, because it says so.
     */
    @Test
    @Rule("MERGE.005")
    @Rule("REF.060")
    void anEntryReplacesWhatItInheritsUnlessItNamesItWithSuper() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2, "palette": {
                    "X": { "block": "minecraft:stone_bricks",
                           "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } } },
                    "$": { "block": "minecraft:oak_door[facing=north]",
                           "traits": { "urbex:damaged": { "into": "minecraft:air" } } } } }
                """, """
                {
                  "version": 2,
                  "extends": "urbex:bricks_standard",
                  "palette": {
                    "X": { "block": "minecraft:deepslate_bricks" },
                    "$": { "$ref": "$super", "traits": { "urbex:rotatable": false } }
                  }
                }
                """);

        assertEquals("minecraft:deepslate_bricks", blockOf(resolved, 'X'));
        assertEquals(Map.of(), resolved.palette().get(new Marker('X')).traits(),
                "X is replaced outright, traits included");

        assertEquals("minecraft:oak_door[facing=north]", blockOf(resolved, '$'),
                "$ keeps the block it inherited");
        assertEquals(Set.of(Identifier.parse("urbex:damaged"), Identifier.parse("urbex:rotatable")),
                resolved.palette().get(new Marker('$')).traits().keySet(),
                "and keeps the inherited trait beside the one it adds, by REF.004");
    }

    /**
     * {@code REF.061}: {@code $super} may be used at any depth within its entry, including as the base
     * of a fragment.
     * <p>
     * {@code REF.070}'s {@code append-a-choice} fixture, over its parent. This is the case
     * {@code $spread} exists for, and the one version 1 could not express at all: {@code $ref} replaces
     * whole keys, so declaring {@code choices} replaces the inherited list, and
     * {@code {"$spread": "$super#/choices"}} is how an author adds one choice to it and says where.
     */
    @Test
    @Rule("REF.061")
    @Rule("REF.070")
    void superIsUsableAsTheBaseOfAFragment() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "weight": 8, "block": "minecraft:deepslate_bricks" },
                    { "weight": 2, "block": "minecraft:cracked_deepslate_bricks" } ] } } }
                """, """
                {
                  "version": 2,
                  "extends": "urbex:bricks_standard",
                  "palette": {
                    "#": {
                      "$ref": "$super",
                      "choices": [
                        { "$spread": "$super#/choices" },
                        { "weight": 4, "block": "minecraft:cracked_deepslate_bricks" }
                      ]
                    }
                  }
                }
                """);

        assertEquals(List.of("minecraft:deepslate_bricks", "minecraft:cracked_deepslate_bricks",
                        "minecraft:cracked_deepslate_bricks"),
                blocksOf(resolved, '#'),
                "the inherited list, in order, then the choice this file appends");
    }

    /**
     * {@code REF.062}: {@code $super} in an entry that inherits nothing is refused - the second of
     * {@code DIAG.036}'s two cases.
     * <p>
     * The first case, a file declaring no {@code extends}, is {@code NodeResolverTest}'s and was
     * reachable before there was a chain. This is the one that needed a parent palette, which is why the
     * rule carries {@code [NO-FIXTURE: a parent palette]}: the file <em>does</em> extend something, and
     * that something says nothing about this marker. The two are different mistakes with different
     * fixes, and the diagnostic says which one was made.
     */
    @Test
    @Rule("REF.062")
    void superInAnEntryNoAncestorDeclaresIsRefused() {
        String message = refusalOf("""
                { "version": 2, "palette": { "X": "minecraft:stone_bricks" } }
                """, """
                { "version": 2, "extends": "urbex:bricks_standard",
                  "palette": { "Y": { "$ref": "$super" } } }
                """);
        assertTrue(Diag.DIAG_036.matches(message), message);
        assertTrue(message.contains("nothing in its extends chain declares"),
                () -> "the file does extend something, so the other sentence would be false: " + message);
    }

    /**
     * {@code REF.063}: {@code $super} names the inherited value, not a named ancestor.
     * <p>
     * "A file that changes what it extends changes what {@code $super} means, and does not need
     * editing." Asserted by doing exactly that: the same child, byte for byte, over two different
     * parents. The alternative the rule rejects is writing the ancestor's id in a pointer, which
     * duplicates what {@code extends} already says and goes stale silently when {@code extends} changes.
     */
    @Test
    @Rule("REF.063")
    void superNamesWhatIsInheritedRatherThanANamedAncestor() {
        String child = """
                { "version": 2, "extends": "urbex:bricks_standard",
                  "palette": { "X": { "$ref": "$super",
                                      "traits": { "urbex:rotatable": false } } } }
                """;

        assertEquals("minecraft:stone_bricks", blockOf(resolve("""
                { "version": 2, "palette": { "X": "minecraft:stone_bricks" } }
                """, child), 'X'));
        assertEquals("minecraft:deepslate_bricks", blockOf(resolve("""
                { "version": 2, "palette": { "X": "minecraft:deepslate_bricks" } }
                """, child), 'X'),
                "the same child over a different parent means a different thing, with no edit");
    }

    /**
     * {@code MERGE.001} over three files, where the middle one also extends what it inherits.
     * <p>
     * "Each file is applied over the accumulated result", and {@code $super} follows the chain down one
     * layer at a time: the leaf's names the middle file's node, and the middle file's names the root's.
     * Worth its own test because the two layers are two nodes of the reference graph and were briefly
     * one - keyed on the entry alone, the second hop would have been reported as a cycle
     * ({@code DIAG.032}) in a file that has none.
     */
    @Test
    @Rule("MERGE.001")
    @Rule("REF.061")
    void superFollowsADeepChainOneLayerAtATime() {
        NodeResolver.ResolvedPalette resolved = resolve("""
                { "version": 2, "palette": { "#": { "kind": "weighted", "choices": [
                    { "weight": 1, "block": "minecraft:stone_bricks" } ] } } }
                """, """
                { "version": 2, "extends": "urbex:bricks_standard", "palette": { "#": {
                    "$ref": "$super", "choices": [
                        { "$spread": "$super#/choices" },
                        { "weight": 1, "block": "minecraft:mossy_stone_bricks" } ] } } }
                """, """
                { "version": 2, "extends": "urbex:bricks_mossy", "palette": { "#": {
                    "$ref": "$super", "choices": [
                        { "$spread": "$super#/choices" },
                        { "weight": 1, "block": "minecraft:cracked_stone_bricks" } ] } } }
                """);

        assertEquals(List.of("minecraft:stone_bricks", "minecraft:mossy_stone_bricks",
                        "minecraft:cracked_stone_bricks"),
                blocksOf(resolved, '#'),
                "each layer added its own choice after the ones it inherited");
    }

    // ---- What the merge does not carry ------------------------------------------------------------

    /**
     * {@code REF.086}: imports are file-local and are not inherited through {@code extends}.
     * <p>
     * Both halves, because only together do they say what the rule means. A child writing an alias its
     * ancestor declared is refused by name ({@code DIAG.039}) rather than silently resolving against a
     * file the reader is not looking at - "an inherited alias would make a pointer's meaning depend on a
     * file the reader is not looking at, which is the property {@code $super} exists to avoid needing".
     * And the ancestor's <em>own</em> entry keeps working after the merge, which is the half that has to
     * be true for the first to be worth anything: the alias travels with the node that was written with
     * it, not with the file at the end of the chain.
     */
    @Test
    @Rule("REF.086")
    void importsAreNotInheritedThoughAnAncestorsOwnPointersStillResolve() {
        String parent = """
                { "version": 2,
                  "$imports": { "mat": "urbex:common#/$defs" },
                  "palette": { "}": { "$ref": "$mat/rubble" } } }
                """;

        NodeResolver.ResolvedPalette resolved = resolveWith(common(), parent, """
                { "version": 2, "extends": "urbex:bricks_standard",
                  "palette": { "X": "minecraft:deepslate_bricks" } }
                """);
        assertEquals("minecraft:cobblestone", blockOf(resolved, '}'),
                "the parent's own alias resolved, in the merged palette, against the parent's $imports");

        Diagnostics diagnostics = new Diagnostics();
        NodeResolver.resolve(mergeOf(parent, """
                { "version": 2, "extends": "urbex:bricks_standard",
                  "palette": { "X": { "$ref": "$mat/rubble" } } }
                """), DefinitionIndex.empty(), palettes(common()), diagnostics);
        String message = diagnostics.asError().orElseThrow();
        assertTrue(Diag.DIAG_039.matches(message), message);
        assertTrue(message.contains("mat"),
                () -> "the alias the child did not declare is named: " + message);
    }

    /**
     * {@code REF.033}: cycle detection covers {@code $ref} and {@code extends} together, and a cycle
     * through both is one cycle.
     * <p>
     * Two shapes, and neither file of either is cyclic on its own - that is what makes them this rule's
     * and not {@code REF.032}'s. The first is a cycle in the merged {@code $defs}: each file references a
     * name the other declares, which is legal in isolation ({@code DIAG.030} apiece) and a cycle once
     * {@code MERGE.003} has made them one map. The second runs through {@code $super}, where the value a
     * child replaced references the name that replaced it.
     * <p>
     * Both must be <em>named</em>. Without a cycle check the walk follows the second one until the JVM's
     * stack runs out, and a {@link StackOverflowError} out of a load says nothing about which file to
     * edit; this test fails on that outcome by being unable to complete, which is deliberate.
     */
    @Test
    @Rule("REF.033")
    @Rule("REF.032")
    void aCycleThroughRefAndExtendsTogetherIsOneCycle() {
        String throughDefs = refusalOf("""
                { "version": 2, "$defs": { "d": { "$ref": "e" } },
                  "palette": { "X": { "$ref": "d" } } }
                """, """
                { "version": 2, "extends": "urbex:bricks_standard",
                  "$defs": { "e": { "$ref": "d" } } }
                """);
        assertTrue(Diag.DIAG_032.matches(throughDefs), throughDefs);
        assertTrue(throughDefs.contains("d → e → d"),
                () -> "the cycle is named in declaration order, from the node reached first: "
                        + throughDefs);

        String throughSuper = refusalOf("""
                { "version": 2, "$defs": { "d": { "$ref": "e" } },
                  "palette": { "X": { "$ref": "d" } } }
                """, """
                { "version": 2, "extends": "urbex:bricks_standard",
                  "$defs": { "d": { "$ref": "$super" }, "e": { "$ref": "d" } } }
                """);
        assertTrue(Diag.DIAG_032.matches(throughSuper), throughSuper);
        assertTrue(throughSuper.contains("$super"),
                () -> "the inherited layer is a node of the cycle and is named as one: " + throughSuper);
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /** {@code urbex:common}, the asset the imports test points into. */
    private static String common() {
        return """
                { "version": 2, "$defs": { "rubble": "minecraft:cobblestone" },
                  "palette": { "c": "minecraft:cobblestone" } }
                """;
    }

    private static PaletteV2Definition decode(String json) {
        return PaletteV2Definition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow();
    }

    private static Map<Identifier, PaletteV2Definition> palettes(String commonJson) {
        return Map.of(Identifier.parse("urbex:common"), decode(commonJson));
    }

    /** The chain, root first, merged - or a failure, if {@code MERGE.007} refused it. */
    private static V2Chain.MergedPalette mergeOf(String... chainRootFirst) {
        List<PaletteV2Definition> chain = new ArrayList<>();
        for (String json : chainRootFirst) {
            chain.add(decode(json));
        }
        Diagnostics diagnostics = new Diagnostics();
        return V2Chain.merge(chain, Optional.empty(), diagnostics)
                .orElseThrow(() -> new AssertionError(
                        "expected the chain to merge: " + diagnostics.asError().orElse("?")));
    }

    private static NodeResolver.ResolvedPalette resolve(String... chainRootFirst) {
        return resolveIn(Map.of(), chainRootFirst);
    }

    /** The same, with {@code urbex:common} reachable by a pointer. */
    private static NodeResolver.ResolvedPalette resolveWith(String commonJson,
                                                            String... chainRootFirst) {
        return resolveIn(palettes(commonJson), chainRootFirst);
    }

    private static NodeResolver.ResolvedPalette resolveIn(Map<Identifier, PaletteV2Definition> palettes,
                                                          String... chainRootFirst) {
        Diagnostics diagnostics = new Diagnostics();
        return NodeResolver.resolve(mergeOf(chainRootFirst), DefinitionIndex.empty(), palettes,
                        diagnostics)
                .orElseThrow(() -> new AssertionError(
                        "expected the chain to resolve: " + diagnostics.asError().orElse("?")));
    }

    /** The message a chain is refused with, once merged. */
    private static String refusalOf(String... chainRootFirst) {
        Diagnostics diagnostics = new Diagnostics();
        Optional<NodeResolver.ResolvedPalette> resolved = NodeResolver.resolve(
                mergeOf(chainRootFirst), DefinitionIndex.empty(), Map.of(), diagnostics);
        assertTrue(resolved.isEmpty(), "expected a refusal, but the chain resolved");
        return diagnostics.asError().orElseThrow();
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
