package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.config.LostCityProfile;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.ChunkDriver;
import dev.krona.urbex.worldgen.ChunkFixer;
import dev.krona.urbex.worldgen.ChunkGenContext;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.LostCityTerrainFeature;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
import dev.krona.urbex.worldgen.lost.CitySphere;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

public class Spheres {

    public static void generateSpheres(LostCityTerrainFeature feature, WorldGenRegion region, ChunkAccess chunk) {
        IDimensionInfo provider = feature.provider;
        LostCityProfile profile = feature.profile;
        // Do the city spheres
        if (profile.isSpace() || profile.isSpheres()) {
            int chunkX = chunk.getPos().x();
            int chunkZ = chunk.getPos().z();
            ChunkCoord coord = new ChunkCoord(provider.getType(), chunkX, chunkZ);
            BuildingInfo info = BuildingInfo.getBuildingInfo(coord, provider);
            ChunkGenContext ctx = new ChunkGenContext(region, chunk, coord, provider, profile, info);

            CitySphere sphere = CitySphere.getCitySphere(coord, provider);
            CitySphere.initSphere(sphere, provider);   // Make sure city sphere information is complete
            if (sphere.isEnabled()) {
                float radius = sphere.getRadius();
                BlockPos cc = sphere.getCenterPos();
                int cx = cc.getX() - (chunkX << 4);
                int cz = cc.getZ() - (chunkZ << 4);
                fillSphere(ctx, feature, cx, profile.GROUNDLEVEL, cz, (int) radius, sphere.getGlassBlock(), sphere.getSideBlock());
            }

            if (profile.isSpace()) {
                Monorails.generateMonorails(ctx, feature, info);
            }

            feature.placeOptionalLights(ctx, info);
            ctx.driver.actuallyGenerate(chunk);
            ChunkFixer.fix(provider, coord, region);
        }
    }

    private static void fillSphere(ChunkGenContext ctx, LostCityTerrainFeature feature, int centerx, int centery, int centerz, int radius,
                                   BlockState glass, BlockState sideBlock) {
        IDimensionInfo provider = feature.provider;
        ChunkDriver driver = ctx.driver;
        LostCityProfile profile = feature.profile;
        BlockState air = Blocks.AIR.defaultBlockState();
        double sqradius = radius * radius;
        double sqradiusOffset = (radius - 2) * (radius - 2);
        double sqradiusOuter = (radius + 2) * (radius + 2);

        int minY = Math.max(provider.getWorld().getMinY(), centery - radius - 1);
        int maxY = Math.min(provider.getWorld().getMaxY() + 1, centery + radius + 1);
        int seaLevel = Tools.getSeaLevel(provider.getWorld());

        for (int x = 0; x < 16; x++) {
            double dxdx = (x - centerx) * (x - centerx);
            for (int z = 0; z < 16; z++) {
                double dzdz = (z - centerz) * (z - centerz);
                int bottom = Integer.MAX_VALUE;
                if (dxdx + dzdz <= sqradius) {
                    driver.current(x, minY, z);
                    for (int y = minY; y <= centery; y++) {
                        double dydy = (y - centery) * (y - centery);
                        double sqdist = dxdx + dydy + dzdz;
                        if (sqdist <= sqradius && sqdist >= sqradiusOffset) {
                            if (y < bottom) {
                                bottom = y - 1;
                            }
                            driver.block(sideBlock);
                        }
                        driver.incY();
                    }
                    for (int y = centery + 1; y < maxY; y++) {
                        double dydy = (y - centery) * (y - centery);
                        double sqdist = dxdx + dydy + dzdz;
                        if (sqdist <= sqradius) {
                            if (sqdist >= sqradiusOffset) {
                                driver.block(glass);
                            }
                        } else {
                            // Optionally clear above the sphere
                            int yy = y;
                            if (profile.CITYSPHERE_CLEARABOVE > 0) {
                                int mY = Math.min(provider.getWorld().getMaxY() + 1, y + profile.CITYSPHERE_CLEARABOVE);
                                while (yy <= mY) {
                                    driver.block(air);
                                    driver.incY();
                                    yy++;
                                }
                            }
                            if (profile.CITYSPHERE_CLEARABOVE_UNTIL_AIR) {
                                // Clear until we hit air
                                while (driver.getBlock() != air) {
                                    driver.block(air);
                                    driver.incY();
                                    yy++;
                                }
                            }
                            // Optionall clear below the sphere
                            yy = bottom;
                            if (profile.CITYSPHERE_CLEARBELOW > 0 && bottom != Integer.MAX_VALUE) {
                                driver.current(x, yy, z);
                                int mY = Math.max(provider.getWorld().getMinY(), bottom - profile.CITYSPHERE_CLEARBELOW);
                                while (yy >= mY) {
                                    driver.block(air);
                                    driver.decY();
                                    yy--;
                                }
                            }
                            if (profile.CITYSPHERE_CLEARBELOW_UNTIL_AIR && bottom != Integer.MAX_VALUE) {
                                // Clear until we hit air or go below build limit
                                driver.current(x, yy, z);
                                while (driver.getBlock() != (yy <= seaLevel ? feature.liquid : air) && yy > provider.getWorld().getMinY()) {
                                    driver.block(air);
                                    driver.decY();
                                    yy--;
                                }
                            }
                            break;
                        }
                        driver.incY();
                    }
                } else if (dxdx + dzdz <= sqradiusOuter) {
                    // If we are in a space profile then we clear the sphere area too
                    if (profile.isFloating() || profile.isSpace()) {
                        driver.current(x, minY, z);
                        for (int y = minY; y < maxY; y++) {
                            driver.block(air);
                            driver.incY();
                        }
                    }
                }
            }
        }
    }
}
