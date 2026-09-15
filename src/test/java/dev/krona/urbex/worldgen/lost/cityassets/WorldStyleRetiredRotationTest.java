package dev.krona.urbex.worldgen.lost.cityassets;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A former rotation tag must produce a migration error rather than silently stop taking effect. */
class WorldStyleRetiredRotationTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void retiredRotationKeyIsRefusedByPresenceWhateverItsValue() {
        for (String value : List.of("\"#mypack:rotatable\"", "true", "false", "null", "{}", "[]")) {
            var result = WorldStyleDefinition.CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseString("{ \"rotatable\": " + value + " }"));
            String message = result.error().orElseThrow(() -> new AssertionError(
                    "a retired worldstyle key must not be ignored: " + value)).message();
            assertTrue(message.contains("worldstyle") && message.contains("'rotatable'"), message);
            assertTrue(message.contains("'urbex:rotatable': false"), message);
            assertTrue(message.contains("by default"), message);
        }
    }

    @Test
    void ordinaryWorldStyleStillDecodesAndNeverEncodesTheRetiredKey() {
        var source = JsonParser.parseString("""
                { "name": "My City", "outsidestyle": "urbex:outside" }
                """);
        var style = WorldStyleDefinition.CODEC.parse(JsonOps.INSTANCE, source).getOrThrow();
        var encoded = WorldStyleDefinition.CODEC.encodeStart(JsonOps.INSTANCE, style)
                .getOrThrow().getAsJsonObject();
        assertEquals("My City", encoded.get("name").getAsString());
        assertEquals("urbex:outside", encoded.get("outsidestyle").getAsString());
        assertFalse(encoded.has("rotatable"));
    }
}
