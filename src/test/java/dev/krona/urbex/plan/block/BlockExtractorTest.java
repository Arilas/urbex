package dev.krona.urbex.plan.block;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.SettlementClass;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.road.ArterialGrowth;
import dev.krona.urbex.plan.road.RoadClass;
import dev.krona.urbex.plan.road.RoadEdge;
import dev.krona.urbex.plan.road.RoadGraph;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockExtractorTest {

    private static final PlanParams P = PlanParams.defaults();

    /** Four nodes in a square, four edges. Exactly one enclosed face. */
    @Test
    void aSingleSquareYieldsOneBlock() {
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(100, 0))
                .node(new Vec2(100, 100)).node(new Vec2(0, 100))
                .edge(0, 1, RoadClass.ARTERIAL).edge(1, 2, RoadClass.ARTERIAL)
                .edge(2, 3, RoadClass.ARTERIAL).edge(3, 0, RoadClass.ARTERIAL)
                .build();
        List<CityBlock> blocks = BlockExtractor.extract(g, P);
        assertEquals(1, blocks.size(), "expected exactly one face, got " + blocks);
        assertEquals(20000, Math.abs(blocks.get(0).outline().signedDoubleArea()));
    }

    /** Two squares sharing an edge. Two faces, and the outer boundary must not become one. */
    @Test
    void twoAdjacentSquaresYieldTwoBlocks() {
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(100, 0)).node(new Vec2(200, 0))
                .node(new Vec2(200, 100)).node(new Vec2(100, 100)).node(new Vec2(0, 100))
                .edge(0, 1, RoadClass.ARTERIAL).edge(1, 2, RoadClass.ARTERIAL)
                .edge(2, 3, RoadClass.ARTERIAL).edge(3, 4, RoadClass.ARTERIAL)
                .edge(4, 5, RoadClass.ARTERIAL).edge(5, 0, RoadClass.ARTERIAL)
                .edge(1, 4, RoadClass.COLLECTOR)
                .build();
        assertEquals(2, BlockExtractor.extract(g, P).size());
    }

    /** A dangling spur encloses nothing and must not produce a zero-area face. */
    @Test
    void aDeadEndSpurProducesNoBlock() {
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(100, 0))
                .node(new Vec2(100, 100)).node(new Vec2(0, 100))
                .node(new Vec2(50, 200))
                .edge(0, 1, RoadClass.ARTERIAL).edge(1, 2, RoadClass.ARTERIAL)
                .edge(2, 3, RoadClass.ARTERIAL).edge(3, 0, RoadClass.ARTERIAL)
                .edge(2, 4, RoadClass.LOCAL)
                .build();
        List<CityBlock> blocks = BlockExtractor.extract(g, P);
        assertEquals(1, blocks.size(), "the spur should not enclose anything, got " + blocks);
    }

    /** A path with no cycle at all encloses nothing. */
    @Test
    void anOpenPathProducesNoBlocks() {
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(100, 0)).node(new Vec2(200, 0))
                .edge(0, 1, RoadClass.ARTERIAL).edge(1, 2, RoadClass.ARTERIAL)
                .build();
        assertTrue(BlockExtractor.extract(g, P).isEmpty());
    }

    /** Three collinear nodes must not create a degenerate face. */
    @Test
    void collinearNodesDoNotCreateASliver() {
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(50, 0)).node(new Vec2(100, 0))
                .node(new Vec2(100, 100)).node(new Vec2(0, 100))
                .edge(0, 1, RoadClass.ARTERIAL).edge(1, 2, RoadClass.ARTERIAL)
                .edge(2, 3, RoadClass.ARTERIAL).edge(3, 4, RoadClass.ARTERIAL)
                .edge(4, 0, RoadClass.ARTERIAL)
                .build();
        List<CityBlock> blocks = BlockExtractor.extract(g, P);
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).areaDoubled() > 0, "collinear run produced a zero-area block");
    }

    @Test
    void facesSmallerThanTheMinimumAreDropped() {
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(4, 0)).node(new Vec2(4, 4)).node(new Vec2(0, 4))
                .edge(0, 1, RoadClass.LOCAL).edge(1, 2, RoadClass.LOCAL)
                .edge(2, 3, RoadClass.LOCAL).edge(3, 0, RoadClass.LOCAL)
                .build();
        // 4x4 = 16 blocks of area, far under minBlockAreaBlocks (256).
        assertTrue(BlockExtractor.extract(g, P).isEmpty());
    }

    @Test
    void blocksFromARealNetworkDoNotOverlap() {
        Settlement town = new Settlement(SettlementClass.TOWN, 0, 0);
        for (long seed = 0; seed < 20; seed++) {
            RoadGraph g = ArterialGrowth.grow(seed, town, new FlatTerrain(64), P);
            List<CityBlock> blocks = BlockExtractor.extract(g, P);
            for (int i = 0; i < blocks.size(); i++) {
                for (int j = i + 1; j < blocks.size(); j++) {
                    Vec2 c = blocks.get(i).outline().boundingBox().center();
                    assertTrue(!blocks.get(j).outline().contains(c),
                            "seed " + seed + ": block " + i + " centre lies inside block " + j);
                }
            }
        }
    }

    @Test
    void extractionIsDeterministic() {
        Settlement town = new Settlement(SettlementClass.TOWN, 0, 0);
        RoadGraph g = ArterialGrowth.grow(3L, town, new FlatTerrain(64), P);
        assertEquals(BlockExtractor.extract(g, P), BlockExtractor.extract(g, P));
    }

    /**
     * Growth lays a ring segment once per ring, so two rings that pick the same pair of spoke nodes
     * emit the same road twice. The copies are geometrically identical, so no bearing can order them
     * at either endpoint; left in, they bound a zero-width two-gon that swallows the real faces on
     * both sides and the square below extracts as nothing at all.
     */
    @Test
    void aRoadLaidTwiceIsStillOneRoad() {
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(100, 0))
                .node(new Vec2(100, 100)).node(new Vec2(0, 100))
                .edge(0, 1, RoadClass.ARTERIAL).edge(1, 2, RoadClass.ARTERIAL)
                .edge(2, 3, RoadClass.ARTERIAL).edge(3, 0, RoadClass.ARTERIAL)
                .edge(1, 0, RoadClass.COLLECTOR)
                .build();
        List<CityBlock> blocks = BlockExtractor.extract(g, P);
        assertEquals(1, blocks.size(), "expected the duplicate road to collapse, got " + blocks);
        assertEquals(20000, blocks.get(0).areaDoubled());
    }

    /**
     * Euler's formula as a completeness check: a connected planar graph has exactly
     * {@code E - V + 1} bounded faces, so the traversal must find that many and no fewer. Counting
     * them is the only way to notice a traversal that quietly finds some faces and misses others —
     * every other test here would still pass.
     * <p>
     * This doubles as a planarity canary for {@link ArterialGrowth}: if growth ever starts crossing
     * roads on these seeds, the face count stops matching and this fails.
     */
    @Test
    void everyBoundedFaceIsFound() {
        PlanParams noMinimum = new PlanParams(
                P.spokeCountMin(), P.spokeCountMax(), P.ringCountMin(), P.ringCountMax(),
                P.segmentLengthBlocks(), P.snapRadiusBlocks(), P.maxSlopePerSegment(),
                P.maxBridgeSpanBlocks(), 0, P.maxLotDepthBlocks(),
                P.coreLotSizeBlocks(), P.fringeLotSizeBlocks());
        Settlement town = new Settlement(SettlementClass.TOWN, 0, 0);
        for (long seed = 0; seed < 20; seed++) {
            RoadGraph g = ArterialGrowth.grow(seed, town, new FlatTerrain(64), noMinimum);
            assertTrue(g.isConnected(), "seed " + seed + ": growth produced a disconnected network");

            Set<Long> distinctRoads = new HashSet<>();
            for (RoadEdge e : g.edges()) {
                distinctRoads.add(((long) Math.min(e.fromId(), e.toId()) << 32)
                        | Math.max(e.fromId(), e.toId()));
            }
            int circuitRank = distinctRoads.size() - g.nodes().size() + 1;

            assertEquals(circuitRank, BlockExtractor.extract(g, noMinimum).size(),
                    "seed " + seed + ": a connected planar graph has exactly E - V + 1 bounded "
                            + "faces, so the traversal found the wrong number of them");
        }
    }
}
