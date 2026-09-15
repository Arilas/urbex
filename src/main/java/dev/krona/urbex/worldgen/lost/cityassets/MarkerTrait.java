package dev.krona.urbex.worldgen.lost.cityassets;

/**
 * The placement traits represented by {@link Palette.Info}, in format phase order.
 *
 * <p>{@code TRAIT.004} requires supported traits to compose. The compiled list records that contract
 * for the adapter and its conformance tests. Generation selects the material first; the chunk driver
 * retains its ownership and {@link dev.krona.urbex.worldgen.MarkerDecorations} applies the surviving
 * decorators at finalization.</p>
 *
 * <h2>The four represented by Palette.Info</h2>
 *
 * <p>{@code 01-traits.md} §4 defines seven traits. The other three travel on
 * {@link CompiledPalette.Placed}: optional selection precedes the part transform, and the marker's
 * damage satellite is retained for the later damage pass. Version 2 in-place light selection also
 * runs before transformation; {@link #LIGHT} then handles accepted lights and deferred sockets.</p>
 *
 * <h2>The order is {@code TRAIT.095}'s phase order, and it is not negotiable</h2>
 *
 * <p>{@code TRAIT.095} fixes it: <b>selection, then transformation, then decoration</b>. {@link #LIGHT}
 * selects - it decides which block stands at the position - and {@link #LOOT}, {@link #SPAWNER} and
 * {@link #BLOCK_ENTITY} decorate, attaching data to the block selection produced. So the light comes
 * <em>first</em>, and the three decorators follow it.</p>
 *
 * <p>{@code TRAIT.096} applies decoration to the selected result. NBT must therefore be valid for an
 * unlit or optional replacement as well as the primary block. Generated loot or spawner fields are
 * composed before explicit block-entity NBT; authored fields win while position and type remain
 * loader-owned.</p>
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
