package dev.krona.urbex.plan;

import dev.krona.urbex.plan.geom.Rect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementMapTest {

    private static final PlanParams P = PlanParams.defaults();
    private static final long SEED = 1337L;

    @Test
    void theSameCoordinateAlwaysGivesTheSameAnswer() {
        for (int i = 0; i < 200; i++) {
            Settlement a = SettlementMap.at(SEED, i, -i, P);
            Settlement b = SettlementMap.at(SEED, i, -i, P);
            assertEquals(a, b, "settlement at " + i + "," + -i + " was not stable");
        }
    }

    @Test
    void differentSeedsPlaceSettlementsDifferently() {
        List<Settlement> a = scan(SEED, 400);
        List<Settlement> b = scan(9999L, 400);
        assertTrue(!a.equals(b), "two seeds produced identical settlement placement");
    }

    @Test
    void everyChunkOfASettlementReportsThatSameSettlement() {
        Settlement found = firstSettlement(SEED, 2000);
        assertNotNull(found, "no settlement found while scanning 2000 chunks");
        Rect bounds = found.boundsChunks();
        for (int cx = bounds.minX(); cx <= bounds.maxX(); cx++) {
            for (int cz = bounds.minZ(); cz <= bounds.maxZ(); cz++) {
                assertEquals(found, SettlementMap.at(SEED, cx, cz, P),
                        "chunk " + cx + "," + cz + " inside the settlement reported something else");
            }
        }
    }

    @Test
    void settlementsNeverOverlap() {
        Set<Settlement> seen = new HashSet<>();
        for (int cx = -600; cx < 600; cx += 2) {
            for (int cz = -600; cz < 600; cz += 2) {
                Settlement s = SettlementMap.at(SEED, cx, cz, P);
                if (s != null) {
                    seen.add(s);
                }
            }
        }
        List<Settlement> all = new ArrayList<>(seen);
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                assertTrue(!all.get(i).boundsChunks().intersects(all.get(j).boundsChunks()),
                        "overlapping settlements: " + all.get(i) + " and " + all.get(j));
            }
        }
    }

    @Test
    void smallerClassesAreMoreCommonThanLargerOnes() {
        Map<SettlementClass, Integer> counts = new EnumMap<>(SettlementClass.class);
        Set<Settlement> seen = new HashSet<>();
        for (int cx = -800; cx < 800; cx += 2) {
            for (int cz = -800; cz < 800; cz += 2) {
                Settlement s = SettlementMap.at(SEED, cx, cz, P);
                if (s != null && seen.add(s)) {
                    counts.merge(s.cls(), 1, Integer::sum);
                }
            }
        }
        assertTrue(counts.getOrDefault(SettlementClass.HAMLET, 0)
                        > counts.getOrDefault(SettlementClass.TOWN, 0),
                "hamlets should outnumber towns, got " + counts);
        assertTrue(counts.getOrDefault(SettlementClass.TOWN, 0)
                        >= counts.getOrDefault(SettlementClass.METROPOLIS, 0),
                "towns should be at least as common as metropolises, got " + counts);
    }

    @Test
    void aSettlementNeverLeavesItsOwnCell() {
        Set<Settlement> seen = new HashSet<>();
        for (int cx = -400; cx < 400; cx += 2) {
            for (int cz = -400; cz < 400; cz += 2) {
                Settlement s = SettlementMap.at(SEED, cx, cz, P);
                if (s != null) {
                    seen.add(s);
                }
            }
        }
        for (Settlement s : seen) {
            int cell = s.cls().cellSizeChunks();
            Rect b = s.boundsChunks();
            assertEquals(Math.floorDiv(b.minX(), cell), Math.floorDiv(b.maxX(), cell),
                    s + " spans two cells in x");
            assertEquals(Math.floorDiv(b.minZ(), cell), Math.floorDiv(b.maxZ(), cell),
                    s + " spans two cells in z");
        }
    }

    private static List<Settlement> scan(long seed, int span) {
        List<Settlement> out = new ArrayList<>();
        for (int cx = 0; cx < span; cx += 4) {
            for (int cz = 0; cz < span; cz += 4) {
                out.add(SettlementMap.at(seed, cx, cz, P));
            }
        }
        return out;
    }

    private static Settlement firstSettlement(long seed, int span) {
        for (int cx = 0; cx < span; cx++) {
            for (int cz = 0; cz < span; cz++) {
                Settlement s = SettlementMap.at(seed, cx, cz, P);
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }
}
