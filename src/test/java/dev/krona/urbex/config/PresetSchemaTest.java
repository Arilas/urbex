package dev.krona.urbex.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.krona.urbex.worldgen.lost.regassets.PresetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.BuildingSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.CitySettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.DecorationSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.DestructionSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.HighwaySettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.MiscSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.RailwaySettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.RoadSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.SpawnSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.TerrainSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift-guards {@code docs/schema/preset.schema.json} (hand-written, since the section records are
 * plain DFU codecs with no schema-generation library wired up) against the same two sources of
 * truth {@link PresetRoundTripTest} pins the codecs to: each section's {@code KEYS} constant, and
 * the full key universe {@code toDefinition()} actually encodes. A field added to a section record without
 * a matching schema edit (or vice versa) fails here instead of silently going undocumented.
 */
class PresetSchemaTest {

    private static final Path SCHEMA_PATH = Path.of("docs/schema/preset.schema.json");
    private static final Path PRESETS_DIR = Path.of("src/main/resources/data/urbex/urbex/presets");

    private static final Map<String, Set<String>> EXPECTED_SECTION_KEYS = new LinkedHashMap<>();

    static {
        EXPECTED_SECTION_KEYS.put("terrain", TerrainSettings.KEYS);
        EXPECTED_SECTION_KEYS.put("cities", CitySettings.KEYS);
        EXPECTED_SECTION_KEYS.put("buildings", BuildingSettings.KEYS);
        EXPECTED_SECTION_KEYS.put("roads", RoadSettings.KEYS);
        EXPECTED_SECTION_KEYS.put("highways", HighwaySettings.KEYS);
        EXPECTED_SECTION_KEYS.put("railways", RailwaySettings.KEYS);
        EXPECTED_SECTION_KEYS.put("destruction", DestructionSettings.KEYS);
        EXPECTED_SECTION_KEYS.put("decoration", DecorationSettings.KEYS);
        EXPECTED_SECTION_KEYS.put("spawn", SpawnSettings.KEYS);
        EXPECTED_SECTION_KEYS.put("misc", MiscSettings.KEYS);
    }

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

    private static void assertClosedAndBlessesUnderscoreKeys(JsonNode objectNode, String where) {
        JsonNode additionalProperties = objectNode.get("additionalProperties");
        assertTrue(additionalProperties != null && additionalProperties.isBoolean() && !additionalProperties.asBoolean(),
                where + ": expected \"additionalProperties\": false");
        JsonNode patternProperties = objectNode.get("patternProperties");
        assertTrue(patternProperties != null && patternProperties.has("^_"),
                where + ": expected a \"^_\" patternProperties escape for pack metadata");
    }

    @Test
    void schemaCoversExactlyTheCodecKeys() throws IOException {
        JsonNode schema = readSchemaNode();

        assertEquals(PresetDefinition.KEYS, propertyNames(schema),
                "top-level schema properties should exactly match PresetDefinition.KEYS");
        assertClosedAndBlessesUnderscoreKeys(schema, "root");

        JsonNode properties = schema.get("properties");
        for (Map.Entry<String, Set<String>> entry : EXPECTED_SECTION_KEYS.entrySet()) {
            String section = entry.getKey();
            JsonNode sectionNode = properties.get(section);
            assertTrue(sectionNode != null && sectionNode.isObject(), "missing schema section: " + section);
            assertEquals(entry.getValue(), propertyNames(sectionNode),
                    "schema properties for section '" + section + "' should exactly match its KEYS constant");
            assertClosedAndBlessesUnderscoreKeys(sectionNode, "section '" + section + "'");
        }
    }

    @Test
    void everyShippedPresetValidatesAgainstSchema() throws IOException {
        JsonSchema schema = loadSchema();
        ObjectMapper mapper = new ObjectMapper();
        List<String> failures = new ArrayList<>();

        List<Path> files;
        try (var stream = Files.list(PRESETS_DIR)) {
            files = stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "No shipped preset files found under " + PRESETS_DIR);

        for (Path file : files) {
            JsonNode node = mapper.readTree(file.toFile());
            Set<ValidationMessage> messages = schema.validate(node);
            if (!messages.isEmpty()) {
                failures.add(file + ": " + messages);
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /**
     * The schema must not green-light JSON the game refuses to load.
     * <p>
     * It did: the {@code extends} pattern made the namespace group optional, so {@code "default"}
     * validated here while {@code DataTools.STRICT_IDENTIFIER_CODEC} rejected it, and the other four
     * reference fields carried no pattern at all. {@link #schemaCoversExactlyTheCodecKeys} compares
     * key <em>names</em>, so nothing failed. These are the five fields
     * {@code DatapackReferenceIntegrityTest} requires to be qualified in the shipped pack.
     */
    @Test
    void schemaRequiresANamespaceOnEveryAssetReference() throws IOException {
        JsonSchema schema = loadSchema();
        ObjectMapper mapper = new ObjectMapper();

        Map<String, String> bare = new LinkedHashMap<>();
        bare.put("extends", "{\"extends\":\"default\"}");
        bare.put("cities.cityStyleAlternative", "{\"cities\":{\"cityStyleAlternative\":\"citystyle_border\"}}");
        bare.put("spawn.spawnCity", "{\"spawn\":{\"spawnCity\":\"city1\"}}");
        bare.put("spawn.forceSpawnBuildings", "{\"spawn\":{\"forceSpawnBuildings\":[\"building1\"]}}");
        bare.put("spawn.forceSpawnParts", "{\"spawn\":{\"forceSpawnParts\":[\"part1\"]}}");

        Map<String, String> qualified = new LinkedHashMap<>();
        qualified.put("extends", "{\"extends\":\"urbex:default\"}");
        qualified.put("cities.cityStyleAlternative", "{\"cities\":{\"cityStyleAlternative\":\"urbex:citystyle_border\"}}");
        qualified.put("spawn.spawnCity", "{\"spawn\":{\"spawnCity\":\"urbex:city1\"}}");
        qualified.put("spawn.forceSpawnBuildings", "{\"spawn\":{\"forceSpawnBuildings\":[\"urbex:building1\"]}}");
        qualified.put("spawn.forceSpawnParts", "{\"spawn\":{\"forceSpawnParts\":[\"urbex:part1\"]}}");

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> e : bare.entrySet()) {
            if (schema.validate(mapper.readTree(e.getValue())).isEmpty()) {
                failures.add(e.getKey() + ": schema accepted an unqualified reference: " + e.getValue());
            }
        }
        for (Map.Entry<String, String> e : qualified.entrySet()) {
            Set<ValidationMessage> messages = schema.validate(mapper.readTree(e.getValue()));
            if (!messages.isEmpty()) {
                failures.add(e.getKey() + ": schema rejected a qualified reference: " + e.getValue() + " -> " + messages);
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    @Test
    void schemaRejectsUnknownKey() throws IOException {
        JsonSchema schema = loadSchema();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree("{\"cities\":{\"cityChanse\":1}}");

        Set<ValidationMessage> messages = schema.validate(node);
        assertFalse(messages.isEmpty(), "expected the typo'd key 'cityChanse' to fail validation");
    }
}
