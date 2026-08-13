package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.worldgen.PlanningContext;

import dev.krona.urbex.varia.ChunkCoord;

import javax.annotation.Nullable;

/**
 * Which way a street chunk slopes, and where its stairs face.
 *
 * <p>Both are decided by looking at the neighbours - a street slopes towards the lower of the two
 * city levels either side of it, and stairs face whichever way the slope runs - so both are memoised
 * per plan rather than recomputed by every caller that asks.</p>
 *
 * <p>Same concurrency shape as {@link BridgeDecisions}, and for the same reason: volatile fields,
 * flag written after the value it guards, no lock, because resolving one chunk's answer reads its
 * neighbours'. Split out of {@link ChunkPlan} with the state it memoises (issue #11).</p>
 */
final class SlopeDecisions {

    private final ChunkPlan plan;

    private volatile boolean slopeCalculated = false;
    private volatile Direction slopeDirection;

    private volatile boolean stairCalculated = false;
    private volatile Direction stairFacing;
    private volatile boolean actualCalculated = false;
    private volatile Direction actualFacing;

    SlopeDecisions(ChunkPlan plan) {
        this.plan = plan;
    }

    Direction streetSlope() {
        if (slopeCalculated) {
            return slopeDirection;
        }
        Direction direction = computeStreetSlope();
        // Value first, then the flag: a reader that sees the flag set must see the value.
        slopeDirection = direction;
        slopeCalculated = true;
        return direction;
    }

    @Nullable
    private Direction computeStreetSlope() {
        if (!plan.isMinorRoadSection()) {
            return null;
        }
        Direction slopeDirection = null;
        for (Direction direction : Direction.VALUES) {
            ChunkPlan adjacent = direction.get(plan);
            if (adjacent.isMinorRoadSection() && adjacent.cityLevel == plan.cityLevel + 1) {
                if (slopeDirection != null) {
                    // Two ways up out of one chunk: which one the ramp should face is not decided.
                    return null;
                }
                slopeDirection = direction;
            }
        }
        if (slopeDirection == null) {
            return null;
        }

        ChunkPlan upper = slopeDirection.get(plan);
        ChunkPlan approach = slopeDirection.getOpposite().get(plan);
        if (!approach.isMinorRoadSection() || approach.cityLevel != plan.cityLevel) {
            return null;
        }
        ChunkPlan departure = slopeDirection.get(upper);
        if (!departure.isMinorRoadSection() || departure.cityLevel != upper.cityLevel) {
            return null;
        }

        for (Direction direction : Direction.VALUES) {
            if (direction != slopeDirection && direction != slopeDirection.getOpposite()) {
                ChunkPlan side = direction.get(plan);
                if (side.isPlannedRoadSection() && side.cityLevel == plan.cityLevel) {
                    return null;
                }
                ChunkPlan upperSide = direction.get(upper);
                if (upperSide.isPlannedRoadSection() && upperSide.cityLevel == upper.cityLevel) {
                    return null;
                }
            }
        }
        return slopeDirection;
    }

    private Direction stair() {
        if (stairCalculated) {
            return stairFacing;
        }
        Direction direction = null;
        // A sloped chunk already carries the whole level change across its full width. The narrow
        // stair decoration on top of it would be a second, contradictory way up.
        if (streetSlope() == null && plan.streetType != ChunkPlan.StreetType.PARK && !plan.hasBuilding && plan.isCity) {
            if (plan.cityLevel == plan.getXmin().cityLevel - 1 && !plan.getXmin().hasBuilding && plan.getXmin().isCity) {
                direction = Direction.XMIN;
            } else if (plan.cityLevel == plan.getXmax().cityLevel - 1 && !plan.getXmax().hasBuilding && plan.getXmax().isCity) {
                direction = Direction.XMAX;
            } else if (plan.cityLevel == plan.getZmin().cityLevel - 1 && !plan.getZmin().hasBuilding && plan.getZmin().isCity) {
                direction = Direction.ZMIN;
            } else if (plan.cityLevel == plan.getZmax().cityLevel - 1 && !plan.getZmax().hasBuilding && plan.getZmax().isCity) {
                direction = Direction.ZMAX;
            }
        }
        // Value first, then the flag: a reader that sees the flag set must see the value.
        stairFacing = direction;
        stairCalculated = true;
        return direction;
    }

    // This returns the actual stair direction. It keeps track if there are stair chunks around
    // it those have higher stair priority
    Direction actualStair() {
        if (actualCalculated) {
            return actualFacing;
        }
        Direction direction = stair();
        if (direction != null) {
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    if (cx != 0 || cz != 0) {
                        ChunkCoord key = plan.coord.offset(cx, cz);
                        ChunkPlan adjacent = ChunkPlan.getChunkPlan(key, plan.provider);
                        if (adjacent.slopes.stair() != null && adjacent.stairPriority > plan.stairPriority) {
                            direction = null;
                            break;
                        }
                    }
                }
            }
        }
        actualFacing = direction;
        actualCalculated = true;
        return direction;
    }
}
