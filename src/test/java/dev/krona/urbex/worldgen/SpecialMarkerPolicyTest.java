package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.LostCityProfile;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialMarkerPolicyTest {

    @Test
    void lootAdmissionUsesDensityWhileSpawnerAdmissionDoesNot() {
        LostCityProfile profile = new LostCityProfile("marker-policy", true);
        BlockPos marker = new BlockPos(12, 70, -4);
        profile.GENERATE_SPAWNERS = true;

        profile.LOOT_DENSITY = 0.0f;
        assertFalse(SpecialMarkerPolicy.populateLoot(41L, marker, profile));
        assertTrue(SpecialMarkerPolicy.generateSpawner(profile));

        profile.LOOT_DENSITY = 1.0f;
        assertTrue(SpecialMarkerPolicy.populateLoot(41L, marker, profile));
        assertTrue(SpecialMarkerPolicy.generateSpawner(profile));

        profile.GENERATE_SPAWNERS = false;
        assertFalse(SpecialMarkerPolicy.generateSpawner(profile));
    }
}
