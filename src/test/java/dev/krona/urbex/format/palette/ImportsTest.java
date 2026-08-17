package dev.krona.urbex.format.palette;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition;
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
 * {@code $imports} end to end, and the {@code definitions} registry the aliases mostly point into.
 * <p>
 * {@link PointerTest} covers the substitution as a text operation. This covers what a file gets out of
 * it - a pointer written short that resolves to the same node as the same pointer written long - and the
 * asset on the other end of it, which is this task's other new piece: a registry entry that is one node
 * ({@code REF.014}), carries {@code $imports} and no {@code $defs} ({@code REF.018}), and may not name
 * an unqualified definition ({@code REF.015}).
 */
class ImportsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- $imports ------------------------------------------------------------------------------

    /**
     * {@code REF.080}: {@code $imports} maps an alias name to a pointer prefix, and a pointer written
     * through one resolves to what the long form resolves to.
     * <p>
     * The pair is the assertion. An alias that resolved to something <em>else</em> would be a shorthand
     * that changed meaning, and the same palette is resolved twice here - once written short, once
     * written long - to say it does not.
     */
    @Test
    @Rule("REF.080")
    @Rule("REF.081")
    void anAliasResolvesToWhatTheSamePointerWrittenInFullResolvesTo() {
        Map<Identifier, PaletteV2Definition> palettes = Map.of(
                Identifier.parse("urbex:common"), palette("""
                        { "version": 2, "$defs": {
                            "Damageable": { "traits": {
                                "urbex:damaged": { "into": "minecraft:iron_bars" } } } } }
                        """));

        NodeResolver.ResolvedPalette shorthand = resolve(palettes, """
                { "version": 2, "$imports": { "mat": "urbex:common#/$defs" },
                  "palette": { "X": { "$ref": "$mat/Damageable", "$only": ["traits"],
                                      "block": "minecraft:deepslate_bricks" } } }
                """);
        NodeResolver.ResolvedPalette inFull = resolve(palettes, """
                { "version": 2,
                  "palette": { "X": { "$ref": "urbex:common#/$defs/Damageable", "$only": ["traits"],
                                      "block": "minecraft:deepslate_bricks" } } }
                """);
        assertEquals(inFull, shorthand);
        assertEquals(Set.of(Identifier.parse("urbex:damaged")),
                shorthand.palette().get(new Marker('X')).traits().keySet());
    }

    /**
     * {@code REF.086}: imports are file-local and are not inherited through {@code extends} - nor lent
     * to a file a pointer reaches into.
     * <p>
     * The half reachable in this task is the second, and it is the same property: the palette below
     * declares {@code mat} and points at {@code urbex:common}, whose own {@code $defs} entry writes
     * {@code $mat/…}. {@code urbex:common} declares no such alias, so the pointer inside it is refused
     * with {@code DIAG.039} rather than quietly expanded with the caller's table - which is what would
     * make "a pointer's meaning depend on a file the reader is not looking at".
     */
    @Test
    @Rule("REF.086")
    void anAliasIsNotLentToTheFileAPointerReachesInto() {
        Map<Identifier, PaletteV2Definition> palettes = Map.of(
                Identifier.parse("urbex:common"), palette("""
                        { "version": 2, "$defs": { "borrowing": { "$ref": "$mat/Damageable" } } }
                        """));
        Diagnostics diagnostics = new Diagnostics();
        NodeResolver.resolve(palette("""
                { "version": 2, "$imports": { "mat": "urbex:common#/$defs" },
                  "palette": { "X": { "$ref": "$mat/borrowing" } } }
                """), DefinitionIndex.empty(), palettes, diagnostics);
        String message = diagnostics.asError().orElseThrow();
        assertTrue(Diag.DIAG_039.matches(message), message);
        assertTrue(message.contains("'$mat'"), message);
    }

    /** {@code REF.082}: a file declaring {@code super} in {@code $imports} does not load. */
    @Test
    @Rule("REF.082")
    void superMayNotBeDeclaredAsAnImportOfAPaletteOrOfADefinitionsAsset() {
        DataResult<PaletteV2Definition> asPalette = PaletteV2Definition.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("""
                        { "version": 2, "$imports": { "super": "urbex:common#/palette" },
                          "palette": { "X": "minecraft:stone" } }
                        """));
        assertTrue(Diag.DIAG_070.matches(asPalette.error().orElseThrow().message()),
                asPalette.error().orElseThrow().message());

        DataResult<DefinitionAssetDefinition> asDefinition = DefinitionAssetDefinition.CODEC.parse(
                JsonOps.INSTANCE, JsonParser.parseString("""
                        { "version": 2, "$imports": { "super": "urbex:common#/palette" },
                          "block": "minecraft:stone" }
                        """));
        assertTrue(Diag.DIAG_070.matches(asDefinition.error().orElseThrow().message()),
                asDefinition.error().orElseThrow().message());
    }

    // ---- The definitions registry --------------------------------------------------------------

    /**
     * {@code REF.014}: a definitions asset is a single node, with the file-level keys {@code version}
     * and {@code extends}, and no {@code palette}.
     */
    @Test
    @Rule("REF.014")
    void aDefinitionsAssetIsOneNodeWithTheFileLevelKeysAroundIt() {
        DefinitionAssetDefinition asset = definition("""
                { "version": 2, "extends": "urbex:rubble_base",
                  "kind": "weighted",
                  "choices": [ { "weight": 1, "block": "minecraft:cobblestone" },
                               { "weight": 1, "block": "minecraft:mossy_cobblestone" } ],
                  "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } } }
                """);
        assertEquals(Optional.of(Identifier.parse("urbex:rubble_base")), asset.extendsId());
        assertEquals(Optional.of(Kind.WEIGHTED), asset.node().kind());
        assertEquals(2, asset.node().choices().orElseThrow().size());
        assertEquals(Set.of(Identifier.parse("urbex:damaged")), asset.node().traits().keySet());
        assertEquals(DefinitionAssetDefinition.FORMAT_VERSION, asset.formatVersion());

        // 'palette' is not a key of a node, so it is refused as one - by MODEL.004, at the one level
        // this asset has.
        String message = refuse("""
                { "version": 2, "block": "minecraft:stone", "palette": { "X": "minecraft:stone" } }
                """);
        assertTrue(Diag.DIAG_003.matches(message), message);
        assertTrue(message.contains("'palette'"), message);
    }

    /** {@code REF.018}: a definitions asset may carry {@code $imports}, and may not carry {@code $defs}. */
    @Test
    @Rule("REF.018")
    void aDefinitionsAssetCarriesImportsAndNotDefs() {
        DefinitionAssetDefinition asset = definition("""
                { "version": 2, "$imports": { "mat": "urbex:common#/$defs" },
                  "$ref": "$mat/Damageable", "block": "minecraft:cobblestone" }
                """);
        assertEquals(Map.of("mat", "urbex:common#/$defs"), asset.imports());

        String message = refuse("""
                { "version": 2, "block": "minecraft:stone",
                  "$defs": { "inner": "minecraft:cobblestone" } }
                """);
        assertTrue(Diag.DIAG_003.matches(message), message);
        assertTrue(message.contains("'$defs'"), message);
    }

    /**
     * {@code REF.015}: a definitions asset may not {@code $ref} an unqualified name.
     * <p>
     * {@code DIAG.033}, and the reason it is refused rather than resolved against the caller is the
     * rule's own {@code > Why}: "resolving against the referring file's would make a shared definition
     * mean different things to different callers". An alias is not an unqualified name - it expands to a
     * qualified pointer against this asset's own {@code $imports} - and neither is {@code $super}, which
     * names what this asset's own {@code extends} chain gave it.
     */
    @Test
    @Rule("REF.015")
    void aDefinitionsAssetMayNotReferenceAnUnqualifiedName() {
        String message = refuse("""
                { "version": 2, "$ref": "rubble", "block": "minecraft:stone" }
                """);
        assertTrue(Diag.DIAG_033.matches(message), message);
        assertTrue(message.contains("'rubble'"), message);

        // Nested as deeply as the format allows, because a choice is a node and REF.015 is about the
        // asset rather than about its top level.
        String nested = refuse("""
                { "version": 2, "kind": "weighted",
                  "choices": [ { "weight": 1, "$ref": "rubble" } ] }
                """);
        assertTrue(Diag.DIAG_033.matches(nested), nested);

        // The two forms that look local and are not.
        definition("""
                { "version": 2, "$imports": { "mat": "urbex:common#/$defs" },
                  "$ref": "$mat/Damageable" }
                """);
        definition("""
                { "version": 2, "extends": "urbex:rubble_base", "$ref": "$super",
                  "traits": { "urbex:rotatable": false } }
                """);
    }

    /**
     * A definitions asset declares {@code "version": 2} and there is no other form of one.
     * <p>
     * The refusal carries no catalogue row, which is a disclosed gap rather than a decision:
     * {@code DIAG.001}'s remedy offers "or omit it for the version 1 format", and this registry has never
     * had a version 1 form, so saying that to an author would send them looking for a format that does
     * not exist. Recorded in the task report.
     */
    @Test
    @Rule("VER.002")
    void aDefinitionsAssetDeclaresVersionTwoAndNothingElse() {
        for (String version : List.of("", "\"version\": 1,", "\"version\": 3,",
                "\"version\": 2.5,", "\"version\": \"2\",")) {
            DataResult<DefinitionAssetDefinition> decoded = DefinitionAssetDefinition.CODEC.parse(
                    JsonOps.INSTANCE, JsonParser.parseString(
                            "{" + version + "\"block\": \"minecraft:stone\"}"));
            assertTrue(decoded.error().isPresent(),
                    () -> "expected [" + version + "] to be refused, got " + decoded.result());
            assertTrue(decoded.error().orElseThrow().message().contains("\"version\": 2"),
                    decoded.error().orElseThrow().message());
        }
        assertEquals(Optional.of(Kind.BLOCK), definition("""
                { "version": 2, "kind": "block", "block": "minecraft:stone" }
                """).node().kind());
    }

    /**
     * A definitions asset round trips: what it encodes decodes to the same asset.
     * <p>
     * Worth asserting because two things here can only be wrong on the way out. {@code version} is
     * written back explicitly, without which the document would not decode again at all; and the node's
     * keys are written at the <em>top level</em>, beside it, rather than nested under a field, which is
     * what {@code REF.014} means by "a definitions asset is a single node".
     */
    @Test
    @Rule("REF.014")
    void aDefinitionsAssetRoundTripsThroughItsOwnCodec() {
        for (String written : List.of(
                "{ \"version\": 2, \"block\": \"minecraft:stone\" }",
                "{ \"version\": 2, \"extends\": \"urbex:base\", \"kind\": \"weighted\","
                        + " \"choices\": [ { \"weight\": 1, \"block\": \"minecraft:stone\" } ] }",
                "{ \"version\": 2, \"$imports\": { \"mat\": \"urbex:common#/$defs\" },"
                        + " \"traits\": { \"urbex:rotatable\": false } }")) {
            DefinitionAssetDefinition asset = definition(written);
            JsonElement encoded = DefinitionAssetDefinition.CODEC
                    .encodeStart(JsonOps.INSTANCE, asset).getOrThrow();
            assertEquals(2, encoded.getAsJsonObject().get("version").getAsInt(), written);
            assertEquals(asset, DefinitionAssetDefinition.CODEC
                    .parse(JsonOps.INSTANCE, encoded).getOrThrow(), written);
        }
    }

    /**
     * The registry is registered as {@code urbex:definitions}, and {@code variants} is still registered
     * beside it.
     * <p>
     * Both halves, because the second is the promise to every shipped pack: {@code VER.004} says version
     * 1 does not change, and version 1 palettes reach their weighted lists through {@code variants}. The
     * new registry is what {@code variants} <em>becomes</em> for a version 2 file, not what replaces it.
     */
    @Test
    @Rule("REF.010")
    @Rule("VER.004")
    void theDefinitionsRegistryIsRegisteredAndVariantsStillIs() {
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "definitions"),
                CustomRegistries.DEFINITIONS_REGISTRY_KEY.identifier());
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "variants"),
                CustomRegistries.VARIANTS_REGISTRY_KEY.identifier());
        assertEquals(Pointer.DEFINITIONS_REGISTRY,
                CustomRegistries.DEFINITIONS_REGISTRY_KEY.identifier(),
                "a pointer's 'definitions' prefix and the registry key must be the same id");
    }

    /**
     * {@code MODEL.044}: a named weighted node in the definitions registry is what a {@code variant}
     * was - and now carries what a variant could not.
     * <p>
     * The migration table's row is {@code "variant": "<id>"} becoming {@code { "$ref": "<id>" }}, and
     * what makes the new tier more than a rename is that the asset is a <em>node</em>: it may have any of
     * the five kinds and it may carry traits, neither of which a {@code VariantDefinition} could.
     */
    @Test
    @Rule("MODEL.044")
    @Rule("VER.008")
    void aDefinitionsAssetIsAVariantThatMayAlsoCarryTraitsAndAnyKind() {
        DefinitionIndex registry = new DefinitionIndex(Map.of(
                Identifier.parse("urbex:rubble"), definition("""
                        { "version": 2, "kind": "weighted",
                          "choices": [ { "share": 0.1, "block": "minecraft:cobblestone" },
                                       { "rest": true, "block": "minecraft:stone_bricks" } ],
                          "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } } }
                        """),
                Identifier.parse("urbex:planks"), definition("""
                        { "version": 2, "kind": "tag", "tag": "#minecraft:planks" }
                        """)));

        Diagnostics diagnostics = new Diagnostics();
        NodeResolver.ResolvedPalette resolved = NodeResolver.resolve(palette("""
                { "version": 2, "palette": { "#": { "$ref": "urbex:rubble" },
                                             "p": { "$ref": "urbex:planks" } } }
                """), registry, Map.of(), diagnostics)
                .orElseThrow(() -> new AssertionError(diagnostics.asError().orElse("?")));

        ResolvedNode weighted = resolved.palette().get(new Marker('#'));
        assertEquals(Kind.WEIGHTED, weighted.kind());
        assertEquals(2, assertInstanceOf(ResolvedNode.Source.Weighted.class, weighted.source())
                .choices().size());
        assertEquals(Set.of(Identifier.parse("urbex:damaged")), weighted.traits().keySet());
        assertEquals(new ResolvedNode.Source.Tag("#minecraft:planks"),
                resolved.palette().get(new Marker('p')).source());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static PaletteV2Definition palette(String json) {
        return PaletteV2Definition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow();
    }

    private static DefinitionAssetDefinition definition(String json) {
        return DefinitionAssetDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow();
    }

    private static String refuse(String json) {
        DataResult<DefinitionAssetDefinition> decoded = DefinitionAssetDefinition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json));
        return decoded.error()
                .orElseThrow(() -> new AssertionError("expected a refusal, got " + decoded.result()))
                .message();
    }

    private static NodeResolver.ResolvedPalette resolve(Map<Identifier, PaletteV2Definition> palettes,
                                                        String json) {
        Diagnostics diagnostics = new Diagnostics();
        return NodeResolver.resolve(palette(json), DefinitionIndex.empty(), palettes, diagnostics)
                .orElseThrow(() -> new AssertionError(
                        "expected the palette to resolve: " + diagnostics.asError().orElse("?")));
    }
}
