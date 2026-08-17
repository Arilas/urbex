package dev.krona.urbex.format.palette;

import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * What one marker compiles to: the states it may place and, per slot, the traits that apply
 * ({@code LOAD.020}, {@code LOAD.021}).
 * <p>
 * <b>One array of pairs, not two parallel arrays.</b> {@code LOAD.022} is an {@code INVARIANT} -
 * "Resolving a marker to a state and to its traits is one lookup, not two" - and it decides the shape
 * here twice over. Two arrays would be two lookups; one array of states with a method returning a
 * freshly built pair would be one lookup and an allocation, which {@code LOAD.040} forbids
 * ("Resolving a marker at a position allocates nothing"). {@link Resolved} objects are built at compile
 * time, one per slot, so the resolution is a single array read returning an object that already exists.
 * <p>
 * <b>Slot counts differ by kind on purpose.</b> A {@code block} node has one slot; a {@code weighted}
 * one has {@code Apportion.SLOTS}. {@code MODEL.011}'s {@code > Why} is the reason - "84% of markers in
 * the shipped corpus are one block with no metadata. The common case pays nothing for the existence of
 * the uncommon ones" - and the addressing is unaffected, because {@code Rng.paletteSlotAt} is given the
 * array's length.
 *
 * @param slots      the states and traits, one entry per slot; empty for a {@code light_socket}, whose
 *                   block source is its candidates ({@code MODEL.070})
 * @param placements a socket's four candidate lists, each compiled as an entry of its own
 *                   ({@code MODEL.071}), in {@code MODEL.073}'s search order; empty for every other kind
 */
public record CompiledEntry(Resolved[] slots, Map<Kind.Placement, CompiledEntry> placements) {

    public CompiledEntry(Resolved[] slots, Map<Kind.Placement, CompiledEntry> placements) {
        this.slots = slots.clone();
        this.placements = Kind.Placement.ordered(placements);
    }

    /** A marker with a block source of its own. */
    public static CompiledEntry of(Resolved[] slots) {
        return new CompiledEntry(slots, Map.of());
    }

    /** A {@code light_socket}: no slots, and one compiled entry per placement list that survived. */
    public static CompiledEntry socket(Map<Kind.Placement, CompiledEntry> placements) {
        return new CompiledEntry(new Resolved[0], placements);
    }

    /** How many slots this entry addresses - what {@code Rng.paletteSlotAt} is handed. */
    public int slotCount() {
        return slots.length;
    }

    /** The slot at {@code index}, with no bounds arithmetic of its own. */
    public Resolved slot(int index) {
        return slots[index];
    }

    /** Whether this entry defers its placement to chunk assembly ({@code MODEL.073}). */
    public boolean isSocket() {
        return slots.length == 0 && !placements.isEmpty();
    }

    /**
     * One slot: the state it places and the traits that apply to it.
     * <p>
     * Built at compile time and never afterwards. Every field is final and every value it holds is
     * already interned - the {@link BlockState} by the block registry, the {@link TraitSet} by
     * {@code LOAD.023} - so an entry of 128 slots over three distinct alternatives holds three of
     * these, repeated.
     *
     * @param state  the block state, or air where the id names content this installation does not have
     *               ({@code MODEL.042})
     * @param traits every trait that applies here, the node's own and the ones it inherited
     */
    public record Resolved(BlockState state, TraitSet traits) {
    }
}
