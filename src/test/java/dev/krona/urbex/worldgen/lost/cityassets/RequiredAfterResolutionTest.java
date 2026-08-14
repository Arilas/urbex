package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.MultiBuildingDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PredefinedCityDefinition;
import dev.krona.urbex.worldgen.lost.regassets.ScatteredDefinition;
import dev.krona.urbex.worldgen.lost.regassets.StuffSettingsDefinition;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.ScatteredSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.TestWiring;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requiredness is enforced <em>after</em> the {@code extends} chain is applied, not by the codec.
 * <p>
 * Both directions matter and they pull against each other. A child that omits a field must be able
 * to decode and take its ancestor's value - otherwise {@code extends} only saves a file from
 * repeating its <em>optional</em> fields, which on {@code multibuildings} meant it saved nothing at
 * all. But a field nothing in the whole chain declares must still be a load error naming the asset
 * and the field, rather than a null that surfaces as an NPE somewhere in generation, hours later.
 * <p>
 * The four registries covered here are the ones that make the point: {@code stuff} is what
 * {@code extends} is most obviously for (a rarer variant of an existing decoration), {@code
 * multibuildings} is where every field used to be required so {@code extends} was purely
 * decorative, {@code worldstyles} carries {@code outsidestyle}, the scalar the spec names by
 * example, and {@code scattered} is where requiredness spans a <em>pair</em> of fields.
 * <p>
 * That last one is the shape {@link Resolved#require} cannot express, and {@code multibuildings}
 * has the other: a constraint between two fields that are each individually present. Per-field
 * requiredness leaves both unguarded, so each is checked by hand where the chain is folded.
 */
class RequiredAfterResolutionTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---------------------------------------------------------------- stuff

    @Test
    void stuffChildInheritsRequiredScalarsItDoesNotDeclare() {
        StuffSettingsDefinition parent = stuff().column("y").counts(2, 5, 10).inbuilding(true).tags("rubble").build();
        StuffSettingsDefinition child = stuff().tags("debris").build();

        StuffObject resolved = new StuffObject(TestAssetId.of("test_stuff"), List.of(parent, child));

        assertEquals(2, resolved.getSettings().getMincount(),
                "a field the child omits comes from the chain, not from a codec default");
        assertEquals(5, resolved.getSettings().getMaxcount());
        assertEquals(10, resolved.getSettings().getAttempts());
        assertEquals("y", resolved.getSettings().getColumn());
        assertEquals(List.of("debris"), resolved.getSettings().getTags(),
                "what the child does declare still wins");
        assertEquals(true, resolved.getSettings().isInBuilding(),
                "'inbuilding' is required of the chain, so it too comes from the ancestor");
    }

    @Test
    void stuffFieldNoEntryInTheChainDeclaresIsALoadErrorNamingBoth() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new StuffObject(TestAssetId.of("test_stuff"), List.of(stuff().column("y").tags("debris").build())));

        assertTrue(e.getMessage().contains("mincount"), e.getMessage());
        assertTrue(e.getMessage().contains("urbex:test_stuff"), e.getMessage());
    }

    @Test
    void stuffWithoutInbuildingAnywhereInTheChainIsALoadError() {
        // Not merely a missing value: Stuff.generateStuff matches on `inbuilding == hasBuilding`,
        // so an undeclared one matched no chunk at all and the decoration was registered, indexed
        // and walked on every city chunk while placing nothing, silently.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new StuffObject(TestAssetId.of("test_stuff"), List.of(stuff().column("y").counts(1, 2, 3).tags("debris").build())));

        assertTrue(e.getMessage().contains("inbuilding"), e.getMessage());
        assertTrue(e.getMessage().contains("urbex:test_stuff"), e.getMessage());
    }

    @Test
    void stuffChildThatDeclaresOnlySomeOfTheChainStillLoadsWhenAnAncestorCoversTheRest() {
        StuffSettingsDefinition root = stuff().column("y").counts(2, 5, 10).inbuilding(false).build();
        StuffSettingsDefinition middle = stuff().counts(3, 6, 11).build();
        StuffSettingsDefinition leaf = stuff().tags("debris").build();

        StuffSettingsDefinition resolved = new StuffObject(TestAssetId.of("test_stuff"), List.of(root, middle, leaf)).getSettings();

        assertEquals("y", resolved.getColumn(), "from the root, two links up");
        assertEquals(3, resolved.getMincount(), "the nearest ancestor that declares one wins");
    }

    // -------------------------------------------------------- multibuildings

    @Test
    void multiBuildingChildInheritsTheGridSizeItDoesNotDeclare() {
        MultiBuildingDefinition parent = multiBuilding(Optional.of(2), Optional.of(2),
                Optional.of(List.of(List.of("urbex:a", "urbex:b"), List.of("urbex:c", "urbex:d"))));
        MultiBuildingDefinition child = multiBuilding(Optional.empty(), Optional.empty(),
                Optional.of(List.of(List.of("urbex:w", "urbex:x"), List.of("urbex:y", "urbex:z"))));

        MultiBuilding resolved = new MultiBuilding(TestAssetId.of("test_multi"), List.of(parent, child));

        assertEquals(2, resolved.getDimX(), "dimx comes from the parent");
        assertEquals(2, resolved.getDimZ(), "dimz comes from the parent");
        assertEquals("urbex:w", resolved.getBuilding(0, 0),
                "a declared grid replaces the inherited one wholesale");
    }

    @Test
    void multiBuildingGridIsInheritedWhenTheChildDeclaresOnlyItsSize() {
        MultiBuildingDefinition parent = multiBuilding(Optional.of(1), Optional.of(1),
                Optional.of(List.of(List.of("urbex:tower"))));
        MultiBuildingDefinition child = multiBuilding(Optional.of(1), Optional.of(1), Optional.empty());

        assertEquals("urbex:tower", new MultiBuilding(TestAssetId.of("test_multi"), List.of(parent, child)).getBuilding(0, 0));
    }

    @Test
    void multiBuildingFieldNoEntryInTheChainDeclaresIsALoadErrorNamingBoth() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new MultiBuilding(TestAssetId.of("test_multi"), List.of(multiBuilding(Optional.empty(), Optional.empty(),
                        Optional.of(List.of(List.of("urbex:a")))))));

        assertTrue(e.getMessage().contains("dimx"), e.getMessage());
        assertTrue(e.getMessage().contains("urbex:test_multi"), e.getMessage());
    }

    /**
     * {@code dimx}/{@code dimz} and {@code buildings} resolve from independent links, so a child
     * that restates only its size inherits a grid of the ancestor's size. Nothing downstream
     * re-checks: {@code MultiChunk.placeBuilding} reserves a cell for every {@code xx < getDimX()}
     * and those coordinates come back to an unguarded {@code buildings.get(x).get(z)}, so without a
     * check here the contradiction surfaces as an IndexOutOfBoundsException mid-generation.
     */
    @Test
    void multiBuildingGridSmallerThanTheDeclaredSizeIsALoadError() {
        MultiBuildingDefinition parent = multiBuilding(Optional.of(1), Optional.of(1),
                Optional.of(List.of(List.of("urbex:tower"))));
        MultiBuildingDefinition child = multiBuilding(Optional.of(2), Optional.of(2), Optional.empty());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new MultiBuilding(TestAssetId.of("test_multi"), List.of(parent, child)));

        assertTrue(e.getMessage().contains("urbex:test_multi"), e.getMessage());
        assertTrue(e.getMessage().contains("dimx"), e.getMessage());
        assertTrue(e.getMessage().contains("2"), e.getMessage());
    }

    @Test
    void multiBuildingRowShorterThanTheDeclaredDepthIsALoadError() {
        MultiBuildingDefinition entry = multiBuilding(Optional.of(2), Optional.of(2),
                Optional.of(List.of(List.of("urbex:a", "urbex:b"), List.of("urbex:c"))));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new MultiBuilding(TestAssetId.of("test_multi"), List.of(entry)));

        assertTrue(e.getMessage().contains("urbex:test_multi"), e.getMessage());
        assertTrue(e.getMessage().contains("dimz"), e.getMessage());
    }

    @Test
    void multiBuildingWithNoGridAnywhereInTheChainIsALoadError() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new MultiBuilding(TestAssetId.of("test_multi"), List.of(
                        multiBuilding(Optional.of(1), Optional.of(1), Optional.empty()))));

        assertTrue(e.getMessage().contains("buildings"), e.getMessage());
        assertTrue(e.getMessage().contains("urbex:test_multi"), e.getMessage());
    }

    // ------------------------------------------------------------- scattered

    /**
     * {@code buildings} and {@code multibuilding} are required of the chain as a pair - at least
     * one of them has to survive resolution. Neither is required on its own, which is why
     * {@link Resolved#require} cannot state this and it has to be checked here. A chain declaring
     * neither used to load and then throw from {@code Scattered.generate} the first time the entry
     * was placed.
     */
    @Test
    void scatteredWithNeitherBuildingsNorMultiBuildingInTheChainIsALoadError() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ScatteredBuilding(TestAssetId.of("test_scattered"), List.of(scattered(Optional.empty(), Optional.empty()))));

        assertTrue(e.getMessage().contains("urbex:test_scattered"), e.getMessage());
        assertTrue(e.getMessage().contains("buildings"), e.getMessage());
        assertTrue(e.getMessage().contains("multibuilding"), e.getMessage());
    }

    @Test
    void scatteredChildInheritsTheBuildingListItDoesNotDeclare() {
        ScatteredDefinition parent = scattered(Optional.of(new Mergeable<>(true, List.of("urbex:cabin"))),
                Optional.empty());
        ScatteredDefinition child = scattered(Optional.empty(), Optional.empty());

        ScatteredBuilding resolved = new ScatteredBuilding(TestAssetId.of("test_scattered"), List.of(parent, child));

        assertEquals(List.of("urbex:cabin"), resolved.getBuildings(),
                "a child that only restates terrain still inherits the chain's building list");
    }

    @Test
    void scatteredSatisfiesThePairWithMultiBuildingAlone() {
        ScatteredBuilding resolved = new ScatteredBuilding(TestAssetId.of("test_scattered"), List.of(
                scattered(Optional.empty(), Optional.of("urbex:oilrig"))));

        assertEquals("urbex:oilrig", resolved.getMultibuilding());
        assertNull(resolved.getBuildings(), "multibuilding alone is a complete scattered entry");
    }

    // ----------------------------------------------------------- worldstyles

    @Test
    void worldStyleChildInheritsOutsideStyleAndCityStylesItDoesNotDeclare() {
        ScatteredSettings scattered = new ScatteredSettings(16, 0.5f, 10, List.of());
        WorldStyleDefinition parent = worldStyle(Optional.of("urbex:standard"),
                Optional.of(new Mergeable<>(true,
                        List.of(new CityStyleSelector(1.0f, "urbex:downtown", null)))),
                Optional.empty());
        WorldStyleDefinition child = worldStyle(Optional.empty(), Optional.empty(), Optional.of(scattered));

        WorldStyle resolved = new WorldStyle(TestAssetId.of("test_world"), List.of(parent, child));

        assertEquals("urbex:standard", resolved.getOutsideStyle(),
                "outsidestyle comes from the parent");
        assertSame(scattered, resolved.getScatteredSettings(), "what the child declares still wins");
        assertEquals(List.of("urbex:downtown"), cityStyleNames(resolved),
                "citystyles comes from the parent rather than resolving to an empty list");
    }

    @Test
    void worldStyleFieldNoEntryInTheChainDeclaresIsALoadErrorNamingBoth() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new WorldStyle(TestAssetId.of("test_world"), List.of(worldStyle(Optional.empty(), Optional.empty(),
                        Optional.of(new ScatteredSettings(16, 0.5f, 10, List.of()))))));

        assertTrue(e.getMessage().contains("outsidestyle"), e.getMessage());
        assertTrue(e.getMessage().contains("urbex:test_world"), e.getMessage());
    }

    @Test
    void worldStyleWithNoCityStylesAnywhereInTheChainIsALoadError() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new WorldStyle(TestAssetId.of("test_world"), List.of(
                        worldStyle(Optional.of("urbex:standard"), Optional.empty(), Optional.empty()))));

        assertTrue(e.getMessage().contains("citystyles"), e.getMessage());
        assertTrue(e.getMessage().contains("urbex:test_world"), e.getMessage());
    }

    @Test
    void worldStyleThatDeclaresAnEmptyCityStyleListHasDeclaredIt() {
        WorldStyle resolved = new WorldStyle(TestAssetId.of("test_world"), List.of(worldStyle(Optional.of("urbex:standard"),
                Optional.of(new Mergeable<>(true, List.of())), Optional.empty())));

        assertEquals(List.of(), cityStyleNames(resolved),
                "an empty list is a declaration - only absence reads as 'inherit'");
    }

    // ----------------------------------------------------- predefinedcities

    @Test
    void predefinedCityMayOmitCityStyleWhileKeepingItsSpatialFieldsRequired() {
        PredefinedCity resolved = new PredefinedCity(TestAssetId.of("unstyled_city"), List.of(
                new PredefinedCityDefinition(Optional.empty(), Optional.of("minecraft:overworld"),
                        Optional.of(7), Optional.of(-9), Optional.of(48), Optional.empty(),
                        Optional.empty(), Optional.empty())));

        assertNull(resolved.getCityStyle(), "absence means the ordinary world-style family applies");
        assertEquals(ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld")),
                resolved.getDimension());
        assertEquals(7, resolved.getChunkX());
        assertEquals(-9, resolved.getChunkZ());
        assertEquals(48, resolved.getRadius());
    }

    // -------------------------------------------------------------- helpers

    private static List<String> cityStyleNames(WorldStyle style) {
        return style.cityStyleSelectors().stream().map(pair -> pair.getRight().getRight().citystyle()).toList();
    }

    private static ScatteredDefinition scattered(Optional<Mergeable<String>> buildings,
                                         Optional<String> multibuilding) {
        return new ScatteredDefinition(Optional.empty(), buildings, multibuilding, Optional.empty(),
                Optional.of(ScatteredBuilding.TerrainHeight.AVERAGE),
                Optional.of(ScatteredBuilding.TerrainFix.NONE), Optional.empty());
    }

    private static MultiBuildingDefinition multiBuilding(Optional<Integer> dimX, Optional<Integer> dimZ,
                                                 Optional<List<List<String>>> buildings) {
        return new MultiBuildingDefinition(Optional.empty(), dimX, dimZ, buildings);
    }

    /**
     * Declares the {@code parts} wiring on every entry, so these tests keep failing on the field
     * each of them names rather than on the wiring - which is required of a world style's chain
     * since the code-side defaults were deleted, and has its own coverage in
     * {@link WiringRequiredTest}.
     */
    private static WorldStyleDefinition worldStyle(Optional<String> outsideStyle,
                                           Optional<Mergeable<CityStyleSelector>> cityStyles,
                                           Optional<ScatteredSettings> scattered) {
        return new WorldStyleDefinition(Optional.empty(), Optional.empty(), outsideStyle,
                Optional.empty(), Optional.empty(), scattered,
                Optional.of(TestWiring.partSelector()),
                cityStyles, Optional.empty(), Optional.empty());
    }

    private static StuffBuilder stuff() {
        return new StuffBuilder();
    }

    /** Builds a {@code stuff} entry that declares exactly the fields the test names, and no others. */
    private static final class StuffBuilder {
        private Optional<Mergeable<String>> tags = Optional.empty();
        private Optional<String> column = Optional.empty();
        private Optional<Integer> mincount = Optional.empty();
        private Optional<Integer> maxcount = Optional.empty();
        private Optional<Integer> attempts = Optional.empty();
        private Optional<Boolean> inbuilding = Optional.empty();

        StuffBuilder inbuilding(boolean inbuilding) {
            this.inbuilding = Optional.of(inbuilding);
            return this;
        }

        StuffBuilder tags(String... values) {
            this.tags = Optional.of(new Mergeable<>(true, List.of(values)));
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

        StuffSettingsDefinition build() {
            return new StuffSettingsDefinition(Optional.empty(), tags, column,
                    Optional.empty(), Optional.empty(), mincount, maxcount, attempts,
                    inbuilding, Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }
}
