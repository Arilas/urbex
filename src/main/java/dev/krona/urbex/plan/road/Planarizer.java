package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.geom.Vec2;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

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
 * until none remain.
 * <p>
 * <b>What is actually guaranteed: no two edges properly cross.</b> Two things are deliberately out
 * of scope and are not detected or fixed by this pass:
 * <ul>
 *   <li><b>Collinear overlap</b> — two non-incident edges lying along the same line and overlapping
 *   along it. The direction-vector cross product used to find a crossing is exactly zero for
 *   collinear segments the same way it is for merely parallel ones, and this pass treats both as
 *   "no unique crossing point, leave alone" rather than as a degenerate case needing its own
 *   handling. Measured at 274 of 5,000 grown graphs across all five settlement classes and all five
 *   synthetic terrains (down from 374 before the duplicate-edge fix, which removes one common source
 *   of collinear overlap: two ring edges occupying the literal same segment).</li>
 *   <li><b>Vertex-on-edge incidence</b> — an existing node that isn't an endpoint of some edge, yet
 *   sits exactly on that edge's interior. This pass only ever looks at pairs of <em>edges</em>; a
 *   node sitting on an edge it has no relationship to, with no second edge to form a crossing pair
 *   with, is never examined. Measured at 440 of 5,000 (down from 484, for the same reason).</li>
 * </ul>
 * Neither has been observed to make {@code BlockExtractor} throw — it degrades gracefully on both —
 * which is why they're documented and tested as a known residual (see
 * {@code PlanarityTest.collinearOverlapIsAMeasuredResidualNotAGoal}) rather than fixed here. A future
 * change that alters this residual should do so on purpose, with that test updated to match, not by
 * accident.
 * <p>
 * Two resolutions, depending on where the crossing lands once rounded to an integer position (every
 * node is integer, per the planner's determinism rule): if it coincides with one of the two edges'
 * own endpoints — the common case on a short edge, where even a solidly-interior crossing parameter
 * can round back onto a nearby node — that edge already has a vertex there, so only the *other* edge
 * is split, reusing the existing node instead of manufacturing a near-zero-length stub next to it.
 * Otherwise a genuinely new node is inserted and both edges are split at it.
 * <p>
 * The crossing <em>test</em> itself is exact: whether two segments properly cross, and within what
 * range, is decided entirely with {@code long} arithmetic over the integer node coordinates — no
 * epsilon, because there is nothing approximate to guard against (deltas are {@code int}, and their
 * products fit in a {@code long} without needing an assumption about how far apart two nodes are, the
 * way a {@code double} cross product silently depends on staying under 2^53 - i.e. on the world
 * border never being approached). Only the final step, turning a confirmed crossing into an actual
 * {@code Vec2} to round to the lattice, needs a division and therefore a {@code double}; by then
 * whether a crossing exists is no longer in question, only where.
 */
final class Planarizer {

    private static final Logger LOG = Logger.getLogger(Planarizer.class.getName());

    /**
     * Guards against a pass that never converges. Splitting rounds an intersection to the lattice,
     * which can in principle create a new crossing that wasn't there before, so the scan restarts and
     * loops; empirically this reaches a fixed point everywhere it's been swept (including a second
     * {@code planarize} call over an already-planarized graph producing zero further changes), but
     * termination is bounded here rather than proven. Hitting the cap degrades rather than throws —
     * see the note below — because a bound whose failure mode is an exception is the same crash class
     * this pass exists to remove from world generation.
     */
    private static final int MAX_SPLITS = 4096;

    private Planarizer() {
    }

    static RoadGraph planarize(RoadGraph g) {
        List<RoadNode> nodes = new ArrayList<>(g.nodes());
        List<RoadEdge> edges = new ArrayList<>(g.edges());

        int splits = 0;
        boolean capped = false;
        boolean changed = true;
        outer:
        while (changed) {
            changed = false;

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

                    if (splits >= MAX_SPLITS) {
                        // Not observed in practice (see the field javadoc), but if it ever happens,
                        // a partially-planarized road network is a far better outcome for a running
                        // world than a failed chunk. Return what's been built so far.
                        capped = true;
                        break outer;
                    }
                    splits++;

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
                    continue outer;
                }
            }
        }

        if (capped) {
            LOG.warning("Planarizer reached its " + MAX_SPLITS + "-split cap before the graph was "
                    + "fully planar; returning it as reached (" + nodes.size() + " nodes, "
                    + edges.size() + " edges) instead of failing generation. This has not been "
                    + "observed in testing - if you see this in practice, the graph it produced is "
                    + "worth capturing.");
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
     * The point where segments {@code a1-b1} and {@code a2-b2} meet within the span of both, or
     * {@code null} if they don't: parallel/collinear (see the class javadoc — collinear overlap is
     * out of scope, not handled here), or the intersection of the two infinite lines falls beyond one
     * segment's actual extent.
     * <p>
     * Whether a crossing exists, and where along each segment, is decided first with exact
     * {@code long} arithmetic (cross products of {@code int}-derived deltas, which cannot lose
     * precision at any coordinate this planner produces) — <em>then</em>, only once that's settled,
     * a {@code double} division turns the confirmed crossing into a lattice point to round to. This
     * deliberately does not try to guess "too close to an endpoint to count": on a short edge
     * (routinely produced by an earlier split in this same pass) a solidly-interior crossing can
     * still round to an endpoint's integer position, and an earlier version that used a
     * parameter-based cutoff for this was found to discard exactly those crossings instead of
     * resolving them. The caller decides what a rounded-to-an-endpoint result means.
     */
    private static Vec2 intersection(Vec2 a1, Vec2 b1, Vec2 a2, Vec2 b2) {
        long d1x = b1.x() - a1.x();
        long d1z = b1.z() - a1.z();
        long d2x = b2.x() - a2.x();
        long d2z = b2.z() - a2.z();

        long denom = d1x * d2z - d1z * d2x;
        if (denom == 0) {
            return null; // parallel or collinear: no unique crossing point
        }

        long ex = a2.x() - a1.x();
        long ez = a2.z() - a1.z();
        long tNum = ex * d2z - ez * d2x;
        long uNum = ex * d1z - ez * d1x;

        // t = tNum/denom and u = uNum/denom must both land in [0, 1]. Checked by comparing the
        // numerator against the denominator directly - exact, no floating-point division involved.
        if (denom > 0) {
            if (tNum < 0 || tNum > denom || uNum < 0 || uNum > denom) {
                return null;
            }
        } else {
            if (tNum > 0 || tNum < denom || uNum > 0 || uNum < denom) {
                return null;
            }
        }

        double t = (double) tNum / (double) denom;
        int x = (int) Math.round(a1.x() + t * d1x);
        int z = (int) Math.round(a1.z() + t * d1z);
        return new Vec2(x, z);
    }
}
