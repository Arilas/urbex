package dev.krona.urbex.plan;

import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.geom.Polygon;
import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.lot.Lot;
import dev.krona.urbex.plan.terrain.CoastTerrain;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    private static final PlanParams P = PlanParams.defaults();
    private static final Settlement TOWN = new Settlement(SettlementClass.TOWN, 0, 0);
    private static final Settlement CITY = new Settlement(SettlementClass.CITY, 0, 0);

    /**
     * A spine class (see task 5b), exercised alongside {@link #TOWN} in every invariant below that
     * makes sense for a tree: it has no blocks, so the two tests that are inherently about
     * block-subdivided districts ({@link #everyLotLiesInsideExactlyOneBlock} and
     * {@link #coreLotsAreSmallerThanFringeLots}/{@link #waterfrontDistrictsAppearOnACoastAndNotInland})
     * either handle that explicitly or stay {@link #TOWN}-only rather than being weakened to fit a
     * shape they were never about.
     */
    private static final Settlement VILLAGE = new Settlement(SettlementClass.VILLAGE, 0, 0);

    private static final List<Settlement> ALL_CLASSES = List.of(TOWN, VILLAGE);

    @Test
    void everyLotTouchesARoad() {
        // A building with no access is the classic failure of generated cities.
        for (Settlement s : ALL_CLASSES) {
            for (long seed = 0; seed < 20; seed++) {
                CityPlan plan = Planner.plan(seed, s, new FlatTerrain(64), P);
                for (Lot lot : plan.lots()) {
                    assertTrue(lot.frontingEdgeIndex() >= 0
                                    && lot.frontingEdgeIndex() < plan.roads().edges().size(),
                            s.cls() + " seed " + seed + ": lot " + lot.id() + " fronts onto no road");
                }
            }
        }
    }

    @Test
    void lotsNeverOverlap() {
        for (Settlement s : ALL_CLASSES) {
            for (long seed = 0; seed < 20; seed++) {
                CityPlan plan = Planner.plan(seed, s, new FlatTerrain(64), P);
                var lots = plan.lots();
                for (int i = 0; i < lots.size(); i++) {
                    for (int j = i + 1; j < lots.size(); j++) {
                        assertTrue(!lots.get(i).footprint().intersects(lots.get(j).footprint()),
                                s.cls() + " seed " + seed + ": lots " + i + " and " + j + " overlap");
                    }
                }
            }
        }
    }

    /**
     * Checks every block of a lot's footprint against every block's outline, not just the
     * footprint's centre point - a lot is an area, and "lies inside exactly one block" has to mean
     * the whole area does, or the assertion can pass for a lot whose corner pokes into a neighbour
     * while its centre happens to sit safely inside the block it was cut from. In practice this
     * still passes: {@code LotSubdivider} already guarantees full-footprint containment by
     * construction ({@code liesFullyInside} checks every corner plus every outline edge against the
     * candidate before it is ever emitted), so this is verifying that guarantee properly rather than
     * catching a live bug - but a centre-only check couldn't have told the difference.
     */
    @Test
    void everyLotLiesInsideExactlyOneBlock() {
        for (long seed = 0; seed < 20; seed++) {
            CityPlan plan = Planner.plan(seed, TOWN, new FlatTerrain(64), P);
            for (Lot lot : plan.lots()) {
                Rect fp = lot.footprint();
                long containing = plan.blocks().stream()
                        .filter(b -> footprintFullyInside(fp, b.outline()))
                        .count();
                assertEquals(1, containing,
                        "seed " + seed + ": lot " + lot.id() + " footprint " + fp
                                + " lies fully in " + containing + " blocks");
            }
        }
    }

    private static boolean footprintFullyInside(Rect footprint, Polygon outline) {
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                if (!outline.contains(new Vec2(x, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The block-containment invariant above is about block-subdivided lots and does not translate to
     * a spine settlement: {@code SpineGrowth} grows a tree, which encloses no faces, so
     * {@code RoadsideLots} derives lots from road frontage instead and {@link Planner#plan} hands
     * back an empty block list by construction (task 5b, step 6). Weakening
     * {@link #everyLotLiesInsideExactlyOneBlock} to tolerate that would let a real regression in the
     * block-subdivision path go unnoticed, so instead this documents and checks the block-free shape
     * on its own terms: no blocks, no districts, but still real lots on flat ground.
     */
    @Test
    void aSpineSettlementsPlanHasNoBlocksButStillHasLots() {
        for (long seed = 0; seed < 20; seed++) {
            CityPlan plan = Planner.plan(seed, VILLAGE, new FlatTerrain(64), P);
            assertTrue(plan.blocks().isEmpty(),
                    "seed " + seed + ": a spine settlement's plan should have no blocks");
            assertTrue(plan.districts().isEmpty(),
                    "seed " + seed + ": a spine settlement's plan should have no districts");
            assertTrue(!plan.lots().isEmpty(),
                    "seed " + seed + ": a village on flat ground should still get roadside lots");
        }
    }

    /**
     * Every block of every lot's footprint, not just its centre, for every class this suite covers -
     * TOWN and CITY (block-subdivision, via {@code LotSubdivider}) as well as VILLAGE (roadside, via
     * {@code RoadsideLots}). Both pipelines' dryness checks now share one exhaustive scan
     * ({@code FootprintDryness.isFullyDry}, in {@code dev.krona.urbex.plan.lot}) after review found
     * TOWN's separate 9-point probe had the same class of gap {@code RoadsideLots}' did: 3.56% of
     * TOWN's river-terrain lots contained a wet block despite passing it (100-seed sweep). This test
     * used to give TOWN a weaker, centre-only pass while that was still true; see the task report for
     * the sweep confirming this assertion failed against the unshared probe first.
     */
    @Test
    void noLotSitsUnderWater() {
        RiverTerrain river = new RiverTerrain(64, 0, 24);
        for (long seed = 0; seed < 20; seed++) {
            for (Settlement s : List.of(TOWN, CITY, VILLAGE)) {
                for (Lot lot : Planner.plan(seed, s, river, P).lots()) {
                    Rect fp = lot.footprint();
                    for (int x = fp.minX(); x <= fp.maxX(); x++) {
                        for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                            assertTrue(!river.isWaterAt(x, z),
                                    s.cls() + " seed " + seed + ": lot " + lot.id() + " footprint " + fp
                                            + " contains a wet block at " + x + "," + z);
                        }
                    }
                }
            }
        }
    }

    @Test
    void theRoadNetworkIsConnected() {
        for (Settlement s : ALL_CLASSES) {
            for (long seed = 0; seed < 20; seed++) {
                assertTrue(Planner.plan(seed, s, new FlatTerrain(64), P).roads().isConnected(),
                        s.cls() + " seed " + seed + " produced a disconnected network");
            }
        }
    }

    @Test
    void waterfrontDistrictsAppearOnACoastAndNotInland() {
        CityPlan coastal = Planner.plan(4L, TOWN, new CoastTerrain(64, 64), P);
        CityPlan inland = Planner.plan(4L, TOWN, new FlatTerrain(64), P);
        assertTrue(coastal.districts().containsValue(District.WATERFRONT),
                "no waterfront district on a coast");
        assertTrue(!inland.districts().containsValue(District.WATERFRONT),
                "waterfront district appeared inland");
    }

    /**
     * FRINGE needs a block whose bounding-box centre sits past 0.75 of the settlement radius, and
     * the ring-growth geometry rarely puts one there: the outermost annulus beyond the last ring is
     * never closed into a block, so a block's centre generally falls well short of the settlement's
     * nominal edge. That used to mean CITY never produced a FRINGE lot at all (sweeping seeds 0-199
     * pre-perimeter-ring, max observed block-centre fraction 0.655) while TOWN did, in 16 of 200 -
     * which is why this test uses TOWN and, of TOWN's seeds, specifically seed 67, the lowest with
     * lots in both CORE and FRINGE.
     * <p>
     * The perimeter ring added since (a ring close to the settlement's true edge, closing that outer
     * band the same way every other ring closes the one inside it - see {@code ArterialGrowth}'s own
     * doc) fixed CITY's reachability too: sweeping seeds 0-299 today, CITY produces a FRINGE lot in
     * 165 of 300 seeds. {@link #fringeIsReachableForACityAcrossManySeeds} is the regression guard for
     * that fix specifically; this test's job is narrower and unrelated to which class can reach
     * FRINGE at all - it only checks that, once a settlement (any settlement) has lots in both bands,
     * CORE's really are smaller - so it stays on TOWN/seed 67 rather than switching to CITY now that
     * CITY could serve too.
     */
    @Test
    void coreLotsAreSmallerThanFringeLots() {
        CityPlan plan = Planner.plan(67L, TOWN, new FlatTerrain(64), P);
        double core = averageArea(plan, District.CORE);
        double fringe = averageArea(plan, District.FRINGE);
        assertTrue(core < fringe,
                "core lots (" + core + ") should be smaller than fringe lots (" + fringe + ")");
    }

    /**
     * The perimeter ring (see {@code ArterialGrowth}) is what makes the settlement's outermost band
     * enclose any blocks at all, and CITY specifically used to reach none of them (see
     * {@link #coreLotsAreSmallerThanFringeLots}'s doc for the history). That fix has its own review
     * evidence (zero FRINGE blocks before, 526 total across a 300-seed sweep after) but nothing in
     * the suite pinned the reachability itself, so a future change to the ring could silently regress
     * CITY back to zero FRINGE blocks without failing anything. This is that guard: sweep seeds 0-299
     * and require a healthy, not just nonzero, total - nonzero alone would still pass if the ring
     * regressed to reaching FRINGE on a single lucky seed in 300.
     */
    @Test
    void fringeIsReachableForACityAcrossManySeeds() {
        long totalFringeBlocks = 0;
        for (long seed = 0; seed < 300; seed++) {
            CityPlan plan = Planner.plan(seed, CITY, new FlatTerrain(64), P);
            totalFringeBlocks += plan.districts().values().stream()
                    .filter(d -> d == District.FRINGE)
                    .count();
        }
        assertTrue(totalFringeBlocks > 100,
                "expected CITY to reach FRINGE comfortably across 300 seeds, got only "
                        + totalFringeBlocks + " total FRINGE blocks");
    }

    @Test
    void planningIsDeterministic() {
        assertEquals(Planner.plan(11L, TOWN, new FlatTerrain(64), P),
                Planner.plan(11L, TOWN, new FlatTerrain(64), P));
        assertEquals(Planner.plan(11L, VILLAGE, new FlatTerrain(64), P),
                Planner.plan(11L, VILLAGE, new FlatTerrain(64), P));
    }

    private static double averageArea(CityPlan plan, District d) {
        return plan.lots().stream()
                .filter(l -> l.district() == d)
                .mapToInt(l -> l.footprint().area())
                .average()
                .orElseThrow(() -> new AssertionError("no lots in district " + d));
    }
}
