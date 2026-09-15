package dev.krona.urbex.worldgen;

import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.cityassets.ConditionContext;

/** The logical building and authored part whose material occupies a position. */
public record PlacementOrigin(ChunkPlan owner, String part) {
    public PlacementOrigin {
        if (part == null) part = ConditionContext.NO_PART;
    }
}
