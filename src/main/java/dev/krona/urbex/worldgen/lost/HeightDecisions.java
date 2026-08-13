package dev.krona.urbex.worldgen.lost;


import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.gen.Terrain;
import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;

/**
 * How high this chunk's city sits, and how far the terrain around it has to move to meet it.
 *
 * <p>Two memoised answers. {@code desiredMaxHeightL1} is what this chunk alone wants; the L2 form
 * widens it by consulting the neighbours, which is what stops a building and the street beside it
 * asking the terrain for two different things. Both are held per plan because the terrain-correction
 * pass asks for them once per column.</p>
 *
 * <p>Split out of {@link ChunkPlan} with the state it memoises (issue #11), for the same reason as
 * {@link BridgeDecisions} and {@link SlopeDecisions}: resolving one chunk's answer reads its
 * neighbours', so the fields and the walk over them are one thing.</p>
 */
final class HeightDecisions {

    private final ChunkPlan plan;

    private volatile ChunkPlan.MinMax correctionHeights = null;
    private volatile ChunkPlan.MinMax maxHeightL1 = null;

    HeightDecisions(ChunkPlan plan) {
        this.plan = plan;
    }

    int buildingBottom() {
        int min = plan.provider.shape().minY() + 2;
        int max = plan.provider.shape().maxY() - 1 - CityGenerator.FLOORHEIGHT;

        // Locals. This used to decrement the published plan.cellars and plan.floors as it walked, so the answer
        // depended on how many times it had been asked: the first call shrank the building and every
        // later one measured the shrunken version, and a TimedCache eviction reset the count by
        // rebuilding the object. It is the "building queries such as bottom-height calculation mutate
        // plan.floors/plan.cellars during consumption" defect in issue #126, and the only reason the fields it
        // touched could not be final.
        int remainingCellars = plan.cellars;
        int lowestLevel = plan.getCityGroundLevel() - remainingCellars * CityGenerator.FLOORHEIGHT;

        // Fix lowest level so it goes above minimum build height
        while (lowestLevel <= min) {
            lowestLevel += CityGenerator.FLOORHEIGHT;
            remainingCellars--;
            if (remainingCellars < 0) {
                return Integer.MIN_VALUE;     // Bail out, this is a degenerate case
            }
        }

        // Contributes nothing but the degenerate bail-out: the height returned is the cellar walk's.
        int remainingFloors = plan.floors;
        while (plan.getCityGroundLevel() + remainingFloors * CityGenerator.FLOORHEIGHT >= max) {
            remainingFloors--;
            if (remainingFloors < 0) {
                return Integer.MIN_VALUE;     // Bail out, this is a degenerate case
            }
        }
        return lowestLevel;
    }

    /**
     * Return the building part at a given y value. Return null if there is no building part at that level
     */
    BuildingPart floorAtY(int lowestLevel, int y) {
        if (y < lowestLevel || y >= lowestLevel + (plan.floors + plan.cellars + 1) * CityGenerator.FLOORHEIGHT) {
            return null;    // No building part at this level
        }
        int localY = (y - lowestLevel) / CityGenerator.FLOORHEIGHT;
        if (localY < 0 || localY >= plan.floorTypes.length) {
            return null;    // No building part at this level
        }
        return plan.floorTypes[localY];
    }

    /**
     * Get the lowest height of a corner of four chunks (if it is a city chunk).
     * info: reference to the bottom-right chunk. The 0,0 position of this chunk is the reference.
     * Returns 100000 if the corner is not adjacent to any city chunk
     * Also returns 100000 if all corners are city or landscape chunks (as
     * this kind of corner should also have no effect on the landscape beyond those chunks)
     * This is the level 0 version which looks at current chunk corner only
     */
    int lowestCityHeightAtCorner() {
        ChunkPlan info00 = plan.getXmin().getZmin();
        ChunkPlan info01 = plan.getXmin();
        ChunkPlan info10 = plan.getZmin();
        if (plan.isCity && info10.isCity && info00.isCity && info01.isCity) {
            return 100000;
        }
        if (!plan.isCity && !info10.isCity && !info00.isCity && !info01.isCity) {
            return 100000;
        }
        // If we come here we have a mix of city and normal chunks
        int h = cityHeightForChunk();
        h = Math.min(h, info01.getCityHeightForChunk());
        h = Math.min(h, info10.getCityHeightForChunk());
        h = Math.min(h, info00.getCityHeightForChunk());
        return h;
    }

    /*
     * This is used for correcting the terrain and indicates the desired
     * level to which adjacent terrains should interpolate
     */
    int cityHeightForChunk() {
        if (plan.isCity) {
            return plan.getCityGroundLevel();
        } else {
            if (plan.isOcean()) {
                return plan.groundLevel - plan.profile.oceanCorrectionBorder();
            } else {
                return 100000;
            }
        }
    }

    /**
     * Given adjacent (city) chunks, calculate the desired height to interpolate the
     * landscape to (minimum/maximum). This is calculated for the reference position of this chunk (0,0 point)
     * This is the level 1 version which looks at adjacent heights only
     */
    private ChunkPlan.MinMax desiredMaxHeightL1() {
        if (maxHeightL1 == null) {
            int h = lowestCityHeightAtCorner();

            int cx = plan.coord.chunkX();
            int cz = plan.coord.chunkZ();

            // @todo build limit
            if (h < plan.provider.shape().maxBuildHeight()) {
                // The L0 height at this corner is fixed so we return that
                maxHeightL1 = new ChunkPlan.MinMax(
                        h + Terrain.getRandomizedOffset(plan.provider.seed(), cx, cz, plan.profile.terrainFixLowerMinOffset(), plan.profile.terrainFixLowerMaxOffset(), Rng.Purpose.TERRAIN_FIX_LOWER),
                        h + Terrain.getRandomizedOffset(plan.provider.seed(), cx, cz, plan.profile.terrainFixUpperMinOffset(), plan.profile.terrainFixUpperMaxOffset(), Rng.Purpose.TERRAIN_FIX_UPPER));
                return maxHeightL1;
            }

            ChunkPlan.MinMax minMax = new ChunkPlan.MinMax();

            plan.getXmin().getZmin().heights.updateMinMaxL1(minMax, 25 + Terrain.getHeightOffsetL1(plan.provider.seed(), cx - 1, cz - 1));
            plan.getXmin().heights.updateMinMaxL1(minMax, 20 + Terrain.getHeightOffsetL1(plan.provider.seed(), cx - 1, cz));
            plan.getXmin().getZmax().heights.updateMinMaxL1(minMax, 25 + Terrain.getHeightOffsetL1(plan.provider.seed(), cx - 1, cz + 1));

            plan.getZmin().heights.updateMinMaxL1(minMax, 20 + Terrain.getHeightOffsetL1(plan.provider.seed(), cx, cz - 1));
            plan.getZmax().heights.updateMinMaxL1(minMax, 20 + Terrain.getHeightOffsetL1(plan.provider.seed(), cx, cz + 1));

            plan.getXmax().getZmin().heights.updateMinMaxL1(minMax, 25 + Terrain.getHeightOffsetL1(plan.provider.seed(), cx + 1, cz - 1));
            plan.getXmax().heights.updateMinMaxL1(minMax, 20 + Terrain.getHeightOffsetL1(plan.provider.seed(), cx + 1, cz));
            plan.getXmax().getZmax().heights.updateMinMaxL1(minMax, 25 + Terrain.getHeightOffsetL1(plan.provider.seed(), cx + 1, cz + 1));

            maxHeightL1 = minMax;
        }
        return maxHeightL1;
    }


    /**
     * Given adjacent (city) chunks, calculate the desired height to interpolate the
     * landscape too. This is calculated for the reference position of this chunk (0,0 point)
     * This is the level 2 version which looks at L1 heights of adjacent chunks
     */
    ChunkPlan.MinMax desiredMaxHeightL2() {
        if (correctionHeights == null) {
            ChunkPlan.MinMax mm = desiredMaxHeightL1();
            // @todo build limit
            if (mm.min < plan.provider.shape().maxBuildHeight()) {
                // The L1 height at this corner is fixed so we return that
                correctionHeights = new ChunkPlan.MinMax(mm);
                return correctionHeights;
            }

            int cx = plan.coord.chunkX();
            int cz = plan.coord.chunkZ();

            ChunkPlan.MinMax minMax = new ChunkPlan.MinMax();

            plan.getXmin().getZmin().updateMinMaxL2(minMax, 25 + Terrain.getHeightOffsetL2(plan.provider.seed(), cx - 1, cz - 1));
            plan.getXmin().updateMinMaxL2(minMax, 20 + Terrain.getHeightOffsetL2(plan.provider.seed(), cx - 1, cz));
            plan.getXmin().getZmax().updateMinMaxL2(minMax, 25 + Terrain.getHeightOffsetL2(plan.provider.seed(), cx - 1, cz + 1));

            plan.getZmin().updateMinMaxL2(minMax, 20 + Terrain.getHeightOffsetL2(plan.provider.seed(), cx, cz - 1));
            plan.getZmax().updateMinMaxL2(minMax, 20 + Terrain.getHeightOffsetL2(plan.provider.seed(), cx, cz + 1));

            plan.getXmax().getZmin().updateMinMaxL2(minMax, 25 + Terrain.getHeightOffsetL2(plan.provider.seed(), cx + 1, cz - 1));
            plan.getXmax().updateMinMaxL2(minMax, 20 + Terrain.getHeightOffsetL2(plan.provider.seed(), cx + 1, cz));
            plan.getXmax().getZmax().updateMinMaxL2(minMax, 25 + Terrain.getHeightOffsetL2(plan.provider.seed(), cx + 1, cz + 1));
            correctionHeights = minMax;
        }
        return correctionHeights;
    }

    void updateMinMaxL2(ChunkPlan.MinMax minMax, int offs) {
        ChunkPlan.MinMax h = desiredMaxHeightL1();
        if ((h.min - offs) < minMax.min) {
            minMax.min = h.min - offs;
        }
        if ((h.max + offs) < minMax.max) {
            minMax.max = h.max + offs;
        }
    }


    private void updateMinMaxL1(ChunkPlan.MinMax minMax, int offs) {
        int h = lowestCityHeightAtCorner();
        if ((h - offs) < minMax.min) {
            minMax.min = h - offs;
        }
        if ((h + offs) < minMax.max) {
            minMax.max = h + offs;
        }
    }

}
