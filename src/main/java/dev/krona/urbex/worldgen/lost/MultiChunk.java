package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.plan.RoadType;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Counter;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.varia.TimedCache;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.PlanningContext;
import dev.krona.urbex.worldgen.lost.cityassets.Building;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.cityassets.MultiBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.MultiSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.WorldSettings;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.NotNull;

import java.util.*;

// @todo handle predefined cities

/**
 * This is a representation of a number of chunks (NxN) for the purpose of calculating multibuildings
 */
public class MultiChunk {

    record MB(String name, int offsetX, int offsetZ) {}


    private final ChunkCoord mc;    // This coordinate is divided by areasize
    private final ChunkCoord topleft;
    private final int areasize;
    private final MB[][] buildingGrid;

    public MultiChunk(ChunkCoord mc, int areasize) {
        this.mc = mc;
        this.topleft = new ChunkCoord(mc.dimension(), mc.chunkX() * areasize, mc.chunkZ() * areasize);
        this.areasize = areasize;
        this.buildingGrid = new MB[areasize][areasize];
        // Initialize to null
        for (int x = 0 ; x < areasize ; x++) {
            for (int z = 0 ; z < areasize ; z++) {
                buildingGrid[x][z] = null;
            }
        }
    }

    /**
     * Not synchronized, and getOrCompute rather than computeIfAbsent: calculateBuildings() reaches
     * back into the city caches, which reach back here.
     */
    public static MultiChunk getOrCreate(PlanningContext provider, ChunkCoord coord) {
        // primary(), unlike the rest of multisettings below: areasize defines the grid getMultiCoord
        // divides by, so it cannot be read from an area that grid has not identified yet.
        int areasize = provider.worldStyles().primary().getMultiSettings().areasize();
        ChunkCoord mc = getMultiCoord(coord, areasize);
        return provider.caches().multiChunk.getOrCompute(mc, k -> new MultiChunk(mc, areasize).calculateBuildings(provider));
    }

    public MB getMultiBuilding(ChunkCoord coord) {
        return buildingGrid[coord.chunkX() - topleft.chunkX()][coord.chunkZ() - topleft.chunkZ()];
    }

    private static @NotNull ChunkCoord getMultiCoord(ChunkCoord coord, int areasize) {
        return new ChunkCoord(coord.dimension(),
                Math.floorDiv(coord.chunkX(), areasize),
                Math.floorDiv(coord.chunkZ(), areasize));
    }

    private MultiChunk calculateBuildings(PlanningContext provider) {
        RandomSource rand = Rng.at(provider.seed(), mc.chunkX(), mc.chunkZ(), Rng.Purpose.MULTI);

        // Determine how many multibuildings we want to place in this multichunk
        // Drawn at this area's own anchor rather than world-wide: how many multi-buildings an area
        // gets is a property of the pack whose cities stand in it.
        MultiSettings settings = provider.worldStyles().atMultiArea(mc).getMultiSettings();
        int min = settings.minimum();
        int max = settings.maximum();
        int cnt = min + rand.nextInt(max - min + 1);
        if (cnt <= 0) {
            // No buildings, early exit
            return this;
        }

        ChunkCoord topleft = new ChunkCoord(mc.dimension(), mc.chunkX() * areasize, mc.chunkZ() * areasize);
        int cityLevel = ChunkPlan.getCityLevel(topleft, provider);

        // Find all city styles in this multichunk and count them.
        //
        // Keyed on CityStyle *identity*: no cityassets class overrides equals/hashCode, so this
        // counter, the getMap().keySet() sort below and the Objects.equals in isMultiBuildingOk all
        // rely on one id resolving to exactly one instance. That holds only because
        // RegistryAssetRegistry canonicalises through putIfAbsent - it is not a property of
        // CityStyle. If AssetRegistries.reset() ever ran mid-generation, a chunk resolved before it
        // and one resolved after would hold two distinct instances of the same id: the counter
        // would split one style's votes in two, and the getId() sort would stop being a total order
        // (two entries comparing equal, ordered by whatever the HashMap handed over).
        //
        // reset() is confined to server start and server stop now, both on the server thread with
        // no level loaded (GenerationSession.open/close, issue #125). It used to be reachable from
        // the generation path itself - CityFeature.cleanUp(), invoked lazily from getDimensionInfo
        // whenever a global dirty counter differed from the feature's own, and that counter was
        // bumped from ClientPlayConnectionEvents.DISCONNECT on the client thread, which in
        // single-player fires while the integrated server is still draining in-flight generation.
        // So a chunk resolved either side of a reset really could hold two instances of one id, and
        // the split vote below was the mild consequence: the same reset emptied AssetRegistries'
        // stuff-by-tag index, which alone among the registries has no lazy rebuild, and those
        // chunks were written and saved with no decoration at all.
        //
        // Keying on ids rather than instances would close this loop's own exposure regardless of
        // who may reset what, and is still worth doing - it is just no longer load-bearing. The
        // guard in Stuff.generateStuff is the remaining detector for the wider failure.
        Counter<CityStyle> cityStyleCounter = new Counter<>();
        for (int x = 0 ; x < areasize ; x++) {
            for (int z = 0 ; z < areasize ; z++) {
                CityStyle cityStyle = City.getCityStyle(topleft.offset(x, z), provider, provider.preset());
                if (cityStyle == null) {
                    throw new RuntimeException("Cannot find city style for chunk: " + topleft.offset(x, z));
                }
                cityStyleCounter.add(cityStyle);
            }
        }

        // Get all the desired multibuildings based on the percentage of the city styles and the counter.
        // One list of pairs, not two parallel lists: the size sort below used to reorder only the
        // buildings, pairing them with the wrong styles (issue #39).
        record Chosen(MultiBuilding building, CityStyle style) {}
        List<Chosen> chosen = new ArrayList<>();
        List<CityStyle> styleList = new ArrayList<>(cityStyleCounter.getMap().keySet());
        // Sorted on getId(), not getName(): this imposes deterministic order on a HashMap
        // keySet, and Tools.getRandomFromList below walks the list subtracting weights, so the
        // order decides which style (and therefore which multibuilding) gets picked. getId()
        // never changes meaning under a future accessor rename the way getName() just did.
        // Identifier's own order - path, then namespace - is also what ChunkPlan's city-style
        // vote breaks ties on, so this asset kind has one order, not two.
        styleList.sort(Comparator.comparing(CityStyle::getId));
        for (int i = 0 ; i < cnt ; i++) {
            CityStyle cityStyle = Tools.getRandomFromList(rand, styleList, style -> (float) cityStyleCounter.get(style));
            String multiBuilding = cityStyle.getRandomMultiBuilding(rand, topleft);
            MultiBuilding mb = provider.assets().multiBuildings().get(multiBuilding);
            if (mb == null) {
                throw new RuntimeException("Cannot find multibuilding: " + multiBuilding);
            }
            chosen.add(new Chosen(mb, cityStyle));
        }

        // Sort the multibuildings by size. Largest first
        chosen.sort((c1, c2) -> Integer.compare(c2.building().getDimX() + c2.building().getDimZ(),
                c1.building().getDimX() + c1.building().getDimZ()));

        // For every building we want to place, try to find a spot
        for (Chosen ch : chosen) {
            MultiBuilding mb = ch.building();
            // Find the maximum possible number of cellars for all buildings in this multibuilding
            int maxCellars = 0;
            for (String b : mb.getBuildingSet()) {
                Building building = provider.assets().buildings().get(b);
                if (building == null) {
                    throw new RuntimeException("Cannot find building: " + b);
                }
                if (building.getMaxCellars() > maxCellars) {
                    maxCellars = building.getMaxCellars();
                }
            }
            int dimX = mb.getDimX();
            int dimZ = mb.getDimZ();
            // Try to find a spot with a number of attempts
            int attempts = settings.attempts();
            for (int att = 0 ; att < attempts ; att++) {
                int x = rand.nextInt(areasize - dimX + 1);
                int z = rand.nextInt(areasize - dimZ + 1);
                if (canPlaceBuilding(topleft, provider, provider.preset(), ch.style(), mb, cityLevel, maxCellars, x, z)) {
                    placeBuilding(mb, x, z);
                    break;
                }
            }
        }

        return this;
    }

    private void dump() {
        // Make a debug dump of the grid in this multichunk with each building a different character
        Map<String, String> charMap = new HashMap<>();
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        System.out.println("################################");
        System.out.println("mc = " + mc);
        for (int z = 0 ; z < areasize ; z++) {
            for (int x = 0 ; x < areasize ; x++) {
                MB building = buildingGrid[x][z];
                if (building == null) {
                    System.out.print(" ");
                } else {
                    String s = charMap.get(building.name);
                    if (s == null) {
                        s = chars.substring(0, 1);
                        chars = chars.substring(1);
                        charMap.put(building.name, s);
                    }
                    System.out.print(s);
                }
            }
            System.out.println();
        }
    }

    private boolean canPlaceBuilding(ChunkCoord topleft, PlanningContext provider, Preset profile, CityStyle buildingCityStyle, MultiBuilding building,
                                     int cityLevel, int maxCellars, int x, int z) {
        int partlevel = provider.worldStyles().primary().getWorldSettings().railPartHeight6();
        int correctStyle = 0;
        for (int xx = 0 ; xx < building.getDimX() ; xx++) {
            for (int zz = 0 ; zz < building.getDimZ() ; zz++) {
                if (buildingGrid[x+xx][z+zz] != null) {
                    return false;
                }
                ChunkCoord coord = topleft.offset(x + xx, z + zz);
                if (City.isChunkOccupied(provider, coord)) {
                    return false;
                }
                // The RAW road, never ChunkPlan.getEffectiveRoadType(). Effective roads depend on
                // city membership, which is fine, but routing this through ChunkPlan would make
                // multi-building acceptance depend on ChunkPlan, which depends on multi-building
                // acceptance. Predefined multi-buildings never reach here at all.
                RoadType rawRoad = provider.roadField().typeAt(coord.chunkX(), coord.chunkZ());
                if (profile.MULTI_BUILDING_STREET_CONFLICT.roadBlocks(rawRoad)) {
                    return false;
                }

                Railway.RailChunkInfo railChunkInfo = Railway.getRailChunkType(coord, provider, profile);
                RailChunkType type = railChunkInfo.getType();
                boolean atSurface = type.isSurface() || type.isStation();

                if (atSurface || !ChunkPlan.isCityRaw(coord, provider, profile) || ChunkPlan.hasHighway(coord, provider, profile)) {
                    return false;
                }
                WorldSettings.RailwayAvoidance avoidance = provider.worldStyles().primary().getWorldSettings().railwayAvoidance();
                if (type != RailChunkType.NONE && avoidance != WorldSettings.RailwayAvoidance.BLOCK_RAILWAY) {
                    int level = railChunkInfo.getLevel();
                    int max = Math.min(cityLevel - level - partlevel, maxCellars);
                    if (max < maxCellars) {
                        return false;
                    }
                }
                CityStyle cityStyle = City.getCityStyle(coord, provider, profile);
                if (Objects.equals(cityStyle, buildingCityStyle)) {
                    correctStyle++;
                }
            }
        }
        // Sufficient chunks need to be the correct cityStyle
        float correctStyleFactor = provider.worldStyles().atMultiArea(mc).getMultiSettings().correctStyleFactor();
        if (correctStyle < building.getDimX() * building.getDimZ() * correctStyleFactor) {
            return false;
        }
        return true;
    }

    private void placeBuilding(MultiBuilding building, int x, int z) {
        // getName() is the fully-qualified id: MB.name is fed straight back into
        // AssetRegistries.MULTI_BUILDINGS.getOrThrow(String) in ChunkPlan.initMultiBuildingSection.
        for (int xx = 0 ; xx < building.getDimX() ; xx++) {
            for (int zz = 0 ; zz < building.getDimZ() ; zz++) {
                buildingGrid[x+xx][z+zz] = new MB(building.getName(), xx, zz);
            }
        }
    }
}
