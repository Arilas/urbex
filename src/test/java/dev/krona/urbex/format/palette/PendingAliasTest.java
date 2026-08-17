package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Rule;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code alias} whose target this palette does not define, which is the shipped idiom rather than a
 * mistake.
 *
 * <p>{@code MODEL.062} refuses an alias "whose target is defined by no palette of the merge a part is
 * generated with", and states outright that it "is decided there, and not against one palette's
 * {@code extends} chain". {@code MODEL.064} says why: the merge holds "markers contributed by palettes
 * this file never mentions". {@code urbex:glass_side_variant_glass} maps {@code '@'} to {@code 'a'} and
 * declares nothing else at all, and an earlier validator that asked this question one palette at a time
 * "reported 45 problems in a pack that generates correctly".</p>
 *
 * <p>These tests are about the half of that the compiled palette owes: it has to <em>carry the question
 * out</em>. Dropping the alias - which is what this class did before - makes the question unanswerable
 * later, because a marker that is silently absent cannot be told apart from a marker the file never
 * wrote.</p>
 */
class PendingAliasTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final Marker AT = new Marker('@');
    private static final Marker A = new Marker('a');

    @Rule("MODEL.062")
    @Rule("MODEL.064")
    @Test
    void anAliasNamingAMarkerThisPaletteDoesNotDefineIsCarriedOutRatherThanDropped() {
        CompiledV2Palette palette = TraitTest.compile("""
                { "version": 2, "palette": { "@": { "kind": "alias", "of": "a" } } }
                """, Set.of());

        assertEquals(Set.of(AT), palette.pendingAliases().keySet(),
                "the alias is the question the merge answers, so it has to survive compilation");
        assertEquals(A, palette.pendingAliases().get(AT).target());
        assertNull(palette.entry(AT.codepoint()),
                "and it is not an entry here, because this palette cannot say what it resolves to");
    }

    @Rule("MODEL.060")
    @Test
    void anAliasNamingAMarkerThisPaletteDoesDefineIsAnsweredHereAndLeavesNothingPending() {
        CompiledV2Palette palette = TraitTest.compile("""
                {
                  "version": 2,
                  "palette": {
                    "a": "minecraft:stone_bricks",
                    "@": { "kind": "alias", "of": "a" }
                  }
                }
                """, Set.of());

        assertTrue(palette.pendingAliases().isEmpty(),
                "nothing is owed to the merge when the target is in this palette");
        assertNotNull(palette.entry(AT.codepoint()));
        assertEquals(Blocks.STONE_BRICKS.defaultBlockState(),
                palette.at(AT, 1L, 0, 0, 0).state());
    }

    @Rule("MODEL.063")
    @Test
    void aPendingAliasCarriesItsOwnTraitsSoTheMergeCanApplyThemOverWhateverItResolves() {
        CompiledV2Palette palette = TraitTest.compile("""
                {
                  "version": 2,
                  "palette": {
                    "@": {
                      "kind": "alias", "of": "a",
                      "traits": { "urbex:damaged": { "into": "minecraft:iron_bars" } }
                    }
                  }
                }
                """, Set.of());

        CompiledV2Palette.Pending pending = palette.pendingAliases().get(AT);
        assertEquals(A, pending.target());
        assertTrue(pending.own().traits().containsKey(Identifier.parse("urbex:damaged")),
                "MODEL.063: an alias carries the traits of its target, then its own - so its own have "
                        + "to travel with the target's name");
    }

    @Rule("MODEL.062")
    @Test
    void aCycleOfAliasesWithinOnePaletteBecomesAPendingQuestionRatherThanRecursing() {
        CompiledV2Palette palette = TraitTest.compile("""
                {
                  "version": 2,
                  "palette": {
                    "@": { "kind": "alias", "of": "a" },
                    "a": { "kind": "alias", "of": "@" }
                  }
                }
                """, Set.of());

        assertEquals(2, palette.pendingAliases().size(),
                "an alias cycle resolves to nothing anywhere, so it goes to the merge as a target the "
                        + "merge will not find either - reported once, with the rest, rather than as a "
                        + "StackOverflowError here");
        assertFalse(palette.pendingAliases().isEmpty());
    }

    @Rule("MODEL.063")
    @Rule("LOAD.023")
    @Test
    void theMergesOverlayIsTheSameOneAnInPaletteAliasGotAndSharesItsTraitSets() {
        CompiledV2Palette target = TraitTest.compile("""
                { "version": 2, "palette": { "a": "minecraft:stone_bricks" } }
                """, Set.of());
        CompiledEntry base = target.entry(A.codepoint());
        Map<TraitSet, TraitSet> interned = new LinkedHashMap<>();

        CompiledEntry overlaid = CompiledV2Palette.overlay(base, TraitSet.EMPTY, interned);

        assertSame(base, overlaid,
                "an alias that adds no traits adds no object either, which is LOAD.030's sharing "
                        + "established rather than recovered");
    }
}
