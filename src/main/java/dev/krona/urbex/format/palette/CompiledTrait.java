package dev.krona.urbex.format.palette;

import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A trait as a compiled palette holds it: its payload, and its satellites compiled to block states.
 * <p>
 * The difference from {@link ResolvedTrait} is {@code LOAD.024}: "No compiled palette holds a reference
 * to the parsed JSON, to a definition name, to a pointer, or to any string used only during
 * compilation." A {@code ResolvedTrait}'s satellites are {@link ResolvedNode}s whose blocks are still
 * the strings the file wrote; these are {@link CompiledEntry}s whose slots are {@code BlockState}s, and
 * the payload has had its satellite fields replaced by {@link RawNode#ABSENT} so that nothing of the
 * raw tree survives here. What the payload keeps is what generation reads and could not get anywhere
 * else - a pool id, a density name, a block entity's NBT, a boolean.
 * <p>
 * <b>Satellites are compiled, not deferred, and {@code LOAD.042} is why.</b> "Resolving a marker at a
 * position reads no registry and no tag." A damaged form or an unlit replacement is written at a
 * position, so if its block were still a string the damage pass would have to resolve it there - which
 * is the version 1 behaviour {@code LOAD.002}'s {@code > Why} records, resolving block strings "against
 * whichever registry a static server reference happened to point at".
 * <p>
 * Provenance is outside {@link #equals} for the reason {@link ResolvedTrait} states: two spellings of
 * one reference compile to the same thing ({@code REF.081}, {@code LOAD.030}), and {@code LOAD.023}'s
 * interning would keep two objects where the format promises one.
 *
 * @param type       the registered trait
 * @param value      its payload, with the block-valued fields blanked
 * @param satellites the block-valued fields, compiled, by field name
 * @param provenance where the trait was written and how it was reached ({@code LOAD.050})
 */
public record CompiledTrait(TraitType<?> type, TraitValue value,
                            Map<String, CompiledEntry> satellites,
                            ResolvedTrait.Provenance provenance) {

    public CompiledTrait(TraitType<?> type, TraitValue value,
                         Map<String, CompiledEntry> satellites,
                         ResolvedTrait.Provenance provenance) {
        this.type = type;
        this.value = value;
        this.satellites = Collections.unmodifiableMap(new LinkedHashMap<>(satellites));
        this.provenance = provenance;
    }

    /** The trait's id. */
    public Identifier id() {
        return type.id();
    }

    /** The compiled satellite in this field, or {@code null} when the trait declares none. */
    public CompiledEntry satellite(String field) {
        return satellites.get(field);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CompiledTrait that
                && type == that.type
                && value.equals(that.value)
                && satellites.equals(that.satellites);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value, satellites);
    }
}
