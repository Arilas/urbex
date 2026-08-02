package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.varia.Rng;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.Level;

import javax.annotation.Nullable;
import java.util.*;

/**
 * More efficient representation of a palette useful for a single chunk
 */
public class CompiledPalette {

    private final Map<Character, Object> palette = new HashMap<>();
    private final Map<BlockState, BlockState> damagedToBlock = new HashMap<>();
    private final Map<Character, Palette.Info> information = new HashMap<>();

    public CompiledPalette(CompiledPalette other, Palette... palettes) {
        this.palette.putAll(other.palette);
        this.damagedToBlock.putAll(other.damagedToBlock);
        this.information.putAll(other.information);
        addPalettes(palettes);
    }

    public CompiledPalette(Palette... palettes) {
        addPalettes(palettes);
    }

    private int addEntries(BlockState[] randomBlocks, int idx, BlockState c, int cnt) {
        for (int i = 0 ; i < cnt ; i++) {
            if (idx >= randomBlocks.length) {
                return idx;
            }
            randomBlocks[idx++] = c;
        }
        return idx;
    }

    private void addPalettes(Palette[] palettes) {
        // First add the straight palette entries
        for (Palette p : palettes) {
            if (p != null) {
                for (Map.Entry<Character, Palette.PE> entry : p.getPalette().entrySet()) {
                    Palette.PE pe = entry.getValue();
                    if (pe.blocks() instanceof BlockState) {
                        palette.put(entry.getKey(), pe.blocks());
                    } else if (pe.blocks() instanceof Pair[]) {
                        Pair<Integer, BlockState>[] r = (Pair<Integer, BlockState>[]) pe.blocks();
                        BlockState[] randomBlocks = new BlockState[128];
                        int idx = 0;
                        for (Pair<Integer, BlockState> pair : r) {
                            idx = addEntries(randomBlocks, idx, pair.getRight(), pair.getLeft());
                            if (idx >= randomBlocks.length) {
                                break;
                            }
                        }
                        palette.put(entry.getKey(), randomBlocks);
                        if (idx < randomBlocks.length) {
                            throw new RuntimeException("Invalid palette entry for '" + entry.getKey() + "'! Not enough blocks in the random list (factor should go up to 128)");
                        }
                    } else if (!(pe.blocks() instanceof String)) {
                        if (pe.blocks() == null) {
                            throw new RuntimeException("Invalid palette entry for '" + entry.getKey() + "'!");
                        }
                        palette.put(entry.getKey(), pe.blocks());
                    }
                    // Remove information for this character here. If we need it again we will add it below
                    information.remove(entry.getKey());
                }
            }
        }

        boolean dirty = true;
        while (dirty) {
            dirty = false;

            // Now add the palette entries that refer to other palette entries
            for (Palette p : palettes) {
                if (p != null) {
                    for (Map.Entry<Character, Palette.PE> entry : p.getPalette().entrySet()) {
                        Palette.PE pe = entry.getValue();
                        if (pe.blocks() instanceof String blocks) {
                            char c = blocks.charAt(0);
                            if (palette.containsKey(c) && !palette.containsKey(entry.getKey())) {
                                palette.put(entry.getKey(), palette.get(c));
                                information.remove(entry.getKey());
                                dirty = true;
                            }
                        }
                    }
                }
            }
        }

        for (Palette p : palettes) {
            if (p != null) {
                for (Map.Entry<BlockState, BlockState> entry : p.getDamaged().entrySet()) {
                    BlockState c = entry.getKey();
                    damagedToBlock.put(c, entry.getValue());
                }
                for (Map.Entry<Character, Palette.PE> entry : p.getPalette().entrySet()) {
                    Palette.PE pe = entry.getValue();
                    if (pe.info().isSpecial()) {
                        information.put(entry.getKey(), pe.info());
                    }
                }
            }
        }
    }

    public Set<Character> getCharacters() {
        return palette.keySet();
    }

    /**
     * Return true if this palette entry exists
     */
    public boolean isDefined(Character c) {
        return c != null && palette.containsKey(c);
    }

    /**
     * Return true if this is a simple character that can have only one value in the palette
     */
    public boolean isSimple(char c) {
        Object o = palette.get(c);
        return o instanceof Character;
    }

    /**
     * The state for {@code c}, drawing from {@code rand} when the character is a weighted choice.
     * <p>
     * There is deliberately no no-argument {@code get(char)}: it used to draw from a static LCG
     * shared by every chunk and every thread, which is exactly what made generation depend on the
     * order chunks were built in. Callers pass the stream they are responsible for.
     */
    public BlockState get(char c, RandomSource rand) {
        try {
            Object o = palette.get(c);
            if (o instanceof BlockState state) {
                return state;
            } else if (o == null) {
                return null;
            } else {
                BlockState[] randomBlocks = (BlockState[]) o;
                return randomBlocks[rand.nextInt(128)];
            }
        } catch (Exception e) {
            Urbex.LOGGER.log(Level.ERROR, e);
            return null;
        }

    }

    /**
     * The state for {@code c} at a block position, drawing nothing from any stream.
     * <p>
     * This is how generation resolves a weighted character. The pick is a pure function of the
     * world seed and the position it is being placed at, so how many other characters this chunk
     * resolved first cannot change it - which is exactly what a per-chunk sequential stream got
     * wrong, and what {@link dev.krona.urbex.varia.Rng} exists to prevent.
     */
    public BlockState getAt(char c, long seed, int x, int y, int z) {
        Object o = palette.get(c);
        if (o instanceof BlockState state) {
            return state;
        } else if (o == null) {
            return null;
        } else {
            BlockState[] randomBlocks = (BlockState[]) o;
            return randomBlocks[Rng.indexAtPos(seed, x, y, z, Rng.Purpose.PALETTE, randomBlocks.length)];
        }
    }

    /**
     * A representative state for {@code c}, with no randomness at all: the first entry of a
     * weighted list. For the editor and for commands that only need to show or match a character.
     * Generation must use {@link #get(char, RandomSource)} - it is what makes rubble look like
     * rubble rather than one repeated block.
     */
    public BlockState getRepresentative(char c) {
        Object o = palette.get(c);
        if (o instanceof BlockState state) {
            return state;
        } else if (o == null) {
            return null;
        } else {
            return ((BlockState[]) o)[0];
        }
    }

    public Set<BlockState> getAll(char c) {
        try {
            Object o = palette.get(c);
            if (o instanceof BlockState state) {
                return Collections.singleton(state);
            } else if (o == null) {
                return Collections.emptySet();
            } else {
                BlockState[] randomBlocks = (BlockState[]) o;
                return Set.of(randomBlocks);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public BlockState canBeDamagedToIronBars(BlockState b) {
        return damagedToBlock.get(b);
    }

    public Palette.Info getInfo(Character c) { return information.get(c); }

    /**
     * For editor. Return the palette entry given a state
     */
    @Nullable
    public Character find(BlockState state) {
        for (Map.Entry<Character, Object> entry : palette.entrySet()) {
            Object o = entry.getValue();
            if (o instanceof BlockState s) {
                if (s == state) {
                    return entry.getKey();
                }
            } else {
                BlockState[] randomBlocks = (BlockState[]) o;
                for (BlockState randomBlock : randomBlocks) {
                    if (randomBlock == state) {
                        return entry.getKey();
                    }
                }
            }
        }
        return null;
    }

    /**
     * For editor. See if a state matches with a character
     */
    public boolean isMatch(char c, BlockState state) {
        Object o = palette.get(c);
        if (o instanceof BlockState s) {
            return s.getBlock() == state.getBlock();
        } else {
            BlockState[] randomBlocks = (BlockState[]) o;
            for (BlockState randomBlock : randomBlocks) {
                if (randomBlock.getBlock() == state.getBlock()) {
                    return true;
                }
            }
        }
        return false;
    }
}
