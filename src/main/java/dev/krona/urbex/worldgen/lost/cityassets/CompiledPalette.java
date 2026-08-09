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

    /** Keys the world seed by a palette character. Odd, so distinct characters give distinct keys. */
    private static final long CHARACTER_KEY = 0x9E3779B97F4A7C15L;

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

    /**
     * Distributes {@code slotCount} slots over the entries proportionally to their weights
     * (largest-remainder rounding, remainder ties to the lowest index).
     * <p>
     * Weights used to be absolute slot counts out of 128: a pack whose weights summed below 128
     * crashed at generation time and one summing above 128 was silently truncated (issue #58).
     * Weights that already sum to exactly {@code slotCount} come back verbatim, so packs authored
     * against the old contract generate identically.
     */
    public static int[] distributeSlots(int[] weights, int slotCount) {
        long total = 0;
        for (int w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("Negative palette weight " + w);
            }
            total += w;
        }
        if (total <= 0) {
            throw new IllegalArgumentException("Palette weights must sum to a positive value");
        }
        int[] slots = new int[weights.length];
        long[] remainders = new long[weights.length];
        int assigned = 0;
        for (int i = 0; i < weights.length; i++) {
            long scaled = (long) weights[i] * slotCount;
            slots[i] = (int) (scaled / total);
            remainders[i] = scaled % total;
            assigned += slots[i];
        }
        // Fractional parts sum to exactly the shortfall, so this hands out fewer slots than
        // there are entries and no entry can win twice.
        for (int remaining = slotCount - assigned; remaining > 0; remaining--) {
            int best = 0;
            for (int i = 1; i < weights.length; i++) {
                if (remainders[i] > remainders[best]) {
                    best = i;
                }
            }
            slots[best]++;
            remainders[best] = -1;
        }
        return slots;
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
                        int[] weights = new int[r.length];
                        for (int i = 0; i < r.length; i++) {
                            weights[i] = r[i].getLeft();
                        }
                        int[] slots;
                        try {
                            slots = distributeSlots(weights, 128);
                        } catch (IllegalArgumentException e) {
                            throw new RuntimeException("Invalid palette entry for '" + entry.getKey() + "': " + e.getMessage());
                        }
                        BlockState[] randomBlocks = new BlockState[128];
                        int idx = 0;
                        for (int i = 0; i < r.length; i++) {
                            for (int j = 0; j < slots[i]; j++) {
                                randomBlocks[idx++] = r[i].getRight();
                            }
                        }
                        palette.put(entry.getKey(), randomBlocks);
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
     * world seed, the character, and the position it is being placed at, so how many other
     * characters this chunk resolved first cannot change it - which is exactly what a per-chunk
     * sequential stream got wrong, and what {@link dev.krona.urbex.varia.Rng} exists to prevent.
     * <p>
     * The character is part of the address. Without it every weighted character resolves to the
     * same index at a given block, so a mossy-cobble wall and a cracked-brick floor put their
     * minority variants at identical offsets - one spatial pattern shared by the whole palette
     * instead of one per character. The character is keyed into the seed rather than into
     * {@code x}, {@code y} or {@code z} so that the address stays the block itself: perturbing a
     * coordinate would alias two characters at neighbouring blocks onto one draw, and
     * {@link Rng.Purpose} cannot carry it because palette characters are datapack-defined.
     */
    public BlockState getAt(char c, long seed, int x, int y, int z) {
        Object o = palette.get(c);
        if (o instanceof BlockState state) {
            return state;
        } else if (o == null) {
            return null;
        } else {
            BlockState[] randomBlocks = (BlockState[]) o;
            return randomBlocks[Rng.indexAtPos(characterSeed(seed, c), x, y, z, Rng.Purpose.PALETTE, randomBlocks.length)];
        }
    }

    /**
     * The world seed keyed by a palette character. The multiplier is odd, so distinct characters
     * always give distinct keys and no two characters can share a stream.
     */
    private static long characterSeed(long seed, char c) {
        return seed ^ (c * CHARACTER_KEY);
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
