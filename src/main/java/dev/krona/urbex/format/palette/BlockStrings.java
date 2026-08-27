package dev.krona.urbex.format.palette;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * {@code MODEL.041} to {@code MODEL.043}: a {@code block} string, against the block registry the
 * compiler was handed.
 * <p>
 * <b>The two failures are different rules and this type keeps them apart,</b> which is
 * {@code MODEL.043}'s own {@code > Why}: "An absent id is a pack naming optional cross-mod content, and
 * refusing the world over it would break every pack written for an optional dependency. A bad property
 * expression is a mistake in the file that installing a mod cannot fix." So an unknown id is
 * {@link Outcome#absent} - {@code MODEL.042}, an {@code ACCEPT} - and a property expression that does
 * not apply to a block this game has is {@link Outcome#malformed}, which the caller reports as
 * {@code DIAG.006}.
 * <p>
 * <b>Why not {@code Tools.resolveState}.</b> Version 1's resolver answers a third question this format
 * does not ask - it upgrades a pre-flattening id through {@code BlockStateData} - and it answers the
 * second one by throwing: a property expression the parser refuses becomes a
 * {@code RuntimeException} out of the load, where {@code MODEL.043} wants a named diagnostic. It also
 * logs, and {@code MODEL.042} is an acceptance about which the format has nothing to say. Version 1
 * keeps its resolver unchanged; this is version 2's, and the two differ because the rules do.
 */
public final class BlockStrings {

    private BlockStrings() {
    }

    /**
     * What a block string named: a state, an absent id, or a property expression that does not apply.
     *
     * @param state     the state, present only in the first case
     * @param malformed whether the id resolved and the property expression did not apply to it
     */
    public record Outcome(Optional<BlockState> state, boolean malformed) {

        static Outcome of(BlockState state) {
            return new Outcome(Optional.of(state), false);
        }

        /** {@code MODEL.042}: no installed mod provides this id. The load succeeds. */
        static Outcome absent() {
            return new Outcome(Optional.empty(), false);
        }

        /** {@code MODEL.043}: the block exists and the property expression does not apply to it. */
        static Outcome badProperty() {
            return new Outcome(Optional.empty(), true);
        }
    }

    /** Resolves one block string. */
    public static Outcome resolve(String written, HolderLookup<Block> blocks) {
        int properties = written.indexOf('[');
        Identifier id = Identifier.tryParse(properties < 0 ? written : written.substring(0, properties));
        if (id == null) {
            // Not a legal identifier in any installation, so not anybody's optional cross-mod content
            // either. Treated as absent rather than malformed: MODEL.043 is about a property
            // expression on a block this game has, and this names no block at all.
            return Outcome.absent();
        }
        Optional<Block> block = blocks.get(ResourceKey.create(Registries.BLOCK, id))
                .map(holder -> holder.value());
        if (block.isEmpty()) {
            return Outcome.absent();
        }
        if (properties < 0) {
            return Outcome.of(block.get().defaultBlockState());
        }
        try {
            return Outcome.of(BlockStateParser
                    .parseForBlock(blocks, new StringReader(written), false).blockState());
        } catch (CommandSyntaxException refused) {
            return Outcome.badProperty();
        }
    }
}
