package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.palette.traits.BlockEntityNbt;
import dev.krona.urbex.format.palette.traits.Damaged;
import dev.krona.urbex.format.palette.traits.Light;
import dev.krona.urbex.format.palette.traits.Loot;
import dev.krona.urbex.format.palette.traits.OptionalTrait;
import dev.krona.urbex.format.palette.traits.Rotatable;
import dev.krona.urbex.format.palette.traits.Spawner;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every trait this Urbex knows, by id ({@code TRAIT.003}, {@code TRAIT.090}).
 * <p>
 * <b>Immutable, and deliberately not a mutable registry.</b> {@code TRAIT.090} makes traits the
 * format's extension point and {@code TRAIT.091} speaks of "a namespace no loaded mod registers", so a
 * registration API is owed - but {@code LOAD.031} forbids compilation retaining state in static fields,
 * and a static mutable map written by mod initialisation and read by every load is exactly the shape
 * that rule's {@code > Why} records as having gone wrong before ("unsynchronised while being written
 * from a decoding worker pool, and nothing emptied them"). Until there is an addon API to hang
 * registration on, this holds the seven traits {@code 01-traits.md} §4 defines and nothing can add an
 * eighth at runtime. {@code TRAIT.091}'s namespace question is answered from {@link #namespaces()},
 * which is that set's namespaces - today, {@code urbex} alone.
 * <p>
 * Ordered by the section order of {@code 01-traits.md} §4 rather than by hash: the id set reaches
 * {@code DIAG.020}'s message through nothing today, but {@link #ids()} is what a schema generator and a
 * {@code /urbex traits} listing would print, and a list that shuffles between runs cannot be diffed.
 */
public final class Traits {

    private static final Map<Identifier, TraitType<?>> BY_ID = index(List.of(
            Damaged.TYPE, Loot.TYPE, Spawner.TYPE, BlockEntityNbt.TYPE,
            Light.TYPE, OptionalTrait.TYPE, Rotatable.TYPE));

    private static final Set<String> NAMESPACES = namespacesOf(BY_ID.keySet());

    private Traits() {
    }

    /** The trait with this id, or empty - which is {@code TRAIT.003}'s refusal, at the caller. */
    public static Optional<TraitType<?>> of(Identifier id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** Every registered trait id, in the order {@code 01-traits.md} §4 defines them. */
    public static Set<Identifier> ids() {
        return BY_ID.keySet();
    }

    /** Every registered trait, in the same order. */
    public static List<TraitType<?>> all() {
        return List.copyOf(BY_ID.values());
    }

    /**
     * {@code TRAIT.091}: whether anything loaded registers traits in this namespace.
     * <p>
     * This is what fills {@code DIAG.020}'s optional clause. The distinction it draws is the useful one:
     * {@code urbex:damage} is a misspelling of a trait this Urbex has, and {@code create:crushed} is a
     * trait from a mod that is not installed. The two have different remedies and the row has two
     * sentences for them - "Check the id, or the mod that provides it".
     */
    public static boolean registersNamespace(String namespace) {
        return NAMESPACES.contains(namespace);
    }

    /** The namespaces {@link #registersNamespace} answers about. */
    public static Set<String> namespaces() {
        return NAMESPACES;
    }

    private static Map<Identifier, TraitType<?>> index(List<TraitType<?>> types) {
        Map<Identifier, TraitType<?>> byId = new LinkedHashMap<>();
        for (TraitType<?> type : types) {
            if (byId.put(type.id(), type) != null) {
                throw new IllegalStateException("two traits registered as " + type.id());
            }
        }
        // Insertion-ordered rather than Map.copyOf: this set is iterated, and Map.copyOf's order is
        // perturbed by a per-JVM salt. The same reason Kind.Placement.ordered exists.
        return Collections.unmodifiableMap(byId);
    }

    private static Set<String> namespacesOf(Set<Identifier> ids) {
        Set<String> namespaces = new LinkedHashSet<>();
        ids.forEach(id -> namespaces.add(id.getNamespace()));
        return Collections.unmodifiableSet(namespaces);
    }
}
