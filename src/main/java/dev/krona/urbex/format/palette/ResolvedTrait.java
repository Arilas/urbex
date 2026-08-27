package dev.krona.urbex.format.palette;

import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A trait that applies to a resolved node: its payload, its satellites resolved, and where it came from.
 * <p>
 * The difference from {@link Trait} is the difference stage 3 makes. A {@code Trait}'s satellites are
 * {@link RawNode}s that may still carry {@code $ref}; these are {@link ResolvedNode}s that have a block
 * source. And this carries a {@link Provenance}, which {@code Trait} has no reason to: a trait on a node
 * may have been written there or inherited from a parent by {@code TRAIT.005}, and by {@code LOAD.050}
 * the loader must be able to print "every trait with the node it was inherited from, and the reference
 * chain each came through".
 * <p>
 * <b>Provenance is a component and is deliberately outside {@link #equals}.</b> It is a component
 * because {@code LOAD.050} needs it wherever a trait is, and the report reads the linked palette rather
 * than a side table it would have to be kept in step with. It is outside equality because two spellings
 * of one reference must compile to the same thing: {@code REF.081} makes {@code $mat/Damageable} and
 * {@code urbex:common#/$defs/Damageable} the same pointer, and {@code LOAD.030} makes two markers that
 * reference one definition share one compiled representation. A chain in the equality would make both
 * false - the two spellings would produce palettes that differ only in the text an author happened to
 * type, and {@code LOAD.023}'s interning would keep two objects where the format promises one.
 * {@code ImportsTest.anAliasResolvesToWhatTheSamePointerWrittenInFullResolvesTo} is the test that says
 * so, and it failed the moment provenance was equated.
 *
 * @param type       the registered trait
 * @param value      its payload, with satellites already resolved in place
 * @param satellites the block-valued fields, completed ({@code TRAIT.009}), by field name
 * @param provenance where this trait was written and how it was reached
 */
public record ResolvedTrait(TraitType<?> type, TraitValue value,
                            Map<String, ResolvedNode> satellites, Provenance provenance) {

    public ResolvedTrait(TraitType<?> type, TraitValue value,
                         Map<String, ResolvedNode> satellites, Provenance provenance) {
        this.type = type;
        this.value = value;
        // Insertion-ordered: the field order reaches the resolution report and DIAG.005's message.
        this.satellites = Collections.unmodifiableMap(new LinkedHashMap<>(satellites));
        this.provenance = provenance;
    }

    /** The trait's id. */
    public Identifier id() {
        return type.id();
    }

    /** Equal when the <em>fact</em> is equal; see the class note on why provenance is excluded. */
    @Override
    public boolean equals(Object other) {
        return other instanceof ResolvedTrait that
                && type == that.type
                && value.equals(that.value)
                && satellites.equals(that.satellites);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(type, value, satellites);
    }

    /**
     * Where a trait came from, as {@code LOAD.050} needs to print it.
     *
     * @param declaredAt the location of the node that wrote it - its own node, or the ancestor it was
     *                   inherited from by {@code TRAIT.005}
     * @param via        the reference chain that node was reached through ({@code LOAD.051})
     * @param inherited  whether this node wrote it, or an ancestor did. Read by validation as well as by
     *                   the report: {@code TRAIT.052} asks about "a node none of whose resolved states
     *                   emit light", and the node the rule means is the one that declared the trait, not
     *                   every alternative that inherited it
     */
    public record Provenance(String declaredAt, List<String> via, boolean inherited) {

        public Provenance(String declaredAt, List<String> via, boolean inherited) {
            this.declaredAt = declaredAt;
            this.via = List.copyOf(via);
            this.inherited = inherited;
        }

        /** The same provenance, seen from an alternative that inherited it ({@code TRAIT.005}). */
        public Provenance inheritedForm() {
            return inherited ? this : new Provenance(declaredAt, via, true);
        }
    }
}
