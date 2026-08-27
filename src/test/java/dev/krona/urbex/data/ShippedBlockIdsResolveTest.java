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
     * palettes, thirteen definitions and the six parts and buildings whose inline palettes are now
     * version 2 as well - and, on the other side, nothing.</p>
     *
     * <p><b>The key-name branch now covers no bundled file, and that is asserted rather than left to be
     * noticed.</b> It covered the twelve {@code variants} until {@code VER.017} removed the registry.
     * The branch is kept because it is what reads the registries that are not palettes, and none of
     * those happens to spell a block today; an assertion of zero is the honest statement of that, and it
     * fails the moment a bundled file spells one by key again - which is the only event that would make
     * the branch load-bearing without anyone deciding it should be.</p>
     */
    @Test
    void bothWalksReachTheFilesTheyAreFor() throws IOException {
        List<ShippedBlockRefs.Ref> refs = ShippedBlockRefs.under(ShippedBlockRefs.DATA_ROOT);
        List<String> version2 = ShippedBlockRefs.version2Documents(ShippedBlockRefs.DATA_ROOT);
        TreeSet<String> version1 = new TreeSet<>();
        refs.stream().filter(ref -> !ref.version2()).forEach(ref -> version1.add(ref.file()));

        assertEquals(49, version2.size(),
                () -> "thirty palettes, thirteen definitions assets and the six parts and buildings "
                        + "whose inline palette is version 2 are what the version 2 walk is "
                        + "responsible for, and it is responsible for " + version2.size()
                        + ": " + version2);
        assertEquals(0, version1.size(),
                () -> "VER.017 took the twelve variants with the registry, and they were the last "
                        + "bundled files to spell a block by key; the key walk reached "
                        + version1.size() + ": " + version1);

        // The totals, so that a walk narrowing without losing a whole file is caught too. This is the
        // number the rotatable guard lost: run version 1's key names over the converted pack and it
        // finds 184 of these and 79 distinct, because the string shorthand has no 'block' key - every
        // rail, ladder, lever, iron_trapdoor, iron_door, barrel, oak_fence and most stairs go missing.
        // It found 200 and 89 before the six inline palettes converted, and the 16 it lost are exactly
        // those files: 9 in cabin and 1 in top1x1_5 became string shorthand, and the three buildings'
        // 6 'damaged' became 'into'. park_trees lost none, because a weighted choice still spells its
        // block 'block'.
        //
        // 295 rather than the 333 the same key walk found over the pack in version 1, and three causes
        // account for it exactly: +4 for the unlit values the key walk never saw in either format,
        // since 'unlit' was not one of its two keys; +2 for the stand-ins the two sockets now state;
        // and -44 for the 45 repetitions of 'damaged: iron_bars' that became one 'into' in
        // urbex:damageable. 333 + 4 + 2 - 44 = 295.
        //
        // There was a fourth term until VER.017: +42 for definitions/, which shipped beside the
        // variants/ it was converted from and was counted twice while both existed. Deleting the
        // registry deleted the duplicate, which is the whole of the difference between this number and
        // the 337 that stood here.
        assertEquals(295, refs.size(), () -> "block strings written across the pack: " + refs.size());
        assertEquals(163, refs.stream().map(ShippedBlockRefs.Ref::value).distinct().count(),
                "of which this many are distinct");
    }
}
