package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;

/**
 * What this chunk joins on to.
 *
 * <p>Corridors and rails passing through, roads extending outwards, and - the bulk of it - which
 * floors of a building line up with the floors of the building next door, so a doorway cut in one
 * opens onto something rather than onto air.</p>
 *
 * <p>Every answer is about a pair of chunks, which is why it is worth its own type: these read the
 * neighbour's plan and its floor list, and reading them off {@link ChunkPlan} made the class both
 * the thing being asked about and the thing doing the asking. Nothing here is memoised - the
 * answers are cheap and the inputs are already fixed (issue #11).</p>
 */
final class ConnectionGraph {

    private final ChunkPlan plan;

    ConnectionGraph(ChunkPlan plan) {
        this.plan = plan;
    }

    boolean xCorridor() {
        if (!plan.xRailCorridor) {
            return false;
        }
        ChunkPlan i = plan.getXmin();
        while (i.canRailGoThrough() && i.xRailCorridor) {
            i = i.getXmin();
        }
        if ((!i.hasBuilding) || i.cellars == 0) {
            return false;
        }
        i = plan.getXmax();
        while (i.canRailGoThrough() && i.xRailCorridor) {
            i = i.getXmax();
        }
        return !((!i.hasBuilding) || i.cellars == 0);
    }

    boolean zCorridor() {
        if (!plan.zRailCorridor) {
            return false;
        }
        ChunkPlan i = plan.getZmin();
        while (i.canRailGoThrough() && i.zRailCorridor) {
            i = i.getZmin();
        }
        if ((!i.hasBuilding) || i.cellars == 0) {
            return false;
        }
        i = plan.getZmax();
        while (i.canRailGoThrough() && i.zRailCorridor) {
            i = i.getZmax();
        }
        return !((!i.hasBuilding) || i.cellars == 0);
    }

    // Return true if it is possible for a rail section to go through here
    boolean canRailGoThrough() {
        if (!plan.isCity) {
            // There is no city here so no passing possible
            return false;
        }
        if (!plan.hasBuilding) {
            // There is no building here but we have a city so we can pass
            return true;
        }
        // Otherwise we can only pass if this building has no floors below ground
        return plan.cellars == 0;
    }

    // Return true if it is possible for a water corridor to go through here
    boolean canWaterCorridorGoThrough() {
        if (!plan.isCity) {
            // There is no city here so no passing possible
            return false;
        }
        if (!plan.hasBuilding) {
            // There is no building here but we have a city so we can pass
            return true;
        }
        // Otherwise we can only pass if this building has at most one floor below ground
        return plan.cellars <= 1;
    }

    // Return true if the road from a neighbouring chunk can extend into this chunk
    boolean roadExtendsOut() {
        boolean b = plan.isCity && !plan.hasBuilding;
        if (b) {
            return !plan.isElevatedParkSection();
        }
        return false;
    }

    // Return true if there can be a road connection between the two given chunks
    static boolean hasRoadConnection(ChunkPlan i1, ChunkPlan i2) {
        if (!i1.doesRoadExtendTo()) {
            return false;
        }
        if (!i2.doesRoadExtendTo()) {
            return false;
        }
        if (i1.cityLevel == i2.cityLevel) {
            return true;
        }
        // A one-level difference only connects where a slope actually bridges it. Reading the slope
        // rather than merely allowing a difference of one is what keeps the upper road drawing
        // through to its edge exactly over the ramp, and ending in a kerb everywhere else.
        Direction slope1 = i1.getStreetSlopeDirection();
        if (slope1 != null && slope1.get(i1).coord.equals(i2.coord)) {
            return true;
        }
        Direction slope2 = i2.getStreetSlopeDirection();
        return slope2 != null && slope2.get(i2).coord.equals(i1.coord);
    }

    /**
     * A stream for one of the per-chunk building decisions.
     * <p>
     * The purpose is the caller's because three independent decisions are made at this one
     * coordinate - whether a building is here at all, which parts its floors use, and whether a
     * lonely neighbour suppresses it - and each of them reads draw 1. Sharing a purpose made the
     * building chance and the loneliness roll literally the same number.
     */
    boolean at(int level, Orientation orientation) {
        return switch (orientation) {
            case X -> atX(level);
            case Z -> atZ(level);
        };
    }

    // Call this from the street reference with the (potential building) as 'adj'
    // 'streetLevel' is the plan.cityLevel at the position of the street
    boolean frontPartFrom(ChunkPlan adj) {
        ChunkPlan.StreetType st = plan.streetType;
        boolean elevated = plan.isElevatedParkSection();
        if (elevated) {
            st = ChunkPlan.StreetType.PARK;
        }

        if (adj.hasBuilding && adj.frontType != null && st == ChunkPlan.StreetType.NORMAL && plan.cityLevel < adj.cityLevel + adj.getNumFloors()) {
            RailChunkType type = plan.getRailInfo().getType();
            if (type == RailChunkType.STATION_UNDERGROUND) {
                return false;
            }
            if (type == RailChunkType.GOING_DOWN_ONE_FROM_SURFACE) {
                return false;
            }
            if (plan.getMaxHighwayLevel() >= 0) {
                return false;
            }

            int local = adj.globalToLocal(plan.cityLevel);
            if (adj.isValidFloor(local) && adj.getFloor(local).getMetaBoolean(BuildingPart.META_DONTCONNECT)) {
                return false;
            }
        } else {
            return false;
        }
        return true;
    }


    // This checks if there can be a connection at minX
    boolean atX(int level) {
        if (!plan.isCity) {
            return false;
        }
        if (plan.multiBuildingPos.isRightSide()) {
            return false;
        }
        if (level < 0 || level >= plan.connectionAtX.length) {
            return false;
        }
        if (level < plan.floorTypes.length && plan.floorTypes[level].getMetaBoolean(BuildingPart.META_DONTCONNECT)) {
            return false;       // No connection supported
        }
        if (plan.getXmin().hasFrontPartFrom(plan)) {
            return true;
        }
        return plan.connectionAtX[level];
    }

    // This checks if there can be a connection at minX
    boolean atXFromStreet(int level) {
        if (!plan.isCity) {
            return false;
        }
        if (plan.multiBuildingPos.isRightSide()) {
            return false;
        }
        if (level < 0 || level >= plan.connectionAtX.length) {
            return false;
        }
        if (frontPartFrom(plan.getXmin())) {
            return true;
        }
        return plan.connectionAtX[level];
    }

    // This checks if there can be a connection at minZ
    boolean atZ(int level) {
        if (!plan.isCity) {
            return false;
        }
        if (plan.multiBuildingPos.isBottomSide()) {
            return false;
        }
        if (level < 0 || level >= plan.connectionAtZ.length) {
            return false;
        }
        if (level < plan.floorTypes.length && plan.floorTypes[level].getMetaBoolean(BuildingPart.META_DONTCONNECT)) {
            return false;       // No connection supported
        }
        if (plan.getZmin().hasFrontPartFrom(plan)) {
            return true;
        }
        return plan.connectionAtZ[level];
    }

    // This checks if there can be a connection at minZ
    boolean atZFromStreet(int level) {
        if (!plan.isCity) {
            return false;
        }
        if (plan.multiBuildingPos.isBottomSide()) {
            return false;
        }
        if (level < 0 || level >= plan.connectionAtZ.length) {
            return false;
        }
        if (frontPartFrom(plan.getZmin())) {
            return true;
        }
        return plan.connectionAtZ[level];
    }
}
