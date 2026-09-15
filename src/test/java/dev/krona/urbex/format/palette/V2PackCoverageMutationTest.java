package dev.krona.urbex.format.palette;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Issue #217: a coverage assertion must fail when its named construct disappears.
 * Each mutation still compiles; only the existing pack assertions may kill it. All edits happen to
 * independent in-memory copies, so this needs neither source rewrites nor one Gradle run per mutation.
 */
class V2PackCoverageMutationTest {

    @BeforeAll
    static void bootstrap() {
        V2PackGoldenTest.bootstrap();
    }

    @TestFactory
    Stream<DynamicTest> removingEachConstructFailsThePackCoverageAssertions() {
        Map<String, Consumer<JsonObject>> mutations = new LinkedHashMap<>();
        mutations.put("plain block becomes weighted", pack -> replace(pack, "s", """
                { "kind": "weighted", "choices": [
                  { "weight": 1, "block": "minecraft:stone_bricks" },
                  { "weight": 1, "block": "minecraft:mossy_stone_bricks" } ] }
                """));
        mutations.put("spread becomes one choice", pack -> marker(pack, "w")
                .getAsJsonArray("choices").set(1, JsonParser.parseString("""
                        { "weight": 1, "block": "minecraft:cobblestone" }
                        """)));
        mutations.put("nested weighted becomes one choice", pack -> marker(pack, "n")
                .getAsJsonArray("choices").set(1, JsonParser.parseString("""
                        { "weight": 1, "block": "minecraft:diorite" }
                        """)));
        mutations.put("tag becomes literal block", pack -> replace(pack, "p", "\"minecraft:oak_planks\""));
        mutations.put("alias becomes literal block", pack -> replace(pack, "@", "\"minecraft:cobblestone\""));
        mutations.put("when is removed", pack -> marker(pack, "e")
                .getAsJsonArray("choices").get(1).getAsJsonObject().remove("when"));
        mutations.put("unlit satellite is removed", pack -> marker(pack, "L")
                .getAsJsonObject("traits").getAsJsonObject("urbex:light").remove("unlit"));
        mutations.put("socket becomes literal light", pack -> replace(pack, "T", """
                { "block": "minecraft:lantern", "traits": { "urbex:light": {} } }
                """));
        mutations.put("damage definition reference is removed", pack -> marker(pack, "d").remove("$ref"));
        mutations.put("optional trait is removed", pack -> removeTrait(pack, "o", "urbex:optional"));
        mutations.put("rotation opt-out is removed", pack -> removeTrait(pack, "F", "urbex:rotatable"));
        mutations.put("chest loot trait is removed", pack -> removeTrait(pack, "C", "urbex:loot"));
        mutations.put("chest NBT trait is removed", pack -> removeTrait(pack, "C", "urbex:block_entity"));
        mutations.put("spawner pool trait is removed", pack -> removeTrait(pack, "S", "urbex:spawner"));
        mutations.put("spawner NBT trait is removed", pack -> removeTrait(pack, "S", "urbex:block_entity"));
        mutations.put("per-slot light trait is removed", pack -> marker(pack, "m")
                .getAsJsonArray("choices").get(1).getAsJsonObject().remove("traits"));

        return mutations.entrySet().stream().map(mutation -> DynamicTest.dynamicTest(
                mutation.getKey(), () -> {
                    JsonObject document = V2PackGoldenTest.document();
                    mutation.getValue().accept(document);
                    // Outside assertThrows: invalid input is not evidence that a coverage assertion works.
                    CompiledV2Palette compiled = V2PackGoldenTest.compiled(document);
                    assertThrows(AssertionError.class, () -> {
                        V2PackGoldenTest.assertConstructs(compiled);
                        V2PackGoldenTest.assertMetadataTraits(V2PackGoldenTest.merged(compiled));
                        V2PackGoldenTest.assertPerSlotTraits(V2PackGoldenTest.merged(compiled));
                    }, "the pack's coverage assertions survived: " + mutation.getKey());
                }));
    }

    private static JsonObject marker(JsonObject pack, String marker) {
        return pack.getAsJsonObject("palette").getAsJsonObject(marker);
    }

    private static void replace(JsonObject pack, String marker, String replacement) {
        pack.getAsJsonObject("palette").add(marker, JsonParser.parseString(replacement));
    }

    private static void removeTrait(JsonObject pack, String marker, String trait) {
        marker(pack, marker).getAsJsonObject("traits").remove(trait);
    }
}
