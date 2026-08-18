package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.format.palette.CompiledEntry;
import dev.krona.urbex.format.palette.CompiledTrait;
import dev.krona.urbex.format.palette.traits.Damaged;
import dev.krona.urbex.format.palette.CompiledV2Palette;
import dev.krona.urbex.format.palette.Marker;
import dev.krona.urbex.format.palette.TraitSet;
import net.minecraft.world.level.block.Blocks;
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

    /**
     * What a palette character resolves to.
     *
     * <p>The cases were once an {@code Object} recovered by {@code instanceof} at twelve lookups. That
     * is exactly how {@code isSimple} rotted: it tested {@code instanceof Character}, which matched
     * neither case, so the bulk-fill fast path behind it was dead for as long as anyone had been
     * reading the code (issue #33).</p>
     *
     * <p><b>What a sealed type actually buys, stated precisely, because the imprecise version cost a
     * second recurrence.</b> It makes an <em>exhaustive switch</em> checkable: add a case and every
     * {@code switch} over the type becomes a compile error until it is handled. It does <b>nothing</b>
     * for an {@code instanceof} against one case, which keeps compiling and quietly answers
     * {@code false} for the case that was added. {@code isSimple} was written as such an
     * {@code instanceof}, and when {@link Entry.V2} arrived it silently excluded the whole of version 2
     * from the fast path. Same method, same failure, ten months apart. Prefer a {@code switch} here
     * even where one arm would do.</p>
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

        /**
         * A marker contributed by a version 2 palette, already compiled by all eight stages of
         * {@code LOAD.001}.
         *
         * <p>A third case rather than a translation into the first two, because the first two carry
         * only states and a version 2 marker carries traits <em>per slot</em> ({@code LOAD.021}). The
         * {@code Placed} array the merge derives is where the two meet.</p>
         *
         * <p>{@code entry} is null for a marker this merge <em>derived</em> rather than took from a
         * compiled palette: an {@code alias} whose target is a version 1 marker has states and an
         * {@code Info} but no compiled version 2 entry behind it, because version 1 never built one.
         * Everything generation reads is in {@code slots}; {@code entry} is what lets a further alias
         * overlay traits per slot, and a null one falls back to the version 1 path.</p>
         */
        record V2(@Nullable CompiledEntry entry, Placed[] slots) implements Entry {}
    }

    /**
     * What a marker places at a position: the state, and everything that applies to it.
     *
     * <p><b>One object, built at merge time, because {@code LOAD.022} is an {@code INVARIANT}</b> -
     * "Resolving a marker to a state and to its traits is one lookup, not two". Version 1 asked
     * {@code getAt} and then {@code getInfo}, two lookups into two maps; version 2 was built around
     * {@code CompiledEntry.Resolved}, which is this shape one layer in. Generation indexes an array of
     * these and allocates nothing, which is {@code LOAD.040}.</p>
     *
     * @param state the block state to write
     * @param info  what the marker carries beyond its block, or null when it carries nothing. Null is
     *              load-bearing: {@code Parts} takes a different branch for a marker with no metadata
     */
    public record Placed(BlockState state, @Nullable Palette.Info info) {}

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

    /**
     * The single-lookup view {@link #placedAt} reads, derived once when this palette is finished.
     *
     * <p><b>Derived, not maintained.</b> {@link #palette} and {@link #information} stay the source of
     * truth through the merge — a version 1 marker's state and its {@code Info} are decided by three
     * separate passes in {@link #addPalettes}, and pulling them together earlier would change which
     * palette's {@code Info} survives. {@link #finish} runs after all of them and reads the answer out.
     * So this is one representation computed from another at a fixed point, not a second representation
     * kept in step with the first, which is the failure {@code docs/format/README.md} §1 is about.</p>
     */
    private final Placed[][] placed = new Placed[128][];
    private final Map<Character, Placed[]> placedByChar = new HashMap<>();

    /**
     * Version 2 aliases whose target no palette had yet contributed when they were merged.
     *
     * <p>{@code MODEL.064}: an alias "is answered by the merged palette the part is generated with -
     * including markers contributed by palettes this file never mentions". So it cannot be answered as
     * each palette is added, only once they all have been, which is what {@link #resolvePendingAliases}
     * does. An alias still unanswered after that is {@code MODEL.062}'s refusal, raised where the merge
     * is validated rather than here - {@code LOAD.013}, and generation is not a place to raise it.</p>
     */
    private final Map<Marker, CompiledV2Palette.Pending> pendingAliases = new LinkedHashMap<>();

    public CompiledPalette(CompiledPalette other, Palette... palettes) {
        other.palette.forEach(this::define);
        this.damagedToBlock.putAll(other.damagedToBlock);
        this.information.putAll(other.information);
        addPalettes(palettes);
        finish();
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
        finish();
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
            if (p != null && p.v2() != null) {
                addVersion2(p.v2());
                continue;
            }
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
            // Version 2's damage mapping is recorded here, in the same pass and the same palette order
            // as version 1's, so that a later palette of a draw wins whichever format it is written in.
            // Recording it in the first pass instead - which is where it was - made a version 1 mapping
            // beat a version 2 one for the same state regardless of draw order, which is a precedence
            // the merge does not have anywhere else.
            if (p != null && p.v2() != null) {
                for (Marker marker : p.v2().markers()) {
                    recordDamage(p.v2().entry(marker.codepoint()));
                }
                continue;
            }
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

    /**
     * Every marker a version 2 palette defines, as merged entries.
     *
     * <p>{@code VER.006} is what makes this a case of the same loop rather than a second merge: "a
     * style's {@code randompalettes} may draw a version 1 palette and a version 2 palette into the same
     * merge", and it "operates on compiled palettes, not on {@code extends}, so it needs no
     * correspondence between the two formats". A version 2 marker therefore lands in the same map, keyed
     * the same way, and a later palette of the draw overrides it whichever format that palette is
     * written in.</p>
     *
     * <p>{@code information.remove} for the same reason the version 1 loop does it: this marker's
     * metadata travels in its {@link Placed} slots, and a stale per-marker {@code Info} left by an
     * earlier version 1 palette would be applied to blocks version 2 chose.</p>
     */
    private void addVersion2(CompiledV2Palette compiled) {
        for (Marker marker : compiled.markers()) {
            char c = (char) marker.codepoint();
            CompiledEntry entry = compiled.entry(marker.codepoint());
            define(c, new Entry.V2(entry, slotsOf(entry)));
            information.remove(c);
        }
        // The aliases this palette could not answer are answered by the merge, once every palette of it
        // has contributed - MODEL.064. Recorded now and resolved in the pass below, so that an alias
        // pointing forward at a palette merged later reads the same as one pointing backward.
        compiled.pendingAliases().forEach(pendingAliases::put);
    }

    /**
     * A compiled version 2 entry as merged slots.
     *
     * <p>A {@code light_socket} has no slots of its own ({@code MODEL.070}: "the candidates in its
     * placement lists are its block source"), so it becomes one slot holding the pool's representative -
     * which is exactly what version 1 does with a socket marker, and for the same reason: a character
     * the palette does not map throws from the driver on the first part that uses it.</p>
     */
    private static Placed[] slotsOf(CompiledEntry entry) {
        if (entry.isSocket()) {
            // The socket's own traits, not an empty set: TRAIT.055's socket-level unlit lives there,
            // and so does any decoration trait written on the socket node.
            Palette.Info info = V2Traits.infoOf(entry.ownTraits(), entry);
            LightSource source = info == null ? null : info.lightSource();
            BlockState representative = source == null || source.pool() == null
                    ? Blocks.AIR.defaultBlockState() : source.pool().representative();
            return new Placed[]{new Placed(representative, info)};
        }
        Placed[] slots = new Placed[entry.slotCount()];
        // One Placed per distinct compiled slot, so LOAD.023's interning reaches this view too: a
        // weighted marker whose 128 slots share three alternatives holds three of these, repeated.
        Map<CompiledEntry.Resolved, Placed> perAlternative = new HashMap<>();
        for (int slot = 0; slot < slots.length; slot++) {
            CompiledEntry.Resolved resolved = entry.slot(slot);
            slots[slot] = perAlternative.computeIfAbsent(resolved,
                    from -> new Placed(from.state(), V2Traits.infoOf(from.traits(), null)));
        }
        return slots;
    }

    /**
     * A version 2 marker's {@code urbex:damaged} satellites, in the state-keyed map the damage pass has.
     *
     * <p><b>This is {@code TRAIT.011} not being reached, and it is deliberate rather than overlooked.</b>
     * That rule keys the mapping by the marker carrying the trait, and the compiled palette does exactly
     * that — a marker's {@code urbex:damaged} is a satellite of its own entry, per slot. The damage pass
     * cannot consume it: {@code DamageArea} and {@code Decorations} read blocks back out of the chunk,
     * where the marker is gone and is not recoverable, which is why version 1 keyed its map by state in
     * the first place. So a version 2 palette gets version 1's fidelity here — two markers on one block
     * collapse to the last one compiled — and it gets that rather than nothing at all, which is what it
     * had before this method existed. The rule carries a {@code [NOT-YET-REACHED]} marker naming
     * issue #216, which is the per-position marker record that would fix it.</p>
     *
     * <p><b>An {@code into} that resolved to air records nothing, which is {@code TRAIT.012}.</b> That
     * rule says an {@code into} "naming a block this game does not have leaves the marker undamaged, and
     * the load succeeds" — and by {@code MODEL.042} an absent id resolves to air, so without this the
     * mapping said the marker damages into <em>nothing</em>. The damage pass would then delete the
     * block, which is the claim version 1 refused to make in so many words: {@code Palette.compile}
     * skips an unresolvable {@code damaged} because "air would say 'damaging this block deletes it',
     * which is a claim the author did not make (issue #91)". Zombie Apocalypse Essentials has seven
     * markers naming {@code immersive_weathering:exposed_iron_bars}, and on a vanilla install every one
     * of them was deleting the block it damaged.</p>
     *
     * <p><b>What this cannot distinguish, said plainly.</b> A file writing {@code "into":
     * "minecraft:air"} deliberately is the same compiled state as an absent id, because MODEL.042 has
     * already turned one into the other, and telling them apart needs the block string — which lives in
     * the resolved node and not in the compiled slot. Version 1 <em>could</em> tell them apart and
     * honoured the deliberate one. No file in the three measured packs writes it: the eight distinct
     * {@code damaged} values across 335 uses are all real blocks or absent mod blocks, none is air. If
     * one is ever wanted, the discriminator belongs where the string still exists, not here.</p>
     */
    private void recordDamage(CompiledEntry entry) {
        for (int slot = 0; slot < entry.slotCount(); slot++) {
            CompiledEntry.Resolved resolved = entry.slot(slot);
            CompiledTrait damaged = resolved.traits().traits().get(Damaged.TYPE.id());
            if (damaged == null) {
                continue;
            }
            CompiledEntry into = damaged.satellite(Damaged.INTO);
            if (into != null && into.slotCount() > 0 && !into.slot(0).state().isAir()) {
                damagedToBlock.put(resolved.state(), into.slot(0).state());
            }
        }
        entry.placements().values().forEach(this::recordDamage);
    }

    /**
     * Derives the single-lookup view, after every pass that decides what a marker is has finished.
     *
     * <p>Runs once, at the end of construction, and reads {@link #palette} and {@link #information}
     * rather than being kept in step with them. A version 1 marker's {@code Info} is decided by the
     * third pass of {@link #addPalettes} and its state by the first, so anything that pulled them
     * together earlier would change which palette's {@code Info} survives - a behaviour version 1 has
     * had for its whole life and which this change is not the place to alter.</p>
     */
    private void finish() {
        resolveAliases();
        palette.forEach((c, entry) -> {
            Placed[] slots = switch (entry) {
                case Entry.Simple simple -> new Placed[]{new Placed(simple.state(), information.get(c))};
                case Entry.Weighted weighted -> {
                    Palette.Info info = information.get(c);
                    Placed[] built = new Placed[weighted.slots().length];
                    Map<BlockState, Placed> perState = new HashMap<>();
                    for (int slot = 0; slot < built.length; slot++) {
                        BlockState state = weighted.slots()[slot];
                        built[slot] = perState.computeIfAbsent(state, s -> new Placed(s, info));
                    }
                    yield built;
                }
                case Entry.V2 v2 -> v2.slots();
            };
            if (c < placed.length) {
                placed[c] = slots;
            } else {
                placedByChar.put(c, slots);
            }
        });
    }

    /**
     * Answers every version 2 {@code alias} against the finished merge ({@code MODEL.060},
     * {@code MODEL.064}).
     *
     * <p>This is the cross-version half, and it is the reason composition happens here rather than in
     * {@link Style}. Under {@code VER.006} a draw may hold both formats, so a version 2 {@code alias}
     * may name a marker only a version 1 palette defines - and version 1's own {@code frompalette} loop
     * below may name one only a version 2 palette defines. Neither is decidable by either palette
     * alone, which is exactly what {@code MODEL.064} says: the merge answers it.</p>
     *
     * <p>Iterated to a fixed point, like the {@code frompalette} loop it runs beside, so an alias whose
     * target is itself an alias resolves whichever order the two were merged in. An alias still
     * unanswered when this settles is left undefined and <b>not</b> reported here: {@code MODEL.062} is
     * a load diagnostic decided where a style's groups are checked, and {@code LOAD.011} forbids a
     * compiled palette raising anything during generation.</p>
     */
    private void resolveAliases() {
        boolean dirty = true;
        while (dirty) {
            dirty = false;
            for (Map.Entry<Marker, CompiledV2Palette.Pending> pending : pendingAliases.entrySet()) {
                char marker = (char) pending.getKey().codepoint();
                if (palette.containsKey(marker)) {
                    continue;
                }
                Entry target = entry((char) pending.getValue().target().codepoint());
                if (target == null) {
                    continue;
                }
                define(marker, aliasOf(target, pending.getValue().own()));
                information.remove(marker);
                dirty = true;
            }
        }
    }

    /**
     * The target's entry with the alias's own traits over it ({@code MODEL.063}, {@code TRAIT.006}).
     *
     * <p>A version 2 target keeps its per-slot traits and has the alias's applied over each of them, by
     * the same {@code CompiledV2Palette.overlay} an in-palette alias used - one implementation, so an
     * alias answered by another palette cannot come out differently from one answered by its own.</p>
     *
     * <p>A <b>version 1</b> target has no trait set to overlay onto. Its states are taken as they are
     * and the alias's own traits become the marker's {@code Info}, which is the closest thing version 1
     * has to "the target's traits, then its own" - the target has none in version 2's sense, so there is
     * nothing of the target's to keep. Cross-format inheritance is not defined anywhere and this does
     * not invent it: it applies what the alias itself declared and nothing else.</p>
     */
    private Entry aliasOf(Entry target, TraitSet own) {
        if (target instanceof Entry.V2 v2 && v2.entry() != null) {
            CompiledEntry overlaid = CompiledV2Palette.overlay(v2.entry(), own, aliasTraitSets);
            return new Entry.V2(overlaid, slotsOf(overlaid));
        }
        Palette.Info info = V2Traits.infoOf(own, null);
        Placed[] slots = switch (target) {
            case Entry.Simple simple -> new Placed[]{new Placed(simple.state(), info)};
            case Entry.Weighted weighted -> {
                Placed[] built = new Placed[weighted.slots().length];
                Map<BlockState, Placed> perState = new HashMap<>();
                for (int slot = 0; slot < built.length; slot++) {
                    BlockState state = weighted.slots()[slot];
                    built[slot] = perState.computeIfAbsent(state, s -> new Placed(s, info));
                }
                yield built;
            }
            case Entry.V2 derived -> {
                // A target that is itself a derived alias of a version 1 marker: its states are version
                // 1's and there is still no trait set to overlay onto, so this alias's own traits
                // replace what the intermediate alias contributed. TRAIT.006 read down the chain.
                Placed[] built = new Placed[derived.slots().length];
                for (int slot = 0; slot < built.length; slot++) {
                    built[slot] = new Placed(derived.slots()[slot].state(), info);
                }
                yield built;
            }
        };
        return new Entry.V2(null, slots);
    }

    /** {@code LOAD.023}'s interning, extended across the merge rather than per palette. */
    private final Map<TraitSet, TraitSet> aliasTraitSets = new HashMap<>();

    /**
     * Every version 2 {@code alias} this merge carried in, as marker to target.
     *
     * <p>What {@code MODEL.062} is asked about. An alias whose target its own palette defined was
     * answered at compile time and is not here; these are the ones {@code MODEL.064} says only the merge
     * can answer, which is exactly the set a load-time check has to look at.</p>
     */
    public Map<Character, Character> aliasTargets() {
        Map<Character, Character> targets = new LinkedHashMap<>();
        pendingAliases.forEach((marker, pending) ->
                targets.put((char) marker.codepoint(), (char) pending.target().codepoint()));
        return targets;
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
     * <b>The check that rotted twice, ten months apart, in this method.</b> First it read
     * {@code palette.get(c) instanceof Character} against a map holding {@code BlockState} or
     * {@code BlockState[]} - matching neither, so the bulk-fill fast path behind it was dead and
     * nothing said so (issue #33). Then it read {@code instanceof Entry.Simple}, and when
     * {@link Entry.V2} was added every version 2 marker answered {@code false} - including a plain
     * one-block marker - so {@link #setBlocksFromPalette}'s {@code driver.setBlockRange} fast path was
     * dead for the whole of version 2.
     * <p>
     * <b>The sealed type did not prevent the second one and was never going to.</b> It makes an
     * exhaustive {@code switch} checkable: add a case and every switch over the type stops compiling
     * until it is handled. It does nothing for an {@code instanceof} against one case, which keeps
     * compiling and quietly answers {@code false} for the case that was added. That is why this is a
     * {@code switch} even though a single arm would express the same thing - the next case added to
     * {@link Entry} has to break this method rather than be silently excluded by it.
     */
    public boolean isSimple(char c) {
        return switch (entry(c)) {
            case null -> false;
            case Entry.Simple ignored -> true;
            case Entry.Weighted ignored -> false;
            // One slot is one state whatever the position, which is the whole of what this asks.
            // MODEL.011's > Why is why it is common: "84% of markers in the shipped corpus are one
            // block with no metadata", and a version 2 block node compiles to exactly one slot.
            case Entry.V2 v2 -> v2.slots().length == 1;
        };
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
            // A version 2 marker addresses however many slots it has - MODEL.011's "84% of markers are
            // one block with no metadata" is why that is not always 128, and Rng.paletteSlotAt is given
            // the length for the same reason.
            case Entry.V2 v2 -> v2.slots()[rand.nextInt(v2.slots().length)].state();
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
     * The character is part of the address, and the addressing itself lives in
     * {@link Rng#paletteSlotAt} rather than here - unchanged, and moved so that the version 2 format
     * addresses its weighted markers with the same function instead of a second copy of the same
     * expression. Why the character is keyed into the seed rather than into a coordinate, and what a
     * palette looks like without it, is recorded there.
     */
    public BlockState getAt(char c, long seed, int x, int y, int z) {
        return switch (entry(c)) {
            case null -> null;
            case Entry.Simple simple -> simple.state();
            case Entry.Weighted weighted -> weighted.slots()[
                    Rng.paletteSlotAt(seed, c, x, y, z, weighted.slots().length)];
            case Entry.V2 v2 -> placedIn(v2.slots(), c, seed, x, y, z).state();
        };
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
            case Entry.V2 v2 -> v2.slots()[0].state();
        };
    }

    public Set<BlockState> getAll(char c) {
        return switch (entry(c)) {
            case null -> Collections.emptySet();
            case Entry.Simple simple -> Collections.singleton(simple.state());
            // Set.copyOf, not Set.of: a weighted array always repeats states, and the varargs form
            // throws on duplicates (issue #44)
            case Entry.Weighted weighted -> Set.copyOf(Arrays.asList(weighted.slots()));
            case Entry.V2 v2 -> {
                Set<BlockState> states = new HashSet<>();
                for (Placed placed : v2.slots()) {
                    states.add(placed.state());
                }
                yield Set.copyOf(states);
            }
        };
    }

    public BlockState canBeDamagedToIronBars(BlockState b) {
        return damagedToBlock.get(b);
    }

    public Palette.Info getInfo(Character c) { return information.get(c); }

    /**
     * What {@code c} places at this position, and everything that applies to it, in <b>one</b> lookup.
     *
     * <p>{@code LOAD.022} is an {@code INVARIANT} — "Resolving a marker to a state and to its traits is
     * one lookup, not two" — and this is the method that keeps it. Version 1's generation asked
     * {@link #getAt} and then {@link #getInfo}, two lookups into two maps; both formats now answer here,
     * and {@code Parts} has one call path rather than one per version.</p>
     *
     * <p>Allocation-free ({@code LOAD.040}): every {@link Placed} was built while this palette was
     * merged, so this is an array index into an array of references. For a version 1 marker the
     * {@code Info} is the same one {@link #getInfo} returns, repeated across the slots — which is the
     * per-marker shape widened to the per-slot one, not narrowed the other way.</p>
     *
     * @return null when this palette does not define {@code c}, exactly as {@link #getAt} does
     */
    @Nullable
    public Placed placedAt(char c, long seed, int x, int y, int z) {
        Placed[] slots = c < placed.length ? placed[c] : placedByChar.get(c);
        return slots == null ? null : placedIn(slots, c, seed, x, y, z);
    }

    /**
     * The slot {@code Rng.paletteSlotAt} addresses, or the only one.
     *
     * <p>The one-slot short circuit is not an optimisation, it is what keeps a version 1 simple marker
     * bit-identical: {@link #getAt} returns a simple marker's state without consulting the addressing at
     * all, so asking for slot zero of one has to be the same answer and not merely a very likely one.</p>
     */
    private static Placed placedIn(Placed[] slots, char c, long seed, int x, int y, int z) {
        return slots.length == 1 ? slots[0]
                : slots[Rng.paletteSlotAt(seed, c, x, y, z, slots.length)];
    }

    /**
     * For editor. Return the palette entry given a state
     */
    @Nullable
    public Character find(BlockState state) {
        for (Map.Entry<Character, Entry> mapping : palette.entrySet()) {
            boolean found = switch (mapping.getValue()) {
                case Entry.Simple simple -> simple.state() == state;
                case Entry.Weighted weighted -> Arrays.asList(weighted.slots()).contains(state);
                case Entry.V2 v2 -> {
                    boolean present = false;
                    for (Placed placed : v2.slots()) {
                        present |= placed.state() == state;
                    }
                    yield present;
                }
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
            case Entry.V2 v2 -> {
                for (Placed placed : v2.slots()) {
                    if (placed.state().getBlock() == state.getBlock()) {
                        yield true;
                    }
                }
                yield false;
            }
        };
    }
}
