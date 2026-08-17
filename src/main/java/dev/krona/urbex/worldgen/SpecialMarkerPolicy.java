package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.DensitySelector;
import net.minecraft.core.BlockPos;

/** Admission policy shared by the production loot and spawner marker handlers. */
final class SpecialMarkerPolicy {

    private SpecialMarkerPolicy() {
    }

    static boolean populateLoot(long seed, BlockPos marker, Preset profile) {
        return DensitySelector.loot(seed, marker, profile.lootDensity());
    }

    /**
     * Whether the marker at {@code marker} keeps its spawner.
     *
     * <p>Two gates, and they answer different questions. {@code generateSpawners} is the switch: a
     * world that wants none has none. {@code spawnerDensity} thins what survives, position-addressed
     * like loot so which markers keep theirs is a property of the seed and the coordinate rather
     * than of the order the parts were generated in.</p>
     *
     * <p>The density defaults to 1, so a preset that says nothing about it admits every marker and
     * generates exactly what it did before this existed.</p>
     */
    static boolean generateSpawner(long seed, BlockPos marker, Preset profile) {
        return profile.generateSpawners()
                && DensitySelector.spawner(seed, marker, profile.spawnerDensity());
    }
}
