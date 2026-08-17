package dev.krona.urbex.worldgen.lost.cityassets;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.format.palette.CompiledEntry;
import dev.krona.urbex.format.palette.CompiledV2Palette;
import dev.krona.urbex.format.palette.Exclusion;
import dev.krona.urbex.format.palette.Marker;
import dev.krona.urbex.format.palette.NodeResolver;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.format.palette.TraitContext;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A compiled version 2 {@code light_socket} as a {@link LightPool}.
 *
 * <p>This is the one hand-written mapping between two already-compiled forms in the version 2 wiring,
 * and it is tested on its own because that is the shape {@code docs/format/README.md} §1 is about: a
 * second representation of a fact the format already states, which drifts and is silent when it does.
 * The fact being re-derived is <em>how likely is this candidate</em> — weights, recovered from slot
 * counts.</p>
 */
class V2SocketsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Rule("MODEL.071")
    @Test
    void eachPlacementListBecomesItsOwnCandidateGroupAndAnEmptyOneStaysEmpty() {
        LightPool pool = poolFor("""
                {
                  "version": 2,
                  "palette": {
                    "T": {
                      "kind": "light_socket",
                      "floor": [ { "weight": 1, "block": "minecraft:lantern" } ],
                      "ceiling": [ { "weight": 1, "block": "minecraft:lantern[hanging=true]" } ]
                    }
                  }
                }
                """);

        assertTrue(pool.hasCandidates(LightPool.Placement.FLOOR));
        assertTrue(pool.hasCandidates(LightPool.Placement.CEILING));
        assertFalse(pool.hasCandidates(LightPool.Placement.WALL),
                "a list the file did not write has no candidates, and MODEL.073's search skips it");
        assertFalse(pool.hasCandidates(LightPool.Placement.FREE));
    }

    @Rule("WEIGHT.040")
    @Test
    void candidateWeightsAreTheApportionedSlotCountsAndNotTheAuthoredWeights() {
        LightPool pool = poolFor("""
                {
                  "version": 2,
                  "palette": {
                    "T": {
                      "kind": "light_socket",
                      "floor": [
                        { "weight": 1, "block": "minecraft:lantern" },
                        { "weight": 1, "block": "minecraft:torch" },
                        { "weight": 1, "block": "minecraft:soul_torch" }
                      ]
                    }
                  }
                }
                """);

        List<LightPool.Candidate> floor = pool.weightedOrder(LightPool.Placement.FLOOR,
                RandomSource.create(1L));
        assertEquals(3, floor.size());
        assertEquals(128, floor.stream().mapToInt(LightPool.Candidate::weight).sum(),
                "three equal alternatives are 43, 43 and 42 of 128 slots - WEIGHT.040's one rounding "
                        + "step, read back rather than performed a second time");
        assertEquals(List.of(42, 43, 43),
                floor.stream().map(LightPool.Candidate::weight).sorted().toList());
    }

    @Rule("TRAIT.055")
    @Rule("TRAIT.005")
    @Test
    void aCandidatesOwnUnlitWinsAndOneThatDeclaresNoneHasAlreadyInheritedTheSockets() {
        LightPool pool = poolFor("""
                {
                  "version": 2,
                  "palette": {
                    "T": {
                      "kind": "light_socket",
                      "traits": { "urbex:light": { "unlit": "minecraft:stone_bricks" } },
                      "floor": [
                        { "weight": 1, "block": "minecraft:lantern",
                          "traits": { "urbex:light": { "unlit": "minecraft:cobblestone" } } },
                        { "weight": 1, "block": "minecraft:torch" }
                      ]
                    }
                  }
                }
                """);

        List<LightPool.Candidate> floor = pool.weightedOrder(LightPool.Placement.FLOOR,
                RandomSource.create(1L));
        LightPool.Candidate lantern = named(floor, Blocks.LANTERN);
        LightPool.Candidate torch = named(floor, Blocks.TORCH);

        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), lantern.unlit(),
                "TRAIT.055: a candidate's own unlit takes precedence over the socket's");
        assertEquals(Blocks.STONE_BRICKS.defaultBlockState(), torch.unlit(),
                "and a candidate that declares none arrives carrying the socket's already, because "
                        + "TRAIT.005 makes a candidate an alternative that inherits its parent's "
                        + "traits - so TRAIT.055's precedence is done at stage 3 and there is nothing "
                        + "left for a null fallback to do here");
    }

    @Rule("TRAIT.051")
    @Test
    void aCandidateCarryingTheLightTraitWithNoUnlitGetsAirRatherThanTheSockets() {
        LightPool pool = poolFor("""
                {
                  "version": 2,
                  "palette": {
                    "T": {
                      "kind": "light_socket",
                      "traits": { "urbex:light": { "unlit": "minecraft:stone_bricks" } },
                      "floor": [ { "weight": 1, "block": "minecraft:lantern",
                                   "traits": { "urbex:light": {} } } ]
                    }
                  }
                }
                """);

        LightPool.Candidate only = pool.weightedOrder(LightPool.Placement.FLOOR,
                RandomSource.create(1L)).getFirst();

        assertEquals(Blocks.AIR.defaultBlockState(), only.unlit(),
                "TRAIT.051 defaults an absent unlit to air at decode, so carrying the trait and naming "
                        + "no replacement means air - deliberately, and not the socket's replacement");
    }

    @Rule("WEIGHT.032")
    @Rule("LOAD.010")
    @Test
    void aSocketWhoseEveryCandidateNamesAnAbsentBlockNeverReachesThisMappingAtAll() {
        String refusal = compileRefusal("""
                {
                  "version": 2,
                  "palette": {
                    "T": {
                      "kind": "light_socket",
                      "floor": [ { "weight": 1, "block": "create:andesite_lantern" } ]
                    }
                  }
                }
                """);

        assertTrue(Diag.DIAG_043.matches(refusal), refusal);
        // Version 1 met this case at generation: LightPool.compile returned null and the caller wrote
        // air. Version 2 refuses it at load, by name, which is LOAD.010 - so poolOf has no empty case
        // to answer and says so by throwing rather than by returning null.
    }

    @Rule("WEIGHT.032")
    @Test
    void oneEmptyPlacementListIsFineBecauseOnlyAllFourAtOnceIsRefused() {
        LightPool pool = poolFor("""
                {
                  "version": 2,
                  "palette": {
                    "T": {
                      "kind": "light_socket",
                      "floor": [ { "weight": 1, "block": "minecraft:lantern" } ],
                      "ceiling": [ { "weight": 1, "block": "create:andesite_lantern" } ]
                    }
                  }
                }
                """);

        assertTrue(pool.hasCandidates(LightPool.Placement.FLOOR));
        assertFalse(pool.hasCandidates(LightPool.Placement.CEILING),
                "the ceiling list lost its only candidate to WEIGHT.030 and the marker still places, "
                        + "so MODEL.073's search simply skips that opportunity");
    }

    @Rule("MODEL.073")
    @Test
    void theCandidateOrderComesFromTheSlotsAndNotFromAHashSoItIsTheSameEveryRun() {
        String json = """
                {
                  "version": 2,
                  "palette": {
                    "T": {
                      "kind": "light_socket",
                      "floor": [
                        { "weight": 3, "block": "minecraft:lantern" },
                        { "weight": 2, "block": "minecraft:torch" },
                        { "weight": 1, "block": "minecraft:soul_torch" }
                      ]
                    }
                  }
                }
                """;

        List<Integer> first = declaredOrderWeights(json);
        List<Integer> second = declaredOrderWeights(json);

        assertEquals(first, second, "the candidate order is observable in the world - weightedOrder "
                + "rotates from the drawn winner over this list - so it must not come from a hash");
        assertEquals(List.of(64, 43, 21), first,
                "declaration order is kept, and 3/2/1 of six apportions to 64/43/21 of 128 slots: "
                        + first);
    }

    private static List<Integer> declaredOrderWeights(String json) {
        // allCandidates, not weightedOrder: weightedOrder deliberately rotates from the drawn winner,
        // so it is the wrong instrument for asking what order the candidates are in.
        return poolFor(json).allCandidates().stream().map(LightPool.Candidate::weight).toList();
    }

    private static LightPool.Candidate named(List<LightPool.Candidate> candidates,
                                             net.minecraft.world.level.block.Block block) {
        return candidates.stream().filter(c -> c.state().getBlock() == block).findFirst()
                .orElseThrow(() -> new AssertionError("no candidate for " + block));
    }

    private static LightPool poolFor(String json) {
        LightPool pool = V2Sockets.poolOf(socketFor(json));
        assertNotNull(pool, "expected the socket to produce a pool");
        return pool;
    }

    /** The message compiling {@code json} is refused with. */
    private static String compileRefusal(String json) {
        Diagnostics diagnostics = new Diagnostics();
        PaletteV2Definition file = PaletteV2Definition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result()
                .orElseThrow(() -> new AssertionError("expected the palette to decode"));
        NodeResolver.ResolvedPalette resolved = NodeResolver.resolve(file, diagnostics)
                .orElseThrow(() -> new AssertionError("expected the palette to resolve"));
        assertTrue(CompiledV2Palette.compile(resolved,
                        Exclusion.installed(BuiltInRegistries.BLOCK, Set.of("urbex", "minecraft")),
                        TraitContext.of(BuiltInRegistries.BLOCK),
                        Diagnostics.DECODING_LOCATION, diagnostics).isEmpty(),
                "expected the palette to be refused");
        return diagnostics.asError().orElseThrow(
                () -> new AssertionError("refused the palette and said nothing"));
    }

    private static CompiledEntry socketFor(String json) {
        CompiledEntry entry = compile(json).entry(new Marker('T').codepoint());
        assertNotNull(entry, "expected the socket marker to compile");
        return entry;
    }

    /**
     * Decode, link and compile one document. A local copy of the format package's own harness rather
     * than a shared one, because sharing it would mean making a test class public across packages -
     * and this needs three lines of it.
     */
    private static CompiledV2Palette compile(String json) {
        Diagnostics diagnostics = new Diagnostics();
        PaletteV2Definition file = PaletteV2Definition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result()
                .orElseThrow(() -> new AssertionError("expected the palette to decode"));
        NodeResolver.ResolvedPalette resolved = NodeResolver.resolve(file, diagnostics)
                .orElseThrow(() -> new AssertionError(
                        "expected the palette to resolve: " + diagnostics.asError().orElse("?")));
        return CompiledV2Palette.compile(resolved,
                        Exclusion.installed(BuiltInRegistries.BLOCK, Set.of("urbex", "minecraft")),
                        TraitContext.of(BuiltInRegistries.BLOCK),
                        Diagnostics.DECODING_LOCATION, diagnostics)
                .orElseThrow(() -> new AssertionError(
                        "expected the palette to compile: " + diagnostics.asError().orElse("?")));
    }
}
