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
        assertEquals("", cfg.selectedPreset());
        assertTrue(cfg.avoidVillages());
        assertFalse(cfg.structuresYieldToCities());
        assertEquals(List.of(), cfg.dimensionsWithPresets());
    }

    @Test
    public void jsonFieldsOverrideDefaults() {
        JsonObject json = JsonParser.parseString(
                "{\"selectedPreset\": \"rare\", \"cacheCleanupSeconds\": 60}").getAsJsonObject();
        UrbexConfig cfg = UrbexConfig.fromJson(json).orElseThrow();
        assertEquals("rare", cfg.selectedPreset());
        assertEquals(60, cfg.cacheCleanupSeconds());
        assertEquals(20, cfg.todoQueueSize());   // untouched fields keep defaults
    }

    /**
     * The written global file names every option, so the file a player opens documents what they can
     * set. The ordinary encoding omits any key still holding its default, which for a fresh install
     * is all of them - it wrote {@code {}} while its own comment called it "the full, normalized
     * file" (issue #130).
     */
    @Test
    public void theFullEncodingNamesEveryFieldEvenAtItsDefault() {
        JsonObject full = UrbexConfig.toFullJson(UrbexConfig.DEFAULT);

        assertEquals(UrbexConfig.class.getRecordComponents().length, full.size(),
                "every record component must be written; a field added to the record and not to "
                        + "FULL_CODEC would silently stop appearing in the file");
        assertTrue(full.has("experimentalMultiWorldStyles"));
        assertTrue(full.has("avoidStructures"));
        assertEquals(new JsonObject(), UrbexConfig.toJson(UrbexConfig.DEFAULT),
                "while the difference encoding stays empty - that is what lets a world file carry "
                        + "only what it changes");
    }

    @Test
    public void theFullEncodingRoundTrips() {
        UrbexConfig config = UrbexConfig.fromJson(JsonParser.parseString(
                        "{\"selectedPreset\": \"urbex:largecities\", \"heightSampleSize\": 9, "
                                + "\"experimentalMultiWorldStyles\": true}")
                .getAsJsonObject()).orElseThrow();

        assertEquals(config, UrbexConfig.fromJson(UrbexConfig.toFullJson(config)).orElseThrow(),
                "the two codecs must agree about what each key means, not only about its name");
    }

    @Test
    public void outOfRangeValueIsRejected() {
        JsonObject json = JsonParser.parseString("{\"heightSampleSize\": 0}").getAsJsonObject();
        assertTrue(UrbexConfig.fromJson(json).isEmpty());
    }

    @Test
    public void mergeLetsOverlayWinPerKey() {
        JsonObject base = JsonParser.parseString(
                "{\"selectedPreset\": \"default\", \"todoQueueSize\": 50}").getAsJsonObject();
        JsonObject overlay = JsonParser.parseString(
                "{\"selectedPreset\": \"cavern\"}").getAsJsonObject();
        JsonObject merged = UrbexConfig.merge(base, overlay);
        UrbexConfig cfg = UrbexConfig.fromJson(merged).orElseThrow();
        assertEquals("cavern", cfg.selectedPreset());   // overlay wins
        assertEquals(50, cfg.todoQueueSize());              // base survives where overlay is silent
    }

    @Test
    public void multiWorldStyleMixingIsOffUnlessAskedFor() {
        assertFalse(UrbexConfig.DEFAULT.experimentalMultiWorldStyles());

        JsonObject json = new JsonObject();
        json.addProperty("experimentalMultiWorldStyles", true);
        UrbexConfig parsed = UrbexConfig.fromJson(json).orElseThrow();
        assertTrue(parsed.experimentalMultiWorldStyles());

        // Round-trips, so loadGlobal's normalized write-back keeps the opt-in.
        assertTrue(UrbexConfig.fromJson(UrbexConfig.toJson(parsed)).orElseThrow()
                .experimentalMultiWorldStyles());
    }

    @Test
    public void roundTripsThroughJson() {
        UrbexConfig cfg = UrbexConfig.fromJson(new JsonObject()).orElseThrow();
        assertEquals(cfg, UrbexConfig.fromJson(UrbexConfig.toJson(cfg)).orElseThrow());
    }
}
