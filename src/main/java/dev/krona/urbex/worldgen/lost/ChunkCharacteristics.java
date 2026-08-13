package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.worldgen.lost.cityassets.Building;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.cityassets.MultiBuilding;

/**
 * The raw city, style and building choices for one chunk - the <em>candidate</em> half of planning,
 * a pure function of the world seed and the coordinate, settled before anything derives from it.
 *
 * <p>A record, so it is immutable after publication rather than immutable by convention. It used to
 * be a class of public mutable fields assembled field-by-field and then published into
 * {@code caches().characteristics}, and structure avoidance wrote {@code isCity = false} back over
 * it from the thread generating that chunk - after every neighbour had already derived its own
 * layout from the old value. The write is gone; making the type unable to express it is what closes
 * issue #126's first acceptance criterion rather than just satisfying it today.</p>
 *
 * <p>Assembly moved into {@link BuildingInfo#getChunkCharacteristics}, which computes each value into
 * a local and constructs this once at the end. The three {@code Identifier} fields this type used to
 * carry - {@code cityStyleId}, {@code multiBuildingId}, {@code buildingTypeId} - are not here: they
 * were never read or written anywhere.</p>
 *
 * @param multiPos       {@link MultiPos#SINGLE} for a chunk that is not part of a multi-building.
 *                       Null only in the GUI-preview variant, which does not resolve one.
 * @param cityStyle      null in the GUI-preview variant, which does not resolve one.
 * @param multiBuilding  null unless {@code multiPos} says this chunk belongs to a multi-building.
 * @param buildingType   null in the GUI-preview variant, which does not resolve one.
 */
public record ChunkCharacteristics(
        boolean isCity,
        boolean couldHaveBuilding,   // True if this chunk could contain a building
        MultiPos multiPos,           // Equal to SINGLE if a single building
        int cityLevel,               // 0 is lowest city level
        CityStyle cityStyle,
        MultiBuilding multiBuilding,
        Building buildingType) {
}
