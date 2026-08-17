package dev.krona.urbex.worldgen.lost.cityassets;

/**
 * A trait that does something where a marker's block is written, <b>in the order they are applied</b>.
 *
 * <p>This enum exists so that {@code Parts.generatePart} can <em>loop</em> over what a marker carries
 * instead of testing four fields in an {@code else if} chain. The chain was a bug: it applied the first
 * trait it found and silently dropped the rest, so a marker carrying both a light and a mob placed the
 * light and lost the spawner. Nothing in the bundled pack carries two - which is why the defect survived
 * - but the format has always permitted it and {@code TRAIT.004} states outright that traits compose.</p>
 *
 * <h2>Only the four that write at a position</h2>
 *
 * <p>{@code 01-traits.md} §4 defines seven traits. Three are not here because they are not applied where
 * a block is written: {@code urbex:damaged} is read by the damage pass off the palette's state mapping,
 * {@code urbex:optional} rolls a density in the decoration pass, and {@code urbex:rotatable} is asked by
 * the part transform.</p>
 *
 * <h2>The order is {@code TRAIT.095}'s phase order, and it is not negotiable</h2>
 *
 * <p>{@code TRAIT.095} fixes it: <b>selection, then transformation, then decoration</b>. {@link #LIGHT}
 * selects - it decides which block stands at the position - and {@link #LOOT}, {@link #SPAWNER} and
 * {@link #BLOCK_ENTITY} decorate, attaching data to the block selection produced. So the light comes
 * <em>first</em>, and the three decorators follow it.</p>
 *
 * <p><b>Reversing them is not a style question, it is {@code TRAIT.096} broken.</b> That rule says a
 * decoration trait applies to the state selection produced. Run a decorator first and it attaches its
 * data to the lit block, the light then replaces that block with its {@code unlit} replacement, and the
 * data is orphaned onto something that is not there - which {@code Parts.forgetBlockEntities} then
 * discards, silently. {@code TRAIT.044} exists precisely because the NBT must be valid for the
 * <em>replacement</em>, and that promise is only keepable if the replacement is chosen before the NBT is
 * queued.</p>
 *
 * <p>This enum was written in {@code 01-traits.md} §4's <em>section</em> order before {@code TRAIT.095}
 * existed, which put {@link #LIGHT} last and inverted the phases. The rule came later in the same task
 * and this was not brought into line with it; the review that caught it is the reason
 * {@link dev.krona.urbex.worldgen.lost.cityassets.Palette.Info#applied()} now has an assertion citing
 * {@code TRAIT.095} rather than only a golden, because no shipped marker carries two traits and so
 * nothing measurable moves when this order is wrong.</p>
 */
public enum MarkerTrait {

    /**
     * {@code urbex:light} - §4.5, version 1's {@code lightSource}. <b>Selection</b>: it decides which
     * block stands here, so it runs before anything that attaches data to that block.
     */
    LIGHT,

    /** {@code urbex:loot} - §4.2, version 1's {@code loot}. Decoration. */
    LOOT,

    /** {@code urbex:spawner} - §4.3, version 1's {@code mob}. Decoration. */
    SPAWNER,

    /** {@code urbex:block_entity} - §4.4, version 1's {@code tag}. Decoration. */
    BLOCK_ENTITY
}
