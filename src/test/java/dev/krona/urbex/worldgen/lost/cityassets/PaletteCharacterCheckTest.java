package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.format.Rule;
import dev.krona.urbex.worldgen.lost.regassets.BuildingPartDefinition;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.StyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.StreetSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.TestWiring;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
class PaletteCharacterCheckTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aCharacterNoPaletteDefinesIsALoadError() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Style style = style(group(palette("base", entry('a', "minecraft:stone"))));

        PaletteCharacterCheck.check(part("tower", "ab"), usage(style), diagnostics);

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

        PaletteCharacterCheck.check(part("tower", "aa"), usage(style), diagnostics);

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

        PaletteCharacterCheck.check(part("tower", "ab"), usage(style), diagnostics);

        assertEquals(1, diagnostics.size(), () -> diagnostics.format(""));
        assertFalse(diagnostics.hasFatal(), "the world still loads: most draws place this part fine");
        assertTrue(diagnostics.problems().getFirst().message().contains("'b'"),
                diagnostics.problems().getFirst().message());
    }

    /**
     * Each character gets its own answer, even though the witness palettes they are tested against
     * are now built once for the whole part rather than once per character (issue #198).
     * <p>
     * Hoisting that construction out of the character loop is what makes this check affordable - it
     * was ~96% of a compile and 890 MB of allocation - and the way to get it wrong is to let one
     * character's result leak into the next. Here {@code 'b'} is defined by only one choice (a
     * warning), {@code 'z'} by none (a load error), and {@code 'a'} by both (silence); a shared
     * witness that had been mutated or short-circuited would collapse them together.
     */
    @Test
    void charactersInOneMixedPartAreEachAnsweredSeparately() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Style style = style(group(
                palette("rich", entry('a', "minecraft:stone"), entry('b', "minecraft:glass")),
                palette("plain", entry('a', "minecraft:stone"))));

        PaletteCharacterCheck.check(part("tower", "abz"), usage(style), diagnostics);

        assertEquals(2, diagnostics.size(), () -> diagnostics.format(""));
        assertTrue(diagnostics.hasFatal(), "'z' is defined nowhere, so the world must not load");
        String report = diagnostics.format("");
        assertTrue(report.contains("'z'"), report);
        assertTrue(report.contains("'b'"), report);
        assertFalse(report.contains("'a'"), () -> "'a' is defined by every choice: " + report);
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

        PaletteCharacterCheck.check(part("tower", "@@"), usage(style), diagnostics);

        assertTrue(diagnostics.isEmpty(),
                () -> "every selection resolves '@' through the other group: " + diagnostics.format(""));
    }

    /** A part's own palette wins over the style's, so a character it defines is always available. */
    @Test
    void aCharacterThePartDefinesItselfIsNotReported() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Style style = style(group(palette("base", entry('a', "minecraft:stone"))));

        PaletteCharacterCheck.check(partWithPalette("tower", "ab", entry('b', "minecraft:glass")),
                usage(style), diagnostics);

        assertTrue(diagnostics.isEmpty(), () -> diagnostics.format(""));
    }

    /**
     * A city style's own character fields are the same question from a different line: the generator
     * resolves {@code streetblock} against the chunk's palette exactly as it resolves a part's slice
     * characters, and an undefined one was the same crash.
     */
    @Test
    void aCityStyleCharacterNoPaletteDefinesIsALoadErrorNamingTheField() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Style style = style(group(palette("base", entry('a', "minecraft:stone"))));

        PaletteCharacterCheck.checkCityStyle(cityStyle('z'), style, diagnostics);

        assertEquals(1, diagnostics.size(), () -> diagnostics.format(""));
        assertTrue(diagnostics.hasFatal());
        String message = diagnostics.problems().getFirst().message();
        assertTrue(message.contains("'z'"), message);
        assertTrue(message.contains("streetblock"), () -> "the message has to name the field: " + message);
    }

    @Test
    void aCityStyleCharacterThePaletteDefinesIsNotReported() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Style style = style(group(palette("base", entry('a', "minecraft:stone"))));

        PaletteCharacterCheck.checkCityStyle(cityStyle('a'), style, diagnostics);

        assertTrue(diagnostics.isEmpty(), () -> diagnostics.format(""));
    }

    // ------------------------------------------------------------------ fixtures

    /** A city style whose {@code streetblock} is the character under test. */
    private static CityStyle cityStyle(char streetBlock) {
        return new CityStyle(Identifier.fromNamespaceAndPath("urbex", "citystyle_test"),
                List.of(new CityStyleDefinition(
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(),
                        Optional.of(new StreetSettings(Optional.empty(), Optional.empty(),
                                Optional.empty(), Optional.of(Character.toString(streetBlock)),
                                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.of(TestWiring.streetParts("street")), Optional.empty(),
                                Optional.empty())),
                        Optional.empty())));
    }

    private static PartUsage usage(Style style) {
        return new PartUsage(style, null, false, "parts", Identifier.fromNamespaceAndPath("urbex", "owner"));
    }

    private static BuildingPart part(String path, String slice) {
        return buildPart(path, slice, Optional.empty());
    }

    private static BuildingPart partWithPalette(String path, String slice, PaletteEntry... entries) {
        return buildPart(path, slice, Optional.<PaletteAssetDefinition>of(
                new PaletteDefinition(Optional.empty(), Optional.of(List.of(entries)))));
    }

    /**
     * A 1x1 part one level tall per character, so the slice string is exactly the character list
     * under test - any length, rather than the two the shape of this fixture used to fix it at.
     */
    private static BuildingPart buildPart(String path, String slice,
                                          Optional<PaletteAssetDefinition> local) {
        Identifier id = Identifier.fromNamespaceAndPath("urbex", path);
        List<List<String>> levels = new ArrayList<>(slice.length());
        for (char c : slice.toCharArray()) {
            levels.add(List.of(String.valueOf(c)));
        }
        return new BuildingPart(id, BuiltInRegistries.BLOCK, null, AssetIndex.empty("urbex:palettes"),
                List.of(new BuildingPartDefinition(Optional.empty(), Optional.of(1), Optional.of(1),
                        Optional.of(levels), Optional.empty(), local, Optional.empty())));
    }

    // ---- MODEL.062 at LOAD.013's stage --------------------------------------------------------

    @Rule("MODEL.062")
    @Test
    void aVersion2AliasNoDrawCanAnswerIsALoadError() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Style style = style(group(v2Palette("only_alias",
                "{ \"version\": 2, \"palette\": { \"@\": { \"kind\": \"alias\", \"of\": \"a\" } } }")));

        PaletteCharacterCheck.checkAliases(style, diagnostics);

        assertEquals(1, diagnostics.size(), () -> diagnostics.format(""));
        assertTrue(diagnostics.hasFatal(), "no selection can resolve it, so the world must not load");
        String message = diagnostics.problems().getFirst().message();
        assertTrue(dev.krona.urbex.format.Diag.DIAG_009.matches(message),
                () -> "MODEL.062 cites DIAG.009, so the message is the catalogue's: " + message);
        assertTrue(message.contains("'@'") && message.contains("'a'"),
                () -> "and it names the alias and its target: " + message);
    }

    @Rule("LOAD.013")
    @Rule("MODEL.064")
    @Test
    void aVersion2AliasAnsweredByAnotherGroupsPaletteIsReportedAsNeitherErrorNorWarning() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        // The shipped idiom, in version 2: urbex:glass_side_variant_glass maps '@' to 'a' and declares
        // nothing else at all, and the marker it names comes from a different randompalettes group.
        // LOAD.013 is explicit that this is reported as neither, and the first implementation of the
        // version 1 check reported 45 of these about a pack that generates correctly.
        Style style = style(
                group(v2Palette("side_glass",
                        "{ \"version\": 2, \"palette\": { \"@\": { \"kind\": \"alias\", \"of\": \"a\" } } }")),
                group(palette("wall_a", entry('a', "minecraft:stone")),
                        palette("wall_b", entry('a', "minecraft:stone_bricks"))));

        PaletteCharacterCheck.checkAliases(style, diagnostics);

        assertEquals(0, diagnostics.size(),
                () -> "every draw of every group defines 'a', so LOAD.013 says report nothing: "
                        + diagnostics.format(""));
    }

    @Rule("LOAD.012")
    @Test
    void aVersion2AliasOnlySomeDrawsAnswerIsAWarningRatherThanARefusal() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        Style style = style(
                group(v2Palette("side_glass",
                        "{ \"version\": 2, \"palette\": { \"@\": { \"kind\": \"alias\", \"of\": \"a\" } } }")),
                group(palette("wall_a", entry('a', "minecraft:stone")),
                        palette("wall_none", entry('b', "minecraft:stone_bricks"))));

        PaletteCharacterCheck.checkAliases(style, diagnostics);

        assertEquals(1, diagnostics.size(), () -> diagnostics.format(""));
        assertFalse(diagnostics.hasFatal(),
                "some draws work, so a pack that ships this generates correctly most of the time and "
                        + "refusing it would be this check inventing a rule");
    }

    @Rule("VER.006")
    @Rule("MODEL.060")
    @Test
    void aStyleMayDrawAVersion1AndAVersion2PaletteIntoOneMergeThatResolvesBothWays() {
        // VER.006: "a style's randompalettes may draw a version 1 palette and a version 2 palette into
        // the same merge", because that composition "operates on compiled palettes, not on extends, so
        // it needs no correspondence between the two formats".
        Palette version2 = PALETTES.get(Identifier.parse(v2Palette("mixed_v2",
                "{ \"version\": 2, \"palette\": {"
                        + " \"x\": \"minecraft:deepslate_bricks\","
                        + " \"@\": { \"kind\": \"alias\", \"of\": \"a\" } } }")));
        Palette version1 = PALETTES.get(Identifier.parse(
                palette("mixed_v1", entry('a', "minecraft:stone"))));

        CompiledPalette merged = new CompiledPalette(version1, version2);

        assertTrue(merged.isDefined('a'), "the version 1 palette's marker survives the merge");
        assertTrue(merged.isDefined('x'), "and so does the version 2 palette's");
        assertTrue(merged.isDefined('@'),
                "and a version 2 alias is answered by a marker only the version 1 palette defines, "
                        + "which is MODEL.064's 'markers contributed by palettes this file never "
                        + "mentions' arriving across a format boundary");
        assertEquals(BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:stone"))
                        .defaultBlockState(),
                merged.getAt('@', 1L, 0, 0, 0),
                "and it resolves to what its target resolves to, by MODEL.060");
    }

    /** Registers a version 2 palette from its JSON and returns its id. */
    private static String v2Palette(String path, String json) {
        Identifier id = Identifier.fromNamespaceAndPath("urbex", path);
        dev.krona.urbex.format.Diagnostics diagnostics = new dev.krona.urbex.format.Diagnostics();
        dev.krona.urbex.format.palette.PaletteV2Definition file =
                dev.krona.urbex.format.palette.PaletteV2Definition.CODEC
                        .parse(com.mojang.serialization.JsonOps.INSTANCE,
                                com.google.gson.JsonParser.parseString(json))
                        .result().orElseThrow();
        PALETTES.put(id, Palette.version2(id,
                dev.krona.urbex.format.palette.NodeResolver.resolve(file, diagnostics)
                        .flatMap(resolved -> dev.krona.urbex.format.palette.CompiledV2Palette.compile(
                                resolved,
                                dev.krona.urbex.format.palette.Exclusion.installed(
                                        BuiltInRegistries.BLOCK, java.util.Set.of("urbex", "minecraft")),
                                dev.krona.urbex.format.palette.TraitContext.of(BuiltInRegistries.BLOCK),
                                dev.krona.urbex.format.Diagnostics.DECODING_LOCATION, diagnostics))
                        .orElseThrow()));
        return id.toString();
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
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static PaletteEntry fromPalette(char marker, char target) {
        return new PaletteEntry(Character.toString(marker), Optional.empty(),
                Optional.empty(), Optional.of(Character.toString(target)), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
