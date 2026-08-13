package dev.krona.urbex.worldgen.gen;

import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.ChunkDriver;
import dev.krona.urbex.worldgen.ChunkGenContext;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.Parts;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.RailChunkType;
import dev.krona.urbex.worldgen.lost.Railway;
import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;
import dev.krona.urbex.worldgen.lost.Transform;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.IBuildingPart;
import dev.krona.urbex.worldgen.lost.regassets.data.StreetParts;
import dev.krona.urbex.worldgen.lost.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Streets, their borders, and what joins them to the chunks next door.
 *
 * <p>Everything between "this chunk is a street" and the blocks that make one: the surface itself,
 * the slope sections where the city level steps, the connectors to minor streets, the border walls
 * and their supports, and the front parts of the buildings that face them.</p>
 *
 * <p>Moved out of {@link CityGenerator} unchanged - same code, same order, same RNG draws - as part
 * of splitting that class up (issue #11). Follows the convention the rest of this package already
 * uses: static entry points that take the generator as {@code feature} and reach back through it for
 * the shared rendering vocabulary ({@code generatePart}, {@code getRandomPart},
 * {@code setBlocksFromPalette}).</p>
 */
public class Streets {

    private Streets() {
    }

    public static void generateStreetDecorations(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info) {
        Direction stairDirection = info.getActualStairDirection();
        if (stairDirection != null) {
            BuildingPart stairs = info.stairType;
            if (stairs != null) {
                Transform transform;
                int oy = info.getCityGroundLevel() + 1;
                transform = switch (stairDirection) {
                    case XMIN -> Transform.ROTATE_NONE;
                    case XMAX -> Transform.ROTATE_180;
                    case ZMIN -> Transform.ROTATE_90;
                    case ZMAX -> Transform.ROTATE_270;
                };

                Parts.generatePart(ctx, feature, info, stairs, transform, 0, oy, 0, CityGenerator.HardAirSetting.AIR);
            }
        }
    }

    public static void generateStreet(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, ChunkHeightmap heightmap) {
        ChunkDriver driver = ctx.driver;
        boolean xRail = info.hasXCorridor();
        boolean zRail = info.hasZCorridor();
        if (xRail || zRail) {
            Corridors.generateCorridors(ctx, feature, info, xRail, zRail);
        }

        Railway.RailChunkInfo railInfo = info.getRailInfo();
        boolean canDoStreetOrPark = info.getHighwayXLevel() != info.cityLevel && info.getHighwayZLevel() != info.cityLevel
                && railInfo.getType() != RailChunkType.STATION_SURFACE
                && (railInfo.getType() != RailChunkType.STATION_EXTENSION_SURFACE || railInfo.getLevel() < info.cityLevel);

        if (canDoStreetOrPark) {
            int height = info.getCityGroundLevel();
            // In default landscape type we clear the landscape on top of the building
//            if (feature.profile.isDefault()) {
//                feature.clearToMax(info, heightmap, height);
//            }

            Direction streetSlopeDirection = info.getStreetSlopeDirection();
            ChunkPlan.StreetType streetType = info.streetType;
            boolean elevated = info.isElevatedParkSection();
            if (elevated) {
                Character elevationBlock = info.getCityStyle().getParkElevationBlock();
                BlockState elevation = ctx.paletteAt(info.getCompiledPalette(), elevationBlock, 0, height, 0);
                for (int x = 0; x < 16; ++x) {
                    driver.current(x, height, 0);
                    for (int z = 0; z < 16; ++z) {
                        driver.block(elevation).incZ();
                    }
                }
                boolean parkElevation = info.profile.parkElevation();
                if (info.getCityStyle().getParkElevation() != null) {
                    parkElevation = info.getCityStyle().getParkElevation();
                }
                if (parkElevation) {
                    height++;
                }
            }

            // No re-roll here any more. The content decision is authoritative: a planned road is
            // NORMAL, an open lot is whatever its addressed park roll said, and re-rolling was what
            // used to clobber the PARK decision neighbours read through isElevatedParkSection()
            // (issue #36).
            switch (streetType) {
                case NORMAL -> {
                    if (streetSlopeDirection == null) {
                        generateNormalStreetSection(ctx, feature, info, height);
                    } else {
                        generateStreetSlopeSection(ctx, feature, info, height, streetSlopeDirection);
                    }
                }
                case PARK -> feature.decorations.parkSection(ctx, feature, info, height, elevated);
            }
            height++;

            // A sloped chunk is a ramp from end to end. Everything that would stand on the surface -
            // a fountain, a park part, vegetation, a building front reaching out over the pavement -
            // is suppressed, because on a ramp it would sit in mid-feature.air or in the middle of the route.
            if (streetSlopeDirection == null) {
                if (streetType == ChunkPlan.StreetType.PARK || info.fountainType != null) {
                    BuildingPart part;
                    if (streetType == ChunkPlan.StreetType.PARK) {
                        part = info.parkType;
                    } else {
                        part = info.fountainType;
                    }
                    if (part != null) {
                        Parts.generatePart(ctx, feature, info, part, Transform.ROTATE_NONE, 0, height, 0, CityGenerator.HardAirSetting.AIR);
                    }
                }

                feature.decorations.vegetation(ctx, feature, info, height);

                generateFrontPart(ctx, feature, info, height, info.getXmin(), Transform.ROTATE_NONE);
                generateFrontPart(ctx, feature, info, height, info.getZmin(), Transform.ROTATE_90);
                generateFrontPart(ctx, feature, info, height, info.getXmax(), Transform.ROTATE_180);
                generateFrontPart(ctx, feature, info, height, info.getZmax(), Transform.ROTATE_270);
            }
        }

        generateBorders(ctx, feature, info, canDoStreetOrPark, heightmap);
    }

    private static void generateBorders(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, boolean canDoParks, ChunkHeightmap heightmap) {
        Character borderBlock = info.getCityStyle().getBorderBlock();

        if (info.profile.isFloating()) {
            fillMainStreetBlock(ctx, feature, info, borderBlock, 3);
        } else if (info.profile.isCavern()) {
            fillMainStreetBlock(ctx, feature, info, borderBlock, 2);
        } else {
            fillToBedrockStreetBlock(ctx, feature, info);
        }

        if (doBorder(feature, info, Direction.XMIN)) {
            int x = 0;
            for (int z = 0; z < 16; z++) {
                generateBorder(ctx, feature, info, canDoParks, x, z, Direction.XMIN.get(info), heightmap);
            }
        }
        if (doBorder(feature, info, Direction.XMAX)) {
            int x = 15;
            for (int z = 0; z < 16; z++) {
                generateBorder(ctx, feature, info, canDoParks, x, z, Direction.XMAX.get(info), heightmap);
            }
        }
        if (doBorder(feature, info, Direction.ZMIN)) {
            int z = 0;
            for (int x = 0; x < 16; x++) {
                generateBorder(ctx, feature, info, canDoParks, x, z, Direction.ZMIN.get(info), heightmap);
            }
        }
        if (doBorder(feature, info, Direction.ZMAX)) {
            int z = 15;
            for (int x = 0; x < 16; x++) {
                generateBorder(ctx, feature, info, canDoParks, x, z, Direction.ZMAX.get(info), heightmap);
            }
        }
    }

    /**
     * Fill feature.profile.getBaseBlock() blocks under streets to bedrock
     */
    private static void fillToBedrockStreetBlock(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info) {
        ChunkDriver driver = ctx.driver;
        // Base blocks below streets
        int minHeight = info.provider.shape().minY();
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                int y = info.getCityGroundLevel() - 1;
                driver.current(x, y, z);
                while (driver.getY() > (minHeight + info.profile.bedrockLayer()) && CityGenerator.isEmpty(driver.getBlock())) {
                    driver.block(feature.profile.getBaseBlock());
                    driver.decY();
                }
//                driver.setBlockRange(x, info.profile.bedrockLayer(), z, info.getCityGroundLevel(), baseChar);
            }
        }
    }

    /**
     * Fill a main street block with feature.profile.getBaseBlock() blocks and border blocks at the bottom
     */
    private static void fillMainStreetBlock(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, Character borderBlock, int offset) {
        ChunkDriver driver = ctx.driver;
        BlockState border = ctx.paletteAt(info.getCompiledPalette(), borderBlock, 0, info.getCityGroundLevel() - offset, 0);
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                driver.setBlockRange(x, info.getCityGroundLevel() - (offset - 1), z, info.getCityGroundLevel(), feature.profile.getBaseBlock());
                driver.current(x, info.getCityGroundLevel() - offset, z).block(border);
            }
        }
    }

    /**
     * Generate a single border column for one side of a street block
     */
    private static void generateBorder(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, boolean canDoParks, int x, int z, ChunkPlan adjacent, ChunkHeightmap heightmap) {
        ChunkDriver driver = ctx.driver;
        Character borderBlock = info.getCityStyle().getBorderBlock();
        Character wallBlock = info.getCityStyle().getWallBlock();
        BlockState wall = ctx.paletteAt(info.getCompiledPalette(), wallBlock, x, info.getCityGroundLevel() + 1, z);

        if (info.profile.isFloating()) {
                Parts.setBlocksFromPalette(ctx, feature, x, info.getCityGroundLevel() - 3, z, info.getCityGroundLevel() + 1, info.getCompiledPalette(), borderBlock);
                if (isCorner(x, z)) {
                    generateBorderSupport(ctx, feature, info, wall, x, z, 3, heightmap);
                }
        } else if (info.profile.isCavern()) {
                Parts.setBlocksFromPalette(ctx, feature, x, info.getCityGroundLevel() - 2, z, info.getCityGroundLevel() + 1, info.getCompiledPalette(), borderBlock);
                if (isCorner(x, z)) {
                    generateBorderSupport(ctx, feature, info, wall, x, z, 2, heightmap);
                }
        } else {
            int y = Terrain.getMinHeightAt(feature, info, x, z, heightmap);
            if (y < info.getCityGroundLevel() + 1) {
                Parts.setBlocksFromPalette(ctx, feature, x, y - 1, z, info.getCityGroundLevel() + 1, info.getCompiledPalette(), borderBlock);
            } else {
                Parts.setBlocksFromPalette(ctx, feature, x, info.getCityGroundLevel() - 3, z, info.getCityGroundLevel() + 1, info.getCompiledPalette(), borderBlock);
            }
        }
        if (canDoParks) {
            if (!borderNeedsConnectionToAdjacentChunk(feature, info, x, z)) {
                driver.current(x, info.getCityGroundLevel() + 1, z).block(wall);
            } else {
                driver.current(x, info.getCityGroundLevel() + 1, z).block(feature.air);
            }
        }
    }

    /**
     * Generate a column of wall blocks (and stone below that in water)
     */
    private static void generateBorderSupport(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, BlockState wall, int x, int z, int offset, ChunkHeightmap heightmap) {
        ChunkDriver driver = ctx.driver;
        int height = heightmap.getHeight();
        if (height > 1) {
            // None void
            int y = info.getCityGroundLevel() - offset - 1;
            driver.current(x, y, z);
            while (y > 1 && driver.getBlock() == feature.air) {
                driver.block(wall).decY();
                y--;
            }
            while (y > 1 && driver.getBlock() == feature.liquid) {
                driver.block(feature.profile.getBaseBlock()).decY();
                y--;
            }
        }
    }

    private static int generateFrontPart(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, int height, ChunkPlan adj, Transform rot) {
        if (info.hasFrontPartFrom(adj)) {
            return Parts.generatePart(ctx, feature, adj, adj.frontType, rot, 0, height, 0, CityGenerator.HardAirSetting.AIR);
        }
        return height;
    }

    private static StreetParts getStreetParts(ChunkPlan info) {
        return switch (info.getEffectiveRoadType()) {
            case PRIMARY -> info.getCityStyle().getLargeStreetParts();
            case TERTIARY -> info.getCityStyle().getTertiaryStreetParts();
            case SECONDARY, NONE -> info.getCityStyle().getStreetParts();
        };
    }

    /**
     * Whether the street part on {@code info} should reach the edge it shares with {@code adjacent}.
     *
     * <p>On a primary road only another primary counts as a road connection. A secondary or tertiary
     * street still meets the primary's surface - that is what the connector overlay is for - but it
     * must not turn the primary into a bend or a junction, which would aim its quartz centre line
     * down a minor street.
     *
     * <p>A bridge still connects, primary or not: a bridge carries the road onward, so ignoring it
     * would end the road in a kerb with the bridge starting a chunk later out of nothing.
     */
    private static boolean hasStreetPartConnection(ChunkPlan info, ChunkPlan adjacent, boolean bridgeConnection) {
        boolean roadConnection = ChunkPlan.hasRoadConnection(info, adjacent);
        if (info.isPrimaryRoad()) {
            return (roadConnection && adjacent.isPrimaryRoad()) || bridgeConnection;
        }
        return roadConnection || bridgeConnection;
    }

    /**
     * The whole chunk as one ramp. The stair part is authored rising towards {@code XMIN}, so the
     * direction of the higher edge is exactly its rotation.
     */
    private static void generateStreetSlopeSection(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, int height, Direction slopeDirection) {
        StreetParts parts = getStreetParts(info);
        // A style with no stair part has opted out of slopes the same way an empty connector list
        // opts out of connectors below: asset gaps degrade rather than crash. Without this guard,
        // getRandomPart would call List.get on an empty list and throw.
        if (parts.stair().isEmpty()) {
            return;
        }
        BuildingPart part = feature.provider.assets().parts().getOrWarn(feature.getRandomPart(ctx, parts.stair()));
        if (part != null) {
            Parts.generatePart(ctx, feature, info, part, slopeDirection.getRotation(), 0, height, 0, CityGenerator.HardAirSetting.VOID);
        }
    }

    private static void generateNormalStreetSection(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, int height) {
        StreetParts parts = getStreetParts(info);
        boolean xmin = hasStreetPartConnection(info, info.getXmin(), info.getXmin().hasXBridge(feature.provider) != null);
        boolean xmax = hasStreetPartConnection(info, info.getXmax(), info.getXmax().hasXBridge(feature.provider) != null);
        boolean zmin = hasStreetPartConnection(info, info.getZmin(), info.getZmin().hasZBridge(feature.provider) != null);
        boolean zmax = hasStreetPartConnection(info, info.getZmax(), info.getZmax().hasZBridge(feature.provider) != null);
        int cnt = (xmin ? 1 : 0) + (xmax ? 1 : 0) + (zmin ? 1 : 0) + (zmax ? 1 : 0);
        Transform transform = Transform.ROTATE_NONE;
        // Each part-family list is checked for emptiness before getRandomPart touches it, the same
        // way parts.stair() and parts.connector() are guarded elsewhere: an empty list is a style
        // opting out of that part, and getRandomPart would otherwise call List.get on a nextInt(0)
        // and throw. A null part here is already handled below (no render, no connectors).
        BuildingPart part = switch (cnt) {
            case 0 -> parts.none().isEmpty() ? null
                    : feature.provider.assets().parts().getOrWarn(feature.getRandomPart(ctx, parts.none()));
            case 1 -> {
                if (xmin) {
                } else if (xmax) {
                    transform = Transform.ROTATE_180;
                } else if (zmin) {
                    transform = Transform.ROTATE_90;
                } else {
                    transform = Transform.ROTATE_270;
                }
                yield parts.end().isEmpty() ? null
                        : feature.provider.assets().parts().getOrWarn(feature.getRandomPart(ctx, parts.end()));
            }
            case 2 -> {
                if (xmin == xmax || zmin == zmax) {
                    if (xmin) {
                    } else if (xmax) {
                        transform = Transform.ROTATE_180;
                    } else if (zmin) {
                        transform = Transform.ROTATE_90;
                    } else {
                        transform = Transform.ROTATE_270;
                    }
                    yield parts.straight().isEmpty() ? null
                            : feature.provider.assets().parts().getOrWarn(feature.getRandomPart(ctx, parts.straight()));
                } else {
                    if (xmin && zmin) {
                    } else if (xmin && zmax) {
                        transform = Transform.ROTATE_270;
                    } else if (xmax && zmin) {
                        transform = Transform.ROTATE_90;
                    } else {
                        transform = Transform.ROTATE_180;
                    }
                    yield parts.bend().isEmpty() ? null
                            : feature.provider.assets().parts().getOrWarn(feature.getRandomPart(ctx, parts.bend()));
                }
            }
            case 3 -> {
                if (!xmin) {
                    transform = Transform.ROTATE_90;
                } else if (!xmax) {
                    transform = Transform.ROTATE_270;
                } else if (!zmin) {
                    transform = Transform.ROTATE_180;
                }
                yield parts.t().isEmpty() ? null
                        : feature.provider.assets().parts().getOrWarn(feature.getRandomPart(ctx, parts.t()));
            }
            case 4 -> parts.all().isEmpty() ? null
                    : feature.provider.assets().parts().getOrWarn(feature.getRandomPart(ctx, parts.all()));
            default -> throw new RuntimeException("Not possible!");
        };
        if (part != null) {
            Parts.generatePart(ctx, feature, info, part, transform, 0, height, 0, CityGenerator.HardAirSetting.VOID);
            generateMinorStreetConnectors(ctx, feature, info, parts, height);
        }
    }

    /**
     * Where a minor street runs up against a primary road, overlay a connector on the primary so the
     * two surfaces meet instead of ending in a kerb. Only primaries carry these - a minor street
     * meeting another minor street is an ordinary junction the topology already covers. A style with
     * an empty connector list has opted out; that is a choice, not a missing asset, so no warning.
     */
    private static void generateMinorStreetConnectors(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, StreetParts parts, int height) {
        if (!info.isPrimaryRoad() || parts.connector().isEmpty()) {
            return;
        }
        generateMinorStreetConnector(ctx, feature, info, info.getXmin(), parts, height, Transform.ROTATE_NONE);
        generateMinorStreetConnector(ctx, feature, info, info.getXmax(), parts, height, Transform.ROTATE_180);
        generateMinorStreetConnector(ctx, feature, info, info.getZmin(), parts, height, Transform.ROTATE_90);
        generateMinorStreetConnector(ctx, feature, info, info.getZmax(), parts, height, Transform.ROTATE_270);
    }

    private static void generateMinorStreetConnector(ChunkGenContext ctx, CityGenerator feature, ChunkPlan info, ChunkPlan adjacent,
                                              StreetParts parts, int height, Transform transform) {
        if (ChunkPlan.hasRoadConnection(info, adjacent) && !adjacent.isPrimaryRoad()) {
            BuildingPart connector = feature.provider.assets().parts().getOrWarn(feature.getRandomPart(ctx, parts.connector()));
            if (connector != null) {
                Parts.generatePart(ctx, feature, info, connector, transform, 0, height, 0, CityGenerator.HardAirSetting.VOID);
            }
        }
    }

    private static boolean borderNeedsConnectionToAdjacentChunk(CityGenerator feature, ChunkPlan info, int x, int z) {
        for (Direction direction : Direction.VALUES) {
            if (direction.atSide(x, z)) {
                ChunkPlan adjacent = direction.get(info);
                // A lower neighbour sloping up towards this chunk needs the retaining wall opened,
                // but only across the ramp itself: the stair part's z1/z2 band, rotated to this
                // edge. The rest of the wall stays, or the level change would read as a gap.
                if (adjacent.getStreetSlopeDirection() == direction.getOpposite()) {
                    StreetParts slopeParts = getStreetParts(adjacent);
                    if (!slopeParts.stair().isEmpty()) {
                        BuildingPart slope = feature.provider.assets().parts().getOrWarn(slopeParts.stair().get(0));
                        if (slope != null) {
                            Integer z1 = slope.getMetaInteger(BuildingPart.META_Z_1);
                            Integer z2 = slope.getMetaInteger(BuildingPart.META_Z_2);
                            if (z1 != null && z2 != null) {
                                Transform transform = direction.getOpposite().getRotation();
                                int xx1 = transform.rotateX(15, z1);
                                int zz1 = transform.rotateZ(15, z1);
                                int xx2 = transform.rotateX(15, z2);
                                int zz2 = transform.rotateZ(15, z2);
                                if (x >= Math.min(xx1, xx2) && x <= Math.max(xx1, xx2)
                                        && z >= Math.min(zz1, zz2) && z <= Math.max(zz1, zz2)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
                if (adjacent.getActualStairDirection() == direction.getOpposite()) {
                    BuildingPart stairType = adjacent.stairType;
                    if (stairType != null) {
                        Integer z1 = stairType.getMetaInteger(BuildingPart.META_Z_1);
                        Integer z2 = stairType.getMetaInteger(BuildingPart.META_Z_2);
                        Transform transform = direction.getOpposite().getRotation();
                        int xx1 = transform.rotateX(15, z1);
                        int zz1 = transform.rotateZ(15, z1);
                        int xx2 = transform.rotateX(15, z2);
                        int zz2 = transform.rotateZ(15, z2);
                        if (x >= Math.min(xx1, xx2) && x <= Math.max(xx1, xx2) && z >= Math.min(zz1, zz2) && z <= Math.max(zz1, zz2)) {
                            return true;
                        }
                    }
                }
                if (adjacent.hasBridge(feature.provider, direction.getOrientation()) != null) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * Generate a part. If 'airWaterLevel' is true then 'hard feature.air' blocks are replaced with water below the waterLevel.
     * Otherwise they are replaced with feature.air.
     */
    private static boolean doBorder(CityGenerator feature, ChunkPlan info, Direction direction) {
        ChunkPlan adjacent = direction.get(info);
        if (isHigherThenNearbyStreetChunk(feature, info, adjacent)) {
            return true;
        } else if (!adjacent.isCity) {
            if (adjacent.cityLevel <= info.cityLevel) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHigherThenNearbyStreetChunk(CityGenerator feature, ChunkPlan info, ChunkPlan adjacent) {
        if (!adjacent.isCity) {
            return false;
        }
        if (adjacent.hasBuilding) {
            return adjacent.cityLevel + adjacent.getNumFloors() < info.cityLevel;
        } else {
            return adjacent.cityLevel < info.cityLevel;
        }
    }

    private static boolean isCorner(int x, int z) {
        return (x == 0 || x == 15) && (z == 0 || z == 15);
    }

    /**
     * Queue a place-twice refresh at {@code pos} on the context generating this chunk.
     * <p>
     * Takes the context rather than the chunk's {@code ChunkPlan}: the refresh belongs to one
     * generation, and a cached planning value is not allowed to hold it (issue #127).
     */
}
