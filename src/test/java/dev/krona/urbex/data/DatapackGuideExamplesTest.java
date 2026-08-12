package dev.krona.urbex.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;
import dev.krona.urbex.worldgen.lost.cityassets.ExtendsChain;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import dev.krona.urbex.worldgen.lost.cityassets.Resolved;
import dev.krona.urbex.worldgen.lost.cityassets.Style;
import dev.krona.urbex.worldgen.lost.cityassets.StuffObject;
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
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteSelector;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
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
import java.util.Optional;
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
 * <p>
 * The second test does the same for the error messages the guide quotes: it provokes each one from
 * the code that produces it and requires the guide to contain the result verbatim. A quoted message
 * that has drifted misleads exactly the reader who is already stuck, and a review of the first
 * version of this guide found one that named an asset the code cannot put there.
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

    /**
     * Every error message the guide quotes verbatim is produced by the code that raises it, and the
     * guide must contain the result.
     * <p>
     * Whitespace is collapsed on both sides, because the guide wraps long messages across lines.
     * <p>
     * Seven rows of the guide's common-errors table stay hand-checked, for two different reasons.
     * Four are unreachable from a headless test: the {@code Can't find} and {@code Cannot find}
     * part lookups and {@code Error getting resource} need a level and a registry, and the
     * renamed-block warning is a log line rather than a thrown message, so it needs an appender. The
     * other two - {@code declares no 'parts.railways.railsbend'} and
     * {@code declares no 'streetblocks.largeparts.connector'} - are trivially reachable, but the
     * table abbreviates both with an ellipsis, so there is no verbatim string to match. They are the
     * same {@link Resolved#require} sentence as the {@code streetblocks.parts.stair} case asserted
     * below, differing only in the field name they carry - as is the seventh, the stuff
     * {@code declares no 'inbuilding'} row, whose cell uses the table's {@code 'urbexmt:x'}
     * placeholder rather than a real id.
     */
    @Test
    void quotedErrorMessagesAreTheOnesTheCodeProduces() throws IOException {
        String guide = collapse(Files.readString(GUIDE));
        List<String> missing = new ArrayList<>();

        Identifier tower = Identifier.fromNamespaceAndPath("urbexmt", "tower");
        Identifier downtown = Identifier.fromNamespaceAndPath("urbexmt", "downtown");
        Identifier a = Identifier.fromNamespaceAndPath("urbexmt", "a");
        Identifier b = Identifier.fromNamespaceAndPath("urbexmt", "b");
        Identifier missingId = Identifier.fromNamespaceAndPath("urbexmt", "missing");

        // Unqualified reference, from the strict resolver every cross-reference goes through.
        expect(guide, missing, () -> DataTools.fromName("street_straight"));

        // Cycle and dangling extends, from the chain walker. The lookup stands in for a registry and
        // each entry is its own id, so 'extends' can be read back off it: a -> b -> a for the cycle,
        // downtown -> missing (which the lookup does not know) for the dangling link.
        Map<Identifier, Identifier> cyclic = Map.of(a, b, b, a);
        expect(guide, missing, () -> ExtendsChain.resolve(a,
                id -> cyclic.containsKey(id) ? id : null,
                entry -> Optional.ofNullable(cyclic.get(entry))));
        expect(guide, missing, () -> ExtendsChain.resolve(downtown,
                id -> downtown.equals(id) ? id : null,
                entry -> Optional.of(missingId)));

        // A required field nothing in the chain declared.
        expect(guide, missing, () -> Resolved.require(null, downtown, "streetblocks.parts.stair"));

        // A part with no geometry, and a part whose redeclared size contradicts inherited slices.
        expect(guide, missing, () -> new BuildingPart(null, List.of(namedPart(tower, null, null, null))));
        expect(guide, missing, () -> new BuildingPart(null, List.of(
                namedPart(Identifier.fromNamespaceAndPath("urbexmt", "tower_base"), 16, 16,
                        List.of(List.of("x".repeat(256)))),
                namedPart(tower, 8, null, null))));

        // 'extends' inside an inline palette block.
        expect(guide, missing, () -> Palette.inline(null, tower, List.of(new PaletteRE(
                Optional.of(Identifier.fromNamespaceAndPath("urbex", "common")), Optional.empty()))));

        // A palette entry that resolves to nothing at all.
        PaletteEntry empty = new PaletteEntry("#", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        expect(guide, missing, () -> new Palette(null, List.of(
                new PaletteRE(Optional.empty(), Optional.of(List.of(empty)))
                        .setRegistryName(Identifier.fromNamespaceAndPath("urbex", "x")))));

        // A 'randompalettes' group nothing could ever be drawn from, and a stuff entry whose two
        // count bounds contradict. Both are checked at the chain fold rather than per field.
        expect(guide, missing, () -> new Style(List.of(
                new StyleRE(Optional.empty(), Optional.of(new Mergeable<>(true,
                        List.of(List.of(new PaletteSelector(0f, "urbex:common"))))))
                        .setRegistryName(downtown))));
        expect(guide, missing, () -> new StuffObject(List.of(
                stuffCounts(downtown, 5, 2))));

        // The two DataResult errors below are not throws, so they cannot go through expect(): a
        // marker that is not one character, and a count above what the RNG slot address holds.
        expectDataResult(guide, missing, DataTools.PALETTE_CHAR_STRING, "\"ab\"");
        expectDataResult(guide, missing, Codec.intRange(0, 4095), "5000");

        // The retired-key rejection, which is a DataResult error rather than a throw, so it cannot
        // go through expect(). Taken from the codec that ships, not from RetiredKeys.problem, so the
        // guide is pinned to what a pack author actually sees.
        String retired = CityStyleRE.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString("{\"inherit\":\"urbex:citystyle_common\"}"))
                .error().orElseThrow(() -> new AssertionError("expected 'inherit' to be rejected"))
                .message();
        if (!guide.contains(collapse(retired))) {
            missing.add(GUIDE + " does not contain: " + retired);
        }

        assertTrue(missing.isEmpty(),
                () -> missing.size() + " quoted message(s) the guide does not contain verbatim:\n"
                        + String.join("\n", missing));
    }

    /** A stuff entry declaring everything required, with the two count bounds the caller names. */
    private static StuffSettingsRE stuffCounts(Identifier id, int mincount, int maxcount) {
        return new StuffSettingsRE(Optional.empty(),
                Optional.of(new Mergeable<>(true, List.of("rubble"))), Optional.of("AA"),
                Optional.empty(), Optional.empty(),
                Optional.of(mincount), Optional.of(maxcount), Optional.of(1),
                Optional.of(false), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())
                .setRegistryName(id);
    }

    /** Decodes {@code json} through {@code codec} and records the error message it must produce. */
    private static void expectDataResult(String guide, List<String> missing, Codec<?> codec, String json) {
        String message = codec.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .error()
                .orElseThrow(() -> new AssertionError("expected '" + json + "' to be rejected"))
                .message();
        if (!guide.contains(collapse(message))) {
            missing.add(GUIDE + " does not contain: " + message);
        }
    }

    /** Runs {@code shouldThrow}, and records its message unless {@code guide} already contains it. */
    private static void expect(String guide, List<String> missing, Runnable shouldThrow) {
        String message;
        try {
            shouldThrow.run();
            missing.add("expected an error, but nothing was thrown");
            return;
        } catch (RuntimeException e) {
            message = e.getMessage();
        }
        if (!guide.contains(collapse(message))) {
            missing.add(GUIDE + " does not contain: " + message);
        }
    }

    private static BuildingPartRE namedPart(Identifier id, Integer xSize, Integer zSize,
                                            List<List<String>> slices) {
        return new BuildingPartRE(Optional.empty(), Optional.ofNullable(xSize),
                Optional.ofNullable(zSize), Optional.ofNullable(slices), Optional.empty(),
                Optional.empty(), Optional.empty()).setRegistryName(id);
    }

    /** Collapses runs of whitespace to one space, so a message wrapped in Markdown still matches. */
    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ");
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
     * Arrays are walked element-wise, because most of the content in this guide's examples lives
     * inside one - palette entries, a variant's {@code blocks}, a selector's {@code values}, a
     * building's {@code parts} - and a misspelled optional field on an element ({@code "tp"} for
     * {@code "top"}) is the same silent no-op as a misspelled top-level key.
     * <p>
     * Two mismatches are skipped rather than reported, and both fall out of the requirement that the
     * two sides be the same kind of node: a one-element part-wiring array re-encodes as a bare
     * string, and an explicit {@code "replace": true} object re-encodes as a bare array. Those are
     * formatting differences, not meaning ones - write the shorter form in either case and the walk
     * resumes. The equal-length condition on arrays is a separate guard, for a codec that changes a
     * list's element count; nothing in the guide currently trips it.
     */
    private static <A> List<String> unreadKeys(Codec<A> codec, JsonElement json) {
        A decoded = codec.parse(JsonOps.INSTANCE, json).getOrThrow();
        JsonElement encoded = codec.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow();
        List<String> unread = new ArrayList<>();
        collectUnreadKeys("", json, encoded, unread);
        return unread;
    }

    private static void collectUnreadKeys(String path, JsonElement source, JsonElement encoded, List<String> unread) {
        if (source.isJsonArray() && encoded.isJsonArray()) {
            JsonArray fromList = source.getAsJsonArray();
            JsonArray toList = encoded.getAsJsonArray();
            if (fromList.size() == toList.size()) {
                for (int i = 0; i < fromList.size(); i++) {
                    collectUnreadKeys(path + "[" + i + "]", fromList.get(i), toList.get(i), unread);
                }
            }
            return;
        }
        if (!source.isJsonObject() || !encoded.isJsonObject()) {
            return;
        }
        JsonObject from = source.getAsJsonObject();
        JsonObject to = encoded.getAsJsonObject();
        for (String key : from.keySet()) {
            String keyPath = path.isEmpty() ? key : path + "." + key;
            if (!to.has(key)) {
                unread.add(keyPath);
            } else {
                collectUnreadKeys(keyPath, from.get(key), to.get(key), unread);
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
