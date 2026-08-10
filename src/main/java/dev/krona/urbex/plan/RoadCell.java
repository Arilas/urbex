package dev.krona.urbex.plan;

import javax.annotation.Nullable;

import java.util.List;

/**
 * One chunk of a {@link RoadField}.
 *
 * <p>Generation consumes only {@link #type} and the four connection flags. Everything from
 * {@link #blockX} onward is diagnostic - it exists for {@code /urbex debug} and the preview, and no
 * rendering decision may read it. A {@link RoadField} with no notion of primary blocks supplies
 * zeroes and empty lists.
 */
public record RoadCell(RoadType type,
                       boolean north, boolean south, boolean west, boolean east,
                       int blockX, int blockZ,
                       int westX, int northZ, int eastX, int southZ,
                       double density,
                       List<Integer> secondaryX, List<Integer> secondaryZ,
                       @Nullable TertiarySegment tertiary) {

    public RoadCell {
        secondaryX = List.copyOf(secondaryX);
        secondaryZ = List.copyOf(secondaryZ);
    }

    /**
     * No caller in this codebase today - every consumer here branches on {@link #type} directly,
     * since it also needs to know <em>which</em> road class. Kept anyway as part of {@link RoadField}'s
     * public data contract, alongside {@link #connects}: a future second {@code RoadField}
     * implementation's own consumers, or a test, get the "is this a road at all" predicate without
     * repeating {@code type() != RoadType.NONE} against a record whose fields beyond {@code type} are
     * explicitly diagnostic-only.
     */
    public boolean isRoad() {
        return type != RoadType.NONE;
    }

    public boolean connects(RoadDirection direction) {
        return switch (direction) {
            case NORTH -> north;
            case SOUTH -> south;
            case WEST -> west;
            case EAST -> east;
        };
    }
}
