package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.Rng;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * One compiled block, or one compiled weighted list of them, resolved by absolute position.
 *
 * <p>The same two cases {@link CompiledPalette.Entry} has, for a place that needs them without
 * being a palette character: a light source's unlit replacement. It is authored with a palette
 * entry's own vocabulary ({@code unlit} for one block, {@code unlitBlocks} for a weighted list), so
 * it has to draw the way a palette character draws - from the position, never from a sequential
 * stream, so that how many other blocks the chunk resolved first cannot change which replacement
 * appears here.</p>
 */
public sealed interface BlockChoice {

    /** One state, whatever the position. */
    record One(BlockState state) implements BlockChoice { }

    /** A weighted choice, expanded to {@link CompiledPalette#SLOTS} slots at compile time. */
    record Weighted(BlockState[] slots) implements BlockChoice { }

    BlockChoice AIR = new One(Blocks.AIR.defaultBlockState());

    static BlockChoice of(BlockState state) {
        return new One(state);
    }

    /**
     * Compiles a weighted list, or {@link #AIR} when nothing survived resolution.
     * <p>
     * Empty is reachable only through issue #91 - every block the replacement named is absent from
     * this game - and air is what a light with no replacement leaves behind anyway.
     */
    static BlockChoice of(List<Pair<Integer, BlockState>> weighted) {
        if (weighted.isEmpty()) {
            return AIR;
        }
        if (weighted.size() == 1) {
            return new One(weighted.getFirst().getRight());
        }
        int[] weights = new int[weighted.size()];
        for (int i = 0; i < weighted.size(); i++) {
            weights[i] = weighted.get(i).getLeft();
        }
        int[] slots = CompiledPalette.distributeSlots(weights, CompiledPalette.SLOTS);
        BlockState[] expanded = new BlockState[CompiledPalette.SLOTS];
        int index = 0;
        for (int i = 0; i < weighted.size(); i++) {
            for (int slot = 0; slot < slots[i]; slot++) {
                expanded[index++] = weighted.get(i).getRight();
            }
        }
        return new Weighted(expanded);
    }

    /** The state this choice resolves to at {@code (x, y, z)}, drawing nothing from any stream. */
    default BlockState at(long seed, int x, int y, int z, Rng.Purpose purpose) {
        return switch (this) {
            case One one -> one.state();
            case Weighted weighted -> weighted.slots()[
                    Rng.indexAtPos(seed, x, y, z, purpose, weighted.slots().length)];
        };
    }
}
