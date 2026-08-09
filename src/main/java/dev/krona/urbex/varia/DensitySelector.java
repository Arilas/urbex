package dev.krona.urbex.varia;

import net.minecraft.core.BlockPos;

public final class DensitySelector {
    private DensitySelector() {
    }

    public static boolean lighting(long worldSeed, BlockPos pos, float density) {
        return admit(worldSeed, pos, density, Rng.Purpose.LIGHTING_DENSITY);
    }

    public static boolean loot(long worldSeed, BlockPos pos, float density) {
        return admit(worldSeed, pos, density, Rng.Purpose.LOOT_DENSITY);
    }

    private static boolean admit(long worldSeed, BlockPos pos, float density, Rng.Purpose purpose) {
        if (density <= 0.0f) {
            return false;
        }
        if (density >= 1.0f) {
            return true;
        }
        return Rng.floatAtPos(worldSeed, pos.getX(), pos.getY(), pos.getZ(), purpose) < density;
    }
}
