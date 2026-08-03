package dev.krona.urbex.plan;

import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.lot.Lot;
import dev.krona.urbex.plan.road.RoadEdge;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PlanQueryTest {

    private static final PlanParams P = PlanParams.defaults();
    private static final TerrainSampler FLAT = new FlatTerrain(64);

    @Test
    void unsettledChunkReturnsNone() {
        Optional<int[]> empty = findChunkWhere(c -> SettlementMap.at(1337L, c[0], c[1], P) == null);
        assertTrue(empty.isPresent(), "expected at least one unsettled chunk in the scanned range");
        int[] c = empty.get();

        PlanQuery.Result result = PlanQuery.at(1337L, c[0], c[1], FLAT, P);
        assertInstanceOf(PlanQuery.Result.None.class, result);
    }

    @Test
    void chunkInsideALotReturnsLotAtForTheCoveringSettlement() {
        Settlement s = findSettlementOfClass(1337L, SettlementClass.TOWN, 800);
        CityPlan plan = Planner.plan(1337L, s, FLAT, P);
        assertTrue(!plan.lots().isEmpty(), "fixture needs at least one lot");

        Lot lot = plan.lots().get(0);
        Vec2 centre = lot.footprint().center();
        int chunkX = Math.floorDiv(centre.x(), 16);
        int chunkZ = Math.floorDiv(centre.z(), 16);

        PlanQuery.Result result = PlanQuery.at(1337L, chunkX, chunkZ, FLAT, P);
        PlanQuery.Result.LotAt lotAt = assertInstanceOf(PlanQuery.Result.LotAt.class, result);
        assertEquals(s, lotAt.settlement());
        Rect chunkRect = new Rect(chunkX * 16, chunkZ * 16, chunkX * 16 + 15, chunkZ * 16 + 15);
        assertTrue(lotAt.lot().footprint().intersects(chunkRect));
    }

    /**
     * Scans a TOWN's road edges for a chunk that a road actually crosses but no lot's footprint
     * touches, so the priority {@link PlanQuery} gives lots over roads (see its class doc) cannot be
     * the reason a {@code Road} result comes back - this chunk has no lot to prefer in the first place.
     */
    @Test
    void chunkOnARoadWithNoLotReturnsRoad() {
        Settlement s = findSettlementOfClass(1337L, SettlementClass.TOWN, 800);
        CityPlan plan = Planner.plan(1337L, s, FLAT, P);

        for (RoadEdge e : plan.roads().edges()) {
            Vec2 a = plan.roads().nodeAt(e.fromId()).pos();
            Vec2 b = plan.roads().nodeAt(e.toId()).pos();
            for (Vec2 point : List.of(a, midpoint(a, b), b)) {
                int chunkX = Math.floorDiv(point.x(), 16);
                int chunkZ = Math.floorDiv(point.z(), 16);
                Rect chunkRect = new Rect(chunkX * 16, chunkZ * 16, chunkX * 16 + 15, chunkZ * 16 + 15);
                boolean anyLotHere = plan.lots().stream().anyMatch(l -> l.footprint().intersects(chunkRect));
                if (anyLotHere) {
                    continue;
                }

                PlanQuery.Result result = PlanQuery.at(1337L, chunkX, chunkZ, FLAT, P);
                if (result instanceof PlanQuery.Result.Road road) {
                    assertEquals(s, road.settlement());
                    return;
                }
            }
        }
        fail("found no chunk with a road but no lot to test Road against, across every edge of a TOWN fixture");
    }

    @Test
    void repeatedQueriesForTheSameChunkAgree() {
        Settlement s = new Settlement(SettlementClass.TOWN, 0, 0);
        PlanQuery.Result first = PlanQuery.at(1337L, s.centerChunkX(), s.centerChunkZ(), FLAT, P);
        PlanQuery.Result second = PlanQuery.at(1337L, s.centerChunkX(), s.centerChunkZ(), FLAT, P);
        assertEquals(first, second);
    }

    /**
     * The cache in front of {@link PlanQuery#at} must use get / compute-outside-the-map /
     * putIfAbsent, never {@code computeIfAbsent} - see the class doc for why. This does not exercise
     * the specific mutual-recursion shape that deadlocked the earlier caches (nothing in this module
     * calls back into {@code PlanQuery}), but it is the concurrency shape a later sub-project's
     * worldgen worker threads will actually produce: many threads asking for the same settlement's
     * plan at once. Two threads are released from the same {@link CountDownLatch} so they race on
     * exactly the same cache keys, and every {@link Future#get} carries a timeout so a deadlock fails
     * this test instead of hanging the build.
     */
    @Test
    void concurrentQueriesForTheSameSettlementAgreeAndDoNotDeadlock() throws Exception {
        Settlement s = new Settlement(SettlementClass.TOWN, 0, 0);
        int chunkX = s.centerChunkX();
        int chunkZ = s.centerChunkZ();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(2);
            Callable<PlanQuery.Result> task = () -> {
                gate.countDown();
                gate.await();
                return PlanQuery.at(1337L, chunkX, chunkZ, FLAT, P);
            };

            Future<PlanQuery.Result> f1 = pool.submit(task);
            Future<PlanQuery.Result> f2 = pool.submit(task);

            PlanQuery.Result r1 = f1.get(10, TimeUnit.SECONDS);
            PlanQuery.Result r2 = f2.get(10, TimeUnit.SECONDS);

            assertEquals(r1, r2);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A wider stress pass: several threads, several distinct settlements, all racing on the shared
     * static caches at once. Every settlement class is included so the block-free spine path
     * (see {@code CityPlan}'s doc) races through the exact same cache code as the block-subdivided one.
     */
    @Test
    void manyThreadsQueryingManySettlementsConcurrentlyDoNotDeadlock() throws Exception {
        List<Settlement> settlements = List.of(
                new Settlement(SettlementClass.HAMLET, 5, 5),
                new Settlement(SettlementClass.VILLAGE, 9, 9),
                new Settlement(SettlementClass.TOWN, 1, 1),
                new Settlement(SettlementClass.CITY, 2, 2)
        );

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch gate = new CountDownLatch(8);
            List<Future<PlanQuery.Result>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Settlement s = settlements.get(i % settlements.size());
                Callable<PlanQuery.Result> task = () -> {
                    gate.countDown();
                    gate.await();
                    return PlanQuery.at(1337L, s.centerChunkX(), s.centerChunkZ(), FLAT, P);
                };
                futures.add(pool.submit(task));
            }
            for (Future<PlanQuery.Result> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Vec2 midpoint(Vec2 a, Vec2 b) {
        return new Vec2((a.x() + b.x()) / 2, (a.z() + b.z()) / 2);
    }

    private static Optional<int[]> findChunkWhere(Predicate<int[]> predicate) {
        for (int x = -50; x < 50; x++) {
            for (int z = -50; z < 50; z++) {
                int[] c = {x, z};
                if (predicate.test(c)) {
                    return Optional.of(c);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * A hand-built {@code Settlement(TOWN, 0, 0)} is a valid input to {@link Planner#plan} (it plans
     * for whatever settlement it is given), but {@link SettlementMap#at} - which {@link PlanQuery}
     * actually calls - almost never places a real settlement at exactly the origin, because every
     * class's centre is jittered within its lattice cell (see {@code SettlementMap}'s class doc). A
     * test that wants {@link PlanQuery#at} to agree with a directly-computed {@link CityPlan} has to
     * use a settlement {@code SettlementMap} would itself report for this seed, not an arbitrary one -
     * otherwise every query below just resolves to {@code None} and the test would trivially "pass"
     * for the wrong reason. This mirrors {@code SettlementMapTest.firstSettlement}'s exhaustive,
     * step-1 scan.
     */
    private static Settlement findSettlementOfClass(long seed, SettlementClass cls, int span) {
        for (int cx = 0; cx < span; cx++) {
            for (int cz = 0; cz < span; cz++) {
                Settlement s = SettlementMap.at(seed, cx, cz, P);
                if (s != null && s.cls() == cls) {
                    return s;
                }
            }
        }
        throw new AssertionError("no " + cls + " settlement found while scanning " + span + " chunks");
    }
}
