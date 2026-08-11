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
        Map<Character, PaletteEntry> merged = new LinkedHashMap<>();
        for (PaletteRE re : chainRootFirst) {
            for (PaletteEntry entry : re.getPaletteEntries()) {
                merged.put(entry.getChr().charAt(0), entry);
            }
        }
        compile(merged.values());
    }

    public Palette(String name) {
        this.name = Identifier.fromNamespaceAndPath(Urbex.MODID, name);
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

    /**
     * Compiles one raw palette entry list into this palette. Used for the inline {@code palette}
     * blocks a part or building can carry, which are not registry entries and so have no
     * {@code extends} chain of their own.
     */
    public void parsePaletteArray(PaletteRE paletteRE) {
        compile(paletteRE.getPaletteEntries());
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
