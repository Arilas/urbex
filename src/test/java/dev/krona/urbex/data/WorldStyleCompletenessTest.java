package dev.krona.urbex.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.cityassets.Resolved;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleRE;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleRE;
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
 * "Anything can select" is the same three routes {@code AssetRegistries.loadReachableCityStyles}
 * enumerates, and the second one is why this is not just the world styles' own lists: the bundled
 * {@code citystyle_border} is named by no world style at all, only by
 * {@code presets/largecities.json}'s {@code cities.cityStyleAlternative}. It generates real cities,
 * and before that route was swept it had no completeness check at build time or at load time - it
 * passed only by inheriting {@code citystyle_common}'s wiring.
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
            List<WorldStyleRE> entries = new ArrayList<>();
            for (Path link : chain) {
                entries.add(WorldStyleRE.CODEC.parse(JsonOps.INSTANCE, read(link)).getOrThrow()
                        .setRegistryName(idOf(link)));
                cityStylesNamed.addAll(cityStyleRefs(link));
            }
            requireWorldStyleWiring(new WorldStyle(entries));
        }

        cityStylesNamed.addAll(cityStyleAlternatives());

        assertFalse(cityStylesNamed.isEmpty(), "no world style names a city style; the sweep found nothing");
        for (String name : cityStylesNamed) {
            Path file = fileFor("citystyles", name);
            assertTrue(Files.isRegularFile(file), name + " does not resolve to " + file);
            List<CityStyleRE> entries = new ArrayList<>();
            for (Path link : chainRootFirst("citystyles", file)) {
                entries.add(CityStyleRE.CODEC.parse(JsonOps.INSTANCE, read(link)).getOrThrow()
                        .setRegistryName(idOf(link)));
            }
            requireCityStyleWiring(new CityStyle(entries));
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
     * Every {@code cities.cityStyleAlternative} the bundled presets name.
     * <p>
     * Read per file rather than through each preset's {@code extends} chain: a value declared
     * anywhere in a chain is a value some preset resolves to, so the union over the raw files is the
     * same set of city styles. Same extraction as {@code DatapackReferenceIntegrityTest} does for
     * namespacing, which is what makes this route findable at all - the preset directory has no
     * other tie to city styles.
     */
    private static List<String> cityStyleAlternatives() throws IOException {
        List<String> refs = new ArrayList<>();
        Path presets = ROOT.resolve("presets");
        if (!Files.isDirectory(presets)) {
            return refs;
        }
        try (Stream<Path> files = Files.walk(presets)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonElement cities = read(file).get("cities");
                if (cities != null && cities.isJsonObject()) {
                    JsonElement alternative = cities.getAsJsonObject().get("cityStyleAlternative");
                    if (alternative != null && alternative.isJsonPrimitive()) {
                        refs.add(alternative.getAsString());
                    }
                }
            }
        }
        return refs;
    }

    /** Every {@code citystyles[].citystyle} one file names, whether it replaces or appends. */
    private static List<String> cityStyleRefs(Path file) throws IOException {
        List<String> refs = new ArrayList<>();
        JsonElement citystyles = read(file).get("citystyles");
        JsonElement entries = citystyles != null && citystyles.isJsonObject()
                ? citystyles.getAsJsonObject().get("values")   // the {"replace": false, ...} form
                : citystyles;
        if (entries != null && entries.isJsonArray()) {
            for (JsonElement entry : entries.getAsJsonArray()) {
                JsonElement ref = entry.getAsJsonObject().get("citystyle");
                if (ref != null) {
                    refs.add(ref.getAsString());
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
