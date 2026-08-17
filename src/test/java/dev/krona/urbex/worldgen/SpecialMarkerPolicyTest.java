package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.PresetDraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialMarkerPolicyTest {

    private static final BlockPos MARKER = new BlockPos(12, 70, -4);

    @Test
    void lootAdmissionUsesDensity() {
        PresetDraft draft = new PresetDraft(Identifier.fromNamespaceAndPath("urbex", "marker-policy"));

        draft.LOOT_DENSITY = 0.0f;
        assertFalse(SpecialMarkerPolicy.populateLoot(41L, MARKER, draft.resolve()));

        draft.LOOT_DENSITY = 1.0f;
        assertTrue(SpecialMarkerPolicy.populateLoot(41L, MARKER, draft.resolve()));
    }

    /**
     * The switch and the dial answer different questions, and both have to hold.
     *
     * <p>{@code generateSpawners} is "this world has none". {@code spawnerDensity} is "fewer", which
     * a switch cannot express and which is what somewhere a player has to survive usually wants.</p>
     */
    @Test
    void spawnerAdmissionNeedsBothTheSwitchAndTheDensity() {
        PresetDraft draft = new PresetDraft(Identifier.fromNamespaceAndPath("urbex", "marker-policy"));

        draft.GENERATE_SPAWNERS = true;
        draft.SPAWNER_DENSITY = 1.0f;
        assertTrue(SpecialMarkerPolicy.generateSpawner(41L, MARKER, draft.resolve()));

        draft.SPAWNER_DENSITY = 0.0f;
        assertFalse(SpecialMarkerPolicy.generateSpawner(41L, MARKER, draft.resolve()),
                "density zero admits nothing, even with the switch on");

        draft.GENERATE_SPAWNERS = false;
        draft.SPAWNER_DENSITY = 1.0f;
        assertFalse(SpecialMarkerPolicy.generateSpawner(41L, MARKER, draft.resolve()),
                "the switch still wins, whatever the density says");
    }

    /**
     * The default, and the reason every existing world generates unchanged: a preset that says
     * nothing about the density admits every marker its parts put down.
     */
    @Test
    void aPresetThatNamesNoDensityKeepsEverySpawner() {
        PresetDraft draft = new PresetDraft(Identifier.fromNamespaceAndPath("urbex", "marker-policy"));

        assertTrue(draft.resolve().generateSpawners());
        for (int x = 0; x < 40; x++) {
            BlockPos marker = new BlockPos(x * 7, 64 + x, -x * 3);
            assertTrue(SpecialMarkerPolicy.generateSpawner(41L, marker, draft.resolve()),
                    "marker at " + marker + " was refused under the default density");
        }
    }

    /** Thinning is position-addressed, so the same marker answers the same way every time. */
    @Test
    void aThinnedSpawnerIsAPropertyOfTheSeedAndThePosition() {
        PresetDraft draft = new PresetDraft(Identifier.fromNamespaceAndPath("urbex", "marker-policy"));
        draft.SPAWNER_DENSITY = 0.5f;

        int kept = 0;
        for (int x = 0; x < 200; x++) {
            BlockPos marker = new BlockPos(x, 64, x * 2);
            boolean first = SpecialMarkerPolicy.generateSpawner(41L, marker, draft.resolve());
            assertTrue(first == SpecialMarkerPolicy.generateSpawner(41L, marker, draft.resolve()),
                    "the same marker answered two different ways");
            if (first) {
                kept++;
            }
        }
        assertTrue(kept > 50 && kept < 150,
                "half density kept " + kept + " of 200, which is not a half of anything");
    }
}
