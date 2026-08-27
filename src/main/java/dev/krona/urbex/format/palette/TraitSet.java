package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.palette.traits.Rotatable;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The traits that apply to one slot of a compiled entry ({@code LOAD.021}), interned
 * ({@code LOAD.023}).
 * <p>
 * <b>Per slot, not per marker.</b> {@code LOAD.021}'s {@code > Why} is the reason this type exists at
 * all: "{@code TRAIT.005} lets two choices of one marker carry different traits, so a per-marker trait
 * table cannot represent them. Version 1 kept traits in a separate map keyed by marker, which is both
 * the wrong granularity and a second lookup."
 * <p>
 * <b>Interned by value.</b> {@code LOAD.023}: "Trait sets are interned, so slots sharing a trait set
 * share one object." A weighted marker whose 128 slots all carry the same inherited
 * {@code urbex:damaged} holds one of these and 128 references to it, and the equality that makes that
 * work is ordinary record equality over a {@link Map} - which ignores iteration order, so two sets built
 * in different orders intern together while {@link #traits()} still iterates in a fixed one.
 * <p>
 * <b>Why the ids are sorted.</b> {@code TRAIT.092} forbids a trait depending on the order traits are
 * applied in, so there is no order this has to preserve - and sorting by id gives the resolution report
 * and any listing the same sequence for the same set however the file wrote it.
 */
public record TraitSet(Map<Identifier, CompiledTrait> traits, boolean rotatable) {

    /** A slot with no traits at all - the 84% case, and one object for all of them. */
    public static final TraitSet EMPTY = new TraitSet(Map.of(), true);

    public TraitSet(Map<Identifier, CompiledTrait> traits, boolean rotatable) {
        Map<Identifier, CompiledTrait> sorted = new LinkedHashMap<>();
        List<Identifier> ids = new ArrayList<>(traits.keySet());
        ids.sort(java.util.Comparator.comparing(Identifier::toString));
        ids.forEach(id -> sorted.put(id, traits.get(id)));
        this.traits = Collections.unmodifiableMap(sorted);
        this.rotatable = rotatable;
    }

    /**
     * The set a resolved node's traits compile to, with {@code TRAIT.071}'s default already applied.
     * <p>
     * {@code urbex:rotatable} is read once, here, and kept as a field rather than looked up per block:
     * every block of every rotated part asks the question, and {@code LOAD.041} forbids a hash lookup at
     * a position. The version 1 block tag is not consulted - {@code TRAIT.071} makes the default
     * <em>on</em>, and the tag it replaces "excluded nothing" and had a test existing solely to catch it
     * falling behind the shipped palettes.
     */
    public static TraitSet of(Map<Identifier, CompiledTrait> traits) {
        if (traits.isEmpty()) {
            return EMPTY;
        }
        boolean rotatable = traits.values().stream()
                .filter(trait -> trait.type() == Rotatable.TYPE)
                .map(trait -> ((Rotatable.Value) trait.value()).on())
                .findFirst()
                .orElse(Rotatable.DEFAULT.on());
        return new TraitSet(traits, rotatable);
    }

    /** The trait with this id, if this slot carries it. */
    public Optional<CompiledTrait> get(Identifier id) {
        return Optional.ofNullable(traits.get(id));
    }

    /** Whether this slot carries the trait this type defines. */
    public boolean has(TraitType<?> type) {
        return traits.containsKey(type.id());
    }

    /** Whether this set says anything at all. */
    public boolean isEmpty() {
        return traits.isEmpty();
    }
}
