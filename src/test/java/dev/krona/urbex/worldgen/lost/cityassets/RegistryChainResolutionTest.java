package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.regassets.BuildingRE;
import dev.krona.urbex.worldgen.lost.regassets.ConditionRE;
import dev.krona.urbex.worldgen.lost.regassets.MultiBuildingRE;
import dev.krona.urbex.worldgen.lost.regassets.PredefinedCityRE;
import dev.krona.urbex.worldgen.lost.regassets.ScatteredRE;
import dev.krona.urbex.worldgen.lost.regassets.StuffSettingsRE;
import dev.krona.urbex.worldgen.lost.regassets.StyleRE;
import dev.krona.urbex.worldgen.lost.regassets.VariantRE;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleRE;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.ConditionPart;
import dev.krona.urbex.worldgen.lost.regassets.data.IdentifierMatcher;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.PartRef;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.ScatteredSettings;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Two-entry-chain coverage for the registries where a wrong fold is silent rather than loud.
 * <p>
 * The bundled datapack uses {@code extends} only in {@code citystyles} and {@code presets}, so
 * every other registry only ever resolves a chain of one in the digest runs and in gameplay -
 * {@link StuffSettingsRE#resolve} even short-circuits that case. Without these tests the fold
 * bodies would never execute anywhere.
 * <p>
 * {@code stuff} is the dangerous one: {@code AssetRegistries.load} files each {@link StuffObject}
 * into {@code STUFF_BY_TAG} by its tags and {@code Stuff} reads back by tag, so a fold that dropped
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
        // The silent-damage case: dropping these would unfile the object from STUFF_BY_TAG and it
        // would never be selected for placement again.
        StuffObject resolved = new StuffObject(List.of(
                stuff("torches").tags(true, "all", "indoor").build(),
                stuff("torches_rare").build()));

        assertEquals(List.of("all", "indoor"), resolved.getSettings().getTags());
    }

    @Test
    void stuffTagsReplaceByDefaultAndAppendWhenTheChildOptsIn() {
        StuffObject replaced = new StuffObject(List.of(
                stuff("torches").tags(true, "all", "indoor").build(),
                stuff("torches_rare").tags(true, "rare").build()));
        assertEquals(List.of("rare"), replaced.getSettings().getTags(), "a bare array replaces");

        StuffObject appended = new StuffObject(List.of(
                stuff("torches").tags(true, "all", "indoor").build(),
                stuff("torches_rare").tags(false, "rare").build()));
        assertEquals(List.of("all", "indoor", "rare"), appended.getSettings().getTags(),
                "{\"replace\": false} keeps the inherited tags and adds after them");
    }

    @Test
    void stuffInheritsEveryOptionalScalarTheChildOmits() {
        IdentifierMatcher onlyLibraries = new IdentifierMatcher(
                Optional.of(List.of("urbex:library")), Optional.empty());
        StuffSettingsRE parent = stuff("torches")
                .tags(true, "all")
                .minheight(40).maxheight(90)
                .inbuilding(true).seesky(false)
                .buildings(onlyLibraries)
                .build();
        StuffSettingsRE child = stuff("torches_rare").column("XX").counts(2, 9, 7).build();

        StuffSettingsRE resolved = new StuffObject(List.of(parent, child)).getSettings();

        assertNotSame(child, resolved, "a two-entry chain must actually fold, not hand back the leaf");
        assertEquals(40, resolved.getMinheight(), "minheight is inherited");
        assertEquals(90, resolved.getMaxheight(), "maxheight is inherited");
        assertEquals(Boolean.TRUE, resolved.isInBuilding(), "inbuilding is inherited");
        assertEquals(Boolean.FALSE, resolved.isSeesky(),
                "seesky is inherited - and 'declared false' must not read as 'undeclared'");
        assertSame(onlyLibraries, resolved.getBuildingMatcher(), "the matcher is inherited");
        assertSame(IdentifierMatcher.ANY,
                new StuffObject(List.of(stuff("plain").build(), stuff("plain_child").build()))
                        .getSettings().getBuildingMatcher(),
                "a matcher no entry in the chain declares still reads as ANY");
    }

    @Test
    void stuffRequiredScalarsComeFromTheLeaf() {
        StuffSettingsRE resolved = new StuffObject(List.of(
                stuff("torches").column("AB").counts(1, 2, 3).build(),
                stuff("torches_rare").column("CD").counts(4, 5, 6).build())).getSettings();

        assertEquals("CD", resolved.getColumn());
        assertEquals(4, resolved.getMincount());
        assertEquals(5, resolved.getMaxcount());
        assertEquals(6, resolved.getAttempts());
    }

    @Test
    void stuffResolvedFromAChainOfOneIsTheEntryItself() {
        StuffSettingsRE only = stuff("torches").tags(true, "all").build();

        assertSame(only, new StuffObject(List.of(only)).getSettings());
    }

    // ----------------------------------------------------------- conditions

    @Test
    void conditionValuesReplaceByDefaultAndAppendWhenTheChildOptsIn() {
        ConditionRE parent = condition("loot", true, "urbex:common_loot", "urbex:rare_loot");

        assertEquals(Set.of("urbex:barrel_loot"),
                valuesOf(new Condition(List.of(parent, condition("loot_barrel", true, "urbex:barrel_loot")))),
                "a bare array replaces");
        assertEquals(Set.of("urbex:common_loot", "urbex:rare_loot", "urbex:barrel_loot"),
                valuesOf(new Condition(List.of(parent, condition("loot_barrel", false, "urbex:barrel_loot")))),
                "{\"replace\": false} keeps the inherited values");
    }

    // ------------------------------------------------------------- variants

    @Test
    void variantBlocksReplaceByDefaultAndAppendWhenTheChildOptsIn() {
        VariantRE parent = variant("stones", true,
                new BlockEntry(1, "minecraft:stone"), new BlockEntry(2, "minecraft:andesite"));

        Variant replaced = new Variant(List.of(parent,
                variant("stones_deep", true, new BlockEntry(3, "minecraft:deepslate"))));
        assertEquals(List.of("minecraft:deepslate"), blockIdsOf(replaced), "a bare array replaces");
        assertEquals(List.of(3), replaced.getBlocks().stream().map(org.apache.commons.lang3.tuple.Pair::getLeft).toList());

        Variant appended = new Variant(List.of(parent,
                variant("stones_deep", false, new BlockEntry(3, "minecraft:deepslate"))));
        assertEquals(List.of("minecraft:stone", "minecraft:andesite", "minecraft:deepslate"),
                blockIdsOf(appended), "{\"replace\": false} keeps the inherited blocks, in order");
    }

    // --------------------------------------------------------------- styles

    @Test
    void styleThatDeclaresNoPalettesKeepsItsAncestors() {
        Style resolved = new Style(List.of(
                style("nordic", group("urbex:common")),
                style("nordic_rare")));

        assertEquals(List.of(List.of("urbex:common")), paletteNamesOf(resolved),
                "a child that only renames its ancestor still paints from it");
    }

    @Test
    void stylePalettesReplaceByDefaultAndAppendWhenTheChildOptsIn() {
        StyleRE parent = style("nordic", group("urbex:common"));

        assertEquals(List.of(List.of("urbex:snowy")),
                paletteNamesOf(new Style(List.of(parent, style("nordic_snow", group("urbex:snowy"))))),
                "a bare array replaces");
        assertEquals(List.of(List.of("urbex:common"), List.of("urbex:snowy")),
                paletteNamesOf(new Style(List.of(parent, styleAppending("nordic_snow", group("urbex:snowy"))))),
                "{\"replace\": false} keeps the inherited groups, in order");
    }

    // ------------------------------------------------------------ scattered

    @Test
    void scatteredChildInheritsTheTerrainHandlingItDoesNotDeclare() {
        ScatteredRE parent = new ScatteredRE(Optional.empty(),
                Optional.of(new Mergeable<>(true, List.of("urbex:oilrig"))), Optional.empty(),
                Optional.empty(),
                Optional.of(ScatteredBuilding.TerrainHeight.OCEAN),
                Optional.of(ScatteredBuilding.TerrainFix.CLEAR),
                Optional.of(3))
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", "oilrig"));
        ScatteredRE child = new ScatteredRE(Optional.empty(),
                Optional.of(new Mergeable<>(true, List.of("urbex:oilrig_burnt"))), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", "oilrig_burnt"));

        ScatteredBuilding resolved = new ScatteredBuilding(List.of(parent, child));

        assertEquals(ScatteredBuilding.TerrainHeight.OCEAN, resolved.getTerrainheight());
        assertEquals(ScatteredBuilding.TerrainFix.CLEAR, resolved.getTerrainfix());
        assertEquals(3, resolved.getHeightoffset(), "and the optional scalar with it");
        assertEquals(List.of("urbex:oilrig_burnt"), resolved.getBuildings(),
                "what the child does declare still wins");
    }

    // ----------------------------------------------------- predefinedcities

    @Test
    void predefinedCityChildInheritsEverythingButThePositionItMoves() {
        PredefinedCityRE parent = new PredefinedCityRE(Optional.empty(),
                Optional.of("minecraft:overworld"), Optional.of(10), Optional.of(20), Optional.of(7),
                Optional.of("urbex:citystyle_common"),
                Optional.of(new Mergeable<>(true,
                        List.of(new PredefinedBuilding("urbex:townhall", 0, 0, false, false)))),
                Optional.empty())
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", "capital"));
        PredefinedCityRE child = new PredefinedCityRE(Optional.empty(),
                Optional.empty(), Optional.of(100), Optional.of(200), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty())
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", "colony"));

        PredefinedCity resolved = new PredefinedCity(List.of(parent, child));

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
        WorldStyleRE parent = new WorldStyleRE(Optional.empty(), Optional.of("urbex:standard"),
                Optional.empty(), Optional.empty(), Optional.of(scattered), Optional.empty(),
                Optional.of(new Mergeable<>(true,
                        List.of(new CityStyleSelector(1.0f, "urbex:citystyle_common", null)))),
                Optional.empty())
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", "standard"));
        WorldStyleRE child = new WorldStyleRE(Optional.empty(), Optional.of("urbex:bleak"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty())
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", "bleak"));

        WorldStyle resolved = new WorldStyle(List.of(parent, child));

        assertEquals("urbex:bleak", resolved.getOutsideStyle(), "what the child declares wins");
        assertSame(scattered, resolved.getScatteredSettings(), "the settings block is inherited");
        assertEquals(List.of("urbex:citystyle_common"), resolved.cityStyleSelectors().stream()
                .map(pair -> pair.getRight().getRight()).toList());
    }

    // -------------------------------------------------------- multibuildings

    @Test
    void multiBuildingChildInheritsTheGridItDoesNotDeclare() {
        MultiBuildingRE parent = new MultiBuildingRE(Optional.empty(),
                Optional.of(1), Optional.of(2),
                Optional.of(List.of(List.of("urbex:oilrig00", "urbex:oilrig01"))))
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", "oilrig"));
        MultiBuildingRE child = new MultiBuildingRE(Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty())
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", "oilrig_burnt"));

        MultiBuilding resolved = new MultiBuilding(List.of(parent, child));

        assertEquals(1, resolved.getDimX());
        assertEquals(2, resolved.getDimZ());
        assertEquals("urbex:oilrig01", resolved.getBuilding(0, 1));
        assertEquals(Set.of("urbex:oilrig00", "urbex:oilrig01"), resolved.getBuildingSet());
    }

    // ------------------------------------------------------------ buildings

    @Test
    void buildingChildInheritsTheFillerAndPartsItDoesNotDeclare() {
        Building resolved = new Building(List.of(
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
        Building resolved = new Building(List.of(
                building("tower").filler("#").parts("urbex:tower_floor").prefersLonely(0.8f).build(),
                building("tower_row").prefersLonely(0.0f).build()));

        assertEquals(0.0f, resolved.getPrefersLonely(),
                "0.0 is a value a file can mean, not a marker for 'undeclared'");
        assertEquals(0.8f, new Building(List.of(
                building("tower").filler("#").parts("urbex:tower_floor").prefersLonely(0.8f).build(),
                building("tower_row").build())).getPrefersLonely(),
                "omitting it still inherits");
    }

    @Test
    void buildingChildCanSetAnInheritedFloorLimitBackToTheLevelDefault() {
        Building resolved = new Building(List.of(
                building("tower").filler("#").parts("urbex:tower_floor").minFloors(4).build(),
                building("tower_short").minFloors(-1).build()));

        assertEquals(-1, resolved.getMinFloors(),
                "-1 means 'take the level's limit', which a child must be able to ask for");
    }

    // -------------------------------------------------------------- helpers

    private static List<List<String>> paletteNamesOf(Style style) {
        return style.paletteChoices().stream()
                .map(group -> group.stream().map(Pair::getRight).toList())
                .toList();
    }

    private static List<PaletteSelector> group(String... palettes) {
        return List.of(palettes).stream().map(p -> new PaletteSelector(1.0f, p)).toList();
    }

    @SafeVarargs
    private static StyleRE style(String path, List<PaletteSelector>... groups) {
        return new StyleRE(Optional.empty(), groups.length == 0
                ? Optional.empty()
                : Optional.of(new Mergeable<>(true, List.of(groups))))
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", path));
    }

    @SafeVarargs
    private static StyleRE styleAppending(String path, List<PaletteSelector>... groups) {
        return new StyleRE(Optional.empty(), Optional.of(new Mergeable<>(false, List.of(groups))))
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", path));
    }

    private static BuildingBuilder building(String path) {
        return new BuildingBuilder(path);
    }

    /** Builds a {@code buildings} entry declaring exactly the fields the test names, and no others. */
    private static final class BuildingBuilder {
        private final String path;
        private Optional<String> filler = Optional.empty();
        private Optional<String> rubble = Optional.empty();
        private Optional<Integer> minFloors = Optional.empty();
        private Optional<Float> prefersLonely = Optional.empty();
        private Optional<Mergeable<PartRef>> parts = Optional.empty();

        BuildingBuilder(String path) {
            this.path = path;
        }

        BuildingBuilder filler(String filler) {
            this.filler = Optional.of(filler);
            return this;
        }

        BuildingBuilder rubble(String rubble) {
            this.rubble = Optional.of(rubble);
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

        BuildingRE build() {
            return new BuildingRE(Optional.empty(), Optional.empty(), Optional.empty(),
                    filler, rubble,
                    Optional.empty(), minFloors, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    prefersLonely, parts, Optional.empty())
                    .setRegistryName(Identifier.fromNamespaceAndPath("urbex", path));
        }
    }

    private static List<String> blockIdsOf(Variant variant) {
        return variant.getBlocks().stream()
                .map(pair -> BuiltInRegistries.BLOCK.getKey(pair.getRight().getBlock()).toString())
                .toList();
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

    private static ConditionRE condition(String path, boolean replace, String... values) {
        List<ConditionPart> parts = List.of(values).stream()
                .map(value -> new ConditionPart(1.0f, value,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
                .toList();
        return new ConditionRE(Optional.empty(), Optional.of(new Mergeable<>(replace, parts)))
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", path));
    }

    private static VariantRE variant(String path, boolean replace, BlockEntry... blocks) {
        return new VariantRE(Optional.empty(), Optional.of(new Mergeable<>(replace, List.of(blocks))))
                .setRegistryName(Identifier.fromNamespaceAndPath("urbex", path));
    }

    private static StuffBuilder stuff(String path) {
        return new StuffBuilder(path);
    }

    private static final class StuffBuilder {
        private final String path;
        private Optional<Mergeable<String>> tags = Optional.empty();
        private Optional<String> column = Optional.of("AA");
        private Optional<Integer> minheight = Optional.empty();
        private Optional<Integer> maxheight = Optional.empty();
        private Optional<Integer> mincount = Optional.of(1);
        private Optional<Integer> maxcount = Optional.of(1);
        private Optional<Integer> attempts = Optional.of(1);
        private Optional<Boolean> inbuilding = Optional.empty();
        private Optional<Boolean> seesky = Optional.empty();
        private Optional<IdentifierMatcher> buildings = Optional.empty();

        StuffBuilder(String path) {
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

        StuffBuilder seesky(boolean seesky) {
            this.seesky = Optional.of(seesky);
            return this;
        }

        StuffBuilder buildings(IdentifierMatcher matcher) {
            this.buildings = Optional.of(matcher);
            return this;
        }

        StuffSettingsRE build() {
            return new StuffSettingsRE(Optional.empty(), tags, column, minheight, maxheight,
                    mincount, maxcount, attempts, inbuilding, seesky,
                    Optional.empty(), Optional.empty(), Optional.empty(), buildings)
                    .setRegistryName(Identifier.fromNamespaceAndPath("urbex", path));
        }
    }
}
