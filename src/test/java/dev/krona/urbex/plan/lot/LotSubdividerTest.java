package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.CityPlan;
import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Planner;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.SettlementClass;
import dev.krona.urbex.plan.block.BlockExtractor;
import dev.krona.urbex.plan.block.CityBlock;
import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.road.RoadClass;
import dev.krona.urbex.plan.road.RoadGraph;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LotSubdividerTest {

    private static final PlanParams P = PlanParams.defaults();

    /** A square bounded by roads on all four sides, small enough that it stays a single leaf. */
    private static RoadGraph perimeterSquare(int size) {
        return RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(size, 0))
                .node(new Vec2(size, size)).node(new Vec2(0, size))
                .edge(0, 1, RoadClass.ARTERIAL).edge(1, 2, RoadClass.ARTERIAL)
                .edge(2, 3, RoadClass.ARTERIAL).edge(3, 0, RoadClass.ARTERIAL)
                .build();
    }

    @Test
    void aSmallBlockThatFitsWithinTheTargetSizeStaysOneLot() {
        // 16x16 is under 2 * coreLotSizeBlocks (24), so no split happens at all.
        RoadGraph g = perimeterSquare(16);
        CityBlock block = BlockExtractor.extract(g, P).get(0);
        List<Lot> lots = LotSubdivider.subdivide(1L, block, District.CORE, g, new FlatTerrain(64), P);

        assertEquals(1, lots.size());
        Lot lot = lots.get(0);
        // Shrunk by ARTERIAL's road half-width (3, with P's defaults) on every side from the 0..16
        // leaf, not a flat 1 block - see PlanParams.roadHalfWidthBlocks's doc for why a lot's near
        // edge has to clear the carriageway's real width, not just sit one block off its centreline.
        assertEquals(new Rect(3, 3, 13, 13), lot.footprint());
        assertTrue(lot.frontingEdgeIndex() >= 0 && lot.frontingEdgeIndex() < g.edges().size());
    }

    @Test
    void aLargeBlockSplitsIntoManyNonOverlappingLots() {
        RoadGraph g = perimeterSquare(200);
        CityBlock block = BlockExtractor.extract(g, P).get(0);
        List<Lot> lots = LotSubdivider.subdivide(5L, block, District.FRINGE, g, new FlatTerrain(64), P);

        assertTrue(lots.size() > 1, "a 200x200 block should split into more than one lot");
        for (int i = 0; i < lots.size(); i++) {
            for (int j = i + 1; j < lots.size(); j++) {
                assertFalse(lots.get(i).footprint().intersects(lots.get(j).footprint()),
                        "lots " + i + " and " + j + " overlap");
            }
        }
    }

    /**
     * With only perimeter roads and no roads through the middle, a leaf whose centre is deeper than
     * {@code maxLotDepthBlocks} from every edge has no road within reach and must be dropped — the
     * interior of a big block with no interior street reads as a courtyard, not a lot.
     */
    @Test
    void aLeafFartherThanMaxLotDepthFromEveryRoadIsDiscarded() {
        int size = 300;
        RoadGraph g = perimeterSquare(size);
        CityBlock block = BlockExtractor.extract(g, P).get(0);
        List<Lot> lots = LotSubdivider.subdivide(2L, block, District.FRINGE, g, new FlatTerrain(64), P);

        assertTrue(lots.size() > 0, "lots near the perimeter should survive");
        for (Lot lot : lots) {
            Vec2 c = lot.footprint().center();
            int distanceToNearestEdge = Math.min(
                    Math.min(c.x(), size - c.x()),
                    Math.min(c.z(), size - c.z()));
            assertTrue(distanceToNearestEdge <= P.maxLotDepthBlocks(),
                    "lot at " + lot.footprint() + " is " + distanceToNearestEdge
                            + " blocks from the nearest road, past maxLotDepthBlocks ("
                            + P.maxLotDepthBlocks() + ")");
        }
    }

    @Test
    void subdivisionIsDeterministic() {
        RoadGraph g = perimeterSquare(200);
        CityBlock block = BlockExtractor.extract(g, P).get(0);
        assertEquals(
                LotSubdivider.subdivide(9L, block, District.OUTER, g, new FlatTerrain(64), P),
                LotSubdivider.subdivide(9L, block, District.OUTER, g, new FlatTerrain(64), P));
    }

    @Test
    void aLotBesideAStraightRiverHasExactlyOneWaterSide() {
        RiverTerrain river = new RiverTerrain(64, 0, 24);
        CityPlan plan = Planner.plan(3L, new Settlement(SettlementClass.TOWN, 0, 0), river, P);
        long waterfront = plan.lots().stream().filter(l -> l.waterSides() != 0).count();
        assertTrue(waterfront > 0, "a town on a river should have lots fronting it");
        for (Lot lot : plan.lots()) {
            if (lot.waterSides() != 0) {
                assertEquals(WaterShape.STRAIGHT, lot.waterShape(),
                        "lot " + lot.id() + " beside a straight river should have a straight frontage");
            }
        }
    }

    @Test
    void inlandLotsHaveNoWaterSides() {
        CityPlan plan = Planner.plan(3L, new Settlement(SettlementClass.TOWN, 0, 0),
                new FlatTerrain(64), P);
        for (Lot lot : plan.lots()) {
            assertEquals(0, lot.waterSides(), "lot " + lot.id() + " found water on flat dry ground");
            assertEquals(WaterShape.INLAND, lot.waterShape());
        }
    }

    @Test
    void theShapeTaxonomyCoversEveryMask() {
        assertEquals(WaterShape.INLAND, WaterShape.of(0));
        assertEquals(WaterShape.STRAIGHT, WaterShape.of(WaterShape.NORTH));
        assertEquals(WaterShape.CORNER, WaterShape.of(WaterShape.NORTH | WaterShape.EAST));
        assertEquals(WaterShape.CHANNEL, WaterShape.of(WaterShape.NORTH | WaterShape.SOUTH));
        assertEquals(WaterShape.CHANNEL, WaterShape.of(WaterShape.EAST | WaterShape.WEST));
        assertEquals(WaterShape.PENINSULA,
                WaterShape.of(WaterShape.NORTH | WaterShape.EAST | WaterShape.SOUTH));
        assertEquals(WaterShape.ISLAND, WaterShape.of(0b1111));
    }
}
