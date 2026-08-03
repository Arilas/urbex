package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.geom.Vec2;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes a grown road graph planar by construction.
 * <p>
 * Ring chords are straight lines between spoke nodes, and spokes curve under terrain avoidance
 * (slope scoring, riverbed rejection, cliff stops), so a ring can cross a spoke — or another ring —
 * without a node at the crossing. Planar face traversal, which block extraction depends on, is
 * undefined on a graph like that: a crossing with no vertex there is not a graph edge intersection,
 * it's two unrelated edges that happen to overlap in space.
 * <p>
 * This is an explicit intersection-and-split pass rather than a routing trick (an arc that follows
 * a ring's constant radius would meet a <em>straight</em> spoke only at spoke nodes, but gives no
 * guarantee against a spoke that wanders across that radius and back under terrain avoidance — the
 * exact case this exists to cover). It finds every pair of edges that properly cross within the span
 * of both segments — not merely touching at a shared endpoint — and resolves each crossing, repeating
 * until none remain. That is a real guarantee, not a strong tendency: it holds regardless of how
 * curved any particular road turns out to be.
 * <p>
 * Two resolutions, depending on where the crossing lands once rounded to an integer position (every
 * node is integer, per the planner's determinism rule): if it coincides with one of the two edges'
 * own endpoints — the common case on a short edge, where even a solidly-interior crossing parameter
 * can round back onto a nearby node — that edge already has a vertex there, so only the *other* edge
 * is split, reusing the existing node instead of manufacturing a near-zero-length stub next to it.
 * Otherwise a genuinely new node is inserted and both edges are split at it.
 */
final class Planarizer {

    /** Below this, a computed cross product is treated as exactly zero (parallel/collinear segments). */
    private static final double DENOM_EPSILON = 1.0e-9;

    /** Slack applied only at the true segment boundary (t/u = 0 or 1), to absorb float roundoff. */
    private static final double BOUNDS_EPSILON = 1.0e-9;

    /** Guards against a pass that never converges, which would mean the intersection math is wrong. */
    private static final int MAX_SPLITS = 4096;

    private Planarizer() {
    }

    static RoadGraph planarize(RoadGraph g) {
        List<RoadNode> nodes = new ArrayList<>(g.nodes());
        List<RoadEdge> edges = new ArrayList<>(g.edges());

        int splits = 0;
        boolean changed = true;
        while (changed) {
            changed = false;

            search:
            for (int i = 0; i < edges.size(); i++) {
                for (int j = i + 1; j < edges.size(); j++) {
                    RoadEdge e1 = edges.get(i);
                    RoadEdge e2 = edges.get(j);
                    if (shareEndpoint(e1, e2)) {
                        continue;
                    }

                    Vec2 a1 = nodes.get(e1.fromId()).pos();
                    Vec2 b1 = nodes.get(e1.toId()).pos();
                    Vec2 a2 = nodes.get(e2.fromId()).pos();
                    Vec2 b2 = nodes.get(e2.toId()).pos();

                    Vec2 crossing = intersection(a1, b1, a2, b2);
                    if (crossing == null) {
                        continue;
                    }

                    if (++splits > MAX_SPLITS) {
                        throw new IllegalStateException(
                                "planarization did not converge after " + MAX_SPLITS + " splits");
                    }

                    int existingId = existingNodeAt(crossing, e1, a1, b1, e2, a2, b2);
                    if (existingId >= 0) {
                        boolean belongsToE1 = existingId == e1.fromId() || existingId == e1.toId();
                        if (belongsToE1) {
                            splitOne(edges, j, e2, existingId);
                        } else {
                            splitOne(edges, i, e1, existingId);
                        }
                    } else {
                        int newId = nodes.size();
                        nodes.add(new RoadNode(newId, crossing));
                        // j > i always, so remove the higher index first to keep i's removal valid.
                        edges.remove(j);
                        edges.remove(i);
                        edges.add(new RoadEdge(e1.fromId(), newId, e1.cls(), e1.bridge(), e1.waterSpanBlocks()));
                        edges.add(new RoadEdge(newId, e1.toId(), e1.cls(), e1.bridge(), e1.waterSpanBlocks()));
                        edges.add(new RoadEdge(e2.fromId(), newId, e2.cls(), e2.bridge(), e2.waterSpanBlocks()));
                        edges.add(new RoadEdge(newId, e2.toId(), e2.cls(), e2.bridge(), e2.waterSpanBlocks()));
                    }

                    changed = true;
                    break search;
                }
            }
        }

        RoadGraph.Builder builder = RoadGraph.builder();
        for (RoadNode n : nodes) {
            builder.node(n.pos());
        }
        for (RoadEdge e : edges) {
            builder.edge(e);
        }
        return builder.build();
    }

    /**
     * Replaces the edge at {@code index} with edges routed through the existing node {@code viaId}.
     * <p>
     * Unlike splitting at a brand-new node (where both halves are necessarily novel pairs), routing
     * through a node that's already in the graph can reproduce a pair some other edge already
     * connects — {@code viaId} was, after all, plucked from a totally unrelated edge. Adding that
     * would reintroduce the exact duplicate-pair problem the graph is built to avoid in the first
     * place, so each half is only added if the graph doesn't already connect that pair; the original
     * edge is dropped either way; connectivity survives because the pair that's skipped is, by
     * definition, already reachable through the edge it duplicates.
     */
    private static void splitOne(List<RoadEdge> edges, int index, RoadEdge e, int viaId) {
        edges.remove(index);
        RoadEdge first = new RoadEdge(e.fromId(), viaId, e.cls(), e.bridge(), e.waterSpanBlocks());
        if (!containsPair(edges, first.fromId(), first.toId())) {
            edges.add(first);
        }
        RoadEdge second = new RoadEdge(viaId, e.toId(), e.cls(), e.bridge(), e.waterSpanBlocks());
        if (!containsPair(edges, second.fromId(), second.toId())) {
            edges.add(second);
        }
    }

    private static boolean containsPair(List<RoadEdge> edges, int a, int b) {
        for (RoadEdge e : edges) {
            if ((e.fromId() == a && e.toId() == b) || (e.fromId() == b && e.toId() == a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The node id of whichever endpoint {@code crossing} coincides with, or {@code -1} if it matches
     * none of the four — meaning the crossing is a genuinely new point.
     */
    private static int existingNodeAt(Vec2 crossing, RoadEdge e1, Vec2 a1, Vec2 b1, RoadEdge e2, Vec2 a2, Vec2 b2) {
        if (crossing.equals(a1)) {
            return e1.fromId();
        }
        if (crossing.equals(b1)) {
            return e1.toId();
        }
        if (crossing.equals(a2)) {
            return e2.fromId();
        }
        if (crossing.equals(b2)) {
            return e2.toId();
        }
        return -1;
    }

    private static boolean shareEndpoint(RoadEdge e1, RoadEdge e2) {
        return e1.fromId() == e2.fromId() || e1.fromId() == e2.toId()
                || e1.toId() == e2.fromId() || e1.toId() == e2.toId();
    }

    /**
     * The point where segments {@code a1-b1} and {@code a2-b2} meet within the span of both
     * (parameters in the closed {@code [0, 1]} range, up to float roundoff at the boundary), or
     * {@code null} if they don't: parallel/collinear, or the intersection of the two infinite lines
     * falls beyond one segment's actual extent.
     * <p>
     * This deliberately does <em>not</em> try to guess "too close to an endpoint to count" from the
     * continuous parameters — on a short edge (routinely produced by an earlier split in this same
     * pass) a solidly-interior parameter can still round to an endpoint's integer position, and a
     * parameter-based cutoff was found to discard exactly those crossings instead of resolving them.
     * The caller decides what a rounded-to-an-endpoint result means.
     */
    private static Vec2 intersection(Vec2 a1, Vec2 b1, Vec2 a2, Vec2 b2) {
        double d1x = b1.x() - a1.x();
        double d1z = b1.z() - a1.z();
        double d2x = b2.x() - a2.x();
        double d2z = b2.z() - a2.z();

        double denom = d1x * d2z - d1z * d2x;
        if (Math.abs(denom) < DENOM_EPSILON) {
            return null;
        }

        double ex = a2.x() - a1.x();
        double ez = a2.z() - a1.z();
        double t = (ex * d2z - ez * d2x) / denom;
        double u = (ex * d1z - ez * d1x) / denom;

        if (t < -BOUNDS_EPSILON || t > 1.0 + BOUNDS_EPSILON || u < -BOUNDS_EPSILON || u > 1.0 + BOUNDS_EPSILON) {
            return null;
        }

        int x = (int) Math.round(a1.x() + t * d1x);
        int z = (int) Math.round(a1.z() + t * d1z);
        return new Vec2(x, z);
    }
}
