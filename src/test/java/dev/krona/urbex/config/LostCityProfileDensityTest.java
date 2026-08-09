package dev.krona.urbex.config;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LostCityProfileDensityTest {

    @Test
    void migratesLegacyLightingBoolean() {
        LostCityProfile on = profile("""
                {"lostcity":{"generateLighting":true}}
                """);
        LostCityProfile off = profile("""
                {"lostcity":{"generateLighting":false}}
                """);
        assertEquals(1.0f, on.LIGHTING_DENSITY);
        assertEquals(0.0f, off.LIGHTING_DENSITY);
    }

    @Test
    void migratesLegacyLootProbability() {
        LostCityProfile profile = profile("""
                {"lostcity":{
                  "generateLoot":true,
                  "buildingWithoutLootChance":0.25,
                  "chestWithoutLootChance":0.40
                }}
                """);
        assertEquals(0.45f, profile.LOOT_DENSITY, 0.00001f);
    }

    @Test
    void migratesDisabledLegacyLootToZero() {
        LostCityProfile profile = profile("""
                {"lostcity":{
                  "generateLoot":false,
                  "buildingWithoutLootChance":0.0,
                  "chestWithoutLootChance":0.0
                }}
                """);
        assertEquals(0.0f, profile.LOOT_DENSITY);
    }

    @Test
    void legacyLootDefaultsMigrateToPoint64() {
        LostCityProfile profile = profile("""
                {"lostcity":{"generateLoot":true}}
                """);
        assertEquals(0.64f, profile.LOOT_DENSITY, 0.00001f);
    }

    @Test
    void newKeysWinIndependentlyOverLegacyKeys() {
        LostCityProfile newLighting = profile("""
                {"lostcity":{
                  "lightingDensity":0.35,
                  "generateLighting":false,
                  "generateLoot":false,
                  "buildingWithoutLootChance":0.0,
                  "chestWithoutLootChance":0.0
                }}
                """);
        assertEquals(0.35f, newLighting.LIGHTING_DENSITY);
        assertEquals(0.0f, newLighting.LOOT_DENSITY);

        LostCityProfile newLoot = profile("""
                {"lostcity":{
                  "generateLighting":true,
                  "lootDensity":0.70,
                  "generateLoot":false,
                  "buildingWithoutLootChance":1.0,
                  "chestWithoutLootChance":1.0
                }}
                """);
        assertEquals(1.0f, newLoot.LIGHTING_DENSITY);
        assertEquals(0.70f, newLoot.LOOT_DENSITY);
    }

    @Test
    void serializesOnlyDensityKeys() {
        JsonObject lostcity = new LostCityProfile("test", true).toJson(false).getAsJsonObject("lostcity");
        assertTrue(lostcity.has("lightingDensity"));
        assertTrue(lostcity.has("lootDensity"));
        assertFalse(lostcity.has("generateLighting"));
        assertFalse(lostcity.has("generateLoot"));
        assertFalse(lostcity.has("buildingWithoutLootChance"));
        assertFalse(lostcity.has("chestWithoutLootChance"));
    }

    @Test
    void clampsAndRoundTripsDensities() {
        LostCityProfile clamped = profile("""
                {"lostcity":{"lightingDensity":-0.25,"lootDensity":1.25}}
                """);
        assertEquals(0.0f, clamped.LIGHTING_DENSITY);
        assertEquals(1.0f, clamped.LOOT_DENSITY);

        clamped.LIGHTING_DENSITY = 0.37f;
        clamped.LOOT_DENSITY = 0.82f;
        LostCityProfile roundTripped = profile(clamped.toJson(false).toString());
        assertEquals(0.37f, roundTripped.LIGHTING_DENSITY);
        assertEquals(0.82f, roundTripped.LOOT_DENSITY);
    }

    private static LostCityProfile profile(String json) {
        return new LostCityProfile("legacy", json);
    }
}
