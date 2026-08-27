package dev.krona.urbex.format.palette;

/**
 * The decoded payload of one trait - whatever that trait's schema says its value is.
 * <p>
 * A marker interface rather than a sealed hierarchy, and that is {@code TRAIT.090} speaking: traits are
 * the format's extension point, and "a mod may register its own". Sealing this would close the set of
 * payload shapes to the ones this repository ships, which is the opposite of what the rule promises.
 * <p>
 * What every implementation owes is stated on {@link TraitType} rather than here, because it is the
 * <em>type</em> that declares which of its fields hold nodes and which are references
 * ({@code TRAIT.090}); a value only holds them.
 */
public interface TraitValue {
}
