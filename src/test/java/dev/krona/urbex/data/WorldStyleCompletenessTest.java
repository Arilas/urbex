package dev.krona.urbex.data;

import dev.krona.urbex.worldgen.lost.cityassets.TestAssetId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.cityassets.Resolved;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.HighwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.RailwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.StreetParts;
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
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every part-wiring field the bundled pack can reach is wired by the bundled pack.
 * <p>
 * Since the thirty code-side defaults were deleted, a field no file declares is a load error rather
 * than a silent fallback to Urbex's own parts - so this is the build-time half of that rule. It
 * walks each world style, every city style anything in the pack can select, and all their
 * {@code extends} chains, and resolves every one of them through the constructors the game itself
 * uses. A shipped file that stops declaring a family fails here, at build time, instead of at
 * someone's world load.
 * <p>
 * "Anything can select" is the routes {@code AssetCompiler.reachableCityStyles} enumerates - a
 * world style's base and edge {@code citystyles} selections, and a predefined city's
 * {@code citystyle}.
 * <p>
 * It asserts on the <em>union</em> over a chain rather than on any single file, because that is the
 * rule: {@code citystyle_border} declares no {@code parts} of its own and correctly takes
 * {@code citystyle_common}'s. Resolving the real chain is what makes that automatic, and it is also
 * why the failure wording is {@link Resolved#require}'s rather than this test's - an author who
 * trips the same rule in their own pack has already read that sentence.
 * <p>
 * An empty list counts as unwired here, and what that means differs by family. For the three street
 * families an empty list is a real opt-out during generation - {@code CityGenerator} tests each of
 * them with {@code isEmpty()} before drawing (the {@code parts.stair()} guard at
 * {@code CityGenerator.java:1631} and the per-shape guards at {@code CityGenerator.java:1653-1703}) -
 * so for those this test is deliberately one notch stricter than the runtime rule, on the grounds
 * that the bundled pack has no reason to ship one. For highways and railways it is not a stricter
 * rule at all but the only check there is: {@code Highways.java:53,58,71} and
 * {@code Railways.java:50} hand the list straight to {@code getRandomPart} with no emptiness guard,
 * so {@code "tunnel": []} is a crash during generation. What this task's load-time
 * {@code Resolved.require} guarantees is <em>non-null</em>, not usable; the gap between the two is
 * this test, and only for the bundled pack.
 * <p>
 * A city style nothing names is not swept, which is why {@code citystyle_config} - a base that exists
 * to be extended and declares only a street width - is not a failure.
 */
class WorldStyleCompletenessTest {

    private static final Path ROOT = Path.of("src/main/resources/data/urbex/urbex");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * The bundled border is a member of each selected world-style family, not a preset override.
     * Keeping this data assertion beside the reachability sweep means removing either nested edge
     * cannot quietly make the intentionally sparse border style unreachable again.
     */
    @Test
    void bundledStandardSelectsTheBorderAsEachFamilyEdge() throws IOException {
        JsonObject standard = read(ROOT.resolve("worldstyles/standard.json"));
        List<JsonElement> selectors = standard.getAsJsonArray("citystyles").asList();

        assertEquals(2, selectors.size(), "urbex:standard must declare its standard and desert families");
        for (JsonElement entry : selectors) {
            JsonObject edge = entry.getAsJsonObject().getAsJsonObject("edge");
            assertEquals("urbex:citystyle_border", edge.get("citystyle").getAsString());
            assertEquals(0.4f, edge.get("threshold").getAsFloat());
        }
        assertEquals(List.of("urbex:citystyle_standard", "urbex:citystyle_border",
                        "urbex:citystyle_desert", "urbex:citystyle_border"),
                cityStyleRefs(ROOT.resolve("worldstyles/standard.json")),
                "the reachability sweep must find both border edges without a preset route");
    }

    @Test
    void everyReachableWorldStyleAndCityStyleWiresEveryPartFamily() throws IOException {
        List<Path> worldStyles;
        try (Stream<Path> files = Files.walk(ROOT.resolve("worldstyles"))) {
            worldStyles = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(worldStyles.isEmpty(), "no world styles found under " + ROOT);

        Set<String> cityStylesNamed = new LinkedHashSet<>();
        for (Path file : worldStyles) {
            List<Path> chain = chainRootFirst("worldstyles", file);
            List<WorldStyleDefinition> entries = new ArrayList<>();
            for (Path link : chain) {
                entries.add(WorldStyleDefinition.CODEC.parse(JsonOps.INSTANCE, read(link)).getOrThrow());
                cityStylesNamed.addAll(cityStyleRefs(link));
            }
            requireWorldStyleWiring(new WorldStyle(TestAssetId.ANY, entries));
        }

        cityStylesNamed.addAll(namedIn("predefinedcities", null, "citystyle"));

        assertFalse(cityStylesNamed.isEmpty(), "no world style names a city style; the sweep found nothing");
        for (String name : cityStylesNamed) {
            Path file = fileFor("citystyles", name);
            assertTrue(Files.isRegularFile(file), name + " does not resolve to " + file);
            List<CityStyleDefinition> entries = new ArrayList<>();
            for (Path link : chainRootFirst("citystyles", file)) {
                entries.add(CityStyleDefinition.CODEC.parse(JsonOps.INSTANCE, read(link)).getOrThrow());
            }
            requireCityStyleWiring(new CityStyle(TestAssetId.ANY, entries));
        }
    }

    private static void requireWorldStyleWiring(WorldStyle style) {
        Identifier owner = style.getId();
        HighwayParts highways = style.getPartSelector().highwayParts();
        requireWired(owner, "parts.highways.tunnel", highways.tunnel());
        requireWired(owner, "parts.highways.open", highways.open());
        requireWired(owner, "parts.highways.bridge", highways.bridge());
        requireWired(owner, "parts.highways.tunnel_bi", highways.tunnelBi());
        requireWired(owner, "parts.highways.open_bi", highways.openBi());
        requireWired(owner, "parts.highways.bridge_bi", highways.bridgeBi());

        RailwayParts railways = style.getPartSelector().railwayParts();
        requireWired(owner, "parts.railways.stationunderground", railways.stationUnderground());
        requireWired(owner, "parts.railways.stationopen", railways.stationOpen());
        requireWired(owner, "parts.railways.stationopenroof", railways.stationOpenRoof());
        requireWired(owner, "parts.railways.stationundergroundstairs", railways.stationUndergroundStairs());
        requireWired(owner, "parts.railways.stationstaircase", railways.stationStaircase());
        requireWired(owner, "parts.railways.stationstaircasesurface", railways.stationStaircaseSurface());
        requireWired(owner, "parts.railways.railshorizontal", railways.railsHorizontal());
        requireWired(owner, "parts.railways.railshorizontalend", railways.railsHorizontalEnd());
        requireWired(owner, "parts.railways.railshorizontalwater", railways.railsHorizontalWater());
        requireWired(owner, "parts.railways.railsvertical", railways.railsVertical());
        requireWired(owner, "parts.railways.railsverticalwater", railways.railsVerticalWater());
        requireWired(owner, "parts.railways.rails3split", railways.rails3Split());
        requireWired(owner, "parts.railways.railsbend", railways.railsBend());
        requireWired(owner, "parts.railways.railsflat", railways.railsFlat());
        requireWired(owner, "parts.railways.railsdown1", railways.railsDown1());
        requireWired(owner, "parts.railways.railsdown2", railways.railsDown2());
    }

    /**
     * The three families a road class can draw from, taken from the getters rather than the fields:
     * primary and tertiary roads fall back to the secondary family when nothing declares them, and
     * those getters are exactly what {@code CityGenerator.getStreetParts} hands to generation, so
     * reading them is what puts the fallback inside what this asserts.
     */
    private static void requireCityStyleWiring(CityStyle style) {
        requireFamily(style.getId(), "streetblocks.parts", style.getStreetParts());
        requireFamily(style.getId(), "streetblocks.largeparts", style.getLargeStreetParts());
        requireFamily(style.getId(), "streetblocks.tertiaryparts", style.getTertiaryStreetParts());
    }

    private static void requireFamily(Identifier owner, String field, StreetParts parts) {
        requireWired(owner, field + ".straight", parts.straight());
        requireWired(owner, field + ".end", parts.end());
        requireWired(owner, field + ".bend", parts.bend());
        requireWired(owner, field + ".t", parts.t());
        requireWired(owner, field + ".none", parts.none());
        requireWired(owner, field + ".all", parts.all());
        requireWired(owner, field + ".connector", parts.connector());
        requireWired(owner, field + ".stair", parts.stair());
    }

    /** Fails in {@link Resolved#require}'s wording, so an author sees one convention, not two. */
    private static void requireWired(Identifier owner, String field, List<String> parts) {
        Resolved.require(parts == null || parts.isEmpty() ? null : parts, owner, field);
    }

    /** The {@code extends} chain of {@code file}, root first - the order the constructors expect. */
    private static List<Path> chainRootFirst(String category, Path file) throws IOException {
        List<Path> chain = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        Path current = file;
        while (current != null) {
            assertTrue(seen.add(current), "extends cycle through " + current);
            chain.add(0, current);
            JsonElement extendsId = read(current).get("extends");
            current = extendsId == null ? null : fileFor(category, extendsId.getAsString());
        }
        return chain;
    }

    /**
     * Every city style named by {@code key} (optionally nested under {@code section}) in every file
     * of {@code category} - routes 2 and 3.
     * <p>
     * Read per file rather than through each asset's {@code extends} chain: a value declared
     * anywhere in a chain is a value something in that chain resolves to, so the union over raw
     * files is a superset of what any resolution can produce, never a subset. A category the pack
     * does not ship yields nothing, which is the {@code predefinedcities} case today.
     */
    private static List<String> namedIn(String category, String section, String key) throws IOException {
        List<String> refs = new ArrayList<>();
        Path dir = ROOT.resolve(category);
        if (!Files.isDirectory(dir)) {
            return refs;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonElement holder = read(file);
                if (section != null) {
                    holder = holder.getAsJsonObject().get(section);
                }
                if (holder != null && holder.isJsonObject()) {
                    JsonElement named = holder.getAsJsonObject().get(key);
                    if (named != null && named.isJsonPrimitive()) {
                        refs.add(named.getAsString());
                    }
                }
            }
        }
        return refs;
    }

    /** Every base and optional edge city style one selector names, whether it replaces or appends. */
    private static List<String> cityStyleRefs(Path file) throws IOException {
        List<String> refs = new ArrayList<>();
        JsonElement citystyles = read(file).get("citystyles");
        JsonElement entries = citystyles != null && citystyles.isJsonObject()
                ? citystyles.getAsJsonObject().get("values")   // the {"replace": false, ...} form
                : citystyles;
        if (entries != null && entries.isJsonArray()) {
            for (JsonElement entry : entries.getAsJsonArray()) {
                JsonObject selector = entry.getAsJsonObject();
                refs.add(selector.get("citystyle").getAsString());
                if (selector.has("edge")) {
                    refs.add(selector.getAsJsonObject("edge").get("citystyle").getAsString());
                }
            }
        }
        return refs;
    }

    private static Path fileFor(String category, String name) {
        return ROOT.resolve(category).resolve(name.substring(name.indexOf(':') + 1) + ".json");
    }

    private static JsonObject read(Path file) throws IOException {
        JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        emptyBiomeSets(json);
        return json;
    }

    /**
     * Replaces every {@code "biomes"} value with an empty object, in place.
     * <p>
     * {@code BiomeMatcher.CODEC} decodes through {@code Biome.LIST_CODEC}, which resolves ids and
     * {@code #tags} against a live biome registry and so needs a {@code RegistryOps} rather than
     * plain {@code JsonOps}; a headless decode of the shipped files fails on
     * {@code "#minecraft:is_river"} before it ever reaches a part list. Every field of
     * {@code BiomeMatcher} is optional, so an empty object decodes to a matcher with no sets - which
     * is all this test needs, since which biome a city style applies to has no bearing on whether
     * its part wiring is declared. Nothing here calls {@code test()} on the result.
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
            element.getAsJsonArray().forEach(WorldStyleCompletenessTest::emptyBiomeSets);
        }
    }

    private static Identifier idOf(Path file) {
        String name = file.getFileName().toString();
        return Identifier.fromNamespaceAndPath("urbex", name.substring(0, name.length() - ".json".length()));
    }
}
