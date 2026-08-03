package dev.krona.urbex.plan;

import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.lot.Lot;
import dev.krona.urbex.plan.road.RoadEdge;
import dev.krona.urbex.plan.road.RoadGraph;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single face P4 (world generation) will query against: given a chunk, is it untouched
 * countryside, does it fall on a road, inside a lot, or on open ground within a settlement that has
 * neither?
 * <p>
 * Two caches sit behind {@link #at}, both populated with the same discipline: {@code get}, compute
 * the value <em>outside</em> the map, then {@code putIfAbsent} - never {@code computeIfAbsent}. An
 * earlier sub-project's city caches deadlocked on {@code computeIfAbsent} because they were mutually
 * recursive: recursive population inside {@code computeIfAbsent} hangs even for distinct keys that
 * land in the same segment, because the map holds a lock for the whole duration of the mapping
 * function. Neither cache here is currently reached recursively, but {@link #at} is exactly the entry
 * point future worldgen worker threads will call concurrently for the same chunk (P4), so the same
 * discipline applies pre-emptively. Recomputing on a race is harmless - both {@link Planner#plan} and
 * {@link SettlementMap#at} are pure functions of their arguments, so two threads racing on the same
 * key simply do the same work twice and agree on the answer.
 * <p>
 * The plan cache is what the brief asks for explicitly - "keyed by Settlement". This class widens
 * that key to {@code (seed, Settlement, PlanParams)}: {@code Settlement} alone does not determine a
 * plan, since the same settlement coordinates under a different seed or different tuning parameters
 * produce a different {@link CityPlan}, and a static cache keyed on {@code Settlement} alone would
 * silently hand back the wrong plan the moment more than one seed is ever queried in the same JVM
 * (plausible even in single-player, across a server's multiple dimensions). {@code TerrainSampler} is
 * deliberately left out of the key: it has no general {@code equals}, and in practice it is a
 * deterministic function of the seed and dimension, not something that varies independently per call.
 * <p>
 * The settlement-resolution cache is the answer to the second lesson: {@link SettlementMap#at} costs
 * roughly 19 {@link Hash#at} calls on average and up to a few hundred worst case for a single chunk
 * (measured; see the task report), because resolving a settlement recursively re-resolves the same
 * larger-class cells with no memoisation of its own - and that recursion is entirely private to
 * {@code SettlementMap}, which this task must not modify. What <em>is</em> achievable from outside is
 * memoising {@code SettlementMap.at}'s own result by its only public key, {@code (seed, chunkX,
 * chunkZ, params)}: once P4 starts calling {@link #at} once per chunk from multiple generation
 * passes and multiple worker threads, the same chunk gets queried more than once, and every repeat
 * after the first is now a map lookup instead of a fresh resolution.
 */
public final class PlanQuery {

    private PlanQuery() {
    }

    public sealed interface Result {
        record None() implements Result {
        }

        record OpenGround(Settlement settlement) implements Result {
        }

        record Road(Settlement settlement, int edgeIndex) implements Result {
        }

        record LotAt(Settlement settlement, Lot lot) implements Result {
        }
    }

    private record SettlementKey(long seed, int chunkX, int chunkZ, PlanParams params) {
    }

    private record PlanKey(long seed, Settlement settlement, PlanParams params) {
    }

    private static final ConcurrentHashMap<SettlementKey, Optional<Settlement>> SETTLEMENT_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PlanKey, CityPlan> PLAN_CACHE = new ConcurrentHashMap<>();

    public static Result at(long seed, int chunkX, int chunkZ, TerrainSampler terrain, PlanParams params) {
        Settlement settlement = resolveSettlement(seed, chunkX, chunkZ, params);
        if (settlement == null) {
            return new Result.None();
        }
        CityPlan plan = planFor(seed, settlement, terrain, params);
        return classify(settlement, plan, chunkX, chunkZ);
    }

    /** Memoised wrapper around {@link SettlementMap#at}. See the class doc for why this is the granularity available. */
    private static Settlement resolveSettlement(long seed, int chunkX, int chunkZ, PlanParams params) {
        SettlementKey key = new SettlementKey(seed, chunkX, chunkZ, params);
        Optional<Settlement> cached = SETTLEMENT_CACHE.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        Settlement computed = SettlementMap.at(seed, chunkX, chunkZ, params);
        // get -> compute outside the map -> putIfAbsent: never computeIfAbsent. See class doc.
        SETTLEMENT_CACHE.putIfAbsent(key, Optional.ofNullable(computed));
        return computed;
    }

    /** Memoised wrapper around {@link Planner#plan}, keyed as described in the class doc. */
    private static CityPlan planFor(long seed, Settlement settlement, TerrainSampler terrain, PlanParams params) {
        PlanKey key = new PlanKey(seed, settlement, params);
        CityPlan cached = PLAN_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        CityPlan computed = Planner.plan(seed, settlement, terrain, params);
        // get -> compute outside the map -> putIfAbsent: never computeIfAbsent. See class doc.
        CityPlan raced = PLAN_CACHE.putIfAbsent(key, computed);
        return raced != null ? raced : computed;
    }

    /**
     * Classifies the queried chunk against an already-resolved plan. Lots are checked before roads:
     * every lot is placed with a setback that keeps it clear of the carriageway (both
     * {@code LotSubdivider} and {@code RoadsideLots} enforce this by construction), so the two are not
     * expected to claim the same chunk, but "there is a building here" is the more specific fact
     * when a chunk happens to be small enough to ask about both.
     */
    private static Result classify(Settlement settlement, CityPlan plan, int chunkX, int chunkZ) {
        Rect chunkRect = new Rect(chunkX * 16, chunkZ * 16, chunkX * 16 + 15, chunkZ * 16 + 15);

        for (Lot lot : plan.lots()) {
            if (lot.footprint().intersects(chunkRect)) {
                return new Result.LotAt(settlement, lot);
            }
        }

        RoadGraph roads = plan.roads();
        List<RoadEdge> edges = roads.edges();
        for (int i = 0; i < edges.size(); i++) {
            RoadEdge e = edges.get(i);
            Vec2 a = roads.nodeAt(e.fromId()).pos();
            Vec2 b = roads.nodeAt(e.toId()).pos();
            if (segmentIntersectsRect(a, b, chunkRect)) {
                return new Result.Road(settlement, i);
            }
        }

        return new Result.OpenGround(settlement);
    }

    // --- Segment/rect intersection, exact in long arithmetic. Small enough, and specific enough to
    // this class's own notion of "does a road pass through this chunk", that it is kept local rather
    // than shared with the very similar copies in road/lot growth - the same choice those classes
    // already made about each other.

    private static boolean segmentIntersectsRect(Vec2 a, Vec2 b, Rect r) {
        if (r.contains(a) || r.contains(b)) {
            return true;
        }
        Vec2 topLeft = new Vec2(r.minX(), r.minZ());
        Vec2 topRight = new Vec2(r.maxX(), r.minZ());
        Vec2 bottomRight = new Vec2(r.maxX(), r.maxZ());
        Vec2 bottomLeft = new Vec2(r.minX(), r.maxZ());
        return segmentsIntersect(a, b, topLeft, topRight)
                || segmentsIntersect(a, b, topRight, bottomRight)
                || segmentsIntersect(a, b, bottomRight, bottomLeft)
                || segmentsIntersect(a, b, bottomLeft, topLeft);
    }

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
}
