package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.BuildingPartDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.StyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteSelector;
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
 * Whether a part's slice characters resolve where the part is used (issue #56).
 * <p>
 * A character that resolves to nothing is {@code "Could not find entry 'x' in the palette for part
 * 'y'!"} thrown from a worldgen worker, on the first chunk that places the part.
 * <p>
 * The hard part is not finding an undefined character; it is not reporting a defined one. A style's
 * palette is one choice per {@code randompalettes} group, and palettes cross-reference each other
 * with {@code frompalette}, so the naive test - does this one choice define the character - flags the
 * shipped pack's own idiom. It did: {@code urbex:glass_side_variant_glass} maps {@code '@'} to
 * {@code 'a'} and nothing else, and the first version of this check produced 45 warnings about a pack
 * that is correct. {@link #aCharacterReachedThroughAnotherGroupsPaletteIsNotReported} is that bug.
 */
class PartPaletteCheckTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aCharacterNoPaletteDefinesIsALoadError() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Style style = style(group(palette("base", entry('a', "minecraft:stone"))));

        PartPaletteCheck.check(part("tower", "ab"), usage(style), diagnostics);

        assertEquals(1, diagnostics.size(), () -> diagnostics.format(""));
        assertTrue(diagnostics.hasFatal(), "no selection can place it, so the world must not load");
        assertTrue(diagnostics.problems().getFirst().message().contains("'b'"),
                diagnostics.problems().getFirst().message());
    }

    @Test
    void aCharacterEveryChoiceDefinesIsNotReported() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Style style = style(group(
                palette("light", entry('a', "minecraft:stone")),
                palette("dark", entry('a', "minecraft:deepslate"))));

        PartPaletteCheck.check(part("tower", "aa"), usage(style), diagnostics);

        assertTrue(diagnostics.isEmpty(), () -> diagnostics.format(""));
    }

    /**
     * The defect that is real but not fatal: the character exists, so some worlds place the part and
     * some crash on it. Refusing the world would make packs that mostly work stop loading, which is
     * this check inventing a rule rather than reporting a break - so it is a warning, and the world
     * still loads.
     */
    @Test
    void aCharacterOnlySomeChoicesDefineIsAWarningRatherThanARefusal() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Style style = style(group(
                palette("rich", entry('a', "minecraft:stone"), entry('b', "minecraft:glass")),
                palette("plain", entry('a', "minecraft:stone"))));

        PartPaletteCheck.check(part("tower", "ab"), usage(style), diagnostics);

        assertEquals(1, diagnostics.size(), () -> diagnostics.format(""));
        assertFalse(diagnostics.hasFatal(), "the world still loads: most draws place this part fine");
        assertTrue(diagnostics.problems().getFirst().message().contains("'b'"),
                diagnostics.problems().getFirst().message());
    }

    /**
     * The shipped pack's idiom, and the bug this check had first. {@code '@'} is defined only as
     * "whatever {@code 'a'} is", and {@code 'a'} comes from a different {@code randompalettes} group -
     * so testing a choice in isolation says undefined, while every real selection resolves it.
     */
    @Test
    void aCharacterReachedThroughAnotherGroupsPaletteIsNotReported() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Style style = style(
                group(palette("blocks", entry('a', "minecraft:stone"))),
                group(palette("aliases", fromPalette('@', 'a'))));

        PartPaletteCheck.check(part("tower", "@@"), usage(style), diagnostics);

        assertTrue(diagnostics.isEmpty(),
                () -> "every selection resolves '@' through the other group: " + diagnostics.format(""));
    }

    /** A part's own palette wins over the style's, so a character it defines is always available. */
    @Test
    void aCharacterThePartDefinesItselfIsNotReported() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Style style = style(group(palette("base", entry('a', "minecraft:stone"))));

        PartPaletteCheck.check(partWithPalette("tower", "ab", entry('b', "minecraft:glass")),
                usage(style), diagnostics);

        assertTrue(diagnostics.isEmpty(), () -> diagnostics.format(""));
    }

    // ------------------------------------------------------------------ fixtures

    private static PartUsage usage(Style style) {
        return new PartUsage(style, null, false, "parts", Identifier.fromNamespaceAndPath("urbex", "owner"));
    }

    private static BuildingPart part(String path, String slice) {
        return buildPart(path, slice, Optional.empty());
    }

    private static BuildingPart partWithPalette(String path, String slice, PaletteEntry... entries) {
        return buildPart(path, slice, Optional.of(
                new PaletteDefinition(Optional.empty(), Optional.of(List.of(entries)))));
    }

    /** A 1x{@code slice.length()} part, so the slice string is the character list under test. */
    private static BuildingPart buildPart(String path, String slice, Optional<PaletteDefinition> local) {
        Identifier id = Identifier.fromNamespaceAndPath("urbex", path);
        return new BuildingPart(id, BuiltInRegistries.BLOCK, null, AssetIndex.empty("urbex:palettes"),
                List.of(new BuildingPartDefinition(Optional.empty(), Optional.of(1), Optional.of(1),
                        Optional.of(List.of(List.of(slice.substring(0, 1)), List.of(slice.substring(1)))),
                        Optional.empty(), local, Optional.empty())));
    }

    @SafeVarargs
    private static Style style(List<PaletteSelector>... groups) {
        Map<Identifier, Palette> byId = new HashMap<>();
        for (List<PaletteSelector> group : groups) {
            for (PaletteSelector selector : group) {
                Identifier id = Identifier.parse(selector.palette());
                byId.put(id, PALETTES.get(id));
            }
        }
        return new Style(Identifier.fromNamespaceAndPath("urbex", "test_style"),
                new AssetIndex<>("urbex:palettes", byId),
                List.of(new StyleDefinition(Optional.empty(),
                        Optional.of(new Mergeable<>(true, List.of(groups))))));
    }

    private static final Map<Identifier, Palette> PALETTES = new HashMap<>();

    private static List<PaletteSelector> group(String... palettes) {
        return List.of(palettes).stream().map(p -> new PaletteSelector(1.0f, p)).toList();
    }

    /** Registers a palette and returns its id, so {@link #group} can name it. */
    private static String palette(String path, PaletteEntry... entries) {
        Identifier id = Identifier.fromNamespaceAndPath("urbex", path);
        PALETTES.put(id, new Palette(id, BuiltInRegistries.BLOCK, null,
                List.of(new PaletteDefinition(Optional.empty(), Optional.of(List.of(entries))))));
        return id.toString();
    }

    private static PaletteEntry entry(char marker, String block) {
        return new PaletteEntry(Character.toString(marker), Optional.of(block),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static PaletteEntry fromPalette(char marker, char target) {
        return new PaletteEntry(Character.toString(marker), Optional.empty(),
                Optional.empty(), Optional.of(Character.toString(target)), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }
}
