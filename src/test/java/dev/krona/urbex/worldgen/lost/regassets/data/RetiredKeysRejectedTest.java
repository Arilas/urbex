package dev.krona.urbex.worldgen.lost.regassets.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.worldgen.lost.regassets.PresetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.RetiredPresetKeyException;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code inherit} and {@code parent} are deleted, not aliased, and a file using either fails to load
 * naming the key and its replacement - spec section 2.
 * <p>
 * Nothing implemented that until now, and the gap was silent in the worst way: DFU ignores unknown
 * map keys, so a city style declaring {@code "inherit": "urbex:citystyle_common"} decoded cleanly and
 * loaded as a chain root with <em>no inheritance and no diagnostic</em>. That is the shape a
 * mechanically ported Lost Cities Modern Tweaks pack has, since {@code inherit} is that format's key.
 * <p>
 * The coverage claim is the point of this test, so it is made by enumeration rather than by a list
 * someone has to remember to extend: {@link #everyRegisteredCodecRejectsBothRetiredKeys} reflects
 * the {@code CODEC} field off every {@code *Definition} class in the registry package and requires each to
 * reject, and {@link #everyDynamicRegistryIsCovered} cross-checks that set against the registry keys
 * {@code CustomRegistries} actually registers, so a fourteenth registry cannot be added uncovered.
 */
class RetiredKeysRejectedTest {

    private static final Map<String, String> RETIRED_CITY_KEYS = Map.of(
            "cityStyleThreshold", "Preset key 'cities.cityStyleThreshold' was removed; declare the selected world style's 'citystyles[].edge' with 'citystyle' and 'threshold' instead.",
            "cityStyleAlternative", "Preset key 'cities.cityStyleAlternative' was removed; declare the selected world style's 'citystyles[].edge' with 'citystyle' and 'threshold' instead.");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final Path RE_PACKAGE =
            Path.of("src/main/java/dev/krona/urbex/worldgen/lost/regassets");

    /** Every {@code *Definition} class in the registry package, by simple name, with its CODEC. */
    private static Map<String, Codec<?>> registryCodecs() throws Exception {
        Map<String, Codec<?>> codecs = new LinkedHashMap<>();
        List<Path> sources;
        try (Stream<Path> files = Files.list(RE_PACKAGE)) {
            sources = files.filter(p -> p.getFileName().toString().endsWith("Definition.java")).sorted().toList();
        }
        for (Path source : sources) {
            String simple = source.getFileName().toString().replace(".java", "");
            Class<?> type = Class.forName("dev.krona.urbex.worldgen.lost.regassets." + simple);
            Field field = type.getDeclaredField("CODEC");
            assertTrue(Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers()),
                    simple + ".CODEC should be public static");
            codecs.put(simple, (Codec<?>) field.get(null));
        }
        return codecs;
    }

    @Test
    void everyRegisteredCodecRejectsBothRetiredKeys() throws Exception {
        Map<String, Codec<?>> codecs = registryCodecs();
        assertEquals(13, codecs.size(),
                "expected the thirteen registry entry codecs, found " + codecs.keySet());

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Codec<?>> entry : codecs.entrySet()) {
            for (String retired : List.of("inherit", "parent")) {
                JsonElement json = JsonParser.parseString("{\"" + retired + "\":\"urbex:whatever\"}");
                DataResult<?> result = entry.getValue().parse(JsonOps.INSTANCE, json);
                Optional<DataResult.Error<?>> error = result.error().map(e -> (DataResult.Error<?>) e);
                if (error.isEmpty()) {
                    problems.add(entry.getKey() + ": accepted '" + retired + "' instead of failing");
                    continue;
                }
                String message = error.get().message();
                // The two things the spec promises the author is told.
                if (!message.contains("'" + retired + "'")) {
                    problems.add(entry.getKey() + " / " + retired
                            + ": error does not name the offending key: " + message);
                }
                if (!message.contains("'extends'")) {
                    problems.add(entry.getKey() + " / " + retired
                            + ": error does not name the replacement key: " + message);
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    /**
     * The reflective sweep above is only a coverage proof if the classes it finds are the classes
     * that get registered. This pins that: {@code CustomRegistries.init()} registers one codec per
     * registry key, and there are as many registry keys as there are {@code *Definition} classes.
     */
    @Test
    void everyDynamicRegistryIsCovered() throws Exception {
        long registryKeys = Stream.of(CustomRegistries.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getName().endsWith("_REGISTRY_KEY"))
                .count();
        assertEquals(registryCodecs().size(), registryKeys,
                "every dynamic registry key should have exactly one *Definition class whose CODEC this test sweeps");
    }

    /** A valid file is untouched: the wrapper is a pre-check, not a new required field. */
    @Test
    void aFileWithNeitherKeyStillDecodes() {
        DataResult<?> result = dev.krona.urbex.worldgen.lost.regassets.PresetDefinition.CODEC.parse(
                JsonOps.INSTANCE, JsonParser.parseString("{\"extends\":\"urbex:default\"}"));
        assertTrue(result.result().isPresent(), () -> "expected a clean decode, got " + result);
    }

    /** Encoding is delegated untouched - the command and GUI export paths depend on it. */
    @Test
    void encodeIsUnaffected() {
        var re = dev.krona.urbex.worldgen.lost.regassets.PresetDefinition.CODEC.parse(
                JsonOps.INSTANCE, JsonParser.parseString("{\"extends\":\"urbex:default\"}")).getOrThrow();
        DataResult<JsonElement> encoded =
                dev.krona.urbex.worldgen.lost.regassets.PresetDefinition.CODEC.encodeStart(JsonOps.INSTANCE, re);
        assertTrue(encoded.result().isPresent(), () -> "expected a clean encode, got " + encoded);
        assertEquals("urbex:default", encoded.getOrThrow().getAsJsonObject().get("extends").getAsString());
    }

    /** Both keys at once reports one of them, and the same one every run. */
    @Test
    void bothKeysAtOnceIsDeterministic() {
        String json = "{\"inherit\":\"urbex:a\",\"parent\":\"urbex:b\"}";
        String first = dev.krona.urbex.worldgen.lost.regassets.CityStyleDefinition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).error().orElseThrow().message();
        for (int i = 0; i < 20; i++) {
            assertEquals(first, dev.krona.urbex.worldgen.lost.regassets.CityStyleDefinition.CODEC
                    .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).error().orElseThrow().message());
        }
        assertTrue(first.contains("'inherit'"), first);
    }

    @Test
    void presetRegistryRejectsEachRetiredCityKeyWithItsMigrationMessage() {
        for (Map.Entry<String, String> entry : RETIRED_CITY_KEYS.entrySet()) {
            JsonElement json = JsonParser.parseString("{\"cities\":{\"" + entry.getKey() + "\":0}}");
            String message = PresetDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                    .error().orElseThrow().message();
            assertTrue(message.contains(entry.getValue()), message);
        }
    }

    @Test
    void overrideParserRejectsEachRetiredCityKeyWithItsMigrationMessage() {
        for (Map.Entry<String, String> entry : RETIRED_CITY_KEYS.entrySet()) {
            JsonElement json = JsonParser.parseString("{\"cities\":{\"" + entry.getKey() + "\":0}}");
            RetiredPresetKeyException error = assertThrows(
                    RetiredPresetKeyException.class, () -> PresetDefinition.parseOverrides(json));
            assertEquals(entry.getValue(), error.getMessage());
        }
    }

    @Test
    void bothRetiredCityKeysChooseOneDeterministicMessageAtBothDecodeRoutes() {
        JsonElement json = JsonParser.parseString(
                "{\"cities\":{\"cityStyleAlternative\":\"urbex:border\",\"cityStyleThreshold\":0.4}}");

        String registryMessage = PresetDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                .error().orElseThrow().message();
        RetiredPresetKeyException overrideError = assertThrows(
                RetiredPresetKeyException.class, () -> PresetDefinition.parseOverrides(json));

        String expected = RETIRED_CITY_KEYS.get("cityStyleThreshold");
        assertTrue(registryMessage.contains(expected), registryMessage);
        assertEquals(expected, overrideError.getMessage());
        for (int i = 0; i < 20; i++) {
            assertTrue(PresetDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                    .error().orElseThrow().message().contains(expected));
            assertEquals(expected, assertThrows(RetiredPresetKeyException.class,
                    () -> PresetDefinition.parseOverrides(json)).getMessage());
        }
    }

    /**
     * No bundled file uses either key, so this change cannot move a golden. Verified rather than
     * assumed - that is the whole basis for claiming the digests stay put.
     */
    @Test
    void noBundledDatapackFileUsesARetiredKey() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/data"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonElement parsed = JsonParser.parseString(Files.readString(file));
                if (!parsed.isJsonObject()) {
                    continue;
                }
                for (String retired : List.of("inherit", "parent")) {
                    if (parsed.getAsJsonObject().has(retired)) {
                        offenders.add(file + " declares '" + retired + "'");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
    }

    /** The pure helper: a non-map input is not this check's business. */
    @Test
    void aNonMapInputIsLeftToTheWrappedCodec() {
        assertFalse(RetiredKeys.problem(
                new com.mojang.serialization.Dynamic<>(JsonOps.INSTANCE, JsonParser.parseString("[1,2]")),
                "citystyle").isPresent());
    }
}
