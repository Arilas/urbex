package dev.krona.urbex.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShippedPresetsTest {

    private static final Path PRESETS_DIR = Path.of("src/main/resources/data/urbex/urbex/presets");
    private static final Path TAG_FILE = Path.of("src/main/resources/data/urbex/tags/urbex/presets/presets.json");

    private Map<Identifier, PresetRE> loadShippedPresets() throws Exception {
        Map<Identifier, PresetRE> presets = new HashMap<>();

        try (var stream = Files.list(PRESETS_DIR)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            String filename = p.getFileName().toString();
                            String presetName = filename.substring(0, filename.length() - 5); // remove .json
                            Identifier id = Identifier.fromNamespaceAndPath("urbex", presetName);

                            String json = Files.readString(p);
                            JsonElement element = JsonParser.parseString(json);
                            PresetRE preset = PresetRE.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
                            presets.put(id, preset);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }

        return presets;
    }

    @Test
    void allShippedPresetsParseAndResolve() throws Exception {
        Map<Identifier, PresetRE> presets = loadShippedPresets();
        assertFalse(presets.isEmpty(), "No preset files found");

        for (Identifier id : presets.keySet()) {
            assertDoesNotThrow(() -> Presets.resolve(id, presets::get),
                    "Failed to resolve preset: " + id);
        }
    }

    @Test
    void everyShippedPresetHasExplicitLightingDensity() throws Exception {
        Map<Identifier, PresetRE> presets = loadShippedPresets();
        assertFalse(presets.isEmpty(), "No preset files found");

        for (var entry : presets.entrySet()) {
            Identifier id = entry.getKey();
            PresetRE preset = entry.getValue();
            assertTrue(preset.decoration().isPresent() && preset.decoration().get().lightingDensity().isPresent(),
                    "Preset " + id + " does not have explicit decoration.lightingDensity");
        }
    }

    @Test
    void avgHeightmapOnEverywhere() throws Exception {
        Map<Identifier, PresetRE> presets = loadShippedPresets();
        assertFalse(presets.isEmpty(), "No preset files found");

        for (var entry : presets.entrySet()) {
            Identifier id = entry.getKey();
            Preset resolved = Presets.resolve(id, presets::get);
            assertTrue(resolved.USE_AVG_HEIGHTMAP,
                    "Preset " + id + " does not have USE_AVG_HEIGHTMAP=true after resolution");
        }
    }

    @Test
    void nonDefaultPresetsParentDefault() throws Exception {
        Map<Identifier, PresetRE> presets = loadShippedPresets();
        assertFalse(presets.isEmpty(), "No preset files found");

        for (var entry : presets.entrySet()) {
            Identifier id = entry.getKey();
            PresetRE preset = entry.getValue();

            if (id.getPath().equals("default")) {
                // Default preset should not have a parent
                assertTrue(preset.parent().isEmpty(),
                        "Default preset should not have a parent");
            } else {
                // All other presets should have urbex:default as parent
                assertTrue(preset.parent().isPresent(),
                        "Non-default preset " + id + " should have a parent");
                assertEquals(Identifier.fromNamespaceAndPath("urbex", "default"), preset.parent().get(),
                        "Preset " + id + " should have urbex:default as parent");
            }
        }
    }

    @Test
    void tagListsExactlyTheShippedPresets() throws Exception {
        Map<Identifier, PresetRE> presets = loadShippedPresets();
        assertFalse(presets.isEmpty(), "No preset files found");

        String tagJson = Files.readString(TAG_FILE);
        JsonElement tagElement = JsonParser.parseString(tagJson);
        var tagValues = tagElement.getAsJsonObject().getAsJsonArray("values").asList().stream()
                .map(e -> Identifier.tryParse(e.getAsString()))
                .collect(Collectors.toSet());

        Set<Identifier> presetIds = presets.keySet();
        assertEquals(presetIds, tagValues,
                "Tag values do not match shipped presets");
    }
}
