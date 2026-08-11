package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleRE;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.ObjectSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.StreetParts;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

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

    /**
     * Builds a fully resolved style from its {@code extends} chain, root first: each entry
     * overwrites what its ancestors set, and a declared selector list replaces the inherited one by
     * default, or appends to it when the entry opts in with {@code {"replace": false, ...}} (see
     * {@link #declare}). Nothing mutates after this returns, so worldgen worker threads share one
     * immutable instance with no locking.
     */
    public CityStyle(List<CityStyleRE> chainRootFirst) {
        CityStyleRE leaf = chainRootFirst.get(chainRootFirst.size() - 1);
        name = leaf.getRegistryName();
        stuffTags.add("all");
        for (CityStyleRE re : chainRootFirst) {
            applyFrom(re);
        }
    }

    /**
     * Applies one link of the {@code extends} chain on top of whatever earlier links already set.
     * Every scalar assignment is conditional on the incoming value being present, so a chain entry
     * that omits a field does not blank out what an earlier entry set.
     */
    private void applyFrom(CityStyleRE object) {
        if (object.getStyle() != null) {
            style = object.getStyle();
        }
        if (object.getStuffTags() != null) {
            stuffTags.addAll(object.getStuffTags());
        }
        if (object.getExplosionChance() != null) {
            explosionChance = object.getExplosionChance();
        }
        object.getBuildingSettings().ifPresent(s -> {
            if (s.getBuildingChance() != null) {
                buildingChance = s.getBuildingChance();
            }
            if (s.getMaxCellarCount() != null) {
                maxCellarCount = s.getMaxCellarCount();
            }
            if (s.getMaxFloorCount() != null) {
                maxFloorCount = s.getMaxFloorCount();
            }
            if (s.getMinCellarCount() != null) {
                minCellarCount = s.getMinCellarCount();
            }
            if (s.getMinFloorCount() != null) {
                minFloorCount = s.getMinFloorCount();
            }
        });
        object.getCorridorSettings().ifPresent(s -> {
            if (s.getCorridorChance() != null) {
                corridorChance = s.getCorridorChance();
            }
            if (s.getCorridorGlassBlock() != null) {
                corridorGlassBlock = s.getCorridorGlassBlock();
            }
            if (s.getCorridorRoofBlock() != null) {
                corridorRoofBlock = s.getCorridorRoofBlock();
            }
        });
        object.getRailSettings().ifPresent(s -> {
            if (s.getRailMainBlock() != null) {
                railMainBlock = s.getRailMainBlock();
            }
        });
        object.getParkSettings().ifPresent(s -> {
            if (s.getAvoidFoliage() != null) {
                avoidFoliage = s.getAvoidFoliage();
            }
            if (s.getParkBorder() != null) {
                parkBorder = s.getParkBorder();
            }
            if (s.getParkElevation() != null) {
                parkElevation = s.getParkElevation();
            }
            if (s.getParkStreetThreshold() != null) {
                parkStreetThreshold = s.getParkStreetThreshold();
            }
            if (s.getGrassBlock() != null) {
                grassBlock = s.getGrassBlock();
            }
            if (s.getParkElevationBlock() != null) {
                parkElevationBlock = s.getParkElevationBlock();
            }
        });
        object.getStreetSettings().ifPresent(s -> {
            if (s.getFountainChance() != null) {
                fountainChance = s.getFountainChance();
            }
            if (s.getFrontChance() != null) {
                frontChance = s.getFrontChance();
            }
            if (s.getBorderBlock() != null) {
                borderBlock = s.getBorderBlock();
            }
            if (s.getStreetBaseBlock() != null) {
                streetBaseBlock = s.getStreetBaseBlock();
            }
            if (s.getStreetBlock() != null) {
                streetBlock = s.getStreetBlock();
            }
            if (s.getStreetVariantBlock() != null) {
                streetVariantBlock = s.getStreetVariantBlock();
            }
            if (s.getWallBlock() != null) {
                wallBlock = s.getWallBlock();
            }
            if (s.getStreetWidth() != null) {
                streetWidth = s.getStreetWidth();
            }
            if (s.getParts() != StreetParts.DEFAULT) {
                streetParts = s.getParts();
            }
            if (s.getLargeParts() != StreetParts.DEFAULT) {
                largeStreetParts = s.getLargeParts();
            }
            if (s.getTertiaryParts() != StreetParts.DEFAULT) {
                tertiaryStreetParts = s.getTertiaryParts();
            }
        });
        object.getGeneralSettings().ifPresent(s -> {
            if (s.getGlowstoneBlock() != null) {
                glowstoneBlock = s.getGlowstoneBlock();
            }
            if (s.getIronbarsBlock() != null) {
                ironbarsBlock = s.getIronbarsBlock();
            }
            if (s.getLeavesBlock() != null) {
                leavesBlock = s.getLeavesBlock();
            }
            if (s.getRubbleDirtBlock() != null) {
                rubbleDirtBlock = s.getRubbleDirtBlock();
            }
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

    /**
     * A bare-array declaration replaces whatever an ancestor put there; the {@code {"replace":
     * false, ...}} object form appends to it instead. A kind the file does not mention at all
     * leaves the inherited list alone.
     */
    private void declare(Sel kind, Optional<Mergeable<ObjectSelector>> values) {
        values.ifPresent(v -> Mergeable.apply(selectorList(kind), v));
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

    /** The fully-qualified id, e.g. {@code "urbex:citystyle_common"}. */
    public String getName() {
        return name.toString();
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
