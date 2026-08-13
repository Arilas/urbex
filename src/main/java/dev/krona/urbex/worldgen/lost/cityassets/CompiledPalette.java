package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.Rng;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.*;

/**
 * More efficient representation of a palette useful for a single chunk
 */
public class CompiledPalette {

    /** Keys the world seed by a palette character. Odd, so distinct characters give distinct keys. */
    private static final long CHARACTER_KEY = 0x9E3779B97F4A7C15L;

    /**
     * What a palette character resolves to.
     *
     * <p>Two cases, and they were an {@code Object} recovered by {@code instanceof} at twelve
     * lookups. That is exactly how {@code isSimple} rotted: it tested {@code instanceof Character},
     * which matched neither case, so the bulk-fill fast path behind it was dead for as long as
     * anyone had been reading the code (issue #33). A sealed type cannot be wrong about which cases
     * exist, and the compiler checks each site instead of a reader doing it (issue #53).</p>
     */
    public sealed interface Entry {

        /** One state, whatever the position. */
        record Simple(BlockState state) implements Entry {}

        /**
         * A weighted choice, expanded to exactly {@link #SLOTS} slots at compile time so a lookup is
         * an array index rather than a walk over weights. Which slot is a pure function of the
         * position - see {@link CompiledPalette#getAt}.
         */
        record Weighted(BlockState[] slots) implements Entry {}
    }

    /** How many slots a weighted entry expands to. A palette weight is a count out of this. */
    public static final int SLOTS = 128;

    private final Map<Character, Entry> palette = new HashMap<>();
    /**
     * The same entries indexed by character, for the characters that fit.
     * <p>
     * A palette character is datapack-defined and so is any {@code char}, but every one anybody has
     * ever written is ASCII. This is the lookup {@code generatePart} does once per block of every
     * part it renders, and an array index beats hashing a boxed {@link Character}; the map stays the
     * source of truth for the rest.
     */
    private final Entry[] ascii = new Entry[128];
    private final Map<BlockState, BlockState> damagedToBlock = new HashMap<>();
    private final Map<Character, Palette.Info> information = new HashMap<>();

    public CompiledPalette(CompiledPalette other, Palette... palettes) {
        other.palette.forEach(this::define);
        this.damagedToBlock.putAll(other.damagedToBlock);
        this.information.putAll(other.information);
        addPalettes(palettes);
    }

    /** Records {@code entry} for {@code c} in both the map and the ASCII index. */
    private void define(char c, Entry entry) {
        palette.put(c, entry);
        if (c < ascii.length) {
            ascii[c] = entry;
        }
    }

    /** What {@code c} resolves to, or null. */
    @Nullable
    private Entry entry(char c) {
        return c < ascii.length ? ascii[c] : palette.get(c);
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
    /**
     * How many of {@code slotCount} slots each weighted palette entry gets.
     * <p>
     * <b>A weight is an absolute slot count, not a proportion</b>, and entries fill the array in
     * declaration order until it is full. That is Lost Cities' rule
     * ({@code CompiledPalette.addEntries}), and every pack in existence is authored against it -
     * including the ones Urbex's own assets were ported from. The idiom it creates is a trailing
     * huge weight meaning <em>fill whatever is left</em>:
     * <pre>
     * "#": [ stone 15, mossy_stone_slab 10, stone_stairs 10, mossy_stone 30,
     *        cracked_stone_bricks 30, moss_block 15, iron_bars 3, moss_block 1000 ]
     * </pre>
     * 113 slots of varied rubble, then moss fills the remaining 15 - a ruined stone wall with moss
     * on it. Read as proportions the same list is 91% moss block: a solid green cube.
     * <p>
     * Urbex briefly did read them as proportions, as part of issue #58 (a list summing under 128
     * used to crash, and one summing over was silently truncated). Scaling fixed the crash and
     * inverted every pack that used the idiom, this repository's own bundled variants among them -
     * {@code blackstone} is {@code [32, 32, 1000]}, meant as half accents and half base, and it
     * generated as 94% base. Truncation is not the bug; it is the mechanism the idiom relies on.
     * <p>
     * The under-128 case keeps issue #58's leniency, because Lost Cities threw there
     * ({@code "factor should go up to 128"}) so no pack can be relying on the old behaviour: the
     * weights are scaled up proportionally, largest fractional part first, rather than crashing.
     *
     * @param weights   one per entry, in declaration order; order is significant
     * @param slotCount the array being filled, always 128 in generation
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
        if (total >= slotCount) {
            // Lost Cities' behaviour: take each weight verbatim, in order, and stop when full.
            int assigned = 0;
            for (int i = 0; i < weights.length && assigned < slotCount; i++) {
                slots[i] = (int) Math.min(weights[i], (long) slotCount - assigned);
                assigned += slots[i];
            }
            return slots;
        }
        // Under-full: scale up rather than throw, largest remainder first.
        long[] remainders = new long[weights.length];
        int assigned = 0;
        for (int i = 0; i < weights.length; i++) {
            long scaled = (long) weights[i] * slotCount;
            slots[i] = (int) (scaled / total);
            remainders[i] = scaled % total;
            assigned += slots[i];
        }
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
                    if (pe.blocks() instanceof BlockState state) {
                        define(entry.getKey(), new Entry.Simple(state));
                    } else if (pe.blocks() instanceof Pair[]) {
                        Pair<Integer, BlockState>[] r = (Pair<Integer, BlockState>[]) pe.blocks();
                        int[] weights = new int[r.length];
                        for (int i = 0; i < r.length; i++) {
                            weights[i] = r[i].getLeft();
                        }
                        int[] slots;
                        try {
                            slots = distributeSlots(weights, SLOTS);
                        } catch (IllegalArgumentException e) {
                            throw new RuntimeException("Invalid palette entry for '" + entry.getKey() + "': " + e.getMessage());
                        }
                        BlockState[] randomBlocks = new BlockState[SLOTS];
                        int idx = 0;
                        for (int i = 0; i < r.length; i++) {
                            for (int j = 0; j < slots[i]; j++) {
                                randomBlocks[idx++] = r[i].getRight();
                            }
                        }
                        define(entry.getKey(), new Entry.Weighted(randomBlocks));
                    } else if (pe.blocks() == null) {
                        throw new RuntimeException("Invalid palette entry for '" + entry.getKey() + "'!");
                    } else if (!(pe.blocks() instanceof String)) {
                        // Unreachable: Palette only ever builds a PE from a BlockState, a Pair[] or
                        // a String. It used to store whatever this was and let the cast fail later,
                        // from a worldgen worker mid-part; the sealed Entry has nowhere to put it,
                        // which is the point.
                        throw new RuntimeException("Palette entry for '" + entry.getKey()
                                + "' is a " + pe.blocks().getClass().getName()
                                + ", which is not a block, a weighted list or a character reference");
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
                            Entry referenced = palette.get(c);
                            if (referenced != null && !palette.containsKey(entry.getKey())) {
                                define(entry.getKey(), referenced);
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
        return c != null && entry(c) != null;
    }

    /**
     * Return true if this is a simple character that can have only one value in the palette.
     * <p>
     * The check that rotted, and the reason for the sealed type. It used to read
     * {@code palette.get(c) instanceof Character} against a map holding {@code BlockState} or
     * {@code BlockState[]} - matching neither, so the bulk-fill fast path behind it was dead and
     * nothing said so (issue #33). {@code instanceof Entry.Simple} cannot be wrong about a case
     * that does not exist.
     */
    public boolean isSimple(char c) {
        return entry(c) instanceof Entry.Simple;
    }

    /**
     * The state for {@code c}, drawing from {@code rand} when the character is a weighted choice.
     * <p>
     * There is deliberately no no-argument {@code get(char)}: it used to draw from a static LCG
     * shared by every chunk and every thread, which is exactly what made generation depend on the
     * order chunks were built in. Callers pass the stream they are responsible for.
     * <p>
     * There is no longer a {@code catch} here either. It existed to log and return null when the
     * unchecked cast to {@code BlockState[]} failed - a defence against the untyped map, on the path
     * that resolves every block of every part.
     */
    public BlockState get(char c, RandomSource rand) {
        return switch (entry(c)) {
            case null -> null;
            case Entry.Simple simple -> simple.state();
            case Entry.Weighted weighted -> weighted.slots()[rand.nextInt(SLOTS)];
        };
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
        return switch (entry(c)) {
            case null -> null;
            case Entry.Simple simple -> simple.state();
            case Entry.Weighted weighted -> weighted.slots()[Rng.indexAtPos(
                    characterSeed(seed, c), x, y, z, Rng.Purpose.PALETTE, weighted.slots().length)];
        };
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
        return switch (entry(c)) {
            case null -> null;
            case Entry.Simple simple -> simple.state();
            case Entry.Weighted weighted -> weighted.slots()[0];
        };
    }

    public Set<BlockState> getAll(char c) {
        return switch (entry(c)) {
            case null -> Collections.emptySet();
            case Entry.Simple simple -> Collections.singleton(simple.state());
            // Set.copyOf, not Set.of: a weighted array always repeats states, and the varargs form
            // throws on duplicates (issue #44)
            case Entry.Weighted weighted -> Set.copyOf(Arrays.asList(weighted.slots()));
        };
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
        for (Map.Entry<Character, Entry> mapping : palette.entrySet()) {
            boolean found = switch (mapping.getValue()) {
                case Entry.Simple simple -> simple.state() == state;
                case Entry.Weighted weighted -> Arrays.asList(weighted.slots()).contains(state);
            };
            if (found) {
                return mapping.getKey();
            }
        }
        return null;
    }

    /**
     * For editor. See if a state matches with a character
     */
    public boolean isMatch(char c, BlockState state) {
        return switch (entry(c)) {
            // Was a NullPointerException: the old code cast a missing character to BlockState[].
            case null -> false;
            case Entry.Simple simple -> simple.state().getBlock() == state.getBlock();
            case Entry.Weighted weighted -> {
                for (BlockState slot : weighted.slots()) {
                    if (slot.getBlock() == state.getBlock()) {
                        yield true;
                    }
                }
                yield false;
            }
        };
    }
}
