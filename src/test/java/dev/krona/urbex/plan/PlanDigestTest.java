package dev.krona.urbex.plan;

import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the layout for a few seeds so a refactor that silently changes it fails a test rather than
 * being noticed by eye six weeks later. Regenerate deliberately, never to make the build green.
 */
class PlanDigestTest {

    private static final PlanParams P = PlanParams.defaults();

    @Test
    void townLayoutIsPinned() {
        assertEquals(TOWN_DIGEST, digest(1337L, SettlementClass.TOWN, new FlatTerrain(64)));
    }

    @Test
    void cityLayoutIsPinned() {
        assertEquals(CITY_DIGEST, digest(1337L, SettlementClass.CITY, new FlatTerrain(64)));
    }

    /**
     * A third pin, added on top of the brief's original two flat-ground digests: bridges, waterfront
     * districts and {@code WaterShape} are all invisible on flat ground ({@code FlatTerrain} never
     * reports water), so {@link #townLayoutIsPinned} and {@link #cityLayoutIsPinned} cannot catch a
     * regression in any of them even though {@code waterSides} and {@code bridge} are already folded
     * into every digest below. CITY is used here (rather than TOWN) because its larger spoke/ring
     * network is more likely to actually put a bridge across the river at seed 1337, giving this pin
     * something real to protect rather than incidentally passing with every water field at zero.
     */
    @Test
    void riverCityLayoutIsPinned() {
        assertEquals(RIVER_CITY_DIGEST, digest(1337L, SettlementClass.CITY, new RiverTerrain(64, 0, 24)));
    }

    /**
     * Takes {@code terrain} as a parameter, unlike the brief's original two-argument sketch, purely so
     * {@link #riverCityLayoutIsPinned} can reuse it instead of hand-duplicating the fold loop a third
     * time; the fold itself is unchanged from the brief.
     */
    private static String digest(long seed, SettlementClass cls, TerrainSampler terrain) {
        CityPlan plan = Planner.plan(seed, new Settlement(cls, 0, 0), terrain, P);
        long h = 0xCBF29CE484222325L;
        for (var e : plan.roads().edges()) {
            h = fold(h, e.fromId());
            h = fold(h, e.toId());
            h = fold(h, e.bridge() ? 1 : 0);
        }
        for (var l : plan.lots()) {
            h = fold(h, l.footprint().minX());
            h = fold(h, l.footprint().minZ());
            h = fold(h, l.footprint().maxX());
            h = fold(h, l.footprint().maxZ());
            h = fold(h, l.district().ordinal());
            h = fold(h, l.waterSides());
        }
        return String.format("%016x", h);
    }

    private static long fold(long h, long v) {
        return (h ^ v) * 0x100000001B3L;
    }

    // Generated once by running the tests and pasting the actual values. Do not edit to fix a
    // failure - a changed digest means the layout changed, which is either the point of your
    // commit or a bug.
    private static final String TOWN_DIGEST = "b03e129fd9975b67";
    private static final String CITY_DIGEST = "e421c2bb0efd2def";
    private static final String RIVER_CITY_DIGEST = "0cd67aa0214b765c";
}
