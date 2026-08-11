package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.ObjectSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.StreetParts;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CommonLevelAccessor;

import java.util.*;

public class CityStyle {

    private final Identifier name;

    private final Set<String> stuffTags = new HashSet<>();

    private final List<ObjectSelector> buildingSelector = new ArrayList<>();
    private final List<ObjectSelector> bridgeSelector = new ArrayList<>();
    private final List<ObjectSelector> largeBridgeSelector = new ArrayList<>();
    private final List<ObjectSelector> parkSelector = new ArrayList<>();
    private final List<ObjectSelector> fountainSelector = new ArrayList<>();
    private final List<ObjectSelector> stairSelector = new ArrayList<>();
    private final List<ObjectSelector> frontSelector = new ArrayList<>();
    private final List<ObjectSelector> railDungeonSelector = new ArrayList<>();
    private final List<ObjectSelector> multiBuildingSelector = new ArrayList<>();

    /** The nine selector lists a city style can declare, so inheritance can be driven by a loop. */
    enum Sel {
        BUILDING, BRIDGE, LARGE_BRIDGE, PARK, FOUNTAIN, STAIR, FRONT, RAIL_DUNGEON, MULTI_BUILDING
    }

    /**
     * Which selector lists this style's <em>own</em> file declared. A declared list replaces the
     * inherited one rather than being appended to it, so a style can narrow a selection - and an
     * explicitly empty list means empty, which is otherwise inexpressible. Lists the file does not
     * mention still inherit whole. See {@link #inheritSelectors}.
     */
    private final Set<Sel> declared = EnumSet.noneOf(Sel.class);
    private StreetParts streetParts = StreetParts.DEFAULT;
    private StreetParts largeStreetParts = StreetParts.DEFAULT;
    private StreetParts tertiaryStreetParts = StreetParts.DEFAULT;

    // Building settings
    private Integer minFloorCount;
    private Integer minCellarCount;
    private Integer maxFloorCount;
    private Integer maxCellarCount;
    private Float buildingChance;   // Optional build chance override

    // Street settings
    private Float fountainChance;
    private Float frontChance;
    private Integer streetWidth;
    private Character streetBlock;
    private Character streetBaseBlock;
    private Character streetVariantBlock;
    private Character borderBlock;
    private Character wallBlock;

    // Park settings
    private Boolean avoidFoliage;
    private Boolean parkBorder;
    private Boolean parkElevation;
    private Integer parkStreetThreshold;
    private Character parkElevationBlock;
    private Character grassBlock;

    // Corridor settings
    private Float corridorChance;
    private Character corridorRoofBlock;
    private Character corridorGlassBlock;

    // Rail settings
    private Character railMainBlock;

    // General settings
    private Character ironbarsBlock;
    private Character glowstoneBlock;
    private Character leavesBlock;
    private Character rubbleDirtBlock;

    private Float explosionChance;
    private String style;
    private final String inherit;
    // 'initialized' is written last, inside the monitor, so a reader that sees it set is guaranteed
    // to see every field init() copied down from the parent style.
    private volatile boolean initialized = false;

    // Per-thread cycle guard for the inherit chain - see init().
    private static final ThreadLocal<Set<CityStyle>> RESOLVING = ThreadLocal.withInitial(HashSet::new);

    public CityStyle(CityStyleRE object) {
        name = object.getRegistryName();
        inherit = object.getInherit();
        style = object.getStyle();
        stuffTags.add("all");
        if (object.getStuffTags() != null) {
            stuffTags.addAll(object.getStuffTags());
        }
        explosionChance = object.getExplosionChance();
        object.getBuildingSettings().ifPresent(s -> {
            buildingChance = s.getBuildingChance();
            maxCellarCount = s.getMaxCellarCount();
            maxFloorCount = s.getMaxFloorCount();
            minCellarCount = s.getMinCellarCount();
            minFloorCount = s.getMinFloorCount();
        });
        object.getCorridorSettings().ifPresent(s -> {
            corridorChance = s.getCorridorChance();
            corridorGlassBlock = s.getCorridorGlassBlock();
            corridorRoofBlock = s.getCorridorRoofBlock();
        });
        object.getRailSettings().ifPresent(s -> {
            railMainBlock = s.getRailMainBlock();
        });
        object.getParkSettings().ifPresent(s -> {
            avoidFoliage = s.getAvoidFoliage();
            parkBorder = s.getParkBorder();
            parkElevation = s.getParkElevation();
            parkStreetThreshold = s.getParkStreetThreshold();
            grassBlock = s.getGrassBlock();
            parkElevationBlock = s.getParkElevationBlock();
        });
        object.getStreetSettings().ifPresent(s -> {
            fountainChance = s.getFountainChance();
            frontChance = s.getFrontChance();
            borderBlock = s.getBorderBlock();
            streetBaseBlock = s.getStreetBaseBlock();
            streetBlock = s.getStreetBlock();
            streetVariantBlock = s.getStreetVariantBlock();
            wallBlock = s.getWallBlock();
            streetWidth = s.getStreetWidth();
            streetParts = s.getParts();
            largeStreetParts = s.getLargeParts();
            tertiaryStreetParts = s.getTertiaryParts();
        });
        object.getGeneralSettings().ifPresent(s -> {
            glowstoneBlock = s.getGlowstoneBlock();
            ironbarsBlock = s.getIronbarsBlock();
            leavesBlock = s.getLeavesBlock();
            rubbleDirtBlock = s.getRubbleDirtBlock();
        });
        object.getSelectors().ifPresent(s -> {
            declare(Sel.BRIDGE, s.getBridgeSelector());
            declare(Sel.LARGE_BRIDGE, s.getLargeBridgeSelector());
            declare(Sel.BUILDING, s.getBuildingSelector());
            declare(Sel.FOUNTAIN, s.getFountainSelector());
            declare(Sel.FRONT, s.getFrontSelector());
            declare(Sel.PARK, s.getParkSelector());
            declare(Sel.MULTI_BUILDING, s.getMultiBuildingSelector());
            declare(Sel.RAIL_DUNGEON, s.getRailDungeonSelector());
            declare(Sel.STAIR, s.getStairSelector());
        });
    }

    /** Records that this style's own file declared {@code kind}, and takes its entries. */
    private void declare(Sel kind, Optional<List<ObjectSelector>> values) {
        values.ifPresent(v -> {
            declared.add(kind);
            selectorList(kind).addAll(v);
        });
    }

    List<ObjectSelector> selectorList(Sel kind) {
        return switch (kind) {
            case BUILDING -> buildingSelector;
            case BRIDGE -> bridgeSelector;
            case LARGE_BRIDGE -> largeBridgeSelector;
            case PARK -> parkSelector;
            case FOUNTAIN -> fountainSelector;
            case STAIR -> stairSelector;
            case FRONT -> frontSelector;
            case RAIL_DUNGEON -> railDungeonSelector;
            case MULTI_BUILDING -> multiBuildingSelector;
        };
    }

    /**
     * Takes the parent's entries for every selector list this style's own file did not declare.
     * Package-private and free of any level lookup so the merge can be tested directly.
     */
    void inheritSelectors(CityStyle inheritFrom) {
        for (Sel kind : Sel.values()) {
            if (!declared.contains(kind)) {
                selectorList(kind).addAll(inheritFrom.selectorList(kind));
            }
        }
    }

    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }

    public String getStyle() {
        return style;
    }

    public Float getExplosionChance() {
        return explosionChance;
    }

    public int getStreetWidth() {
        return streetWidth;
    }

    public StreetParts getStreetParts() {
        return streetParts;
    }

    /** Primary roads fall back to the secondary-road family when a style does not define their own. */
    public StreetParts getLargeStreetParts() {
        return largeStreetParts == StreetParts.DEFAULT ? streetParts : largeStreetParts;
    }

    /** Tertiary roads fall back to the secondary-road family when a style does not define their own. */
    public StreetParts getTertiaryStreetParts() {
        return tertiaryStreetParts == StreetParts.DEFAULT ? streetParts : tertiaryStreetParts;
    }

    public Integer getMinFloorCount() {
        return minFloorCount;
    }

    public Integer getMinCellarCount() {
        return minCellarCount;
    }

    public Integer getMaxFloorCount() {
        return maxFloorCount;
    }

    public Integer getMaxCellarCount() {
        return maxCellarCount;
    }

    public Float getBuildingChance() {
        return buildingChance;
    }

    public Float getFrontChance() { return frontChance; }

    public Float getCorridorChance() { return corridorChance; }

    public Boolean getAvoidFoliage() {
        return avoidFoliage;
    }

    public Boolean getParkBorder() {
        return parkBorder;
    }

    public Integer getParkStreetThreshold() { return parkStreetThreshold; }

    public Boolean getParkElevation() {
        return parkElevation;
    }

    public Character getGrassBlock() {
        return grassBlock;
    }

    public Character getIronbarsBlock() {
        return ironbarsBlock;
    }

    public Character getGlowstoneBlock() {
        return glowstoneBlock;
    }

    public Character getLeavesBlock() {
        return leavesBlock;
    }

    public Character getRubbleDirtBlock() {
        return rubbleDirtBlock;
    }

    public Float getFountainChance() {return fountainChance; }

    public Character getStreetBlock() {
        return streetBlock;
    }

    public Character getStreetBaseBlock() {
        return streetBaseBlock;
    }

    public Character getStreetVariantBlock() {
        return streetVariantBlock;
    }

    public Character getRailMainBlock() {
        return railMainBlock;
    }

    public Character getParkElevationBlock() {
        return parkElevationBlock;
    }

    public Character getCorridorRoofBlock() {
        return corridorRoofBlock;
    }

    public Character getCorridorGlassBlock() {
        return corridorGlassBlock;
    }

    public Character getBorderBlock() {
        return borderBlock;
    }

    public Character getWallBlock() {
        return wallBlock;
    }

    /**
     * Resolve 'inherit'. Called on every lookup of this style, from any worldgen worker thread, so
     * it has to be safe to race - and it mutates about thirty fields, so it cannot be done with a
     * volatile write per field. A monitor on this style is the cheap answer: after the first call
     * the volatile read below short-circuits and no lock is taken at all.
     * <p>
     * The parent is resolved <em>before</em> the monitor is taken, deliberately. Resolving it
     * recursively init()s the parent, so doing it inside would mean holding this style's monitor
     * while acquiring the parent's - and a cyclic inherit chain touched by two threads would then
     * be a lock-ordering deadlock, which in worldgen means a hung server and no diagnostic at all.
     * By the time the monitor is entered, every lock this method needs has already been released.
     * <p>
     * That moves the cycle guard out of the monitor, so it is a ThreadLocal rather than a field: a
     * field would make one thread's half-resolved style visible to another. A style already being
     * resolved further up <em>this thread's</em> stack returns uninitialised, which is exactly what
     * the old single-threaded 'resolveInherit' flag did - a cycle terminates rather than recursing.
     */
    public void init(CommonLevelAccessor level) {
        if (initialized) {
            return;
        }
        CityStyle inheritFrom = null;
        if (inherit != null) {
            Set<CityStyle> inProgress = RESOLVING.get();
            if (!inProgress.add(this)) {
                return;     // cycle in the inherit chain; leave this style as it is, as before
            }
            try {
                inheritFrom = AssetRegistries.CITYSTYLES.getOrThrow(level, inherit);
            } finally {
                inProgress.remove(this);
            }
        }
        synchronized (this) {
            // Re-check: the addAll()s below are not idempotent, so exactly one thread may run them.
            if (initialized) {
                return;
            }
            if (inheritFrom != null) {
                if (style == null) {
                    style = inheritFrom.getStyle();
                }
                stuffTags.addAll(inheritFrom.stuffTags);
                inheritSelectors(inheritFrom);
                if (explosionChance == null) {
                    explosionChance = inheritFrom.explosionChance;
                }
                if (streetWidth == null) {
                    streetWidth = inheritFrom.streetWidth;
                }
                if (streetParts == StreetParts.DEFAULT) {
                    streetParts = inheritFrom.streetParts;
                }
                if (largeStreetParts == StreetParts.DEFAULT) {
                    largeStreetParts = inheritFrom.largeStreetParts;
                }
                if (tertiaryStreetParts == StreetParts.DEFAULT) {
                    tertiaryStreetParts = inheritFrom.tertiaryStreetParts;
                }
                if (minFloorCount == null) {
                    minFloorCount = inheritFrom.minFloorCount;
                }
                if (minCellarCount == null) {
                    minCellarCount = inheritFrom.minCellarCount;
                }
                if (maxFloorCount == null) {
                    maxFloorCount = inheritFrom.maxFloorCount;
                }
                if (maxCellarCount == null) {
                    maxCellarCount = inheritFrom.maxCellarCount;
                }
                if (buildingChance == null) {
                    buildingChance = inheritFrom.buildingChance;
                }
                if (fountainChance == null) {
                    fountainChance = inheritFrom.fountainChance;
                }
                if (frontChance == null) {
                    frontChance = inheritFrom.frontChance;
                }
                if (corridorChance == null) {
                    corridorChance = inheritFrom.corridorChance;
                }
                if (parkElevation == null) {
                    parkElevation = inheritFrom.parkElevation;
                }
                if (avoidFoliage == null) {
                    avoidFoliage = inheritFrom.avoidFoliage;
                }
                if (parkBorder == null) {
                    parkBorder = inheritFrom.parkBorder;
                }
                if (parkStreetThreshold == null) {
                    parkStreetThreshold = inheritFrom.parkStreetThreshold;
                }
                if (streetBlock == null) {
                    streetBlock = inheritFrom.streetBlock;
                }
                if (streetBaseBlock == null) {
                    streetBaseBlock = inheritFrom.streetBaseBlock;
                }
                if (streetVariantBlock == null) {
                    streetVariantBlock = inheritFrom.streetVariantBlock;
                }
                if (parkElevationBlock == null) {
                    parkElevationBlock = inheritFrom.parkElevationBlock;
                }
                if (corridorRoofBlock == null) {
                    corridorRoofBlock = inheritFrom.corridorRoofBlock;
                }
                if (corridorGlassBlock == null) {
                    corridorGlassBlock = inheritFrom.corridorGlassBlock;
                }
                if (railMainBlock == null) {
                    railMainBlock = inheritFrom.railMainBlock;
                }
                if (borderBlock == null) {
                    borderBlock = inheritFrom.borderBlock;
                }
                if (wallBlock == null) {
                    wallBlock = inheritFrom.wallBlock;
                }
            }
            initialized = true;
        }
    }

    private static String getRandomFromList(RandomSource random, List<ObjectSelector> list, ChunkCoord pos) {
        ObjectSelector fromList = Tools.getRandomFromList(random, list, objectSelector -> {
            if (objectSelector.minSpawnDistance() > 0 || objectSelector.maxSpawnDistance() < Integer.MAX_VALUE) {
                // Distance in objectSelector is in blocks whereas pos is in chunks
                // Objects can only return 'factor' between minSpawnDistance and maxSpawnDistance
                // objectSelector.feather() is used to make the transition at minSpawnDistance and maxSpawnDistance more smooth
                int squaredDist = (pos.chunkX() << 4) * (pos.chunkX() << 4) + (pos.chunkZ() << 4) * (pos.chunkZ() << 4);
                int minDist = objectSelector.minSpawnDistance();
                int maxDist = objectSelector.maxSpawnDistance();
                if (squaredDist < minDist * minDist) {
                    if (objectSelector.feather() <= 0) {
                        return 0.0f;
                    } else {
                        int fd = minDist - objectSelector.feather();
                        if (squaredDist < fd * fd) {
                            return 0.0f;
                        } else {
                            float f = (float) (Math.sqrt(squaredDist) - fd) / (float) (minDist - fd);
                            return f * objectSelector.factor();
                        }
                    }
                } else if (squaredDist > maxDist * maxDist) {
                    if (objectSelector.feather() <= 0) {
                        return 0.0f;
                    } else {
                        int fd = maxDist + objectSelector.feather();
                        if (squaredDist > fd * fd) {
                            return 0.0f;
                        } else {
                            float f = (float) (fd - Math.sqrt(squaredDist)) / (float) (fd - maxDist);
                            return f * objectSelector.factor();
                        }
                    }
                }
            }
            return objectSelector.factor();
        });
        if (fromList == null) {
            return null;
        } else {
            return fromList.value();
        }
    }

    public Set<String> getStuffTags() {
        return stuffTags;
    }

    public String getRandomStair(RandomSource random, ChunkCoord pos) {
        return getRandomFromList(random, stairSelector, pos);
    }

    public String getRandomFront(RandomSource random, ChunkCoord pos) {
        return getRandomFromList(random, frontSelector, pos);
    }

    public String getRandomRailDungeon(RandomSource random, ChunkCoord pos) {
        return getRandomFromList(random, railDungeonSelector, pos);
    }

    public String getRandomPark(RandomSource random, ChunkCoord pos) {
        return getRandomFromList(random, parkSelector, pos);
    }

    public String getRandomBridge(RandomSource random, ChunkCoord pos) {
        return getRandomFromList(random, bridgeSelector, pos);
    }

    public String getRandomLargeBridge(RandomSource random, ChunkCoord pos) {
        if (largeBridgeSelector.isEmpty()) {
            return getRandomBridge(random, pos);
        }
        return getRandomFromList(random, largeBridgeSelector, pos);
    }

    public String getRandomFountain(RandomSource random, ChunkCoord pos) {
        return getRandomFromList(random, fountainSelector, pos);
    }

    public String getRandomBuilding(RandomSource random, ChunkCoord pos) {
        return getRandomFromList(random, buildingSelector, pos);
    }

    public String getRandomMultiBuilding(RandomSource random, ChunkCoord pos) {
        return getRandomFromList(random, multiBuildingSelector, pos);
    }

    public boolean hasMultiBuildings() {
        return !multiBuildingSelector.isEmpty();
    }

    public List<ObjectSelector> getMultiBuildingSelector() {
        return multiBuildingSelector;
    }
}
