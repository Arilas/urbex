package dev.krona.urbex.format.palette;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.lost.cityassets.AssetIndex;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.LightPool;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import dev.krona.urbex.worldgen.lost.cityassets.Variant;
import dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.VariantDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * <p>What it cannot see is asserted beside it rather than left implicit. A socket resolves to its
 * representative and defers the rest to {@code OptionalLightPlacer}, so
 * {@link #aLightSocketNowReachesThePlacerIdenticallyFromEitherFormat} asks the pool directly; and
 * {@link #anAbsentBlockRedistributesDifferentlyInTheTwoFormats} states the one case
 * {@code VER.021}'s quantification puts outside the rule, because a difference this test cannot
 * express is a difference the next person rediscovers.</p>
 */
class V1ToV2Test {

    /** The three packs {@code VER.021} names, as pack roots: the directory holding {@code palettes/}. */
    private static final Map<String, Path> PACKS = Map.of(
            "urbex", Path.of("src/main/resources/data/urbex/urbex"),
            "urbexmt", Path.of("../Urbex-ModernTweaks/pack/data/urbexmt/urbex"),
            "urbexza", Path.of("../Urbex-Zombie-Apocalypse-Essentials/pack/data/urbexza/urbex"));

    /**
     * The two of them still written in version 1, which is what a before-and-after can be run over.
     *
     * <p>The bundled pack left this set in Task 10 and its evidence moved with it: a version 2 file has
     * no version 1 form to compile beside, and the property {@code VER.021} actually asks for — "a world
     * generated from the converted pack is identical to one generated from the original" — is now
     * measured the way the rule words it, by {@code runDigestCheck} and its five sibling windows over
     * the shipped pack itself. That is a stronger measurement than this one and not a weaker: it drives
     * chunks rather than asking a palette what it would have answered.</p>
     *
     * <p>Named as its own constant rather than by deleting the bundled entry from {@link #PACKS},
     * because the converter is still run over all three — {@code VER.023} makes converting a version 2
     * file return it unchanged, and the survey in §5 reads whatever the pack is.</p>
     */
    private static final Map<String, Path> VERSION_1_PACKS = Map.of(
            "urbexmt", PACKS.get("urbexmt"),
            "urbexza", PACKS.get("urbexza"));

    private static final long SEED = 20260817L;

    /**
     * The two sockets the bundled pack shipped in version 1, as it wrote them at {@code 2ada507f}.
     *
     * <p>Written here rather than read from {@code palettes/common.json} because that file is version 2
     * now, and a version 2 file has no version 1 side for a before-and-after to have. Neither reference
     * pack has a {@code free} list, so without this the four-candidate case would go uncompared —
     * which is the coverage this document exists to keep, not a fixture invented to make a point.</p>
     */
    private static final String BUNDLED_SOCKETS_IN_VERSION_1 = """
            {
              "palette": [
                {
                  "char": "T",
                  "lightSource": {
                    "floor": [
                      { "weight": 6, "block": "minecraft:lantern[hanging=false]" },
                      { "weight": 3, "block": "minecraft:torch",
                        "unlit": "minecraft:candle[candles=1,lit=false]" },
                      { "weight": 1, "block": "minecraft:end_rod[facing=up]" }
                    ],
                    "wall": [
                      { "weight": 8, "block": "minecraft:wall_torch[facing=north]" },
                      { "weight": 2, "block": "minecraft:end_rod[facing=north]" }
                    ],
                    "ceiling": [
                      { "weight": 8, "block": "minecraft:lantern[hanging=true]",
                        "unlit": "minecraft:iron_chain[axis=y]" },
                      { "weight": 2, "block": "minecraft:end_rod[facing=down]" }
                    ]
                  }
                },
                {
                  "char": "h",
                  "lightSource": {
                    "free": [
                      { "weight": 6, "block": "minecraft:glowstone" },
                      { "weight": 2, "block": "minecraft:sea_lantern" },
                      { "weight": 1, "block": "minecraft:shroomlight" },
                      { "weight": 1, "block": "minecraft:ochre_froglight" }
                    ]
                  }
                }
              ]
            }
            """;

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

        // VER.031's first number was the bundled pack's sixty uses of one value, and it is the one
        // target of the four that has since been taken: Task 10 hoisted 45 of them into the shared
        // 'urbex:damageable' definition and left 9 written inline, because those markers already carry
        // a $ref and a node has one. What the tool still counts here is version 1's spelling, which
        // survives only in the inline palettes of six parts and buildings.
        assertEquals(Map.of("minecraft:iron_bars", 6), survey.damagedValues(),
                "the bundled pack after the hoist; VER.031's > Why records both numbers");

        // Still measured where it has not been acted on, so the rule keeps a live instance rather than
        // only a historical one.
        Map<String, Integer> modernTweaks = V1ToV2.survey(PACKS.get("urbexmt")).damagedValues();
        assertEquals(7, modernTweaks.size(), () -> "seven distinct 'damaged' values: " + modernTweaks);
        assertEquals(257, modernTweaks.values().stream().mapToInt(Integer::intValue).sum(),
                "across 257 uses");

        for (Path file : V1ToV2.jsonUnder(PACKS.get("urbex").resolve("palettes"))) {
            assertFalse(V1ToV2.paletteFile(read(file), file.toString()).json().contains("$defs"),
                    () -> file + " grew a $defs, and VER.030 forbids inventing one");
        }

        assertEquals(5285, V1ToV2.survey(PACKS.get("urbexza")).inlineEntries()
                        - V1ToV2.survey(PACKS.get("urbexza")).distinctInline(),
                "VER.031's other number: 6,527 inline entries in Zombie Apocalypse Essentials of "
                        + "which 1,242 are distinct");
    }

    /**
     * {@code VER.032}: a count nobody can reproduce is a claim rather than a measurement, so every
     * printed opportunity says how it was counted.
     *
     * <p>Asserted on the printed line and not on the javadoc, because the line is what a pack author
     * reads. The phrase this checks for is the one {@code VER.032}'s own {@code > Why} was written
     * about: "differing only by a directional property" is what the unreproducible figure was reported
     * under, and a report repeating it without saying what a family is would reproduce the defect.</p>
     */
    @Rule("VER.032")
    @Test
    void everyDeclinedOpportunityStatesTheRuleItWasCountedBy() {
        List<String> lines = V1ToV2.survey(PACKS.get("urbex")).describe();

        assertEquals(3, lines.size());
        for (String line : lines) {
            assertTrue(line.contains("Counted as:"),
                    () -> "VER.032: this line states no counting rule: " + line);
        }
        assertTrue(lines.get(2).contains("minus the 'char' key")
                        && lines.get(2).contains("facing")
                        && lines.get(2).contains("more than one marker"),
                () -> "and the family rule is stated in full, since that is the count the "
                        + "specification could not reproduce: " + lines.get(2));
    }

    /**
     * {@code VER.022} at the severity a review corrected: a socket's weighted replacement is a guess,
     * and a guess is a blocker.
     *
     * <p>Version 1 draws a socket's own replacement per position, over a {@code BlockChoice.Weighted};
     * version 2 gives a socket a single replacement state, its first alternative, because
     * {@code LightPool.Candidate} holds one and the placer writes it at a position the palette never
     * addressed. Emitting the weighted list anyway exits zero on a pack whose generation the tool knows
     * it changed.</p>
     */
    @Rule("VER.022")
    @Test
    void aWeightedReplacementOnASocketIsRefusedRatherThanQuietlyCollapsed() {
        V1ToV2.Converted converted = V1ToV2.paletteFile("{\"palette\":[{\"char\":\"T\","
                + "\"lightSource\":{\"floor\":[{\"weight\":1,\"block\":\"minecraft:lantern\"}],"
                + "\"unlitBlocks\":[{\"random\":10,\"block\":\"minecraft:a\"},"
                + "{\"random\":2000,\"block\":\"minecraft:b\"}]}}]}", "test");

        assertTrue(converted.blocked(), "VER.022 exits non-zero rather than guessing");
        assertTrue(converted.findings().stream().anyMatch(f -> f.rule().equals("VER.022")
                        && f.detail().contains("unlitBlocks")),
                () -> "and names the construct: " + converted.findings());

        // The in-place spelling of the same field is not a guess: an in-place light keeps its
        // BlockChoice on both sides, so the weighted list translates and the file converts.
        assertFalse(V1ToV2.paletteFile("{\"palette\":[{\"char\":\"h\","
                + "\"block\":\"minecraft:glowstone\",\"lightSource\":{\"unlitBlocks\":["
                + "{\"random\":10,\"block\":\"minecraft:a\"},"
                + "{\"random\":2000,\"block\":\"minecraft:b\"}]}}]}", "test").blocked(),
                "and the in-place form, which ModernTweaks ships, still converts");
    }

    /**
     * The {@code Mergeable} object form of a version 1 list, which is legal and which this tool used to
     * crash on.
     *
     * <p>A variant's {@code blocks} is a {@code Mergeable}, so {@code {"replace": false, "values": […]}}
     * is a version 1 file a pack may ship. Reading it as an array threw a {@code ClassCastException}
     * naming no file. The replacing form is a list and converts; the appending form is
     * {@code $spread} of {@code $super} over a chain this tool cannot see, and its weights are not the
     * weights of this list, so {@code VER.022} names it and stops.</p>
     */
    @Rule("VER.022")
    @Test
    void aMergeableBlocksObjectConvertsWhenItReplacesAndIsNamedWhenItAppends() {
        V1ToV2.Converted replacing = V1ToV2.variantFile(
                "{\"blocks\":{\"values\":[{\"random\":9,\"block\":\"minecraft:a\"},"
                        + "{\"random\":1000,\"block\":\"minecraft:b\"}]}}", "variants/x.json");
        assertFalse(replacing.blocked(), () -> "" + replacing.findings());
        assertEquals("{\"version\":2,\"kind\":\"weighted\",\"choices\":["
                        + "{\"weight\":9,\"block\":\"minecraft:a\"},"
                        + "{\"weight\":119,\"block\":\"minecraft:b\"}]}",
                compact(replacing.json()),
                "an absent 'replace' defaults to true, so this is the bare array by another spelling");

        V1ToV2.Converted appending = V1ToV2.variantFile(
                "{\"blocks\":{\"replace\":false,\"values\":["
                        + "{\"random\":9,\"block\":\"minecraft:a\"}]}}", "variants/x.json");
        assertTrue(appending.blocked(), "an appending list is not this list");
        assertTrue(appending.findings().stream().anyMatch(f -> f.rule().equals("VER.022")
                        && f.detail().contains("$spread")),
                () -> "and the remedy names what version 2 spells it: " + appending.findings());
    }

    /**
     * {@code append} was in the variant key set and is a key of nothing.
     *
     * <p>Version 1 opts into appending with {@code "replace": false} <em>inside</em> the list, so a
     * top-level {@code append} is an unread key like any other — and accepting it meant the one class of
     * mistake {@code VER.022} exists to catch was being waved through by the check that catches it.</p>
     */
    @Rule("VER.022")
    @Test
    void aKeyNoVersion1VariantHasIsRefusedRatherThanAccepted() {
        V1ToV2.Converted converted = V1ToV2.variantFile(
                "{\"append\":true,\"blocks\":[{\"random\":1,\"block\":\"minecraft:a\"}]}",
                "variants/x.json");

        assertTrue(converted.blocked());
        assertTrue(converted.findings().stream()
                        .anyMatch(f -> f.rule().equals("VER.022") && f.detail().contains("'append'")),
                () -> "" + converted.findings());
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
        int markers = 0;
        for (String name : new TreeSet<>(VERSION_1_PACKS.keySet())) {
            Path pack = VERSION_1_PACKS.get(name);
            Fixture fixture = new Fixture(name, pack);
            for (Path file : V1ToV2.jsonUnder(pack.resolve("palettes"))) {
                compared++;
                Map<Character, String> differences = fixture.compare(file);
                markers += fixture.compiledVersion1(file).getCharacters().size();
                if (differences.isEmpty()) {
                    continue;
                }
                Set<Character> excused = fixture.markersNamingAnAbsentBlock(pack, file);
                differences.forEach((marker, difference) -> {
                    if (excused.contains(marker)) {
                        absentBlockCases.put(name + "/" + file.getFileName() + " '" + marker + "'",
                                difference);
                    } else {
                        unexplained.add(difference);
                    }
                });
            }
        }
        assertEquals(List.of(), unexplained,
                "a converted palette answered differently for a reason nothing here accounts for");
        assertEquals(105, compared, "98 + 7 shipped version 1 palettes were compared; the bundled "
                + "pack's thirty are version 2 since Task 10 and are measured by the digest windows");
        assertEquals(474, markers,
                "and every marker of each of them, so the twelve exceptions below are twelve out of "
                        + "this and not twelve out of however many the comparison happened to reach. "
                        + "It used to stop at the first differing marker of a file, which hid four "
                        + "more; it counted 663 over three packs until the bundled pack's 189 became "
                        + "version 2");
        assertEquals(List.of(
                        "urbexza/bricks_building.json '#'",
                        "urbexza/default.json '/'", "urbexza/default.json ':'",
                        "urbexza/default.json '='", "urbexza/default.json 'B'",
                        "urbexza/default.json '_'", "urbexza/default.json 'u'",
                        "urbexza/default.json 'v'", "urbexza/default.json 'w'",
                        "urbexza/default.json 'x'", "urbexza/default.json 'y'",
                        "urbexza/stone_building.json '#'"),
                List.copyOf(absentBlockCases.keySet()),
                () -> "the only markers that may differ are the ones whose own weighted list names an "
                        + "immersive_weathering block this JVM does not have, which is "
                        + "anAbsentBlockRedistributesDifferentlyInTheTwoFormats' case; they are named "
                        + "marker by marker so a fourth cannot join them quietly: " + absentBlockCases);
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
        Fixture fixture = new Fixture("urbex", PACKS.get("urbex"));
        CompiledPalette version1 = fixture.compiledVersion1(BUNDLED_SOCKETS_IN_VERSION_1, "common.json");
        CompiledPalette version2 = fixture.compiledVersion2(BUNDLED_SOCKETS_IN_VERSION_1, "common.json");

        assertEquals(List.of(6, 3, 1, 8, 2, 8, 2), fixture.socketWeights(version1, 'T'),
                "version 1 hands LightPool the weights the file wrote");
        assertEquals(List.of(77, 38, 13, 102, 26, 102, 26), fixture.socketWeights(version2, 'T'),
                "version 2 hands it the slot counts, which total 128 per placement list");

        // Both sockets, not one: 'T' has floor, wall and ceiling lists and 'h' has a four-candidate
        // 'free' list, which no assertion about 'T' reaches.
        assertEquals(List.of(6, 2, 1, 1), fixture.socketWeights(version1, 'h'));
        assertEquals(List.of(77, 25, 13, 13), fixture.socketWeights(version2, 'h'));
        for (char marker : new char[] {'T', 'h'}) {
            assertEquals(fixture.socketSlots(version1, marker), fixture.socketSlots(version2, marker),
                    () -> "marker '" + marker + "': both formats are apportioned to the same 128 "
                            + "slots, so 6 of 10 and 77 of 128 select the same candidate at every "
                            + "position - which is what VER.021 asks of a socket and what no converter "
                            + "output could deliver before WEIGHT.043 was built");
        }

        assertEquals(List.of(), V1ToV2.paletteFile(BUNDLED_SOCKETS_IN_VERSION_1, "common.json")
                        .findings().stream().filter(f -> f.rule().equals("VER.021")).toList(),
                "and the converter has nothing left to warn about here");

        // And the same property over a socket still shipped in version 1, so this does not become an
        // assertion about a document only this file has: Modern Tweaks' own 'T'.
        Path modernTweaks = PACKS.get("urbexmt").resolve("palettes/common.json");
        Fixture mt = new Fixture("urbexmt", PACKS.get("urbexmt"));
        assertEquals(mt.socketSlots(mt.compiledVersion1(modernTweaks), 'T'),
                mt.socketSlots(mt.compiledVersion2(modernTweaks), 'T'));
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
        // A version 1 palette naming a variant, which is what the conversion turns into a $ref. The
        // bundled pack's bricks_standard.json was this test's subject until Task 10 converted it, and
        // a converted file cannot show the conversion doing anything.
        Path pack = PACKS.get("urbexmt");
        Path file = pack.resolve("palettes/common.json");
        assertFalse(V1ToV2.paletteFile(read(file), file.toString()).json().contains("\"variant\""),
                "the retired key does not survive the conversion");
        assertTrue(V1ToV2.paletteFile(read(file), file.toString()).json().contains("\"$ref\""),
                "this file's markers name variants, which §2's table turns into $ref");

        assertNotNull(new Fixture("urbexmt", pack).compiledVersion2(file),
                "with the definitions registry it compiles");

        Diagnostics diagnostics = new Diagnostics();
        PaletteV2Definition converted = decode(PaletteV2Definition.CODEC,
                V1ToV2.paletteFile(read(file), file.toString()).json(), file);
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
        String version1 = "{\"palette\":[{\"char\":\"#\",\"blocks\":["
                + "{\"random\":32,\"block\":\"minecraft:stone\"},"
                + "{\"random\":32,\"block\":\"nosuchmod:nosuchblock\"},"
                + "{\"random\":1000,\"block\":\"minecraft:cobweb\"}]}]}";

        Fixture fixture = new Fixture("urbex", PACKS.get("urbex"));
        Map<Block, Integer> before = fixture.slotCensus(version1, '#', false);
        Map<Block, Integer> after = fixture.slotCensus(version1, '#', true);

        assertEquals(Map.of(Blocks.STONE, 32, Blocks.COBWEB, 96), before,
                "version 1 drops the absent choice and apportions 32 and 1000 as absolute counts");
        assertEquals(Map.of(Blocks.STONE, 43, Blocks.COBWEB, 85), after,
                "version 2 drops it (WEIGHT.030) and divides its share among the survivors in "
                        + "proportion to theirs - so a pack naming an absent block converts to a "
                        + "palette that is not identical on an installation missing it, which is what "
                        + "VER.021's quantification puts outside the rule");
    }

    /**
     * {@code TRAIT.012}: an {@code into} naming a block this game does not have leaves the marker
     * undamaged — not damaged into nothing.
     *
     * <p>Found by tightening {@code VER.021}'s exception from the file to the marker: the comparison
     * used to stop at a file's first differing marker, so four more differences behind the three known
     * ones had never been looked at. By {@code MODEL.042} an absent id resolves to air, and the
     * state-keyed damage map recorded <em>state → air</em>, so the damage pass deleted the block. That
     * is what version 1 refuses in so many words — {@code Palette.compile} skips an unresolvable
     * {@code damaged} because "air would say 'damaging this block deletes it', which is a claim the
     * author did not make". Seven markers in Zombie Apocalypse Essentials were doing it.</p>
     */
    @Rule("TRAIT.012")
    @Rule("VER.021")
    @Test
    void aDamagedIntoABlockThisGameLacksLeavesTheMarkerUndamagedRatherThanDeletingIt() {
        Path pack = PACKS.get("urbexza");
        Path file = pack.resolve("palettes/bricks_building.json");
        Fixture fixture = new Fixture("urbexza", pack);

        CompiledPalette version2 = fixture.compiledVersion2(file);
        CompiledPalette version1 = fixture.compiledVersion1(file);
        for (char marker : new TreeSet<>(version1.getCharacters())) {
            BlockState from = version1.getRepresentative(marker);
            if (from == null) {
                continue;
            }
            BlockState damaged = version2.canBeDamagedToIronBars(from);
            assertTrue(damaged == null || !damaged.isAir(),
                    () -> "marker '" + marker + "' damages into air, which deletes the block");
        }
        assertNull(version2.canBeDamagedToIronBars(
                        version2.getRepresentative('X')),
                "'X' names immersive_weathering:exposed_iron_bars, which this game does not have, so "
                        + "it has no damaged form at all - exactly as version 1 records none");
        assertNull(version1.canBeDamagedToIronBars(version1.getRepresentative('X')),
                "and version 1 agrees, which is the point");
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
         * The markers of this document whose weighted lists name a block this game does not have.
         *
         * <p>Per marker rather than per file, and the difference is the whole value of it: a file-level
         * answer gives every one of a palette's forty markers a blanket pass because one of them names
         * an {@code immersive_weathering} block, so a real difference somewhere else in the same file
         * would be filed under {@code VER.021}'s stated exception and never looked at. The three ZA
         * palettes that differ hold 40, 25 and 25 markers between them; only three of those markers
         * have any business differing.</p>
         *
         * <p>Only weighted lists, because a single {@code block} naming an absent id is air in version 1
         * ({@code Tools.stringToState}) and air in version 2 ({@code MODEL.042}) and nothing is
         * redistributed either way. A {@code variant} is followed into the pack's own variants
         * directory, since a marker naming one inherits that list's absent blocks.</p>
         */
        Set<Character> markersNamingAnAbsentBlock(Path pack, Path file) {
            Set<Character> markers = new java.util.LinkedHashSet<>();
            JsonElement palette = JsonParser.parseString(read(file)).getAsJsonObject().get("palette");
            if (palette == null || !palette.isJsonArray()) {
                return markers;
            }
            for (JsonElement element : palette.getAsJsonArray()) {
                JsonObject entry = element.getAsJsonObject();
                boolean absent = namesAnAbsentBlock(entry);
                if (!absent && entry.has("variant")) {
                    Path variant = pack.resolve("variants").resolve(
                            entry.get("variant").getAsString().split(":", 2)[1] + ".json");
                    absent = Files.exists(variant)
                            && namesAnAbsentBlock(JsonParser.parseString(read(variant))
                                    .getAsJsonObject());
                }
                if (absent) {
                    markers.add(entry.get("char").getAsString().charAt(0));
                }
            }
            return markers;
        }

        private static boolean namesAnAbsentBlock(JsonElement document) {
            for (String written : blocksOfWeightedLists(document)) {
                String id = written.contains("[") ? written.substring(0, written.indexOf('[')) : written;
                if (BuiltInRegistries.BLOCK.get(Identifier.parse(id)).isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        private static List<String> blocksOfWeightedLists(JsonElement element) {
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
        List<Integer> socketWeights(CompiledPalette palette, char marker) {
            return pool(palette, marker).allCandidates().stream()
                    .map(candidate -> candidate.weight()).toList();
        }

        /**
         * Which candidate each of a socket's placement lists offers first, over a lattice of positions.
         *
         * <p>{@code WEIGHT.043}'s actual claim, asked the way generation asks it. Comparing the weights
         * alone would pass on two pools that round to the same numbers and address them differently;
         * comparing one position would pass on two that agree there by luck.</p>
         */
        List<String> socketSlots(CompiledPalette palette, char marker) {
            LightPool pool = pool(palette, marker);
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

        private LightPool pool(CompiledPalette palette, char marker) {
            Palette.Info info = palette.placedAt(marker, SEED, 0, 64, 0).info();
            return info.lightSource().pool();
        }

        /**
         * How many of a marker's 128 slots each block holds, from either format, over a document
         * written inline rather than read from a pack.
         *
         * <p>A census over the compiled palette rather than arithmetic in a test: the point of
         * {@link #anAbsentBlockRedistributesDifferentlyInTheTwoFormats} is what the two compilers do,
         * and a test that recomputes what it believes they do proves only that the belief is
         * self-consistent.</p>
         */
        Map<Block, Integer> slotCensus(String version1Document, char marker, boolean version2) {
            CompiledPalette palette = version2
                    ? compiledVersion2(version1Document, "inline")
                    : compiledVersion1(version1Document, "inline");
            // Exact, not sampled. A slot is addressed by position rather than indexed, so the slot
            // array is recovered by finding one position per index through the same Rng.paletteSlotAt
            // both formats resolve with, and asking the palette what stands there. Counting hits over a
            // lattice instead gives a multinomial spread - it returned 33/95 for a 32/96 palette, which
            // is a test that cannot tell the two formats' apportionments apart.
            BlockState[] slots = new BlockState[CompiledPalette.SLOTS];
            int found = 0;
            for (int x = 0; found < slots.length && x < 512; x++) {
                for (int z = 0; found < slots.length && z < 512; z++) {
                    int index = Rng.paletteSlotAt(SEED, marker, x, 64, z, slots.length);
                    if (slots[index] == null) {
                        slots[index] = palette.getAt(marker, SEED, x, 64, z);
                        found++;
                    }
                }
            }
            assertEquals(slots.length, found, "every slot of the marker was reached");
            Map<Block, Integer> census = new java.util.LinkedHashMap<>();
            for (BlockState state : slots) {
                census.merge(state.getBlock(), 1, Integer::sum);
            }
            return census;
        }

        /** The palette as version 1 compiles it, through the merge a style's draw would use. */
        CompiledPalette compiledVersion1(Path file) {
            return compiledVersion1(read(file), file.toString());
        }

        /** The converted palette, through all eight stages of {@code LOAD.001} and the same merge. */
        CompiledPalette compiledVersion2(Path file) {
            return compiledVersion2(read(file), file.toString());
        }

        /**
         * The same two compilations over a document written in the test rather than read from a pack.
         *
         * <p>Both forms exist because the corpus of version 1 palettes shrank: the bundled pack is
         * written in version 2 since Task 10, so a construct it was the only shipped instance of has
         * to be stated here to keep being compared. {@code where} is what a diagnostic names.</p>
         */
        CompiledPalette compiledVersion1(String document, String where) {
            return new CompiledPalette(new Palette(UNDER_TEST, BuiltInRegistries.BLOCK, variants,
                    List.of(decode(PaletteDefinition.CODEC, document, Path.of(where)))));
        }

        CompiledPalette compiledVersion2(String version1Document, String where) {
            Diagnostics diagnostics = new Diagnostics();
            PaletteV2Definition converted = decode(PaletteV2Definition.CODEC,
                    V1ToV2.paletteFile(version1Document, where).json(), Path.of(where));
            CompiledV2Palette compiled =
                    NodeResolver.resolve(converted, definitions, Map.of(), diagnostics)
                            .flatMap(resolved -> CompiledV2Palette.compile(resolved, presence, traits,
                                    "'" + where + "'", diagnostics))
                            .orElseThrow(() -> new AssertionError(where
                                    + ": the converted palette did not compile: "
                                    + diagnostics.asError().orElse("?")));
            return new CompiledPalette(Palette.version2(UNDER_TEST, compiled));
        }

        /**
         * Every marker of one file that answers differently after conversion, by marker.
         *
         * <p>Keyed rather than first-difference-wins, so the caller can decide per marker whether
         * {@code VER.021}'s absent-block exception covers it. Returning one string per file made the
         * exception file-granular, which is the granularity a review found too coarse.</p>
         */
        Map<Character, String> compare(Path file) {
            CompiledPalette before = compiledVersion1(file);
            CompiledPalette after;
            try {
                after = compiledVersion2(file);
            } catch (AssertionError refused) {
                return Map.of('?', refused.getMessage());
            }

            Map<Character, String> differences = new java.util.LinkedHashMap<>();
            if (!before.getCharacters().equals(after.getCharacters())) {
                differences.put('?', file + ": markers " + new TreeSet<>(before.getCharacters())
                        + " became " + new TreeSet<>(after.getCharacters()));
                return differences;
            }
            for (char marker : new TreeSet<>(before.getCharacters())) {
                String difference = compareMarker(file, before, after, marker);
                if (difference != null) {
                    differences.put(marker, difference);
                }
            }
            return differences;
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
                        BlockPos at = new BlockPos(x, 64 + y, z);
                        String was = describe(before.placedAt(marker, SEED, x, 64 + y, z), at);
                        String is = describe(after.placedAt(marker, SEED, x, 64 + y, z), at);
                        if (!was.equals(is)) {
                            return file + " marker '" + marker + "' at " + x + "," + (64 + y) + ","
                                    + z + ": " + was + " became " + is;
                        }
                    }
                }
            }
            // Every state the marker resolves to, not just its representative. urbex:damaged is keyed
            // by state, so a weighted marker has one mapping per alternative and checking the first
            // proves nothing about the other 127 slots' worth.
            Set<BlockState> states = new java.util.LinkedHashSet<>(before.getAll(marker));
            states.addAll(after.getAll(marker));
            for (BlockState state : states.stream()
                    .sorted(java.util.Comparator.comparing(Object::toString)).toList()) {
                String wasDamaged = String.valueOf(before.canBeDamagedToIronBars(state));
                String isDamaged = String.valueOf(after.canBeDamagedToIronBars(state));
                if (!wasDamaged.equals(isDamaged)) {
                    return file + " marker '" + marker + "': " + state + " damaged into " + wasDamaged
                            + " and now into " + isDamaged;
                }
            }
            return null;
        }

        /**
         * What generation reads off a resolved marker, and nothing else.
         *
         * <p>The light source is described by what it is rather than by identity: two
         * {@code LightSource} records are never equal across the two compilations, and the thing that
         * has to match is whether the marker defers placement and what it leaves behind — read at the
         * position under examination, because an unlit replacement may itself be weighted.</p>
         */
        private static String describe(CompiledPalette.Placed placed, BlockPos at) {
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
                // At this position and not at BlockPos.ZERO: an unlit replacement may be a weighted
                // BlockChoice addressed by position, and one sample point cannot see it differ.
                text.append(" unlit=").append(info.lightSource().unlitAt(SEED, at));
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

    private static JsonElement markerElement(String json, String marker) {
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
