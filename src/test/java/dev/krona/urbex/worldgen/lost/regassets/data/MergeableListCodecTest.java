package dev.krona.urbex.worldgen.lost.regassets.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeableListCodecTest {

    private static final Codec<Mergeable<String>> CODEC = Mergeable.codec(Codec.STRING);

    private static Mergeable<String> decode(String json) {
        return CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(msg -> new AssertionError("decode failed: " + msg));
    }

    private static JsonElement encode(Mergeable<String> m) {
        return CODEC.encodeStart(JsonOps.INSTANCE, m)
                .getOrThrow(msg -> new AssertionError("encode failed: " + msg));
    }

    @Test
    void bareArrayReplaces() {
        Mergeable<String> m = decode("[\"a\", \"b\"]");
        assertTrue(m.replace(), "a plain array is the whole list");
        assertEquals(List.of("a", "b"), m.values());
    }

    @Test
    void objectFormCanOptIntoAppending() {
        Mergeable<String> m = decode("{\"replace\": false, \"values\": [\"c\"]}");
        assertEquals(false, m.replace());
        assertEquals(List.of("c"), m.values());
    }

    @Test
    void objectFormDefaultsToReplacing() {
        assertTrue(decode("{\"values\": [\"c\"]}").replace(),
                "omitting 'replace' means the same as a bare array");
    }

    @Test
    void applyReplacesOrAppendsAgainstInheritedEntries() {
        List<String> target = new ArrayList<>(List.of("inherited1", "inherited2"));
        Mergeable.apply(target, decode("[\"own\"]"));
        assertEquals(List.of("own"), target);

        List<String> target2 = new ArrayList<>(List.of("inherited1", "inherited2"));
        Mergeable.apply(target2, decode("{\"replace\": false, \"values\": [\"own\"]}"));
        assertEquals(List.of("inherited1", "inherited2", "own"), target2,
                "appended entries follow the parent's, so parent order is stable");
    }

    @Test
    void explicitlyEmptyArrayMeansEmpty() {
        List<String> target = new ArrayList<>(List.of("inherited"));
        Mergeable.apply(target, decode("[]"));
        assertTrue(target.isEmpty());
    }

    @Test
    void replacingEncodesToABareArray() {
        JsonElement encoded = encode(new Mergeable<>(true, List.of("a", "b")));

        assertTrue(encoded.isJsonArray(), "a replacing Mergeable must encode to a bare array, not the object form: " + encoded);
        assertEquals(JsonParser.parseString("[\"a\", \"b\"]"), encoded);
    }

    @Test
    void appendingEncodesToTheObjectFormWithReplaceFalsePresent() {
        JsonElement encoded = encode(new Mergeable<>(false, List.of("c")));

        assertTrue(encoded.isJsonObject(), "an appending Mergeable must encode to the object form: " + encoded);
        var obj = encoded.getAsJsonObject();
        assertTrue(obj.has("replace"), "'replace': false must be written explicitly - if it were ever "
                + "omitted, decoding would fall back to the field's declared default (true), silently "
                + "turning an appending Mergeable back into a replacing one: " + encoded);
        assertFalse(obj.get("replace").getAsBoolean());
        assertEquals(JsonParser.parseString("[\"c\"]"), obj.get("values"));
    }

    @Test
    void bothShapesRoundTripThroughEncodeThenDecode() {
        Mergeable<String> replacing = new Mergeable<>(true, List.of("a", "b"));
        Mergeable<String> appending = new Mergeable<>(false, List.of("c", "d"));

        assertEquals(replacing, decode(encode(replacing).toString()));
        assertEquals(appending, decode(encode(appending).toString()));
    }
}
