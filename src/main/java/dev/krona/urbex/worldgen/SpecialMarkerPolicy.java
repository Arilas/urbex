package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.varia.DensitySelector;
import net.minecraft.core.BlockPos;

/** Admission policy shared by the production loot and spawner marker handlers. */
final class SpecialMarkerPolicy {

    private SpecialMarkerPolicy() {
    }

    static boolean populateLoot(long seed, BlockPos marker, UrbexProfile profile) {
        return DensitySelector.loot(seed, marker, profile.LOOT_DENSITY);
    }

    static boolean generateSpawner(UrbexProfile profile) {
        return profile.GENERATE_SPAWNERS;
    }
}
