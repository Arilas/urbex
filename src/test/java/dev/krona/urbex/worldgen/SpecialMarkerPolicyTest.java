package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.PresetDraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialMarkerPolicyTest {

    @Test
    void lootAdmissionUsesDensityWhileSpawnerAdmissionDoesNot() {
        PresetDraft draft = new PresetDraft(Identifier.fromNamespaceAndPath("urbex", "marker-policy"));
        BlockPos marker = new BlockPos(12, 70, -4);
        draft.GENERATE_SPAWNERS = true;

        draft.LOOT_DENSITY = 0.0f;
        assertFalse(SpecialMarkerPolicy.populateLoot(41L, marker, draft.resolve()));
        assertTrue(SpecialMarkerPolicy.generateSpawner(draft.resolve()));

        draft.LOOT_DENSITY = 1.0f;
        assertTrue(SpecialMarkerPolicy.populateLoot(41L, marker, draft.resolve()));
        assertTrue(SpecialMarkerPolicy.generateSpawner(draft.resolve()));

        draft.GENERATE_SPAWNERS = false;
        assertFalse(SpecialMarkerPolicy.generateSpawner(draft.resolve()));
    }
}
