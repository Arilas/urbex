package dev.krona.urbex.worldgen.lost.cityassets;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.HighwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.RailwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.StreetParts;
import dev.krona.urbex.worldgen.lost.regassets.data.StreetSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.TestWiring;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Street, highway and railway part wiring is declared by the datapack or it is a load error.
 * <p>
 * It used to be neither: thirty {@code Tools.listOrStringList} call sites carried a bare asset name
 * as a default, so a world style that never mentioned primary roads still generated Urbex's own
 * {@code urbex:street_large_*} parts and a pack author had nothing to grep for. Requiredness is
 * checked after the {@code extends} chain is applied, exactly as in {@link RequiredAfterResolutionTest}
 * - a child that overrides one family must still be able to omit the rest.
 * <p>
 * The other half is the append opt-in the same change made possible: because the families are now
 * folded component by component rather than swapped whole, a child can add one street variant to the
 * ones it inherits with {@code {"replace": false, "values": [...]}}, which is what spec section 4
 * promised for ordered part lists.
 */
class WiringRequiredTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ------------------------------------------------- the three codec arms

    @Test
    void aPartFieldTakesABareString_aBareArray_orTheAppendObject() {
        StreetParts.Decl decl = decodeParts("""
                {
                  "straight": "urbex:street_straight",
                  "end": ["urbex:street_end", "urbex:street_end_alt"],
                  "bend": {"replace": false, "values": ["urbex:street_bend_alt"]}
                }
                """);

        assertEquals(Optional.of(new Mergeable<>(true, List.of("urbex:street_straight"))), decl.straight(),
                "a bare string is one id, replacing");
        assertEquals(Optional.of(new Mergeable<>(true, List.of("urbex:street_end", "urbex:street_end_alt"))),
                decl.end(), "a bare array replaces");
        assertEquals(Optional.of(new Mergeable<>(false, List.of("urbex:street_bend_alt"))), decl.bend(),
                "the object form opts into appending");
        assertEquals(Optional.empty(), decl.t(),
                "a field the file does not mention is absent, not a default part name");
    }

    /**
     * The encode direction, which nothing in the mod exercises and which an {@code xmap} makes easy
     * to get silently wrong: each arm has to come back out in the shape it went in, and an absent
     * field has to stay absent rather than being written back as an empty list.
     */
    @Test
    void eachArmEncodesBackToTheShapeItWasWrittenIn() {
        String json = """
                {"straight":"urbex:street_straight",\
                "end":["urbex:street_end","urbex:street_end_alt"],\
                "bend":{"replace":false,"values":["urbex:street_bend_alt"]}}""";

        JsonElement encoded = StreetParts.Decl.CODEC
                .encodeStart(JsonOps.INSTANCE, decodeParts(json)).getOrThrow();

        assertEquals(JsonParser.parseString(json), encoded);
    }

    // ------------------------------------------------------ the append opt-in

    @Test
    void aChildAppendsToTheStraightPartsItInheritsWhenItOptsIn() {
        CityStyle resolved = new CityStyle(TestAssetId.of("citystyle_parent"), List.of(
                cityStyle("parent", TestWiring.streetParts("street")),
                cityStyle("child", partsWith("straight",
                        new Mergeable<>(false, List.of("urbex:street_straight_alt"))))));

        assertEquals(List.of("urbex:test_street_straight", "urbex:street_straight_alt"),
                resolved.getStreetParts().straight(),
                "appended entries follow the inherited ones, so the parent's order is stable");
        assertEquals(List.of("urbex:test_street_bend"), resolved.getStreetParts().bend(),
                "a component the child never mentions is inherited unchanged");
    }

    @Test
    void aChildsBareArrayReplacesTheStraightPartsItInherits() {
        CityStyle resolved = new CityStyle(TestAssetId.of("citystyle_parent"), List.of(
                cityStyle("parent", TestWiring.streetParts("street")),
                cityStyle("child", partsWith("straight",
                        new Mergeable<>(true, List.of("urbex:street_straight_alt"))))));

        assertEquals(List.of("urbex:street_straight_alt"), resolved.getStreetParts().straight(),
                "a bare array is the whole list: no inherited entries, no duplicates");
    }

    @Test
    void aChildAppendsToAHighwayGroupItInherits() {
        WorldStyleDefinition parent = worldStyle("parent", TestWiring.partSelector());
        WorldStyleDefinition child = worldStyle("child", new PartSelector.Decl(
                Optional.of(new HighwayParts.Decl(
                        Optional.of(new Mergeable<>(false, List.of("urbex:highway_tunnel_alt"))),
                        Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty())),
                Optional.empty()));

        HighwayParts resolved = new WorldStyle(TestAssetId.of("test_world"), List.of(parent, child)).getPartSelector().highwayParts();

        assertEquals(List.of("urbex:test_highway_tunnel", "urbex:highway_tunnel_alt"), resolved.tunnel());
        assertEquals(List.of("urbex:test_highway_open"), resolved.open(),
                "a field the child never mentions is inherited unchanged");
        assertEquals(List.of("urbex:test_rails_bend"),
                new WorldStyle(TestAssetId.of("test_world"), List.of(parent, child)).getPartSelector().railwayParts().railsBend(),
                "and so is the group it never mentions");
    }

    // ------------------------------------------------ required after the chain

    @Test
    void aCityStyleWithNoStreetPartsAnywhereInTheChainIsALoadError() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CityStyle(TestAssetId.of("citystyle_bare"), List.of(cityStyle("bare", null))));

        assertTrue(e.getMessage().contains("streetblocks.parts"), e.getMessage());
        assertTrue(e.getMessage().contains("urbex:citystyle_bare"), e.getMessage());
    }

    @Test
    void aCityStyleThatDeclaresHalfAFamilyIsALoadErrorNamingTheMissingComponent() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CityStyle(TestAssetId.of("citystyle_half"), List.of(cityStyle("half",
                        partsWith("straight", new Mergeable<>(true, List.of("urbex:street_straight")))))));

        assertTrue(e.getMessage().contains("streetblocks.parts.end"), e.getMessage());
        assertTrue(e.getMessage().contains("urbex:citystyle_half"), e.getMessage());
    }

    @Test
    void aChildThatDeclaresOneComponentStillLoadsWhenAnAncestorCoversTheRest() {
        CityStyle resolved = new CityStyle(TestAssetId.of("citystyle_parent"), List.of(
                cityStyle("parent", TestWiring.streetParts("street")),
                cityStyle("child", partsWith("all",
                        new Mergeable<>(true, List.of("urbex:street_all_alt"))))));

        assertEquals(List.of("urbex:street_all_alt"), resolved.getStreetParts().all());
        assertEquals(List.of("urbex:test_street_stair"), resolved.getStreetParts().stair(),
                "the seven components the child omits come from the chain, not from a codec default");
    }

    @Test
    void aHalfDeclaredLargePartsFamilyIsALoadErrorEvenThoughTheFamilyItselfIsOptional() {
        StreetSettings settings = new StreetSettings(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(TestWiring.streetParts("street")),
                Optional.of(partsWith("straight", new Mergeable<>(true, List.of("urbex:street_large_straight")))),
                Optional.empty());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CityStyle(TestAssetId.of("citystyle_halflarge"), List.of(cityStyleDefinition("halflarge", settings))));

        assertTrue(e.getMessage().contains("streetblocks.largeparts.end"), e.getMessage());
    }

    @Test
    void theLargeAndTertiaryFamiliesFallBackToTheSecondaryOneWhenNothingDeclaresThem() {
        CityStyle resolved = new CityStyle(TestAssetId.of("citystyle_plain"), List.of(cityStyle("plain", TestWiring.streetParts("street"))));

        assertSame(resolved.getStreetParts(), resolved.getLargeStreetParts(),
                "primary roads draw from the pack's own secondary parts, not from Java's");
        assertSame(resolved.getStreetParts(), resolved.getTertiaryStreetParts());
    }

    @Test
    void aWorldStyleWithNoPartsBlockAnywhereInTheChainIsALoadError() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new WorldStyle(TestAssetId.of("bare"), List.of(worldStyle("bare", null))));

        assertTrue(e.getMessage().contains("'parts'"), e.getMessage());
        assertTrue(e.getMessage().contains("urbex:bare"), e.getMessage());
    }

    @Test
    void aWorldStyleThatDeclaresOnlyHighwaysIsALoadErrorNamingRailways() {
        PartSelector.Decl highwaysOnly = new PartSelector.Decl(
                TestWiring.partSelector().highways(), Optional.empty());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new WorldStyle(TestAssetId.of("nrail"), List.of(worldStyle("nrail", highwaysOnly))));

        assertTrue(e.getMessage().contains("parts.railways"), e.getMessage());
        assertTrue(e.getMessage().contains("urbex:nrail"), e.getMessage());
    }

    @Test
    void aWorldStyleThatDeclaresHalfARailwayGroupIsALoadErrorNamingTheMissingField() {
        PartSelector.Decl halfRail = new PartSelector.Decl(
                TestWiring.partSelector().highways(),
                Optional.of(new RailwayParts.Decl(
                        Optional.of(new Mergeable<>(true, List.of("urbex:station_underground"))),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty())));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new WorldStyle(TestAssetId.of("halfrail"), List.of(worldStyle("halfrail", halfRail))));

        assertTrue(e.getMessage().contains("parts.railways.stationopen"), e.getMessage());
    }

    // -------------------------------------------------------------- helpers

    private static StreetParts.Decl decodeParts(String json) {
        return StreetParts.Decl.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    /** A {@code parts} block declaring exactly one component and leaving the other seven absent. */
    private static StreetParts.Decl partsWith(String component, Mergeable<String> value) {
        Optional<Mergeable<String>> v = Optional.of(value);
        Optional<Mergeable<String>> no = Optional.empty();
        return switch (component) {
            case "straight" -> new StreetParts.Decl(v, no, no, no, no, no, no, no);
            case "all" -> new StreetParts.Decl(no, no, no, no, no, v, no, no);
            default -> throw new IllegalArgumentException(component);
        };
    }

    private static CityStyleDefinition cityStyle(String name, StreetParts.Decl parts) {
        return cityStyleDefinition(name, parts == null ? null : TestWiring.streetSettings(parts));
    }

    private static CityStyleDefinition cityStyleDefinition(String name, StreetSettings settings) {
        return new CityStyleDefinition(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.ofNullable(settings), Optional.empty());
    }

    private static WorldStyleDefinition worldStyle(String name, PartSelector.Decl parts) {
        return new WorldStyleDefinition(Optional.empty(), Optional.empty(), Optional.of("urbex:outside"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(parts),
                Optional.of(new Mergeable<>(true, List.of())), Optional.empty(), Optional.empty());
    }
}
