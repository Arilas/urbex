package dev.krona.urbex.plan;

import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the layout for a few seeds so a refactor that silently changes it fails a test rather than
 * being noticed by eye six weeks later. Regenerate deliberately, never to make the build green.
 * <p>
 * Whole-branch review (I4) found this pinned about half the branch: {@code HAMLET}/{@code VILLAGE}
 * (the {@code SpineGrowth}/{@code RoadsideLots} path) were pinned by nothing at all, and the fold
 * itself skipped {@code sizeClass}, {@code frontingEdgeIndex}, ground height and every block outline
 * and district assignment - a regression in, say, fronting-edge selection could change every lot's
 * behaviour without moving a single digest. {@link #hamletLayoutIsPinned}/{@link #villageLayoutIsPinned}
 * close the first gap; the widened {@link #digest} closes the second. All five digests below were
 * regenerated in the same commit as the road-clearance (C1/I2) and ground-height-range (I3) fixes,
 * since both change the numbers a correct fold has to fold - moving a digest here is not itself a
 * finding, only failing to update one deliberately would be.
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
     * {@code HAMLET} and {@code VILLAGE} take an entirely different generator
     * ({@code SpineGrowth}/{@code RoadsideLots} in place of {@code ArterialGrowth}/{@code
     * BlockExtractor}/{@code LotSubdivider}), so pinning only TOWN/CITY left two of the branch's five
     * settlement classes with no regression coverage at all - a change to spine growth, branch
     * placement or roadside lot geometry could pass every other test in the suite and still silently
     * change what a hamlet or village looks like. Flat terrain, same as the original two pins: a
     * spine settlement's blocks list is always empty (see {@code Planner.plan}), so there is no
     * separate "river spine" pin to add the way {@link #riverCityLayoutIsPinned} adds one for CITY -
     * water only ever shows up in {@code waterSides}, which the fold already folds.
     */
    @Test
    void hamletLayoutIsPinned() {
        assertEquals(HAMLET_DIGEST, digest(1337L, SettlementClass.HAMLET, new FlatTerrain(64)));
    }

    @Test
    void villageLayoutIsPinned() {
        assertEquals(VILLAGE_DIGEST, digest(1337L, SettlementClass.VILLAGE, new FlatTerrain(64)));
    }

    /**
     * Takes {@code terrain} as a parameter, unlike the brief's original two-argument sketch, purely so
     * {@link #riverCityLayoutIsPinned} can reuse it instead of hand-duplicating the fold loop a third
     * time.
     * <p>
     * Widened from the brief's original fold (edge endpoints/bridge flag, lot footprint/district/
     * waterSides) after review found it skipped enough of {@link CityPlan} that whole classes of
     * regression could pass unnoticed: node positions (only referenced by id before, never by where
     * they actually are), each edge's {@code RoadClass} and water span, every block's id, assigned
     * district and full outline ring, and - per lot - the id, {@code sizeClass},
     * {@code frontingEdgeIndex}, and both {@code minGroundHeight}/{@code maxGroundHeight} (replacing
     * the single {@code groundHeight} sample review's I3 removed).
     */
    private static String digest(long seed, SettlementClass cls, TerrainSampler terrain) {
        CityPlan plan = Planner.plan(seed, new Settlement(cls, 0, 0), terrain, P);
        long h = 0xCBF29CE484222325L;

        for (var n : plan.roads().nodes()) {
            h = fold(h, n.pos().x());
            h = fold(h, n.pos().z());
        }
        for (var e : plan.roads().edges()) {
            h = fold(h, e.fromId());
            h = fold(h, e.toId());
            h = fold(h, e.cls().ordinal());
            h = fold(h, e.bridge() ? 1 : 0);
            h = fold(h, e.waterSpanBlocks());
        }
        for (var b : plan.blocks()) {
            h = fold(h, b.id());
            District d = plan.districts().get(b.id());
            h = fold(h, d.ordinal());
            for (Vec2 v : b.outline().ring()) {
                h = fold(h, v.x());
                h = fold(h, v.z());
            }
        }
        for (var l : plan.lots()) {
            h = fold(h, l.id());
            h = fold(h, l.footprint().minX());
            h = fold(h, l.footprint().minZ());
            h = fold(h, l.footprint().maxX());
            h = fold(h, l.footprint().maxZ());
            h = fold(h, l.district().ordinal());
            h = fold(h, l.sizeClass());
            h = fold(h, l.frontingEdgeIndex());
            h = fold(h, l.minGroundHeight());
            h = fold(h, l.maxGroundHeight());
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
    private static final String TOWN_DIGEST = "3872bbe8f8d64063";
    private static final String CITY_DIGEST = "4d87a4e33d2b8640";
    private static final String RIVER_CITY_DIGEST = "027e1a702ef21874";
    private static final String HAMLET_DIGEST = "5d310fc5a1e57f70";
    private static final String VILLAGE_DIGEST = "6a211e2c72fe1581";
}
