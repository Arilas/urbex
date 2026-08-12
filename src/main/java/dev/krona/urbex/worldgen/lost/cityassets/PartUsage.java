package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;

/**
 * One way a part is used: the style whose palette it will be drawn against, the building it sits in
 * if any, and whether it is placed as a chunk-sized road piece.
 *
 * <p>A part's characters do not resolve against the part's own palette. They resolve against the
 * merge {@code CityGenerator.computePalette} performs - the chunk's style palette, the building's
 * local palette if there is one, and the part's own on top - so "is this character defined" is a
 * question about a <em>usage</em>, not about a part. The same part reached from two city styles is
 * two questions, and a part that is fine in one may be broken in the other (issue #56).</p>
 *
 * <p>That is why the sequencing note on #56 said this had to wait for #128. It does not any more, and
 * not because the merge moved: it is because every input to the merge is now fixed snapshot data, so
 * this can build the <em>exact</em> palette generation will build rather than approximating one.</p>
 *
 * @param style     the style whose {@code randompalettes} the chunk will draw from, or null where
 *                  the walk reached the part without knowing one - a road part is placed in
 *                  whatever chunk the road runs through, so it is paired with each city style its
 *                  world style can select, and reached once more with no style at all so its
 *                  geometry is checked even by a world style that selects none
 * @param building  the building the part sits in, or null for a road, highway, railway or scattered
 *                  placement - which have no building palette layer
 * @param road      true when the part is wired into a street, highway or railway slot, where the
 *                  generator addresses it as a whole chunk. See
 *                  {@link AssetGraph} for what that costs a part of the wrong size.
 * @param field     where the reference was written, for the message
 * @param owner     the asset that wired this part in, for the message
 */
record PartUsage(@Nullable Style style, @Nullable Building building, boolean road, String field,
                 Identifier owner) {

    /** Identity for de-duplication: the same part under the same style and building is one question. */
    String key(Identifier part) {
        return part + "|" + (style == null ? "-" : style.getId())
                + "|" + (building == null ? "-" : building.getId()) + "|" + road;
    }
}
