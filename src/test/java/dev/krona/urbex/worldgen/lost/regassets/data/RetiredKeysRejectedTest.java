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
import java.lang.reflect.ParameterizedType;
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
 * {@code CustomRegistries} actually registers, so a registry cannot be added uncovered.
 * <p>
 * <b>Palettes are now two codecs behind one registry key, and both have to reject.</b> The
 * {@code palettes} registry takes {@link dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition#CODEC},
 * a dispatcher that reads {@code version} and delegates - so "the registered codec rejects
 * {@code inherit}" is a claim about whichever branch the document selects, and a document selects the
 * version 1 branch by declaring no {@code version} at all. The sweep below therefore counts fourteen
 * {@code *Definition} classes against thirteen registry keys, and the extra one is not slack: it is
 * {@code PaletteDefinition}, still reachable as a branch of the dispatcher rather than as a registered
 * codec, and {@link #bothBranchesOfThePaletteDispatcherRejectBothRetiredKeys} walks the branches
 * explicitly. The contract the count pins is unchanged in strength - every dynamic registry key has
 * exactly one registered codec, and every codec that can read a datapack file refuses both keys - and it
 * is now stated over the registry keys, which are what actually create registries, rather than over a
 * class-name glob.
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
        assertEquals(14, codecs.size(),
                "expected the fourteen definition codecs - thirteen registries plus the version 1"
                        + " palette branch behind the palette dispatcher - found " + codecs.keySet());

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Codec<?>> entry : codecs.entrySet()) {
            problems.addAll(rejectionsOf(entry.getKey(), entry.getValue(), ""));
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    /**
     * Both branches of the palette dispatcher refuse both keys.
     * <p>
     * The version 1 branch is reached by declaring no {@code version} - which is what
     * {@link #everyRegisteredCodecRejectsBothRetiredKeys} already exercises through the dispatcher - and
     * the version 2 branch by declaring {@code "version": 2}, where the refusal comes from the version 2
     * retired-key table rather than from {@code RetiredKeys}. Both are asserted here because they are two
     * pieces of code, and a registry that refuses a retired key on only the branch nobody's pack uses
     * refuses nothing: every shipped pack is version 1 today, and every converted pack will be version 2.
     */
    @Test
    void bothBranchesOfThePaletteDispatcherRejectBothRetiredKeys() {
        List<String> problems = new ArrayList<>(rejectionsOf("palettes (version 1 branch)",
                dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition.CODEC, ""));
        problems.addAll(rejectionsOf("palettes (version 2 branch)",
                dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition.CODEC,
                "\"version\":2,"));
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    /** What {@code codec} fails to say about each retired key, given a document prefix. */
    private static List<String> rejectionsOf(String name, Codec<?> codec, String prefix) {
        List<String> problems = new ArrayList<>();
        for (String retired : List.of("inherit", "parent")) {
            JsonElement json = JsonParser.parseString(
                    "{" + prefix + "\"" + retired + "\":\"urbex:whatever\"}");
            DataResult<?> result = codec.parse(JsonOps.INSTANCE, json);
            Optional<DataResult.Error<?>> error = result.error().map(e -> (DataResult.Error<?>) e);
            if (error.isEmpty()) {
                problems.add(name + ": accepted '" + retired + "' instead of failing");
                continue;
            }
            String message = error.get().message();
            // The two things the spec promises the author is told.
            if (!message.contains("'" + retired + "'")) {
                problems.add(name + " / " + retired
                        + ": error does not name the offending key: " + message);
            }
            if (!message.contains("'extends'")) {
                problems.add(name + " / " + retired
                        + ": error does not name the replacement key: " + message);
            }
        }
        return problems;
    }

    /**
     * Every dynamic registry key has exactly one registered codec, and that codec is one this test
     * swept.
     * <p>
     * Derived from the {@code _REGISTRY_KEY} fields rather than from a count, and from each field's own
     * type argument rather than from its name: a {@code ResourceKey<Registry<X>>} names the type
     * {@code DynamicRegistries.register} will demand a {@code Codec<X>} for, so reading {@code X} off the
     * field and requiring {@code X.CODEC} to exist and to have been swept is the same claim the registry
     * itself makes. That is what closes the hole a count alone would leave open now that one registry's
     * value type is not a {@code *Definition} class of its own: a fourteenth registry still cannot be
     * added without retired-key rejection, because its key field is what this reads.
     */
    @Test
    void everyDynamicRegistryIsCovered() throws Exception {
        Map<String, Codec<?>> swept = registryCodecs();
        List<Field> keyFields = Stream.of(CustomRegistries.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getName().endsWith("_REGISTRY_KEY"))
                .toList();
        assertEquals(13, keyFields.size(), "the thirteen dynamic registries");

        List<String> problems = new ArrayList<>();
        for (Field keyField : keyFields) {
            Class<?> valueType = registryValueType(keyField);
            if (!swept.containsKey(valueType.getSimpleName())) {
                problems.add(keyField.getName() + " holds entries of " + valueType.getSimpleName()
                        + ", whose CODEC this test does not sweep");
                continue;
            }
            Field codec = valueType.getDeclaredField("CODEC");
            assertTrue(Modifier.isPublic(codec.getModifiers()) && Modifier.isStatic(codec.getModifiers()),
                    valueType.getSimpleName() + ".CODEC should be public static");
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    /** {@code X} out of a {@code ResourceKey<Registry<X>>} field. */
    private static Class<?> registryValueType(Field keyField) {
        ParameterizedType resourceKey = (ParameterizedType) keyField.getGenericType();
        ParameterizedType registry = (ParameterizedType) resourceKey.getActualTypeArguments()[0];
        return (Class<?>) registry.getActualTypeArguments()[0];
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
