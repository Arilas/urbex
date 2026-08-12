package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the thing that would satisfy a reference is installed - which is what decides whether an
 * unresolvable reference is worth telling anyone about (issues #56, #91).
 * <p>
 * A pack may name a block, mob, loot table or part from something it does not require, so that
 * players who have it get the content and everyone else does not. Those must be silent. A reference
 * whose provider <em>is</em> installed and still does not resolve is a rename or a typo, and must
 * not be.
 */
class ReferenceProviderTest {

    /**
     * The special case that carries the whole vanilla story. Outside a game
     * {@code isModLoaded("minecraft")} answers false, so without this a renamed vanilla id - the
     * {@code minecraft:chain} that made {@code urbex:chains} invisible - would be filed as somebody
     * else's missing mod and never reported.
     */
    @Test
    void minecraftAlwaysCounts() {
        assertTrue(ReferenceProvider.modIsInstalled(Identifier.parse("minecraft:iron_chain")));
    }

    @Test
    void aModNobodyHasDoesNot() {
        assertFalse(ReferenceProvider.modIsInstalled(Identifier.parse("somemod:fancy_block")));
    }

    /**
     * A datapack need not be a mod, so the loader is the wrong question for an asset reference: a
     * pack shipping only JSON has no mod id to ask about. What is asked instead is whether anything
     * loaded actually registered assets in that namespace.
     */
    @Test
    void aPackIsInstalledWhenItsNamespaceHasAssets() {
        Set<String> loaded = Set.of("urbex", "urbexmt");

        assertTrue(ReferenceProvider.packIsInstalled(Identifier.parse("urbexmt:tower"), loaded));
        assertFalse(ReferenceProvider.packIsInstalled(Identifier.parse("somepack:tower"), loaded));
    }
}
