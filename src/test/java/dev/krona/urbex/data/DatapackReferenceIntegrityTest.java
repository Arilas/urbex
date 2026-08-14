package dev.krona.urbex.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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

    /** target category (directory under ROOT) -> collected [sourceFile, reference] pairs */
    private final List<String> problems = new ArrayList<>();

    @Test
    void allDatapackReferencesAreNamespacedAndResolve() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(this::checkFile);
        }
        assertTrue(problems.isEmpty(),
                () -> problems.size() + " bad datapack references:\n" + String.join("\n", problems));
    }

    private void checkFile(Path file) {
        String category = ROOT.relativize(file).getName(0).toString();
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
                forEachObject(d.get("citystyles"), e -> ref(src, e.get("citystyle"), "citystyles"));
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
            default -> problems.add(file + ": category '" + category
                    + "' has no reference checks; add a case to this switch");
        }
        walkPaletteEntries(src, d);
    }

    /** Any "palette" array anywhere: entries may reference variants ("variant") and conditions ("loot"/"mob"). */
    private void walkPaletteEntries(String src, JsonElement el) {
        if (el == null) {
            return;
        }
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
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
        Path target = ROOT.resolve(targetCategory).resolve(name.substring(colon + 1) + ".json");
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
        Path target = ASSETS_ROOT.resolve(path);
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
