package dev.krona.urbex.worldgen.lost;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.RailShape;

public enum Transform {
    ROTATE_NONE(Rotation.NONE, Mirror.NONE),
    ROTATE_90(Rotation.CLOCKWISE_90, Mirror.NONE),
    ROTATE_180(Rotation.CLOCKWISE_180, Mirror.NONE),
    ROTATE_270(Rotation.COUNTERCLOCKWISE_90, Mirror.NONE),
    // The mirror/rotation pairs below reproduce the coordinate maps of rotateX/rotateZ in
    // vanilla's application order (mirror first, then rotate). MIRROR_X flips x (east<->west),
    // MIRROR_Z flips z (north<->south), MIRROR_90_X is the transpose (x,z)->(z,x).
    MIRROR_X(Rotation.NONE, Mirror.FRONT_BACK),
    MIRROR_Z(Rotation.NONE, Mirror.LEFT_RIGHT),
    MIRROR_90_X(Rotation.CLOCKWISE_90, Mirror.LEFT_RIGHT);

    private final Rotation mcRotation;
    private final Mirror mcMirror;

    Transform(Rotation mcRotation, Mirror mcMirror) {
        this.mcRotation = mcRotation;
        this.mcMirror = mcMirror;
    }

    public Rotation getMcRotation() {
        return mcRotation;
    }

    public Mirror getMcMirror() {
        return mcMirror;
    }

    public Transform getOpposite() {
        return switch (this) {
            case ROTATE_NONE -> ROTATE_NONE;
            case ROTATE_270 -> ROTATE_90;
            case ROTATE_180 -> ROTATE_180;
            case ROTATE_90 -> ROTATE_270;
            case MIRROR_X -> MIRROR_X;
            case MIRROR_Z -> MIRROR_Z;
            case MIRROR_90_X -> MIRROR_90_X;
        };
    }

    public int rotateX(int x, int z) {
        return switch (this) {
            case ROTATE_NONE -> x;
            case ROTATE_90 -> 15 - z;
            case ROTATE_180 -> 15 - x;
            case ROTATE_270 -> z;
            case MIRROR_X -> 15 - x;
            case MIRROR_Z -> x;
            case MIRROR_90_X -> z;
        };
    }

    public int rotateZ(int x, int z) {
        return switch (this) {
            case ROTATE_NONE -> z;
            case ROTATE_90 -> x;
            case ROTATE_180 -> 15 - z;
            case ROTATE_270 -> 15 - x;
            case MIRROR_X -> z;
            case MIRROR_Z -> 15 - z;
            case MIRROR_90_X -> x;
        };
    }

    /**
     * The horizontal direction this transform sends {@code direction} to. Same geometry as
     * {@link #rotateX}/{@link #rotateZ}, expressed through the vanilla enums so rails and
     * rotated/mirrored block states agree on what the transform means.
     */
    public Direction transform(Direction direction) {
        return mcRotation.rotate(mcMirror.mirror(direction));
    }

    /**
     * Rail shapes under this transform, derived from {@link #transform(Direction)} rather than
     * hand-written tables: the old table had holes that threw (MIRROR_Z on an ascending east/west
     * rail) and arms that disagreed with the coordinate maps.
     */
    public RailShape transform(RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH, EAST_WEST -> straight(transform(axis(shape)));
            case ASCENDING_EAST -> ascending(transform(Direction.EAST));
            case ASCENDING_WEST -> ascending(transform(Direction.WEST));
            case ASCENDING_NORTH -> ascending(transform(Direction.NORTH));
            case ASCENDING_SOUTH -> ascending(transform(Direction.SOUTH));
            case SOUTH_EAST -> corner(transform(Direction.SOUTH), transform(Direction.EAST));
            case SOUTH_WEST -> corner(transform(Direction.SOUTH), transform(Direction.WEST));
            case NORTH_WEST -> corner(transform(Direction.NORTH), transform(Direction.WEST));
            case NORTH_EAST -> corner(transform(Direction.NORTH), transform(Direction.EAST));
        };
    }

    private static Direction axis(RailShape straight) {
        return straight == RailShape.NORTH_SOUTH ? Direction.NORTH : Direction.EAST;
    }

    private static RailShape straight(Direction axis) {
        return axis.getAxis() == Direction.Axis.Z ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST;
    }

    private static RailShape ascending(Direction towards) {
        return switch (towards) {
            case EAST -> RailShape.ASCENDING_EAST;
            case WEST -> RailShape.ASCENDING_WEST;
            case NORTH -> RailShape.ASCENDING_NORTH;
            case SOUTH -> RailShape.ASCENDING_SOUTH;
            default -> throw new IllegalArgumentException("Not a horizontal direction: " + towards);
        };
    }

    private static RailShape corner(Direction a, Direction b) {
        Direction northSouth = a.getAxis() == Direction.Axis.Z ? a : b;
        Direction eastWest = a.getAxis() == Direction.Axis.X ? a : b;
        if (northSouth == Direction.SOUTH) {
            return eastWest == Direction.EAST ? RailShape.SOUTH_EAST : RailShape.SOUTH_WEST;
        } else {
            return eastWest == Direction.EAST ? RailShape.NORTH_EAST : RailShape.NORTH_WEST;
        }
    }
}
