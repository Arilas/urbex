package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Traits apply in phases, and a decorator decorates whatever selection produced.
 *
 * <p>{@code TRAIT.095} splits the defined traits into <b>selection</b> ({@code urbex:light},
 * {@code urbex:optional} - which block stands here), <b>transformation</b> ({@code urbex:rotatable} -
 * rewrite the selected state) and <b>decoration</b> ({@code urbex:loot}, {@code urbex:spawner},
 * {@code urbex:block_entity} - attach data to what selection produced). {@code TRAIT.092} is what
 * survives of the old blanket prohibition: order must not matter <em>within</em> a phase, which is why
 * {@code TRAIT.064} refuses two selection traits on one node.</p>
 *
 * <p>{@code TRAIT.096} is the consequence, and it is the one version 1 could never reach. A decorator
 * applies to the state selection produced - so a marker carrying both {@code urbex:block_entity} and
 * {@code urbex:light} writes its NBT to the <em>unlit</em> block on every position where the lighting
 * roll rejects the light. If that replacement has no block entity, the NBT silently never appears
 * there, which is exactly the version 1 silence {@code TRAIT.041} exists to close, one position over.
 * {@code TRAIT.044} closes it.</p>
 *
 * <p>Version 1's {@code else if} chain could not produce this case at all: it applied one trait ever, so
 * a marker carrying a light and a block entity applied the light and dropped the NBT before it could be
 * written anywhere. The loop that replaces it cannot hide the question.</p>
 */
class TraitPhaseTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Rule("TRAIT.044")
    @Rule("TRAIT.096")
    @Test
    void aBlockEntityBesideALightIsRefusedWhenTheUnlitReplacementCannotHoldItsNbt() {
        String message = compileRefusal("""
                {
                  "version": 2,
                  "palette": {
                    "C": {
                      "block": "minecraft:campfire",
                      "traits": {
                        "urbex:block_entity": { "nbt": { "Items": [] } },
                        "urbex:light": { "unlit": "minecraft:stone_bricks" }
                      }
                    }
                  }
                }
                """);

        assertTrue(Diag.DIAG_022.matches(message), message);
        assertTrue(message.contains("minecraft:stone_bricks"),
                "the message must name the replacement whose NBT would never be written, not the "
                        + "chest that holds it perfectly well: " + message);
    }

    @Rule("TRAIT.044")
    @Test
    void aBlockEntityBesideALightIsAcceptedWhenTheUnlitReplacementCanHoldTheNbtToo() {
        assertTrue(compiles("""
                {
                  "version": 2,
                  "palette": {
                    "C": {
                      "block": "minecraft:campfire",
                      "traits": {
                        "urbex:block_entity": { "nbt": { "Items": [] } },
                        "urbex:light": { "unlit": "minecraft:barrel" }
                      }
                    }
                  }
                }
                """), "a barrel holds the NBT as squarely as the campfire does, so nothing is silent");
    }

    @Rule("TRAIT.044")
    @Rule("TRAIT.043")
    @Test
    void aWeightedReplacementIsRefusedOnlyWhenNoneOfItsAlternativesCanHoldTheNbt() {
        assertTrue(compiles("""
                {
                  "version": 2,
                  "palette": {
                    "C": {
                      "block": "minecraft:campfire",
                      "traits": {
                        "urbex:block_entity": { "nbt": { "Items": [] } },
                        "urbex:light": { "unlit": { "kind": "weighted", "choices": [
                            { "weight": 1, "block": "minecraft:barrel" },
                            { "weight": 1, "block": "minecraft:stone_bricks" } ] } }
                      }
                    }
                  }
                }
                """), "TRAIT.043's mixed case reads the same one position over: refusing a replacement "
                        + "because one of its alternatives cannot hold the NBT is the over-rejection "
                        + "ACCEPT exists as a class to prevent");
    }

    @Rule("TRAIT.044")
    @Test
    void aBlockEntityWithNoSelectionTraitIsUnaffectedSoNothingThatCompilesTodayStopsCompiling() {
        assertTrue(compiles("""
                {
                  "version": 2,
                  "palette": {
                    "C": { "block": "minecraft:campfire",
                           "traits": { "urbex:block_entity": { "nbt": { "Items": [] } } } }
                  }
                }
                """), "with no selection trait there is no replacement, so there is nothing to ask");
    }

    @Rule("TRAIT.044")
    @Test
    void anAbsentReplacementIsAirAndAirIsRefusedBecauseAirHoldsNoBlockEntityEither() {
        String message = compileRefusal("""
                {
                  "version": 2,
                  "palette": {
                    "C": {
                      "block": "minecraft:campfire",
                      "traits": {
                        "urbex:block_entity": { "nbt": { "Items": [] } },
                        "urbex:light": {}
                      }
                    }
                  }
                }
                """);

        assertTrue(Diag.DIAG_022.matches(message),
                "TRAIT.062's default replacement is air, and writing a campfire's NBT onto air is the "
                        + "same silence written shorter: " + message);
    }

    @Rule("TRAIT.095")
    @Rule("TRAIT.064")
    @Test
    void twoSelectionTraitsOnOneNodeAreStillRefusedBecauseTheyAreOfOnePhase() {
        String message = compileRefusal("""
                {
                  "version": 2,
                  "palette": {
                    "e": {
                      "block": "minecraft:lantern",
                      "traits": {
                        "urbex:light":    { "unlit": "minecraft:air" },
                        "urbex:optional": { "density": "stuff" }
                      }
                    }
                  }
                }
                """);

        assertTrue(Diag.DIAG_025.matches(message),
                "TRAIT.064 is now an instance of TRAIT.092 rather than a special case, and it still "
                        + "refuses: " + message);
    }

    @Rule("TRAIT.095")
    @Test
    void aDecorationTraitBesideASelectionTraitIsNotRefusedForBeingBesideIt() {
        assertTrue(compiles("""
                {
                  "version": 2,
                  "palette": {
                    "S": {
                      "block": "minecraft:campfire",
                      "traits": {
                        "urbex:spawner": { "pool": "urbex:easymobs" },
                        "urbex:light": { "unlit": "minecraft:air" }
                      }
                    }
                  }
                }
                """, Set.of(Identifier.parse("urbex:easymobs"))),
                "traits of different phases compose; only same-phase conflicts are refused");
    }

    private static String compileRefusal(String json) {
        Diagnostics diagnostics = new Diagnostics();
        assertFalse(CompiledV2Palette.compile(TraitTest.resolve(json), TraitTest.installed(),
                        TraitContext.withConditions(BuiltInRegistries.BLOCK, Set.of()),
                        Diagnostics.DECODING_LOCATION, diagnostics).isPresent(),
                "expected the palette to be refused");
        return diagnostics.asError().orElseThrow(
                () -> new AssertionError("refused the palette and said nothing"));
    }

    private static boolean compiles(String json) {
        return compiles(json, Set.of());
    }

    private static boolean compiles(String json, Set<Identifier> conditions) {
        Diagnostics diagnostics = new Diagnostics();
        return CompiledV2Palette.compile(TraitTest.resolve(json), TraitTest.installed(),
                        TraitContext.withConditions(BuiltInRegistries.BLOCK, conditions),
                        Diagnostics.DECODING_LOCATION, diagnostics)
                .isPresent() && !diagnostics.hasFatal();
    }
}
