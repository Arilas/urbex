package dev.krona.urbex.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.PresetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.BuildingSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.CitySettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.TerrainSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.UnknownKeys;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless: PresetDefinition and its sections hold only primitives/strings/lists, so decoding needs no MC
 * bootstrap.
 */
class PresetCodecTest {

    private static PresetDefinition decode(String json) {
        JsonElement element = JsonParser.parseString(json);
        return PresetDefinition.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
    }

    @Test
    void minimalFileParses() {
        PresetDefinition re = decode("{\"description\":\"x\",\"cities\":{\"cityChance\":0.001}}");

        assertEquals("x", re.description().orElseThrow());
        assertTrue(re.cities().isPresent());
        assertEquals(0.001, re.cities().get().cityChance().orElseThrow());

        assertTrue(re.getExtends().isEmpty());
        assertTrue(re.extraDescription().isEmpty());
        assertTrue(re.warning().isEmpty());
        assertTrue(re.icon().isEmpty());
        assertTrue(re.terrain().isEmpty());
        assertTrue(re.buildings().isEmpty());
        assertTrue(re.roads().isEmpty());
        assertTrue(re.highways().isEmpty());
        assertTrue(re.railways().isEmpty());
        assertTrue(re.destruction().isEmpty());
        assertTrue(re.decoration().isEmpty());
        assertTrue(re.spawn().isEmpty());
        assertTrue(re.misc().isEmpty());
    }

    @Test
    void unknownTopLevelKeyParsesButWarns() {
        String json = "{\"citiez\":{}}";

        assertDoesNotThrow(() -> decode(json));

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Dynamic<JsonElement> dyn = new Dynamic<>(JsonOps.INSTANCE, root);
        assertEquals(List.of("citiez"), UnknownKeys.check(dyn, PresetDefinition.KEYS));
    }

    /**
     * The removed {@code atmosphere} section is not a hard error. It never had a reader (issue #73),
     * so a pack that still declares it generates exactly what it did before - it is reported through
     * the ordinary unknown-key WARN, and the file still loads.
     */
    @Test
    void removedAtmosphereSectionParsesButWarns() {
        String json = "{\"description\":\"x\",\"atmosphere\":{\"horizon\":128,\"fogDensity\":0.02}}";

        PresetDefinition re = assertDoesNotThrow(() -> decode(json));
        assertEquals("x", re.description().orElseThrow());

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Dynamic<JsonElement> dyn = new Dynamic<>(JsonOps.INSTANCE, root);
        assertEquals(List.of("atmosphere"), UnknownKeys.check(dyn, PresetDefinition.KEYS));
    }

    @Test
    void unknownSectionKeyParsesButWarns() {
        String json = "{\"cities\":{\"cityChanse\":0.1}}";

        PresetDefinition re = decode(json);
        assertTrue(re.cities().isPresent());

        JsonObject citiesObj = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("cities");
        Dynamic<JsonElement> dyn = new Dynamic<>(JsonOps.INSTANCE, citiesObj);
        assertEquals(List.of("cityChanse"), UnknownKeys.check(dyn, CitySettings.KEYS));
    }

    @Test
    void underscoreKeysAreSilentlyAllowed() {
        String json = "{\"_comment\":\"x\",\"cities\":{\"_note\":\"y\"}}";

        PresetDefinition re = decode(json);
        assertTrue(re.cities().isPresent());

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Dynamic<JsonElement> rootDyn = new Dynamic<>(JsonOps.INSTANCE, root);
        assertEquals(List.of(), UnknownKeys.check(rootDyn, PresetDefinition.KEYS));

        JsonObject citiesObj = root.getAsJsonObject("cities");
        Dynamic<JsonElement> citiesDyn = new Dynamic<>(JsonOps.INSTANCE, citiesObj);
        assertEquals(List.of(), UnknownKeys.check(citiesDyn, CitySettings.KEYS));
    }

    @Test
    void enumValuesParse() {
        PresetDefinition re = decode("{\"terrain\":{\"landscapeType\":\"cavern\"},"
                + "\"buildings\":{\"multiBuildingStreetConflict\":\"override_all\"}}");

        TerrainSettings terrain = re.terrain().orElseThrow();
        assertEquals(LandscapeType.CAVERN, terrain.landscapeType().orElseThrow());

        BuildingSettings buildings = re.buildings().orElseThrow();
        assertEquals(MultiBuildingStreetConflict.OVERRIDE_ALL, buildings.multiBuildingStreetConflict().orElseThrow());
    }
}
