package dev.krona.urbex.plan.geom;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeomTest {

    @Test
    void rectMeasuresInclusiveBounds() {
        Rect r = new Rect(0, 0, 15, 15);
        assertEquals(16, r.width());
        assertEquals(16, r.depth());
        assertEquals(256, r.area());
    }

    @Test
    void rectRejectsInvertedBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Rect(10, 0, 0, 10));
    }

    @Test
    void rectContainmentIncludesItsEdges() {
        Rect r = new Rect(0, 0, 10, 10);
        assertTrue(r.contains(new Vec2(0, 0)));
        assertTrue(r.contains(new Vec2(10, 10)));
        assertFalse(r.contains(new Vec2(11, 5)));
    }

    @Test
    void rectsTouchingAtAnEdgeIntersect() {
        assertTrue(new Rect(0, 0, 10, 10).intersects(new Rect(10, 10, 20, 20)));
        assertFalse(new Rect(0, 0, 10, 10).intersects(new Rect(11, 11, 20, 20)));
    }

    @Test
    void polygonAreaSignIndicatesWinding() {
        List<Vec2> ccw = List.of(new Vec2(0, 0), new Vec2(10, 0), new Vec2(10, 10), new Vec2(0, 10));
        assertTrue(new Polygon(ccw).isCounterClockwise());
        assertFalse(new Polygon(ccw.reversed()).isCounterClockwise());
    }

    @Test
    void polygonAreaIsExactForASquare() {
        Polygon square = new Polygon(List.of(
                new Vec2(0, 0), new Vec2(10, 0), new Vec2(10, 10), new Vec2(0, 10)));
        assertEquals(200, Math.abs(square.signedDoubleArea()));
    }

    @Test
    void polygonContainmentHandlesAConcaveShape() {
        // An L-shape: the notch must be outside.
        Polygon l = new Polygon(List.of(
                new Vec2(0, 0), new Vec2(20, 0), new Vec2(20, 10),
                new Vec2(10, 10), new Vec2(10, 20), new Vec2(0, 20)));
        assertTrue(l.contains(new Vec2(5, 5)));
        assertTrue(l.contains(new Vec2(5, 15)));
        assertFalse(l.contains(new Vec2(15, 15)));
    }

    @Test
    void polygonRejectsDegenerateRings() {
        assertThrows(IllegalArgumentException.class,
                () -> new Polygon(List.of(new Vec2(0, 0), new Vec2(1, 1))));
    }
}
