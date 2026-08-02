package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.regassets.VariantRE;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
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

    public Variant(VariantRE object) {
        name = object.getRegistryName();
        for (BlockEntry entry : object.getBlocks()) {
            BlockState state = Tools.stringToState(entry.block());
            blocks.add(Pair.of(entry.random(), state));
        }
    }

    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }

    public List<Pair<Integer, BlockState>> getBlocks() {
        return blocks;
    }
}