package dev.krona.urbex.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.AtmosphereSettings;
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
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code toReEncodesEveryKey} is the drift guard's engine: it pins each section's declared
 * {@code KEYS} constant to what {@code toRE()} actually encodes, so a field added to a section
 * record without updating {@code KEYS} (or vice versa) fails a test instead of silently warning
 * at runtime.
 */
class PresetRoundTripTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("urbex", "roundtrip");

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
        EXPECTED_SECTION_KEYS.put("atmosphere", AtmosphereSettings.KEYS);
        EXPECTED_SECTION_KEYS.put("misc", MiscSettings.KEYS);
    }

    @Test
    void toReEncodesEveryKey() {
        PresetRE re = new Preset(ID).toRE();

        JsonElement encoded = PresetRE.CODEC.encodeStart(JsonOps.INSTANCE, re).getOrThrow();
        JsonObject root = encoded.getAsJsonObject();

        for (Map.Entry<String, Set<String>> entry : EXPECTED_SECTION_KEYS.entrySet()) {
            String section = entry.getKey();
            assertTrue(root.has(section), "expected section '" + section + "' to be present");
            JsonObject sectionObj = root.getAsJsonObject(section);
            assertEquals(entry.getValue(), sectionObj.keySet(),
                    "key set for section '" + section + "' should exactly match its KEYS constant");
        }
    }

    @Test
    void roundTripPreservesValues() {
        Preset p = new Preset(ID);
        p.GROUNDLEVEL = 80;
        p.CITY_CHANCE = 0.55;
        p.BUILDING_CHANCE = 0.66f;
        p.PRIMARY_ROAD_SPACING_X = 20;
        p.HIGHWAY_DISTANCE_MASK = 15;
        p.RAILWAY_DUNGEON_CHANCE = 0.5f;
        p.RUIN_CHANCE = 0.77f;
        p.LIGHTING_DENSITY = 0.9f;
        p.SPAWN_CHECK_RADIUS = 500;
        p.HORIZON = 100f;
        p.EDITMODE = true;

        PresetRE re = p.toRE();
        JsonElement encoded = PresetRE.CODEC.encodeStart(JsonOps.INSTANCE, re).getOrThrow();
        PresetRE decoded = PresetRE.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        Preset resolved = Presets.resolve(ID, i -> i.equals(ID) ? decoded : null);

        assertEquals(80, resolved.GROUNDLEVEL);
        assertEquals(0.55, resolved.CITY_CHANCE);
        assertEquals(0.66f, resolved.BUILDING_CHANCE);
        assertEquals(20, resolved.PRIMARY_ROAD_SPACING_X);
        assertEquals(15, resolved.HIGHWAY_DISTANCE_MASK);
        assertEquals(0.5f, resolved.RAILWAY_DUNGEON_CHANCE);
        assertEquals(0.77f, resolved.RUIN_CHANCE);
        assertEquals(0.9f, resolved.LIGHTING_DENSITY);
        assertEquals(500, resolved.SPAWN_CHECK_RADIUS);
        assertEquals(100f, resolved.HORIZON);
        assertTrue(resolved.EDITMODE);
    }
}
