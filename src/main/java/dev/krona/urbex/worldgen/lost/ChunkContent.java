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
 * @param openLot      true when this is a city chunk with neither a planned road nor a building:
 *                     {@code isCity && !hasBuilding && effectiveRoad == NONE}. Note the middle term
 *                     is the settled verdict rather than the pass-one candidate - a building the
 *                     lonely veto or a STREET sphere centre took away leaves an open lot behind just
 *                     as a failed building roll does - and that the {@code isCity} term is what keeps
 *                     the whole wilderness from reporting itself as one enormous vacant lot. Every
 *                     open lot renders through the park surface, so {@code openLot} implies
 *                     {@code streetType == PARK}
 * @param parkPart     true when this open lot also receives a weighted park part on that grass.
 *                     This is the whole of what {@code OPEN_LOT_PARK_CHANCE} decides: it furnishes a
 *                     lot, it never chooses the lot's surface and never turns the lot into a road.
 *                     Always false when {@code !openLot}
 */
public record ChunkContent(boolean hasBuilding,
                           @Nullable BuildingInfo.StreetType streetType,
                           @Nullable String buildingName,
                           boolean openLot,
                           boolean parkPart) {
}
