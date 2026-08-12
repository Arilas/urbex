package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.regassets.data.ConditionTest;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.StringUtils;

import java.util.Set;
import java.util.function.Predicate;

public abstract class ConditionContext {

    /**
     * The value of {@code part}, {@code belowPart} or {@code building} when there is no such thing
     * here: below the lowest floor, before a floor's part has been chosen, or outside a building.
     * Every other value in those three slots is a fully-qualified asset id, and this one cannot
     * collide with any of them - an id must contain a {@code ':'} and this does not.
     */
    public static final String NO_PART = "<none>";

    private final int level;        // Global level in world with 0 being to lowest possible level where a building section can be
    private final int floor;        // Level of the building with 0 being the ground floor. floor == floorsAboveGround means the top of the building section
    private final int floorsBelowGround;    // 0 means nothing below ground
    private final int floorsAboveGround;    // 1 means 1 floor above ground
    private final String part;
    private final String belowPart;
    private final String building;
    private final ChunkCoord coord;

    /**
     * Every asset name reaching a condition test is the fully-qualified id, on both sides of the
     * comparison and in all nine places one is written. Three fields take one -
     * {@code belowpart}, {@code inpart} and {@code inbuilding} - and three blocks declare all
     * three: a building's {@code parts[]} and {@code parts2[]} (both are {@code PartRef}, bound
     * twice by {@code BuildingRE}) and a condition's own {@code values[]}
     * ({@code ConditionPart}). {@code inbiome} is the fourth field of both records and is
     * deliberately not in this list: it is a biome id, not an asset name.
     * {@code DatapackReferenceIntegrityTest} walks the same nine.
     * There used to be a {@code legacyMatchKey} here that stripped
     * the {@code urbex:} namespace, mirroring what {@code cityassets}' {@code getName()} returned
     * before those were qualified - so a condition file, which {@code DatapackReferenceIntegrityTest}
     * requires to write a qualified id, was comparing {@code "urbex:rail_dungeon1"} against
     * {@code "rail_dungeon1"} and could never match. Anything constructing a {@code ConditionContext}
     * must pass {@code getName()}/{@code getId().toString()}, never a bare path.
     * <p>
     * {@code "<none>"} is the one non-id value the {@code part}, {@code belowPart} and
     * {@code building} slots take, for "there is no such thing here": below the first floor, or
     * outside a building. It is not an id, cannot collide with one (no {@code ':'}), and is what
     * {@link #isBuilding()} tests.
     */
    public ConditionContext(int level, int floor, int floorsBelowGround, int floorsAboveGround, String part, String belowPart, String building, ChunkCoord coord) {
        this.level = level;
        this.floor = floor;
        this.floorsBelowGround = floorsBelowGround;
        this.floorsAboveGround = floorsAboveGround;
        this.part = part;
        this.belowPart = belowPart;
        this.building = building;
        this.coord = coord;
    }

    /**
     * This same floor, once its {@code parts[]} entry has been chosen: everything is carried over
     * and only the current part is replaced. It is what {@code parts2[]} selection is evaluated
     * against - the second part of a floor that now has a first one, sitting on the same floor below.
     * <p>
     * A derivation rather than a second constructor call at each site, and the only way to build a
     * {@code parts2} context ({@link Building#getRandomPart2} applies it internally), because
     * building one by hand is how the defect this replaces happened: all three floor loops advanced
     * their {@code belowPart} variable to the part just chosen <em>before</em> constructing the
     * second context, so it saw {@code getBelowPart()} equal to {@code getPart()} and a
     * {@code parts2[]} {@code belowpart} was an exact duplicate of its {@code inpart} - the same
     * defect issue #58 fixed on the reading side in {@link #parseTest}.
     * <p>
     * What deriving buys is exactly one invariant, and it is worth stating no wider than it is: the
     * {@code parts2} context's {@code belowPart} is always the {@code parts[]} context's
     * {@code belowPart}, so whatever a caller does to its own local afterwards cannot reach it. That
     * is the defect that was shipping, and it is gone at all three sites. It is not a proof that a
     * {@code parts2} context can never have {@code getPart()} equal to {@code getBelowPart()}: the
     * constructor is public, the {@code parts[]} contexts are still hand-written with a hand-chosen
     * {@code belowPart}, and nothing rejects {@code getRandomPart2(rand, ctx, ctx.getBelowPart())}.
     * Nor should anything: a building that repeats one part on consecutive floors makes
     * {@code part} legitimately equal {@code belowPart}, and {@code buildings/library00.json} - one
     * non-top entry, so every non-top floor draws {@code urbex:library00_1} - does exactly that.
     */
    final ConditionContext withPart(String newPart) {
        ConditionContext floorContext = this;
        return new ConditionContext(level, floor, floorsBelowGround, floorsAboveGround,
                newPart, belowPart, building, coord) {
            @Override
            public boolean isBuilding() {
                return floorContext.isBuilding();
            }

            @Override
            public Identifier getBiome() {
                return floorContext.getBiome();
            }
        };
    }

    private static Predicate<ConditionContext> combine(Predicate<ConditionContext> orig, Predicate<ConditionContext> newTest) {
        if (orig == null) {
            return newTest;
        }
        return levelInfo -> orig.test(levelInfo) && newTest.test(levelInfo);
    }

    public static Predicate<ConditionContext> parseTest(ConditionTest element) {
        Predicate<ConditionContext> test = null;
        if (element.getTop() != null) {
            boolean top = element.getTop();
            if (top) {
                test = combine(test, ConditionContext::isTopOfBuilding);
            } else {
                test = combine(test, levelInfo -> !levelInfo.isTopOfBuilding());
            }
        }
        if (element.getGround() != null) {
            boolean ground = element.getGround();
            if (ground) {
                test = combine(test, ConditionContext::isGroundFloor);
            } else {
                test = combine(test, levelInfo -> !levelInfo.isGroundFloor());
            }
        }
        if (element.getIsbuilding() != null) {
            boolean ground = element.getIsbuilding();
            if (ground) {
                test = combine(test, ConditionContext::isBuilding);
            } else {
                test = combine(test, levelInfo -> !levelInfo.isBuilding());
            }
        }
        if (element.getChunkx() != null) {
            int chunkX = element.getChunkx();
            test = combine(test, context -> chunkX == context.getChunkX());
        }
        if (element.getChunkz() != null) {
            int chunkZ = element.getChunkz();
            test = combine(test, context -> chunkZ == context.getChunkZ());
        }
        if (element.getBelowPart() != null) {
            Set<String> belowPart = element.getBelowPart();
            // context.getBelowPart(), not getPart(): reading the current part made belowpart
            // an exact duplicate of inpart, so the condition never did what it says (issue #58)
            test = combine(test, context -> belowPart.contains(context.getBelowPart()));
        }
        if (element.getInpart() != null) {
            Set<String> part = element.getInpart();
            test = combine(test, context -> part.contains(context.getPart()));
        }
        if (element.getInbuilding() != null) {
            Set<String> building = element.getInbuilding();
            test = combine(test, context -> building.contains(context.getBuilding()));
        }
        if (element.getInbiome() != null) {
            Set<String> biome = element.getInbiome();
            test = combine(test, context -> biome.contains(context.getBiome().toString()));
        }
        if (element.getCellar() != null) {
            boolean cellar = element.getCellar();
            if (cellar) {
                test = combine(test, ConditionContext::isCellar);
            } else {
                test = combine(test, levelInfo -> !levelInfo.isCellar());
            }
        }
        if (element.getFloor() != null) {
            int level = element.getFloor();
            test = combine(test, levelInfo -> levelInfo.isFloor(level));
        }
        if (element.getRange() != null) {
            String range = element.getRange();
            String[] split = StringUtils.split(range, ',');
            try {
                int l1 = Integer.parseInt(split[0]);
                int l2 = Integer.parseInt(split[1]);
                test = combine(test, levelInfo -> levelInfo.isRange(l1, l2));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                throw new RuntimeException("Bad range specification: <l1>,<l2>!", e);
            }
        }
        if (test == null) {
            test = conditionContext -> true;
        }
        return test;
    }

    public int getLevel() {
        return level;
    }

    public int getFloor() {
        return floor;
    }

    public int getFloorsBelowGround() {
        return floorsBelowGround;
    }

    public int getFloorsAboveGround() {
        return floorsAboveGround;
    }

    public boolean isGroundFloor() {
        return floor == 0;
    }

    public boolean isBuilding() {
        return !NO_PART.equals(building);
    }

    public abstract Identifier getBiome();

    public boolean isTopOfBuilding() {
        return floor >= floorsAboveGround;
    }

    public boolean isCellar() {
        return floor < 0;
    }

    public boolean isFloor(int l) {
        return floor == l;
    }

    public boolean isRange(int l1, int l2) {
        return floor >= l1 && floor <= l2;
    }

    public String getPart() {
        return part;
    }

    /** The part generated directly below the current one, or {@code "<none>"} on the first floor. */
    public String getBelowPart() {
        return belowPart;
    }

    public String getBuilding() {
        return building;
    }

    public int getChunkX() {
        return coord.chunkX();
    }

    public int getChunkZ() {
        return coord.chunkZ();
    }
}
