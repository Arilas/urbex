package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.Hash;
import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.PlanPurpose;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.geom.Vec2;

import java.util.ArrayList;
import java.util.List;

/**
 * Grows a small settlement's road network as a single main road (the spine) walking outward from
 * the centre in both directions, with short branches hanging off it here and there.
 * <p>
 * This is deliberately not {@link ArterialGrowth}: a hamlet or village is too small for even one
 * arterial segment to take a first step, and the owner's ruling was that the two smallest classes
 * should not be miniature cities anyway - "a main road with some additional branches" rather than a
 * spoke-and-ring network. Concretely that means this class never snaps a new node onto an existing
 * one the way {@link ArterialGrowth} does: the result must stay a tree (see {@link #maybeBranch}),
 * because {@code BlockExtractor} and {@code LotSubdivider} only know how to work with the enclosed
 * faces a loop would create, and a tree encloses none. {@link dev.krona.urbex.plan.lot.RoadsideLots}
 * is what derives lots for a tree instead, straight from road frontage.
 * <p>
 * Never snapping stops a node from being <em>reused</em>, but it does nothing to stop a fresh edge
 * from crossing an <em>existing</em> one geometrically without sharing a node - a branch curving back
 * across the spine it grew from, say. Review (after this class shipped) measured that at ~2.4% of
 * graphs. {@code ArterialGrowth} would fix that after the fact with {@code Planarizer}, but running
 * Planarizer here would split the crossing into a shared node, which turns a tree into a graph with
 * a cycle - exactly the property this class exists to prevent. So instead {@link #nextStep} rejects
 * any candidate whose new edge would cross one already grown, the same way it rejects a candidate for
 * bad slope or leaving the bounds: prevented by construction, never patched up after.
 * <p>
 * Every decision is addressed through {@link Hash} against the seed and either the settlement's
 * centre chunk or a node's own block position, so two calls with the same inputs always grow the
 * identical tree and nothing here ever consults {@link Math#random()} or {@link java.util.Random}.
 */
public final class SpineGrowth {

    /** Candidate turn angles considered at each step - the same set {@link ArterialGrowth} scores. */
    private static final double[] CANDIDATE_DELTAS = {
            0.0,
            -Math.toRadians(15),
            Math.toRadians(15),
            -Math.toRadians(30),
            Math.toRadians(30)
    };

    /** Guards against a pathological walk that never leaves the settlement's bounds. */
    private static final int MAX_STEPS_PER_ARM = 64;

    private SpineGrowth() {
    }

    public static RoadGraph grow(long seed, Settlement s, TerrainSampler terrain, PlanParams p) {
        List<RoadNode> nodes = new ArrayList<>();
        List<RoadEdge> edges = new ArrayList<>();

        Vec2 centre = s.centerBlock();
        int radius = s.radiusBlocks();

        // Step 1: the centre node and the spine's bearing, drawn once per settlement.
        int centreId = nodes.size();
        nodes.add(new RoadNode(centreId, centre));

        long angleHash = Hash.at(seed, s.centerChunkX(), s.centerChunkZ(), PlanPurpose.SPOKE_ANGLE.key());
        double bearing = Hash.unit(angleHash) * 2.0 * Math.PI;

        // Step 2: walk outward in both directions along that bearing. Branching is only ever rolled
        // on nodes the spine itself produces (rule in step 3), so allowBranching is true here and
        // false wherever a branch grows its own arm (a branch never sprouts a branch).
        growArm(seed, nodes, edges, centreId, bearing, true, MAX_STEPS_PER_ARM,
                RoadClass.COLLECTOR, centre, radius, terrain, p);
        growArm(seed, nodes, edges, centreId, bearing + Math.PI, true, MAX_STEPS_PER_ARM,
                RoadClass.COLLECTOR, centre, radius, terrain, p);

        RoadGraph.Builder builder = RoadGraph.builder();
        for (RoadNode n : nodes) {
            builder.node(n.pos());
        }
        for (RoadEdge e : edges) {
            builder.edge(e);
        }
        RoadGraph grown = builder.build();

        // Step 6: bridges stay derived from terrain, never rolled, exactly as ArterialGrowth does.
        // Planarizer is deliberately NOT run here - see the class doc: splitting a crossing would add
        // a node and turn this tree into a graph with a cycle. Crossings are prevented earlier, in
        // nextStep, by construction instead.
        return BridgeDetector.mark(grown, terrain, p, centreId);
    }

    /**
     * Walks a single arm - the spine in one direction, or one branch - from {@code fromId}, stepping
     * in {@code cls}-classed edges until a step fails to survive scoring, {@code maxSteps} is
     * reached, or the settlement radius is hit. Rule 4 of the brief: this never snaps to an existing
     * node, so every surviving step always creates a brand new one and the graph can never close a
     * loop back on itself.
     */
    private static void growArm(long seed, List<RoadNode> nodes, List<RoadEdge> edges, int fromId,
                                 double bearing, boolean allowBranching, int maxSteps, RoadClass cls,
                                 Vec2 centre, int radius, TerrainSampler terrain, PlanParams p) {
        int currentId = fromId;
        Vec2 currentPos = nodes.get(fromId).pos();
        double currentBearing = bearing;

        for (int step = 0; step < maxSteps; step++) {
            StepResult next = nextStep(currentId, currentPos, currentBearing, nodes, edges, centre, radius,
                    terrain, p);
            if (next == null) {
                // No candidate survived: stop this arm where it stands.
                return;
            }

            int newId = nodes.size();
            nodes.add(new RoadNode(newId, next.pos()));
            edges.add(new RoadEdge(currentId, newId, cls, false, 0));

            if (allowBranching) {
                // Step 3: at each spine node after the first, roll a branch addressed at that node's
                // own position, so two spine nodes that happen to coincide in position (impossible
                // here, but true in general of Hash-addressed decisions) never silently correlate
                // with anything but themselves.
                maybeBranch(seed, nodes, edges, newId, next.bearing(), centre, radius, terrain, p);
            }

            currentId = newId;
            currentPos = next.pos();
            currentBearing = next.bearing();
        }
    }

    /**
     * Rolls {@code branchChance} at {@code nodeId}'s position; on a hit, grows a branch perpendicular
     * to the local spine direction, the side chosen from the same draw so a single {@link Hash} call
     * decides both whether and where.
     */
    private static void maybeBranch(long seed, List<RoadNode> nodes, List<RoadEdge> edges, int nodeId,
                                     double arrivalBearing, Vec2 centre, int radius, TerrainSampler terrain,
                                     PlanParams p) {
        Vec2 pos = nodes.get(nodeId).pos();
        long h = Hash.at(seed, pos.x(), pos.z(), PlanPurpose.SPOKE_STEP.key());
        if (Hash.unit(h) >= p.branchChance()) {
            return;
        }

        double side = Hash.index(h, 2) == 0 ? -1.0 : 1.0;
        double branchBearing = arrivalBearing + side * (Math.PI / 2.0);

        growArm(seed, nodes, edges, nodeId, branchBearing, false, p.branchLengthSegments(),
                RoadClass.LOCAL, centre, radius, terrain, p);
    }

    /**
     * One candidate-scored step from {@code currentPos} along {@code bearing}, or {@code null} if
     * nothing survives. This mirrors {@link ArterialGrowth}'s per-step scoring exactly: consider the
     * current bearing and +-15 degrees and +-30 degrees, drop anything that would leave the
     * settlement's square bounds, run from water into more water (following a riverbed rather than
     * crossing it), exceed {@code maxSlopePerSegment} unless the candidate is itself over water (a
     * bridge in waiting, judged later by {@link BridgeDetector}), or - the one rule
     * {@code ArterialGrowth} doesn't need, because it planarizes after the fact - cross an edge
     * already grown elsewhere in this same graph. Whatever survives, take the lowest-slope candidate.
     */
    private static StepResult nextStep(int currentId, Vec2 currentPos, double bearing, List<RoadNode> nodes,
                                        List<RoadEdge> edges, Vec2 centre, int radius, TerrainSampler terrain,
                                        PlanParams p) {
        boolean currentInWater = terrain.isWaterAt(currentPos.x(), currentPos.z());
        int currentHeight = terrain.heightAt(currentPos.x(), currentPos.z());

        Vec2 bestPos = null;
        double bestBearing = bearing;
        int bestScore = Integer.MAX_VALUE;

        for (double delta : CANDIDATE_DELTAS) {
            double candidateBearing = bearing + delta;
            Vec2 candidatePos = step(currentPos, candidateBearing, p.spineSegmentLengthBlocks());

            if (Math.abs(candidatePos.x() - centre.x()) > radius
                    || Math.abs(candidatePos.z() - centre.z()) > radius) {
                continue;
            }

            boolean candidateInWater = terrain.isWaterAt(candidatePos.x(), candidatePos.z());
            if (currentInWater && candidateInWater) {
                continue;
            }

            int candidateHeight = terrain.heightAt(candidatePos.x(), candidatePos.z());
            int slope = Math.abs(candidateHeight - currentHeight);
            if (slope > p.maxSlopePerSegment() && !candidateInWater) {
                continue;
            }

            if (crossesExistingEdge(currentId, currentPos, candidatePos, nodes, edges)) {
                continue;
            }

            if (slope < bestScore) {
                bestScore = slope;
                bestPos = candidatePos;
                bestBearing = candidateBearing;
            }
        }

        return bestPos == null ? null : new StepResult(bestPos, bestBearing);
    }

    /**
     * Whether the prospective new edge {@code currentPos -> candidatePos} would cross any edge
     * already in the graph. Edges incident to {@code currentId} are skipped - sharing the start point
     * is an ordinary junction, not a crossing - so this only ever rejects a genuinely new
     * intersection between two edges that don't already meet at a node.
     */
    private static boolean crossesExistingEdge(int currentId, Vec2 currentPos, Vec2 candidatePos,
                                                List<RoadNode> nodes, List<RoadEdge> edges) {
        for (RoadEdge e : edges) {
            if (e.fromId() == currentId || e.toId() == currentId) {
                continue;
            }
            Vec2 a = nodes.get(e.fromId()).pos();
            Vec2 b = nodes.get(e.toId()).pos();
            if (segmentsIntersect(currentPos, candidatePos, a, b)) {
                return true;
            }
        }
        return false;
    }

    /** General-position segment intersection, exact in long arithmetic; touching counts as crossing. */
    private static boolean segmentsIntersect(Vec2 p1, Vec2 p2, Vec2 p3, Vec2 p4) {
        long d1 = cross(p3, p4, p1);
        long d2 = cross(p3, p4, p2);
        long d3 = cross(p1, p2, p3);
        long d4 = cross(p1, p2, p4);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        if (d1 == 0 && onSegment(p3, p4, p1)) {
            return true;
        }
        if (d2 == 0 && onSegment(p3, p4, p2)) {
            return true;
        }
        if (d3 == 0 && onSegment(p1, p2, p3)) {
            return true;
        }
        return d4 == 0 && onSegment(p1, p2, p4);
    }

    private static long cross(Vec2 a, Vec2 b, Vec2 c) {
        return (long) (b.x() - a.x()) * (c.z() - a.z()) - (long) (b.z() - a.z()) * (c.x() - a.x());
    }

    private static boolean onSegment(Vec2 a, Vec2 b, Vec2 p) {
        return Math.min(a.x(), b.x()) <= p.x() && p.x() <= Math.max(a.x(), b.x())
                && Math.min(a.z(), b.z()) <= p.z() && p.z() <= Math.max(a.z(), b.z());
    }

    private static Vec2 step(Vec2 from, double bearing, int length) {
        int dx = (int) Math.round(Math.cos(bearing) * length);
        int dz = (int) Math.round(Math.sin(bearing) * length);
        return from.plus(dx, dz);
    }

    private record StepResult(Vec2 pos, double bearing) {
    }
}
