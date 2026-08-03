package dev.krona.urbex.plan.block;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.SettlementClass;
import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.road.ArterialGrowth;
import dev.krona.urbex.plan.road.RoadClass;
import dev.krona.urbex.plan.road.RoadEdge;
import dev.krona.urbex.plan.road.RoadGraph;
import dev.krona.urbex.plan.terrain.CliffTerrain;
import dev.krona.urbex.plan.terrain.CoastTerrain;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.HillTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockExtractorTest {

    private static final PlanParams P = PlanParams.defaults();

    private static final int SEEDS_PER_COMBINATION = 50;

    /** The same sweep space {@code PlanarityTest} uses, so both cover the same ground. */
    private static TerrainSampler[] terrains() {
        return new TerrainSampler[] {
                new FlatTerrain(64),
                new HillTerrain(64, 100, 300),
                new RiverTerrain(64, 0, 24),
                new CoastTerrain(64, 200),
                new CliffTerrain(64, 40, 0)
        };
    }

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

    /**
     * A dangling spur encloses nothing and must not produce a zero-area face.
     * <p>
     * Asserting only that one block comes out would also pass if the traversal walked the spur into
     * a separate zero-area face and the area filter then dropped it — a different, worse traversal
     * with the same visible output. The correct behaviour is that the spur is absorbed into the
     * outer face's walk, traversed out and back as part of it, so no degenerate face is ever created
     * in the first place. Check the raw walk, not just the survivors.
     */
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

        List<List<Vec2>> walked = BlockExtractor.walkFaceRings(g);
        assertEquals(2, walked.size(),
                "expected the square's face and the outer face, and nothing for the spur");
        for (List<Vec2> face : walked) {
            assertTrue(BlockExtractor.signedDoubleArea(face) != 0,
                    "the spur was walked into a zero-area face of its own instead of being "
                            + "absorbed into the face beside it: " + face);
        }
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

    /**
     * Three collinear nodes must not create a degenerate face, and the middle one must survive.
     * <p>
     * Keeping collinear points is a deliberate decision, not an accident of the traversal: a road
     * junction sitting mid-edge of a block is a real junction, and the next task's lot-frontage work
     * needs to know it is there. A well-meaning tidy-up that simplified collinear runs away would
     * leave the area untouched — the vertex contributes nothing to the shoelace sum — so an
     * area-only assertion would not notice it. Pin the vertex itself.
     */
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

        // The pentagon traces the same 100x100 square, so the collinear vertex costs no area.
        assertEquals(20000, blocks.get(0).areaDoubled());
        assertEquals(List.of(new Vec2(0, 0), new Vec2(50, 0), new Vec2(100, 0),
                        new Vec2(100, 100), new Vec2(0, 100)),
                blocks.get(0).outline().ring(),
                "the mid-run collinear vertex must be retained: lot frontage needs the junction");
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

    /**
     * The threshold is exactly {@code minBlockAreaBlocks}, in real blocks.
     * <p>
     * {@link dev.krona.urbex.plan.geom.Polygon#signedDoubleArea} returns <em>twice</em> the area, so
     * the filter compares against {@code minBlockAreaBlocks * 2}. A 4x4 square is dropped by a
     * threshold of 256, 512 or 1024 alike, so it cannot tell a missing factor of two from a correct
     * one. This pair straddles the boundary by a single block of area: 16x16 is exactly the minimum
     * and must be kept, 15x17 is one block under it and must be dropped. Lose the {@code * 2} and
     * the 15x17 case (doubled area 510, against a threshold that would then be 256) is kept.
     */
    @Test
    void theAreaThresholdIsMeasuredInRealBlocksNotDoubledOnes() {
        assertEquals(256, P.minBlockAreaBlocks(), "this test is written against the default");

        RoadGraph exactlyAtTheMinimum = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(16, 0))
                .node(new Vec2(16, 16)).node(new Vec2(0, 16))
                .edge(0, 1, RoadClass.LOCAL).edge(1, 2, RoadClass.LOCAL)
                .edge(2, 3, RoadClass.LOCAL).edge(3, 0, RoadClass.LOCAL)
                .build();
        List<CityBlock> kept = BlockExtractor.extract(exactlyAtTheMinimum, P);
        assertEquals(1, kept.size(), "16x16 is exactly minBlockAreaBlocks and must be kept");
        assertEquals(512, kept.get(0).areaDoubled());

        RoadGraph oneBlockUnder = RoadGraph.builder()
                .node(new Vec2(0, 0)).node(new Vec2(15, 0))
                .node(new Vec2(15, 17)).node(new Vec2(0, 17))
                .edge(0, 1, RoadClass.LOCAL).edge(1, 2, RoadClass.LOCAL)
                .edge(2, 3, RoadClass.LOCAL).edge(3, 0, RoadClass.LOCAL)
                .build();
        assertEquals(510, BlockExtractor.signedDoubleArea(
                        List.of(new Vec2(0, 0), new Vec2(15, 0), new Vec2(15, 17), new Vec2(0, 17))),
                "15x17 is 255 real blocks, one under the minimum");
        assertTrue(BlockExtractor.extract(oneBlockUnder, P).isEmpty(),
                "15x17 is one block under minBlockAreaBlocks and must be dropped");
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
     * every other test here would still pass on one that drops a face.
     * <p>
     * The identity is over <em>bounded faces</em>, which is not the same as extracted blocks: a
     * bounded face of zero area is still a bounded face, and extraction drops it. So the assertion
     * is {@code extracted + zeroAreaFaces == E - V + 1}, not {@code extracted == E - V + 1}. The
     * stricter form happens to hold wherever growth produces no degenerate face, which is everywhere
     * today — but writing it that way would mean that the first graph with a degenerate face fails
     * this test and blames the traversal for something that is not its doing. Stated robustly, it
     * can be swept across every class and terrain, which is the point of having it.
     * <p>
     * A tree is the one shape the identity does not cover: its single face is the outer face and has
     * zero area, so there are no bounded faces at all and nothing to count. Handled explicitly.
     * <p>
     * This doubles as a planarity canary for {@link ArterialGrowth} and {@code Planarizer}: if
     * growth ever starts crossing roads on these seeds, the face count stops matching and this
     * fails.
     */
    @Test
    void everyBoundedFaceIsFound() {
        PlanParams noMinimum = new PlanParams(
                P.spokeCountMin(), P.spokeCountMax(), P.ringCountMin(), P.ringCountMax(),
                P.segmentLengthBlocks(), P.snapRadiusBlocks(), P.maxSlopePerSegment(),
                P.maxBridgeSpanBlocks(), 0, P.maxLotDepthBlocks(),
                P.coreLotSizeBlocks(), P.fringeLotSizeBlocks());

        for (SettlementClass cls : SettlementClass.values()) {
            for (TerrainSampler terrain : terrains()) {
                for (long seed = 0; seed < SEEDS_PER_COMBINATION; seed++) {
                    String where = cls + "/" + terrain.getClass().getSimpleName() + "/seed" + seed;
                    RoadGraph g = ArterialGrowth.grow(seed, new Settlement(cls, 0, 0), terrain,
                            noMinimum);
                    assertTrue(g.isConnected(), where + ": growth produced a disconnected network");

                    Set<Long> distinctRoads = new HashSet<>();
                    for (RoadEdge e : g.edges()) {
                        distinctRoads.add(((long) Math.min(e.fromId(), e.toId()) << 32)
                                | Math.max(e.fromId(), e.toId()));
                    }
                    int circuitRank = distinctRoads.size() - g.nodes().size() + 1;

                    int extracted = BlockExtractor.extract(g, noMinimum).size();
                    int zeroArea = 0;
                    int outer = 0;
                    for (List<Vec2> face : BlockExtractor.walkFaceRings(g)) {
                        long area = BlockExtractor.signedDoubleArea(face);
                        if (area == 0) {
                            zeroArea++;
                        } else if (area < 0) {
                            outer++;
                        }
                    }

                    if (circuitRank == 0) {
                        // A tree, or a lone node with no roads at all. No bounded faces exist.
                        assertEquals(0, extracted, where + ": an acyclic network encloses nothing");
                        continue;
                    }

                    assertEquals(circuitRank, extracted + zeroArea,
                            where + ": a connected planar graph has exactly E - V + 1 bounded "
                                    + "faces (" + circuitRank + "), but the traversal found "
                                    + extracted + " with area and " + zeroArea + " degenerate");
                    assertEquals(1, outer,
                            where + ": a connected graph with a cycle has exactly one outer face");
                }
            }
        }
    }
}
