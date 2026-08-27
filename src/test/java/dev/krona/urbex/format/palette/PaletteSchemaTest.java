package dev.krona.urbex.format.palette;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.format.SpecDocuments;
import dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition;
import dev.krona.urbex.format.palette.traits.BlockEntityNbt;
import dev.krona.urbex.format.palette.traits.Damaged;
import dev.krona.urbex.format.palette.traits.Light;
import dev.krona.urbex.format.palette.traits.Loot;
import dev.krona.urbex.format.palette.traits.OptionalTrait;
import dev.krona.urbex.format.palette.traits.Rotatable;
import dev.krona.urbex.format.palette.traits.Spawner;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift-guards {@code docs/schema/palette.v2.schema.json} against the version 2 codecs, and validates
 * every fixture the specification defines against it - {@code PresetSchemaTest}'s shape, applied to a
 * recursive node instead of a flat section list.
 * <p>
 * <b>Two things this schema cannot be, and both are reasons it is still worth having.</b> It is not a
 * second implementation of {@code RawNode}'s decoder: {@code MODEL.062}'s alias resolution,
 * {@code WEIGHT.014}'s share arithmetic, {@code TRAIT.021}'s registry lookup and every other rule that
 * needs another document, a game registry, or exact rational arithmetic across sibling choices is
 * refused by the loader and accepted here - JSON Schema has no way to ask any of those questions. And it
 * is not a hand-written list of keys: every key set it declares is compared below against the same
 * codec constants the decoder is built from, so a key added to {@link RawNode}, {@link RawChoice}, a
 * {@link dev.krona.urbex.format.palette.traits} record or {@link PaletteV2Definition} without a matching
 * schema edit fails a test here rather than going undocumented.
 * <p>
 * What it <em>is</em> for is caught by {@link #everySpecificationFixtureValidatesAsTheShapeLevelExpects()}:
 * every fixture the specification defines, run through this schema, and asserted at whichever strength
 * the schema can actually claim - {@code accept} for every {@code accept}/{@code equiv} fixture, and
 * {@code reject} for the subset of {@code reject} fixtures whose refusal is a shape a JSON Schema can
 * express (an unknown key, a kind-specific key on the wrong kind, a missing required key, an out-of-range
 * number, two keys that are never both legal) rather than a fact about another document or the installed
 * game.
 * {@link #SHAPE_LEVEL_REJECTIONS} is that subset, named explicitly rather than inferred, because which
 * rules belong in it is exactly the judgement call this task's brief asks for: "an accept fixture the
 * schema rejects, or a shape-level reject fixture it accepts, is a defect in one of the two, and finding
 * which is the point."
 */
class PaletteSchemaTest {

    private static final Path SCHEMA_PATH = Path.of("docs/schema/palette.v2.schema.json");

    /**
     * REJECT fixtures whose refusal is a shape - closed keys, an enum, a numeric range, a presence
     * constraint - that this schema is built to catch, addressed as {@code <rule>#<ordinal>}
     * ({@code docs/format/README.md} §4.1's addressing, matched by {@code FormatFixtureTest}).
     * <p>
     * Every other {@code REJECT} fixture in the specification needs a fact this schema cannot have: a
     * cycle or a dangling name across {@code $defs} ({@code REF.013}, {@code REF.032}), a game registry
     * ({@code MODEL.043}, {@code MODEL.053}, {@code TRAIT.021}, {@code TRAIT.031}, {@code TRAIT.041},
     * {@code TRAIT.044}, {@code TRAIT.052}, {@code TRAIT.053}), exact rational arithmetic over a list's
     * siblings ({@code WEIGHT.014}, {@code WEIGHT.024}, {@code WEIGHT.032}), a Unicode category table
     * ({@code CHAR.003}-{@code CHAR.005}), a merged {@code extends} chain ({@code MERGE.007}), or a
     * resolved pointer's target type ({@code REF.071}, {@code REF.083}). Those are asserted {@code accept}
     * by {@link #everySpecificationFixtureValidatesAsTheShapeLevelExpects()} instead, which is the honest
     * claim: this schema does not refuse them, on purpose.
     * <p>
     * {@code MODEL.033} <b>used to be listed among those exclusions</b>, on the reasoning that a satellite
     * and an ordinary node share the one {@code $defs/node} the brief requires, so the restriction "could
     * not live in the node schema without a second copy of it". That reasoning was wrong: it does not need
     * a second copy of {@code node}, only one extra {@code not} clause composed onto it with {@code allOf}
     * - {@code $defs/satelliteNode} in the schema, used for the three block-valued trait fields
     * ({@code urbex:damaged.into}, {@code urbex:light.unlit}, {@code urbex:optional.replacement}) instead
     * of the plain {@code nodeOrBlock} every other position not needing that exclusion uses. Left here
     * rather than deleted so the next reader sees what was tried and rejected, and why it turned out not
     * to be necessary.
     */
    private static final Set<String> SHAPE_LEVEL_REJECTIONS = Set.of(
            "MODEL.002#1",  // version other than 1 or 2 - 'version' is fixed to the literal 2
            "MODEL.004#1",  // an unknown key ('damagd') inside a node
            "MODEL.012#1",  // a 'kind' outside the five defined values
            "MODEL.013#1",  // a kind-specific key ('choices') on the wrong kind ('block')
            "MODEL.033#1",  // a satellite ('urbex:light.unlit') declaring kind 'light_socket'
            "MODEL.045#1",  // a 'weighted' node with an empty 'choices'
            "MODEL.051#1",  // a 'tag' value with no leading '#'
            "MODEL.072#1",  // a 'light_socket' with no candidate in any placement list
            "REF.053#1",    // '$only' and '$without' together
            "REF.055#1",    // '$only' naming something that is not a key of a node
            "REF.056#1",    // '$only' on a node with no '$ref'
            "REF.082#1",    // '$imports' declaring the reserved alias 'super'
            "REF.022#1",    // an operand ('$ref') written on a trait value instead of 'into'
            "TRAIT.064#1",  // 'urbex:light' and 'urbex:optional' on one node
            "VER.010#1",    // a renamed version 1 key ('random') - unknown to this schema either way
            "VER.011#1",    // a deleted version 1 key ('torch') - unknown to this schema either way
            "WEIGHT.002#1", // a 'weight' of zero
            "WEIGHT.013#1"  // 'rest' declared beside a 'weight' choice in the same list
    );

    private static JsonNode readSchemaNode() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(SCHEMA_PATH.toFile());
    }

    private static JsonSchema loadSchema() throws IOException {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        return factory.getSchema(Files.readString(SCHEMA_PATH));
    }

    private static Set<String> propertyNames(JsonNode objectNode) {
        JsonNode properties = objectNode.get("properties");
        assertTrue(properties != null && properties.isObject(),
                "expected node to have a 'properties' object: " + objectNode);
        return StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(properties.fieldNames(), 0), false)
                .collect(Collectors.toSet());
    }

    private static Set<String> enumItems(JsonNode arraySchema) {
        JsonNode items = arraySchema.get("items");
        assertTrue(items != null, "expected an array schema with 'items': " + arraySchema);
        JsonNode values = items.get("enum");
        assertTrue(values != null && values.isArray(), "expected 'items' to declare an 'enum': " + items);
        Set<String> names = new LinkedHashSet<>();
        values.forEach(value -> names.add(value.asText()));
        return names;
    }

    // ------------------------------------------------------------------------------------------
    // Drift guards: every key set the schema declares, against the codec it describes.
    // ------------------------------------------------------------------------------------------

    /**
     * The top level is the two documents this schema says it describes, and nothing else.
     * <p>
     * <b>It used to be one closed object over the palette's five keys</b>, while the {@code description}
     * beside it said "a version 2 palette asset […] or a definitions asset". Measured rather than
     * argued: 30 of 30 shipped palettes validated and 13 of 13 shipped definitions assets were refused,
     * every one of them by {@code additionalProperties} on the node keys a definitions asset carries at
     * its top level ({@code REF.014}). {@link #everyShippedAssetValidatesAgainstTheSchema()} is the
     * check whose absence let that stand for a whole task.
     */
    @Test
    void theTopLevelIsExactlyTheTwoDocumentShapesTheSchemaClaimsToDescribe() throws IOException {
        JsonNode schema = readSchemaNode();
        JsonNode alternatives = schema.get("anyOf");
        assertTrue(alternatives != null && alternatives.isArray(),
                "expected the top level to be an 'anyOf' over the two document shapes");
        List<String> branches = new ArrayList<>();
        alternatives.forEach(branch -> branches.add(branch.path("$ref").asText()));
        assertEquals(List.of("#/$defs/paletteAsset", "#/$defs/definitionsAsset"), branches,
                "the top level should name exactly the palette and definitions branches");
        assertTrue(schema.get("properties") == null && schema.get("additionalProperties") == null,
                "the two shapes' key sets live in their own branches, not half at the top level");
    }

    /** {@code MODEL.001}: the five file-level keys of a palette asset, and no others. */
    @Rule("MODEL.001")
    @Test
    void schemaFileLevelPropertiesMatchPaletteV2DefinitionFileLevelKeys() throws IOException {
        JsonNode schema = readSchemaNode().at("/$defs/paletteAsset");
        assertFalse(schema.isMissingNode(), "expected a '$defs/paletteAsset' schema");
        assertEquals(PaletteV2Definition.FILE_LEVEL_KEYS, propertyNames(schema),
                "'$defs/paletteAsset' properties should exactly match"
                        + " PaletteV2Definition.FILE_LEVEL_KEYS");
        JsonNode additionalProperties = schema.get("additionalProperties");
        assertTrue(additionalProperties != null && additionalProperties.isBoolean()
                        && !additionalProperties.asBoolean(),
                "expected the file level to declare \"additionalProperties\": false");
        JsonNode required = schema.get("required");
        assertTrue(required != null && required.isArray()
                        && StreamSupport.stream(required.spliterator(), false)
                                .anyMatch(node -> "version".equals(node.asText())),
                "expected 'version' to be required at the file level");
    }

    /**
     * {@code REF.014}, {@code REF.018}, {@code REF.019}: a definitions asset's file-level keys, drift-
     * guarded against {@link DefinitionAssetDefinition#FILE_LEVEL_KEYS} exactly as the palette's are
     * against {@link PaletteV2Definition#FILE_LEVEL_KEYS}.
     * <p>
     * The codec constant had no schema and no guard, which is why the branch above could describe one
     * document and be tested against the other. The rest of the key universe is the node's, so this also
     * asserts what makes it so - the branch composes {@code $defs/node} rather than restating it, and
     * closes the union with {@code unevaluatedProperties} so that {@code REF.018}'s refusal of
     * {@code $defs} needs nothing said about {@code $defs}.
     */
    @Rule("REF.014")
    @Rule("REF.018")
    @Rule("REF.019")
    @Test
    void schemaDefinitionsAssetPropertiesMatchDefinitionAssetDefinitionFileLevelKeys()
            throws IOException {
        JsonNode schema = readSchemaNode().at("/$defs/definitionsAsset");
        assertFalse(schema.isMissingNode(), "expected a '$defs/definitionsAsset' schema");
        assertEquals(Set.copyOf(DefinitionAssetDefinition.FILE_LEVEL_KEYS), propertyNames(schema),
                "'$defs/definitionsAsset' properties should exactly match"
                        + " DefinitionAssetDefinition.FILE_LEVEL_KEYS");

        JsonNode composed = schema.get("allOf");
        assertTrue(composed != null && composed.isArray() && composed.size() == 1
                        && "#/$defs/node".equals(composed.get(0).path("$ref").asText()),
                "a definitions asset is a node with three keys around it, so its schema should compose"
                        + " '$defs/node' rather than restate it: " + composed);
        JsonNode unevaluated = schema.get("unevaluatedProperties");
        assertTrue(unevaluated != null && unevaluated.isBoolean() && !unevaluated.asBoolean(),
                "expected '$defs/definitionsAsset' to declare \"unevaluatedProperties\": false, which is"
                        + " what refuses '$defs' (REF.018) and 'palette' (REF.014) without naming them");
        assertEquals(2, schema.at("/properties/version/const").asInt(),
                "REF.019: a definitions asset declares version 2 and there is no other version of it");
    }

    /**
     * Every asset the mod ships, validated against the schema that claims to describe it.
     * <p>
     * <b>This is the test whose absence is the whole of the defect above.</b> Both key sets were
     * drift-guarded against their codecs, every fixture in the specification was run, and nothing ever
     * pointed the schema at the pack - so a schema that refused all thirteen shipped definitions assets
     * passed a suite of 84 assertions written specifically about it.
     * <p>
     * The counts are asserted, not just the validations: a walk that found no file would otherwise
     * report a clean run over nothing, which is the same vacuity one directory over. A palette that is
     * not version 2 is skipped rather than failed - {@code VER.004} keeps version 1 loadable and this
     * schema describes version 2 - and the skipped count is asserted zero today, so the day a version 1
     * palette reappears in the pack this test says so rather than quietly covering one fewer file.
     */
    @TestFactory
    Stream<DynamicTest> everyShippedAssetValidatesAgainstTheSchema() throws IOException {
        JsonSchema schema = loadSchema();
        List<Path> assets = shippedAssets();
        List<DynamicTest> tests = new ArrayList<>();
        int[] palettes = {0};
        int[] definitions = {0};
        int[] skipped = {0};

        for (Path asset : assets) {
            JsonNode document = toJackson(Files.readString(asset));
            boolean definitionsAsset = asset.getParent().getFileName().toString().equals("definitions");
            if (!definitionsAsset && document.path("version").asInt(1) != 2) {
                skipped[0]++;
                continue;
            }
            if (definitionsAsset) {
                definitions[0]++;
            } else {
                palettes[0]++;
            }
            String name = SpecDocuments.repoRoot().relativize(asset).toString();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                Set<ValidationMessage> messages =
                        schema.validate(workAroundHashMarkerBug(document));
                assertTrue(messages.isEmpty(),
                        () -> name + ": the shipped asset does not validate: " + messages);
            }));
        }

        tests.add(DynamicTest.dynamicTest("the walk found the pack", () -> {
            assertTrue(palettes[0] >= 30, () -> "expected the bundled palettes, found " + palettes[0]);
            assertTrue(definitions[0] >= 13,
                    () -> "expected the bundled definitions assets, found " + definitions[0]);
            assertEquals(0, skipped[0],
                    "no bundled palette is version 1 any more; a skipped one is a file this test"
                            + " silently stopped covering");
        }));
        return tests.stream();
    }

    /** Every {@code palettes/} and {@code definitions/} asset under {@code src/main/resources/data}. */
    private static List<Path> shippedAssets() throws IOException {
        Path data = SpecDocuments.repoRoot().resolve("src/main/resources/data");
        try (Stream<Path> walk = Files.walk(data)) {
            return walk.filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> {
                        Path parent = path.getParent();
                        String dir = parent == null ? "" : parent.getFileName().toString();
                        return dir.equals("palettes") || dir.equals("definitions");
                    })
                    .sorted()
                    .toList();
        }
    }

    /**
     * {@code MODEL.010}: a node appears in exactly five positions, and this is the one recursive
     * definition that stands for all of them. Its declared {@code properties} are the whole key
     * universe a node may carry, whatever its kind - {@link RawNode#ANY_KIND_KEYS} is that same union,
     * read off {@link Kind#allKindSpecificKeys()} and {@link RawNode#COMMON_KEYS}.
     */
    @Rule("MODEL.010")
    @Test
    void schemaNodePropertiesMatchRawNodeAnyKindKeys() throws IOException {
        JsonNode schema = readSchemaNode();
        JsonNode node = schema.at("/$defs/node");
        assertFalse(node.isMissingNode(), "expected a '$defs/node' schema");
        assertEquals(RawNode.ANY_KIND_KEYS, propertyNames(node),
                "'$defs/node' properties should exactly match RawNode.ANY_KIND_KEYS");
    }

    /**
     * {@code MODEL.046}: a choice is a node carrying additionally {@code share}/{@code weight}/
     * {@code rest} and {@code when} - {@link RawChoice#OWN_KEYS}, exactly, added on top of the node's
     * own properties and nothing else.
     */
    @Rule("MODEL.046")
    @Test
    void schemaChoicePropertiesAddExactlyRawChoiceOwnKeysToTheNode() throws IOException {
        JsonNode schema = readSchemaNode();
        JsonNode choice = schema.at("/$defs/choice");
        assertFalse(choice.isMissingNode(), "expected a '$defs/choice' schema");
        Set<String> ownKeys = new LinkedHashSet<>(propertyNames(choice));
        ownKeys.removeAll(RawNode.ANY_KIND_KEYS);
        assertEquals(RawChoice.OWN_KEYS, ownKeys,
                "'$defs/choice' should add exactly RawChoice.OWN_KEYS to the node's own properties");
    }

    /**
     * {@code REF.054}, {@code REF.055}: {@code $only} and {@code $without} name a node's top-level keys
     * only, and a name that is not one is refused - {@link RawNode#FILTERABLE_KEYS} is that set.
     */
    @Rule("REF.054")
    @Rule("REF.055")
    @Test
    void schemaOnlyAndWithoutEnumsMatchRawNodeFilterableKeys() throws IOException {
        JsonNode schema = readSchemaNode();
        JsonNode node = schema.at("/$defs/node/properties");
        assertEquals(RawNode.FILTERABLE_KEYS, enumItems(node.get("$only")),
                "'$only' items enum should exactly match RawNode.FILTERABLE_KEYS");
        assertEquals(RawNode.FILTERABLE_KEYS, enumItems(node.get("$without")),
                "'$without' items enum should exactly match RawNode.FILTERABLE_KEYS");
    }

    /** {@code WEIGHT.023}: {@code when}'s two keys, and no others - {@link When#KEYS}. */
    @Rule("WEIGHT.023")
    @Test
    void schemaWhenPropertiesMatchWhenKeys() throws IOException {
        JsonNode schema = readSchemaNode();
        JsonNode when = schema.at("/$defs/when");
        assertFalse(when.isMissingNode(), "expected a '$defs/when' schema");
        assertEquals(When.KEYS, propertyNames(when),
                "'$defs/when' properties should exactly match When.KEYS");
        JsonNode additionalProperties = when.get("additionalProperties");
        assertTrue(additionalProperties != null && additionalProperties.isBoolean()
                        && !additionalProperties.asBoolean(),
                "expected '$defs/when' to declare \"additionalProperties\": false");
    }

    /**
     * {@code TRAIT.090}: each registered trait's declared key set, one {@code $defs} entry per trait,
     * compared against {@link dev.krona.urbex.format.palette.TraitType#keys()} - the same source
     * {@link Trait#MAP_CODEC} checks a payload's keys against at decode. {@code urbex:rotatable} has no
     * keys at all ({@code TRAIT.001}'s scalar shorthand), so it is checked as a boolean schema instead
     * of a key set.
     */
    @Rule("TRAIT.090")
    @Test
    void schemaTraitPropertiesMatchEachRegisteredTraitsKeySet() throws IOException {
        JsonNode schema = readSchemaNode();
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("urbex:damaged", Damaged.TYPE.keys());
        expected.put("urbex:loot", Loot.TYPE.keys());
        expected.put("urbex:spawner", Spawner.TYPE.keys());
        expected.put("urbex:block_entity", BlockEntityNbt.TYPE.keys());
        expected.put("urbex:light", Light.TYPE.keys());
        expected.put("urbex:optional", OptionalTrait.TYPE.keys());

        Map<String, String> defsByTraitId = new LinkedHashMap<>();
        defsByTraitId.put("urbex:damaged", "traitDamaged");
        defsByTraitId.put("urbex:loot", "traitLoot");
        defsByTraitId.put("urbex:spawner", "traitSpawner");
        defsByTraitId.put("urbex:block_entity", "traitBlockEntity");
        defsByTraitId.put("urbex:light", "traitLight");
        defsByTraitId.put("urbex:optional", "traitOptional");

        JsonNode traitsProperties = schema.at("/$defs/traits/properties");
        assertEquals(defsByTraitId.keySet(), StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(traitsProperties.fieldNames(), 0), false)
                        .filter(id -> !"urbex:rotatable".equals(id))
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                "'$defs/traits/properties' should name exactly the six non-scalar registered traits"
                        + " (plus 'urbex:rotatable', checked separately)");

        for (Map.Entry<String, String> entry : defsByTraitId.entrySet()) {
            JsonNode traitSchema = schema.at("/$defs/" + entry.getValue());
            assertFalse(traitSchema.isMissingNode(), "expected a '$defs/" + entry.getValue() + "' schema");
            assertEquals(expected.get(entry.getKey()), propertyNames(traitSchema),
                    "'$defs/" + entry.getValue() + "' properties should exactly match "
                            + entry.getKey() + "'s TraitType.keys()");
        }

        assertTrue(Rotatable.TYPE.keys().isEmpty(),
                "this test's boolean-schema branch assumes urbex:rotatable has no keys");
        JsonNode rotatable = schema.at("/$defs/traitRotatable");
        assertEquals("boolean", rotatable.get("type").asText(),
                "'$defs/traitRotatable' should be a bare boolean schema, matching TRAIT.001's scalar"
                        + " shorthand");
    }

    /**
     * Every trait {@link Traits} registers has a schema entry above - the reverse direction of the
     * previous test, so that an eighth trait registered in code and never given a schema shape is
     * caught here rather than silently falling through {@code traits}'s open {@code additionalProperties}.
     */
    @Test
    void everyRegisteredTraitHasASchemaEntry() throws IOException {
        JsonNode schema = readSchemaNode();
        JsonNode traitsProperties = schema.at("/$defs/traits/properties");
        Set<String> declared = StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(traitsProperties.fieldNames(), 0), false)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> registered = Traits.ids().stream().map(Object::toString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(registered, declared,
                "'$defs/traits/properties' should name exactly the ids Traits registers");
    }

    // ------------------------------------------------------------------------------------------
    // Every specification fixture, validated at the strength this schema can claim.
    // ------------------------------------------------------------------------------------------

    /**
     * {@code accept} and {@code equiv} fixtures must validate; {@code reject} fixtures in
     * {@link #SHAPE_LEVEL_REJECTIONS} must not; every other {@code reject} fixture is asserted to
     * validate too, because this schema deliberately does not attempt it - see that field's javadoc.
     * A fixture whose top level is a <em>part</em> ({@code MERGE.009}'s, the one place a version 2
     * palette is written inline) is excluded: this schema describes a palette asset's own document, and
     * a part is a different document with a palette embedded in one of its fields, matched the same way
     * {@code FormatFixtureTest} tells the two apart.
     */
    @TestFactory
    Stream<DynamicTest> everySpecificationFixtureValidatesAsTheShapeLevelExpects() throws IOException {
        JsonSchema schema = loadSchema();
        List<SpecDocuments.Fixture> fixtures = SpecDocuments.load().fixtures();
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<DynamicTest> tests = new ArrayList<>();

        for (SpecDocuments.Fixture fixture : fixtures) {
            int ordinal = seen.merge(fixture.ruleId(), 1, Integer::sum);
            String address = fixture.ruleId() + "#" + ordinal;
            if (fixture.outcome() == SpecDocuments.Outcome.FRAGMENT) {
                continue;
            }
            if (isPartFixture(fixture.json())) {
                continue;
            }
            boolean expectValid = fixture.outcome() != SpecDocuments.Outcome.REJECT
                    || !SHAPE_LEVEL_REJECTIONS.contains(address);
            String name = address + " " + (expectValid ? "validates" : "is refused by the schema")
                    + " (" + fixture.file() + ":" + fixture.line() + ")";
            tests.add(DynamicTest.dynamicTest(name,
                    () -> assertValidates(schema, fixture, address, expectValid)));
        }
        return tests.stream();
    }

    private static void assertValidates(JsonSchema schema, SpecDocuments.Fixture fixture, String address,
                                        boolean expectValid) {
        JsonNode document = workAroundHashMarkerBug(toJackson(fixture.json()));
        Set<ValidationMessage> messages = schema.validate(document);
        if (expectValid) {
            assertTrue(messages.isEmpty(), () -> address + " (" + fixture.file() + ":" + fixture.line()
                    + "): expected the schema to accept this fixture, but it reported: " + messages);
        } else {
            assertFalse(messages.isEmpty(), () -> address + " (" + fixture.file() + ":" + fixture.line()
                    + "): expected the schema to refuse this fixture as a shape-level violation, but it"
                    + " accepted it");
        }
    }

    /** Matches {@code FormatFixtureTest}'s own test: a part fixture has 'slices' and no palette does. */
    private static boolean isPartFixture(String json) {
        JsonNode node = toJackson(json);
        return node.isObject() && node.has("slices");
    }

    /**
     * Works around a blind spot in {@code com.networknt:json-schema-validator} 1.5.6: an object key that
     * is literally {@code "#"} makes it report every validation of that subtree as passing, whatever the
     * schema says. Filed upstream and tracked as
     * <a href="https://github.com/Arilas/urbex/issues/218">issue #218</a>; this method is the workaround
     * that issue is about, and it goes when the library version that fixes it lands.
     * <p>
     * Reproduced directly: {@code {"palette":{"#":{"kind":"weighted","choices":[]}}}} validates cleanly
     * against this schema, and the same document with any other marker in {@code "#"}'s place correctly
     * reports {@code MODEL.045}'s violation. This is not an edge case this test can shrug off - {@code #}
     * is the single most common marker in the shipped corpus, and it is what {@code MODEL.013}'s,
     * {@code MODEL.045}'s, {@code VER.010}'s, {@code WEIGHT.002}'s and {@code WEIGHT.013}'s own fixtures
     * use, five of {@link #SHAPE_LEVEL_REJECTIONS}' seventeen. Renaming the key before validation, to a
     * marker no sibling in the same object already uses, tests the fixture's actual content instead of
     * the library's blind spot: nothing about any of those five rules depends on which character names
     * the marker being checked.
     */
    private static JsonNode workAroundHashMarkerBug(JsonNode document) {
        if (!document.isObject()) {
            return document;
        }
        com.fasterxml.jackson.databind.node.ObjectNode root = ((com.fasterxml.jackson.databind.node.ObjectNode) document).deepCopy();
        renameHashKey(root, "palette");
        renameHashKey(root, "$defs");
        return root;
    }

    private static void renameHashKey(com.fasterxml.jackson.databind.node.ObjectNode root, String field) {
        JsonNode container = root.get(field);
        if (container == null || !container.isObject()) {
            return;
        }
        com.fasterxml.jackson.databind.node.ObjectNode object = (com.fasterxml.jackson.databind.node.ObjectNode) container;
        if (!object.has("#")) {
            return;
        }
        String replacement = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".chars().mapToObj(c -> String.valueOf((char) c))
                .filter(candidate -> !object.has(candidate))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "every single-letter replacement for '#' is already a sibling key in " + object));
        com.fasterxml.jackson.databind.node.ObjectNode renamed =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            renamed.set("#".equals(entry.getKey()) ? replacement : entry.getKey(), entry.getValue());
        }
        root.set(field, renamed);
    }

    /**
     * Parses through Gson first, matching {@code FormatFixtureTest}'s own parser, then re-serialises for
     * Jackson - the two Gson and Jackson JSON parsers agree on every fixture in this specification, and
     * routing through Gson keeps this test reading fixtures exactly as the rest of the format harness
     * does rather than trusting a second parser to agree with the first on edge cases neither hits here.
     */
    private static JsonNode toJackson(String json) {
        try {
            return new ObjectMapper().readTree(JsonParser.parseString(json).toString());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Every address named in {@link #SHAPE_LEVEL_REJECTIONS} names a real {@code reject} fixture, so the
     * set cannot go stale under a renumbered rule or a rewritten fixture the way {@code FormatFixtureTest.PENDING}
     * is guarded against the same failure mode.
     */
    @Test
    void everyShapeLevelRejectionAddressNamesARealRejectFixture() {
        List<SpecDocuments.Fixture> fixtures = SpecDocuments.load().fixtures();
        Map<String, Integer> seen = new LinkedHashMap<>();
        Set<String> rejectAddresses = new LinkedHashSet<>();
        for (SpecDocuments.Fixture fixture : fixtures) {
            int ordinal = seen.merge(fixture.ruleId(), 1, Integer::sum);
            if (fixture.outcome() == SpecDocuments.Outcome.REJECT) {
                rejectAddresses.add(fixture.ruleId() + "#" + ordinal);
            }
        }
        List<String> stale = SHAPE_LEVEL_REJECTIONS.stream()
                .filter(address -> !rejectAddresses.contains(address))
                .toList();
        assertTrue(stale.isEmpty(), () -> "SHAPE_LEVEL_REJECTIONS names addresses that are not reject"
                + " fixtures, or no longer exist: " + stale);
    }

    // ------------------------------------------------------------------------------------------
    // MODEL.081 completeness: required at four positions, deliberately not at a fifth.
    // ------------------------------------------------------------------------------------------

    /**
     * {@code MODEL.081}: a node in a marker position must have its kind's required key - {@code block}
     * for {@code block} (or no {@code kind} at all, {@code MODEL.011}'s default), {@code choices} for
     * {@code weighted}, {@code tag} for {@code tag}, {@code of} for {@code alias} - unless a {@code $ref}
     * is present to supply it once resolved, which is {@code REF.021}'s problem and not this schema's.
     * {@code MODEL.082}/{@code REF.020} draw the opposite conclusion for a {@code $defs} entry, which may
     * stay partial forever.
     * <p>
     * No fixture in the specification probes this shape - every {@code REJECT} fixture that touches
     * completeness ({@code MODEL.081}'s own) does it through a {@code $ref} to an incomplete
     * <em>referenced</em> node, which this schema correctly does not attempt (it is REF.021's territory,
     * listed in {@link #SHAPE_LEVEL_REJECTIONS}'s javadoc). This test is what stands in for that missing
     * fixture: eleven shapes, each run through {@link #workAroundHashMarkerBug} the same way
     * {@link #everySpecificationFixtureValidatesAsTheShapeLevelExpects()} does, so a constraint that only
     * held up on an unrenamed marker could not pass here unnoticed.
     */
    @Rule("MODEL.081")
    @Test
    void aMarkerPositionRequiresItsKindsOwnKeyUnlessAReferenceMightSupplyIt() throws IOException {
        JsonSchema schema = loadSchema();
        Map<String, Boolean> cases = new LinkedHashMap<>();

        // Incomplete at a marker, nothing to defer to: refused.
        cases.put("{\"version\":2,\"palette\":{\"X\":{}}}", false);
        cases.put("{\"version\":2,\"palette\":{\"X\":{\"kind\":\"weighted\"}}}", false);
        cases.put("{\"version\":2,\"palette\":{\"X\":{\"kind\":\"tag\"}}}", false);
        cases.put("{\"version\":2,\"palette\":{\"X\":{\"kind\":\"alias\"}}}", false);

        // The same four kinds, complete: accepted.
        cases.put("{\"version\":2,\"palette\":{\"X\":\"minecraft:stone\"}}", true);
        cases.put("{\"version\":2,\"palette\":{\"X\":{\"block\":\"minecraft:stone\"}}}", true);
        cases.put("{\"version\":2,\"palette\":{\"X\":{\"kind\":\"weighted\",\"choices\":"
                + "[{\"weight\":1,\"block\":\"minecraft:stone\"}]}}}", true);
        cases.put("{\"version\":2,\"palette\":{\"X\":{\"kind\":\"tag\",\"tag\":\"#minecraft:planks\"}}}",
                true);
        cases.put("{\"version\":2,\"palette\":{\"X\":{\"kind\":\"alias\",\"of\":\"Y\"}}}", true);

        // The same incompleteness, deferred to a '$ref': accepted, because whether 'rubble' actually
        // supplies the missing key is a fact only the loader has (REF.021).
        cases.put("{\"version\":2,\"palette\":{\"X\":{\"$ref\":\"rubble\"}}}", true);
        cases.put("{\"version\":2,\"palette\":{\"X\":{\"kind\":\"weighted\",\"$ref\":\"rubble\"}}}", true);

        // The same incompleteness, in '$defs' instead of 'palette': accepted, on MODEL.082's own words.
        cases.put("{\"version\":2,\"$defs\":{\"rubble\":{}}}", true);
        cases.put("{\"version\":2,\"$defs\":{\"rubble\":{\"kind\":\"weighted\"}}}", true);

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Boolean> testCase : cases.entrySet()) {
            boolean expectValid = testCase.getValue();
            Set<ValidationMessage> messages =
                    schema.validate(workAroundHashMarkerBug(toJackson(testCase.getKey())));
            boolean actuallyValid = messages.isEmpty();
            if (actuallyValid != expectValid) {
                failures.add(testCase.getKey() + ": expected " + (expectValid ? "valid" : "refused")
                        + ", got " + (actuallyValid ? "valid" : messages.toString()));
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /**
     * The reviewer's own caution on this fix: {@link #workAroundHashMarkerBug} only renames a key, and
     * must never end up papering over a real defect along the way it touches. Proven directly rather than
     * assumed - an incomplete {@code weighted} node under marker {@code "#"} is still refused after the
     * rename, through the same path every fixture in
     * {@link #everySpecificationFixtureValidatesAsTheShapeLevelExpects()} runs.
     */
    @Rule("MODEL.081")
    @Test
    void theHashMarkerWorkaroundDoesNotHideAnIncompleteNode() throws IOException {
        JsonSchema schema = loadSchema();
        JsonNode document = workAroundHashMarkerBug(
                toJackson("{\"version\":2,\"palette\":{\"#\":{\"kind\":\"weighted\"}}}"));
        Set<ValidationMessage> messages = schema.validate(document);
        assertFalse(messages.isEmpty(),
                "expected an incomplete 'weighted' node under marker '#' to be refused after the rename,"
                        + " not silently accepted");
    }
}
