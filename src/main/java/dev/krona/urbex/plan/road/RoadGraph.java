package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.geom.Vec2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * An immutable road network. Node ids are stable, dense array indices assigned in insertion order,
 * so {@link #nodeAt(int)} is a plain array lookup rather than a map probe.
 * <p>
 * Build one through {@link #builder()}; there is no public constructor and no mutator, so a
 * {@code RoadGraph} handed to a caller can never change under it.
 * <p>
 * {@code equals}/{@code hashCode} compare {@link #nodes} and {@link #edges} — both lists of records,
 * so this gives real value equality rather than the identity equality a plain class gets by default.
 * {@link #adjacency} is excluded because it is entirely derived from the other two: two graphs with
 * equal nodes and edges always build equal adjacency, so comparing it too would only cost time.
 */
public final class RoadGraph {

    private final List<RoadNode> nodes;
    private final List<RoadEdge> edges;
    private final List<List<RoadEdge>> adjacency;

    private RoadGraph(List<RoadNode> nodes, List<RoadEdge> edges) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);

        List<List<RoadEdge>> adj = new ArrayList<>(this.nodes.size());
        for (int i = 0; i < this.nodes.size(); i++) {
            adj.add(new ArrayList<>());
        }
        for (RoadEdge e : this.edges) {
            adj.get(e.fromId()).add(e);
            adj.get(e.toId()).add(e);
        }
        List<List<RoadEdge>> frozen = new ArrayList<>(adj.size());
        for (List<RoadEdge> at : adj) {
            frozen.add(List.copyOf(at));
        }
        this.adjacency = List.copyOf(frozen);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<RoadNode> nodes() {
        return nodes;
    }

    public List<RoadEdge> edges() {
        return edges;
    }

    /** O(1): node ids are dense array indices. */
    public RoadNode nodeAt(int id) {
        return nodes.get(id);
    }

    public List<RoadEdge> edgesAt(int nodeId) {
        return adjacency.get(nodeId);
    }

    /** A breadth-first walk from node 0 that must reach every node. */
    public boolean isConnected() {
        if (nodes.isEmpty()) {
            return true;
        }
        boolean[] seen = new boolean[nodes.size()];
        Deque<Integer> queue = new ArrayDeque<>();
        seen[0] = true;
        queue.add(0);
        int reached = 1;
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            for (RoadEdge e : adjacency.get(cur)) {
                int other = e.fromId() == cur ? e.toId() : e.fromId();
                if (!seen[other]) {
                    seen[other] = true;
                    reached++;
                    queue.add(other);
                }
            }
        }
        return reached == nodes.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RoadGraph other)) {
            return false;
        }
        return nodes.equals(other.nodes) && edges.equals(other.edges);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes, edges);
    }

    public static final class Builder {
        private final List<RoadNode> nodes = new ArrayList<>();
        private final List<RoadEdge> edges = new ArrayList<>();

        private Builder() {
        }

        /** Assigns the next sequential id, starting at 0. */
        public Builder node(Vec2 pos) {
            nodes.add(new RoadNode(nodes.size(), pos));
            return this;
        }

        /** {@code bridge} defaults to false and {@code waterSpanBlocks} to 0. */
        public Builder edge(int fromId, int toId, RoadClass cls) {
            edges.add(new RoadEdge(fromId, toId, cls, false, 0));
            return this;
        }

        /** Adds a fully-formed edge as-is, preserving its bridge flag and water span. */
        public Builder edge(RoadEdge edge) {
            edges.add(edge);
            return this;
        }

        public RoadGraph build() {
            return new RoadGraph(nodes, edges);
        }
    }
}
