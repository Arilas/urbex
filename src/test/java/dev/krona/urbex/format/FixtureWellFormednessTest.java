package dev.krona.urbex.format;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the one thing a fixture can be wrong about before any codec exists to run it against: its
 * own shape. {@code docs/format/README.md} §4.2 gives the full completeness contract to
 * {@code FormatFixtureTest}, once a decoder exists for it to drive - this class covers the two checks
 * that need no decoder at all, ported from what {@code conformance.py} did inline while parsing.
 * <p>
 * Both checks exist because of the same failure mode: a fixture hand-edited into invalidity. It
 * happened four times while this specification was being written - a stray comma, a copy-pasted key
 * that belonged to a different node - and each time nothing caught it until a human read the diff.
 */
class FixtureWellFormednessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyFixtureIsWellFormedJson() {
        SpecDocuments spec = SpecDocuments.load();
        List<String> failures = new ArrayList<>();
        for (SpecDocuments.Fixture fixture : spec.fixtures()) {
            try {
                MAPPER.readTree(fixture.json());
            } catch (JsonProcessingException e) {
                failures.add(fixture.file() + ":" + fixture.line() + ": fixture for " + fixture.ruleId()
                        + " is not valid JSON - " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /**
     * A palette file's top level may hold only the five keys {@code MODEL.001} names. An inline
     * palette (§{@code MERGE.011}) is a palette file too, just nested inside a part or building's own
     * JSON rather than standing alone - so this walks the whole fixture tree looking for any object
     * that declares {@code version}, the one key every palette file must carry, and checks only that
     * object's own keys. A fixture whose top level is something else entirely - a part, in
     * {@code MERGE.009}'s fixture - is correctly left unchecked at that level, because it is not
     * claiming to be a palette file there; its nested inline palette still is, and still gets checked.
     */
    @Test
    void everyFixtureUsesOnlyFileLevelKeysTheSpecificationDefines() {
        SpecDocuments spec = SpecDocuments.load();
        List<String> failures = new ArrayList<>();
        for (SpecDocuments.Fixture fixture : spec.fixtures()) {
            JsonNode root;
            try {
                root = MAPPER.readTree(fixture.json());
            } catch (JsonProcessingException e) {
                continue; // reported by everyFixtureIsWellFormedJson
            }
            checkFileLevelKeys(root, fixture, failures);
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    private static void checkFileLevelKeys(JsonNode node, SpecDocuments.Fixture fixture, List<String> failures) {
        if (node.isObject()) {
            if (node.has("version")) {
                List<String> extraKeys = new ArrayList<>();
                Iterator<String> names = node.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    if (!SpecDocuments.MODEL_001_FILE_LEVEL_KEYS.contains(name)) {
                        extraKeys.add(name);
                    }
                }
                if (!extraKeys.isEmpty()) {
                    failures.add(fixture.file() + ":" + fixture.line() + ": fixture for " + fixture.ruleId()
                            + " declares a palette file with key(s) MODEL.001 does not define: " + extraKeys);
                }
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                checkFileLevelKeys(it.next().getValue(), fixture, failures);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                checkFileLevelKeys(child, fixture, failures);
            }
        }
    }
}
