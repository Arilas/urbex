package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.ChunkCoord;

import dev.krona.urbex.worldgen.IDimensionInfo;


import java.util.Map;
import java.util.function.Function;

public class Highway {

    /**
     * Upper bound on how far a single highway segment is scanned in either direction before the run
     * is treated as degenerate. A highway is a straight run of contiguous chunks whose perlin value
     * exceeds {@code HIGHWAY_PERLIN_FACTOR}; the extent scan walks that run to find its two ends.
     * <p>
     * Guardrail against a non-terminating scan: if {@code HIGHWAY_PERLIN_FACTOR} is set (via config,
     * datapack, or editor) below the perlin's minimum output, {@code hasHighway} is true for EVERY
     * chunk and the unbounded walk would never return, freezing the calling thread (worldgen or the
     * synchronous GUI preview). {@code 10_000} chunks is 160,000 blocks - orders of magnitude longer
     * than any legitimate highway, which merely connects nearby cities and terminates at the first
     * chunk whose perlin value drops below the factor. For any realistic factor the run ends long
     * before this cap, so normal generation (and its digest) is unchanged; only the pathological
     * "every chunk is a highway" case ever reaches the cap, and it is then reported as "no highway".
     */
    static final int MAX_HIGHWAY_SCAN = 10_000;

    public static boolean hasHighway(ChunkCoord coord, IDimensionInfo provider, Preset profile) {
        if (getXHighwayLevel(coord, provider, profile) >= 0) {
            return true;
        }
        if (getZHighwayLevel(coord, provider, profile) >= 0) {
            return true;
        }
        return false;
    }

    /**
     * Returns -1 if there is no highway in X direction that goes through this chunk.
     * Returns 0 or 1 if there is a highway (at that city level) going through this chunk.
     */
    public static int getXHighwayLevel(ChunkCoord coord, IDimensionInfo provider, Preset profile) {
        return getHighwayLevel(provider, profile, provider.caches().xHighwayLevel,
                cp -> hasXHighway(provider, cp, profile), Orientation.X, coord);
    }

    /**
     * Returns -1 if there is no highway in Z direction that goes through this chunk.
     * Returns 0 or 1 if there is a highway (at that city level) going through this chunk.
     */
    public static int getZHighwayLevel(ChunkCoord coord, IDimensionInfo provider, Preset profile) {
        return getHighwayLevel(provider, profile, provider.caches().zHighwayLevel,
                cp -> hasZHighway(provider, cp, profile), Orientation.Z, coord);
    }

    private static int getHighwayLevel(IDimensionInfo provider, Preset profile, Map<ChunkCoord, Integer> cache, Function<ChunkCoord, Boolean> hasHighway, Orientation orientation, ChunkCoord cp) {
        Integer known = cache.get(cp);
        if (known != null) {
            return known;
        }

        // Highways can only occur at chunkZ that is a multiple of 8
        int mask = profile.HIGHWAY_DISTANCE_MASK;
        if (mask <= 0) {
            cache.put(cp, -1);
            return -1;
        }

        if ((cp.getCoord(orientation.getOpposite()) & mask) != 0) {
            cache.put(cp, -1);
            return -1;
        }

        if (hasHighway.apply(cp)) {
            // This is part of a highway. Find the left-most chunk that is still part of this highway
            ChunkCoord lowerEnd = scanHighwayExtent(hasHighway, cp.lower(orientation), orientation, false, MAX_HIGHWAY_SCAN);
            if (lowerEnd == null) {
                // Degenerate run (factor below the perlin floor => every chunk is a "highway"). This
                // is not a real highway network; bail instead of looping forever.
                cache.put(cp, -1);
                return -1;
            }
            ChunkCoord lower = lowerEnd.higher(orientation);     // This is now where the highway starts

            // Find the right-most chunk that is still part of this highway
            ChunkCoord higherEnd = scanHighwayExtent(hasHighway, cp.higher(orientation), orientation, true, MAX_HIGHWAY_SCAN);
            if (higherEnd == null) {
                cache.put(cp, -1);
                return -1;
            }
            ChunkCoord higher = higherEnd.lower(orientation);     // This is now where the highway ends

            int level = -1;
            if (higher.getCoord(orientation)-lower.getCoord(orientation) >= 5) {
                boolean valid;
                if (profile.HIGHWAY_REQUIRES_TWO_CITIES) {
                    valid = BuildingInfo.isCityRaw(lower, provider, profile) && BuildingInfo.isCityRaw(higher, provider, profile);
                } else {
                    valid = BuildingInfo.isCityRaw(lower, provider, profile) || BuildingInfo.isCityRaw(higher, provider, profile);
                }
                if (valid) {
                    // We have at least one city. Valid highway:
                    level = switch (profile.HIGHWAY_LEVEL_FROM_CITIES_MODE) {
                        case 0 -> BuildingInfo.getCityLevel(lower, provider);
                        case 1 -> Math.min(BuildingInfo.getCityLevel(lower, provider),
                                BuildingInfo.getCityLevel(higher, provider));
                        case 2 -> Math.max(BuildingInfo.getCityLevel(lower, provider),
                                BuildingInfo.getCityLevel(higher, provider));
                        case 3 -> (BuildingInfo.getCityLevel(lower, provider) +
                                BuildingInfo.getCityLevel(higher, provider)) / 2;
                        default -> throw new RuntimeException("Bad value for 'highwayLevelFromCities'!");
                    };
                    for (ChunkCoord cc = lower; cc.getCoord(orientation) <= higher.getCoord(orientation); cc = cc.higher(orientation)) {
                        cache.put(cc, level);
                    }
                }
            }
            return level;

        }

        cache.put(cp, -1);
        return -1;
    }

    /**
     * Walks chunk-by-chunk from {@code start} along {@code orientation} (higher or lower) for as long
     * as {@code hasHighway} keeps returning true, and returns the first chunk for which it is false -
     * i.e. the chunk just past the end of the contiguous highway run in that direction.
     * <p>
     * The walk is bounded by {@code cap}: if it takes more than {@code cap} steps without the
     * predicate turning false, the run is degenerate (an always-true predicate) and {@code null} is
     * returned so the caller can bail instead of looping forever. For any non-degenerate predicate
     * the result is identical to an unbounded while-loop, so normal generation is unaffected.
     *
     * @param goHigher true to walk in the higher direction, false for the lower direction
     * @return the first non-highway chunk in the given direction, or {@code null} if {@code cap} was exceeded
     */
    static ChunkCoord scanHighwayExtent(Function<ChunkCoord, Boolean> hasHighway, ChunkCoord start,
                                        Orientation orientation, boolean goHigher, int cap) {
        ChunkCoord c = start;
        int steps = 0;
        while (hasHighway.apply(c)) {
            if (++steps > cap) {
                return null;
            }
            c = goHigher ? c.higher(orientation) : c.lower(orientation);
        }
        return c;
    }

    private static boolean hasXHighway(IDimensionInfo provider, ChunkCoord cp, Preset profile) {
        return provider.caches().highwayPerlinX.getValue(cp.chunkX() / profile.HIGHWAY_MAINPERLIN_SCALE, cp.chunkZ() / profile.HIGHWAY_SECONDARYPERLIN_SCALE)
                > profile.HIGHWAY_PERLIN_FACTOR;
    }

    private static boolean hasZHighway(IDimensionInfo provider, ChunkCoord cp, Preset profile) {
        return provider.caches().highwayPerlinZ.getValue(cp.chunkX() / profile.HIGHWAY_SECONDARYPERLIN_SCALE, cp.chunkZ() / profile.HIGHWAY_MAINPERLIN_SCALE)
                > profile.HIGHWAY_PERLIN_FACTOR;
    }

}
