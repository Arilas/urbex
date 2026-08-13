package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.ChunkGenContext;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * What grows on, and falls onto, the ground: leaves and rubble.
 *
 * <p>Two weighted tables and the rule for using them. A city style may name a single character for
 * either, in which case that palette entry wins and the table is not consulted at all; otherwise the
 * block is drawn from the table, <em>addressed by the position being written</em> - so how many
 * leaves this chunk has already placed cannot change which one comes next.</p>
 *
 * <p>The tables are constants in every sense that matters - three leaf states and three mossy
 * states, in fixed proportions, depending on nothing outside this file. They are still built per
 * instance rather than statically because {@code Blocks} is only legal to touch after the game has
 * bootstrapped, and an instance built by {@link dev.krona.urbex.worldgen.CityGenerator}'s
 * constructor is built at a moment where that is guaranteed (issue #11).</p>
 */
public final class GroundCover {

    private final BlockState[] leaves = buildLeaves();
    private final BlockState[] rubble = buildRubble();
    private final Set<BlockState> defaultRubble = Set.of(
            Blocks.MOSSY_STONE_BRICKS.defaultBlockState(),
            Blocks.MOSSY_COBBLESTONE.defaultBlockState(),
            Blocks.MOSS_BLOCK.defaultBlockState());

    /**
     * One leaf state for the block the driver is about to write. Addressed by that position, so
     * how many leaves this chunk placed first cannot change which one this is.
     */
    public BlockState leafAt(ChunkGenContext ctx, ChunkPlan info, CompiledPalette compiledPalette) {
        Character leavesBlock = info.getCityStyle().getLeavesBlock();
        if (leavesBlock != null) {
            return ctx.paletteHere(compiledPalette, leavesBlock);
        }
        return leaves[Rng.indexAtPos(ctx.seed, ctx.driver.getX(), ctx.driver.getY(), ctx.driver.getZ(),
                Rng.Purpose.LEAVES, leaves.length)];
    }

    public Set<BlockState> possibleRubble(ChunkPlan info, CompiledPalette compiledPalette) {
        Character rubbleDirtBlock = info.getCityStyle().getRubbleDirtBlock();
        if (rubbleDirtBlock != null) {
            return compiledPalette.getAll(rubbleDirtBlock);
        } else {
            return defaultRubble;
        }
    }

    /**
     * One rubble state for the block the driver is about to write. Addressed by that position, for
     * the same reason as {@link #leafAt}. Nothing is regenerated here: {@code rubble} is
     * a final array built once in the constructor and never empty.
     */
    public BlockState rubbleAt(ChunkGenContext ctx, ChunkPlan info, CompiledPalette compiledPalette) {
        Character rubbleDirtBlock = info.getCityStyle().getRubbleDirtBlock();
        if (rubbleDirtBlock != null) {
            return ctx.paletteHere(compiledPalette, rubbleDirtBlock);
        }
        return rubble[Rng.indexAtPos(ctx.seed, ctx.driver.getX(), ctx.driver.getY(), ctx.driver.getZ(),
                Rng.Purpose.RUBBLE, rubble.length)];
    }

    private static BlockState[] buildLeaves() {
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        BlockState leaves2 = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        BlockState leaves3 = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);

        BlockState[] result = new BlockState[128];
        int i = 0;
        while (i < 20) {
            result[i] = leaves2;
            i++;
        }
        while (i < 40) {
            result[i] = leaves3;
            i++;
        }
        while (i < result.length) {
            result[i] = leaves;
            i++;
        }
        return result;
    }

    private static BlockState[] buildRubble() {
        BlockState mBricks = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        BlockState mCobble = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        BlockState moss = Blocks.MOSS_BLOCK.defaultBlockState();

        BlockState[] result = new BlockState[128];
        int i = 0;
        while (i < 20) {
            result[i] = mBricks;
            i++;
        }
        while (i < 60) {
            result[i] = mCobble;
            i++;
        }
        while (i < result.length) {
            result[i] = moss;
            i++;
        }
        return result;
    }
}
