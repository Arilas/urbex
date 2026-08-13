package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.varia.NoiseGeneratorPerlin;
import dev.krona.urbex.worldgen.ChunkDriver;
import dev.krona.urbex.worldgen.ChunkGenContext;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What is left lying about: rubble, ruined upper floors, undergrowth and park planting.
 *
 * <p>Four passes and the three noise fields they read. The noise fields moved with them - each is a
 * pure function of the world seed and describes the whole dimension, so they were built once in
 * {@link CityGenerator}'s constructor and used by nothing else (issue #11).</p>
 *
 * <p>The seeds are positional and must stay so: rubble is offset 0, leaves 1, ruins 2. A fourth,
 * offset 3, feeds the floating profile's underside and stays with the terrain code.</p>
 */
public final class Decorations {

    private final NoiseGeneratorPerlin rubbleNoise;
    private final NoiseGeneratorPerlin leavesNoise;
    private final NoiseGeneratorPerlin ruinNoise;

    public Decorations(long seed) {
        this.rubbleNoise = new NoiseGeneratorPerlin(Rng.at(seed, 0, 0, Rng.Purpose.NOISE), 4);
        this.leavesNoise = new NoiseGeneratorPerlin(Rng.at(seed, 1, 0, Rng.Purpose.NOISE), 4);
        this.ruinNoise = new NoiseGeneratorPerlin(Rng.at(seed, 2, 0, Rng.Purpose.NOISE), 4);
    }

    public void rubble(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info) {
        ChunkDriver driver = ctx.driver;
        int chunkX = info.coord.chunkX();
        int chunkZ = info.coord.chunkZ();
        double[] rubbleBuffer = ctx.buffers.rubble = rubbleNoise.getRegion(ctx.buffers.rubble, (chunkX << 4), (chunkZ << 4), 16, 16, 1.0 / 16.0, 1.0 / 16.0, 1.0D);
        double[] leavesBuffer = ctx.buffers.leaves = leavesNoise.getRegion(ctx.buffers.leaves, (chunkX << 6), (chunkZ << 6), 16, 16, 1.0 / 64.0, 1.0 / 64.0, 4.0D);

        Set<BlockState> possibleRandomDirts = feature.groundCover.possibleRubble(info, info.getCompiledPalette());
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                double vr = info.profile.rubbleDirtScale() < 0.01f ? 0 : rubbleBuffer[x + z * 16] / info.profile.rubbleDirtScale();
                double vl = info.profile.rubbleLeaveScale() < 0.01f ? 0 : leavesBuffer[x + z * 16] / info.profile.rubbleLeaveScale();
                if (vr > .5 || vl > .5) {
                    int height = interpolatedHeight(info, x, z);
                    driver.current(x, height, z);
                    BlockState c = driver.getBlockDown();
                    if (c != feature.air && c != feature.liquid) {
                        for (int i = 0; i < vr; i++) {
                            if (CityGenerator.isEmpty(driver.getBlock())) {
                                driver.add(feature.groundCover.rubbleAt(ctx, info, info.getCompiledPalette()));
                            } else {
                                driver.incY();
                            }
                        }
                    }
                    //first round may not have generated this - stops crash on create world
                    BlockState leafBaseState = driver.getBlockDown();
                    if (leafBaseState == feature.profile.getBaseBlock() || possibleRandomDirts.contains(leafBaseState)) {
                        for (int i = 0; i < vl; i++) {
                            if (CityGenerator.isEmpty(driver.getBlock())) {
                                driver.add(feature.groundCover.leafAt(ctx, info, info.getCompiledPalette()));
                            } else {
                                driver.incY();
                            }
                        }
                    }
                }
            }
        }
    }

    private int interpolatedHeight(ChunkPlan info, int x, int z) {
        if (x < 8 && z < 8) {
            // First quadrant
            float h00 = info.getXmin().getZmin().getCityGroundLevelOutsideLower();
            float h10 = info.getZmin().getCityGroundLevelOutsideLower();
            float h01 = info.getXmin().getCityGroundLevelOutsideLower();
            float h11 = info.getCityGroundLevelOutsideLower();
            return bipolate(h00, h10, h01, h11, x + 8, z + 8);
        } else if (x >= 8 && z < 8) {
            // Second quadrant
            float h00 = info.getZmin().getCityGroundLevelOutsideLower();
            float h10 = info.getXmax().getZmin().getCityGroundLevelOutsideLower();
            float h01 = info.getCityGroundLevelOutsideLower();
            float h11 = info.getXmax().getCityGroundLevelOutsideLower();
            return bipolate(h00, h10, h01, h11, x - 8, z + 8);
        } else if (x < 8 && z >= 8) {
            // Third quadrant
            float h00 = info.getXmin().getCityGroundLevelOutsideLower();
            float h10 = info.getCityGroundLevelOutsideLower();
            float h01 = info.getXmin().getZmax().getCityGroundLevelOutsideLower();
            float h11 = info.getZmax().getCityGroundLevelOutsideLower();
            return bipolate(h00, h10, h01, h11, x + 8, z - 8);
        } else {
            // Fourth quadrant
            float h00 = info.getCityGroundLevelOutsideLower();
            float h10 = info.getXmax().getCityGroundLevelOutsideLower();
            float h01 = info.getZmax().getCityGroundLevelOutsideLower();
            float h11 = info.getXmax().getZmax().getCityGroundLevelOutsideLower();
            return bipolate(h00, h10, h01, h11, x - 8, z - 8);
        }
    }

    private int bipolate(float h00, float h10, float h01, float h11, int dx, int dz) {
        float factor = (15.0f - dx) / 15.0f;
        float h0 = h00 + (h10 - h00) * factor;
        float h1 = h01 + (h11 - h01) * factor;
        float h = h0 + (h1 - h0) * (15.0f - dz) / 15.0f;
        return (int) h;
    }


    /**
     * A roll in {@code [0, 1)} for the block the driver is currently on. Addressed by that
     * position, so a loop that walks two blocks further than it did last time changes those two
     * blocks and nothing else.
     */
    public void ruins(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info) {
        if (info.ruinHeight < 0) {
            return;
        }

        ChunkDriver driver = ctx.driver;
        int chunkX = info.coord.chunkX();
        int chunkZ = info.coord.chunkZ();
        double d0 = 0.03125D;
        double[] ruinBuffer = ctx.buffers.ruin = ruinNoise.getRegion(ctx.buffers.ruin, (chunkX << 4), (chunkZ << 4), 16, 16, d0 * 2.0D, d0 * 2.0D, 1.0D);
        double[] leavesBuffer = ctx.buffers.leaves;
        boolean doLeaves = info.profile.rubbleLayer();
        if (doLeaves) {
            leavesBuffer = ctx.buffers.leaves = leavesNoise.getRegion(ctx.buffers.leaves, (chunkX << 6), (chunkZ << 6), 16, 16, 1.0 / 64.0, 1.0 / 64.0, 4.0D);
        }

        int baseheight = (int) (info.getCityGroundLevel() + 1 + (info.ruinHeight * info.getNumFloors() * CityGenerator.FLOORHEIGHT));

        CompiledPalette palette = info.getCompiledPalette();
        BlockState ironbarsState = Blocks.IRON_BARS.defaultBlockState();
        Character infobarsChar = info.getCityStyle().getIronbarsBlock();
        Supplier<BlockState> ironbars = infobarsChar == null ? () -> ironbarsState : () -> ctx.paletteHere(palette, infobarsChar);
        Set<BlockState> infoBarSet = infobarsChar == null ? Collections.singleton(ironbarsState) : palette.getAll(infobarsChar);
        Predicate<BlockState> checkIronbars = infobarsChar == null ? s -> s == ironbarsState : infoBarSet::contains;
        Character rubbleBlock = info.getBuilding().getRubbleBlock();

        int maxBuildHeight = info.provider.shape().maxBuildHeight();
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                double v = ruinBuffer[x + z * 16];
//                double v = ruinNoise.getValue(x, z) / 16.0;
                int height = baseheight + (int) v;
                driver.current(x, height, z);
                height = info.getMaxHeight() + 10 - height;
                if (height > maxBuildHeight - 2) {
                    height = maxBuildHeight - 2;
                }
                int vl = 0;
                if (doLeaves) {
                    vl = (int) (info.profile.rubbleLeaveScale() < 0.01f ? 0 : leavesBuffer[x + z * 16] / info.profile.rubbleLeaveScale());
//                    vl = (int) (info.profile.rubbleLeaveScale() < 0.01f ? 0 : leavesNoise.getValue(x / 64.0, z / 64.0) / 4.0 * info.profile.rubbleLeaveScale());
                }
                boolean doRubble = palette.isDefined(rubbleBlock);
                while (height > 0) {
                    BlockState damage = palette.canBeDamagedToIronBars(driver.getBlock());
                    BlockState c = driver.getBlockDown();

                    if (doRubble && !checkIronbars.test(c) && c != feature.air && c != feature.liquid && rollHere(ctx, driver, Rng.Purpose.RUINS) < .2f) {      // @todo hardcoded random
                        doRubble = false;
                        driver.add(ctx.paletteHere(palette, rubbleBlock));
                    } else if ((damage != null || checkIronbars.test(c)) && c != feature.air && c != feature.liquid && rollHere(ctx, driver, Rng.Purpose.RUINS_BARS) < .2f) {    // @todo hardcoded random
                        driver.add(ironbars.get());
                    } else {
                        if (vl > 0) {
                            c = driver.getBlockDown();
                            while (CityGenerator.isEmpty(c)) {
                                driver.decY();
                                height++;   // Make sure we keep on filling with air a bit longer because we are lowering here
                                c = driver.getBlockDown();
                            }
                            driver.add(feature.groundCover.leafAt(ctx, info, palette));
                            vl--;
                        } else {
                            driver.add(feature.air);
                        }
                    }
                    height--;
                }
            }
        }
    }

    public void vegetation(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, int height) {
        ChunkDriver driver = ctx.driver;

        if (info.getXmin().hasBuilding) {
            for (int x = 0; x < info.profile.randomLeafBlockThickness(); x++) {
                for (int z = 0; z < 16; z++) {
                    driver.current(x, height, z);
                    // @todo can be more optimal? Only go down to non air in case random succeeds?
                    // It's ok to only go down to 0 as we are not expecting to go lower then that
                    while (driver.getBlockDown() == feature.air && driver.getY() > 0) {
                        driver.decY();
                    }
                    float v = Math.min(.8f, info.profile.randomLeafBlockChance() * (info.profile.randomLeafBlockThickness() + 1 - x));
                    int cnt = 0;
                    while (rollHere(ctx, driver, Rng.Purpose.VEGETATION) < v && cnt < 30) {
                        driver.add(feature.groundCover.leafAt(ctx, info, info.getCompiledPalette()));
                        cnt++;
                    }
                }
            }
        }
        if (info.getXmax().hasBuilding) {
            for (int x = 15 - info.profile.randomLeafBlockThickness(); x < 15; x++) {
                for (int z = 0; z < 16; z++) {
                    driver.current(x, height, z);
                    // @todo can be more optimal? Only go down to non air in case random succeeds?
                    // It's ok to only go down to 0 as we are not expecting to go lower then that
                    while (driver.getBlockDown() == feature.air && driver.getY() > 0) {
                        driver.decY();
                    }
                    float v = Math.min(.8f, info.profile.randomLeafBlockChance() * (x - 14 + info.profile.randomLeafBlockThickness()));
                    int cnt = 0;
                    while (rollHere(ctx, driver, Rng.Purpose.VEGETATION_XMAX) < v && cnt < 30) {
                        driver.add(feature.groundCover.leafAt(ctx, info, info.getCompiledPalette()));
                        cnt++;
                    }
                }
            }
        }
        if (info.getZmin().hasBuilding) {
            for (int z = 0; z < info.profile.randomLeafBlockThickness(); z++) {
                for (int x = 0; x < 16; x++) {
                    driver.current(x, height, z);
                    // @todo can be more optimal? Only go down to non air in case random succeeds?
                    // It's ok to only go down to 0 as we are not expecting to go lower then that
                    while (driver.getBlockDown() == feature.air && driver.getY() > 0) {
                        driver.decY();
                    }
                    float v = Math.min(.8f, info.profile.randomLeafBlockChance() * (info.profile.randomLeafBlockThickness() + 1 - z));
                    int cnt = 0;
                    while (rollHere(ctx, driver, Rng.Purpose.VEGETATION_ZMIN) < v && cnt < 30) {
                        driver.add(feature.groundCover.leafAt(ctx, info, info.getCompiledPalette()));
                        cnt++;
                    }
                }
            }
        }
        if (info.getZmax().hasBuilding) {
            for (int z = 15 - info.profile.randomLeafBlockThickness(); z < 15; z++) {
                for (int x = 0; x < 16; x++) {
                    driver.current(x, height, z);
                    // @todo can be more optimal? Only go down to non air in case random succeeds?
                    // It's ok to only go down to 0 as we are not expecting to go lower then that
                    while (driver.getBlockDown() == feature.air && driver.getY() > 0) {
                        driver.decY();
                    }
                    float v = info.profile.randomLeafBlockChance() * (z - 14 + info.profile.randomLeafBlockThickness());
                    int cnt = 0;
                    while (rollHere(ctx, driver, Rng.Purpose.VEGETATION_ZMAX) < v && cnt < 30) {
                        driver.add(feature.groundCover.leafAt(ctx, info, info.getCompiledPalette()));
                        cnt++;
                    }
                }
            }
        }
    }

    public void parkSection(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, int height, boolean elevated) {
        ChunkDriver driver = ctx.driver;
        char street = ctx.street;
        BlockState b;
        boolean el00 = info.getXmin().getZmin().isElevatedParkSection();
        boolean el10 = info.getZmin().isElevatedParkSection();
        boolean el20 = info.getXmax().getZmin().isElevatedParkSection();
        boolean el01 = info.getXmin().isElevatedParkSection();
        boolean el21 = info.getXmax().isElevatedParkSection();
        boolean el02 = info.getXmin().getZmax().isElevatedParkSection();
        boolean el12 = info.getZmax().isElevatedParkSection();
        boolean el22 = info.getXmax().getZmax().isElevatedParkSection();
        CompiledPalette compiledPalette = info.getCompiledPalette();

        Character grassChar = info.getCityStyle().getGrassBlock();
        BlockState grassBlock = Blocks.GRASS_BLOCK.defaultBlockState();
        boolean parkBorder = info.getCityStyle().getParkBorder() != null ? info.getCityStyle().getParkBorder() : info.profile.parkBorder();
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                // Resolved per column, at the block it will be written to.
                BlockState grass = (grassChar == null)
                        ? grassBlock
                        : ctx.paletteAt(compiledPalette, grassChar, x, height, z);
                if (x == 0 || x == 15 || z == 0 || z == 15) {
                    b = null;
                    if (elevated) {
                        if (x == 0 && z == 0) {
                            if (el01 && el00 && el10) {
                                b = grass;
                            }
                        } else if (x == 15 && z == 0) {
                            if (el21 && el20 && el10) {
                                b = grass;
                            }
                        } else if (x == 0 && z == 15) {
                            if (el01 && el02 && el12) {
                                b = grass;
                            }
                        } else if (x == 15 && z == 15) {
                            if (el12 && el22 && el21) {
                                b = grass;
                            }
                        } else if (x == 0) {
                            if (el01) {
                                b = grass;
                            }
                        } else if (x == 15) {
                            if (el21) {
                                b = grass;
                            }
                        } else if (z == 0) {
                            if (el10) {
                                b = grass;
                            }
                        } else if (z == 15) {
                            if (el12) {
                                b = grass;
                            }
                        }
                        if (b == null) {
                            b = parkBorder ? ctx.paletteAt(compiledPalette, street, x, height, z) : grass;
                        }
                    } else {
                        b = parkBorder ? ctx.paletteAt(compiledPalette, street, x, height, z) : grass;
                    }
                } else {
                    b = grass;
                }
                driver.current(x, height, z).block(b);
            }
        }
    }

    /**
     * The part family a chunk's road class draws from. A style that defines no tertiary family falls
     * back to its ordinary streets, which {@link dev.krona.urbex.worldgen.lost.cityassets.CityStyle}
     * handles; a chunk with no planned road (an open lot rendered as paving) also uses the ordinary
     * family, because that is the narrowest surface available.
     */

    private static float rollHere(ChunkGenContext ctx, ChunkDriver driver, Rng.Purpose purpose) {
        return Rng.floatAtPos(ctx.seed, driver.getX(), driver.getY(), driver.getZ(), purpose);
    }
}
