package dev.krona.urbex.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.BuildingPartRE;
import dev.krona.urbex.worldgen.lost.regassets.BuildingRE;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleRE;
import dev.krona.urbex.worldgen.lost.regassets.ConditionRE;
import dev.krona.urbex.worldgen.lost.regassets.MultiBuildingRE;
import dev.krona.urbex.worldgen.lost.regassets.PaletteRE;
import dev.krona.urbex.worldgen.lost.regassets.PredefinedCityRE;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import dev.krona.urbex.worldgen.lost.regassets.ScatteredRE;
import dev.krona.urbex.worldgen.lost.regassets.StuffSettingsRE;
import dev.krona.urbex.worldgen.lost.regassets.StyleRE;
import dev.krona.urbex.worldgen.lost.regassets.VariantRE;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleRE;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every JSON example in {@code docs/datapacks.md} decodes through the codec of the registry it
 * claims to belong to.
 * <p>
 * The guide is the authoring reference for third-party packs - Urbex-ModernTweaks first - and an
 * example that would not load is worse than no example: it is copied, it fails, and the reader has
 * no way to tell whether the mistake is theirs or the document's. Field names, required fields and
 * qualified ids all move when a codec moves, and none of that is visible to a Markdown reader.
 * <p>
 * Each fenced {@code json} block must be preceded by an <code>&lt;!-- example: &lt;registry&gt;
 * --&gt;</code> marker naming the registry directory it is a file for, or {@code none} for a block
 * that is not a registry file at all ({@code pack.mcmeta}, a fragment shown out of context). An
 * unmarked block fails, so a future example cannot skip the check by omission.
 */
class DatapackGuideExamplesTest {

    private static final Path GUIDE = Path.of("docs/datapacks.md");

    /**
     * The thirteen registries, by the directory name the guide (and a datapack) spells them with.
     * <p>
     * A method rather than a static field: touching any {@code *RE.CODEC} initialises Minecraft's
     * built-in registries, and a static field would do that during class initialisation, before
     * {@link #bootstrap()} has run.
     */
    private static Map<String, Codec<?>> codecs() {
        return Map.ofEntries(
            Map.entry("worldstyles", WorldStyleRE.CODEC),
            Map.entry("citystyles", CityStyleRE.CODEC),
            Map.entry("buildings", BuildingRE.CODEC),
            Map.entry("parts", BuildingPartRE.CODEC),
            Map.entry("palettes", PaletteRE.CODEC),
            Map.entry("styles", StyleRE.CODEC),
            Map.entry("multibuildings", MultiBuildingRE.CODEC),
            Map.entry("scattered", ScatteredRE.CODEC),
            Map.entry("conditions", ConditionRE.CODEC),
            Map.entry("variants", VariantRE.CODEC),
            Map.entry("stuff", StuffSettingsRE.CODEC),
            Map.entry("predefinedcities", PredefinedCityRE.CODEC),
            Map.entry("presets", PresetRE.CODEC));
    }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyJsonExampleDecodes() throws IOException {
        List<String> problems = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        Map<String, Codec<?>> codecs = codecs();

        for (Example example : examples(Files.readAllLines(GUIDE), problems)) {
            if (example.registry.equals("none")) {
                continue;
            }
            Codec<?> codec = codecs.get(example.registry);
            if (codec == null) {
                problems.add(GUIDE + ":" + example.line + ": unknown registry '" + example.registry
                        + "'; expected one of " + new java.util.TreeSet<>(codecs.keySet()) + " or 'none'");
                continue;
            }
            covered.add(example.registry);
            try {
                JsonElement json = JsonParser.parseString(example.json);
                emptyBiomeSets(json);
                for (String key : unreadKeys(codec, json)) {
                    problems.add(GUIDE + ":" + example.line + ": " + example.registry
                            + " example declares '" + key + "', which the codec does not read");
                }
            } catch (RuntimeException e) {
                problems.add(GUIDE + ":" + example.line + ": " + example.registry + " example does not decode: "
                        + e.getMessage());
            }
        }

        Set<String> uncovered = new java.util.TreeSet<>(codecs.keySet());
        uncovered.removeAll(covered);
        if (!uncovered.isEmpty()) {
            // The guide promises a working example of every registry; without this the promise
            // quietly lapses the next time one is added.
            problems.add(GUIDE + ": no example for " + uncovered);
        }

        assertTrue(problems.isEmpty(), () -> problems.size() + " problems:\n" + String.join("\n", problems));
    }

    private record Example(String registry, String json, int line) {
    }

    /**
     * Decodes {@code json} and returns the object keys the codec threw away.
     * <p>
     * Decoding alone is not enough to keep an example honest. A DFU record codec ignores map keys it
     * does not know, so {@code "styl"} for {@code "style"} decodes cleanly and produces a file that
     * does nothing - which in a document is worse than a file that fails, because the reader copies
     * it and blames themselves. Re-encoding the decoded value and looking for keys that did not
     * survive is what catches that.
     * <p>
     * Only object keys are compared, and only where both sides are objects: a list field re-encodes
     * in whichever of its three accepted shapes the value now has, so aligning those would be
     * comparing formatting rather than meaning. The one false positive this leaves is an explicit
     * {@code "replace": true}, which is the default and re-encodes as a bare array; write the bare
     * array instead.
     */
    private static <A> List<String> unreadKeys(Codec<A> codec, JsonElement json) {
        A decoded = codec.parse(JsonOps.INSTANCE, json).getOrThrow();
        JsonElement encoded = codec.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow();
        List<String> unread = new ArrayList<>();
        collectUnreadKeys("", json, encoded, unread);
        return unread;
    }

    private static void collectUnreadKeys(String path, JsonElement source, JsonElement encoded, List<String> unread) {
        if (!source.isJsonObject() || !encoded.isJsonObject()) {
            return;
        }
        JsonObject from = source.getAsJsonObject();
        JsonObject to = encoded.getAsJsonObject();
        for (String key : from.keySet()) {
            if (!to.has(key)) {
                unread.add(path + key);
            } else {
                collectUnreadKeys(path + key + ".", from.get(key), to.get(key), unread);
            }
        }
    }

    /**
     * Every fenced {@code json} block in the guide, paired with the marker on the line above it. A
     * block with no marker is recorded as a problem rather than skipped.
     */
    private static List<Example> examples(List<String> lines, List<String> problems) {
        List<Example> examples = new ArrayList<>();
        String pending = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("<!-- example:") && line.endsWith("-->")) {
                pending = line.substring("<!-- example:".length(), line.length() - "-->".length()).strip();
                continue;
            }
            if (!line.equals("```json")) {
                // A blank line between the marker and its fence is fine; anything else means the
                // marker was not attached to a block, which is worth knowing about.
                if (!line.isEmpty() && pending != null) {
                    problems.add(GUIDE + ":" + (i + 1) + ": '<!-- example: " + pending
                            + " -->' is not followed by a ```json block");
                    pending = null;
                }
                continue;
            }
            int start = i + 1;
            StringBuilder json = new StringBuilder();
            while (++i < lines.size() && !lines.get(i).strip().equals("```")) {
                json.append(lines.get(i)).append('\n');
            }
            if (pending == null) {
                problems.add(GUIDE + ":" + start + ": json block with no '<!-- example: ... -->' marker");
            } else {
                examples.add(new Example(pending, json.toString(), start));
                pending = null;
            }
        }
        return examples;
    }

    /**
     * Replaces every {@code "biomes"} value with an empty object, in place - the same shim
     * {@link WorldStyleCompletenessTest} uses, and for the same reason: {@code BiomeMatcher.CODEC}
     * resolves ids and {@code #tags} against a live biome registry, so it needs a {@code RegistryOps}
     * rather than plain {@code JsonOps}. Every field of the matcher is optional, so an empty object
     * decodes fine, and nothing here calls {@code test()} on the result. What is being checked is the
     * shape of the surrounding file, not which biomes an example happens to name.
     */
    private static void emptyBiomeSets(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : List.copyOf(object.keySet())) {
                if (key.equals("biomes")) {
                    object.add(key, new JsonObject());
                } else {
                    emptyBiomeSets(object.get(key));
                }
            }
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(DatapackGuideExamplesTest::emptyBiomeSets);
        }
    }
}
