package dev.krona.urbex.worldgen.lost.cityassets;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.regassets.BuildingDefinition;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.ConditionDefinition;
import dev.krona.urbex.worldgen.lost.regassets.MultiBuildingDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PredefinedCityDefinition;
import dev.krona.urbex.worldgen.lost.regassets.ScatteredDefinition;
import dev.krona.urbex.worldgen.lost.regassets.StuffSettingsDefinition;
import dev.krona.urbex.worldgen.lost.regassets.StyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleEdge;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.ConditionPart;
import dev.krona.urbex.worldgen.lost.regassets.data.IdentifierMatcher;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.PartRef;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.ScatteredSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.TestWiring;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two-entry-chain coverage for the registries where a wrong fold is silent rather than loud.
 * <p>
 * The bundled datapack uses {@code extends} only in {@code citystyles} and {@code presets}, so
 * every other registry only ever resolves a chain of one in the digest runs and in gameplay -
 * {@link StuffSettingsDefinition#resolve} even short-circuits that case. Without these tests the fold
 * bodies would never execute anywhere.
 * <p>
 * {@code stuff} is the dangerous one: {@code AssetRegistries.load} files each {@link StuffObject}
 * into the {@code stuffIndex()} tag index and {@code Stuff} reads back by tag, so a fold that dropped
 * inherited tags would produce decoration that simply never spawns - no exception, no log line, and
 * nothing a digest would catch.
 * <p>
 * The load error a chain raises when <em>nothing</em> in it declares a required field lives in
 * {@link RequiredAfterResolutionTest}; what is asserted here is the other direction, that a child
 * which declares one field keeps everything else its ancestors set.
 */
class RegistryChainResolutionTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---------------------------------------------------------------- stuff

    @Test
    void stuffThatDeclaresNoTagsKeepsItsAncestors() {
        // The silent-damage case: dropping these would unfile the object from the tag index and it
        // would never be selected for placement again.
        StuffObject resolved = new StuffObject(TestAssetId.ANY, List.of(
                stuff("torches").tags(true, "all", "indoor").build(),
                stuff("torches_rare").build()));

        assertEquals(List.of("all", "indoor"), resolved.getSettings().getTags());
    }

    @Test
    void stuffTagsReplaceByDefaultAndAppendWhenTheChildOptsIn() {
        StuffObject replaced = new StuffObject(TestAssetId.ANY, List.of(
                stuff("torches").tags(true, "all", "indoor").build(),
                stuff("torches_rare").tags(true, "rare").build()));
        assertEquals(List.of("rare"), replaced.getSettings().getTags(), "a bare array replaces");

        StuffObject appended = new StuffObject(TestAssetId.ANY, List.of(
                stuff("torches").tags(true, "all", "indoor").build(),
                stuff("torches_rare").tags(false, "rare").build()));
        assertEquals(List.of("all", "indoor", "rare"), appended.getSettings().getTags(),
                "{\"replace\": false} keeps the inherited tags and adds after them");
    }

    @Test
    void stuffInheritsEveryOptionalScalarTheChildOmits() {
        IdentifierMatcher onlyLibraries = new IdentifierMatcher(
                Optional.of(List.of("urbex:library")), Optional.empty());
        StuffSettingsDefinition parent = stuff("torches")
                .tags(true, "all")
                .minheight(40).maxheight(90)
                .inbuilding(true).seesky(false)
                .buildings(onlyLibraries)
                .build();
        StuffSettingsDefinition child = stuff("torches_rare").column("XX").counts(2, 9, 7)
                .undeclaredInbuilding().build();

        StuffSettingsDefinition resolved = new StuffObject(TestAssetId.ANY, List.of(parent, child)).getSettings();

        assertNotSame(child, resolved, "a two-entry chain must actually fold, not hand back the leaf");
        assertEquals(40, resolved.getMinheight(), "minheight is inherited");
        assertEquals(90, resolved.getMaxheight(), "maxheight is inherited");
        assertEquals(true, resolved.isInBuilding(), "inbuilding is inherited");
        assertEquals(Boolean.FALSE, resolved.isSeesky(),
                "seesky is inherited - and 'declared false' must not read as 'undeclared'");
        assertSame(onlyLibraries, resolved.getBuildingMatcher(), "the matcher is inherited");
        assertSame(IdentifierMatcher.ANY,
                new StuffObject(TestAssetId.of("plain"), List.of(stuff("plain").build(), stuff("plain_child").build()))
                        .getSettings().getBuildingMatcher(),
                "a matcher no entry in the chain declares still reads as ANY");
    }

    @Test
    void stuffRequiredScalarsComeFromTheLeaf() {
        StuffSettingsDefinition resolved = new StuffObject(TestAssetId.ANY, List.of(
                stuff("torches").column("AB").counts(1, 2, 3).build(),
                stuff("torches_rare").column("CD").counts(4, 5, 6).build())).getSettings();

        assertEquals("CD", resolved.getColumn());
        assertEquals(4, resolved.getMincount());
        assertEquals(5, resolved.getMaxcount());
        assertEquals(6, resolved.getAttempts());
    }

    @Test
    void stuffResolvedFromAChainOfOneIsTheEntryItself() {
        StuffSettingsDefinition only = stuff("torches").tags(true, "all").build();

        assertSame(only, new StuffObject(TestAssetId.ANY, List.of(only)).getSettings());
    }

    // ------------------------------------------- stuff ordering (RNG addresses)

    /**
     * {@code Stuff.generateStuff} walks each tag's list assigning the {@code stuffOrdinal} that
     * addresses the RNG slot every placement attempt of that decoration draws from, so the position
     * of every chain and cobweb in the world depends on this list's order. The source is
     * {@code STUFF.getIterable()}, a {@code ConcurrentHashMap}'s values - {@code Identifier}
     * hash-bucket order - which is exactly what must not reach an RNG address.
     */
    @Test
    void stuffFiledUnderATagIsOrderedByIdRatherThanByArrivalOrder() {
        StuffObject cobweb = new StuffObject(TestAssetId.of("cobweb"), List.of(stuff("cobweb").tags(true, "rubble").build()));
        StuffObject chains = new StuffObject(TestAssetId.of("chains"), List.of(stuff("chains").tags(true, "rubble").build()));

        // Deliberately the order AssetRegistries' ConcurrentHashMap actually hands these two over
        // in - hash("urbex:cobweb") lands in a lower bucket than hash("urbex:chains") - so this
        // fails if the sort is ever dropped, rather than passing by luck of the input order.
        assertEquals(List.of("urbex:chains", "urbex:cobweb"),
                namesOf(AssetCompiler.groupStuffByTag(List.of(cobweb, chains)).get("rubble")));
    }

    @Test
    void stuffOrderIsPathFirstThenNamespace() {
        // Identifier's own order, the same one MultiChunk sorts city styles by and ChunkPlan
        // breaks its cityStyle vote on. Ordering on toString() instead would put both urbex entries
        // ahead of the third-party one and silently relocate its decoration the day it is installed.
        StuffObject ownRope = new StuffObject(Identifier.fromNamespaceAndPath("urbex", "rope"), List.of(stuff("urbex", "rope").tags(true, "rubble").build()));
        StuffObject ownVines = new StuffObject(Identifier.fromNamespaceAndPath("urbex", "vines"), List.of(stuff("urbex", "vines").tags(true, "rubble").build()));
        StuffObject thirdPartySoot = new StuffObject(Identifier.fromNamespaceAndPath("urbexmt", "soot"), List.of(stuff("urbexmt", "soot").tags(true, "rubble").build()));

        assertEquals(List.of("urbex:rope", "urbexmt:soot", "urbex:vines"),
                namesOf(AssetCompiler.groupStuffByTag(List.of(ownVines, thirdPartySoot, ownRope)).get("rubble")));
    }

    @Test
    void stuffWithSeveralTagsIsFiledUnderEachOfThem() {
        StuffObject both = new StuffObject(TestAssetId.of("torches"), List.of(stuff("torches").tags(true, "all", "indoor").build()));
        StuffObject indoorOnly = new StuffObject(TestAssetId.of("banners"), List.of(stuff("banners").tags(true, "indoor").build()));

        Map<String, List<StuffObject>> byTag = AssetCompiler.groupStuffByTag(List.of(both, indoorOnly));

        assertEquals(List.of("all", "indoor"), List.copyOf(byTag.keySet()),
                "the tag map is sorted too - Stuff walks the tags in one pass, so their order is "
                        + "part of the same running ordinal");
        assertEquals(List.of("urbex:torches"), namesOf(byTag.get("all")));
        assertEquals(List.of("urbex:banners", "urbex:torches"), namesOf(byTag.get("indoor")));
    }

    private static List<String> namesOf(List<StuffObject> stuff) {
        return stuff.stream().map(StuffObject::getName).toList();
    }

    // ------------------------------------------ city style tag order (RNG addresses)

    /**
     * The other half of the stuff ordinal: {@code Stuff.generateStuff} iterates
     * {@code CityStyle.getStuffTags()} and keeps counting across tags, so the tag order is as much
     * an RNG address as the per-tag list order. Under the former {@code HashSet} it was String
     * hash-bucket order, which meant adding one tag to a city style relocated the decoration filed
     * under all the others.
     */
    @Test
    void cityStyleStuffTagsIterateInASortedOrderRegardlessOfHowTheyWereDeclared() {
        CityStyle style = new CityStyle(TestAssetId.ANY, List.of(
                cityStyleWithTags("indoor", "rubble"),
                cityStyleWithTags("banners", "cobbles")));

        assertEquals(List.of("all", "banners", "cobbles", "indoor", "rubble"),
                List.copyOf(style.getStuffTags()),
                "'all' is added by the constructor and sorts in with the rest");
    }

    private static CityStyleDefinition cityStyleWithTags(String... tags) {
        return new CityStyleDefinition(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(List.of(tags)),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(TestWiring.streetSettings()), Optional.empty());
    }

    // ----------------------------------------------------------- conditions

    @Test
    void conditionValuesReplaceByDefaultAndAppendWhenTheChildOptsIn() {
        ConditionDefinition parent = condition("loot", true, "urbex:common_loot", "urbex:rare_loot");

        assertEquals(Set.of("urbex:barrel_loot"),
                valuesOf(new Condition(TestAssetId.ANY, List.of(parent, condition("loot_barrel", true, "urbex:barrel_loot")))),
                "a bare array replaces");
        assertEquals(Set.of("urbex:common_loot", "urbex:rare_loot", "urbex:barrel_loot"),
                valuesOf(new Condition(TestAssetId.ANY, List.of(parent, condition("loot_barrel", false, "urbex:barrel_loot")))),
                "{\"replace\": false} keeps the inherited values");
    }

    // --------------------------------------------------------------- styles

    @Test
    void styleThatDeclaresNoPalettesKeepsItsAncestors() {
        Style resolved = new Style(TestAssetId.ANY, PALETTES, List.of(
                style("nordic", group("urbex:common")),
                style("nordic_rare")));

        assertEquals(List.of(List.of("urbex:common")), paletteNamesOf(resolved),
                "a child that only renames its ancestor still paints from it");
    }

    @Test
    void stylePalettesReplaceByDefaultAndAppendWhenTheChildOptsIn() {
        StyleDefinition parent = style("nordic", group("urbex:common"));

        assertEquals(List.of(List.of("urbex:snowy")),
                paletteNamesOf(new Style(TestAssetId.ANY, PALETTES, List.of(parent, style("nordic_snow", group("urbex:snowy"))))),
                "a bare array replaces");
        assertEquals(List.of(List.of("urbex:common"), List.of("urbex:snowy")),
                paletteNamesOf(new Style(TestAssetId.ANY, PALETTES, List.of(parent, styleAppending("nordic_snow", group("urbex:snowy"))))),
                "{\"replace\": false} keeps the inherited groups, in order");
    }

    // ------------------------------------------------------------ scattered

    @Test
    void scatteredChildInheritsTheTerrainHandlingItDoesNotDeclare() {
        ScatteredDefinition parent = new ScatteredDefinition(Optional.empty(),
                Optional.of(new Mergeable<>(true, List.of("urbex:oilrig"))), Optional.empty(),
                Optional.empty(),
                Optional.of(ScatteredBuilding.TerrainHeight.OCEAN),
                Optional.of(ScatteredBuilding.TerrainFix.CLEAR),
                Optional.of(3));
        ScatteredDefinition child = new ScatteredDefinition(Optional.empty(),
                Optional.of(new Mergeable<>(true, List.of("urbex:oilrig_burnt"))), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        ScatteredBuilding resolved = new ScatteredBuilding(TestAssetId.ANY, List.of(parent, child));

        assertEquals(ScatteredBuilding.TerrainHeight.OCEAN, resolved.getTerrainheight());
        assertEquals(ScatteredBuilding.TerrainFix.CLEAR, resolved.getTerrainfix());
        assertEquals(3, resolved.getHeightoffset(), "and the optional scalar with it");
        assertEquals(List.of("urbex:oilrig_burnt"), resolved.getBuildings(),
                "what the child does declare still wins");
    }

    // ----------------------------------------------------- predefinedcities

    @Test
    void predefinedCityChildInheritsEverythingButThePositionItMoves() {
        PredefinedCityDefinition parent = new PredefinedCityDefinition(Optional.empty(),
                Optional.of("minecraft:overworld"), Optional.of(10), Optional.of(20), Optional.of(7),
                Optional.of("urbex:citystyle_common"),
                Optional.of(new Mergeable<>(true,
                        List.of(new PredefinedBuilding("urbex:townhall", 0, 0, false, false)))),
                Optional.empty());
        PredefinedCityDefinition child = new PredefinedCityDefinition(Optional.empty(),
                Optional.empty(), Optional.of(100), Optional.of(200), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        PredefinedCity resolved = new PredefinedCity(TestAssetId.ANY, List.of(parent, child));

        assertEquals(100, resolved.getChunkX(), "the child moves the city");
        assertEquals(200, resolved.getChunkZ());
        assertEquals(7, resolved.getRadius(), "and inherits the rest");
        assertEquals("urbex:citystyle_common", resolved.getCityStyle());
        assertEquals(ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld")), resolved.getDimension());
        assertEquals(List.of("urbex:townhall"), resolved.getPredefinedBuildings().stream()
                .map(PredefinedBuilding::building).toList());
    }

    // ---------------------------------------------------------- worldstyles

    @Test
    void worldStyleChildInheritsTheSelectorsAndSettingsItDoesNotDeclare() {
        ScatteredSettings scattered = new ScatteredSettings(24, 0.25f, 4, List.of());
        WorldStyleDefinition parent = new WorldStyleDefinition(Optional.empty(), Optional.empty(), Optional.of("urbex:standard"),
                Optional.empty(), Optional.empty(), Optional.of(scattered),
                Optional.of(TestWiring.partSelector()),
                Optional.of(new Mergeable<>(true,
                        List.of(new CityStyleSelector(1.0f, "urbex:citystyle_common", null,
                                Optional.of(new CityStyleEdge("urbex:citystyle_edge", 0.4f)))))),
                Optional.empty(), Optional.empty());
        WorldStyleDefinition child = new WorldStyleDefinition(Optional.empty(), Optional.empty(), Optional.of("urbex:bleak"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        WorldStyle resolved = new WorldStyle(TestAssetId.ANY, List.of(parent, child));

        assertEquals("urbex:bleak", resolved.getOutsideStyle(), "what the child declares wins");
        assertSame(scattered, resolved.getScatteredSettings(), "the settings block is inherited");
        assertEquals(List.of("urbex:citystyle_common"), resolved.cityStyleSelectors().stream()
                .map(pair -> pair.getRight().getRight().citystyle()).toList());
        assertEquals("urbex:citystyle_edge", resolved.cityStyleSelectors().getFirst().getRight().getRight()
                .edge().orElseThrow().citystyle(), "the edge remains atomic with its selector entry");
    }

    // -------------------------------------------------------- multibuildings

    @Test
    void multiBuildingChildInheritsTheGridItDoesNotDeclare() {
        MultiBuildingDefinition parent = new MultiBuildingDefinition(Optional.empty(),
                Optional.of(1), Optional.of(2),
                Optional.of(List.of(List.of("urbex:oilrig00", "urbex:oilrig01"))));
        MultiBuildingDefinition child = new MultiBuildingDefinition(Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        MultiBuilding resolved = new MultiBuilding(TestAssetId.ANY, List.of(parent, child));

        assertEquals(1, resolved.getDimX());
        assertEquals(2, resolved.getDimZ());
        assertEquals("urbex:oilrig01", resolved.getBuilding(0, 1));
        assertEquals(Set.of("urbex:oilrig00", "urbex:oilrig01"), resolved.getBuildingSet());
    }

    // ------------------------------------------------------------ buildings

    @Test
    void buildingChildInheritsTheFillerAndPartsItDoesNotDeclare() {
        Building resolved = new Building(TestAssetId.ANY, BuiltInRegistries.BLOCK, PALETTES, List.of(
                building("library").filler("#").parts("urbex:library_floor").minFloors(2).build(),
                building("library_burnt").rubble("R").build()));

        assertEquals('#', resolved.getFillerBlock(), "filler comes from the chain");
        assertEquals(2, resolved.getMinFloors());
        assertEquals(Character.valueOf('R'), resolved.getRubbleBlock());
        assertEquals("urbex:library_floor", resolved.getRandomPart(RandomSource.create(1L), context()),
                "and so do the parts, which the child never restates");
    }

    @Test
    void buildingChildCanSetAnInheritedPrefersLonelyBackToZero() {
        Building resolved = new Building(TestAssetId.ANY, BuiltInRegistries.BLOCK, PALETTES, List.of(
                building("tower").filler("#").parts("urbex:tower_floor").prefersLonely(0.8f).build(),
                building("tower_row").prefersLonely(0.0f).build()));

        assertEquals(0.0f, resolved.getPrefersLonely(),
                "0.0 is a value a file can mean, not a marker for 'undeclared'");
        assertEquals(0.8f, new Building(TestAssetId.ANY, BuiltInRegistries.BLOCK, PALETTES, List.of(
                building("tower").filler("#").parts("urbex:tower_floor").prefersLonely(0.8f).build(),
                building("tower_row").build())).getPrefersLonely(),
                "omitting it still inherits");
    }

    @Test
    void buildingChildCanSetAnInheritedFloorLimitBackToTheLevelDefault() {
        Building resolved = new Building(TestAssetId.ANY, BuiltInRegistries.BLOCK, PALETTES, List.of(
                building("tower").filler("#").parts("urbex:tower_floor").minFloors(4).build(),
                building("tower_short").minFloors(-1).build()));

        assertEquals(-1, resolved.getMinFloors(),
                "-1 means 'take the level's limit', which a child must be able to ask for");
    }

    // ------------------------------------------------- palettes across versions


    // -------------------------------------------------------------- helpers

    private static List<List<String>> paletteNamesOf(Style style) {
        return style.paletteChoices().stream()
                .map(group -> group.stream().map(pair -> pair.getRight().getName()).toList())
                .toList();
    }

    /**
     * The palettes these styles name. Needed because a style resolves its {@code randompalettes} when
     * it is compiled now, rather than on the first chunk that draws from it (issue #128).
     */
    private static final AssetIndex<Palette> PALETTES = new AssetIndex<>("urbex:palettes", Map.of(
            Identifier.fromNamespaceAndPath("urbex", "common"), new Palette("common"),
            Identifier.fromNamespaceAndPath("urbex", "snowy"), new Palette("snowy")));

    private static List<PaletteSelector> group(String... palettes) {
        return List.of(palettes).stream().map(p -> new PaletteSelector(1.0f, p)).toList();
    }

    @SafeVarargs
    private static StyleDefinition style(String path, List<PaletteSelector>... groups) {
        return new StyleDefinition(Optional.empty(), groups.length == 0
                ? Optional.empty()
                : Optional.of(new Mergeable<>(true, List.of(groups))));
    }

    @SafeVarargs
    private static StyleDefinition styleAppending(String path, List<PaletteSelector>... groups) {
        return new StyleDefinition(Optional.empty(), Optional.of(new Mergeable<>(false, List.of(groups))));
    }

    private static BuildingBuilder building(String path) {
        return new BuildingBuilder(path);
    }

    /** Builds a {@code buildings} entry declaring exactly the fields the test names, and no others. */
    private static final class BuildingBuilder {
        private final String path;
        private Optional<Character> filler = Optional.empty();
        private Optional<Character> rubble = Optional.empty();
        private Optional<Integer> minFloors = Optional.empty();
        private Optional<Float> prefersLonely = Optional.empty();
        private Optional<Mergeable<PartRef>> parts = Optional.empty();

        BuildingBuilder(String path) {
            this.path = path;
        }

        BuildingBuilder filler(String filler) {
            this.filler = Optional.of(filler.charAt(0));
            return this;
        }

        BuildingBuilder rubble(String rubble) {
            this.rubble = Optional.of(rubble.charAt(0));
            return this;
        }

        BuildingBuilder minFloors(int minFloors) {
            this.minFloors = Optional.of(minFloors);
            return this;
        }

        BuildingBuilder prefersLonely(float prefersLonely) {
            this.prefersLonely = Optional.of(prefersLonely);
            return this;
        }

        BuildingBuilder parts(String... partNames) {
            this.parts = Optional.of(new Mergeable<>(true,
                    List.of(partNames).stream().map(BuildingBuilder::partRef).toList()));
            return this;
        }

        private static PartRef partRef(String part) {
            return new PartRef(part, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty());
        }

        BuildingDefinition build() {
            return new BuildingDefinition(Optional.empty(), Optional.empty(), Optional.empty(),
                    filler, rubble,
                    Optional.empty(), minFloors, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    prefersLonely, parts, Optional.empty());
        }
    }

    /**
     * The distinct values a condition can hand back. {@code Condition} exposes only a weighted
     * draw, so this sweeps a fixed random sequence; the sequence is seeded, so the result is a
     * fixed computation rather than a flaky one.
     */
    private static Set<String> valuesOf(Condition condition) {
        ConditionContext context = context();
        RandomSource random = RandomSource.create(1234L);
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(condition.getRandomValue(random, context));
        }
        return seen;
    }

    private static ConditionContext context() {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        return new ConditionContext(0, 1, 0, 5, "part", "belowpart", "building",
                new ChunkCoord(dimension, 0, 0)) {
            @Override
            public boolean isBuilding() {
                return true;
            }

            @Override
            public Identifier getBiome() {
                return Identifier.fromNamespaceAndPath("minecraft", "plains");
            }
        };
    }

    private static ConditionDefinition condition(String path, boolean replace, String... values) {
        List<ConditionPart> parts = List.of(values).stream()
                .map(value -> new ConditionPart(1.0f, value,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
                .toList();
        return new ConditionDefinition(Optional.empty(), Optional.of(new Mergeable<>(replace, parts)));
    }

    private static StuffBuilder stuff(String path) {
        return new StuffBuilder("urbex", path);
    }

    private static StuffBuilder stuff(String namespace, String path) {
        return new StuffBuilder(namespace, path);
    }

    private static final class StuffBuilder {
        private final String namespace;
        private final String path;
        private Optional<Mergeable<String>> tags = Optional.empty();
        private Optional<String> column = Optional.of("AA");
        private Optional<Integer> minheight = Optional.empty();
        private Optional<Integer> maxheight = Optional.empty();
        private Optional<Integer> mincount = Optional.of(1);
        private Optional<Integer> maxcount = Optional.of(1);
        private Optional<Integer> attempts = Optional.of(1);
        // Declared by default, like the four scalars above it: 'inbuilding' is required of a
        // resolved chain (an undeclared one made the stuff object silently inert), so a builder
        // that left it out would fail every test that is not about that rule.
        private Optional<Boolean> inbuilding = Optional.of(false);
        private Optional<Boolean> seesky = Optional.empty();
        private Optional<IdentifierMatcher> buildings = Optional.empty();

        StuffBuilder(String namespace, String path) {
            this.namespace = namespace;
            this.path = path;
        }

        StuffBuilder tags(boolean replace, String... values) {
            this.tags = Optional.of(new Mergeable<>(replace, List.of(values)));
            return this;
        }

        StuffBuilder column(String column) {
            this.column = Optional.of(column);
            return this;
        }

        StuffBuilder counts(int mincount, int maxcount, int attempts) {
            this.mincount = Optional.of(mincount);
            this.maxcount = Optional.of(maxcount);
            this.attempts = Optional.of(attempts);
            return this;
        }

        StuffBuilder minheight(int minheight) {
            this.minheight = Optional.of(minheight);
            return this;
        }

        StuffBuilder maxheight(int maxheight) {
            this.maxheight = Optional.of(maxheight);
            return this;
        }

        StuffBuilder inbuilding(boolean inbuilding) {
            this.inbuilding = Optional.of(inbuilding);
            return this;
        }

        /** Leaves 'inbuilding' undeclared, for a test about inheriting it from an ancestor. */
        StuffBuilder undeclaredInbuilding() {
            this.inbuilding = Optional.empty();
            return this;
        }

        StuffBuilder seesky(boolean seesky) {
            this.seesky = Optional.of(seesky);
            return this;
        }

        StuffBuilder buildings(IdentifierMatcher matcher) {
            this.buildings = Optional.of(matcher);
            return this;
        }

        StuffSettingsDefinition build() {
            return new StuffSettingsDefinition(Optional.empty(), tags, column, minheight, maxheight,
                    mincount, maxcount, attempts, inbuilding, seesky,
                    Optional.empty(), Optional.empty(), Optional.empty(), buildings);
        }
    }
}
