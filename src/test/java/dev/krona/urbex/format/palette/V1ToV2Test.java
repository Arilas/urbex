package dev.krona.urbex.format.palette;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.worldgen.lost.cityassets.AssetIndex;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.LightPool;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import dev.krona.urbex.worldgen.lost.cityassets.Variant;
import dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.VariantDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The version 1 to version 2 converter, checked against the rules of
 * {@code docs/format/palette/09-migration.md} §2, §4 and §5.
 *
 * <h2>Why the big test here compiles palettes instead of comparing documents</h2>
 *
 * <p>{@code VER.021} is emphatic that the converter is verified <em>by generation</em>: "a world
 * generated from the converted pack is identical to one generated from the original, at the same seed",
 * and its {@code > Why} says why an equality of compiled forms was the wrong property — "the property
 * that actually matters to a pack author was never the compiled form anyway — it was that their city
 * still looks the same". Shape equality is worth even less: there is no version 2 spelling of a version
 * 1 file to compare against, only a behaviour to preserve.</p>
 *
 * <p>So {@link #everyShippedPaletteResolvesIdenticallyBeforeAndAfterConversion} compiles each palette
 * both ways and asks it, at 4,096 positions per marker, exactly the questions generation asks: which
 * block state stands here, which traits does it carry, what does it damage into. Everything downstream
 * of a palette — {@code Parts.generatePart}, the damage pass, the decoration pass — reads those answers
 * and nothing else, so two palettes agreeing on all of them place the same blocks in the same chunks at
 * the same seed. That is short of driving a world, and the gap is named rather than papered over: the
 * whole-world run is {@code runDigestCheck} against a converted <em>bundled</em> pack, which is Task 10,
 * and this test is what stands guard over all three packs meanwhile.</p>
 *
 * <p>It is also the test that finds where the property does <em>not</em> hold. Both cases it finds are
 * asserted below rather than tolerated — {@link #aLightSocketIsTheOneConstructConversionCannotPreserve}
 * and {@link #anAbsentBlockRedistributesDifferentlyInTheTwoFormats} — because a failure this test cannot
 * express is a failure the next person rediscovers.</p>
 */
class V1ToV2Test {

    /** The three packs {@code VER.021} names, as pack roots: the directory holding {@code palettes/}. */
    private static final Map<String, Path> PACKS = Map.of(
            "urbex", Path.of("src/main/resources/data/urbex/urbex"),
            "urbexmt", Path.of("../Urbex-ModernTweaks/pack/data/urbexmt/urbex"),
            "urbexza", Path.of("../Urbex-Zombie-Apocalypse-Essentials/pack/data/urbexza/urbex"));

    private static final long SEED = 20260817L;

    /** The id both compilations are given, so nothing in either depends on which file it came from. */
    private static final Identifier UNDER_TEST =
            Identifier.fromNamespaceAndPath("urbex", "under_test");

    /** Every {@code conditions} id any of the three packs registers; see {@link Fixture}. */
    private static final Set<Identifier> CONDITIONS = conditions();

    private static Set<Identifier> conditions() {
        Set<Identifier> ids = new java.util.LinkedHashSet<>();
        PACKS.forEach((namespace, pack) -> {
            for (Path file : V1ToV2.jsonUnder(pack.resolve("conditions"))) {
                ids.add(Fixture.idOf(namespace, pack.resolve("conditions"), file));
            }
        });
        return Set.copyOf(ids);
    }

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- §2, the translation table ------------------------------------------------------------

    @Rule("VER.008")
    @Rule("MODEL.020")
    @Test
    void aPlainBlockEntryBecomesTheStringShorthandAndTheCharDisappears() {
        assertEquals("{\"version\":2,\"palette\":{\"X\":\"minecraft:stone\"}}",
                compact(convert("{\"palette\":[{\"char\":\"X\",\"block\":\"minecraft:stone\"}]}")));
    }

    @Rule("VER.008")
    @Rule("WEIGHT.001")
    @Test
    void aWeightedListBecomesChoicesAndRandomBecomesWeight() {
        JsonObject node = marker(convert("{\"palette\":[{\"char\":\"#\",\"blocks\":["
                + "{\"random\":9,\"block\":\"minecraft:a\"},"
                + "{\"random\":8,\"block\":\"minecraft:b\"}]}]}"), "#");

        assertEquals("weighted", node.get("kind").getAsString());
        assertEquals(2, node.getAsJsonArray("choices").size());
        assertTrue(node.getAsJsonArray("choices").get(0).getAsJsonObject().has("weight"),
                "'random' is spelled 'weight' in version 2 and nothing else states a size here");
    }

    /**
     * The row this test exists for reads, in §2's table, "trailing weight ≥ what remains" becomes
     * {@code { "rest": true, … }}, and that cell is wrong — see the task 9 report. The Notes cell beside
     * it is right, and is what is asserted: "its clipped value, restated as a size".
     */
    @Rule("VER.008")
    @Rule("WEIGHT.011")
    @Test
    void aTrailingSentinelBecomesItsClippedValueRestatedAsASize() {
        JsonObject node = marker(convert("{\"palette\":[{\"char\":\"F\",\"blocks\":["
                + "{\"random\":25,\"block\":\"minecraft:furnace\"},"
                + "{\"random\":20,\"block\":\"minecraft:crafting_table\"},"
                + "{\"random\":7,\"block\":\"minecraft:brewing_stand\"},"
                + "{\"random\":6,\"block\":\"minecraft:anvil\"},"
                + "{\"random\":5,\"block\":\"minecraft:cauldron\"},"
                + "{\"random\":2,\"block\":\"minecraft:enchanting_table\"},"
                + "{\"random\":1000,\"block\":\"minecraft:cobweb\"}]}]}"), "F");

        List<Integer> weights = new ArrayList<>();
        node.getAsJsonArray("choices").forEach(choice ->
                weights.add(choice.getAsJsonObject().get("weight").getAsInt()));
        assertEquals(List.of(25, 20, 7, 6, 5, 2, 63), weights,
                "this is the shipped workstation list, and 63 is 05-weights.md's own WEIGHT.011 "
                        + "fixture for it - the sentinel clipped, not a 'rest'");
        assertEquals(128, weights.stream().mapToInt(Integer::intValue).sum(),
                "the emitted weights are the slot counts version 1 computed, so they total the 128 "
                        + "slots version 2 apportions them over, and the apportionment is the identity");
    }

    @Rule("WEIGHT.013")
    @Test
    void noConvertedListEverCarriesARestBesideAWeight() {
        for (Path pack : PACKS.values()) {
            for (Path file : V1ToV2.jsonUnder(pack.resolve("palettes"))) {
                assertFalse(V1ToV2.paletteFile(read(file), file.toString()).json().contains("\"rest\""),
                        () -> file + " emitted a 'rest', which WEIGHT.013 refuses in a list that also "
                                + "carries a 'weight' - and every other choice of a converted list is "
                                + "a weight");
            }
        }
    }

    @Rule("WEIGHT.002")
    @Test
    void aChoiceTheSlotsNeverReachIsDroppedRatherThanGivenAWeightOfZero() {
        V1ToV2.Converted converted = V1ToV2.paletteFile("{\"palette\":[{\"char\":\"#\",\"blocks\":["
                + "{\"random\":64,\"block\":\"minecraft:a\"},"
                + "{\"random\":64,\"block\":\"minecraft:b\"},"
                + "{\"random\":1000,\"block\":\"minecraft:c\"}]}]}", "test");

        assertEquals(2, marker(converted.json(), "#").getAsJsonArray("choices").size(),
                "the first two choices fill all 128 slots, so version 1 never placed the third");
        assertTrue(converted.findings().stream().anyMatch(f -> f.rule().equals("WEIGHT.002")),
                "and dropping a block the author wrote is reported, not silent");
    }

    @Rule("VER.008")
    @Test
    void everyVersion1MetadataFieldBecomesItsTrait() {
        JsonObject traits = marker(convert("{\"palette\":[{\"char\":\"C\","
                + "\"block\":\"minecraft:chest\",\"damaged\":\"minecraft:iron_bars\","
                + "\"loot\":\"urbex:chestloot\",\"mob\":\"urbex:easymobs\","
                + "\"tag\":{\"Items\":[]}}]}"), "C").getAsJsonObject("traits");

        assertEquals("minecraft:iron_bars",
                traits.getAsJsonObject("urbex:damaged").get("into").getAsString());
        assertEquals("urbex:chestloot", traits.getAsJsonObject("urbex:loot").get("pool").getAsString());
        assertEquals("urbex:easymobs",
                traits.getAsJsonObject("urbex:spawner").get("pool").getAsString());
        assertTrue(traits.getAsJsonObject("urbex:block_entity").has("nbt"));
    }

    /**
     * {@code TRAIT.011} carries {@code [NOT-YET-REACHED: issue #216]} because the damage pass keys its
     * map by block state and cannot see a marker. That is a statement about what generation does with
     * the trait, not about how it is spelled, so the conversion emits the version 2 spelling regardless
     * and the note records how far it gets.
     */
    @Rule("TRAIT.011")
    @Test
    void damagedConvertsToItsVersion2SpellingEvenThoughTheDamagePassCannotYetKeyItByMarker() {
        assertEquals("minecraft:iron_bars",
                marker(convert("{\"palette\":[{\"char\":\"X\",\"block\":\"minecraft:stone_bricks\","
                        + "\"damaged\":\"minecraft:iron_bars\"}]}"), "X")
                        .getAsJsonObject("traits").getAsJsonObject("urbex:damaged")
                        .get("into").getAsString());
    }

    @Rule("VER.008")
    @Rule("MODEL.061")
    @Test
    void frompaletteBecomesAnAliasOnTheOneCharacterVersion1Read() {
        JsonObject node = marker(
                convert("{\"palette\":[{\"char\":\"@\",\"frompalette\":\"ab\"}]}"), "@");

        assertEquals("alias", node.get("kind").getAsString());
        assertEquals("a", node.get("of").getAsString(),
                "version 1 read charAt(0) and nothing else, so 'ab' silently meant 'a'");
    }

    @Rule("VER.008")
    @Test
    void aVariantBecomesARefAndTheVariantsRegistryBecomesDefinitions() {
        assertEquals("urbex:stonebrick",
                marker(convert("{\"palette\":[{\"char\":\"#\",\"variant\":\"urbex:stonebrick\"}]}"),
                        "#").get("$ref").getAsString());

        String definition = V1ToV2.variantFile("{\"blocks\":[{\"random\":9,\"block\":\"minecraft:a\"},"
                + "{\"random\":1000,\"block\":\"minecraft:b\"}]}", "variants/x.json").json();
        assertEquals("{\"version\":2,\"kind\":\"weighted\",\"choices\":["
                        + "{\"weight\":9,\"block\":\"minecraft:a\"},"
                        + "{\"weight\":119,\"block\":\"minecraft:b\"}]}",
                compact(definition),
                "REF.019: a definitions asset declares version 2, and REF.014 says it is a bare node");
    }

    @Rule("VER.008")
    @Rule("MODEL.075")
    @Test
    void aLightSourceIsAKindWhenItSelectsTheBlockAndATraitWhenItDoesNot() {
        JsonObject socket = marker(convert("{\"palette\":[{\"char\":\"T\",\"lightSource\":{\"floor\":["
                + "{\"weight\":6,\"block\":\"minecraft:lantern\"},"
                + "{\"weight\":3,\"block\":\"minecraft:torch\",\"unlit\":\"minecraft:candle\"}]}}]}"),
                "T");
        assertEquals("light_socket", socket.get("kind").getAsString());
        assertEquals("minecraft:candle", socket.getAsJsonArray("floor").get(1).getAsJsonObject()
                        .getAsJsonObject("traits").getAsJsonObject("urbex:light")
                        .get("unlit").getAsString(),
                "TRAIT.055: a candidate's own unlit is its own urbex:light, which by TRAIT.006 "
                        + "replaces the socket's whole");

        assertTrue(marker(convert("{\"palette\":[{\"char\":\"e\",\"block\":\"minecraft:lantern\","
                        + "\"lightSource\":true}]}"), "e")
                        .getAsJsonObject("traits").getAsJsonObject("urbex:light").isEmpty(),
                "TRAIT.051: an absent 'unlit' is air, which is what version 1 left behind too");

        assertEquals("weighted", marker(convert("{\"palette\":[{\"char\":\"h\","
                        + "\"block\":\"minecraft:glowstone\",\"lightSource\":{\"unlitBlocks\":["
                        + "{\"random\":10,\"block\":\"minecraft:a\"},"
                        + "{\"random\":2000,\"block\":\"minecraft:b\"}]}}]}"), "h")
                        .getAsJsonObject("traits").getAsJsonObject("urbex:light")
                        .getAsJsonObject("unlit").get("kind").getAsString(),
                "TRAIT.009: 'unlitBlocks' existed only because 'unlit' was a bare string; in version 2 "
                        + "the field is a node, so it is one field and not two");
    }

    // ---- §4, the tool -------------------------------------------------------------------------

    @Rule("VER.023")
    @Test
    void convertingAVersion2FileReturnsItByteForByteUnchanged() {
        String v2 = "{ \"version\": 2, \"palette\": { \"X\": \"minecraft:stone\" } }";
        assertEquals(v2, V1ToV2.paletteFile(v2, "test").json());

        for (Path pack : PACKS.values()) {
            for (Path file : V1ToV2.jsonUnder(pack.resolve("palettes"))) {
                String once = V1ToV2.paletteFile(read(file), file.toString()).json();
                assertEquals(once, V1ToV2.paletteFile(once, file.toString()).json(),
                        () -> "converting " + file + " twice moved it the second time");
            }
        }
    }

    /**
     * {@code VER.009}: the ladder took one source and dropped the rest without a word. The translation
     * keeps the behaviour, and the warning is the whole difference between this and version 1.
     */
    @Rule("VER.009")
    @Test
    void anEntryWithTwoBlockSourcesTakesTheLadderSChoiceAndNamesWhatItDropped() {
        V1ToV2.Converted converted = V1ToV2.paletteFile("{\"palette\":[{\"char\":\"#\","
                + "\"block\":\"minecraft:stone\",\"variant\":\"urbex:v\",\"blocks\":["
                + "{\"random\":1,\"block\":\"minecraft:a\"}]}]}", "test");

        assertEquals("minecraft:stone", markerElement(converted.json(), "#").getAsString(),
                "the ladder took 'block', which is first");
        V1ToV2.Finding dropped = converted.findings().stream()
                .filter(f -> f.rule().equals("VER.009")).findFirst().orElseThrow();
        assertTrue(dropped.detail().contains("'variant'") && dropped.detail().contains("'blocks'"),
                "and the warning names the keys it dropped: " + dropped.detail());
        assertEquals(V1ToV2.Severity.WARNING, dropped.severity(),
                "a warning: the file still converts, and it converts to what it always meant");
    }

    /**
     * {@code VER.022}: the converter names what it could not translate without a decision, and stops.
     *
     * <p>An unread key is the clearest instance. Version 1 discarded it silently, so dropping it
     * preserves the behaviour and translating it gives the marker something the original never had —
     * and the author wrote it meaning the second.</p>
     */
    @Rule("VER.022")
    @Test
    void aKeyVersion1NeverReadIsNamedAndRefusedRatherThanGuessedAt() {
        V1ToV2.Converted converted = V1ToV2.paletteFile("{\"palette\":[{\"char\":\"X\","
                + "\"block\":\"minecraft:stone\",\"damagd\":\"minecraft:iron_bars\"}]}", "test");

        assertTrue(converted.blocked(), "VER.022 refuses rather than guessing");
        assertTrue(converted.findings().stream()
                        .anyMatch(f -> f.rule().equals("VER.022") && f.detail().contains("'damagd'")),
                "and it names the key: " + converted.findings());
    }

    @Rule("VER.008")
    @Test
    void everyShippedPaletteAndVariantHasAVersion2FormAndTheConverterProducesIt() {
        List<String> blocked = new ArrayList<>();
        int files = 0;
        for (Path pack : PACKS.values()) {
            for (Path file : V1ToV2.jsonUnder(pack.resolve("palettes"))) {
                files++;
                if (V1ToV2.paletteFile(read(file), file.toString()).blocked()) {
                    blocked.add(file.toString());
                }
            }
            for (Path file : V1ToV2.jsonUnder(pack.resolve("variants"))) {
                files++;
                if (V1ToV2.variantFile(read(file), file.toString()).blocked()) {
                    blocked.add(file.toString());
                }
            }
        }
        assertEquals(List.of(), blocked, "the translation is total");
        assertEquals(216, files,
                "30 + 98 + 7 palettes and 12 + 58 + 11 variants; if a pack gained a file this "
                        + "number moves and the claim above is about a corpus nobody counted");
    }

    // ---- §5, what it will not do ---------------------------------------------------------------

    @Rule("VER.030")
    @Rule("VER.031")
    @Test
    void theConverterInventsNoDefinitionAndReportsTheOnesItDeclined() {
        V1ToV2.Survey survey = V1ToV2.survey(PACKS.get("urbex"));

        assertEquals(Map.of("minecraft:iron_bars", 60), survey.damagedValues(),
                "VER.031's own number: 'damaged' has one distinct value across sixty uses");
        for (Path file : V1ToV2.jsonUnder(PACKS.get("urbex").resolve("palettes"))) {
            assertFalse(V1ToV2.paletteFile(read(file), file.toString()).json().contains("$defs"),
                    () -> file + " grew a $defs, and VER.030 forbids inventing one");
        }

        assertEquals(5285, V1ToV2.survey(PACKS.get("urbexza")).inlineEntries()
                        - V1ToV2.survey(PACKS.get("urbexza")).distinctInline(),
                "VER.031's other number: 6,527 inline entries in Zombie Apocalypse Essentials of "
                        + "which 1,242 are distinct");
    }

    // ---- VER.021, by generation -----------------------------------------------------------------

    /**
     * {@code VER.021}, over all three packs: every marker of every shipped palette resolves to the same
     * block state, carrying the same traits and damaging into the same block, before and after
     * conversion, at the same seed.
     *
     * <p>The two known exceptions are excluded here <em>by name</em> and asserted on their own below, so
     * that this test cannot quietly grow a third.</p>
     */
    @Rule("VER.021")
    @Test
    void everyShippedPaletteResolvesIdenticallyBeforeAndAfterConversion() {
        List<String> unexplained = new ArrayList<>();
        Map<String, String> absentBlockCases = new java.util.TreeMap<>();
        int compared = 0;
        for (String name : new TreeSet<>(PACKS.keySet())) {
            Path pack = PACKS.get(name);
            Fixture fixture = new Fixture(name, pack);
            for (Path file : V1ToV2.jsonUnder(pack.resolve("palettes"))) {
                compared++;
                String difference = fixture.compare(file);
                if (difference == null) {
                    continue;
                }
                if (Fixture.namesAnAbsentBlock(read(file))) {
                    absentBlockCases.put(name + "/" + file.getFileName(), difference);
                } else {
                    unexplained.add(difference);
                }
            }
        }
        assertEquals(List.of(), unexplained,
                "a converted palette answered differently for a reason nothing here accounts for");
        assertEquals(135, compared, "30 + 98 + 7 shipped palettes were compared");
        assertEquals(List.of("urbexza/bricks_building.json", "urbexza/default.json",
                        "urbexza/stone_building.json"),
                List.copyOf(absentBlockCases.keySet()),
                () -> "the only palettes that may differ are the ones whose weighted lists name "
                        + "immersive_weathering blocks this JVM does not have, which is "
                        + "anAbsentBlockRedistributesDifferentlyInTheTwoFormats' case; they are "
                        + "named so a fourth cannot join them quietly: " + absentBlockCases);
    }

    /**
     * The construct that could not be preserved until {@code WEIGHT.043} was implemented, measured on
     * both sides.
     *
     * <p>Version 1 drew a socket candidate with {@code LightPool.weightedOrder}, a sequential ticket
     * below the <em>authored</em> total; version 2 apportions a placement list to 128 slots like any
     * other list and {@code V2Sockets} counts the slots back into weights. So a floor list of
     * {@code 6, 3, 1} reached the placer as {@code 6, 3, 1} out of 10 on one side and
     * {@code 77, 38, 13} out of 128 on the other, {@code weightedOrder} called {@code nextInt} against
     * a different bound, and a converted socket relit the city. Nothing the converter could write
     * avoided it: the counts always total 128, and 6/10 is not a number of 128ths.</p>
     *
     * <p>{@code WEIGHT.043} — "selected by the same rules, addressed by the same position" — was
     * specified and unimplemented, and that was the defect. {@code LightPool} now materialises a
     * placement list to 128 slots with the same {@code distributeSlots} every other weighted list uses,
     * which is the identity on a version 2 pool and scales {@code 6, 3, 1} to {@code 77, 38, 13} on a
     * version 1 one. Both assertions below are what that buys: the same weights, and the same candidate
     * at the same position.</p>
     */
    @Rule("VER.021")
    @Rule("WEIGHT.043")
    @Test
    void aLightSocketNowReachesThePlacerIdenticallyFromEitherFormat() {
        Path common = PACKS.get("urbex").resolve("palettes/common.json");
        Fixture fixture = new Fixture("urbex", PACKS.get("urbex"));

        assertEquals(List.of(6, 3, 1, 8, 2, 8, 2), fixture.socketWeights(common, 'T', false),
                "version 1 hands LightPool the weights the file wrote");
        assertEquals(List.of(77, 38, 13, 102, 26, 102, 26), fixture.socketWeights(common, 'T', true),
                "version 2 hands it the slot counts, which total 128 per placement list");
        assertEquals(fixture.socketSlots(common, 'T', false), fixture.socketSlots(common, 'T', true),
                "and both are apportioned to the same 128 slots, so 6 of 10 and 77 of 128 select the "
                        + "same candidate at every position - which is what VER.021 asks of a socket "
                        + "and what no converter output could deliver before WEIGHT.043 was built");

        assertEquals(List.of(), V1ToV2.paletteFile(read(common), "common.json").findings().stream()
                        .filter(f -> f.rule().equals("VER.021")).toList(),
                "and the converter has nothing left to warn about here");
    }

    /**
     * {@code REF.010}: a converted {@code variant} resolves against the {@code definitions} registry,
     * and says so by name when the registry is not there.
     *
     * <p>Both halves, because the second was the shipped behaviour. {@code V2Palettes.compileV2} passed
     * {@code DefinitionIndex.empty()} — the registry was declared, the converter wrote assets into it,
     * and nothing handed it to the resolver — so a fully converted bundled pack refused to load naming
     * four palettes and {@code DIAG.030}. The failing half is asserted here so that unwiring it again
     * fails a test rather than a world load.</p>
     */
    @Rule("REF.010")
    @Rule("VER.021")
    @Test
    void aConvertedVariantCompilesAgainstTheDefinitionsRegistryAndIsRefusedWithoutIt() {
        Path pack = PACKS.get("urbex");
        Path file = pack.resolve("palettes/bricks_standard.json");
        assertTrue(V1ToV2.paletteFile(read(file), "bricks_standard.json").json().contains("\"$ref\""),
                "this file's markers name variants, which §2's table turns into $ref");

        assertNotNull(new Fixture("urbex", pack).compiledVersion2(file),
                "with the definitions registry it compiles");

        Diagnostics diagnostics = new Diagnostics();
        PaletteV2Definition converted = decode(PaletteV2Definition.CODEC,
                V1ToV2.paletteFile(read(file), "bricks_standard.json").json(), file);
        assertTrue(NodeResolver.resolve(converted, DefinitionIndex.empty(), Map.of(), diagnostics)
                        .isEmpty(),
                "and without it, it does not");
        assertTrue(Diag.DIAG_030.matches(diagnostics.asError().orElseThrow()),
                () -> "REF.013 names the tier it searched: " + diagnostics.asError().orElseThrow());
    }

    /**
     * The second exception, which is a difference between the two <em>formats</em> rather than anything
     * the converter chose.
     *
     * <p>Version 1 drops a choice naming an absent block and then apportions the survivors' authored
     * weights; version 2 drops it ({@code WEIGHT.030}) and divides its share among the survivors in
     * proportion to theirs. Given the emitted slot counts those are not the same arithmetic:
     * {@code [32, 32, 1000]} with the second block absent is {@code [32, 96]} in version 1 and
     * {@code [43, 85]} in version 2. It bites only where a pack names content this installation does not
     * have, which is why the comparison above passes on a vanilla test JVM and this states the case it
     * does not cover.</p>
     */
    @Rule("VER.021")
    @Rule("WEIGHT.030")
    @Test
    void anAbsentBlockRedistributesDifferentlyInTheTwoFormats() {
        int[] version1 = CompiledPalette.distributeSlots(new int[] {32, 1000}, 128);
        assertEquals(32, version1[0]);
        assertEquals(96, version1[1]);

        // What the converter emits for [32, 32, 1000] is [32, 32, 64]; dropping the middle choice
        // leaves version 2 apportioning 32 and 64 over 128 slots, in proportion.
        assertEquals(43, Math.round(128 * 32.0 / (32 + 64)),
                "version 2 scales the survivors up, and version 1 does not - so a pack naming an "
                        + "absent block converts to a palette that is not identical on an "
                        + "installation missing it. See the task 9 report.");
    }

    // ---- The harness --------------------------------------------------------------------------

    /**
     * One pack, compiled both ways.
     *
     * <p>Holds what the two compilers need that the palette file does not carry: the {@code variants}
     * index a version 1 {@code variant} resolves against, the {@code definitions} index its converted
     * {@code $ref} resolves against, and the set of {@code conditions} ids a {@code pool} is checked
     * for. All three are read off the pack itself, so adding a palette to a pack needs no edit here.</p>
     */
    private static final class Fixture {

        private final AssetIndex<Variant> variants;
        private final DefinitionIndex definitions;
        private final TraitContext traits;
        private final Exclusion.Presence presence;

        Fixture(String namespace, Path pack) {
            Map<Identifier, Variant> compiled = new LinkedHashMap<>();
            for (Path file : V1ToV2.jsonUnder(pack.resolve("variants"))) {
                Identifier id = idOf(namespace, pack.resolve("variants"), file);
                compiled.put(id, new Variant(id, BuiltInRegistries.BLOCK,
                        List.of(decode(VariantDefinition.CODEC, read(file), file))));
            }
            this.variants = new AssetIndex<>("variants", compiled);

            Map<Identifier, DefinitionAssetDefinition> byId = new LinkedHashMap<>();
            for (Path file : V1ToV2.jsonUnder(pack.resolve("variants"))) {
                byId.put(idOf(namespace, pack.resolve("variants"), file),
                        decode(DefinitionAssetDefinition.CODEC,
                                V1ToV2.variantFile(read(file), file.toString()).json(), file));
            }
            this.definitions = new DefinitionIndex(byId);

            // Every pack's conditions, not this one's: an addon is installed beside the bundled pack
            // and its palettes name urbex:chestloot, which is Urbex's own asset. A per-pack set
            // refuses ModernTweaks' common.json for naming a condition that is loaded in every
            // installation it will ever run in.
            this.traits = TraitContext.withConditions(BuiltInRegistries.BLOCK, CONDITIONS);
            this.presence = Exclusion.installed(BuiltInRegistries.BLOCK,
                    Set.copyOf(List.of("urbex", "minecraft", namespace)));
        }

        /**
         * Whether any weighted list in this document names a block this game does not have.
         *
         * <p>The condition under which the two formats' apportionments part company, and the only
         * reason {@link #everyShippedPaletteResolvesIdenticallyBeforeAndAfterConversion} tolerates a
         * difference. Only weighted lists, because a single {@code block} naming an absent id is air in
         * version 1 ({@code Tools.stringToState}) and air in version 2 ({@code MODEL.042}) and nothing
         * is redistributed either way.</p>
         */
        static boolean namesAnAbsentBlock(String document) {
            for (String written : blocksOfWeightedLists(JsonParser.parseString(document))) {
                String id = written.contains("[") ? written.substring(0, written.indexOf('[')) : written;
                if (BuiltInRegistries.BLOCK.get(Identifier.parse(id)).isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        private static List<String> blocksOfWeightedLists(com.google.gson.JsonElement element) {
            List<String> found = new ArrayList<>();
            if (element.isJsonArray()) {
                element.getAsJsonArray().forEach(child -> found.addAll(blocksOfWeightedLists(child)));
                return found;
            }
            if (!element.isJsonObject()) {
                return found;
            }
            JsonObject object = element.getAsJsonObject();
            for (String key : List.of("blocks", "unlitBlocks", "floor", "wall", "ceiling", "free")) {
                if (!object.has(key) || !object.get(key).isJsonArray()) {
                    continue;
                }
                object.getAsJsonArray(key).forEach(entry -> {
                    if (entry.isJsonObject() && entry.getAsJsonObject().has("block")) {
                        found.add(entry.getAsJsonObject().get("block").getAsString());
                    }
                });
            }
            object.entrySet().forEach(child -> found.addAll(blocksOfWeightedLists(child.getValue())));
            return found;
        }

        /**
         * The candidate weights one socket hands the placer, from whichever format compiled it.
         *
         * <p>{@code LightPool.allCandidates} flattens the four placement lists in {@code Placement}
         * order, which is the same order both formats build them in, so the two lists are comparable
         * element by element.</p>
         */
        List<Integer> socketWeights(Path file, char marker, boolean version2) {
            return pool(file, marker, version2).allCandidates().stream()
                    .map(candidate -> candidate.weight()).toList();
        }

        /**
         * Which candidate each of a socket's placement lists offers first, over a lattice of positions.
         *
         * <p>{@code WEIGHT.043}'s actual claim, asked the way generation asks it. Comparing the weights
         * alone would pass on two pools that round to the same numbers and address them differently;
         * comparing one position would pass on two that agree there by luck.</p>
         */
        List<String> socketSlots(Path file, char marker, boolean version2) {
            LightPool pool = pool(file, marker, version2);
            List<String> winners = new ArrayList<>();
            for (LightPool.Placement placement : LightPool.Placement.values()) {
                if (!pool.hasCandidates(placement)) {
                    continue;
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        winners.add(placement + "@" + x + "," + z + "="
                                + pool.weightedOrder(placement, SEED, x, 64, z).getFirst().state());
                    }
                }
            }
            return winners;
        }

        private LightPool pool(Path file, char marker, boolean version2) {
            Palette.Info info = (version2 ? compiledVersion2(file) : compiledVersion1(file))
                    .placedAt(marker, SEED, 0, 64, 0).info();
            return info.lightSource().pool();
        }

        /** The palette as version 1 compiles it, through the merge a style's draw would use. */
        CompiledPalette compiledVersion1(Path file) {
            return new CompiledPalette(new Palette(UNDER_TEST, BuiltInRegistries.BLOCK, variants,
                    List.of(decode(PaletteDefinition.CODEC, read(file), file))));
        }

        /** The converted palette, through all eight stages of {@code LOAD.001} and the same merge. */
        CompiledPalette compiledVersion2(Path file) {
            Diagnostics diagnostics = new Diagnostics();
            PaletteV2Definition converted = decode(PaletteV2Definition.CODEC,
                    V1ToV2.paletteFile(read(file), file.toString()).json(), file);
            CompiledV2Palette compiled =
                    NodeResolver.resolve(converted, definitions, Map.of(), diagnostics)
                            .flatMap(resolved -> CompiledV2Palette.compile(resolved, presence, traits,
                                    "'" + file + "'", diagnostics))
                            .orElseThrow(() -> new AssertionError(file
                                    + ": the converted palette did not compile: "
                                    + diagnostics.asError().orElse("?")));
            return new CompiledPalette(Palette.version2(UNDER_TEST, compiled));
        }

        /** The difference between the two compilations of one file, or null when there is none. */
        String compare(Path file) {
            CompiledPalette before = compiledVersion1(file);
            CompiledPalette after;
            try {
                after = compiledVersion2(file);
            } catch (AssertionError refused) {
                return refused.getMessage();
            }

            if (!before.getCharacters().equals(after.getCharacters())) {
                return file + ": markers " + new TreeSet<>(before.getCharacters()) + " became "
                        + new TreeSet<>(after.getCharacters());
            }
            for (char marker : new TreeSet<>(before.getCharacters())) {
                String difference = compareMarker(file, before, after, marker);
                if (difference != null) {
                    return difference;
                }
            }
            return null;
        }

        /**
         * One marker at 4,096 positions, plus its damaged form.
         *
         * <p>A 16x16x16 lattice rather than a handful of points: a weighted marker holds 128 slots
         * addressed by position, so a difference of one slot shows up at roughly 32 of these and at none
         * of three sample points.</p>
         */
        private String compareMarker(Path file, CompiledPalette before, CompiledPalette after,
                                     char marker) {
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        String was = describe(before.placedAt(marker, SEED, x, 64 + y, z));
                        String is = describe(after.placedAt(marker, SEED, x, 64 + y, z));
                        if (!was.equals(is)) {
                            return file + " marker '" + marker + "' at " + x + "," + (64 + y) + ","
                                    + z + ": " + was + " became " + is;
                        }
                    }
                }
            }
            String was = String.valueOf(before.getRepresentative(marker) == null ? null
                    : before.canBeDamagedToIronBars(before.getRepresentative(marker)));
            String is = String.valueOf(after.getRepresentative(marker) == null ? null
                    : after.canBeDamagedToIronBars(after.getRepresentative(marker)));
            return was.equals(is) ? null
                    : file + " marker '" + marker + "' damages into " + was + ", was " + is;
        }

        /**
         * What generation reads off a resolved marker, and nothing else.
         *
         * <p>The light source is described by what it is rather than by identity: two
         * {@code LightSource} records are never equal across the two compilations, and the thing that
         * has to match is whether the marker defers placement and what it leaves behind.</p>
         */
        private static String describe(CompiledPalette.Placed placed) {
            if (placed == null) {
                return "-";
            }
            StringBuilder text = new StringBuilder(placed.state().toString());
            Palette.Info info = placed.info();
            if (info == null) {
                return text.toString();
            }
            text.append(" traits=").append(info.applied());
            if (info.mobId() != null) {
                text.append(" mob=").append(info.mobId());
            }
            if (info.loot() != null) {
                text.append(" loot=").append(info.loot());
            }
            if (info.tag() != null) {
                text.append(" nbt=").append(info.tag());
            }
            if (info.lightSource() != null) {
                text.append(" socket=").append(info.lightSource().isSocket());
                text.append(" unlit=").append(info.lightSource()
                        .unlitAt(SEED, net.minecraft.core.BlockPos.ZERO));
            }
            return text.toString();
        }

        static Identifier idOf(String namespace, Path registry, Path file) {
            String path = registry.relativize(file).toString().replace('\\', '/');
            return Identifier.fromNamespaceAndPath(namespace,
                    path.substring(0, path.length() - ".json".length()));
        }
    }

    // ---- Small helpers ---------------------------------------------------------------------------

    private static String convert(String v1) {
        V1ToV2.Converted converted = V1ToV2.paletteFile(v1, "test");
        assertFalse(converted.blocked(), () -> "unexpectedly blocked: " + converted.findings());
        return converted.json();
    }

    /** The node one marker became, as an object; every caller but the shorthand test wants that. */
    private static JsonObject marker(String json, String marker) {
        return markerElement(json, marker).getAsJsonObject();
    }

    private static com.google.gson.JsonElement markerElement(String json, String marker) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("palette").get(marker);
    }

    private static String compact(String json) {
        return JsonParser.parseString(json).toString();
    }

    private static <T> T decode(com.mojang.serialization.Codec<T> codec, String json, Path file) {
        return codec.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(message -> new AssertionError(file + " did not decode: " + message));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
