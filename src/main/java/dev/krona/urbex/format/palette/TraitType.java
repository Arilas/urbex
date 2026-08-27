package dev.krona.urbex.format.palette;

import com.mojang.serialization.Codec;
import dev.krona.urbex.format.Diagnostics;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One registered trait: its id, its schema, and the two declarations {@code TRAIT.090} requires of it.
 * <p>
 * {@code TRAIT.090} · "A registered trait declares its id, its schema, which of its fields are
 * block-valued, and which of its fields are references into which registry." The four are the four
 * groups of methods here, and stating them on the type is what retires the table
 * {@code TRAIT.022}'s {@code > Why} measures: "version 1 recorded which string fields were asset
 * references nowhere, so an addon's importer and its validator each kept a hand-written 48-name table.
 * They drifted, and 35-55% of real references went unchecked in both without either failing." A tool
 * that wants that table now reads {@link #references()} off the registry, and a trait that forgets to
 * declare one has no way to be validated <em>at all</em> rather than being silently half-validated.
 * <p>
 * <b>Why the block-valued fields are declared rather than discovered.</b> {@code TRAIT.009} makes every
 * block-valued field a {@link RawNode}, so a reflective walk over a payload record could find them - and
 * would then be a second implementation of the same claim, in the shape {@code docs/format/README.md}
 * §1 exists to prevent. The declaration is also what stage 3 reads to know which subtrees to resolve,
 * so a trait that omits a field from it gets a satellite with unresolved operands rather than a merely
 * undocumented one, which fails loudly the first time an author writes a {@code $ref} in it.
 *
 * @param <T> this trait's payload
 */
public interface TraitType<T extends TraitValue> {

    /** {@code TRAIT.002}: the trait's id, always namespaced. */
    Identifier id();

    /** {@code TRAIT.001}: the payload's schema, as a codec. */
    Codec<T> codec();

    /**
     * {@code MODEL.004}: the keys this trait's payload defines, and no others.
     * <p>
     * The checkable half of "its schema". A payload holding a key outside this set is refused with
     * {@code DIAG.003} exactly as a node holding one is - "at every level of a version 2 palette file"
     * is the rule's own wording, and a trait payload was the one level strict-key rejection did not
     * reach while traits were opaque.
     * <p>
     * Empty for a trait whose value is a scalar shorthand ({@code TRAIT.001}), which has no keys to
     * name: {@code urbex:rotatable} is written {@code "urbex:rotatable": false}.
     */
    Set<String> keys();

    /**
     * {@code TRAIT.090}: which of this trait's fields hold a {@link RawNode}.
     * <p>
     * Every one of them is a <b>satellite</b> ({@code MODEL.033}, {@code TRAIT.007}): a node the trait
     * points at, placed under the trait's own condition, which inherits nothing and may not be a
     * {@code light_socket}.
     */
    Set<String> blockValuedFields();

    /** {@code TRAIT.090}: which of this trait's fields are references, and into which registry. */
    List<ReferenceTarget> references();

    /**
     * Which of {@code TRAIT.095}'s three phases this trait applies in.
     * <p>
     * Declared rather than looked up in a table here, for the reason {@code TRAIT.090}'s whole design
     * exists: the 48-name table an addon importer kept was a second copy of a fact the format did not
     * state, and it drifted. A mod registering a selection trait of its own has to be refusable beside
     * {@code urbex:light} by {@code TRAIT.064}, and reachable by {@code TRAIT.044}'s check, without
     * either of those knowing its name.
     * <p>
     * {@link Phase#DECORATION} is the default because it is the phase that assumes least: a decorator
     * attaches data to whatever selection produced and changes nothing about which block stands there,
     * so a trait that forgets to declare its phase cannot silently start deciding placement.
     */
    default Phase phase() {
        return Phase.DECORATION;
    }

    /**
     * For a {@link Phase#SELECTION} trait, the satellite field holding what is written when its roll
     * rejects the node's own block. Empty for every other phase.
     * <p>
     * This is what makes {@code TRAIT.044} askable generically. A decoration trait has to know which
     * state it will really be attached to, and by {@code TRAIT.096} that is the replacement whenever
     * the selection rejects - so the field name has to be a value the selection trait declares, not one
     * {@code urbex:block_entity} hardcodes for the two traits this repository happens to ship.
     */
    default Optional<String> replacementField() {
        return Optional.empty();
    }

    /**
     * {@code TRAIT.095}'s three phases, in application order.
     * <p>
     * The order of the constants <em>is</em> the application order, and nothing may reorder them: a
     * decorator applied before selection would attach its data to a block that selection then replaces,
     * which is the defect {@code TRAIT.096} exists to make impossible.
     */
    enum Phase {

        /** Decides which block stands here: {@code urbex:light}, {@code urbex:optional}. */
        SELECTION,

        /** Rewrites the selected state: {@code urbex:rotatable}. */
        TRANSFORMATION,

        /**
         * Attaches data to what selection produced: {@code urbex:loot}, {@code urbex:spawner},
         * {@code urbex:block_entity}.
         */
        DECORATION
    }

    /**
     * The satellite nodes this value holds, by field name, in declaration order.
     * <p>
     * Keyed by field rather than returned as a list because {@code DIAG.005}'s message names the field
     * ("a {@code <field>} replacement cannot be a light_socket") and a diagnostic derived from a value
     * is only true if the value is the one the file wrote.
     */
    Map<String, RawNode> satellites(T value);

    /** The same value with its satellites replaced by their resolved forms ({@code REF.030}). */
    T withSatellites(T value, Map<String, RawNode> satellites);

    /**
     * The ids this value writes in each of its {@link #references()}.
     * <p>
     * A list per target because a future trait may name several assets in one field; every trait
     * defined today writes exactly one.
     */
    Map<ReferenceTarget, List<Identifier>> referenced(T value);

    /**
     * {@code TRAIT.093}: everything this trait refuses that is not a property of its payload alone.
     * <p>
     * Runs at load, against the registries {@code context} carries, and reports through
     * {@code diagnostics} rather than returning - by {@code DIAG.903} a node with two broken traits is
     * two lines, and by {@code LOAD.010} none of it is deferred to generation.
     *
     * @param owner the node this trait is on, already resolved: {@code TRAIT.041} asks what block it is
     *              and {@code TRAIT.052} asks whether any state it resolves to emits light
     */
    void validate(T value, ResolvedNode owner, TraitContext context, PointerResolver.Site site,
                  Diagnostics diagnostics);

    /**
     * One reference field of a trait, and the registry its value names.
     *
     * @param field    the payload key holding the id
     * @param registry the registry that must hold it
     */
    record ReferenceTarget(String field, ResourceKey<? extends Registry<?>> registry) {
    }

    // ---- Bridges, for a caller holding a TraitType<?> and a TraitValue -------------------------

    /**
     * {@link #satellites(TraitValue)} for a caller that has lost the payload type.
     * <p>
     * {@link Trait} holds a {@code TraitType<?>} beside a {@link TraitValue}, because a map of traits by
     * id cannot be generic in seven payload types at once. The cast is safe by construction - the only
     * thing that builds a {@code Trait} is this type's own codec - and it is confined to these three
     * bridges rather than repeated at every call site.
     */
    @SuppressWarnings("unchecked")
    default Map<String, RawNode> satellitesOf(TraitValue value) {
        return satellites((T) value);
    }

    /** {@link #withSatellites} for a caller that has lost the payload type. */
    @SuppressWarnings("unchecked")
    default TraitValue withSatellitesOf(TraitValue value, Map<String, RawNode> satellites) {
        return withSatellites((T) value, satellites);
    }

    /**
     * The same payload with every block-valued field blanked ({@code LOAD.024}).
     * <p>
     * Called once per trait when it is compiled, after its satellites have become
     * {@link CompiledEntry}s. What is left is exactly the fields generation reads and cannot get
     * elsewhere - a pool id, a density name, NBT, a boolean - and no part of the raw tree.
     */
    default TraitValue strippedOf(TraitValue value) {
        if (blockValuedFields().isEmpty()) {
            return value;
        }
        Map<String, RawNode> blank = new java.util.LinkedHashMap<>();
        blockValuedFields().forEach(field -> blank.put(field, RawNode.ABSENT));
        return withSatellitesOf(value, blank);
    }

    /** {@link #referenced} for a caller that has lost the payload type. */
    @SuppressWarnings("unchecked")
    default Map<ReferenceTarget, List<Identifier>> referencedBy(TraitValue value) {
        return referenced((T) value);
    }

    /** {@link #validate} for a caller that has lost the payload type. */
    @SuppressWarnings("unchecked")
    default void validateValue(TraitValue value, ResolvedNode owner, TraitContext context,
                               PointerResolver.Site site, Diagnostics diagnostics) {
        validate((T) value, owner, context, site, diagnostics);
    }
}
