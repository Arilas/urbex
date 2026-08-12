package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.BuildingDefinition;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.BuildingPartDefinition;
import dev.krona.urbex.worldgen.lost.regassets.MultiBuildingDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PredefinedCityDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartRef;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
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

    // ------------------------------------------------------------------ fixtures

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

        /** A part of an explicit size, for the road-geometry check. */
        Fixture part(String path, int xSize, int zSize) {
            Identifier id = Identifier.fromNamespaceAndPath("urbex", path);
            List<List<String>> slices = List.of(List.of("a".repeat(xSize * zSize)));
            extraParts.put(id, new BuildingPart(id, BuiltInRegistries.BLOCK, null,
                    AssetIndex.empty("urbex:palettes"), List.of(new BuildingPartDefinition(
                    Optional.empty(), Optional.of(xSize), Optional.of(zSize), Optional.of(slices),
                    Optional.empty(), Optional.empty(), Optional.empty()))));
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
            return new AssetSnapshot(empty.variants(), empty.palettes(), empty.conditions(),
                    empty.styles(), new AssetIndex<>("urbex:parts", parts),
                    new AssetIndex<>("urbex:buildings", buildings),
                    new AssetIndex<>("urbex:multibuildings", multiBuildings), empty.scattered(),
                    empty.worldStyles(), empty.cityStyles(),
                    new AssetIndex<>("urbex:predefinedcities", cities), empty.stuff(), empty.stuffByTag());
        }
    }
}
