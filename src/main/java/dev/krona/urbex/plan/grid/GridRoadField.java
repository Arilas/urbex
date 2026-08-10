package dev.krona.urbex.plan.grid;

import dev.krona.urbex.plan.Hash;
import dev.krona.urbex.plan.RoadCell;
import dev.krona.urbex.plan.RoadDirection;
import dev.krona.urbex.plan.RoadField;
import dev.krona.urbex.plan.RoadType;
import dev.krona.urbex.plan.TertiarySegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure, order-independent grid {@link RoadField}, ported from upstream 1.20-7.5.0's
 * {@code HierarchicalStreetPlanner}.
 *
 * <p>This class deliberately knows nothing about cities or buildings. Callers clip the
 * mathematical field to the city mask via {@code EffectiveRoad}. A future terrain-aware planner
 * can implement {@link RoadField} the same way, without touching anything that consumes it.
 *
 * <p>A primary grid runs at {@code settings.primarySpacingX()/Z()} candidate spacing; each
 * candidate is either forced active (every {@code primaryForceEvery}th one) or optionally active
 * by a hashed roll. Between two active primary lines lies a "block", which gets a hashed density
 * and a scatter of secondary roads along each axis, and - inside the cells those secondaries carve
 * up - at most one tertiary access road stub.
 */
public final class GridRoadField implements RoadField {

    private final long seed;
    private final GridSettings settings;
    private final int primaryOffsetX;
    private final int primaryOffsetZ;

    public GridRoadField(long seed, String dimensionId, GridSettings settings) {
        this.seed = seed ^ stableStringHash(dimensionId);
        this.settings = settings;
        this.primaryOffsetX = Hash.index(
                Hash.at(this.seed, 0, 0, GridPurpose.PRIMARY_X_OFFSET.key()), settings.primarySpacingX());
        this.primaryOffsetZ = Hash.index(
                Hash.at(this.seed, 0, 0, GridPurpose.PRIMARY_Z_OFFSET.key()), settings.primarySpacingZ());
    }

    @Override
    public RoadCell at(int chunkX, int chunkZ) {
        RawRoad raw = rawAt(chunkX, chunkZ);
        BlockLayout block = raw.block();
        boolean isRoad = raw.type() != RoadType.NONE;
        boolean north = isRoad && typeAt(chunkX, chunkZ - 1) != RoadType.NONE;
        boolean south = isRoad && typeAt(chunkX, chunkZ + 1) != RoadType.NONE;
        boolean west = isRoad && typeAt(chunkX - 1, chunkZ) != RoadType.NONE;
        boolean east = isRoad && typeAt(chunkX + 1, chunkZ) != RoadType.NONE;
        return new RoadCell(raw.type(), north, south, west, east,
                block.blockX(), block.blockZ(), block.westX(), block.northZ(), block.eastX(), block.southZ(),
                block.density(), block.secondaryX(), block.secondaryZ(), raw.tertiary());
    }

    /**
     * The same classification {@link #rawAt} performs, without the block layout the caller does not
     * want. A primary road is decided by the chunk's coordinate alone, so answering that case first
     * skips {@link #blockLayout}: a secondary-position sort whose comparator hashes twice per
     * comparison, over up to a full primary spacing of candidates per axis. This sits on the
     * worldgen hot path - {@link #at} probes four neighbours and only reads their class, and the
     * multi-building conflict check probes every chunk under a footprint.
     *
     * <p>Pure and output-identical to {@code rawAt(x, z).type()} by construction: the branch order
     * is the same and {@code blockLayout} has no side effects, so hoisting the primary test above it
     * cannot change the answer.
     */
    @Override
    public RoadType typeAt(int chunkX, int chunkZ) {
        if (isVerticalPrimary(chunkX) || isHorizontalPrimary(chunkZ)) {
            return RoadType.PRIMARY;
        }
        BlockLayout block = blockLayout(chunkX, chunkZ);
        if (block.secondaryX().contains(chunkX) || block.secondaryZ().contains(chunkZ)) {
            return RoadType.SECONDARY;
        }
        TertiarySegment tertiary = tertiarySegment(block, chunkX, chunkZ);
        return tertiary != null && tertiary.contains(chunkX, chunkZ) ? RoadType.TERTIARY : RoadType.NONE;
    }

    private RawRoad rawAt(int chunkX, int chunkZ) {
        BlockLayout block = blockLayout(chunkX, chunkZ);
        boolean verticalPrimary = isVerticalPrimary(chunkX);
        boolean horizontalPrimary = isHorizontalPrimary(chunkZ);
        if (verticalPrimary || horizontalPrimary) {
            return new RawRoad(RoadType.PRIMARY, block, null);
        }
        if (block.secondaryX().contains(chunkX) || block.secondaryZ().contains(chunkZ)) {
            return new RawRoad(RoadType.SECONDARY, block, null);
        }
        TertiarySegment tertiary = tertiarySegment(block, chunkX, chunkZ);
        if (tertiary != null && tertiary.contains(chunkX, chunkZ)) {
            return new RawRoad(RoadType.TERTIARY, block, tertiary);
        }
        return new RawRoad(RoadType.NONE, block, tertiary);
    }

    private BlockLayout blockLayout(int chunkX, int chunkZ) {
        // floorDiv is essential here: truncating division would create a seam at negative
        // coordinates. Candidate indices identify blocks even though optional candidates may be
        // absent. The active west/north line is the inclusive bound; forced candidates cap each
        // search at primaryForceEvery.
        int candidateX = Math.toIntExact(Math.floorDiv((long) chunkX - primaryOffsetX, settings.primarySpacingX()));
        int candidateZ = Math.toIntExact(Math.floorDiv((long) chunkZ - primaryOffsetZ, settings.primarySpacingZ()));
        int blockX = findActiveAtOrBefore(candidateX, true);
        int blockZ = findActiveAtOrBefore(candidateZ, false);
        int nextBlockX = findActiveAfter(blockX, true);
        int nextBlockZ = findActiveAfter(blockZ, false);
        int westX = candidateCoordinate(primaryOffsetX, blockX, settings.primarySpacingX());
        int northZ = candidateCoordinate(primaryOffsetZ, blockZ, settings.primarySpacingZ());
        int eastX = candidateCoordinate(primaryOffsetX, nextBlockX, settings.primarySpacingX());
        int southZ = candidateCoordinate(primaryOffsetZ, nextBlockZ, settings.primarySpacingZ());
        int spacingX = eastX - westX;
        int spacingZ = southZ - northZ;
        double density = Hash.unit(Hash.at(seed, blockX, blockZ, GridPurpose.DENSITY.key()));
        int countX = selectCount(settings.secondaryMinCountX(), settings.secondaryMaxCountX(), density,
                Hash.at(seed, blockX, blockZ, GridPurpose.SECONDARY_X_COUNT.key()));
        int countZ = selectCount(settings.secondaryMinCountZ(), settings.secondaryMaxCountZ(), density,
                Hash.at(seed, blockX, blockZ, GridPurpose.SECONDARY_Z_COUNT.key()));
        List<Integer> secondaryX = selectSecondaryPositions(blockX, blockZ, westX, spacingX, countX,
                GridPurpose.SECONDARY_X_POSITION);
        List<Integer> secondaryZ = selectSecondaryPositions(blockX, blockZ, northZ, spacingZ, countZ,
                GridPurpose.SECONDARY_Z_POSITION);
        return new BlockLayout(blockX, blockZ, westX, northZ, eastX, southZ, density, secondaryX, secondaryZ);
    }

    private boolean isVerticalPrimary(int chunkX) {
        long relative = (long) chunkX - primaryOffsetX;
        if (Math.floorMod(relative, settings.primarySpacingX()) != 0) {
            return false;
        }
        int candidate = Math.toIntExact(Math.floorDiv(relative, settings.primarySpacingX()));
        return isActivePrimaryCandidate(candidate, true);
    }

    private boolean isHorizontalPrimary(int chunkZ) {
        long relative = (long) chunkZ - primaryOffsetZ;
        if (Math.floorMod(relative, settings.primarySpacingZ()) != 0) {
            return false;
        }
        int candidate = Math.toIntExact(Math.floorDiv(relative, settings.primarySpacingZ()));
        return isActivePrimaryCandidate(candidate, false);
    }

    private boolean isActivePrimaryCandidate(int candidate, boolean xAxis) {
        if (Math.floorMod(candidate, settings.primaryForceEvery()) == 0) {
            return true;
        }
        GridPurpose purpose = xAxis ? GridPurpose.PRIMARY_X_ACTIVATION : GridPurpose.PRIMARY_Z_ACTIVATION;
        long h = Hash.at(seed, xAxis ? candidate : 0, xAxis ? 0 : candidate, purpose.key());
        return Hash.unit(h) < settings.primaryOptionalChance();
    }

    private int findActiveAtOrBefore(int candidate, boolean xAxis) {
        for (int distance = 0; distance < settings.primaryForceEvery(); distance++) {
            int current = Math.subtractExact(candidate, distance);
            if (isActivePrimaryCandidate(current, xAxis)) {
                return current;
            }
        }
        throw new IllegalStateException("No forced primary candidate found");
    }

    private int findActiveAfter(int candidate, boolean xAxis) {
        for (int distance = 1; distance <= settings.primaryForceEvery(); distance++) {
            int current = Math.addExact(candidate, distance);
            if (isActivePrimaryCandidate(current, xAxis)) {
                return current;
            }
        }
        throw new IllegalStateException("No forced primary candidate found");
    }

    private static int candidateCoordinate(int offset, int candidate, int spacing) {
        return Math.toIntExact((long) offset + (long) candidate * spacing);
    }

    private int selectCount(int minimum, int maximum, double density, long variationHash) {
        if (minimum == maximum) {
            return minimum;
        }
        // Density is shared by both axes; a smaller dedicated component prevents every block from
        // receiving identical X/Z counts.
        double variedDensity = density * .75 + Hash.unit(variationHash) * .25;
        int count = minimum + (int) Math.floor(variedDensity * (maximum - minimum + 1));
        return Math.min(maximum, count);
    }

    private List<Integer> selectSecondaryPositions(int blockX, int blockZ, int start, int spacing, int desired,
                                                     GridPurpose purpose) {
        if (desired == 0) {
            return List.of();
        }
        int first = settings.minimumEdgeDistance();
        int last = spacing - settings.minimumEdgeDistance();
        if (first > last) {
            return List.of();
        }
        List<Integer> candidates = new ArrayList<>();
        for (int local = first; local <= last; local++) {
            candidates.add(local);
        }
        candidates.sort((a, b) -> {
            int comparison = Long.compareUnsigned(
                    Hash.atSlot(seed, blockX, blockZ, a, purpose.key()),
                    Hash.atSlot(seed, blockX, blockZ, b, purpose.key()));
            return comparison != 0 ? comparison : Integer.compare(a, b);
        });
        List<Integer> selected = new ArrayList<>();
        for (int local : candidates) {
            boolean separated = true;
            for (int existing : selected) {
                if (Math.abs(existing - local) < settings.minimumRoadSeparation()) {
                    separated = false;
                    break;
                }
            }
            if (separated) {
                selected.add(local);
                if (selected.size() == desired) {
                    break;
                }
            }
        }
        selected.sort(Comparator.naturalOrder());
        return selected.stream().map(local -> start + local).toList();
    }

    private TertiarySegment tertiarySegment(BlockLayout block, int chunkX, int chunkZ) {
        List<Integer> xRoads = new ArrayList<>(block.secondaryX().size() + 2);
        xRoads.add(block.westX());
        xRoads.addAll(block.secondaryX());
        xRoads.add(block.eastX());
        List<Integer> zRoads = new ArrayList<>(block.secondaryZ().size() + 2);
        zRoads.add(block.northZ());
        zRoads.addAll(block.secondaryZ());
        zRoads.add(block.southZ());

        int cellX = findCell(xRoads, chunkX);
        int cellZ = findCell(zRoads, chunkZ);
        if (cellX < 0 || cellZ < 0) {
            return null;
        }
        int x0 = xRoads.get(cellX);
        int x1 = xRoads.get(cellX + 1);
        int z0 = zRoads.get(cellZ);
        int z1 = zRoads.get(cellZ + 1);
        long slot = cellX * 257L + cellZ;
        long identity = Hash.atSlot(seed, block.blockX(), block.blockZ(), slot, GridPurpose.TERTIARY_CHANCE.key());
        if (Hash.unit(identity) >= settings.tertiaryChance()) {
            return null;
        }

        RoadDirection direction = selectTertiaryDirection(block, slot, x0, x1, z0, z1);
        if (direction == null) {
            return null;
        }
        boolean vertical = direction == RoadDirection.NORTH || direction == RoadDirection.SOUTH;
        int interiorLength = vertical ? z1 - z0 - 1 : x1 - x0 - 1;
        int maxLength = Math.min(settings.tertiaryMaxLength(), interiorLength - 1);
        if (maxLength < settings.tertiaryMinLength()) {
            return null;
        }
        int lengthRange = maxLength - settings.tertiaryMinLength() + 1;
        long lengthHash = Hash.atSlot(seed, block.blockX(), block.blockZ(), slot, GridPurpose.TERTIARY_LENGTH.key());
        int length = settings.tertiaryMinLength() + Hash.index(lengthHash, lengthRange);

        int originX;
        int originZ;
        long originHash = Hash.atSlot(seed, block.blockX(), block.blockZ(), slot, GridPurpose.TERTIARY_ORIGIN.key());
        if (vertical) {
            int originMin = x0 + 2;
            int originMax = x1 - 2;
            if (originMin > originMax) {
                return null;
            }
            originX = originMin + Hash.index(originHash, originMax - originMin + 1);
            originZ = direction == RoadDirection.SOUTH ? z0 : z1;
        } else {
            int originMin = z0 + 2;
            int originMax = z1 - 2;
            if (originMin > originMax) {
                return null;
            }
            originX = direction == RoadDirection.EAST ? x0 : x1;
            originZ = originMin + Hash.index(originHash, originMax - originMin + 1);
        }
        return new TertiarySegment(identity, originX, originZ, direction, length);
    }

    private RoadDirection selectTertiaryDirection(BlockLayout block, long slot, int x0, int x1, int z0, int z1) {
        RoadDirection[] directions = RoadDirection.values();
        long sideHash = Hash.atSlot(seed, block.blockX(), block.blockZ(), slot, GridPurpose.TERTIARY_SIDE.key());
        int first = Hash.index(sideHash, directions.length);
        // Keep the hashed side as the first choice, but do not discard the entire cell merely
        // because that side cannot fit an origin or the minimum length. Walking the remaining
        // sides is deterministic and is especially useful for narrow rectangular city blocks.
        for (int offset = 0; offset < directions.length; offset++) {
            RoadDirection direction = directions[(first + offset) % directions.length];
            boolean vertical = direction == RoadDirection.NORTH || direction == RoadDirection.SOUTH;
            int transverseSpan = vertical ? x1 - x0 : z1 - z0;
            int inwardSpan = vertical ? z1 - z0 : x1 - x0;
            if (transverseSpan >= 4 && inwardSpan - 2 >= settings.tertiaryMinLength()) {
                return direction;
            }
        }
        return null;
    }

    private static int findCell(List<Integer> roads, int coordinate) {
        for (int i = 0; i < roads.size() - 1; i++) {
            if (coordinate > roads.get(i) && coordinate < roads.get(i + 1)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A stable FNV-1a hash of {@code dimensionId}, folded into the seed once here rather than at
     * every query. Deliberately not {@code String.hashCode()}, which is not guaranteed stable
     * across JVM versions and would make worlds non-reproducible.
     */
    private static long stableStringHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return mix64(hash);
    }

    /**
     * splitmix64's avalanche step, shared with {@link Hash#mix} via {@link Hash#avalanche}. Named
     * {@code mix64} (upstream's name) rather than {@code finalize}, which shadows a name
     * deprecated for removal on {@link Object}.
     */
    private static long mix64(long value) {
        return Hash.avalanche(value);
    }

    private record RawRoad(RoadType type, BlockLayout block, TertiarySegment tertiary) {
    }

    private record BlockLayout(
            int blockX,
            int blockZ,
            int westX,
            int northZ,
            int eastX,
            int southZ,
            double density,
            List<Integer> secondaryX,
            List<Integer> secondaryZ
    ) {
    }
}
