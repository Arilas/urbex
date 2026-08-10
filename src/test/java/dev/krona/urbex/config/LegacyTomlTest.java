package dev.krona.urbex.config;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The migration reader for the old Forge Config API Port TOML files. */
public class LegacyTomlTest {

    @Test
    public void readsScalars() {
        JsonObject json = LegacyToml.toJson(List.of(
                "#A comment",
                "[profiles]",
                "\tselectedProfile = \"biosphere\"",
                "\ttodoQueueSize = 50",
                "\tforceSaplingGrowth = false"
        ));
        assertEquals("biosphere", json.get("selectedProfile").getAsString());
        assertEquals(50, json.get("todoQueueSize").getAsInt());
        assertFalse(json.get("forceSaplingGrowth").getAsBoolean());
    }

    @Test
    public void readsSingleLineArrays() {
        JsonObject json = LegacyToml.toJson(List.of(
                "\tdimensionsWithProfiles = [\"urbex:city=biosphere\", \"foo:bar=rare\"]"
        ));
        assertEquals(2, json.get("dimensionsWithProfiles").getAsJsonArray().size());
        assertEquals("foo:bar=rare", json.get("dimensionsWithProfiles").getAsJsonArray().get(1).getAsString());
    }

    @Test
    public void readsMultiLineArrays() {
        JsonObject json = LegacyToml.toJson(List.of(
                "\tavoidStructures = [",
                "\t\t\"minecraft:mansion\",",
                "\t\t\"minecraft:igloo\"",
                "\t]"
        ));
        assertEquals(List.of("minecraft:mansion", "minecraft:igloo"),
                json.get("avoidStructures").getAsJsonArray().asList().stream().map(e -> e.getAsString()).toList());
    }

    @Test
    public void ignoresCommentsAndBlankLines() {
        JsonObject json = LegacyToml.toJson(List.of("", "#x = 1", "  ", "[profiles]"));
        assertTrue(json.isEmpty());
    }
}
