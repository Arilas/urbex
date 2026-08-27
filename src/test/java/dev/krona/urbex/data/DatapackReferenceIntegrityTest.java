package dev.krona.urbex.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.krona.urbex.format.palette.TraitType;
import dev.krona.urbex.format.palette.Traits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every cross-reference in the bundled datapack must be fully namespaced ("urbex:...") and
 * resolve to an existing asset file. A bare name is a load error, not a shorthand:
 * {@code DataTools.fromName} refuses it rather than defaulting it to the urbex namespace, so this
 * test enforces at build time the rule the game enforces at load time — and the shipped pack stays
 * unambiguous next to third-party datapacks, the same convention LCMT uses. The field map here
 * mirrors docs/superpowers/specs/2026-08-11-explicit-references-and-extends-design.md.
 */
class DatapackReferenceIntegrityTest {

    private static final Path ROOT = Path.of("src/main/resources/data/urbex/urbex");
    private static final Path ASSETS_ROOT = Path.of("src/main/resources/assets/urbex");

    /**
     * Which trait field names an asset, and in which registry - read off the traits themselves.
     *
     * <p>{@code TRAIT.090} makes a trait declare its own reference fields
     * ({@code TraitType.references()}) precisely so that "reference validation reads that declaration"
     * rather than carrying a list beside it, and {@code TRAIT.022}'s {@code > Why} measures what the
     * list cost in version 1: "version 1 recorded which string fields were asset references in a
     * different place from the fields themselves". A hardcoded {@code List.of("urbex:loot",
     * "urbex:spawner")} here would be correct today and silent the day an eighth trait names a pool -
     * which is the same "a guessed key list is how a guard goes quietly out of date" that
     * {@link ShippedBlockRefs} exists to stop.</p>
     *
     * <p>The registry's own path is the directory it loads from ({@code urbex:conditions} lives in
     * {@code conditions/}), which is what {@link #ref} takes.</p>
     */
    private static final Map<String, Map<String, String>> TRAIT_REFERENCES = traitReferences();

    private static Map<String, Map<String, String>> traitReferences() {
        Map<String, Map<String, String>> byTrait = new LinkedHashMap<>();
        for (TraitType<?> trait : Traits.all()) {
            Map<String, String> fields = new LinkedHashMap<>();
            trait.references().forEach(target ->
                    fields.put(target.field(), target.registry().identifier().getPath()));
            if (!fields.isEmpty()) {
                byTrait.put(trait.id().toString(), fields);
            }
        }
        return Map.copyOf(byTrait);
    }

    private Path root = ROOT;
    private Path assetsRoot = ASSETS_ROOT;
    /** target category (directory under ROOT) -> collected [sourceFile, reference] pairs */
    private final List<String> problems = new ArrayList<>();

    @Test
    void allDatapackReferencesAreNamespacedAndResolve() throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(this::checkFile);
        }
        assertTrue(problems.isEmpty(),
                () -> problems.size() + " bad datapack references:\n" + String.join("\n", problems));
    }

    /**
     * The derivation in {@link #TRAIT_REFERENCES} is pinned, because an empty one disables silently.
     *
     * <p>Asserted <em>about</em> the registry rather than used <em>as</em> the registry: the walk reads
     * {@code TraitType.references()}, so an eighth trait naming a pool is checked the day it is
     * registered, and this fails then to say so out loud rather than because anything depends on the
     * list. A refactor that made the derivation yield nothing would otherwise leave every trait
     * reference in the pack unchecked and every test still green.</p>
     */
    @Test
    void theTraitReferenceFieldsAreReadOffTheTraitsThemselves() {
        assertEquals(Map.of(
                        "urbex:loot", Map.of("pool", "conditions"),
                        "urbex:spawner", Map.of("pool", "conditions")),
                TRAIT_REFERENCES,
                "TRAIT.090 says a trait declares its own reference fields; these are the ones that do");
    }

    /** The optional nested edge is a city-style reference under the same static validation as its base. */
    @Test
    void missingNestedEdgeCityStyleFailsTheSameStaticIntegrityCheckAsAMissingBase(@TempDir Path temp)
            throws IOException {
        Path fixtureRoot = temp.resolve("data/urbex/urbex");
        Path source = fixtureRoot.resolve("worldstyles/family.json");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                {
                  "citystyles": [
                    {
                      "factor": 1.0,
                      "citystyle": "urbex:missing_base",
                      "edge": {
                        "citystyle": "urbex:missing_edge",
                        "threshold": 0.4
                      }
                    }
                  ]
                }
                """);

        DatapackReferenceIntegrityTest fixture = new DatapackReferenceIntegrityTest();
        fixture.root = fixtureRoot;
        fixture.assetsRoot = temp.resolve("assets/urbex");
        fixture.checkFile(source);

        assertEquals(List.of(
                        source + ": \"urbex:missing_base\" does not resolve to "
                                + fixtureRoot.resolve("citystyles/missing_base.json"),
                        source + ": \"urbex:missing_edge\" does not resolve to "
                                + fixtureRoot.resolve("citystyles/missing_edge.json")),
                fixture.problems,
                "the actual world-style walker must report the nested edge just like its base");
    }

    private void checkFile(Path file) {
        String category = root.relativize(file).getName(0).toString();
        JsonObject d;
        try {
            d = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException e) {
            problems.add(file + ": unreadable: " + e.getMessage());
            return;
        }
        String src = file.toString();
        // Every registry supports "extends", and it always names an asset in that registry's own
        // directory - so this is checked once here rather than per category, which is what stops a
        // category being added without its extends being covered.
        ref(src, d.get("extends"), category);
        switch (category) {
            case "buildings" -> {
                for (String key : List.of("parts", "parts2")) {
                    forEachObject(d.get(key), e -> {
                        ref(src, e.get("part"), "parts");
                        // The condition fields on a part entry name assets too, and are matched at
                        // runtime against a fully-qualified id - the same string this requires -
                        // so a bare value here is a condition that silently never fires. Each takes
                        // either one string or an array of them (ConditionTest's either-codec).
                        refListOrString(src, e.get("inpart"), "parts");
                        refListOrString(src, e.get("belowpart"), "parts");
                        refListOrString(src, e.get("inbuilding"), "buildings");
                    });
                }
            }
            case "multibuildings" -> forEachElement(d.get("buildings"),
                    row -> forEachElement(row, cell -> ref(src, cell, "buildings")));
            case "styles" -> forEachElement(d.get("randompalettes"),
                    row -> forEachObject(row, e -> ref(src, e.get("palette"), "palettes")));
            case "citystyles" -> {
                ref(src, d.get("style"), "styles");
                JsonObject sel = asObject(d.get("selectors"));
                if (sel != null) {
                    for (Map.Entry<String, String> e : Map.of(
                            "buildings", "buildings", "multibuildings", "multibuildings",
                            "bridges", "parts", "largebridges", "parts", "parks", "parts", "fountains", "parts",
                            "stairs", "parts", "fronts", "parts", "raildungeons", "parts").entrySet()) {
                        forEachObject(sel.get(e.getKey()), o -> ref(src, o.get("value"), e.getValue()));
                    }
                }
                JsonObject sb = asObject(d.get("streetblocks"));
                if (sb != null) {
                    // "tertiaryparts" is optional by design (falls back to "parts" when absent),
                    // so this must tolerate a missing key rather than require it.
                    for (String partsKey : List.of("parts", "largeparts", "tertiaryparts")) {
                        JsonObject sbParts = asObject(sb.get(partsKey));
                        if (sbParts != null) {
                            for (Map.Entry<String, JsonElement> e : sbParts.entrySet()) {
                                refListOrString(src, e.getValue(), "parts");
                            }
                        }
                    }
                }
            }
            case "parts" -> ref(src, d.get("refpalette"), "palettes");
            case "worldstyles" -> {
                ref(src, d.get("outsidestyle"), "styles");
                forEachObject(d.get("citystyles"), selector -> {
                    ref(src, selector.get("citystyle"), "citystyles");
                    JsonObject edge = asObject(selector.get("edge"));
                    if (edge != null) {
                        ref(src, edge.get("citystyle"), "citystyles");
                    }
                });
                JsonObject scattered = asObject(d.get("scattered"));
                if (scattered != null) {
                    forEachObject(scattered.get("list"), e -> ref(src, e.get("name"), "scattered"));
                }
                JsonObject parts = asObject(d.get("parts"));
                if (parts != null) {
                    for (String group : List.of("highways", "railways")) {
                        JsonObject g = asObject(parts.get(group));
                        if (g != null) {
                            for (Map.Entry<String, JsonElement> e : g.entrySet()) {
                                refListOrString(src, e.getValue(), "parts");
                            }
                        }
                    }
                }
            }
            case "scattered" -> {
                forEachElement(d.get("buildings"), v -> ref(src, v, "buildings"));
                ref(src, d.get("multibuilding"), "multibuildings");
            }
            // inpart was already covered; belowpart and inbuilding are the same convention on the
            // same object, and were the two that could be written bare without anything noticing.
            case "conditions" -> forEachObject(d.get("values"), e -> {
                refListOrString(src, e.get("inpart"), "parts");
                refListOrString(src, e.get("belowpart"), "parts");
                refListOrString(src, e.get("inbuilding"), "buildings");
            });
            case "stuff" -> {
                JsonObject buildings = asObject(d.get("buildings"));
                if (buildings != null) {
                    for (String key : List.of("if_any", "if_all", "excluding")) {
                        forEachElement(buildings.get(key), v -> ref(src, v, "buildings"));
                    }
                }
            }
            case "presets" -> {
                // "extends" is already checked unconditionally above, for every category.
                JsonObject spawn = asObject(d.get("spawn"));
                if (spawn != null) {
                    ref(src, spawn.get("spawnCity"), "predefinedcities");
                    forEachElement(spawn.get("forceSpawnBuildings"), v -> ref(src, v, "buildings"));
                    forEachElement(spawn.get("forceSpawnParts"), v -> ref(src, v, "parts"));
                }
                // "icon" is a texture path under assets/urbex, not a data/ asset reference, so it
                // gets its own check rather than ref().
                iconRef(src, d.get("icon"));
            }
            case "palettes", "variants" -> { /* only palette-entry refs, handled below */ }
            // A definitions asset is one node (REF.014), so everything it can reference - a $ref into
            // this registry, a trait naming a conditions pool - is a node reference, and the walk
            // below is the one that reads those. It has no keys of its own beyond "extends", which is
            // checked unconditionally above.
            case "definitions" -> { /* only node refs, handled below */ }
            default -> problems.add(file + ": category '" + category
                    + "' has no reference checks; add a case to this switch");
        }
        walkPaletteEntries(src, d);
    }

    /**
     * Every reference a palette entry can make, in either format, at any depth.
     *
     * <p>Version 1 writes them as keys on an entry of a {@code palette} <em>array</em>:
     * {@code variant} into the variants registry and {@code loot}/{@code mob} into conditions. Version
     * 2 writes the same three as {@code $ref} into the definitions registry and as the {@code pool} of
     * {@code urbex:loot} and {@code urbex:spawner}, on a node that may sit inside {@code $defs}, a
     * choice, a placement list or another trait's satellite — so those two are matched wherever they
     * appear rather than only on an entry, which is also what covers an inline palette in either
     * format without a second walk.</p>
     */
    private void walkPaletteEntries(String src, JsonElement el) {
        if (el == null) {
            return;
        }
        if (el.isJsonObject()) {
            JsonObject node = el.getAsJsonObject();
            definitionRef(src, node.get("$ref"));
            JsonObject traits = asObject(node.get("traits"));
            if (traits != null) {
                TRAIT_REFERENCES.forEach((trait, fields) -> {
                    JsonObject payload = asObject(traits.get(trait));
                    if (payload != null) {
                        fields.forEach((field, category) -> ref(src, payload.get(field), category));
                    }
                });
            }
            for (Map.Entry<String, JsonElement> e : node.entrySet()) {
                if (e.getKey().equals("palette") && e.getValue().isJsonArray()) {
                    forEachObject(e.getValue(), entry -> {
                        ref(src, entry.get("variant"), "variants");
                        ref(src, entry.get("loot"), "conditions");
                        ref(src, entry.get("mob"), "conditions");
                    });
                } else {
                    walkPaletteEntries(src, e.getValue());
                }
            }
        } else if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                walkPaletteEntries(src, e);
            }
        }
    }

    /**
     * A version 2 {@code $ref}, checked only when it names the definitions registry.
     *
     * <p>{@code REF.012}: a name resolves in exactly one tier and the colon decides which. A name
     * without one is this file's own {@code $defs} and there is no file for it to resolve to; a name
     * starting with {@code $} is an import alias or {@code $super} ({@code REF.040}), which this test
     * cannot expand. Both are left to the loader, which refuses them by name with {@code DIAG.030}.
     * A fragment addresses a path inside the asset ({@code REF.005}); the asset is what has to
     * exist.</p>
     */
    private void definitionRef(String src, JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) {
            return;
        }
        String name = el.getAsString();
        if (name.startsWith("$") || name.indexOf(':') < 0) {
            return;
        }
        int fragment = name.indexOf('#');
        ref(src, new com.google.gson.JsonPrimitive(fragment < 0 ? name : name.substring(0, fragment)),
                "definitions");
    }

    private void ref(String src, JsonElement el, String targetCategory) {
        if (el == null || !el.isJsonPrimitive()) {
            return;
        }
        String name = el.getAsString();
        int colon = name.indexOf(':');
        if (colon < 0) {
            problems.add(src + ": bare reference \"" + name + "\" (expected urbex:" + name + ")");
            return;
        }
        String namespace = name.substring(0, colon);
        if (!namespace.equals("urbex")) {
            return; // foreign namespace: not resolvable from this repo, and none are shipped
        }
        Path target = root.resolve(targetCategory).resolve(name.substring(colon + 1) + ".json");
        if (!Files.isRegularFile(target)) {
            problems.add(src + ": \"" + name + "\" does not resolve to " + target);
        }
    }

    /** A preset's "icon" is a path relative to assets/urbex, always in the urbex namespace - not
     *  a data/ registry cross-reference, so this checks the file exists rather than calling ref(). */
    private void iconRef(String src, JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) {
            return;
        }
        String path = el.getAsString();
        Path target = assetsRoot.resolve(path);
        if (!Files.isRegularFile(target)) {
            problems.add(src + ": icon \"" + path + "\" does not resolve to " + target);
        }
    }

    /**
     * One reference, an array of them, or the {@code {"replace": false, "values": [...]}} append
     * form - the third arm every part-wiring field and every mergeable list accepts.
     * <p>
     * The object arm was going unchecked: this method used to fall through on anything that was not
     * a primitive or an array, so a bare or dangling name inside a {@code values} list was invisible
     * to the whole sweep. Nothing bundled writes that form yet, which is exactly why it needed
     * covering before the authoring guide documents it.
     */
    private void refListOrString(String src, JsonElement el, String targetCategory) {
        if (el == null) {
            return;
        }
        if (el.isJsonPrimitive()) {
            ref(src, el, targetCategory);
        } else if (el.isJsonArray()) {
            forEachElement(el, v -> ref(src, v, targetCategory));
        } else if (el.isJsonObject()) {
            refListOrString(src, el.getAsJsonObject().get("values"), targetCategory);
        }
    }

    private static JsonObject asObject(JsonElement el) {
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    /**
     * Every element of a list field, whether it was written as a bare array or as the
     * {@code {"replace": false, "values": [...]}} append form - which {@code citystyles} and every
     * {@code selectors.*} list accept, and which the array-only version of this silently skipped.
     */
    private static void forEachElement(JsonElement el, java.util.function.Consumer<JsonElement> fn) {
        if (el == null) {
            return;
        }
        if (el.isJsonObject()) {
            forEachElement(el.getAsJsonObject().get("values"), fn);
            return;
        }
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                fn.accept(e);
            }
        }
    }

    private static void forEachObject(JsonElement el, java.util.function.Consumer<JsonObject> fn) {
        forEachElement(el, e -> {
            if (e.isJsonObject()) {
                fn.accept(e.getAsJsonObject());
            }
        });
    }
}
