package dev.krona.urbex.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UrbexConfigTest {

    @Test
    public void emptyJsonYieldsAllDefaults() {
        UrbexConfig cfg = UrbexConfig.fromJson(new JsonObject()).orElseThrow();
        assertEquals(UrbexConfig.DEFAULT, cfg);
        assertEquals(3, cfg.heightSampleSize());
        assertEquals("", cfg.selectedProfile());
        assertTrue(cfg.avoidVillages());
        assertFalse(cfg.structuresYieldToCities());
        assertEquals(List.of(), cfg.dimensionsWithProfiles());
    }

    @Test
    public void jsonFieldsOverrideDefaults() {
        JsonObject json = JsonParser.parseString(
                "{\"selectedProfile\": \"rare\", \"cacheCleanupSeconds\": 60}").getAsJsonObject();
        UrbexConfig cfg = UrbexConfig.fromJson(json).orElseThrow();
        assertEquals("rare", cfg.selectedProfile());
        assertEquals(60, cfg.cacheCleanupSeconds());
        assertEquals(20, cfg.todoQueueSize());   // untouched fields keep defaults
    }

    @Test
    public void outOfRangeValueIsRejected() {
        JsonObject json = JsonParser.parseString("{\"heightSampleSize\": 0}").getAsJsonObject();
        assertTrue(UrbexConfig.fromJson(json).isEmpty());
    }

    @Test
    public void mergeLetsOverlayWinPerKey() {
        JsonObject base = JsonParser.parseString(
                "{\"selectedProfile\": \"default\", \"todoQueueSize\": 50}").getAsJsonObject();
        JsonObject overlay = JsonParser.parseString(
                "{\"selectedProfile\": \"biosphere\"}").getAsJsonObject();
        JsonObject merged = UrbexConfig.merge(base, overlay);
        UrbexConfig cfg = UrbexConfig.fromJson(merged).orElseThrow();
        assertEquals("biosphere", cfg.selectedProfile());   // overlay wins
        assertEquals(50, cfg.todoQueueSize());              // base survives where overlay is silent
    }

    @Test
    public void roundTripsThroughJson() {
        UrbexConfig cfg = UrbexConfig.fromJson(new JsonObject()).orElseThrow();
        assertEquals(cfg, UrbexConfig.fromJson(UrbexConfig.toJson(cfg)).orElseThrow());
    }
}
