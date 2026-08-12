package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.CityStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.TestWiring;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The world-style and city-style half of {@code name}: folded root-first like every other scalar,
 * falling back to the fully-qualified id when the chain declares none.
 * <p>
 * The fallback is what these mostly pin. Both pickers showed the id before the field existed, and
 * both must keep doing so for an asset that never grew a name - the field is there to remove the
 * namespacing from what a player reads, not to make an unnamed asset unreadable.
 * <p>
 * Like the other chain tests here, these build the chain by hand and let the constructor apply it
 * root-first rather than resolving ids through a registry: the fold itself needs no world.
 */
class StyleDisplayNameTest {

    /** {@code WorldStyle}'s rotatable fallback resolves a {@code TagKey}, which needs the registries. */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    private static WorldStyleDefinition worldStyle(Optional<String> name) {
        return new WorldStyleDefinition(Optional.empty(), name, Optional.of("urbex:outside"),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(TestWiring.partSelector()),
                Optional.of(new Mergeable<>(true,
                        List.of(new CityStyleSelector(1.0f, "urbex:citystyle_common", null)))),
                Optional.empty(), Optional.empty());
    }

    private static CityStyleDefinition cityStyle(Optional<String> name) {
        return new CityStyleDefinition(
                Optional.empty(), Optional.empty(), Optional.empty(), name,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(TestWiring.streetSettings()),
                Optional.empty());
    }

    // ---------------------------------------------------------------- world styles

    @Test
    void aWorldStyleDeclaringNoNameIsLabelledByItsId() {
        WorldStyle resolved = new WorldStyle(id("urbex", "standard"), List.of(worldStyle(Optional.empty())));

        assertEquals("urbex:standard", resolved.getDisplayName(),
                "every world style written before this field existed must keep reading as its id");
    }

    @Test
    void aWorldStyleShowsTheNameItDeclares() {
        WorldStyle resolved = new WorldStyle(id("urbex", "standard"), List.of(worldStyle(Optional.of("Standard"))));

        assertEquals("Standard", resolved.getDisplayName());
    }

    /**
     * The case Urbex-Zombie-Apocalypse-Essentials actually hits: its world style extends
     * {@code urbex:standard}, which ships {@code "name": "Standard"}, so without one of its own it
     * would be offered in the picker as "Standard".
     */
    @Test
    void aWorldStyleWithoutANameInheritsItsParents() {
        WorldStyle resolved = new WorldStyle(id("urbexza", "zombie_apocalypse"), List.of(
                worldStyle(Optional.of("Standard")),
                worldStyle(Optional.empty())));

        assertEquals("Standard", resolved.getDisplayName(),
                "the inheritance is why every shipped world style restates its own name");
    }

    @Test
    void aWorldStylesOwnNameWinsOverTheOneItExtends() {
        WorldStyle resolved = new WorldStyle(id("urbexza", "zombie_apocalypse"), List.of(
                worldStyle(Optional.of("Standard")),
                worldStyle(Optional.of("Zombie Apocalypse"))));

        assertEquals("Zombie Apocalypse", resolved.getDisplayName());
    }

    @Test
    void anEmptyWorldStyleNameIsTreatedAsNoneRatherThanAsABlankLabel() {
        WorldStyle resolved = new WorldStyle(id("urbex", "standard"), List.of(worldStyle(Optional.of(""))));

        assertEquals("urbex:standard", resolved.getDisplayName());
    }

    /**
     * The world-style picker labels rows from a registry it cannot build whole {@link WorldStyle}s
     * out of - doing so would demand a complete chain, which is worldgen's check to make and not a
     * dropdown's. It calls this instead, so the two must agree.
     */
    @Test
    void theStaticFoldTheGuiUsesMatchesWhatTheConstructorResolves() {
        Identifier leaf = id("urbexmt", "moderntweaks");
        List<WorldStyleDefinition> chain = List.of(
                worldStyle(Optional.of("Standard")),
                worldStyle(Optional.of("Modern Tweaks")));

        assertEquals(new WorldStyle(leaf, chain).getDisplayName(), WorldStyle.displayNameOf(chain, leaf));
        assertEquals("urbexmt:moderntweaks",
                WorldStyle.displayNameOf(List.of(worldStyle(Optional.empty())), leaf));
    }

    // ---------------------------------------------------------------- city styles

    @Test
    void aCityStyleDeclaringNoNameIsLabelledByItsId() {
        CityStyle resolved = new CityStyle(id("urbex", "citystyle_standard"), List.of(cityStyle(Optional.empty())));

        assertEquals("urbex:citystyle_standard", resolved.getDisplayName());
    }

    @Test
    void aCityStyleShowsTheNameItDeclares() {
        CityStyle resolved = new CityStyle(id("urbex", "citystyle_desert"), List.of(cityStyle(Optional.of("Desert"))));

        assertEquals("Desert", resolved.getDisplayName());
    }

    /**
     * Why the two abstract bases ({@code urbex:citystyle_common}, {@code urbex:citystyle_config})
     * deliberately declare no name: an unnamed child of a named base borrows a label belonging to
     * something else, where an unnamed child of an unnamed base falls back to its own id.
     */
    @Test
    void aCityStyleWithoutANameInheritsItsParents() {
        CityStyle borrowed = new CityStyle(id("mypack", "downtown"), List.of(
                cityStyle(Optional.of("Common")),
                cityStyle(Optional.empty())));
        assertEquals("Common", borrowed.getDisplayName());

        CityStyle ownId = new CityStyle(id("mypack", "downtown"), List.of(
                cityStyle(Optional.empty()),
                cityStyle(Optional.empty())));
        assertEquals("mypack:downtown", ownId.getDisplayName(),
                "an unnamed base is what lets an unnamed child keep its own identity");
    }

    @Test
    void aCityStylesOwnNameWinsOverTheOneItExtends() {
        CityStyle resolved = new CityStyle(id("urbexmt", "citystyle_desert"), List.of(
                cityStyle(Optional.of("Common")),
                cityStyle(Optional.of("Modern Desert"))));

        assertEquals("Modern Desert", resolved.getDisplayName());
    }

    /** {@code getName()} stays the id: worldgen, logs and conditions all key off it. */
    @Test
    void namingAStyleDoesNotChangeWhatGetNameReturns() {
        CityStyle city = new CityStyle(id("urbex", "citystyle_desert"), List.of(cityStyle(Optional.of("Desert"))));
        assertEquals("urbex:citystyle_desert", city.getName());

        WorldStyle world = new WorldStyle(id("urbex", "standard"), List.of(worldStyle(Optional.of("Standard"))));
        assertEquals("urbex:standard", world.getName());
    }
}
