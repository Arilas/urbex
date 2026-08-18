package dev.krona.urbex.data;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every block id in the bundled datapack must name a block this Minecraft version actually has.
 * <p>
 * Nothing checked this, and the failure mode is silent rather than loud: {@code Tools.stringToState}
 * ends by returning {@code minecraft:air} for an id it cannot resolve, and version 2 reaches the same
 * outcome deliberately - {@code MODEL.042} makes an absent id an {@code ACCEPT}, because a pack naming
 * optional cross-mod content must not refuse the world. Either way an id that a Minecraft version
 * renames turns into air everywhere it is used, with no exception and one warning in the log that
 * nobody reads while playing. (It used to reach that outcome by accident, through
 * {@code BuiltInRegistries.BLOCK.getValue} handing back a defaulted registry's default; it is
 * written out as a return now, and pinned by {@code BlockResolutionTest}, so #91 has one line to
 * change.)
 * <p>
 * Two shipped entries were in exactly that state when this test was written, both surfaced by
 * Task 5c making the load-time validation actually run: {@code minecraft:chain} (renamed
 * {@code minecraft:iron_chain} in 26.x), which made the whole {@code urbex:chains} decoration
 * invisible, and {@code minecraft:red_sandstone@2}, a 1.12 {@code name@meta} string predating
 * flattening that is not even a legal {@link Identifier} - {@code Identifier.parse} threw on it,
 * and because that happens inside {@code Palette}'s constructor it took the whole world load with
 * it as soon as anything resolved that palette.
 * <p>
 * The air fallback itself is left alone here; see the Task 5c report. This test is the guard that
 * does not depend on it.
 * <p>
 * Which strings count as block ids is {@link ShippedBlockRefs}, shared with
 * {@link RotatableTagCoversShippedBlocksTest} rather than copied into it - see that class for what the
 * copy cost.
 */
class ShippedBlockIdsResolveTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyShippedBlockIdNamesARealBlock() throws IOException {
        List<ShippedBlockRefs.Ref> refs = ShippedBlockRefs.under(ShippedBlockRefs.DATA_ROOT);
        assertFalse(refs.isEmpty(), "found no block ids at all - the walk or the key names are wrong");

        List<String> problems = new ArrayList<>();
        for (ShippedBlockRefs.Ref ref : refs) {
            // Block-state properties are parsed separately by Tools.stringToState and by
            // BlockStrings.resolve; only the id is under test here.
            String id = ref.value().contains("[")
                    ? ref.value().substring(0, ref.value().indexOf('['))
                    : ref.value();
            try {
                if (!BuiltInRegistries.BLOCK.containsKey(Identifier.parse(id))) {
                    problems.add(ref.file() + ": '" + ref.value() + "' is not a block in this "
                            + "version - it would silently generate as air");
                }
            } catch (Exception e) {
                problems.add(ref.file() + ": '" + ref.value() + "' is not a valid block id: " + e.getMessage());
            }
        }
        assertTrue(problems.isEmpty(),
                () -> problems.size() + " unresolvable block id(s):\n" + String.join("\n", problems));
    }

    /**
     * The bundled pack reaches this test through both of {@link ShippedBlockRefs}' walks, which is
     * worth asserting because one of them silently covering nothing is exactly how this guard - and the
     * rotatable one beside it - stops guarding.
     *
     * <p>The counts are the shape of the pack rather than a second copy of it: thirty version 2
     * palettes and thirteen definitions on one side, and on the other the six parts and buildings whose
     * inline palettes are still version 1 ({@code VER.006} allows the mix) together with the twelve
     * {@code variants} the version 1 side of the pack still reads.</p>
     */
    @Test
    void bothWalksReachTheFilesTheyAreFor() throws IOException {
        List<ShippedBlockRefs.Ref> refs = ShippedBlockRefs.under(ShippedBlockRefs.DATA_ROOT);
        List<String> version2 = ShippedBlockRefs.version2Documents(ShippedBlockRefs.DATA_ROOT);
        TreeSet<String> version1 = new TreeSet<>();
        refs.stream().filter(ref -> !ref.version2()).forEach(ref -> version1.add(ref.file()));

        assertEquals(43, version2.size(),
                () -> "thirty palettes and thirteen definitions assets are written in version 2, and "
                        + "the version 2 walk is responsible for " + version2.size() + ": " + version2);
        assertEquals(18, version1.size(),
                () -> "twelve variants and the six parts and buildings with an inline version 1 "
                        + "palette still spell a block by key, and the key walk reached "
                        + version1.size() + ": " + version1);

        // The totals, so that a walk narrowing without losing a whole file is caught too. This is the
        // number the rotatable guard lost: run version 1's key names over the converted pack and it
        // finds 200 of these and 89 distinct, because the string shorthand has no 'block' key - every
        // rail, ladder, lever, iron_trapdoor, iron_door, barrel, oak_fence and most stairs go missing.
        //
        // 337 rather than the 333 the same key walk found over the pack in version 1, and the four
        // causes account for it exactly: +42 for definitions/, which ships beside the variants/ it was
        // converted from and is counted twice until the inline palettes convert; +4 for the unlit
        // values the key walk never saw in either format, since 'unlit' was not one of its two keys;
        // +2 for the stand-ins the two sockets now state; and -44 for the 45 repetitions of
        // 'damaged: iron_bars' that became one 'into' in urbex:damageable. 333 + 42 + 4 + 2 - 44 = 337.
        assertEquals(337, refs.size(), () -> "block strings written across the pack: " + refs.size());
        assertEquals(163, refs.stream().map(ShippedBlockRefs.Ref::value).distinct().count(),
                "of which this many are distinct");
    }
}
