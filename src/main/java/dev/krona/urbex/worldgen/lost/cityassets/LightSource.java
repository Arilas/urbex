package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.Rng;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * A compiled {@code lightSource}: what to place when this marker is lit, and what to place when it
 * is not.
 *
 * <p>A socket carries a {@link LightPool} and is placed after the chunk is assembled, so it can see
 * its support. An in-place source carries none: the palette entry's own block is the lit block, and
 * this type only decides whether that block or its replacement is written.</p>
 *
 * <p>The replacement is never absent - an author who names none gets air, which is what a rejected
 * light marker has always left behind. What changed is that <em>something</em> is always written,
 * so a pack can say "this lantern hangs from a chain" and keep the chain when the lantern is off.</p>
 */
public record LightSource(@Nullable LightPool pool, BlockChoice unlit,
                          @Nullable PlacementChoice unlitPlacements) {

    public LightSource(@Nullable LightPool pool, BlockChoice unlit) {
        this(pool, unlit, null);
    }

    /** Whether this is placed by the deferred placer rather than written where the marker sits. */
    public boolean isSocket() {
        return pool != null;
    }

    /** The replacement for this marker, addressed at its own position. */
    public BlockState unlitAt(long seed, BlockPos pos) {
        return unlitPlacements != null ? unlitPlacements.at(seed, pos).state()
                : unlit.at(seed, pos.getX(), pos.getY(), pos.getZ(), Rng.Purpose.LIGHTING_UNLIT);
    }

    /**
     * What {@code candidate} leaves behind when this socket is off: the replacement the candidate
     * named, or this source's own when it named none.
     */
    public BlockState unlitFor(LightPool.Candidate candidate, long seed, BlockPos pos) {
        if (candidate.unlitPlacements() != null) {
            return candidate.unlitPlacements().at(seed, pos).state();
        }
        return candidate.unlit() != null ? candidate.unlit() : unlitAt(seed, pos);
    }

    @Nullable
    public CompiledPalette.Placed unlitPlacementAt(Preset preset, long seed, BlockPos pos) {
        return unlitPlacements == null ? null : unlitPlacements.at(preset, seed, pos);
    }

    @Nullable
    public CompiledPalette.Placed unlitPlacementFor(LightPool.Candidate candidate, Preset preset,
                                                   long seed, BlockPos pos) {
        if (candidate.unlitPlacements() != null) {
            return candidate.unlitPlacements().at(preset, seed, pos);
        }
        // A legacy candidate's explicit replacement must not acquire the source's metadata.
        return candidate.unlit() != null ? null : unlitPlacementAt(preset, seed, pos);
    }
}
