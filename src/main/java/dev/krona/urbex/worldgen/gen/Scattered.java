package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.config.LostCityProfile;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.ChunkDriver;
import dev.krona.urbex.worldgen.ChunkGenContext;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.LostCityTerrainFeature;
import dev.krona.urbex.worldgen.lost.*;
import dev.krona.urbex.worldgen.lost.cityassets.*;
import dev.krona.urbex.worldgen.lost.regassets.data.ScatteredReference;
import dev.krona.urbex.worldgen.lost.regassets.data.ScatteredSettings;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class Scattered {
    public static boolean avoidScattered(LostCityTerrainFeature feature, BuildingInfo info) {
        if (info.isCity) {
            return true;
        }
        if (info.hasBridge(feature.provider)) {
            return true;
        }
        return Highway.hasHighway(info.coord, feature.provider, feature.profile);
    }

    public static void generateScattered(ChunkGenContext ctx, LostCityTerrainFeature feature, BuildingInfo info, ScatteredSettings scatteredSettings, ChunkHeightmap heightmap) {
        int chunkX = info.coord.chunkX();
        int chunkZ = info.coord.chunkZ();
        IDimensionInfo provider = feature.provider;

        // First normalize the coordinates to scatter area sized coordinates. Add a large amount to make sure the coordinates are positive
        int ax = (chunkX + 2000000) / scatteredSettings.getAreasize();
        int az = (chunkZ + 2000000) / scatteredSettings.getAreasize();

        RandomSource scatteredRandom = Rng.at(provider.getSeed(), ax, az, Rng.Purpose.SCATTERED);

        if (scatteredRandom.nextFloat() >= (scatteredSettings.getChance() * provider.getProfile().SCATTERED_CHANCE_MULTIPLIER)) {
            // No scattered structure in this area
            return;
        }

        // Find the right type of scattered asset for this area
        ScatteredReference reference = selectRandomScattered(feature, info, scatteredSettings, scatteredRandom);
        if (reference == null) {
            // Nothing matches
            return;
        }
        ScatteredBuilding scattered = AssetRegistries.SCATTERED.getOrThrow(provider.getWorld(), reference.getName());

        // Find the size of the scattered building
        int w;
        int h;
        MultiBuilding multiBuilding;
        if (scattered.getMultibuilding() != null) {
            multiBuilding = AssetRegistries.MULTI_BUILDINGS.getOrThrow(provider.getWorld(), scattered.getMultibuilding());
            w = multiBuilding.getDimX();
            h = multiBuilding.getDimZ();
        } else {
            w = h = 1;
            multiBuilding = null;
        }

        // Find the position of the building in the world
        int tlChunkX = (ax * scatteredSettings.getAreasize() - 2000000) + scatteredRandom.nextInt(scatteredSettings.getAreasize() - w + 1);
        int tlChunkZ = (az * scatteredSettings.getAreasize() - 2000000) + scatteredRandom.nextInt(scatteredSettings.getAreasize() - h + 1);

        if (chunkX < tlChunkX || chunkZ < tlChunkZ || chunkX >= (tlChunkX + w) || chunkZ >= (tlChunkZ + h)) {
            return;
        }

        // First test the conditions for all the relevant chunks (does this need to be cached?)
        int minheight = Integer.MAX_VALUE;
        int maxheight = Integer.MIN_VALUE;
        int avgheight = 0;
        for (int x = tlChunkX; x < tlChunkX + w; x++) {
            for (int z = tlChunkZ; z < tlChunkZ + h; z++) {
                ChunkCoord coord = new ChunkCoord(provider.getType(), x, z);
                if (!isValidScatterBiome(feature, reference, coord)) {
                    return;
                }
                BuildingInfo tinfo = BuildingInfo.getBuildingInfo(coord, provider);
                if (avoidScattered(feature, tinfo)) {
                    return;
                }
                if (reference.isNearHighway()) {
                    if (!Highway.hasHighway(coord.east(), provider, feature.profile) &&
                            !Highway.hasHighway(coord.west(), provider, feature.profile) &&
                            !Highway.hasHighway(coord.north(), provider, feature.profile) &&
                            !Highway.hasHighway(coord.south(), provider, feature.profile)) {
                        return;
                    }
                }
                ChunkHeightmap hm = feature.getHeightmap(coord, provider.getWorld());
                int height = hm.getHeight();
                hm.calculateAccurateHeight(provider.getWorld(), x, z);   // generator-only, no block access
                if (!reference.isAllowVoid()) {
                    if (!(feature.profile.isDefault() || feature.profile.isCavern())) {
                        // We are in a world that can have void chunks. Check if this chunk is a void chunk
                        if (height <= feature.provider.getWorld().getMinY() + 3) {
                            return;
                        }
                    }
                }
                minheight = Math.min(minheight, hm.getMinHeight());
                maxheight = Math.max(maxheight, hm.getMaxHeight());
                avgheight += height;
            }
        }
        // Check the height difference
        if (reference.getMaxheightdiff() != null) {
            int diff = maxheight - minheight;
            if (diff > reference.getMaxheightdiff()) {
                return;
            }
        }

        avgheight /= w * h;

        // We need to generate a part of the building
        if (multiBuilding == null) {
            // A single building
            List<String> buildings = scattered.getBuildings();
            if (buildings == null) {
                throw new RuntimeException("Missing buildings for scattered '" + reference.getName() + "'!");
            }
            String buildingName;
            if (buildings.size() == 1) {
                buildingName = buildings.get(0);
            } else {
                buildingName = buildings.get(scatteredRandom.nextInt(buildings.size()));
            }
            Building building = AssetRegistries.BUILDINGS.getOrThrow(provider.getWorld(), buildingName);
            int lowestLevel = handleScatteredTerrain(feature, scattered, info.coord, heightmap);
            if (lowestLevel < -4000) {
                LostCityProfile profile = feature.provider.getProfile();
                if (profile.isCavern()) {
                    lowestLevel = profile.GROUNDLEVEL;
                } else {
                    lowestLevel = provider.getWorld().getMinY() + 2;  // @todo is this right?
                }
            }
            generateScatteredBuilding(ctx, feature, info, building, scatteredRandom, lowestLevel, scattered.getTerrainfix());
        } else {
            int lowestLevel = handleScatteredTerrainMulti(feature, scattered, info.coord, minheight, maxheight, avgheight);
            int relx = chunkX - tlChunkX;
            int relz = chunkZ - tlChunkZ;
            String buildingName = multiBuilding.getBuilding(relx, relz);
            Building building = AssetRegistries.BUILDINGS.getOrThrow(provider.getWorld(), buildingName);
            generateScatteredBuilding(ctx, feature, info, building, scatteredRandom, lowestLevel, scattered.getTerrainfix());
        }
    }

    @Nullable
    private static ScatteredReference selectRandomScattered(LostCityTerrainFeature feature, BuildingInfo info, ScatteredSettings scatteredSettings, RandomSource rand) {
        List<ScatteredReference> list = scatteredSettings.getList();
        if (list.isEmpty()) {
            return null;
        }

        int totalweight = 0;
        List<ScatteredReference> filteredList = new ArrayList<>();
        for (ScatteredReference reference : list) {
            if (isValidScatterBiome(feature, reference, info.coord)) {
                totalweight += reference.getWeight();
                filteredList.add(reference);
            }
        }
        if (filteredList.isEmpty()) {
            return null;
        }

        int rndweight = rand.nextInt(totalweight + scatteredSettings.getWeightnone());
        ScatteredReference reference = null;
        for (ScatteredReference scatteredReference : filteredList) {
            int weight = scatteredReference.getWeight();
            if (rndweight <= weight) {
                reference = scatteredReference;
                break;
            }
            rndweight -= weight;
        }
        return reference;
    }

    private static boolean isValidScatterBiome(LostCityTerrainFeature feature, ScatteredReference reference, ChunkCoord coord) {
        if (reference.getBiomeMatcher() != null) {
            BiomeInfo biome = BiomeInfo.getBiomeInfo(feature.provider, coord);
            return reference.getBiomeMatcher().test(biome.getMainBiome());
        }
        return true;
    }

    private static void generateScatteredBuilding(ChunkGenContext ctx, LostCityTerrainFeature feature, BuildingInfo info, Building building, RandomSource rand, int lowestLevel, ScatteredBuilding.TerrainFix terrainFix) {
        IDimensionInfo provider = feature.provider;

        int height = lowestLevel;
        int floors;
        int minfloors = building.getMinFloors();
        if (minfloors <= 0) {
            minfloors = 1;
        }
        int maxfloors = building.getMaxFloors();
        if (maxfloors <= 0) {
            maxfloors = 1;
        }
        if (minfloors >= maxfloors) {
            floors = minfloors;
        } else {
            floors = minfloors + rand.nextInt(maxfloors - minfloors + 1);
        }
        // TODO top condition is wrong due to floor calculation being different
        String belowFloor = "<none>";
        for (int f = 0; f < floors; f++) {
            ConditionContext conditionContext = new ConditionContext(lowestLevel, f, 0, floors, "<none>", belowFloor, building.getName(), info.coord) {
                @Override
                public boolean isBuilding() {
                    return true;
                }

                @Override
                public boolean isSphere() {
                    return CitySphere.isInSphere(info.coord, info.getCenter(0), provider);
                }

                @Override
                public Identifier getBiome() {
                    // provider.getBiome(), not level.getBiome(): the latter goes through the chunk,
                    // which during generation may not be loaded in this region at all.
                    Holder<Biome> biome = provider.getBiome(info.getCenter(0));
                    return biome.unwrap().map(ResourceKey::identifier, b -> provider.getWorld().registryAccess().lookupOrThrow(Registries.BIOME).getKey(b));
                }
            };
            ChunkDriver driver = ctx.driver;
            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState liquid = feature.liquid;
            String randomPart = building.getRandomPart(rand, conditionContext);
            BuildingPart part = AssetRegistries.PARTS.getOrThrow(provider.getWorld(), randomPart);
            belowFloor = randomPart;
            randomPart = building.getRandomPart2(rand, conditionContext);
            BuildingPart part2 = AssetRegistries.PARTS.get(provider.getWorld(), randomPart);    // Null is legal

            if (f == 0) {
                switch (terrainFix) {
                    case NONE -> {
                    }
                    case CLEAR -> {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                feature.clearRange(ctx, info, x, z, lowestLevel, lowestLevel + 50, false);
                            }
                        }
                    }
                    case REPEATSLICE -> {
                        CompiledPalette compiledPalette = feature.computePalette(info, part);
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                char c = part.getPaletteChar(x, 0, z);
                                if (c != ' ') {
                                    int y = lowestLevel - 1;
                                    driver.current(x, y, z);
                                    BlockState b = driver.getBlock();
                                    while (b == air || b == liquid) {
                                        driver.block(ctx.paletteHere(compiledPalette, c));
                                        driver.decY();
                                        b = driver.getBlock();
                                    }
                                }
                            }
                        }
                    }
                }
            }

            height = feature.generatePart(ctx, info, part, Transform.ROTATE_NONE, 0, height, 0, LostCityTerrainFeature.HardAirSetting.AIR);
            if (part2 != null) {
                feature.generatePart(ctx, info, part2, Transform.ROTATE_NONE, 0, height, 0, LostCityTerrainFeature.HardAirSetting.AIR);
            }
        }
    }

    private static int handleScatteredTerrain(LostCityTerrainFeature feature, ScatteredBuilding scattered, ChunkCoord coord, ChunkHeightmap heightmap) {
        int lowestLevel = switch (scattered.getTerrainheight()) {
            case LOWEST -> heightmap.getHeight();
            case AVERAGE -> heightmap.getHeight();
            case HIGHEST -> heightmap.getHeight();
            case OCEAN -> ((ServerChunkCache) feature.provider.getWorld().getChunkSource()).getGenerator().getSeaLevel();
        };
        lowestLevel += scattered.getHeightoffset();
        return lowestLevel;
    }

    private static int handleScatteredTerrainMulti(LostCityTerrainFeature feature, ScatteredBuilding scattered, ChunkCoord coord, int minimum, int maximum, int average) {
        int lowestLevel = switch (scattered.getTerrainheight()) {
            case LOWEST -> minimum;
            case AVERAGE -> maximum;
            case HIGHEST -> average;
            case OCEAN -> ((ServerChunkCache) feature.provider.getWorld().getChunkSource()).getGenerator().getSeaLevel();
        };
        lowestLevel += scattered.getHeightoffset();
        return lowestLevel;
    }
}
