package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.regassets.VariantRE;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * A block variant
 */
public class Variant {

    private final Identifier name;
    private final List<Pair<Integer, BlockState>> blocks = new ArrayList<>();

    /**
     * Builds a fully resolved variant from its {@code extends} chain, root first: a declared
     * {@code blocks} replaces the inherited list unless it opts into appending, and an absent one
     * inherits it unchanged. A chain where nothing declares {@code blocks} is a load error.
     */
    public Variant(List<VariantRE> chainRootFirst) {
        name = chainRootFirst.get(chainRootFirst.size() - 1).getRegistryName();
        List<BlockEntry> entries = new ArrayList<>();
        boolean anyBlocks = false;
        for (VariantRE object : chainRootFirst) {
            if (object.getBlocks() != null) {
                Mergeable.apply(entries, object.getBlocks());
                anyBlocks = true;
            }
        }
        Resolved.require(anyBlocks ? entries : null, name, "blocks");
        for (BlockEntry entry : entries) {
            BlockState state = Tools.stringToState(entry.block());
            blocks.add(Pair.of(entry.random(), state));
        }
    }

    /** The fully-qualified id, e.g. {@code "urbex:leaves_green"}. */
    public String getName() {
        return name.toString();
    }

    public Identifier getId() {
        return name;
    }

    public List<Pair<Integer, BlockState>> getBlocks() {
        return blocks;
    }
}