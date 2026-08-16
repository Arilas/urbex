package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.ChunkDriver;
import dev.krona.urbex.worldgen.ChunkGenContext;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.PlanningContext;
import dev.krona.urbex.worldgen.TagSnapshot;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.BiomeInfo;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Making the ground fit what is about to be built on it.
 *
 * <p>Clearing a range, flattening and blending the terrain at a city's edge, walking a column up or
 * down to the first solid or empty block, and the height offsets that decide where a city level
 * sits. Everything that reads or rewrites vanilla terrain before the city goes in.</p>
 *
 * <p>Moved out of {@link CityGenerator} unchanged - same code, same order, same RNG draws
 * (issue #11). {@code isEmpty} stays behind: it is a predicate on a block state with 107 callers
 * across the mod, and it is vocabulary rather than terrain shaping.</p>
 */
public class Terrain {

    private Terrain() {
    }

    public static void clearRange(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, int x, int z, int height1, int height2, boolean dowater) {
        ChunkDriver driver = ctx.driver;
        if (dowater) {
            // Special case for drowned city
            driver.setBlockRange(x, height1, z, info.waterLevel, feature.liquid);
            driver.setBlockRangeToAir(x, info.waterLevel + 1, z, height2);
        } else {
            driver.setBlockRangeToAir(x, height1, z, height2);
        }
    }

    public static void clearRange(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, int x, int z, int height1, int height2, boolean dowater, Predicate<BlockState> test) {
        ChunkDriver driver = ctx.driver;
        if (dowater) {
            // Special case for drowned city
            driver.setBlockRange(x, height1, z, info.waterLevel, feature.liquid, test);
            driver.setBlockRangeToAir(x, info.waterLevel + 1, z, height2, test);
        } else {
            driver.setBlockRangeToAir(x, height1, z, height2, test);
        }
    }

    /**
     * These three are asked about chunks other than the one being generated - a chunk interpolates
     * its terrain fix against its eight neighbours - so they take the seed and the coordinate they
     * are being asked about rather than a {@link ChunkGenContext}.
     * <p>
     * {@code getRandomizedOffset} takes its purpose from the caller because it is asked twice at
     * one coordinate, for the lower and the upper bound of the same mesh. One purpose would tie
     * them together, and neither may be shared with the street-type pick, which is drawn at the
     * same address.
     */
    public static int getRandomizedOffset(long seed, int chunkX, int chunkZ, int min, int max, Rng.Purpose purpose) {
        return Rng.at(seed, chunkX, chunkZ, purpose).nextInt(max - min + 1) + min;
    }

    public static int getHeightOffsetL1(long seed, int chunkX, int chunkZ) {
        return Rng.at(seed, chunkX, chunkZ, Rng.Purpose.TERRAIN_L1).nextInt(5);
    }

    public static int getHeightOffsetL2(long seed, int chunkX, int chunkZ) {
        return Rng.at(seed, chunkX, chunkZ, Rng.Purpose.TERRAIN_L2).nextInt(5);
    }

    /*
     * This routine is used on a normal (non-city) chunk to make sure the landscape nicely fits
     * with any possible adjacent city chunks. It works by creating two meshes that are overlayed
     * on the terrain. Meshes are defined at chunk corners. Every chunk corner has a corresponding
     * height on the two meshes.
     *
     * The upper mesh indicates the maximum height the terrain is allowed to go. If a certain chunk
     * corner is not adjacent to any city chunk or is not adjacent to any normal chunk then there is
     * no maximum height and in that case we set it to 100000. Otherwise (if the chunk corner
     * is adjacent to mixed chunks) the maximum allowed height of the terrain is equal to the minimum
     * height of all the city chunks (with minimum height we mean the lower city level or the height
     * of the first floor).
     *
     * The lower mesh indicates the minimum height the terrain is allowed to go. Same as with the upper
     * mesh there is no minimum in case the chunk corner is not a mixed type corner. Otherwise the
     * minimum height is going to be some (configurable) offset below the minimum lower city level.
     *
     * Every normal chunk is made to fit between the lower and the upper mesh by moving down
     * or up the top layer (6 thick) of the terrain. In a chunk these heights are interpolated
     * (bilinear interpolation).
     */
    public static void correctTerrainShape(ChunkGenContext ctx, CityGenerator feature, ChunkCoord coord, ChunkHeightmap heightmap) {
        ChunkPlan info = ChunkPlan.getChunkPlan(coord, feature.provider);
        ChunkPlan.MinMax mm00 = info.getDesiredMaxHeightL2();
        ChunkPlan.MinMax mm10 = info.getXmax().getDesiredMaxHeightL2();
        ChunkPlan.MinMax mm01 = info.getZmax().getDesiredMaxHeightL2();
        ChunkPlan.MinMax mm11 = info.getXmax().getZmax().getDesiredMaxHeightL2();

        int min = feature.provider.shape().minY();
        int max = feature.provider.shape().maxBuildHeight();
        int heightmapH = Short.MIN_VALUE;

        float min00 = mm00.min;
        float min10 = mm10.min;
        float min01 = mm01.min;
        float min11 = mm11.min;
        float max00 = mm00.max;
        float max10 = mm10.max;
        float max01 = mm01.max;
        float max11 = mm11.max;
        if (max00 < max || max10 < max || max01 < max || max11 < max ||
                min00 < max || min10 < max || min01 < max || min11 < max) {
            // We need to fit the terrain between the upper and lower mesh here
            int maxHeightP = heightmap.getHeight() + 90;
            int minHeightP = heightmap.getHeight() - 90;
            if (max00 >= max) {
                max00 = maxHeightP;
            }
            if (max10 >= max) {
                max10 = maxHeightP;
            }
            if (max01 >= max) {
                max01 = maxHeightP;
            }
            if (max11 >= max) {
                max11 = maxHeightP;
            }
            if (min00 >= max) {
                min00 = minHeightP;
            }
            if (min10 >= max) {
                min10 = minHeightP;
            }
            if (min01 >= max) {
                min01 = minHeightP;
            }
            if (min11 >= max) {
                min11 = minHeightP;
            }

            for (int x = 0; x < 16; x++) {
                // Bilinear interpolation
                float factor = (15.0f - x) / 15.0f;
                float maxh0 = max11 + (max01 - max11) * factor;
                float maxh1 = max10 + (max00 - max10) * factor;
                float minh0 = min11 + (min01 - min11) * factor;
                float minh1 = min10 + (min00 - min10) * factor;
                for (int z = 0; z < 16; z++) {
                    float maxheight = maxh0 + (maxh1 - maxh0) * (15.0f - z) / 15.0f;
                    if (maxheight > max) {
                        maxheight = max;
                    }
                    int maxTouchedY = moveDown(ctx, feature, x, z, (int) maxheight, max);

                    if (maxTouchedY == Short.MIN_VALUE) {
                        float minheight = minh0 + (minh1 - minh0) * (15.0f - z) / 15.0f;
                        if (minheight < min) {
                            minheight = min;
                        }
                        maxTouchedY = moveUp(ctx, feature, x, z, (int) minheight, info.waterLevel > info.groundLevel);
                    }
                    if (maxTouchedY != Short.MIN_VALUE && x == 8 && z == 8) {
                        // Only adjust heightmap for center value
                        heightmapH = Math.max(heightmapH, maxTouchedY);
                    }
                }
            }
            if (heightmapH != Short.MIN_VALUE) {
                heightmap.setHeight(heightmapH);
            }
        }
    }

    // Return true if state is feature.air or feature.liquid
    private static boolean isFoliageOrEmpty(TagSnapshot tags, BlockState state) {
        if (CityGenerator.isEmpty(state)) {
            return true;
        }
        return tags.isFoliage(state);
    }

    /**
     * Fill feature.profile.getBaseBlock() blocks downwards from 'y' until solid ground (or the bedrock layer) is reached,
     * so that whatever rests on top of it is not left hanging in the feature.air. This is the same fill
     * fillToBedrockStreetBlock() applies under streets.
     */
    public static void fillSupportBelow(ChunkGenContext ctx, CityGenerator feature, int x, int z, int y) {
        ChunkDriver driver = ctx.driver;
        int lowest = feature.provider.shape().minY() + feature.profile.bedrockLayer();
        driver.current(x, y, z);
        while (driver.getY() > lowest && CityGenerator.isEmpty(driver.getBlock())) {
            driver.block(feature.profile.getBaseBlock());
            driver.decY();
        }
    }

    // Return the new max height of the chunk in this column. Or Short.MIN_VALUE if nothing was done
    public static int moveUp(ChunkGenContext ctx, CityGenerator feature, int x, int z, int height, boolean dowater) {
        ChunkDriver driver = ctx.driver;
        int maxYTouched = Short.MIN_VALUE;       // Max Y that we touched
        // Find the first non-empty block starting at the given height
        driver.current(x, height, z);
        int minHeight = feature.provider.shape().minY();
        // We assume here we are not in a void chunk
        while (isFoliageOrEmpty(ctx.tags, driver.getBlock()) && driver.getY() > minHeight) {
            driver.decY();
        }

        if (driver.getY() >= height) {
            return maxYTouched; // Nothing to do
        }

        int idx = driver.getY();    // Points to non-empty block below the empty block
        driver.current(x, height, z);
        while (idx > 0) {
            BlockState blockToMove = driver.getBlock(x, idx, z);
            if (blockToMove.isAir() || blockToMove.getBlock() == Blocks.BEDROCK) {
                break;
            }
            if (maxYTouched == Short.MIN_VALUE) {
                maxYTouched = idx;
            }
            driver.block(blockToMove);
            driver.decY();
            idx--;
        }
        return maxYTouched;
    }

    // Return the new max height of the chunk in this column. Or Short.MIN_VALUE if nothing was done
    public static int moveDown(ChunkGenContext ctx, CityGenerator feature, int x, int z, int height, int maxBuildLimit) {
        ChunkDriver driver = ctx.driver;
        BlockState[] buffer = ctx.moveDownBuffer;
        int maxYTouched = Short.MIN_VALUE;       // Max Y that we touched
        int y = maxBuildLimit-1;
        driver.current(x, y, z);
        // Step over whole sections of nothing but air before reading a single block.
        //
        // The scan below starts at the build limit and walks down to the first non-empty block, so
        // on ordinary terrain it reads a couple of hundred blocks of sky per column, 256 columns a
        // chunk. Each of those reads misses the buffer, goes to the world, and gets remembered -
        // and remembering the first block of a section allocates a BlockState[4096] for it. So the
        // sky above a city was costing tens of thousands of world reads and several hundred KiB per
        // chunk, to establish that it is air.
        //
        // Exactly equivalent, not approximately: every block in a skipped section is air, air is
        // empty, so the loop below would have stepped through all sixteen and stopped nowhere. A
        // section is only skipped when it lies wholly above `height`, so the loop still stops at
        // `height` on a column that is empty all the way down - which is the "nothing to do" case
        // immediately after.
        while (driver.getY() > height) {
            int bottom = driver.sectionBottomAt(driver.getY());
            if (bottom <= height || !driver.sectionIsAllAir(driver.getY())) {
                break;
            }
            driver.current(x, bottom - 1, z);
        }
        // We assume here we are not in a void chunk
        while (CityGenerator.isEmpty(driver.getBlock()) && driver.getY() > height) {
            driver.decY();
        }

        if (driver.getY() <= height) {
            return maxYTouched; // Nothing to do
        }

        // We arrived at our first non-feature.air block
        int bufferIdx = 0;
        while (driver.getY() >= height) {
            if (bufferIdx < buffer.length) {
                buffer[bufferIdx++] = driver.getBlock();
            }
            driver.block(feature.air);
            driver.decY();
        }

        maxYTouched = driver.getY();
        int idx = 0;
        while (idx < bufferIdx && driver.getY() > 0) {
            driver.block(buffer[idx++]);
            driver.decY();
        }

        // The buffer only carried the top few blocks of whatever used to be here, and nothing
        // above this point looked at what is underneath. Whenever the column we just moved that
        // surface down onto is empty - an overhang or cliff shoulder over a carved cavern, or a
        // sampled heightmap that disagrees with the local terrain - the relocated surface is
        // left hanging in the feature.air, and so is everything the city then builds on top of it.
        fillSupportBelow(ctx, feature, x, z, driver.getY());

//
//        if (dowater) {
//            // Special case for drowned city
//            driver.setBlockRange(x, height1, z, info.waterLevel, feature.liquid);
//            driver.setBlockRange(x, info.waterLevel+1, z, height2, feature.air);
//        } else {
//            driver.setBlockRange(x, height1, z, height2, feature.air);
//        }
        return maxYTouched;
    }


    public static boolean isWaterBiome(PlanningContext provider, ChunkCoord coord) {
        BiomeInfo biomeInfo = BiomeInfo.getBiomeInfo(provider, coord);
        Holder<Biome> mainBiome = biomeInfo.getMainBiome();
        return isWaterBiome(mainBiome);
    }

    public static boolean isWaterBiome(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN) || biome.is(BiomeTags.IS_BEACH) || biome.is(BiomeTags.IS_RIVER);
    }

    /**
     * This function returns the height at a given point in this chunk
     * If the point is at a border and the adjacent chunk at that point happens to be lower
     * then this will return the minimum height
     */
    public static int getMinHeightAt(CityGenerator feature, ChunkPlan info, int x, int z, ChunkHeightmap heightmap) {
        int height = heightmap.getHeight();
        int adjacent;
        if (x == 0) {
            if (z == 0) {
                adjacent = feature.provider.heightmap(info.coord.northWest()).getHeight();
            } else if (z == 15) {
                adjacent = feature.provider.heightmap(info.coord.southWest()).getHeight();
            } else {
                adjacent = feature.provider.heightmap(info.coord.west()).getHeight();
            }
        } else if (x == 15) {
            if (z == 0) {
                adjacent = feature.provider.heightmap(info.coord.northEast()).getHeight();
            } else if (z == 15) {
                adjacent = feature.provider.heightmap(info.coord.southEast()).getHeight();
            } else {
                adjacent = feature.provider.heightmap(info.coord.east()).getHeight();
            }
        } else if (z == 0) {
            adjacent = feature.provider.heightmap(info.coord.north()).getHeight();
        } else if (z == 15) {
            adjacent = feature.provider.heightmap(info.coord.south()).getHeight();
        } else {
            return height;
        }
        return Math.min(height, adjacent);
    }
}
