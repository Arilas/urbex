package dev.krona.urbex.worldgen.lost;

import net.minecraft.world.level.block.state.properties.RailShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TransformTest {

    // --- total: no (transform, shape) pair may throw -------------------------------------------

    @Test
    public void transformIsTotalOverAllShapes() {
        for (Transform t : Transform.values()) {
            for (RailShape shape : RailShape.values()) {
                t.transform(shape);   // must not throw; MIRROR_Z x ASCENDING_EAST used to
            }
        }
    }

    // --- structural invariants -----------------------------------------------------------------

    @Test
    public void rotateNoneIsIdentity() {
        for (RailShape shape : RailShape.values()) {
            assertSame(shape, Transform.ROTATE_NONE.transform(shape));
        }
    }

    @Test
    public void rotationsCompose() {
        for (RailShape shape : RailShape.values()) {
            assertSame(Transform.ROTATE_180.transform(shape),
                    Transform.ROTATE_90.transform(Transform.ROTATE_90.transform(shape)), "180 = 90+90 for " + shape);
            assertSame(Transform.ROTATE_270.transform(shape),
                    Transform.ROTATE_90.transform(Transform.ROTATE_180.transform(shape)), "270 = 180+90 for " + shape);
            assertSame(shape,
                    Transform.ROTATE_90.transform(Transform.ROTATE_270.transform(shape)), "360 = id for " + shape);
        }
    }

    @Test
    public void mirrorsAreInvolutions() {
        for (Transform t : new Transform[]{Transform.MIRROR_X, Transform.MIRROR_Z, Transform.MIRROR_90_X}) {
            for (RailShape shape : RailShape.values()) {
                assertSame(shape, t.transform(t.transform(shape)), t + " twice on " + shape);
            }
        }
    }

    @Test
    public void mirror90IsRotate90ThenMirrorX() {
        // MIRROR_90_X's coordinate map is (x,z) -> (z,x), i.e. ROTATE_90 followed by MIRROR_X;
        // the rail mapping must agree with the coordinate mapping used to place the blocks.
        for (RailShape shape : RailShape.values()) {
            assertSame(Transform.MIRROR_X.transform(Transform.ROTATE_90.transform(shape)),
                    Transform.MIRROR_90_X.transform(shape), "MIRROR_90_X vs composition on " + shape);
        }
    }

    // --- geometric spot checks (derived from rotateX/rotateZ direction maps) -------------------

    @Test
    public void mirrorXFlipsOnlyEastWest() {
        // MIRROR_X maps x -> 15-x and leaves z alone: east/west swap, north/south stay.
        assertSame(RailShape.ASCENDING_WEST, Transform.MIRROR_X.transform(RailShape.ASCENDING_EAST));
        assertSame(RailShape.ASCENDING_NORTH, Transform.MIRROR_X.transform(RailShape.ASCENDING_NORTH));
        assertSame(RailShape.ASCENDING_SOUTH, Transform.MIRROR_X.transform(RailShape.ASCENDING_SOUTH));
        assertSame(RailShape.SOUTH_WEST, Transform.MIRROR_X.transform(RailShape.SOUTH_EAST));
        assertSame(RailShape.NORTH_SOUTH, Transform.MIRROR_X.transform(RailShape.NORTH_SOUTH));
        assertSame(RailShape.EAST_WEST, Transform.MIRROR_X.transform(RailShape.EAST_WEST));
    }

    @Test
    public void mirrorZFlipsOnlyNorthSouth() {
        assertSame(RailShape.ASCENDING_EAST, Transform.MIRROR_Z.transform(RailShape.ASCENDING_EAST));
        assertSame(RailShape.ASCENDING_SOUTH, Transform.MIRROR_Z.transform(RailShape.ASCENDING_NORTH));
        assertSame(RailShape.NORTH_EAST, Transform.MIRROR_Z.transform(RailShape.SOUTH_EAST));
        assertSame(RailShape.EAST_WEST, Transform.MIRROR_Z.transform(RailShape.EAST_WEST));
    }

    @Test
    public void mirror90TransposesAxes() {
        // (x,z) -> (z,x): N<->W, E<->S; the corner {S,E} maps onto itself.
        assertSame(RailShape.EAST_WEST, Transform.MIRROR_90_X.transform(RailShape.NORTH_SOUTH));
        assertSame(RailShape.NORTH_SOUTH, Transform.MIRROR_90_X.transform(RailShape.EAST_WEST));
        assertSame(RailShape.ASCENDING_SOUTH, Transform.MIRROR_90_X.transform(RailShape.ASCENDING_EAST));
        assertSame(RailShape.ASCENDING_WEST, Transform.MIRROR_90_X.transform(RailShape.ASCENDING_NORTH));
        assertSame(RailShape.SOUTH_EAST, Transform.MIRROR_90_X.transform(RailShape.SOUTH_EAST));
        assertSame(RailShape.NORTH_WEST, Transform.MIRROR_90_X.transform(RailShape.NORTH_WEST));
        assertSame(RailShape.SOUTH_WEST, Transform.MIRROR_90_X.transform(RailShape.NORTH_EAST));
    }

    @Test
    public void rotate90MapsClockwise() {
        // (x,z) -> (15-z, x): N->E->S->W->N
        assertSame(RailShape.ASCENDING_SOUTH, Transform.ROTATE_90.transform(RailShape.ASCENDING_EAST));
        assertSame(RailShape.ASCENDING_EAST, Transform.ROTATE_90.transform(RailShape.ASCENDING_NORTH));
        assertSame(RailShape.EAST_WEST, Transform.ROTATE_90.transform(RailShape.NORTH_SOUTH));
        // corner {S,E} -> {W,S}
        assertSame(RailShape.SOUTH_WEST, Transform.ROTATE_90.transform(RailShape.SOUTH_EAST));
    }

    // --- coordinate maps stay untouched by the rewrite -----------------------------------------

    @Test
    public void oppositeUndoesCoordinateTransform() {
        for (Transform t : Transform.values()) {
            Transform opp = t.getOpposite();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int tx = t.rotateX(x, z);
                    int tz = t.rotateZ(x, z);
                    assertEquals(x, opp.rotateX(tx, tz), t + " x roundtrip");
                    assertEquals(z, opp.rotateZ(tx, tz), t + " z roundtrip");
                }
            }
        }
    }
}
