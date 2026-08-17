package dev.krona.urbex.worldgen.lost.cityassets;

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
public record LightSource(@Nullable LightPool pool, BlockChoice unlit) {

    /** Whether this is placed by the deferred placer rather than written where the marker sits. */
    public boolean isSocket() {
        return pool != null;
    }

    /** The replacement for this marker, addressed at its own position. */
    public BlockState unlitAt(long seed, BlockPos pos) {
        return unlit.at(seed, pos.getX(), pos.getY(), pos.getZ(), Rng.Purpose.LIGHTING_UNLIT);
    }
}
