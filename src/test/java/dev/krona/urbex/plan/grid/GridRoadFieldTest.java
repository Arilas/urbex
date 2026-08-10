package dev.krona.urbex.plan.grid;

import dev.krona.urbex.plan.RoadCell;
import dev.krona.urbex.plan.RoadDirection;
import dev.krona.urbex.plan.RoadType;
import dev.krona.urbex.plan.TertiarySegment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridRoadFieldTest {

    private static GridRoadField field(long seed) {
        return new GridRoadField(seed, "urbex:test", GridSettings.defaults());
    }

    private static String sample(GridRoadField f, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int x = from; x < to; x++) {
            for (int z = from; z < to; z++) {
                sb.append(f.at(x, z).type().ordinal());
            }
        }
        return sb.toString();
    }

    @Test
    void sameInputsReproduceTheFieldExactly() {
        assertEquals(sample(field(1337L), -40, 40), sample(field(1337L), -40, 40));
    }

    @Test
    void changingTheSeedChangesTheField() {
        assertNotEquals(sample(field(1337L), -40, 40), sample(field(9001L), -40, 40));
    }

    @Test
    void changingTheDimensionChangesTheField() {
        GridRoadField a = new GridRoadField(1337L, "urbex:one", GridSettings.defaults());
        GridRoadField b = new GridRoadField(1337L, "urbex:two", GridSettings.defaults());
        assertNotEquals(sample(a, -40, 40), sample(b, -40, 40));
    }

    @Test
    void queryOrderCannotChangeTheAnswer() {
        GridRoadField f = field(1337L);
        List<int[]> coords = new ArrayList<>();
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                coords.add(new int[]{x, z});
            }
        }
        String rowMajor = sample(f, -40, 40);

        GridRoadField shuffledField = field(1337L);
        Collections.shuffle(coords, new java.util.Random(42));
        for (int[] c : coords) {
            shuffledField.at(c[0], c[1]);
        }
        assertEquals(rowMajor, sample(shuffledField, -40, 40),
                "a shuffled warm-up must not change later answers");
    }

    @Test
    void typeAtAgreesWithTheFullCellEverywhere() {
        // typeAt skips the block layout for primary roads, which is what makes the four neighbour
        // probes in at() and the multi-building footprint scan affordable. It is only a shortcut if
        // it answers identically, so sweep both against each other.
        GridRoadField f = field(1337L);
        for (int x = -64; x < 64; x++) {
            for (int z = -64; z < 64; z++) {
                assertEquals(f.at(x, z).type(), f.typeAt(x, z), "typeAt disagrees at " + x + "," + z);
            }
        }
    }

    @Test
    void anActivePrimaryIsStraightAndContinuous() {
        GridRoadField f = field(1337L);
        int found = 0;
        for (int x = -64; x < 64; x++) {
            if (f.at(x, 0).type() != RoadType.PRIMARY) {
                continue;
            }
            boolean verticalEverywhere = true;
            for (int z = -64; z < 64; z++) {
                if (f.at(x, z).type() != RoadType.PRIMARY) {
                    verticalEverywhere = false;
                    break;
                }
            }
            if (verticalEverywhere) {
                found++;
            }
        }
        assertTrue(found > 0, "expected at least one continuous vertical primary corridor");
    }

    @Test
    void thereIsNoSeamAtCoordinateZero() {
        // Containment, not just non-inverted bounds: truncating division instead of floorDiv would
        // still leave eastX >= westX, but it would assign a chunk to a block it isn't inside.
        GridRoadField f = field(1337L);
        for (int x = -64; x < 64; x++) {
            for (int z = -64; z < 64; z++) {
                RoadCell cell = f.at(x, z);
                assertTrue(cell.westX() <= x && x < cell.eastX(),
                        "chunk x=" + x + " outside its primary block [" + cell.westX() + "," + cell.eastX() + ")");
                assertTrue(cell.northZ() <= z && z < cell.southZ(),
                        "chunk z=" + z + " outside its primary block [" + cell.northZ() + "," + cell.southZ() + ")");
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Step 2: primary spacing invariants
    // ---------------------------------------------------------------------------------------

    /**
     * A z whose row is not itself an active horizontal primary line, found empirically: if any
     * probed x along it fails to read {@code PRIMARY}, that z cannot be one (a horizontal primary
     * would make every x read {@code PRIMARY}). Needed so that sweeping x for vertical primaries is
     * not contaminated by a horizontal line that would make the whole row read {@code PRIMARY}
     * regardless of x.
     */
    private static int findWitnessZ(GridRoadField f) {
        for (int z = 0; z < 8; z++) {
            for (int x = -500; x < 500; x += 13) {
                if (f.typeAt(x, z) != RoadType.PRIMARY) {
                    return z;
                }
            }
        }
        throw new IllegalStateException("no z found that is free of a horizontal primary line");
    }

    /** The horizontal-axis mirror of {@link #findWitnessZ}. */
    private static int findWitnessX(GridRoadField f) {
        for (int x = 0; x < 8; x++) {
            for (int z = -500; z < 500; z += 13) {
                if (f.typeAt(x, z) != RoadType.PRIMARY) {
                    return x;
                }
            }
        }
        throw new IllegalStateException("no x found that is free of a vertical primary line");
    }

    @Test
    void primaryVerticalCorridorsObeySpacingAndForceInvariants() {
        GridSettings settings = GridSettings.defaults();
        GridRoadField f = field(1337L);
        int spacing = settings.primarySpacingX();
        int forceEvery = settings.primaryForceEvery();
        int witnessZ = findWitnessZ(f);

        List<Integer> corridors = new ArrayList<>();
        for (int x = -300; x < 300; x++) {
            if (f.typeAt(x, witnessZ) == RoadType.PRIMARY) {
                corridors.add(x);
            }
        }
        assertTrue(corridors.size() > 4, "expected multiple vertical primary corridors in range");

        for (int i = 1; i < corridors.size(); i++) {
            int gap = corridors.get(i) - corridors.get(i - 1);
            assertTrue(gap > 0, "corridors must be strictly increasing");
            assertEquals(0, gap % spacing, "gap " + gap + " between x=" + corridors.get(i - 1)
                    + " and x=" + corridors.get(i) + " is not a multiple of primarySpacingX");
            assertTrue(gap <= (long) spacing * forceEvery,
                    "gap " + gap + " between x=" + corridors.get(i - 1) + " and x=" + corridors.get(i)
                            + " exceeds primarySpacingX * primaryForceEvery");
        }

        // Every primaryForceEvery-th candidate must be active. blockX is the diagnostic candidate
        // index behind each active line (see RoadCell's javadoc); reading it here, rather than
        // re-deriving the offset by hand, checks the type-flag path (typeAt, used above) and the
        // block-boundary path (blockX) agree on which candidates are active.
        Set<Integer> candidateIndices = new HashSet<>();
        for (int x : corridors) {
            candidateIndices.add(f.at(x, witnessZ).blockX());
        }
        int minK = Collections.min(candidateIndices);
        int maxK = Collections.max(candidateIndices);
        for (int k = minK; k <= maxK; k++) {
            if (Math.floorMod(k, forceEvery) == 0) {
                assertTrue(candidateIndices.contains(k),
                        "forced candidate " + k + " (every " + forceEvery + "th) must be active");
            }
        }
    }

    @Test
    void primaryHorizontalCorridorsObeySpacingAndForceInvariants() {
        GridSettings settings = GridSettings.defaults();
        GridRoadField f = field(1337L);
        int spacing = settings.primarySpacingZ();
        int forceEvery = settings.primaryForceEvery();
        int witnessX = findWitnessX(f);

        List<Integer> corridors = new ArrayList<>();
        for (int z = -300; z < 300; z++) {
            if (f.typeAt(witnessX, z) == RoadType.PRIMARY) {
                corridors.add(z);
            }
        }
        assertTrue(corridors.size() > 4, "expected multiple horizontal primary corridors in range");

        for (int i = 1; i < corridors.size(); i++) {
            int gap = corridors.get(i) - corridors.get(i - 1);
            assertTrue(gap > 0, "corridors must be strictly increasing");
            assertEquals(0, gap % spacing, "gap " + gap + " between z=" + corridors.get(i - 1)
                    + " and z=" + corridors.get(i) + " is not a multiple of primarySpacingZ");
            assertTrue(gap <= (long) spacing * forceEvery,
                    "gap " + gap + " between z=" + corridors.get(i - 1) + " and z=" + corridors.get(i)
                            + " exceeds primarySpacingZ * primaryForceEvery");
        }

        Set<Integer> candidateIndices = new HashSet<>();
        for (int z : corridors) {
            candidateIndices.add(f.at(witnessX, z).blockZ());
        }
        int minK = Collections.min(candidateIndices);
        int maxK = Collections.max(candidateIndices);
        for (int k = minK; k <= maxK; k++) {
            if (Math.floorMod(k, forceEvery) == 0) {
                assertTrue(candidateIndices.contains(k),
                        "forced candidate " + k + " (every " + forceEvery + "th) must be active");
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Step 3: secondary road invariants
    // ---------------------------------------------------------------------------------------

    @Test
    void secondaryRoadsSpanTheirBlockTouchBothBoundariesAndRespectSeparationAndEdgeDistance() {
        GridSettings settings = GridSettings.defaults();
        GridRoadField f = field(1337L);
        Set<Long> seenBlocks = new HashSet<>();
        int blocksWithSecondaries = 0;
        for (int x = -80; x < 80; x++) {
            for (int z = -80; z < 80; z++) {
                RoadCell cell = f.at(x, z);
                long blockKey = (((long) cell.blockX()) << 32) ^ (cell.blockZ() & 0xffffffffL);
                if (!seenBlocks.add(blockKey)) {
                    continue;
                }
                if (cell.secondaryX().isEmpty() && cell.secondaryZ().isEmpty()) {
                    continue;
                }
                blocksWithSecondaries++;
                assertSecondaryXInvariants(f, settings, cell);
                assertSecondaryZInvariants(f, settings, cell);
            }
        }
        assertTrue(blocksWithSecondaries >= 5,
                "expected several distinct blocks with secondary roads, found " + blocksWithSecondaries);
    }

    private static void assertSecondaryXInvariants(GridRoadField f, GridSettings settings, RoadCell cell) {
        List<Integer> xs = cell.secondaryX();
        for (int i = 0; i < xs.size(); i++) {
            int sx = xs.get(i);
            for (int z = cell.northZ() + 1; z < cell.southZ(); z++) {
                assertEquals(RoadType.SECONDARY, f.at(sx, z).type(),
                        "secondary x=" + sx + " must span the full block depth, failed at z=" + z);
            }
            if (i > 0) {
                assertTrue(xs.get(i) - xs.get(i - 1) >= settings.minimumRoadSeparation(),
                        "secondary x positions " + xs.get(i - 1) + "," + xs.get(i)
                                + " are closer than minimumRoadSeparation");
            }
        }
        if (!xs.isEmpty()) {
            assertTrue(xs.get(0) - cell.westX() >= settings.minimumEdgeDistance(),
                    "first secondary x=" + xs.get(0) + " too close to west boundary " + cell.westX());
            assertTrue(cell.eastX() - xs.get(xs.size() - 1) >= settings.minimumEdgeDistance(),
                    "last secondary x=" + xs.get(xs.size() - 1) + " too close to east boundary " + cell.eastX());
        }
    }

    private static void assertSecondaryZInvariants(GridRoadField f, GridSettings settings, RoadCell cell) {
        List<Integer> zs = cell.secondaryZ();
        for (int i = 0; i < zs.size(); i++) {
            int sz = zs.get(i);
            for (int x = cell.westX() + 1; x < cell.eastX(); x++) {
                assertEquals(RoadType.SECONDARY, f.at(x, sz).type(),
                        "secondary z=" + sz + " must span the full block width, failed at x=" + x);
            }
            if (i > 0) {
                assertTrue(zs.get(i) - zs.get(i - 1) >= settings.minimumRoadSeparation(),
                        "secondary z positions " + zs.get(i - 1) + "," + zs.get(i)
                                + " are closer than minimumRoadSeparation");
            }
        }
        if (!zs.isEmpty()) {
            assertTrue(zs.get(0) - cell.northZ() >= settings.minimumEdgeDistance(),
                    "first secondary z=" + zs.get(0) + " too close to north boundary " + cell.northZ());
            assertTrue(cell.southZ() - zs.get(zs.size() - 1) >= settings.minimumEdgeDistance(),
                    "last secondary z=" + zs.get(zs.size() - 1) + " too close to south boundary " + cell.southZ());
        }
    }

    @Test
    void demandingMoreSecondaryRoadsThanFitYieldsFewerRatherThanThrowing() {
        // A default-width primary block (8 wide, since primaryForceEvery=4 caps the search at
        // one forced candidate away in the worst case) cannot possibly fit 128 roads at
        // minimumRoadSeparation=4; the count must fall back to whatever actually fits instead of
        // throwing or returning the full demand.
        GridSettings crowded = new GridSettings(8, 8, 0.45f, 4, 128, 128, 128, 128, 4, 3, 0.40f, 2, 5);
        GridRoadField f = new GridRoadField(1337L, "urbex:test", crowded);

        RoadCell cell = assertDoesNotThrow(() -> f.at(5, 5), "an oversubscribed block must still resolve");
        assertTrue(cell.secondaryX().size() < 128,
                "expected far fewer than 128 secondary-x positions to fit, got " + cell.secondaryX().size());
        assertTrue(cell.secondaryZ().size() < 128,
                "expected far fewer than 128 secondary-z positions to fit, got " + cell.secondaryZ().size());
    }

    // ---------------------------------------------------------------------------------------
    // Step 4: tertiary road invariants
    // ---------------------------------------------------------------------------------------

    private static List<Integer> roadList(int start, List<Integer> middle, int end) {
        List<Integer> list = new ArrayList<>(middle.size() + 2);
        list.add(start);
        list.addAll(middle);
        list.add(end);
        Collections.sort(list);
        return list;
    }

    /** Mirrors {@code GridRoadField.findCell}: the index {@code i} with {@code roads[i] < c < roads[i+1]}. */
    private static int bracket(List<Integer> roads, int coordinate) {
        for (int i = 0; i < roads.size() - 1; i++) {
            if (roads.get(i) < coordinate && coordinate < roads.get(i + 1)) {
                return i;
            }
        }
        return -1;
    }

    private static Map<Long, TertiarySegment> findTertiarySegments(GridRoadField f, int from, int to) {
        Map<Long, TertiarySegment> segments = new LinkedHashMap<>();
        for (int x = from; x < to; x++) {
            for (int z = from; z < to; z++) {
                RoadCell cell = f.at(x, z);
                if (cell.type() == RoadType.TERTIARY) {
                    TertiarySegment seg = cell.tertiary();
                    segments.putIfAbsent(seg.id(), seg);
                }
            }
        }
        return segments;
    }

    @Test
    void tertiarySegmentsAreContiguousClearOfIntersectionsAndLeaveAGapBeforeTheOppositeRoad() {
        GridRoadField f = field(1337L);
        Map<Long, TertiarySegment> segments = findTertiarySegments(f, -80, 80);
        assertTrue(segments.size() >= 3, "expected several distinct tertiary segments in range, found "
                + segments.size());

        for (TertiarySegment seg : segments.values()) {
            assertTertiaryInvariants(f, seg);
        }
    }

    private static void assertTertiaryInvariants(GridRoadField f, TertiarySegment seg) {
        int originX = seg.originX();
        int originZ = seg.originZ();
        RoadDirection dir = seg.direction();
        int length = seg.length();

        RoadType originType = f.at(originX, originZ).type();
        assertTrue(originType == RoadType.PRIMARY || originType == RoadType.SECONDARY,
                "tertiary origin (" + originX + "," + originZ + ") must sit on a primary or secondary road, was "
                        + originType);

        for (int d = 1; d <= length; d++) {
            int cx = originX + dir.stepX() * d;
            int cz = originZ + dir.stepZ() * d;
            RoadCell cell = f.at(cx, cz);
            assertEquals(RoadType.TERTIARY, cell.type(),
                    "chunk " + d + " along the segment from (" + originX + "," + originZ + ") must be tertiary");
            assertEquals(seg.id(), cell.tertiary().id(), "chunk " + d + " must belong to the same segment");
        }
        int pastX = originX + dir.stepX() * (length + 1);
        int pastZ = originZ + dir.stepZ() * (length + 1);
        assertNotEquals(RoadType.TERTIARY, f.at(pastX, pastZ).type(),
                "the chunk one past the end of the segment must not be tertiary");

        // Reconstruct the cell's bounding roads from a point one step into the segment: guaranteed
        // interior to the enclosing primary block even when the cell's own boundary happens to sit
        // exactly on that block's outer edge, which querying at the origin itself would risk.
        int probeX = originX + dir.stepX();
        int probeZ = originZ + dir.stepZ();
        RoadCell probeCell = f.at(probeX, probeZ);
        List<Integer> xRoads = roadList(probeCell.westX(), probeCell.secondaryX(), probeCell.eastX());
        List<Integer> zRoads = roadList(probeCell.northZ(), probeCell.secondaryZ(), probeCell.southZ());

        boolean vertical = dir == RoadDirection.NORTH || dir == RoadDirection.SOUTH;
        if (vertical) {
            int cellIndex = bracket(xRoads, originX);
            assertTrue(cellIndex >= 0, "origin x=" + originX + " must sit strictly inside a cell of " + xRoads);
            int x0 = xRoads.get(cellIndex);
            int x1 = xRoads.get(cellIndex + 1);
            assertTrue(originX - x0 >= 2, "origin must stay 2 chunks clear of the near cross-road");
            assertTrue(x1 - originX >= 2, "origin must stay 2 chunks clear of the far cross-road");

            int zIndex = zRoads.indexOf(originZ);
            assertTrue(zIndex >= 0, "origin z=" + originZ + " must be one of the cell's bounding roads " + zRoads);
            int oppositeZ = dir == RoadDirection.SOUTH ? zRoads.get(zIndex + 1) : zRoads.get(zIndex - 1);
            int farthestZ = originZ + dir.stepZ() * length;
            assertTrue(Math.abs(oppositeZ - farthestZ) >= 2,
                    "segment must leave at least one non-road chunk before the opposite road at z=" + oppositeZ);
        } else {
            int cellIndex = bracket(zRoads, originZ);
            assertTrue(cellIndex >= 0, "origin z=" + originZ + " must sit strictly inside a cell of " + zRoads);
            int z0 = zRoads.get(cellIndex);
            int z1 = zRoads.get(cellIndex + 1);
            assertTrue(originZ - z0 >= 2, "origin must stay 2 chunks clear of the near cross-road");
            assertTrue(z1 - originZ >= 2, "origin must stay 2 chunks clear of the far cross-road");

            int xIndex = xRoads.indexOf(originX);
            assertTrue(xIndex >= 0, "origin x=" + originX + " must be one of the cell's bounding roads " + xRoads);
            int oppositeX = dir == RoadDirection.EAST ? xRoads.get(xIndex + 1) : xRoads.get(xIndex - 1);
            int farthestX = originX + dir.stepX() * length;
            assertTrue(Math.abs(oppositeX - farthestX) >= 2,
                    "segment must leave at least one non-road chunk before the opposite road at x=" + oppositeX);
        }
    }

    @Test
    void aCellWhoseFirstChoiceSideCannotFitFallsThroughToAFittingSideRatherThanLosingItsAccessRoad() {
        // tertiaryChance=1.0 removes the "did this cell even want a tertiary" roll from the
        // picture: Hash.unit(...) never reaches 1.0, so the identity check can never refuse,
        // leaving fit and fallback as the only reasons a geometrically-eligible cell could end up
        // with no segment. Small, tightly packed cells (2 secondaries forced per axis, minimal
        // separation and edge distance) put many cells in a position where at least one of the
        // four sides is too small to host a tertiary - exactly the situation the fallback exists
        // for. Every such cell is enumerated directly from the block's own road lists, not just
        // the ones a scan happens to find a segment in, so a cell that silently lost its access
        // road is visible as a failure rather than simply missing from a found-segments count.
        GridSettings crowded = new GridSettings(8, 8, 0.45f, 4, 2, 2, 2, 2, 2, 2, 1.0f, 1, 3);
        GridRoadField f = new GridRoadField(1337L, "urbex:test", crowded);

        Set<Long> seenBlocks = new HashSet<>();
        int cellsChecked = 0;
        int constrainedCellsSeen = 0;
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                RoadCell cell = f.at(x, z);
                long blockKey = (((long) cell.blockX()) << 32) ^ (cell.blockZ() & 0xffffffffL);
                if (!seenBlocks.add(blockKey)) {
                    continue;
                }
                List<Integer> xRoads = roadList(cell.westX(), cell.secondaryX(), cell.eastX());
                List<Integer> zRoads = roadList(cell.northZ(), cell.secondaryZ(), cell.southZ());
                for (int xi = 0; xi < xRoads.size() - 1; xi++) {
                    for (int zi = 0; zi < zRoads.size() - 1; zi++) {
                        int x0 = xRoads.get(xi);
                        int x1 = xRoads.get(xi + 1);
                        int z0 = zRoads.get(zi);
                        int z1 = zRoads.get(zi + 1);

                        int fitCount = 0;
                        for (RoadDirection d : RoadDirection.values()) {
                            boolean vertical = d == RoadDirection.NORTH || d == RoadDirection.SOUTH;
                            int transverseSpan = vertical ? x1 - x0 : z1 - z0;
                            int inwardSpan = vertical ? z1 - z0 : x1 - x0;
                            if (transverseSpan >= 4 && inwardSpan - 2 >= crowded.tertiaryMinLength()) {
                                fitCount++;
                            }
                        }
                        if (fitCount == 0) {
                            continue;
                        }
                        cellsChecked++;
                        if (fitCount < 4) {
                            constrainedCellsSeen++;
                        }

                        boolean found = false;
                        search:
                        for (int cx = x0 + 1; cx < x1; cx++) {
                            for (int cz = z0 + 1; cz < z1; cz++) {
                                if (f.at(cx, cz).type() == RoadType.TERTIARY) {
                                    found = true;
                                    break search;
                                }
                            }
                        }
                        assertTrue(found, "cell [" + x0 + "," + x1 + ")x[" + z0 + "," + z1 + ") has a fitting side ("
                                + fitCount + "/4) and tertiaryChance=1.0, but no tertiary chunk was found in it");
                    }
                }
            }
        }
        assertTrue(cellsChecked >= 10, "expected to examine several eligible cells, examined " + cellsChecked);
        assertTrue(constrainedCellsSeen > 0,
                "expected at least one cell where not all four sides fit, to actually exercise the fallback");
    }
}
