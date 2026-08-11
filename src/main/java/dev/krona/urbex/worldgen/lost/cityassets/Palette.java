package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.regassets.PaletteRE;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import dev.krona.urbex.varia.ServerAccess;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A palette of materials as used by building parts
 */
public class Palette {

    private final Identifier name;
    private final Map<Character, PE> palette = new HashMap<>();
    private final Map<BlockState, BlockState> damaged = new HashMap<>();

    /**
     * Builds a fully resolved palette from its {@code extends} chain, root first.
     * <p>
     * A palette is a keyed collection, so the chain merges <em>by character</em> rather than by
     * position: entries land in a {@link LinkedHashMap} keyed by their marker, in chain order, so a
     * descendant that repaints two markers out of thirty overwrites exactly those two and keeps the
     * other twenty-eight. Appending as a list would double-register a character; replacing as a
     * list would silently drop everything the child did not restate. Only the surviving entries are
     * then compiled, so an overridden entry takes its {@code damaged} mapping with it.
     */
    public Palette(List<PaletteRE> chainRootFirst) {
        name = chainRootFirst.get(chainRootFirst.size() - 1).getRegistryName();
        compile(mergeByCharacter(chainRootFirst, name));
    }

    public Palette(String name) {
        this.name = Identifier.fromNamespaceAndPath(Urbex.MODID, name);
    }

    /**
     * Builds the inline {@code palette} block a part or building carries, merged along that asset's
     * own {@code extends} chain.
     * <p>
     * An inline palette is a keyed collection exactly like a registered one, so it merges by
     * character too: a part that extends another and declares an inline palette repainting two
     * markers keeps the rest of its ancestor's. Replacing wholesale here would reproduce, one level
     * down, the very failure {@link #Palette(List)} exists to prevent.
     *
     * @param owner          the part or building the block is written in, for error messages and
     *                       for the synthetic palette name
     * @param chainRootFirst the inline blocks along the owner's chain, root first
     */
    public static Palette inline(Identifier owner, List<PaletteRE> chainRootFirst) {
        for (PaletteRE re : chainRootFirst) {
            // The codec accepts 'extends' wherever a PaletteRE is embedded, but an inline block is
            // not a registry entry, so nothing can resolve it. Rejecting is the honest option:
            // silently dropping a key the codec accepted is how a datapack quietly means something
            // other than what it says.
            if (re.getExtends().isPresent()) {
                throw new IllegalStateException("The inline palette in '" + owner + "' declares "
                        + "extends '" + re.getExtends().get() + "', but an inline palette is not a "
                        + "registry entry and nothing can resolve that. Use 'refpalette' to build "
                        + "on a registered palette, or put 'extends' on '" + owner + "' itself.");
            }
        }
        Palette palette = new Palette("__local__" + owner.getPath());
        palette.compile(mergeByCharacter(chainRootFirst, owner));
        return palette;
    }

    /**
     * Later entries overwrite the characters they declare and leave the rest alone. Only the
     * surviving entries are handed on to {@link #compile}, so an overridden entry takes its
     * {@code damaged} mapping with it rather than leaving it keyed on a block that is no longer
     * placed.
     * <p>
     * {@code palette} is required of the chain rather than of each file, so an entry that only
     * declares {@code extends} inherits its ancestor's markers; a chain where nothing declares one
     * is a load error rather than a palette that silently maps no characters at all.
     */
    private static Collection<PaletteEntry> mergeByCharacter(List<PaletteRE> chainRootFirst,
                                                             Identifier owner) {
        Map<Character, PaletteEntry> merged = new LinkedHashMap<>();
        boolean anyEntries = false;
        for (PaletteRE re : chainRootFirst) {
            if (re.getPaletteEntries() == null) {
                continue;
            }
            anyEntries = true;
            for (PaletteEntry entry : re.getPaletteEntries()) {
                merged.put(entry.getChr().charAt(0), entry);
            }
        }
        Resolved.require(anyEntries ? merged : null, owner, "palette");
        return merged.values();
    }

    public void merge(Palette other) {
        palette.putAll(other.palette);
        damaged.putAll(other.damaged);
    }

    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }

    public Map<BlockState, BlockState> getDamaged() {
        return damaged;
    }

    public Map<Character, PE> getPalette() {
        return palette;
    }

    private void compile(Collection<PaletteEntry> entries) {
        for (PaletteEntry entry : entries) {
            Character c = entry.getChr().charAt(0);
            BlockState dmg = null;
            if (entry.getDamaged() != null) {
                dmg = Tools.stringToState(entry.getDamaged());
            }
            LightPool light = entry.getLight() == null ? null : LightPool.compile(name, c, entry.getLight());
            Info info = new Info(entry.getMob(), entry.getLoot(),
                    entry.getTorch() != null && entry.getTorch(), light, entry.getTag());

            if (entry.getBlock() != null) {
                String block = entry.getBlock();
                BlockState state = Tools.stringToState(block);
                palette.put(c, new PE(state, info));
                if (dmg != null) {
                    damaged.put(state, dmg);
                }
            } else if (entry.getVariant() != null) {
                String variantName = entry.getVariant();
                MinecraftServer server = ServerAccess.getServer();
                ServerLevel level = server.getLevel(Level.OVERWORLD);
                Variant variant = AssetRegistries.VARIANTS.getOrThrow(level, variantName);
                List<Pair<Integer, BlockState>> blocks = variant.getBlocks();
                if (dmg != null) {
                    for (Pair<Integer, BlockState> pair : blocks) {
                        damaged.put(pair.getRight(), dmg);
                    }
                }
                addMappingViaState(c, blocks, info);
            } else if (entry.getFrompalette() != null) {
                String value = entry.getFrompalette();
                palette.put(c, new PE(value, info));
            } else if (entry.getBlocks() != null) {
                List<BlockEntry> entryBlocks = entry.getBlocks();
                List<Pair<Integer, BlockState>> blocks = new ArrayList<>();
                for (BlockEntry ob : entryBlocks) {
                    Integer f = ob.random();
                    String block = ob.block();
                    BlockState state = Tools.stringToState(block);
                    blocks.add(Pair.of(f, state));
                    if (dmg != null) {
                        damaged.put(state, dmg);
                    }
                }
                addMappingViaState(c, blocks, info);
            } else if (light != null) {
                palette.put(c, new PE(light.representative(), info));
            } else {
                throw new RuntimeException("Illegal palette " + name + "!");
            }
        }
    }

    private Palette addMappingViaState(char c, List<Pair<Integer, BlockState>> randomBlocks, Info info) {
        palette.put(c, new PE(randomBlocks.toArray(new Pair[randomBlocks.size()]), info));
        return this;
    }

    public record Info(String mobId, String loot, boolean isTorch, LightPool light, CompoundTag tag) {
        public boolean isSpecial() {
            return mobId != null || loot != null || isTorch || light != null || tag != null;
        }
    }

    public record PE(Object blocks, Info info) {
    }

}
