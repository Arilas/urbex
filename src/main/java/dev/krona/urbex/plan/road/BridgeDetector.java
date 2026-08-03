package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.geom.Vec2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns {@code bridge} from something a road roll decides into something the terrain decides.
 * <p>
 * An edge is a bridge because its span crosses water, never because a die said so — that is the
 * whole point of separating this from {@link ArterialGrowth}'s growth step. {@link #mark} is a pure
 * function of the graph and the terrain, so calling it twice on its own output is a no-op: the
 * second pass recomputes exactly what the first pass already found.
 */
public final class BridgeDetector {

    /** How far apart along an edge each water probe sits. */
    private static final int SAMPLE_INTERVAL_BLOCKS = 4;

    private BridgeDetector() {
    }

    public static RoadGraph mark(RoadGraph g, TerrainSampler terrain, PlanParams p) {
        List<RoadEdge> marked = new ArrayList<>(g.edges().size());
        for (RoadEdge e : g.edges()) {
            Vec2 a = g.nodeAt(e.fromId()).pos();
            Vec2 b = g.nodeAt(e.toId()).pos();
            int waterSpan = longestWaterRun(a, b, terrain);

            if (waterSpan > p.maxBridgeSpanBlocks()) {
                // A run this long is not a bridge, it is a mistake. Drop the edge entirely.
                continue;
            }
            marked.add(waterSpan > 0 ? e.asBridge(waterSpan) : e);
        }

        return keepOnlyCentreComponent(g.nodes(), marked);
    }

    /** The length, in blocks, of the longest unbroken run of water samples along the segment a-b. */
    private static int longestWaterRun(Vec2 a, Vec2 b, TerrainSampler terrain) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double length = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.round(length / SAMPLE_INTERVAL_BLOCKS));

        int longestRun = 0;
        int currentRun = 0;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int sx = (int) Math.round(a.x() + dx * t);
            int sz = (int) Math.round(a.z() + dz * t);
            if (terrain.isWaterAt(sx, sz)) {
                currentRun++;
                longestRun = Math.max(longestRun, currentRun);
            } else {
                currentRun = 0;
            }
        }
        return longestRun == 0 ? 0 : longestRun * SAMPLE_INTERVAL_BLOCKS;
    }

    /**
     * Rejecting an over-span edge can split the graph. Keep only the component that still contains
     * node 0 — the settlement's centre in every graph {@link ArterialGrowth} produces — not merely
     * the largest surviving piece.
     */
    private static RoadGraph keepOnlyCentreComponent(List<RoadNode> nodes, List<RoadEdge> edges) {
        if (nodes.isEmpty()) {
            return RoadGraph.builder().build();
        }

        List<List<RoadEdge>> adjacency = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            adjacency.add(new ArrayList<>());
        }
        for (RoadEdge e : edges) {
            adjacency.get(e.fromId()).add(e);
            adjacency.get(e.toId()).add(e);
        }

        Set<Integer> reachable = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        reachable.add(0);
        queue.add(0);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            for (RoadEdge e : adjacency.get(cur)) {
                int other = e.fromId() == cur ? e.toId() : e.fromId();
                if (reachable.add(other)) {
                    queue.add(other);
                }
            }
        }

        if (reachable.size() == nodes.size()) {
            RoadGraph.Builder b = RoadGraph.builder();
            for (RoadNode n : nodes) {
                b.node(n.pos());
            }
            for (RoadEdge e : edges) {
                b.edge(e);
            }
            return b.build();
        }

        int[] oldToNew = new int[nodes.size()];
        RoadGraph.Builder b = RoadGraph.builder();
        int next = 0;
        for (RoadNode n : nodes) {
            if (reachable.contains(n.id())) {
                oldToNew[n.id()] = next++;
                b.node(n.pos());
            } else {
                oldToNew[n.id()] = -1;
            }
        }
        for (RoadEdge e : edges) {
            if (reachable.contains(e.fromId()) && reachable.contains(e.toId())) {
                b.edge(new RoadEdge(oldToNew[e.fromId()], oldToNew[e.toId()], e.cls(), e.bridge(), e.waterSpanBlocks()));
            }
        }
        return b.build();
    }
}
