package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.DensitySelector;
import net.minecraft.core.BlockPos;

/** Admission policy shared by the production loot and spawner marker handlers. */
final class SpecialMarkerPolicy {

    private SpecialMarkerPolicy() {
    }

    static boolean populateLoot(long seed, BlockPos marker, Preset profile) {
        return DensitySelector.loot(seed, marker, profile.LOOT_DENSITY);
    }

    static boolean generateSpawner(Preset profile) {
        return profile.GENERATE_SPAWNERS;
    }
}
