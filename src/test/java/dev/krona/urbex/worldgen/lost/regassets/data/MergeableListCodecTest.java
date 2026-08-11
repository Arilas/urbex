package dev.krona.urbex.worldgen.lost.regassets.data;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeableListCodecTest {

    private static final Codec<Mergeable<String>> CODEC = Mergeable.codec(Codec.STRING);

    private static Mergeable<String> decode(String json) {
        return CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(msg -> new AssertionError("decode failed: " + msg));
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
}
