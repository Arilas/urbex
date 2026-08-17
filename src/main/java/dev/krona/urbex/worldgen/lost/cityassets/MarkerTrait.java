package dev.krona.urbex.worldgen.lost.cityassets;

/**
 * A trait that does something where a marker's block is written, in the order they are applied.
 *
 * <p>This enum exists so that {@code Parts.generatePart} can <em>loop</em> over what a marker carries
 * instead of testing four fields in an {@code else if} chain. The chain was a bug: it applied the first
 * trait it found and silently dropped the rest, so a marker carrying both a light and a mob placed the
 * light and lost the spawner. Nothing in the bundled pack carries two - which is why the defect survived
 * - but the format has always permitted it and version 2 states outright that traits compose
 * ({@code TRAIT.004}: "A node may carry any number of them").</p>
 *
 * <h2>Only the four that write at a position</h2>
 *
 * <p>{@code 01-traits.md} §4 defines seven traits. Three of them are not here because they are not
 * applied where a block is written: {@code urbex:damaged} is read by the damage pass off the palette's
 * state mapping, {@code urbex:optional} rolls a density in the decoration pass, and
 * {@code urbex:rotatable} is asked by the part transform. The four that remain are the four version 1
 * spelled as {@code loot}, {@code mob}, {@code tag} and {@code lightSource}, which is the correspondence
 * {@code 09-migration.md} §2's table states.</p>
 *
 * <h2>The order is §4's, and the order is a known defect in the specification</h2>
 *
 * <p>The constants are in the order {@code 01-traits.md} §4 defines the traits, so that the sequence is
 * read off the specification rather than chosen here. That is enough to be deterministic and it is
 * <em>not</em> enough to be correct, because {@link #LIGHT} conflicts with the other three: it decides
 * which block stands at the position, while {@link #LOOT}, {@link #SPAWNER} and {@link #BLOCK_ENTITY}
 * attach data to the block that was already resolved. A marker carrying a light and a spawner attaches
 * spawner NBT and may then replace the block it was attached to.</p>
 *
 * <p>{@code TRAIT.092} forbids exactly this - "A trait may not depend on the order traits are applied
 * in" - and {@code TRAIT.064} refuses the {@code urbex:light}/{@code urbex:optional} pair for the same
 * reason, stated in its own {@code > Why}: "which replacement is written would depend on which trait was
 * consulted first". No rule refuses the light/spawner, light/loot or light/block_entity pairs, which
 * have the same shape. The {@code else if} chain hid that by applying only one trait ever; a loop cannot
 * hide it. This is reported as a specification defect rather than settled here, because settling it
 * means either a new refusal or a stated order, and both are the specification's to write.</p>
 */
public enum MarkerTrait {

    /** {@code urbex:loot} - {@code 01-traits.md} §4.2, version 1's {@code loot}. */
    LOOT,

    /** {@code urbex:spawner} - §4.3, version 1's {@code mob}. */
    SPAWNER,

    /** {@code urbex:block_entity} - §4.4, version 1's {@code tag}. */
    BLOCK_ENTITY,

    /** {@code urbex:light} - §4.5, version 1's {@code lightSource}. */
    LIGHT
}
