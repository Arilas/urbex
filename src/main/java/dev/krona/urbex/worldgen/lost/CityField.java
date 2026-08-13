package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.PlanningContext;

/**
 * Where a city is, and how high it sits.
 *
 * <p>Two questions answered at one coordinate: whether the city mask covers it at all, and which of
 * the preset's nine level bands the terrain there falls into. They are one class because they are
 * mutually recursive - averaging the heightmap over a neighbourhood only counts the neighbours that
 * are themselves city - and because they are the same kind of thing: a pure function of the world
 * seed and a chunk coordinate, with no dependency on any building decision.</p>
 *
 * <p><strong>Raw, and that is load-bearing.</strong> These are consulted while the chunk candidate
 * graph is still being computed, so they must not read anything that depends on a building decision,
 * its own or a neighbour's, or the graph stops being acyclic. {@code ChunkPlan.isCity} is the
 * decided form and belongs to the layer above; this is the one underneath it.</p>
 *
 * <p>Split out of {@link ChunkPlan} (issue #11), which is where all of this lived along with
 * everything else about a planned chunk.</p>
 */
public final class CityField {

    private CityField() {
    }

    /**
     * Don't use the cache as we're busy building the cache.
     */
    public static boolean isCityRaw(ChunkCoord coord, PlanningContext provider, Preset profile) {
        if (isVoidChunk(coord, provider)) {
            // If we have a void chunk then no city here
            return false;
        }

        float cityFactor = City.getCityFactor(coord, provider, profile);
        return cityFactor > profile.cityThreshold();
    }

    public static boolean isVoidChunk(ChunkCoord coord, PlanningContext provider) {
        if (provider.preset().isFloating()) {
            return provider.heightmap(coord).getHeight() <= 0;
        } else {
            return false;
        }
    }


    /**
     * This function does not use the cache. So safe to use when the cache is building
     * This function uses its own cache.
     */
    public static int getCityLevel(ChunkCoord key, PlanningContext provider) {
        // Unconditional. This used to be gated on provider.getWorld() != null, "In LC preview we
        // don't want to use the cache as the config isn't loaded yet" - a guard from when the
        // preview shared the dimension's caches. It has held its own DimensionCaches, built from
        // its own seed and dropped with it, since #125; and the value cached here is a pure function
        // of the seed and the preset, both of which are fixed for one preview.
        Integer cached = provider.caches().cityLevel.get(key);
        if (cached != null) {
            return cached;
        }
        int result;
        if (provider.preset().isFloating()) {
            result = getCityLevelFloating(key, provider);
        } else if (provider.preset().isCavern()) {
            result =  getCityLevelCavern(key, provider);
        } else {
            result = getCityLevelNormal(key, provider, provider.preset());
        }
        Integer raced = provider.caches().cityLevel.putIfAbsent(key, result);
        if (raced != null) {
            return raced;
        }
        return result;
    }

    public static int cityLevelUncached(ChunkCoord key, PlanningContext provider) {
        int result;
        if (provider.preset().isFloating()) {
            result = getCityLevelFloating(key, provider);
        } else if (provider.preset().isCavern()) {
            result =  getCityLevelCavern(key, provider);
        } else {
            result = getCityLevelNormal(key, provider, provider.preset());
        }
        return result;
    }

    private static int getCityLevelCavern(ChunkCoord coord, PlanningContext provider) {
        // @todo for now
        return getCityLevelFloating(coord, provider);
    }


    private static int getCityLevelNormal(ChunkCoord coord, PlanningContext provider, Preset profile) {
        ChunkHeightmap heightmap = provider.heightmap(coord);
        int height = heightmap.getHeight();
        if (profile.useAvgHeightmap() && Config.heightSampleSize() > 2) {
            int sampleSize = Config.heightSampleSize();
            int constX = coord.chunkX() < 0 ? -1 : 1;
            int constZ = coord.chunkZ() < 0 ? -1 : 1;
            int chunkBaseX =  (coord.chunkX() / sampleSize) * sampleSize + (sampleSize / 2 * constX);
            int chunkBaseZ =  (coord.chunkZ() / sampleSize) * sampleSize + (sampleSize / 2 * constZ);
            int chunkLeft = ((coord.chunkX() / sampleSize) - 1) * sampleSize + (sampleSize / 2 * constX);
            int chunkRight = ((coord.chunkX() / sampleSize) + 1) * sampleSize + (sampleSize / 2 * constX);
            int chunkUp = ((coord.chunkZ() / sampleSize) - 1) * sampleSize + (sampleSize / 2 * constZ);
            int chunkDown = ((coord.chunkZ() / sampleSize) + 1) * sampleSize + (sampleSize / 2 * constZ);
            ChunkCoord left = new ChunkCoord(provider.dimension(), chunkLeft, chunkBaseZ);
            ChunkCoord right = new ChunkCoord(provider.dimension(), chunkRight, chunkBaseZ);
            ChunkCoord up = new ChunkCoord(provider.dimension(), chunkBaseX, chunkUp);
            ChunkCoord down = new ChunkCoord(provider.dimension(), chunkBaseX, chunkDown);
            int avgHeightmap = height;
            int counter = 1;
            if (isCityRaw(left, provider, profile)) {
                avgHeightmap += provider.heightmap(left).getHeight();
                counter++;
            }
            if (isCityRaw(right, provider, profile)) {
                avgHeightmap += provider.heightmap(right).getHeight();
                counter++;
            }
            if (isCityRaw(up, provider, profile)) {
                avgHeightmap += provider.heightmap(up).getHeight();
                counter++;
            }
            if (isCityRaw(down, provider, profile)) {
                avgHeightmap += provider.heightmap(down).getHeight();
                counter++;
            }
            avgHeightmap /= counter;
            return getLevelBasedOnHeight(avgHeightmap, profile);
        }
        return getLevelBasedOnHeight(height, profile);
    }

    private static int getCityLevelFloating(ChunkCoord coord, PlanningContext provider) {
        int h = provider.heightmap(coord).getHeight();
        return getLevelBasedOnHeight(h, provider.preset());
    }

    private static int getLevelBasedOnHeight(int height, Preset profile) {
        if (height < profile.cityLevel0Height()) {
            return 0;
        } else if (height < profile.cityLevel1Height()) {
            return 1;
        } else if (height < profile.cityLevel2Height()) {
            return 2;
        } else if (height < profile.cityLevel3Height()) {
            return 3;
        } else if (height < profile.cityLevel4Height()) {
            return 4;
        } else if (height < profile.cityLevel5Height()) {
            return 5;
        } else if (height < profile.cityLevel6Height()) {
            return 6;
        } else if (height < profile.cityLevel7Height()) {
            return 7;
        } else {
            return 8;
        }
    }
}
