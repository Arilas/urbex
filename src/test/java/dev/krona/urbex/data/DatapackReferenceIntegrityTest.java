package dev.krona.urbex.data;

import com.google.gson.JsonArray;
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
 * resolve to an existing asset file. Bare names would still work at runtime (they default to
 * the urbex namespace), but the shipped pack is fully qualified so it stays unambiguous next
 * to third-party datapacks — the same convention LCMT uses. The field map here mirrors
 * docs/superpowers/specs/2026-08-10-cleanup-dimension-worldstyle-namespacing-design.md.
 */
class DatapackReferenceIntegrityTest {

    private static final Path ROOT = Path.of("src/main/resources/data/urbex/urbex");

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
        switch (category) {
            case "buildings" -> {
                for (String key : List.of("parts", "parts2")) {
                    forEachObject(d.get(key), e -> ref(src, e.get("part"), "parts"));
                }
            }
            case "multibuildings" -> forEachElement(d.get("buildings"),
                    row -> forEachElement(row, cell -> ref(src, cell, "buildings")));
            case "styles" -> forEachElement(d.get("randompalettes"),
                    row -> forEachObject(row, e -> ref(src, e.get("palette"), "palettes")));
            case "citystyles" -> {
                ref(src, d.get("inherit"), "citystyles");
                ref(src, d.get("style"), "styles");
                JsonObject sel = asObject(d.get("selectors"));
                if (sel != null) {
                    for (Map.Entry<String, String> e : Map.of(
                            "buildings", "buildings", "multibuildings", "multibuildings",
                            "bridges", "parts", "parks", "parts", "fountains", "parts",
                            "stairs", "parts", "fronts", "parts", "raildungeons", "parts").entrySet()) {
                        forEachObject(sel.get(e.getKey()), o -> ref(src, o.get("value"), e.getValue()));
                    }
                }
                JsonObject sb = asObject(d.get("streetblocks"));
                JsonObject sbParts = sb == null ? null : asObject(sb.get("parts"));
                if (sbParts != null) {
                    for (Map.Entry<String, JsonElement> e : sbParts.entrySet()) {
                        forEachElement(e.getValue(), v -> ref(src, v, "parts"));
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
                    JsonObject mono = asObject(parts.get("monorails"));
                    if (mono != null) {
                        for (Map.Entry<String, JsonElement> e : mono.entrySet()) {
                            ref(src, e.getValue(), "parts");
                        }
                    }
                    for (String group : List.of("highways", "railways")) {
                        JsonObject g = asObject(parts.get(group));
                        if (g != null) {
                            for (Map.Entry<String, JsonElement> e : g.entrySet()) {
                                forEachElement(e.getValue(), v -> ref(src, v, "parts"));
                            }
                        }
                    }
                }
            }
            case "scattered" -> {
                forEachElement(d.get("buildings"), v -> ref(src, v, "buildings"));
                ref(src, d.get("multibuilding"), "multibuildings");
            }
            case "conditions" -> forEachObject(d.get("values"), e -> ref(src, e.get("inpart"), "parts"));
            case "stuff" -> {
                JsonObject buildings = asObject(d.get("buildings"));
                if (buildings != null) {
                    for (String key : List.of("if_any", "if_all", "excluding")) {
                        forEachElement(buildings.get(key), v -> ref(src, v, "buildings"));
                    }
                }
            }
            default -> { /* variants and future categories: only palette-entry refs below */ }
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

    private static JsonObject asObject(JsonElement el) {
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static void forEachElement(JsonElement el, java.util.function.Consumer<JsonElement> fn) {
        if (el != null && el.isJsonArray()) {
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
