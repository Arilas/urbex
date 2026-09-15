package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.format.palette.CompiledEntry;
import dev.krona.urbex.format.palette.TraitSet;
import dev.krona.urbex.varia.Rng;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

/** Precompiled replacement slots, retaining the selected block's own traits. */
public final class PlacementChoice {
    private final CompiledPalette.Placed[] slots;

    private PlacementChoice(CompiledPalette.Placed[] slots) {
        this.slots = slots.clone();
    }

    @Nullable
    public static PlacementChoice of(@Nullable CompiledEntry entry) {
        return entry == null || entry.slotCount() == 0 ? null
                : new PlacementChoice(CompiledPalette.slotsOf(entry));
    }

    @Nullable
    public static PlacementChoice of(@Nullable CompiledEntry entry, TraitSet parentTraits) {
        return entry == null || entry.slotCount() == 0 ? null
                : new PlacementChoice(CompiledPalette.selectionSlotsOf(entry, parentTraits));
    }

    public CompiledPalette.Placed at(Preset preset, long seed, BlockPos pos) {
        return at(seed, pos).selectOptional(preset, seed, pos.getX(), pos.getY(), pos.getZ());
    }

    /** Raw choice for compatibility callers that only inspect the replacement's block state. */
    public CompiledPalette.Placed at(long seed, BlockPos pos) {
        return slots[slots.length == 1 ? 0 : Rng.indexAtPos(seed, pos.getX(), pos.getY(), pos.getZ(),
                Rng.Purpose.LIGHTING_UNLIT, slots.length)];
    }
}
