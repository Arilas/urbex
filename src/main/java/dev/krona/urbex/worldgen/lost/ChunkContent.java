package dev.krona.urbex.worldgen.lost;

import javax.annotation.Nullable;

/**
 * What occupies one city chunk. The outcome of {@link ChunkContentResolver}'s precedence order,
 * computed once and consumed by {@link BuildingInfo}.
 *
 * @param hasBuilding  true when a building (single or part of a multi) occupies this chunk
 * @param streetType   how a non-building chunk renders; meaningless when {@code hasBuilding}, and
 *                     null for the non-top-left chunks of a multi-building, which inherit every
 *                     rendering decision from their top-left chunk rather than rolling their own
 * @param buildingName the selected building asset, null when {@code !hasBuilding}
 * @param openLot      true when this is a city chunk with neither a planned road nor a building.
 *                     Hardcoded false until the planned-road system lands, at which point it becomes
 *                     {@code !couldHaveBuilding && effectiveRoad == NONE}
 */
public record ChunkContent(boolean hasBuilding,
                           @Nullable BuildingInfo.StreetType streetType,
                           @Nullable String buildingName,
                           boolean openLot) {
}
