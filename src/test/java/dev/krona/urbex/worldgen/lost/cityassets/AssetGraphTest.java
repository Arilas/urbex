package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.ConditionDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.StyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.ConditionPart;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteSelector;
import com.mojang.datafixers.util.Either;
import dev.krona.urbex.worldgen.lost.regassets.BuildingDefinition;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.BuildingPartDefinition;
import dev.krona.urbex.worldgen.lost.regassets.MultiBuildingDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PredefinedCityDefinition;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleEdge;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.HighwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.PartRef;
import dev.krona.urbex.worldgen.lost.regassets.data.PartRef;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.RailwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.StreetParts;
import dev.krona.urbex.worldgen.lost.regassets.data.StreetSettings;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reference that names nothing is a message about a file, not an exception from a worldgen worker
 * (issue #56).
 * <p>
 * Cross-asset references live in the compiled model as names, and generation resolves them one at a
 * time on whichever chunk first needs one. A building naming a part no datapack registers used to
 * surface as {@code "Can't find 'urbex:nope' in urbex/parts!"} thrown from a worker, on a chunk
 * somewhere out in the world, long after the world loaded. {@link AssetGraph} resolves them all at
 * compile time, into the one report a load already produces.
 * <p>
 * The shipped pack is covered from the other side: {@code DatapackReferenceIntegrityTest} reads its
 * JSON, and both digest runs now compile with this walk active. What is tested here is the walk
 * itself - that it reports, that it scopes to what a world can reach, and that a datapack cannot make
 * it spin. Predefined cities are the root throughout because they are the cheapest one to build; the
 * other two roots (world styles, reachable city styles) enter the same traversal.
 */
class AssetGraphTest {

    private static final Identifier PRESENT_PART = Identifier.fromNamespaceAndPath("urbex", "present_part");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aPartNameNothingRegistersIsReportedNamingTheBuildingAndTheField() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Fixture fixture = new Fixture()
                .building("tower", "urbex:no_such_part")
                .city("downtown", building("urbex:tower"));

        AssetGraph.validate(fixture.snapshot(), List.of(), diagnostics);

        assertEquals(1, diagnostics.size(), () -> diagnostics.format("expected exactly one"));
        AssetDiagnostics.Problem problem = diagnostics.problems().getFirst();
        assertEquals("urbex:parts", problem.registry());
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "tower"), problem.asset(),
                "the report names the asset holding the bad reference, not the one it wanted");
        assertTrue(problem.message().contains("urbex:no_such_part"), problem.message());
        assertTrue(problem.message().contains("parts"), problem.message());
    }

    @Test
    void aReferenceThatResolvesIsNotReported() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Fixture fixture = new Fixture()
                .building("tower", PRESENT_PART.toString())
                .city("downtown", building("urbex:tower"));

        AssetGraph.validate(fixture.snapshot(), List.of(), diagnostics);

        assertTrue(diagnostics.isEmpty(), () -> diagnostics.format("expected nothing"));
    }

    /**
     * A bare name is a load error by design - {@code DataTools.fromName} refuses it rather than
     * defaulting it to the urbex namespace - and it has to arrive as a line in the report rather than
     * as the exception that ends the walk, or one typo hides every other problem in the pack.
     */
    @Test
    void aNameThatIsNotFullyQualifiedIsReportedRatherThanEndingTheWalk() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Fixture fixture = new Fixture()
                .building("first", "present_part")
                .building("second", "urbex:also_missing")
                .city("downtown", building("urbex:first"), building("urbex:second"));

        AssetGraph.validate(fixture.snapshot(), List.of(), diagnostics);

        assertEquals(2, diagnostics.size(),
                () -> "the unqualified name must not stop the walk before the second building: "
                        + diagnostics.format(""));
    }

    /**
     * Nothing reaches this building, so its broken reference is not this world's problem. Same rule
     * the compiler already applies to city styles: requiredness is a property of the end of a chain,
     * and refusing a world over a file nothing can select is how an earlier draft of the compiler
     * refused the shipped pack's own world.
     */
    @Test
    void anAssetNoRootReachesIsNotWalked() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Fixture fixture = new Fixture().building("orphan", "urbex:no_such_part");

        AssetGraph.validate(fixture.snapshot(), List.of(), diagnostics);

        assertTrue(diagnostics.isEmpty(),
                () -> "an unreachable asset is not a broken pack: " + diagnostics.format(""));
    }

    /**
     * One building reached by two paths - directly and through a multibuilding - reports once. A
     * datapack is user input, so "an author would not write that" is not an argument: the walk has to
     * terminate and not multiply its own report.
     */
    @Test
    void anAssetReachableTwiceIsWalkedOnce() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Fixture fixture = new Fixture()
                .building("shared", "urbex:no_such_part")
                .multiBuilding("block", "urbex:shared")
                .city("downtown", building("urbex:shared"), multi("urbex:block"));

        AssetGraph.validate(fixture.snapshot(), List.of(), diagnostics);

        assertEquals(1, diagnostics.size(),
                () -> "reachable twice, reported once: " + diagnostics.format(""));
    }

    /** A multibuilding naming a building that names it back must not spin. */
    @Test
    void aCycleTerminates() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Fixture fixture = new Fixture()
                .multiBuilding("left", "urbex:right_holder")
                .building("right_holder", "urbex:no_such_part")
                .multiBuilding("right", "urbex:right_holder")
                .city("downtown", multi("urbex:left"), multi("urbex:right"));

        AssetGraph.validate(fixture.snapshot(), List.of(), diagnostics);

        assertEquals(1, diagnostics.size(), () -> diagnostics.format(""));
    }

    /**
     * A street, highway or railway part is addressed as a whole chunk and nothing clamps it:
     * {@code ChunkDriver.current} converts chunk-local to absolute unchanged, and {@code block()}
     * masks the result with {@code & 0xf}. A part wider than 16 therefore wraps round and overwrites
     * its own beginning - no exception, nothing in the log, just a road that comes out wrong.
     * {@code BuildingPart.checkGeometry} proves a part is self-consistent; this is the separate
     * question of whether it fits the slot it was wired into.
     */
    @Test
    void aRoadPartThatIsNotChunkSizedIsALoadError() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Fixture fixture = new Fixture().part("too_wide", 24, 16);

        AssetGraph.validate(fixture.snapshot(), List.of(fixture.cityStyleWiring("urbex:too_wide")),
                diagnostics);

        assertTrue(diagnostics.hasFatal(), () -> diagnostics.format("expected a refusal"));
        String message = diagnostics.problems().getFirst().message();
        assertTrue(message.contains("24x16"), message);
        assertTrue(message.contains("16x16"), message);
    }

    /** A building part is placed at a computed offset inside a chunk, so its size is its own business. */
    @Test
    void aBuildingPartThatIsNotChunkSizedIsFine() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Fixture fixture = new Fixture()
                .part("narrow", 4, 4)
                .building("tower", "urbex:narrow")
                .city("downtown", building("urbex:tower"));

        AssetGraph.validate(fixture.snapshot(), List.of(), diagnostics);

        assertTrue(diagnostics.isEmpty(), () -> diagnostics.format(""));
    }

    /**
     * A matcher is not a dereference: a condition naming a part nothing registers does not crash, it
     * silently never fires. Worth a line, not worth refusing a world for.
     */
    @Test
    void aConditionMatchingOnAMissingPartOfAnInstalledPackIsAWarning() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        // 'urbex' is a namespace this snapshot has assets in, so the pack is installed and the name
        // is a typo rather than absent content.
        Fixture fixture = new Fixture().condition("loot_table", "urbex:no_such_part");
        CityStyle root = fixture.cityStyleNaming("urbex:loot_table");

        AssetGraph.validate(fixture.snapshot(), List.of(root), diagnostics);

        assertFalse(diagnostics.isEmpty(), "a typo in an installed pack is worth saying");
        assertFalse(diagnostics.hasFatal(), "but the condition merely never fires, so the world loads");
    }

    /**
     * The case that must stay quiet. A pack may match on, or hand back, content from a mod it does not
     * require - so that players who have it get the content and everyone else simply does not. Saying
     * so every load would be a warning per optional entry for a pack working exactly as written.
     */
    @Test
    void aConditionMatchingOnAnUninstalledPacksPartSaysNothing() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Fixture fixture = new Fixture().condition("loot_table", "somemod:their_part");
        CityStyle root = fixture.cityStyleNaming("urbex:loot_table");

        AssetGraph.validate(fixture.snapshot(), List.of(root), diagnostics);

        assertTrue(diagnostics.isEmpty(),
                () -> "nobody has that pack, which is not a defect: " + diagnostics.format(""));
    }

    /**
     * A building's {@code parts} entries carry matchers of their own, and they were the last
     * references the walk could not see. Nothing in the bundled pack writes {@code belowpart}, so no
     * golden can catch a revert of this - which is why it is pinned here.
     */
    @Test
    void aBuildingPartsEntryMatchingOnAMissingPartIsAWarning() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Fixture fixture = new Fixture()
                .buildingBelow("tower", PRESENT_PART.toString(), "urbex:no_such_floor")
                .city("downtown", building("urbex:tower"));

        AssetGraph.validate(fixture.snapshot(), List.of(), diagnostics);

        assertFalse(diagnostics.isEmpty(), "a belowpart naming nothing means the entry never fires");
        assertFalse(diagnostics.hasFatal(), "which is not worth refusing a world for");
        assertTrue(diagnostics.problems().getFirst().message().contains("belowpart"),
                diagnostics.problems().getFirst().message());
    }

    @Test
    void anEdgeCityStyleParticipatesInWorldRoadPaletteValidation() {
        Fixture fixture = familyFixture();
        CityStyle base = fixture.cityStyle("base", "urbex:base_style", "urbex:city_road");
        CityStyle edge = fixture.cityStyle("edge", "urbex:edge_style", "urbex:city_road");
        fixture.worldStyle("family", new CityStyleSelector(1.0f, "urbex:base", null,
                Optional.of(new CityStyleEdge("urbex:edge", 0.4f))));

        AssetDiagnostics diagnostics = new AssetDiagnostics();
        AssetGraph.validate(fixture.snapshot(), List.of(base, edge), diagnostics);

        assertTrue(diagnostics.problems().stream().anyMatch(problem ->
                        problem.asset().equals(Identifier.fromNamespaceAndPath("urbex", "world_road"))
                                && problem.message().contains("'x'")),
                () -> "the edge palette lacks the world road marker, so graph validation must report it: "
                        + diagnostics.format("problems"));
    }

    @Test
    void aBaseOnlyCityStyleSelectorKeepsWorldRoadPaletteValidationBaseOnly() {
        Fixture fixture = familyFixture();
        CityStyle base = fixture.cityStyle("base", "urbex:base_style", "urbex:city_road");
        CityStyle edge = fixture.cityStyle("edge", "urbex:edge_style", "urbex:city_road");
        fixture.worldStyle("family", new CityStyleSelector(1.0f, "urbex:base", null));

        AssetDiagnostics diagnostics = new AssetDiagnostics();
        AssetGraph.validate(fixture.snapshot(), List.of(base, edge), diagnostics);

        assertTrue(diagnostics.isEmpty(), () -> diagnostics.format("a base-only selector must not validate an unselected edge"));
    }

    // ------------------------------------------------------------------ fixtures

    private static Fixture familyFixture() {
        Fixture fixture = new Fixture();
        fixture.paletteStyle("outside", 'z');
        fixture.paletteStyle("base_style", 'x');
        fixture.paletteStyle("edge_style", 'y');
        fixture.partWithPalette("city_road", 16, 16, 'x', 'x');
        fixture.part("world_road", 16, 16, 'x');
        return fixture;
    }

    private static PredefinedBuilding building(String name) {
        return new PredefinedBuilding(name, 0, 0, false, false);
    }

    private static PredefinedBuilding multi(String name) {
        return new PredefinedBuilding(name, 0, 0, true, false);
    }

    /** Assembles a snapshot holding only what a test names, with one registered part to resolve to. */
    private static final class Fixture {

        private final Map<Identifier, Building> buildings = new HashMap<>();
        private final Map<Identifier, MultiBuilding> multiBuildings = new HashMap<>();
        private final Map<Identifier, PredefinedCity> cities = new HashMap<>();
        private final Map<Identifier, BuildingPart> extraParts = new HashMap<>();
        private final Map<Identifier, Condition> conditions = new HashMap<>();
        private final Map<Identifier, Style> styles = new HashMap<>();
        private final Map<Identifier, WorldStyle> worldStyles = new HashMap<>();
        private final Map<Identifier, CityStyle> cityStyles = new HashMap<>();

        Fixture building(String path, String partName) {
            PartRef ref = new PartRef(partName, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            Identifier id = Identifier.fromNamespaceAndPath("urbex", path);
            buildings.put(id, new Building(id, BuiltInRegistries.BLOCK, null,
                    AssetIndex.empty("urbex:palettes"), List.of(new BuildingDefinition(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of('#'), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(new Mergeable<>(true, List.of(ref))), Optional.empty()))));
            return this;
        }

        Fixture multiBuilding(String path, String... names) {
            Identifier id = Identifier.fromNamespaceAndPath("urbex", path);
            multiBuildings.put(id, new MultiBuilding(id, List.of(new MultiBuildingDefinition(
                    Optional.empty(), Optional.of(names.length), Optional.of(1),
                    Optional.of(List.of(List.of(names)))))));
            return this;
        }

        /** A building whose one {@code parts} entry only fires below {@code belowPart}. */
        Fixture buildingBelow(String path, String partName, String belowPart) {
            PartRef ref = new PartRef(partName, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(Either.right(belowPart)), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty());
            Identifier id = Identifier.fromNamespaceAndPath("urbex", path);
            buildings.put(id, new Building(id, BuiltInRegistries.BLOCK, null,
                    AssetIndex.empty("urbex:palettes"), List.of(new BuildingDefinition(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of('#'), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(new Mergeable<>(true, List.of(ref))), Optional.empty()))));
            return this;
        }

        /** A part of an explicit size, for the road-geometry check. */
        Fixture part(String path, int xSize, int zSize) {
            return part(path, xSize, zSize, 'a');
        }

        Fixture part(String path, int xSize, int zSize, char marker) {
            Identifier id = Identifier.fromNamespaceAndPath("urbex", path);
            List<List<String>> slices = List.of(List.of(Character.toString(marker).repeat(xSize * zSize)));
            extraParts.put(id, new BuildingPart(id, BuiltInRegistries.BLOCK, null,
                    AssetIndex.empty("urbex:palettes"), List.of(new BuildingPartDefinition(
                    Optional.empty(), Optional.of(xSize), Optional.of(zSize), Optional.of(slices),
                    Optional.empty(), Optional.empty(), Optional.empty()))));
            return this;
        }

        Fixture partWithPalette(String path, int xSize, int zSize, char marker, char paletteMarker) {
            Identifier id = Identifier.fromNamespaceAndPath("urbex", path);
            List<List<String>> slices = List.of(List.of(Character.toString(marker).repeat(xSize * zSize)));
            extraParts.put(id, new BuildingPart(id, BuiltInRegistries.BLOCK, null, AssetIndex.empty("urbex:palettes"),
                    List.of(new BuildingPartDefinition(Optional.empty(), Optional.of(xSize), Optional.of(zSize),
                            Optional.of(slices), Optional.empty(),
                            Optional.of(singleMarkerPaletteDefinition(paletteMarker)), Optional.empty()))));
            return this;
        }

        Fixture paletteStyle(String path, char marker) {
            Identifier paletteId = Identifier.fromNamespaceAndPath("urbex", path + "_palette");
            Palette palette = singleMarkerPalette(path + "_palette", marker);
            Identifier styleId = Identifier.fromNamespaceAndPath("urbex", path);
            styles.put(styleId, new Style(styleId, new AssetIndex<>("urbex:palettes", Map.of(paletteId, palette)),
                    List.of(new StyleDefinition(Optional.empty(), Optional.of(new Mergeable<>(true,
                            List.of(List.of(new PaletteSelector(1.0f, paletteId.toString())))))))));
            return this;
        }

        CityStyle cityStyle(String path, String style, String streetPart) {
            Optional<Mergeable<String>> one = Optional.of(new Mergeable<>(true, List.of(streetPart)));
            StreetParts.Decl family = new StreetParts.Decl(one, one, one, one, one, one, one, one);
            CityStyle cityStyle = new CityStyle(Identifier.fromNamespaceAndPath("urbex", path),
                    List.of(new CityStyleDefinition(Optional.empty(), Optional.of(style), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.of(new StreetSettings(Optional.empty(),
                                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(family),
                                    Optional.empty(), Optional.empty())), Optional.empty())));
            cityStyles.put(cityStyle.getId(), cityStyle);
            return cityStyle;
        }

        Fixture worldStyle(String path, CityStyleSelector selector) {
            WorldStyle worldStyle = new WorldStyle(Identifier.fromNamespaceAndPath("urbex", path),
                    List.of(new WorldStyleDefinition(Optional.empty(), Optional.empty(), Optional.of("urbex:outside"),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(worldRoadParts()),
                            Optional.of(new Mergeable<>(true, List.of(selector))), Optional.empty(), Optional.empty())));
            worldStyles.put(worldStyle.getId(), worldStyle);
            return this;
        }

        /**
         * A city style whose whole street family is one part, so the walk reaches it as a road. It
         * declares no {@code style}, which leaves the character check without a palette context and
         * the geometry check as the only thing this asserts.
         */
        CityStyle cityStyleWiring(String partName) {
            Optional<Mergeable<String>> one = Optional.of(new Mergeable<>(true, List.of(partName)));
            StreetParts.Decl family = new StreetParts.Decl(one, one, one, one, one, one, one, one);
            return new CityStyle(Identifier.fromNamespaceAndPath("urbex", "citystyle_roads"),
                    List.of(new CityStyleDefinition(
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(),
                            Optional.of(new StreetSettings(Optional.empty(), Optional.empty(),
                                    Optional.empty(), Optional.empty(), Optional.empty(),
                                    Optional.empty(), Optional.empty(), Optional.empty(),
                                    Optional.of(family), Optional.empty(), Optional.empty())),
                            Optional.empty())));
        }

        /** A condition whose one entry matches on {@code inpart}, which is a matcher, not a lookup. */
        Fixture condition(String path, String inPart) {
            Identifier id = Identifier.fromNamespaceAndPath("urbex", path);
            ConditionPart entry = new ConditionPart(1.0f, "minecraft:chests/simple_dungeon",
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(Either.right(inPart)), Optional.empty(), Optional.empty(),
                    Optional.empty());
            conditions.put(id, new Condition(id, List.of(new ConditionDefinition(
                    Optional.empty(), Optional.of(new Mergeable<>(true, List.of(entry)))))));
            return this;
        }

        /**
         * A city style whose style has one palette, whose one marker names {@code conditionName} as
         * its loot table - which is how a condition is reached at all.
         */
        CityStyle cityStyleNaming(String conditionName) {
            Identifier paletteId = Identifier.fromNamespaceAndPath("urbex", "loot_palette");
            PaletteEntry marker = new PaletteEntry("L", Optional.of("minecraft:chest"),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.of(conditionName), Optional.empty(), Optional.empty(),
                    Optional.empty());
            // 'a' as well as the loot marker: the road part this city style wires in is built from
            // 'a', and the character check would otherwise fire on the fixture rather than on what
            // the test is about.
            PaletteEntry filler = new PaletteEntry("a", Optional.of("minecraft:stone"),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty());
            Palette palette = new Palette(paletteId, BuiltInRegistries.BLOCK, null,
                    List.of(new PaletteDefinition(Optional.empty(), Optional.of(List.of(marker, filler)))));
            Identifier styleId = Identifier.fromNamespaceAndPath("urbex", "style_loot");
            styles.put(styleId, new Style(styleId,
                    new AssetIndex<>("urbex:palettes", Map.of(paletteId, palette)),
                    List.of(new StyleDefinition(Optional.empty(), Optional.of(new Mergeable<>(true,
                            List.of(List.of(new PaletteSelector(1.0f, paletteId.toString())))))))));
            return new CityStyle(Identifier.fromNamespaceAndPath("urbex", "citystyle_loot"),
                    List.of(new CityStyleDefinition(
                            Optional.empty(), Optional.of(styleId.toString()), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.of(new StreetSettings(Optional.empty(), Optional.empty(),
                                    Optional.empty(), Optional.empty(), Optional.empty(),
                                    Optional.empty(), Optional.empty(), Optional.empty(),
                                    Optional.of(roadFamily()), Optional.empty(),
                                    Optional.empty())),
                            Optional.empty())));
        }

        /** Every street slot pointing at one 16x16 part this fixture registers. */
        private StreetParts.Decl roadFamily() {
            part("road", 16, 16);
            Optional<Mergeable<String>> one = Optional.of(new Mergeable<>(true, List.of("urbex:road")));
            return new StreetParts.Decl(one, one, one, one, one, one, one, one);
        }

        private static Palette singleMarkerPalette(String path, char marker) {
            return new Palette(Identifier.fromNamespaceAndPath("urbex", path), BuiltInRegistries.BLOCK, null,
                    List.of(singleMarkerPaletteDefinition(marker)));
        }

        private static PaletteDefinition singleMarkerPaletteDefinition(char marker) {
            PaletteEntry entry = new PaletteEntry(Character.toString(marker), Optional.of("minecraft:stone"),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            return new PaletteDefinition(Optional.empty(), Optional.of(List.of(entry)));
        }

        private static PartSelector.Decl worldRoadParts() {
            Optional<Mergeable<String>> one = Optional.of(new Mergeable<>(true, List.of("urbex:world_road")));
            return new PartSelector.Decl(Optional.of(new HighwayParts.Decl(one, one, one, one, one, one)),
                    Optional.of(new RailwayParts.Decl(one, one, one, one, one, one, one, one, one, one, one,
                            one, one, one, one, one)));
        }

        Fixture city(String path, PredefinedBuilding... contents) {
            Identifier id = Identifier.fromNamespaceAndPath("urbex", path);
            cities.put(id, new PredefinedCity(id, List.of(new PredefinedCityDefinition(
                    Optional.empty(), Optional.of("minecraft:overworld"), Optional.of(0), Optional.of(0),
                    Optional.of(1), Optional.of("urbex:citystyle_common"),
                    Optional.of(new Mergeable<>(true, List.of(contents))), Optional.empty()))));
            return this;
        }

        AssetSnapshot snapshot() {
            AssetSnapshot empty = AssetSnapshot.empty();
            Map<Identifier, BuildingPart> parts = new HashMap<>();
            // A part is a leaf for the walk, so this one exists only so that "resolves" and "does
            // not resolve" are both reachable states. One block, so it satisfies checkGeometry.
            parts.put(PRESENT_PART, new BuildingPart(PRESENT_PART, BuiltInRegistries.BLOCK, null,
                    AssetIndex.empty("urbex:palettes"), List.of(new BuildingPartDefinition(
                    Optional.empty(), Optional.of(1), Optional.of(1),
                    Optional.of(List.of(List.of("a"))), Optional.empty(), Optional.empty(),
                    Optional.empty()))));
            parts.putAll(extraParts);
            return new AssetSnapshot(empty.variants(), empty.palettes(),
                    new AssetIndex<>("urbex:conditions", conditions),
                    new AssetIndex<>("urbex:styles", styles), new AssetIndex<>("urbex:parts", parts),
                    new AssetIndex<>("urbex:buildings", buildings),
                    new AssetIndex<>("urbex:multibuildings", multiBuildings), empty.scattered(),
                    new AssetIndex<>("urbex:worldstyles", worldStyles), new AssetIndex<>("urbex:citystyles", cityStyles),
                    new AssetIndex<>("urbex:predefinedcities", cities), empty.stuff(), empty.stuffByTag(),
                    empty.predefined());
        }
    }
}
