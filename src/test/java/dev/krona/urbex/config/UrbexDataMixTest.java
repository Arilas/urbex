package dev.krona.urbex.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.data.UrbexData;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.setup.WorldStyleMix;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A world records the styles it was created with, so it keeps generating them after the create
 * screen is gone. The new {@code worldStyleMix} key has to coexist with the old {@code worldStyle}
 * one: a world made before mixing existed carries only the latter and must keep loading, and a
 * single-style world must keep writing only the latter so its save is unchanged.
 */
class UrbexDataMixTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static UrbexData decode(String json) {
        return UrbexData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static JsonObject encode(UrbexData data) {
        return UrbexData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow().getAsJsonObject();
    }

    @Test
    void aWorldSavedBeforeMixingExistedStillLoads() {
        UrbexData data = decode("{\"preset\":\"urbex:default\",\"worldStyle\":\"urbex:standard\"}");
        assertEquals(WorldStyleMix.of(Identifier.fromNamespaceAndPath("urbex", "standard")),
                data.getSelectedWorldStyles());
    }

    @Test
    void aWorldWithNoStyleAtAllFallsBackToTheDefault() {
        assertEquals(Config.DEFAULT_WORLD_STYLE_MIX,
                decode("{\"preset\":\"urbex:default\"}").getSelectedWorldStyles());
    }

    @Test
    void aSingleStyleWorldWritesOnlyTheOldKey() {
        UrbexData data = new UrbexData();
        data.setChoice("urbex:default",
                WorldStyleMix.of(Identifier.fromNamespaceAndPath("urbex", "standard")), "");
        JsonObject json = encode(data);
        assertEquals("urbex:standard", json.get("worldStyle").getAsString());
        // Absent, not present-and-empty: optionalFieldOf omits a value equal to its default, so a
        // single-style save is byte-identical to one written before mixing existed.
        assertFalse(json.has("worldStyleMix"));
    }

    @Test
    void aMixedWorldRoundTripsThroughTheNewKey() {
        WorldStyleMix mix = WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9");
        UrbexData data = new UrbexData();
        data.setChoice("urbex:default", mix, "");
        assertEquals(mix.format(), encode(data).get("worldStyleMix").getAsString());
        // The legacy key still names the primary, so a downgrade generates the heaviest style
        // rather than nothing at all.
        assertEquals("urbexmt:moderntweaks", encode(data).get("worldStyle").getAsString());

        UrbexData reloaded = decode(encode(data).toString());
        assertEquals(mix, reloaded.getSelectedWorldStyles());
        assertFalse(reloaded.getSelectedWorldStyles().isSingle());
    }

    @Test
    void aMalformedSavedMixFallsBackRatherThanThrowing() {
        // Saved data can be hand-edited or corrupted, and this is read on a worldgen worker thread
        // the moment a chunk generates; it must not take the server down.
        UrbexData bothBad = decode("{\"preset\":\"urbex:default\",\"worldStyleMix\":\"nonsense*\"}");
        assertEquals(Config.DEFAULT_WORLD_STYLE_MIX, bothBad.getSelectedWorldStyles());

        // A bad mix with a good legacy id falls back to that id, not all the way to the default.
        UrbexData mixBadIdGood = decode(
                "{\"preset\":\"urbex:default\",\"worldStyle\":\"urbex:cavern\",\"worldStyleMix\":\"nonsense*\"}");
        assertEquals(WorldStyleMix.of(Identifier.fromNamespaceAndPath("urbex", "cavern")),
                mixBadIdGood.getSelectedWorldStyles());
    }
}
