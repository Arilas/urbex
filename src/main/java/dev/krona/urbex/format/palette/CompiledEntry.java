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
 * <b>This record's {@code equals} is array identity, and nothing may rely on it.</b> A record's
 * generated {@code equals} compares components with {@code Object.equals}, which for {@code Resolved[]}
 * is reference identity - so two entries built from one document are never equal. That costs nothing
 * today because {@code LOAD.023}'s interning is done one level down, on {@link TraitSet}, and one level
 * further by the per-alternative memo that builds each {@link Resolved} once. It would cost something
 * the moment a second construction path built entries and expected equal ones to collapse, so: intern
 * the parts, never the entry.
 *
 * @param slots      the states and traits, one entry per slot; empty for a {@code light_socket}, whose
 *                   block source is its candidates ({@code MODEL.070})
 * @param placements a socket's four candidate lists, each compiled as an entry of its own
 *                   ({@code MODEL.071}), in {@code MODEL.073}'s search order; empty for every other kind
 */
public record CompiledEntry(Resolved[] slots, Map<Kind.Placement, CompiledEntry> placements,
                            TraitSet ownTraits) {

    public CompiledEntry(Resolved[] slots, Map<Kind.Placement, CompiledEntry> placements,
                         TraitSet ownTraits) {
        this.slots = slots.clone();
        this.placements = Kind.Placement.ordered(placements);
        this.ownTraits = ownTraits;
    }

    /** A marker with a block source of its own; its traits live on its slots. */
    public static CompiledEntry of(Resolved[] slots) {
        return new CompiledEntry(slots, Map.of(), TraitSet.EMPTY);
    }

    /**
     * A {@code light_socket}: no slots, one compiled entry per placement list that survived, and the
     * socket node's <b>own</b> traits.
     * <p>
     * <b>The third component exists because a socket has no slots to carry traits on.</b> Every other
     * kind keeps its traits per slot ({@code LOAD.021}), which is where {@code TRAIT.005} leaves them
     * after inheritance. A socket's block source is its candidates ({@code MODEL.070}) so it has an
     * empty slot array, and without this its own {@code traits} object were compiled and then dropped:
     * {@code TRAIT.055}'s socket-level {@code urbex:light.unlit} became air whatever the file wrote, and
     * an {@code urbex:loot}, {@code urbex:spawner} or {@code urbex:block_entity} on a socket vanished
     * with no diagnostic. The candidates inherit these traits too, by {@code TRAIT.005}; what the
     * candidates cannot answer is what applies to the marker when the placer finds nowhere to put a
     * light at all.
     */
    public static CompiledEntry socket(Map<Kind.Placement, CompiledEntry> placements, TraitSet own) {
        return new CompiledEntry(new Resolved[0], placements, own);
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
