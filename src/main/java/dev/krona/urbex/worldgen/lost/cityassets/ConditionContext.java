package dev.krona.urbex.worldgen.lost.cityassets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.regassets.data.ConditionTest;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.StringUtils;

import java.util.Set;
import java.util.function.Predicate;

public abstract class ConditionContext {
    private final int level;        // Global level in world with 0 being to lowest possible level where a building section can be
    private final int floor;        // Level of the building with 0 being the ground floor. floor == floorsAboveGround means the top of the building section
    private final int floorsBelowGround;    // 0 means nothing below ground
    private final int floorsAboveGround;    // 1 means 1 floor above ground
    private final String part;
    private final String belowPart;
    private final String building;
    private final ChunkCoord coord;

    /**
     * The key an asset is matched against by an {@code inpart}/{@code inbuilding} condition: the
     * bare path for an {@code urbex}-namespace id, the full qualified id otherwise. This mirrors
     * what every {@code getName()} in {@code cityassets} used to return before this pass qualified
     * them all - deliberately preserved here rather than switched to the now-qualified {@code
     * getName()}, because a bundled condition file's {@code inpart}/{@code inbuilding} value is
     * required to be fully qualified (by {@code DatapackReferenceIntegrityTest}), so comparing it
     * against a bare {@code urbex}-namespace key can never match. That mismatch is a real,
     * pre-existing bug (see {@code chestloot.json}'s rail-dungeon {@code inpart} entries, which
     * never fire) - fixing it changes chest loot within the digest-check window (confirmed by
     * hand), so it is intentionally left alone here as a separate, tracked follow-up rather than
     * fixed as an incidental side effect of qualifying {@code getName()} everywhere else.
     */
    public static String legacyMatchKey(Identifier id) {
        return id.getNamespace().equals(Urbex.MODID) ? id.getPath() : id.toString();
    }

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

    public static Predicate<ConditionContext> parseTest(JsonElement element) {
        Predicate<ConditionContext> test = null;
        JsonObject obj = element.getAsJsonObject();
        if (obj.has("top")) {
            boolean top = obj.get("top").getAsBoolean();
            if (top) {
                test = combine(test, ConditionContext::isTopOfBuilding);
            } else {
                test = combine(test, levelInfo -> !levelInfo.isTopOfBuilding());
            }
        }
        if (obj.has("ground")) {
            boolean ground = obj.get("ground").getAsBoolean();
            if (ground) {
                test = combine(test, ConditionContext::isGroundFloor);
            } else {
                test = combine(test, levelInfo -> !levelInfo.isGroundFloor());
            }
        }
        if (obj.has("isbuilding")) {
            boolean ground = obj.get("isbuilding").getAsBoolean();
            if (ground) {
                test = combine(test, ConditionContext::isBuilding);
            } else {
                test = combine(test, levelInfo -> !levelInfo.isBuilding());
            }
        }
        if (obj.has("chunkx")) {
            int chunkX = obj.get("chunkx").getAsInt();
            test = combine(test, context -> chunkX == context.getChunkX());
        }
        if (obj.has("chunkz")) {
            int chunkZ = obj.get("chunkz").getAsInt();
            test = combine(test, context -> chunkZ == context.getChunkZ());
        }
        if (obj.has("inpart")) {
            String part = obj.get("inpart").getAsString();
            test = combine(test, context -> part.equals(context.getPart()));
        }
        if (obj.has("inbuilding")) {
            String building = obj.get("inbuilding").getAsString();
            test = combine(test, context -> building.equals(context.getBuilding()));
        }
        if (obj.has("inbiome")) {
            String biome = obj.get("inbiome").getAsString();
            test = combine(test, context -> biome.equals(context.getBiome().toString()));
        }
        if (obj.has("cellar")) {
            boolean cellar = obj.get("cellar").getAsBoolean();
            if (cellar) {
                test = combine(test, ConditionContext::isCellar);
            } else {
                test = combine(test, levelInfo -> !levelInfo.isCellar());
            }
        }
        if (obj.has("floor")) {
            int level = obj.get("floor").getAsInt();
            test = combine(test, levelInfo -> levelInfo.isFloor(level));
        }
        if (obj.has("range")) {
            String range = obj.get("range").getAsString();
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
        return !"<none>".equals(building);
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
