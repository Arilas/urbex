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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void nonDefaultPresetsExtendDefault() throws Exception {
        Map<Identifier, PresetRE> presets = loadShippedPresets();
        assertFalse(presets.isEmpty(), "No preset files found");

        for (var entry : presets.entrySet()) {
            Identifier id = entry.getKey();
            PresetRE preset = entry.getValue();

            if (id.getPath().equals("default")) {
                // Default preset should not extend anything
                assertTrue(preset.getExtends().isEmpty(),
                        "Default preset should not have an extends");
            } else {
                // All other presets should extend urbex:default
                assertTrue(preset.getExtends().isPresent(),
                        "Non-default preset " + id + " should have an extends");
                assertEquals(Identifier.fromNamespaceAndPath("urbex", "default"), preset.getExtends().get(),
                        "Preset " + id + " should have urbex:default as extends");
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

    /**
     * Every shipped preset declares its own {@code name}, and no two share one.
     * <p>
     * Both halves matter, and both are consequences of the field being inherited. A preset that
     * omits it is not blank - it silently wears {@code urbex:default}'s "Default", because eleven
     * of the twelve extend it - so the omission shows up as two identical rows in the Cities tab
     * rather than as anything the loader would complain about. Uniqueness is the check that
     * actually catches it, and stating it per file is what keeps it true.
     */
    @Test
    void everyShippedPresetDeclaresItsOwnUniqueName() throws Exception {
        Map<Identifier, PresetRE> presets = loadShippedPresets();
        assertFalse(presets.isEmpty(), "No preset files found");

        Map<String, Identifier> byName = new HashMap<>();
        for (var entry : presets.entrySet()) {
            Identifier id = entry.getKey();
            var name = entry.getValue().displayName();
            assertTrue(name.isPresent() && !name.get().isBlank(),
                    "Preset " + id + " declares no 'name', so the Cities tab shows the name it "
                            + "inherits from urbex:default instead of its own");
            Identifier clash = byName.put(name.get(), id);
            assertNull(clash, "Presets " + clash + " and " + id + " both call themselves '"
                    + name.get() + "'; the Cities tab shows the name alone, so they would be "
                    + "two identical rows");
        }
    }

    /**
     * Every shipped preset declares its own {@code description}, for the same reason it declares its
     * own {@code name}: the field is inherited, so omitting it is not blank but wrong.
     * <p>
     * {@code largecities} was exactly that - the only one of the twelve without one, so the Cities
     * tab printed {@code urbex:default}'s "Default generation, common cities, explosions" under the
     * heading "Large Cities". Nothing in the loader could have flagged it; it reads as a plausible
     * blurb until you notice it is the same blurb as the row above.
     */
    @Test
    void everyShippedPresetDeclaresItsOwnDescription() throws Exception {
        Map<Identifier, PresetRE> presets = loadShippedPresets();
        assertFalse(presets.isEmpty(), "No preset files found");

        for (var entry : presets.entrySet()) {
            var description = entry.getValue().description();
            assertTrue(description.isPresent() && !description.get().isBlank(),
                    "Preset " + entry.getKey() + " declares no 'description', so the Cities tab "
                            + "shows the one it inherits from urbex:default instead of its own");
        }
    }
}
