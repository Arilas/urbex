package dev.krona.urbex.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
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
 * Headless: PresetRE and its sections hold only primitives/strings/lists, so decoding needs no MC
 * bootstrap.
 */
class PresetCodecTest {

    private static PresetRE decode(String json) {
        JsonElement element = JsonParser.parseString(json);
        return PresetRE.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
    }

    @Test
    void minimalFileParses() {
        PresetRE re = decode("{\"description\":\"x\",\"cities\":{\"cityChance\":0.001}}");

        assertEquals("x", re.description().orElseThrow());
        assertTrue(re.cities().isPresent());
        assertEquals(0.001, re.cities().get().cityChance().orElseThrow());

        assertTrue(re.parent().isEmpty());
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
        assertTrue(re.atmosphere().isEmpty());
        assertTrue(re.misc().isEmpty());
    }

    @Test
    void unknownTopLevelKeyParsesButWarns() {
        String json = "{\"citiez\":{}}";

        assertDoesNotThrow(() -> decode(json));

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Dynamic<JsonElement> dyn = new Dynamic<>(JsonOps.INSTANCE, root);
        assertEquals(List.of("citiez"), UnknownKeys.check(dyn, PresetRE.KEYS));
    }

    @Test
    void unknownSectionKeyParsesButWarns() {
        String json = "{\"cities\":{\"cityChanse\":0.1}}";

        PresetRE re = decode(json);
        assertTrue(re.cities().isPresent());

        JsonObject citiesObj = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("cities");
        Dynamic<JsonElement> dyn = new Dynamic<>(JsonOps.INSTANCE, citiesObj);
        assertEquals(List.of("cityChanse"), UnknownKeys.check(dyn, CitySettings.KEYS));
    }

    @Test
    void underscoreKeysAreSilentlyAllowed() {
        String json = "{\"_comment\":\"x\",\"cities\":{\"_note\":\"y\"}}";

        PresetRE re = decode(json);
        assertTrue(re.cities().isPresent());

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Dynamic<JsonElement> rootDyn = new Dynamic<>(JsonOps.INSTANCE, root);
        assertEquals(List.of(), UnknownKeys.check(rootDyn, PresetRE.KEYS));

        JsonObject citiesObj = root.getAsJsonObject("cities");
        Dynamic<JsonElement> citiesDyn = new Dynamic<>(JsonOps.INSTANCE, citiesObj);
        assertEquals(List.of(), UnknownKeys.check(citiesDyn, CitySettings.KEYS));
    }

    @Test
    void enumValuesParse() {
        PresetRE re = decode("{\"terrain\":{\"landscapeType\":\"cavern\"},"
                + "\"buildings\":{\"multiBuildingStreetConflict\":\"override_all\"}}");

        TerrainSettings terrain = re.terrain().orElseThrow();
        assertEquals(LandscapeType.CAVERN, terrain.landscapeType().orElseThrow());

        BuildingSettings buildings = re.buildings().orElseThrow();
        assertEquals(MultiBuildingStreetConflict.OVERRIDE_ALL, buildings.multiBuildingStreetConflict().orElseThrow());
    }
}
