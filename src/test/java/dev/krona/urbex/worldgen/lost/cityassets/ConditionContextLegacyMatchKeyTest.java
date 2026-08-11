package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code legacyMatchKey} preserves the exact string every {@code cityassets} {@code getName()}
 * used to return before this task qualified them all - the bare path for an {@code urbex}-
 * namespace id, the full qualified id otherwise. {@code ConditionContext}'s {@code inpart}/
 * {@code inbuilding} matching is the one place that old, pre-existing convention is deliberately
 * kept: switching it to the now-qualified {@code getName()} would change which chest-loot
 * conditions fire (confirmed against the digest check), which is a separate, tracked fix, not
 * something this task's reference-qualification pass should do as a side effect.
 */
class ConditionContextLegacyMatchKeyTest {

    @Test
    void anUrbexNamespaceIdReturnsItsBarePath() {
        assertEquals("radiotower", ConditionContext.legacyMatchKey(
                Identifier.fromNamespaceAndPath("urbex", "radiotower")));
    }

    @Test
    void aForeignNamespaceIdReturnsItsFullQualifiedId() {
        assertEquals("urbexmt:radiotower", ConditionContext.legacyMatchKey(
                Identifier.fromNamespaceAndPath("urbexmt", "radiotower")));
    }
}
