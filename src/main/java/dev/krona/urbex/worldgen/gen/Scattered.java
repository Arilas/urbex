package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.ChunkDriver;
import dev.krona.urbex.worldgen.ChunkGenContext;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.CityGenerator;
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
    public static boolean avoidScattered(CityGenerator feature, BuildingInfo info) {
        if (info.isCity) {
            return true;
        }
        if (info.hasBridge(feature.provider)) {
            return true;
        }
        return Highway.hasHighway(info.coord, feature.provider, feature.profile);
    }

    public static void generateScattered(ChunkGenContext ctx, CityGenerator feature, BuildingInfo info, ScatteredSettings scatteredSettings) {
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

        // Find the right type of scattered asset for this area. The biome pre-filter is keyed
        // on the area's anchor chunk, never the chunk that happens to be generating: every
        // chunk of the area must compute the same filtered list and draw the same reference,
        // or a multi-chunk building disagrees with itself about where it stands (issue #38).
        ChunkCoord areaAnchor = new ChunkCoord(provider.getType(),
                ax * scatteredSettings.getAreasize() - 2000000,
                az * scatteredSettings.getAreasize() - 2000000);
        ScatteredReference reference = selectRandomScattered(feature, areaAnchor, scatteredSettings, scatteredRandom);
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

        // Test the conditions for all the relevant chunks. Cached on the area: every chunk of
        // the footprint used to redo the full scan - w*h noise-column samples each, O((w*h)^2)
        // in total (issue #38). Everything the scan reads is a pure function of the area, so
        // the first chunk to arrive computes it for all of them.
        AreaScan scan = provider.caches().scatterAreaScan.getOrCompute(areaAnchor,
                k -> scanArea(feature, reference, tlChunkX, tlChunkZ, w, h));
        if (!scan.valid()) {
            return;
        }
        // Check the height difference
        if (reference.getMaxheightdiff() != null) {
            int diff = scan.maxheight() - scan.minheight();
            if (diff > reference.getMaxheightdiff()) {
                return;
            }
        }

        int minheight = scan.minheight();
        int maxheight = scan.maxheight();
        int avgheight = scan.avgheight();

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
            int lowestLevel = scatteredLevel(feature, scattered, minheight, maxheight, avgheight);
            if (lowestLevel < -4000) {
                Preset profile = feature.provider.getProfile();
                if (profile.isCavern()) {
                    lowestLevel = profile.GROUNDLEVEL;
                } else {
                    lowestLevel = provider.getWorld().getMinY() + 2;  // @todo is this right?
                }
            }
            generateScatteredBuilding(ctx, feature, info, building, scatteredRandom, lowestLevel, scattered.getTerrainfix());
        } else {
            int lowestLevel = scatteredLevel(feature, scattered, minheight, maxheight, avgheight);
            int relx = chunkX - tlChunkX;
            int relz = chunkZ - tlChunkZ;
            String buildingName = multiBuilding.getBuilding(relx, relz);
            Building building = AssetRegistries.BUILDINGS.getOrThrow(provider.getWorld(), buildingName);
            generateScatteredBuilding(ctx, feature, info, building, scatteredRandom, lowestLevel, scattered.getTerrainfix());
        }
    }

    @Nullable
    private static ScatteredReference selectRandomScattered(CityGenerator feature, ChunkCoord areaAnchor, ScatteredSettings scatteredSettings, RandomSource rand) {
        List<ScatteredReference> list = scatteredSettings.getList();
        if (list.isEmpty()) {
            return null;
        }

        int totalweight = 0;
        List<ScatteredReference> filteredList = new ArrayList<>();
        for (ScatteredReference reference : list) {
            if (isValidScatterBiome(feature, reference, areaAnchor)) {
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
            // Strict comparison: <= gave the first entry one extra winning value (issue #38)
            if (rndweight < weight) {
                reference = scatteredReference;
                break;
            }
            rndweight -= weight;
        }
        return reference;
    }

    /** What the area scan learned about a scatter area: whether it may generate, and its heights. */
    public record AreaScan(boolean valid, int minheight, int maxheight, int avgheight) {
        private static final AreaScan INVALID = new AreaScan(false, 0, 0, 0);
    }

    /**
     * Validates every chunk of a scatter footprint and gathers its height statistics. Pure
     * function of the area (all inputs derive from the area coordinate), which is what makes
     * caching it per area sound.
     */
    private static AreaScan scanArea(CityGenerator feature, ScatteredReference reference, int tlChunkX, int tlChunkZ, int w, int h) {
        IDimensionInfo provider = feature.provider;
        int minheight = Integer.MAX_VALUE;
        int maxheight = Integer.MIN_VALUE;
        int avgheight = 0;
        for (int x = tlChunkX; x < tlChunkX + w; x++) {
            for (int z = tlChunkZ; z < tlChunkZ + h; z++) {
                ChunkCoord coord = new ChunkCoord(provider.getType(), x, z);
                if (!isValidScatterBiome(feature, reference, coord)) {
                    return AreaScan.INVALID;
                }
                BuildingInfo tinfo = BuildingInfo.getBuildingInfo(coord, provider);
                if (avoidScattered(feature, tinfo)) {
                    return AreaScan.INVALID;
                }
                if (reference.isNearHighway()) {
                    if (!Highway.hasHighway(coord.east(), provider, feature.profile) &&
                            !Highway.hasHighway(coord.west(), provider, feature.profile) &&
                            !Highway.hasHighway(coord.north(), provider, feature.profile) &&
                            !Highway.hasHighway(coord.south(), provider, feature.profile)) {
                        return AreaScan.INVALID;
                    }
                }
                // A copy: calculateAccurateHeight writes minHeight/maxHeight, and the cached
                // instance is shared with every thread generating near this chunk. See #24.
                ChunkHeightmap hm = new ChunkHeightmap(feature.getHeightmap(coord, provider.getWorld()));
                int height = hm.getHeight();
                hm.calculateAccurateHeight(provider.getWorld(), x, z);   // generator-only, no block access
                if (!reference.isAllowVoid()) {
                    if (!(feature.profile.isDefault() || feature.profile.isCavern())) {
                        // We are in a world that can have void chunks. Check if this chunk is a void chunk
                        if (height <= provider.getWorld().getMinY() + 3) {
                            return AreaScan.INVALID;
                        }
                    }
                }
                minheight = Math.min(minheight, hm.getMinHeight());
                maxheight = Math.max(maxheight, hm.getMaxHeight());
                avgheight += height;
            }
        }
        return new AreaScan(true, minheight, maxheight, avgheight / (w * h));
    }

    private static boolean isValidScatterBiome(CityGenerator feature, ScatteredReference reference, ChunkCoord coord) {
        if (reference.getBiomeMatcher() != null) {
            BiomeInfo biome = BiomeInfo.getBiomeInfo(feature.provider, coord);
            return reference.getBiomeMatcher().test(biome.getMainBiome());
        }
        return true;
    }

    private static void generateScatteredBuilding(ChunkGenContext ctx, CityGenerator feature, BuildingInfo info, Building building, RandomSource rand, int lowestLevel, ScatteredBuilding.TerrainFix terrainFix) {
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
        String belowFloor = ConditionContext.NO_PART;
        for (int f = 0; f < floors; f++) {
            ConditionContext conditionContext = new ConditionContext(lowestLevel, f, 0, floors, ConditionContext.NO_PART, belowFloor, building.getName(), info.coord) {
                @Override
                public boolean isBuilding() {
                    return true;
                }

                @Override
                public Identifier getBiome() {
                    // ctx.region, not provider.getWorld(): the region is what this used to be, back
                    // when IDimensionInfo held a mutable world reference. Not provider.getBiome()
                    // either - that asks the biome source directly, while WorldGenLevel.getBiome
                    // goes through BiomeManager, which applies a seeded sub-quart fuzzy offset. The
                    // two disagree near quart boundaries, so swapping them would move output.
                    Holder<Biome> biome = ctx.region.getBiome(info.getCenter(0));
                    return biome.unwrap().map(ResourceKey::identifier, b -> provider.getWorld().registryAccess().lookupOrThrow(Registries.BIOME).getKey(b));
                }
            };
            ChunkDriver driver = ctx.driver;
            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState liquid = feature.liquid;
            String partName = building.getRandomPart(rand, conditionContext);
            BuildingPart part = AssetRegistries.PARTS.getOrThrow(provider.getWorld(), partName);
            // getRandomPart2 derives its own context (ConditionContext.withPart) with partName as
            // the current part. This used to pass conditionContext itself, whose part is NO_PART,
            // so a scattered building's parts2[] "inpart" could never match while a city
            // building's matched normally - one field, two meanings.
            String part2Name = building.getRandomPart2(rand, conditionContext, partName);
            BuildingPart part2 = AssetRegistries.PARTS.get(provider.getWorld(), part2Name);    // Null is legal
            // Read by the next iteration's parts[] context, at the top of the loop.
            belowFloor = partName;

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

            height = feature.generatePart(ctx, info, part, Transform.ROTATE_NONE, 0, height, 0, CityGenerator.HardAirSetting.AIR);
            if (part2 != null) {
                feature.generatePart(ctx, info, part2, Transform.ROTATE_NONE, 0, height, 0, CityGenerator.HardAirSetting.AIR);
            }
        }
    }

    private static int scatteredLevel(CityGenerator feature, ScatteredBuilding scattered, int minimum, int maximum, int average) {
        int seaLevel = ((ServerChunkCache) feature.provider.getWorld().getChunkSource()).getGenerator().getSeaLevel();
        return pickLevel(scattered.getTerrainheight(), minimum, maximum, average, seaLevel) + scattered.getHeightoffset();
    }

    /**
     * The base level a terrain-height policy asks for. The old multi-chunk switch had the
     * AVERAGE and HIGHEST arms swapped, and the single-chunk variant returned the same value
     * for all three (issue #38).
     */
    static int pickLevel(ScatteredBuilding.TerrainHeight terrainHeight, int minimum, int maximum, int average, int seaLevel) {
        return switch (terrainHeight) {
            case LOWEST -> minimum;
            case AVERAGE -> average;
            case HIGHEST -> maximum;
            case OCEAN -> seaLevel;
        };
    }
}
