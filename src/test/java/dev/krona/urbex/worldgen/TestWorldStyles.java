package dev.krona.urbex.worldgen;

import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.HighwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.RailwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.TestWiring;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A world style for tests that are about something else.
 * <p>
 * Every {@code PlanningContext} needs a {@link WorldStyleField}, and a field needs at least one
 * style - correctly, since a dimension with no world style has nothing to generate. Building one
 * means declaring everything {@link WorldStyle} requires after its {@code extends} chain resolves:
 * {@code outsidestyle}, {@code citystyles} and each of the twenty-two wiring components
 * {@code PartSelector.requireComplete} checks. That is twenty-five lines of {@code Optional.empty()}
 * no test wants to carry a copy of.
 * <p>
 * The ids are deliberately not the bundled pack's, so a test that accidentally depends on one names
 * {@code urbextest:*} in its failure rather than looking like a real style. Nothing here declares a
 * part, because a test using this is not rendering one.
 */
public final class TestWorldStyles {

    private TestWorldStyles() {
    }

    /** A field of one style, which every accessor answers without drawing from {@code Rng}. */
    public static WorldStyleField singleStyleField(long seed) {
        return new WorldStyleField(seed, List.of(new WorldStyleField.Weighted(1.0f, minimal("only"))));
    }

    /** One minimal resolvable world style, named {@code urbextest:<path>}. */
    public static WorldStyle minimal(String path) {
        return minimal(path, List.of());
    }

    /** A minimal world style carrying exactly the selector entries a scope test names. */
    public static WorldStyle minimal(String path, List<CityStyleSelector> selectors) {
        WorldStyleDefinition declaration = new WorldStyleDefinition(
                Optional.empty(),
                // No display name: these exist to be told apart by id, and getName() is what the
                // field's primary tie-break reads.
                Optional.empty(),
                Optional.of("urbex:standard"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new PartSelector.Decl(
                        Optional.of(new HighwayParts.Decl(
                                noParts(), noParts(), noParts(), noParts(), noParts(), noParts())),
                        Optional.of(new RailwayParts.Decl(
                                noParts(), noParts(), noParts(), noParts(), noParts(), noParts(),
                                noParts(), noParts(), noParts(), noParts(), noParts(), noParts(),
                                noParts(), noParts(), noParts(), noParts())))),
                Optional.of(new Mergeable<>(true, selectors)),
                Optional.empty(),
                Optional.empty());
        return new WorldStyle(Identifier.fromNamespaceAndPath("urbextest", path), List.of(declaration));
    }

    /** A minimal resolvable city style, named {@code urbextest:<path>}. */
    public static CityStyle cityStyle(String path) {
        return new CityStyle(Identifier.fromNamespaceAndPath("urbextest", path), List.of(new CityStyleDefinition(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(TestWiring.streetSettings()),
                Optional.empty())));
    }

    private static Optional<Mergeable<String>> noParts() {
        return Optional.of(new Mergeable<>(true, Collections.emptyList()));
    }
}
