package dev.krona.urbex.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the clean break documented on {@link UrbexData#CODEC}: the pre-preset save format stored
 * the selection under {@code profile}/{@code json} (plus whatever the old worldStyle key was).
 * None of those keys are read any more - {@code optionalFieldOf} makes them simply absent from a
 * decode, not an error, and a world that saved under the old format regenerates its selection as
 * unset rather than crashing on load.
 */
class UrbexDataCodecTest {

    @Test
    void legacyKeysDecodeToAnEmptySelection() {
        JsonElement json = JsonParser.parseString("{\"profile\":\"x\",\"json\":\"y\"}");

        UrbexData data = UrbexData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

        assertEquals("", data.getSelectedPreset());
        assertEquals("", data.getSelectedWorldStyle());
        assertEquals("", data.getSelectedOverrides());
    }
}
