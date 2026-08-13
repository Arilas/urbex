package dev.krona.urbex.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
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
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code toReEncodesEveryKey} is the drift guard's engine: it pins each section's declared
 * {@code KEYS} constant to what {@code toDefinition()} actually encodes, so a field added to a section
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
        EXPECTED_SECTION_KEYS.put("misc", MiscSettings.KEYS);
    }

    @Test
    void toReEncodesEveryKey() {
        PresetDefinition re = new Preset(ID).toDefinition();

        JsonElement encoded = PresetDefinition.CODEC.encodeStart(JsonOps.INSTANCE, re).getOrThrow();
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
        PresetDraft draft = new PresetDraft(ID);
        draft.GROUNDLEVEL = 80;
        draft.CITY_CHANCE = 0.55;
        draft.BUILDING_CHANCE = 0.66f;
        draft.PRIMARY_ROAD_SPACING_X = 20;
        draft.HIGHWAY_DISTANCE_MASK = 15;
        draft.RAILWAY_DUNGEON_CHANCE = 0.5f;
        draft.RUIN_CHANCE = 0.77f;
        draft.LIGHTING_DENSITY = 0.9f;
        draft.SPAWN_CHECK_RADIUS = 500;
        draft.EDITMODE = true;

        PresetDefinition re = draft.resolve().toDefinition();
        JsonElement encoded = PresetDefinition.CODEC.encodeStart(JsonOps.INSTANCE, re).getOrThrow();
        PresetDefinition decoded = PresetDefinition.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        Preset resolved = Presets.resolve(ID, i -> i.equals(ID) ? decoded : null);

        assertEquals(80, resolved.groundLevel());
        assertEquals(0.55, resolved.cityChance());
        assertEquals(0.66f, resolved.buildingChance());
        assertEquals(20, resolved.primaryRoadSpacingX());
        assertEquals(15, resolved.highwayDistanceMask());
        assertEquals(0.5f, resolved.railwayDungeonChance());
        assertEquals(0.77f, resolved.ruinChance());
        assertEquals(0.9f, resolved.lightingDensity());
        assertEquals(500, resolved.spawnCheckRadius());
        assertTrue(resolved.editMode());
    }
}
