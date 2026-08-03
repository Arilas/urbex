package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.Hash;
import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.PlanPurpose;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.geom.Vec2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Grows a settlement's major road network outward from its centre: a set of spokes walking away
 * from the centre in short, terrain-scored steps, closed into loops by a set of connecting rings and
 * a final perimeter ring near the settlement edge, which is what gives the outermost band anything
 * to enclose (see the perimeter-ring step below for why the regular rings alone never reach there).
 * <p>
 * Every decision is addressed through {@link Hash} against the seed and the settlement's centre
 * chunk (or a per-spoke/per-ring slot within it), so two calls with the same inputs always grow the
 * identical network and nothing here ever consults {@link Math#random()} or {@link java.util.Random}.
 */
public final class ArterialGrowth {

    /** Candidate turn angles considered at each step, current bearing first so ties favour going straight. */
    private static final double[] CANDIDATE_DELTAS = {
            0.0,
            -Math.toRadians(15),
            Math.toRadians(15),
            -Math.toRadians(30),
            Math.toRadians(30)
    };

    /** Guards against a pathological walk that never leaves the settlement's bounds. */
    private static final int MAX_STEPS_PER_SPOKE = 256;

    private ArterialGrowth() {
    }

    public static RoadGraph grow(long seed, Settlement s, TerrainSampler terrain, PlanParams p) {
        RoadGraph.Builder builder = RoadGraph.builder();
        List<RoadNode> allNodes = new ArrayList<>();
        List<RoadEdge> allEdges = new ArrayList<>();
        // Membership only, never iterated to decide output order or content - safe under the
        // determinism rule, which is about iteration order leaking into what gets produced.
        Set<Long> edgeKeys = new HashSet<>();

        Vec2 centre = s.centerBlock();
        int cx = s.centerChunkX();
        int cz = s.centerChunkZ();

        // Step 1: the hub.
        int centreId = allNodes.size();
        allNodes.add(new RoadNode(centreId, centre));

        int spokeCount = rollInRange(Hash.at(seed, cx, cz, PlanPurpose.SPOKE_COUNT.key()),
                p.spokeCountMin(), p.spokeCountMax());

        // Every node any spoke has ever occupied, id -> position, for snap lookups and ring targeting.
        // spokeChains[i] is the ordered list of node ids spoke i passed through, starting at the centre.
        List<List<Integer>> spokeChains = new ArrayList<>(spokeCount);

        for (int i = 0; i < spokeCount; i++) {
            List<Integer> chain = new ArrayList<>();
            chain.add(centreId);

            // Step 2: initial bearing, jittered.
            double base = i * 2.0 * Math.PI / spokeCount;
            double maxJitter = Math.PI / spokeCount / 2.0;
            long jitterHash = Hash.atSlot(seed, cx, cz, i, PlanPurpose.SPOKE_ANGLE.key());
            double jitter = (Hash.unit(jitterHash) * 2.0 - 1.0) * maxJitter;
            double bearing = base + jitter;

            int currentId = centreId;
            for (int step = 0; step < MAX_STEPS_PER_SPOKE; step++) {
                RoadNode current = allNodes.get(currentId);
                boolean currentInWater = terrain.isWaterAt(current.pos().x(), current.pos().z());
                int currentHeight = terrain.heightAt(current.pos().x(), current.pos().z());

                Vec2 bestPos = null;
                double bestBearing = bearing;
                int bestScore = Integer.MAX_VALUE;

                for (double delta : CANDIDATE_DELTAS) {
                    double candidateBearing = bearing + delta;
                    Vec2 candidatePos = step(current.pos(), candidateBearing, p.segmentLengthBlocks());

                    // Step 4: never leave the settlement's (square) bounds.
                    if (Math.abs(candidatePos.x() - centre.x()) > s.radiusBlocks()
                            || Math.abs(candidatePos.z() - centre.z()) > s.radiusBlocks()) {
                        continue;
                    }

                    boolean candidateInWater = terrain.isWaterAt(candidatePos.x(), candidatePos.z());

                    // Never run along a riverbed: a step that starts and ends submerged is not a
                    // crossing, it is following the channel. Reject it outright.
                    if (currentInWater && candidateInWater) {
                        continue;
                    }

                    int candidateHeight = terrain.heightAt(candidatePos.x(), candidatePos.z());
                    int slope = Math.abs(candidateHeight - currentHeight);

                    // Step 3: slope is only a hard limit on dry ground; a candidate that crosses
                    // onto water (a bridge in waiting) is judged on the crossing later, not here.
                    if (slope > p.maxSlopePerSegment() && !candidateInWater) {
                        continue;
                    }

                    if (slope < bestScore) {
                        bestScore = slope;
                        bestPos = candidatePos;
                        bestBearing = candidateBearing;
                    }
                }

                if (bestPos == null) {
                    // No candidate survived: stop this spoke where it stands.
                    break;
                }

                // Step 5: snap to an existing node within range instead of adding a new one.
                Integer snapTarget = findSnapTarget(allNodes, bestPos, currentId, p.snapRadiusBlocks());
                if (snapTarget != null) {
                    addEdge(allEdges, edgeKeys, currentId, snapTarget, RoadClass.ARTERIAL);
                    chain.add(snapTarget);
                    break;
                }

                int newId = allNodes.size();
                allNodes.add(new RoadNode(newId, bestPos));
                addEdge(allEdges, edgeKeys, currentId, newId, RoadClass.ARTERIAL);
                chain.add(newId);

                currentId = newId;
                bearing = bestBearing;
            }

            spokeChains.add(chain);
        }

        // Step 6: rings, closing loops across the spokes.
        int ringCount = rollInRange(Hash.at(seed, cx, cz, PlanPurpose.RING_COUNT.key()),
                p.ringCountMin(), p.ringCountMax());

        for (int r = 0; r < ringCount; r++) {
            double spacing = s.radiusBlocks() / (double) (ringCount + 1);
            double baseRadius = spacing * (r + 1);
            long jitterHash = Hash.atSlot(seed, cx, cz, r, PlanPurpose.RING_RADIUS.key());
            double jitter = (Hash.unit(jitterHash) * 2.0 - 1.0) * (spacing / 4.0);
            double targetRadius = baseRadius + jitter;

            int[] nearest = new int[spokeCount];
            for (int i = 0; i < spokeCount; i++) {
                nearest[i] = nearestInChain(spokeChains.get(i), allNodes, centre, targetRadius);
            }

            for (int i = 0; i < spokeCount; i++) {
                int from = nearest[i];
                int to = nearest[(i + 1) % spokeCount];
                if (from == to) {
                    continue;
                }
                // Different rings can land on the same pair of spoke nodes (a tight ring spacing,
                // or jitter, can make two rings pick identical "nearest" nodes). Two edges on the
                // same pair make face rotation undefined downstream, so skip a pair already joined,
                // by an earlier ring or by the spokes themselves, in either direction.
                addEdge(allEdges, edgeKeys, from, to, RoadClass.COLLECTOR);
            }
        }

        // Step 7: a perimeter ring near the settlement edge. Rings above only ever reach
        // ringCount/(ringCount+1) of the radius - 0.75 at the most rings ever rolls - so nothing is
        // ever enclosed beyond that: the outer band has spokes running through it but nothing to
        // close them into a face, so it contains no blocks, and the district model's outermost band
        // is unreachable by construction. A ring close to the true edge - real cities have exactly
        // this, an outer ring road or bypass - closes that band the same way every other ring closes
        // the one inside it. Reuses the ring loop's own machinery (nearest spoke node to a target
        // radius, connected around) and the ring-slot addressing scheme at the next free slot
        // (ringCount, since regular rings only ever use 0..ringCount-1), so no new PlanPurpose key is
        // needed for a decision that is, structurally, just one more ring.
        double perimeterSpacing = s.radiusBlocks() * (1.0 - p.perimeterRingFraction());
        double perimeterBase = s.radiusBlocks() * p.perimeterRingFraction();
        long perimeterJitterHash = Hash.atSlot(seed, cx, cz, ringCount, PlanPurpose.RING_RADIUS.key());
        double perimeterJitter = (Hash.unit(perimeterJitterHash) * 2.0 - 1.0) * (perimeterSpacing / 4.0);
        double perimeterTargetRadius = perimeterBase + perimeterJitter;

        int[] perimeterNearest = new int[spokeCount];
        for (int i = 0; i < spokeCount; i++) {
            perimeterNearest[i] = nearestInChain(spokeChains.get(i), allNodes, centre, perimeterTargetRadius);
        }
        for (int i = 0; i < spokeCount; i++) {
            int from = perimeterNearest[i];
            int to = perimeterNearest[(i + 1) % spokeCount];
            if (from == to) {
                continue;
            }
            addEdge(allEdges, edgeKeys, from, to, RoadClass.COLLECTOR);
        }

        for (RoadNode n : allNodes) {
            builder.node(n.pos());
        }
        for (RoadEdge e : allEdges) {
            builder.edge(e);
        }

        RoadGraph grown = builder.build();
        RoadGraph planar = Planarizer.planarize(grown);
        return BridgeDetector.mark(planar, terrain, p, centreId);
    }

    /** Adds an edge only if that node pair (in either direction) isn't already connected. */
    private static void addEdge(List<RoadEdge> edges, Set<Long> edgeKeys, int from, int to, RoadClass cls) {
        long key = edgeKey(from, to);
        if (!edgeKeys.add(key)) {
            return;
        }
        edges.add(new RoadEdge(from, to, cls, false, 0));
    }

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private static Vec2 step(Vec2 from, double bearing, int length) {
        int dx = (int) Math.round(Math.cos(bearing) * length);
        int dz = (int) Math.round(Math.sin(bearing) * length);
        return from.plus(dx, dz);
    }

    /** The nearest existing node to {@code pos}, other than {@code excludeId}, within {@code radius}. */
    private static Integer findSnapTarget(List<RoadNode> nodes, Vec2 pos, int excludeId, int radius) {
        long radiusSq = (long) radius * radius;
        Integer best = null;
        long bestDistSq = Long.MAX_VALUE;
        for (RoadNode n : nodes) {
            if (n.id() == excludeId) {
                continue;
            }
            long d = n.pos().distanceSquaredTo(pos);
            if (d <= radiusSq && d < bestDistSq) {
                bestDistSq = d;
                best = n.id();
            }
        }
        return best;
    }

    /** The node in {@code chain} whose Euclidean distance from {@code centre} is closest to {@code targetRadius}. */
    private static int nearestInChain(List<Integer> chain, List<RoadNode> nodes, Vec2 centre, double targetRadius) {
        int best = chain.get(0);
        double bestDiff = Double.MAX_VALUE;
        for (int id : chain) {
            Vec2 pos = nodes.get(id).pos();
            double dist = Math.sqrt((double) pos.distanceSquaredTo(centre));
            double diff = Math.abs(dist - targetRadius);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = id;
            }
        }
        return best;
    }

    private static int rollInRange(long hash, int min, int max) {
        return min + Hash.index(hash, max - min + 1);
    }
}
