package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.Rng;

/** A named density and its already compiled replacement slots. Built once, read by position. */
public final class OptionalSelection {
    private final boolean lighting;
    private final CompiledPalette.Placed[] replacements;

    public OptionalSelection(String density, CompiledPalette.Placed[] replacements) {
        lighting = switch (density) {
            case "lightingDensity" -> true;
            case "lootDensity" -> false;
            default -> throw new IllegalArgumentException("Unknown optional density '" + density + "'");
        };
        if (replacements.length == 0) {
            throw new IllegalArgumentException("An optional replacement must contain a block slot");
        }
        this.replacements = replacements.clone();
    }

    /** Returns one existing slot; neither endpoint nor a weighted choice allocates. */
    public CompiledPalette.Placed select(CompiledPalette.Placed original, Preset preset,
                                         long seed, int x, int y, int z) {
        float density = lighting ? preset.lightingDensity() : preset.lootDensity();
        Rng.Purpose purpose = lighting ? Rng.Purpose.LIGHTING_DENSITY : Rng.Purpose.LOOT_DENSITY;
        if (density >= 1 || (density > 0 && Rng.floatAtPos(seed, x, y, z, purpose) < density)) {
            return original;
        }
        // Lighting uses the same acceptance and replacement addresses as an in-place light.
        Rng.Purpose replacementPurpose = lighting ? Rng.Purpose.LIGHTING_UNLIT : Rng.Purpose.OPTIONAL_REPLACEMENT;
        return replacements[replacements.length == 1 ? 0
                : Rng.indexAtPos(seed, x, y, z, replacementPurpose, replacements.length)];
    }
}
