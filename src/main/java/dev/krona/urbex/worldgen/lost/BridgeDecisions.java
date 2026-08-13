package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.gen.Terrain;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.tags.BiomeTags;
import dev.krona.urbex.worldgen.PlanningContext;
import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;

/**
 * Whether a bridge crosses this chunk, and which deck part it uses.
 *
 * <p>Two kinds of answer, and the order between them matters: a span the primary bridge planner laid
 * down settles the chunk for both orientations, and only when there is none does the opportunistic
 * scan run - letting an ordinary bridge claim the chunk first would cancel the planned one.</p>
 *
 * <h2>The memoisation, and why there is no lock</h2>
 *
 * <p>A {@link ChunkPlan} lives in the dimension's cache and is read by every chunk that neighbours
 * it, so these fields are filled in from whichever thread got here first while other threads are
 * reading. They are all volatile, and every {@code …Calculated} flag is written <em>after</em> the
 * value it guards, so a reader that sees the flag set is guaranteed to see the value. Two threads
 * racing on the same field both compute and both write; the result is the same either way, because
 * it is derived from the already-fixed plan graph.</p>
 *
 * <p>That is the racy-single-check idiom, not double-checked locking, and the absence of a lock is
 * deliberate: the scan below walks into its neighbours and reads their decisions, so any
 * per-instance lock would be a lock-ordering deadlock waiting to happen. Splitting this out of
 * {@link ChunkPlan} (issue #11) moved the state and the walk together for exactly that reason -
 * they are one thing, and separating them would leave the rationale attached to neither.</p>
 */
final class BridgeDecisions {

    private final ChunkPlan plan;

    private volatile boolean xCalculated = false;
    private volatile boolean zCalculated = false;
    private volatile BuildingPart xType = null;
    private volatile BuildingPart zType = null;

    private volatile boolean plannedCalculated = false;
    private volatile PrimaryBridgePlanner.BridgeSpan plannedSpan;

    private volatile Boolean ocean = null;

    BridgeDecisions(ChunkPlan plan) {
        this.plan = plan;
    }

    PrimaryBridgePlanner.BridgeSpan planned() {
        if (plannedCalculated) {
            return plannedSpan;
        }
        PrimaryBridgePlanner.BridgeSpan result = PrimaryBridgePlanner.spanAt(plan.coord, plan.provider).orElse(null);
        // Value first, then the flag.
        plannedSpan = result;
        plannedCalculated = true;
        return result;
    }

    BuildingPart at(PlanningContext provider, Orientation orientation) {
        return switch (orientation) {
            case X -> x(plan.provider);
            case Z -> z(plan.provider);
        };
    }

    boolean any(PlanningContext provider) {
        if (x(plan.provider) != null) {
            return true;
        }
        if (z(plan.provider) != null) {
            return true;
        }
        return false;
    }

    // To prevent adjacent bridges of the same direction we give the bridges at even chunk Z coordinates higher priority
    BuildingPart x(PlanningContext provider) {
        if (xCalculated) {
            return xType;
        }
        BuildingPart result = computeX(plan.provider);
        // Value first, then the flag. The old code set the flag up front and filled the value in
        // afterwards, which is fine under a lock and a lie without one.
        xType = result;
        xCalculated = true;
        return result;
    }

    private BuildingPart computeX(PlanningContext provider) {
        PrimaryBridgePlanner.BridgeSpan planned = planned();
        if (planned != null) {
            // A planned span settles this chunk for both orientations. Falling through to the
            // opportunistic scan below when the span runs the other way would let an ordinary bridge
            // claim the chunk first and cancel the planned one.
            return planned.orientation() == Orientation.X
                    ? PrimaryBridgePlanner.deckPart(planned, plan.coord, plan.provider) : null;
        }
        if (!plan.xBridge) {
            return null;
        }
        if (!isSuitableForBridge(plan.provider, plan)) {
            return null;
        }
        if (plan.coord.chunkZ() % 2 != 0 && (plan.getZmin().hasXBridge(plan.provider) != null || plan.getZmax().hasXBridge(plan.provider) != null)) {
            return null;
        }
        BuildingPart bt = plan.bridgeType;
        ChunkPlan i = plan.getXmin();
        while ((!i.isCity) && i.xBridge && isSuitableForBridge(plan.provider, i)) {
            if (plan.coord.chunkZ() % 2 != 0 && (i.getZmin().hasXBridge(plan.provider) != null || i.getZmax().hasXBridge(plan.provider) != null)) {
                return null;
            }
            bt = i.bridgeType;
            i = i.getXmin();
        }
        if ((!i.isCity) || i.hasBuilding || i.cityLevel > 0) {  // @todo support bridges at higher levels?
            return null;
        }

        ChunkPlan minimum = i;

        i = plan.getXmax();
        while ((!i.isCity) && i.xBridge && isSuitableForBridge(plan.provider, i)) {
            if (plan.coord.chunkZ() % 2 != 0 && (i.getZmin().hasXBridge(plan.provider) != null || i.getZmax().hasXBridge(plan.provider) != null)) {
                return null;
            }
            i = i.getXmax();
        }
        if ((!i.isCity) || i.hasBuilding || i.cityLevel > 0) {
            return null;
        }
        // Here we can automatically mark the rest of the bridge as ok. Saves on calculation
        i = i.getXmin();
        ChunkCoord minCoord = minimum.coord;
        while (!i.coord.equals(minCoord)) {
            i.bridges.xType = bt;
            i.bridges.xCalculated = true;
            i.bridges.zType = null;
            i.bridges.zCalculated = true;
            i = i.getXmin();
        }

        return bt;
    }

    // To prevent adjacent bridges of the same direction we give the bridges at even chunk X coordinates higher priority
    BuildingPart z(PlanningContext provider) {
        if (zCalculated) {
            return zType;
        }
        BuildingPart result = computeZ(plan.provider);
        zType = result;
        zCalculated = true;
        return result;
    }

    private BuildingPart computeZ(PlanningContext provider) {
        PrimaryBridgePlanner.BridgeSpan planned = planned();
        if (planned != null) {
            return planned.orientation() == Orientation.Z
                    ? PrimaryBridgePlanner.deckPart(planned, plan.coord, plan.provider) : null;
        }
        if (!plan.zBridge) {
            return null;
        }
        if (!isSuitableForBridge(plan.provider, plan)) {
            return null;
        }
        if (x(plan.provider) != null) {
            return null;
        }

        if (plan.coord.chunkX() % 2 != 0 && (plan.getXmin().hasZBridge(plan.provider) != null || plan.getXmax().hasZBridge(plan.provider) != null)) {
            return null;
        }

        BuildingPart bt = plan.bridgeType;
        ChunkPlan i = plan.getZmin();
        while ((!i.isCity) && i.zBridge && isSuitableForBridge(plan.provider, i)) {
            if (i.hasXBridge(plan.provider) != null) {
                return null;
            }
            if (plan.coord.chunkX() % 2 != 0 && (i.getXmin().hasZBridge(plan.provider) != null || i.getXmax().hasZBridge(plan.provider) != null)) {
                return null;
            }

            bt = i.bridgeType;
            i = i.getZmin();
        }

        ChunkPlan minimum = i;

        if ((!i.isCity) || i.hasBuilding || i.cityLevel > 0) {
            return null;
        }
        i = plan.getZmax();
        while ((!i.isCity) && i.zBridge && isSuitableForBridge(plan.provider, i)) {
            if (i.hasXBridge(plan.provider) != null) {
                return null;
            }
            if (plan.coord.chunkX() % 2 != 0 && (i.getXmin().hasZBridge(plan.provider) != null || i.getXmax().hasZBridge(plan.provider) != null)) {
                return null;
            }
            i = i.getZmax();
        }
        if ((!i.isCity) || i.hasBuilding || i.cityLevel > 0) {
            return null;
        }
        // Here we can automatically mark the rest of the bridge as ok. Saves on calculation
        i = i.getZmin();
        ChunkCoord minCoord = minimum.coord;
        while (!i.coord.equals(minCoord)) {
            i.bridges.zType = bt;
            i.bridges.zCalculated = true;
            i.bridges.xType = null;
            i.bridges.xCalculated = true;
            i = i.getZmin();
        }

        return bt;
    }

    boolean isOcean() {
        if (ocean != null) {
            return ocean;
        }
        Holder<Biome> mainBiome = BiomeInfo.getBiomeInfo(plan.provider, plan.coord).getMainBiome();
        ocean = mainBiome.is(BiomeTags.IS_OCEAN) || mainBiome.is(BiomeTags.IS_DEEP_OCEAN);
        return ocean;
    }


    private boolean isSuitableForBridge(PlanningContext provider, ChunkPlan i) {
        if (i.getPlannedBridge() != null) {
            // A planned span owns this chunk. An opportunistic bridge must not run through it -
            // it would stamp its own part over the planned deck on its way past.
            return false;
        }
        return i.cityLevel < plan.cityLevel || Terrain.isWaterBiome(plan.provider, i.coord);
    }
}
