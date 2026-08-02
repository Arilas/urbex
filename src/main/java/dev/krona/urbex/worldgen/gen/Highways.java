package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.worldgen.ChunkDriver;
import dev.krona.urbex.worldgen.ChunkGenContext;
import dev.krona.urbex.worldgen.LostCityTerrainFeature;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
import dev.krona.urbex.worldgen.lost.Highway;
import dev.krona.urbex.worldgen.lost.Transform;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;
import dev.krona.urbex.worldgen.lost.regassets.data.HighwayParts;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

public class Highways {
    public static void generateHighways(ChunkGenContext ctx, LostCityTerrainFeature feature, BuildingInfo info) {
        int levelX = Highway.getXHighwayLevel(info.coord, info.provider, info.profile);
        int levelZ = Highway.getZHighwayLevel(info.coord, info.provider, info.profile);
        if (levelX == levelZ && levelX >= 0) {
            // Crossing
            generateHighwayPart(ctx, feature, info, levelX, Transform.ROTATE_NONE, info.getXmax(), info.getZmax(), true);
        } else if (levelX >= 0 && levelZ >= 0) {
            // There are two highways on different level. Make sure the lowest one is done first because it
            // will clear out what is above it
            if (levelX == 0) {
                generateHighwayPart(ctx, feature, info, levelX, Transform.ROTATE_NONE, info.getZmin(), info.getZmax(), false);
                generateHighwayPart(ctx, feature, info, levelZ, Transform.ROTATE_90, info.getXmax(), info.getXmax(), false);
            } else {
                generateHighwayPart(ctx, feature, info, levelZ, Transform.ROTATE_90, info.getXmax(), info.getXmax(), false);
                generateHighwayPart(ctx, feature, info, levelX, Transform.ROTATE_NONE, info.getZmin(), info.getZmax(), false);
            }
        } else {
            if (levelX >= 0) {
                generateHighwayPart(ctx, feature, info, levelX, Transform.ROTATE_NONE, info.getZmin(), info.getZmax(), false);
            } else if (levelZ >= 0) {
                generateHighwayPart(ctx, feature, info, levelZ, Transform.ROTATE_90, info.getXmax(), info.getXmax(), false);
            }
        }
    }

    public static boolean isClearableAboveHighway(BlockState st) {
        return !st.is(BlockTags.LEAVES) && !st.is(BlockTags.LOGS);
    }

    private static void generateHighwayPart(ChunkGenContext ctx, LostCityTerrainFeature feature, BuildingInfo info, int level, Transform transform, BuildingInfo adjacent1, BuildingInfo adjacent2, boolean bidirectional) {
        ChunkDriver driver = ctx.driver;
        int highwayGroundLevel = info.groundLevel + level * LostCityTerrainFeature.FLOORHEIGHT;
        HighwayParts highwayParts = info.provider.getWorldStyle().getPartSelector().highwayParts();

        BuildingPart part;
        if (info.isTunnel(level)) {
            // We know we need a tunnel
            part = AssetRegistries.PARTS.getOrThrow(info.provider.getWorld(), feature.getRandomPart(ctx, highwayParts.tunnel(bidirectional)));
            feature.generatePart(ctx, info, part, transform, 0, highwayGroundLevel, 0, LostCityTerrainFeature.HardAirSetting.WATERLEVEL);
        } else {
            if (info.isCity && level <= adjacent1.cityLevel && level <= adjacent2.cityLevel && adjacent1.isCity && adjacent2.isCity) {
                // Simple highway in the city
                part = AssetRegistries.PARTS.getOrThrow(info.provider.getWorld(), feature.getRandomPart(ctx, highwayParts.open(bidirectional)));
                int height = feature.generatePart(ctx, info, part, transform, 0, highwayGroundLevel, 0, LostCityTerrainFeature.HardAirSetting.WATERLEVEL);
                // Clear a bit more above the highway
                if (!info.profile.isCavern()) {
                    int clearheight = 15;
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            feature.clearRange(ctx, info, x, z, height, height + clearheight, info.waterLevel > info.groundLevel,
                                    Highways::isClearableAboveHighway);
                        }
                    }
                }
            } else {
                part = AssetRegistries.PARTS.getOrThrow(info.provider.getWorld(), feature.getRandomPart(ctx, highwayParts.bridge(bidirectional)));
                int height = feature.generatePart(ctx, info, part, transform, 0, highwayGroundLevel, 0, LostCityTerrainFeature.HardAirSetting.WATERLEVEL);
                // Clear a bit more above the highway
                if (!info.profile.isCavern()) {
                    int clearheight = 15;
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            feature.clearRange(ctx, info, x, z, height, height + clearheight, info.waterLevel > info.groundLevel,
                                    Highways::isClearableAboveHighway);
                        }
                    }
                }
            }
        }

        Character support = part.getMetaChar(BuildingPart.META_SUPPORT);
        if (info.profile.HIGHWAY_SUPPORTS && support != null) {
            BlockState sup = ctx.paletteAt(info.getCompiledPalette(), support, 0, highwayGroundLevel - 1, 0);
            if (sup == null) {
                throw new RuntimeException("Cannot find support block '" + support + "' for highway part '" + part.getName() + "'!");
            }
            int x1 = transform.rotateX(0, 15);
            int z1 = transform.rotateZ(0, 15);
            driver.current(x1, highwayGroundLevel - 1, z1);
            for (int y = 0; y < 40; y++) {
                if (LostCityTerrainFeature.isEmpty(driver.getBlock())) {
                    driver.block(sup);
                } else {
                    break;
                }
                driver.decY();
            }

            int x2 = transform.rotateX(0, 0);
            int z2 = transform.rotateZ(0, 0);
            driver.current(x2, highwayGroundLevel - 1, z2);
            for (int y = 0; y < 40; y++) {
                if (LostCityTerrainFeature.isEmpty(driver.getBlock())) {
                    driver.block(sup);
                } else {
                    break;
                }
                driver.decY();
            }
        }
    }
}
