package dev.krona.urbex.plan.block;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.geom.Polygon;
import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.road.RoadEdge;
import dev.krona.urbex.plan.road.RoadGraph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a road network into city blocks by finding the enclosed faces of the graph.
 * <p>
 * Standard planar face traversal. Every undirected road becomes two directed half-edges; at each
 * node the outgoing half-edges are sorted by bearing; arriving at {@code v} along {@code u -> v} the
 * walk leaves along the half-edge immediately <em>clockwise</em> from the reverse {@code v -> u}.
 * The orbits of that permutation are exactly the faces of the embedding: every interior face comes
 * out counter-clockwise (positive signed area) and the outer face of each connected component comes
 * out clockwise (negative), so the outer face is discarded by its winding.
 * <p>
 * <b>Planarity is assumed, not verified.</b> {@link dev.krona.urbex.plan.road.ArterialGrowth} snaps
 * to nearby nodes rather than crossing edges, so crossings should not occur, but nothing here
 * detects one. If two roads did cross without a node at the crossing, the traversal would still
 * terminate and still produce closed rings, but those rings could overlap in space and one of them
 * could enclose another; the winding assertion in {@link #assertOuterFaceIsTheLargest} is the only
 * tripwire, and it only catches the case where a bogus face grows larger than the true boundary.
 * <p>
 * Everything here is deterministic. Bearings are compared with exact integer arithmetic rather than
 * {@link Math#atan2}, so the ordering cannot shift with a platform's floating-point library, and the
 * surviving faces are sorted geometrically before ids are assigned, so half-edge iteration order
 * cannot leak into the output.
 */
public final class BlockExtractor {

    private BlockExtractor() {
    }

    public static List<CityBlock> extract(RoadGraph graph, PlanParams params) {
        HalfEdges halfEdges = HalfEdges.of(graph);
        List<List<Vec2>> faces = halfEdges.walkFaces();

        assertOuterFaceIsTheLargest(faces);

        long minAreaDoubled = (long) params.minBlockAreaBlocks() * 2L;
        List<Polygon> kept = new ArrayList<>();
        for (List<Vec2> face : faces) {
            long area = signedDoubleArea(face);
            // Step 5: the outer face winds the other way. Zero-area walks — a spur traversed out and
            // back, a path with no cycle at all — are neither, and fall out here too.
            if (area <= 0) {
                continue;
            }
            // Step 6: too small to build on.
            if (area < minAreaDoubled) {
                continue;
            }
            // Step 7: a ring that never encloses anything cannot be a polygon. Positive area already
            // implies three distinct points; this is the guard that makes that implication safe.
            if (face.size() < 3 || distinctPointCount(face) < 3) {
                continue;
            }
            kept.add(new Polygon(canonicalRotation(face)));
        }

        // Step 8: sort before assigning ids, so ids describe the map rather than the walk.
        kept.sort(BlockExtractor::comparePolygons);

        List<CityBlock> blocks = new ArrayList<>(kept.size());
        for (int i = 0; i < kept.size(); i++) {
            blocks.add(new CityBlock(i, kept.get(i)));
        }
        return List.copyOf(blocks);
    }

    /**
     * The outer face is identified two ways — by winding and by being the largest — and the two must
     * agree. The magnitude of a component's outer face is the sum of its interior faces, so no
     * interior face can be strictly larger than every outer face. If one is, either the winding rule
     * or the traversal is wrong, and it is far cheaper to hear about it here than to find an
     * enormous city block covering the whole town in the viewer.
     * <p>
     * Faces are compared by absolute area rather than by picking a single largest, because a graph
     * with exactly one interior face ties its outer face exactly and there is no reason to make an
     * arbitrary choice between them.
     */
    private static void assertOuterFaceIsTheLargest(List<List<Vec2>> faces) {
        long largestAbsolute = 0;
        long largestClockwise = 0;
        for (List<Vec2> face : faces) {
            long area = signedDoubleArea(face);
            long magnitude = Math.abs(area);
            largestAbsolute = Math.max(largestAbsolute, magnitude);
            if (area < 0) {
                largestClockwise = Math.max(largestClockwise, magnitude);
            }
        }
        if (largestAbsolute > 0 && largestAbsolute != largestClockwise) {
            throw new IllegalStateException(
                    "face traversal is inconsistent: the largest face by absolute area is "
                            + largestAbsolute + " but the largest clockwise (outer) face is only "
                            + largestClockwise + ", so the winding test and the largest-area test "
                            + "disagree about which face is the outer one. An interior face cannot "
                            + "outgrow its component's boundary in a planar graph, so the input is "
                            + "almost certainly not planar: two roads cross without a node at the "
                            + "crossing. Look at the road graph, not at this traversal.");
        }
    }

    /** Twice the signed area of an arbitrary ring, including degenerate ones a Polygon would reject. */
    private static long signedDoubleArea(List<Vec2> ring) {
        long total = 0;
        for (int i = 0; i < ring.size(); i++) {
            Vec2 a = ring.get(i);
            Vec2 b = ring.get((i + 1) % ring.size());
            total += (long) a.x() * b.z() - (long) b.x() * a.z();
        }
        return total;
    }

    private static int distinctPointCount(List<Vec2> ring) {
        int distinct = 0;
        for (int i = 0; i < ring.size(); i++) {
            boolean seenEarlier = false;
            for (int j = 0; j < i; j++) {
                if (ring.get(i).equals(ring.get(j))) {
                    seenEarlier = true;
                    break;
                }
            }
            if (!seenEarlier) {
                distinct++;
            }
        }
        return distinct;
    }

    /**
     * Rotates a ring to start at its lowest point in (x, z) order. The walk's starting half-edge is
     * already deterministic, but a ring that does not depend on it at all is one less way for edge
     * insertion order to reach the output.
     */
    private static List<Vec2> canonicalRotation(List<Vec2> ring) {
        int start = 0;
        for (int i = 1; i < ring.size(); i++) {
            if (comparePoints(ring.get(i), ring.get(start)) < 0) {
                start = i;
            }
        }
        List<Vec2> rotated = new ArrayList<>(ring.size());
        for (int i = 0; i < ring.size(); i++) {
            rotated.add(ring.get((start + i) % ring.size()));
        }
        return rotated;
    }

    private static int comparePoints(Vec2 a, Vec2 b) {
        int byX = Integer.compare(a.x(), b.x());
        return byX != 0 ? byX : Integer.compare(a.z(), b.z());
    }

    /** A total order: bounding box first, then area, then the ring itself, so no tie is left open. */
    private static int comparePolygons(Polygon a, Polygon b) {
        Rect ra = a.boundingBox();
        Rect rb = b.boundingBox();
        int cmp = Integer.compare(ra.minX(), rb.minX());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(ra.minZ(), rb.minZ());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(ra.maxX(), rb.maxX());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(ra.maxZ(), rb.maxZ());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Long.compare(a.signedDoubleArea(), b.signedDoubleArea());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(a.ring().size(), b.ring().size());
        if (cmp != 0) {
            return cmp;
        }
        for (int i = 0; i < a.ring().size(); i++) {
            cmp = comparePoints(a.ring().get(i), b.ring().get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * The directed half-edge structure. Half-edge {@code 2e} runs along road {@code e} from its
     * {@code fromId} to its {@code toId} and {@code 2e + 1} runs back, so the reverse of {@code h} is
     * always {@code h ^ 1}.
     */
    private static final class HalfEdges {

        private final int[] from;
        private final int[] to;
        private final Vec2[] positions;
        /** Outgoing half-edges per node, sorted by bearing, ascending. */
        private final int[][] outgoing;
        /** Where each half-edge sits in its origin node's sorted list. */
        private final int[] slot;

        private HalfEdges(int[] from, int[] to, Vec2[] positions, int[][] outgoing, int[] slot) {
            this.from = from;
            this.to = to;
            this.positions = positions;
            this.outgoing = outgoing;
            this.slot = slot;
        }

        static HalfEdges of(RoadGraph graph) {
            int nodeCount = graph.nodes().size();
            Vec2[] positions = new Vec2[nodeCount];
            for (int i = 0; i < nodeCount; i++) {
                positions[i] = graph.nodeAt(i).pos();
            }

            List<RoadEdge> usable = new ArrayList<>(graph.edges().size());
            Set<Long> seenPairs = new HashSet<>();
            for (RoadEdge e : graph.edges()) {
                // A road with no length has no bearing, so no place in the rotation at its endpoints.
                // Neither growth nor the bridge pass produces one, but one would corrupt the ordering
                // silently rather than fail.
                if (e.fromId() == e.toId() || positions[e.fromId()].equals(positions[e.toId()])) {
                    continue;
                }
                // Two roads between the same pair of nodes are the same road: growth emits a ring
                // segment once per ring, so two rings that pick the same pair of spoke nodes lay the
                // same tarmac twice. Kept, the copies are geometrically identical, which leaves the
                // rotation at both endpoints with a tie that no bearing can break — and whichever way
                // it breaks, the copies bound a zero-width two-gon that swallows the real faces
                // either side of them. Collapsing them is not a tidy-up; it is what makes the
                // embedding well defined. (Membership only — this set is never iterated.)
                long low = Math.min(e.fromId(), e.toId());
                long high = Math.max(e.fromId(), e.toId());
                if (!seenPairs.add((low << 32) | high)) {
                    continue;
                }
                usable.add(e);
            }

            int count = usable.size() * 2;
            int[] from = new int[count];
            int[] to = new int[count];
            int[] degree = new int[nodeCount];
            for (int i = 0; i < usable.size(); i++) {
                RoadEdge e = usable.get(i);
                from[2 * i] = e.fromId();
                to[2 * i] = e.toId();
                from[2 * i + 1] = e.toId();
                to[2 * i + 1] = e.fromId();
                degree[e.fromId()]++;
                degree[e.toId()]++;
            }

            int[][] outgoing = new int[nodeCount][];
            int[] filled = new int[nodeCount];
            for (int n = 0; n < nodeCount; n++) {
                outgoing[n] = new int[degree[n]];
            }
            for (int h = 0; h < count; h++) {
                outgoing[from[h]][filled[from[h]]++] = h;
            }

            HalfEdges result = new HalfEdges(from, to, positions, outgoing, new int[count]);
            for (int n = 0; n < nodeCount; n++) {
                result.sortByBearing(outgoing[n]);
                for (int i = 0; i < outgoing[n].length; i++) {
                    result.slot[outgoing[n][i]] = i;
                }
            }
            return result;
        }

        /** Insertion sort: node degrees are single digits, and it keeps the comparator honest. */
        private void sortByBearing(int[] halfEdges) {
            for (int i = 1; i < halfEdges.length; i++) {
                int h = halfEdges[i];
                int j = i - 1;
                while (j >= 0 && compareBearing(halfEdges[j], h) > 0) {
                    halfEdges[j + 1] = halfEdges[j];
                    j--;
                }
                halfEdges[j + 1] = h;
            }
        }

        /**
         * Orders two half-edges leaving the same node the way {@code Math.atan2(dz, dx)} would, but
         * in exact integer arithmetic: first by which quadrant-pair the direction falls in, then by
         * the sign of the cross product within it. The half-plane split guarantees any two
         * directions compared by cross product are less than half a turn apart, which is what makes
         * the cross product's sign a valid ordering.
         */
        private int compareBearing(int a, int b) {
            int ax = positions[to[a]].x() - positions[from[a]].x();
            int az = positions[to[a]].z() - positions[from[a]].z();
            int bx = positions[to[b]].x() - positions[from[b]].x();
            int bz = positions[to[b]].z() - positions[from[b]].z();

            int rankA = bearingRank(ax, az);
            int rankB = bearingRank(bx, bz);
            if (rankA != rankB) {
                return Integer.compare(rankA, rankB);
            }
            if (rankA == 0 || rankA == 2) {
                long cross = (long) ax * bz - (long) az * bx;
                if (cross > 0) {
                    return -1;
                }
                if (cross < 0) {
                    return 1;
                }
            }
            // Exactly parallel — two roads leaving a node in the same direction. Geometrically there
            // is no right answer, so pick a stable one.
            int byTarget = Integer.compare(to[a], to[b]);
            return byTarget != 0 ? byTarget : Integer.compare(a, b);
        }

        /** atan2 lands in (-pi, pi]; these are its four monotone bands, in ascending order. */
        private static int bearingRank(int dx, int dz) {
            if (dz < 0) {
                return 0;          // (-pi, 0)
            }
            if (dz == 0) {
                return dx > 0 ? 1 : 3;  // exactly 0, or exactly pi
            }
            return 2;              // (0, pi)
        }

        /**
         * Walks every face. Each half-edge belongs to exactly one face, so the orbits partition the
         * half-edges and the total work is linear in the number of roads.
         */
        List<List<Vec2>> walkFaces() {
            boolean[] visited = new boolean[from.length];
            List<List<Vec2>> faces = new ArrayList<>();
            for (int start = 0; start < from.length; start++) {
                if (visited[start]) {
                    continue;
                }
                List<Vec2> ring = new ArrayList<>();
                int current = start;
                do {
                    if (visited[current]) {
                        // The next-half-edge rule is a permutation, so an orbit can only ever close
                        // on the half-edge it started from. Reaching an already-visited half-edge
                        // that is not the start means the rotation at some node is broken.
                        throw new IllegalStateException(
                                "face walk from half-edge " + start + " re-entered half-edge "
                                        + current + " without closing; the half-edge rotation is not "
                                        + "a permutation");
                    }
                    visited[current] = true;
                    ring.add(positions[from[current]]);
                    current = nextAroundFace(current);
                } while (current != start);
                faces.add(ring);
            }
            return faces;
        }

        /**
         * Arriving at {@code v} along {@code h = u -> v}, leave along the half-edge immediately
         * clockwise from {@code v -> u} — the predecessor in {@code v}'s ascending-bearing list.
         * At a dead end that list has one entry, so the walk turns straight back the way it came,
         * which is exactly how a spur gets traversed out and back inside its face rather than
         * trapping the walk in a loop.
         */
        private int nextAroundFace(int h) {
            int reverse = h ^ 1;
            int[] atTarget = outgoing[from[reverse]];
            int position = slot[reverse];
            return atTarget[(position - 1 + atTarget.length) % atTarget.length];
        }
    }
}
