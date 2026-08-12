package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.regassets.VariantRE;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
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
     *
     * @param blockLookup what the block strings resolve against, from the compiling world's own
     *               registries. Taken rather than fetched: resolution used to reach a static server
     *               reference from wherever it happened to run (issues #60, #128).
     */
    public Variant(HolderLookup<Block> blockLookup, List<VariantRE> chainRootFirst) {
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
            BlockState state = Tools.resolveState(entry.block(), blockLookup, name);
            // Skipped rather than placed as air: this is a weighted list, so dropping the entry
            // hands its share of the draw to the blocks this game does have, while air at full
            // weight would punch holes in whatever the variant paints (issue #91). A chain whose
            // every block is absent resolves to an empty list, which the palette turns into air for
            // that character - a load error here would refuse the world over an uninstalled mod.
            if (state != null) {
                blocks.add(Pair.of(entry.random(), state));
            }
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