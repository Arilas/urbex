package dev.krona.urbex.setup;

import dev.krona.urbex.worldgen.GenerationSession;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.*;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;
import dev.krona.urbex.worldgen.lost.cityassets.PredefinedCity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.ServerLevelData;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Picks and applies the world spawn for profiles that constrain it (spawn biome, spawn city,
 * in/outside buildings). Stateless apart from {@link #spawnPositions}, the pending
 * spawn corrections applied when the first player joins - cleared on server stop so nothing
 * leaks between worlds in one client session.
 */
public class SpawnPlacement {

    private static final Map<ResourceKey<Level>, BlockPos> spawnPositions = new HashMap<>();

    static void reset() {
        spawnPositions.clear();
    }

    public static void onPlayerFirstJoin(ServerPlayer serverPlayer) {
        ServerLevel level = serverPlayer.level();
        ResourceKey<Level> dimKey = level.dimension();

        if (spawnPositions.containsKey(dimKey)) {
            BlockPos correctPos = spawnPositions.get(dimKey);
            LevelData.RespawnData rd = level.getRespawnData();
            BlockPos currentWorldSpawn = rd.pos();

            if (!currentWorldSpawn.equals(correctPos)) {
                LevelData.RespawnData newd = new LevelData.RespawnData(new GlobalPos(level.dimension(), correctPos), 0.0f, 0.0f);
                level.setRespawnData(newd);

                if (level.getLevelData() instanceof ServerLevelData data) {
                    data.setSpawn(newd);
                }
                serverPlayer.teleportTo(level, correctPos.getX() + 0.5, correctPos.getY(), correctPos.getZ() + 0.5, Collections.emptySet(), serverPlayer.getYRot(), serverPlayer.getXRot(), true);
                spawnPositions.remove(dimKey);
            }
        }
    }

    /**
     * Called from MinecraftServerMixin when the initial spawn position is being determined.
     * Returns true if Lost Cities took over spawn placement (the vanilla logic should be skipped).
     */
    public static boolean onCreateSpawnPoint(ServerLevel serverLevel, ServerLevelData settings) {
        LevelAccessor world = serverLevel;
        {
            IDimensionInfo dimensionInfo = GenerationSession.planningFor(serverLevel);
            if (dimensionInfo == null) {
                return false;
            }
            Preset profile = dimensionInfo.getProfile();

            Predicate<BlockPos> isSuitable = pos -> true;
            boolean needsCheck = false;

            if (!profile.SPAWN_BIOME.isEmpty()) {
                final Biome spawnBiome = serverLevel.registryAccess().lookupOrThrow(Registries.BIOME).getValue(Identifier.parse(profile.SPAWN_BIOME));
                if (spawnBiome == null) {
                    ModSetup.getLogger().error("Cannot find biome '{}' for the player to spawn in !", profile.SPAWN_BIOME);
                } else {
                    isSuitable = blockPos -> world.getBiome(blockPos).value() == spawnBiome;
                    needsCheck = true;
                }
            } else if (!profile.SPAWN_CITY.isEmpty()) {
                final PredefinedCity city = AssetRegistries.PREDEFINED_CITIES.get(world, profile.SPAWN_CITY);
                if (city == null) {
                    ModSetup.getLogger().error("Cannot find city '{}' for the player to spawn in !", profile.SPAWN_CITY);
                } else {
                    float sqradius = getSqRadius(city.getRadius(), 0.8f);
                    isSuitable = blockPos -> city.getDimension() == serverLevel.dimension() &&
                            squaredHorizontalDistance(city.getChunkX()*16+8, city.getChunkZ()*16+8, blockPos.getX(), blockPos.getZ()) < sqradius;
                    needsCheck = true;
                }
            }

            if (profile.SPAWN_NOT_IN_BUILDING) {
                isSuitable = isSuitable.and(blockPos -> isOutsideBuilding(dimensionInfo, blockPos));
                needsCheck = true;
            } else if (!profile.FORCE_SPAWN_BUILDINGS.isEmpty() || !profile.FORCE_SPAWN_PARTS.isEmpty()) {
                Set<String> buildings = Set.copyOf(profile.FORCE_SPAWN_BUILDINGS);
                Set<String> parts = Set.copyOf(profile.FORCE_SPAWN_PARTS);
                isSuitable = isSuitable.and(blockPos -> {
                    ChunkCoord coord = new ChunkCoord(dimensionInfo.getType(), blockPos.getX() >> 4, blockPos.getZ() >> 4);
                    BuildingInfo info = BuildingInfo.getBuildingInfo(coord, dimensionInfo);
                    if (info == null) {
                        return false;
                    }
                    if (info.isCity() && info.hasBuilding) {
                        if (!buildings.isEmpty()) {
                            if (!buildings.contains(info.buildingType.getId().toString())) {
                                return false;
                            }
                        }
                        if (!parts.isEmpty()) {
                            int lowestLevel = info.getBuildingBottomHeight();
                            if (lowestLevel != Integer.MIN_VALUE) {
                                BuildingPart part = info.getFloorAtY(lowestLevel, blockPos.getY());
                                if (part == null || !parts.contains(part.getId().toString())) {
                                    return false;
                                }
                            }
                        }
                        return true;
                    }
                    return false;
                });
                needsCheck = true;
            } else if (profile.FORCE_SPAWN_IN_BUILDING) {
                isSuitable = isSuitable.and(blockPos -> !isOutsideBuilding(dimensionInfo, blockPos));
                needsCheck = true;
            }

            // Potentially set the spawn point
            // In single player, this is potentially being ignored due to the case that level.dat does not exists yet
            // thus the world spawn is not set
            // then we'll store into the spawnPositions first and prepare to set it up again.
            if (profile.isDefault()) {
                if (needsCheck) {
                    BlockPos pos = findSafeSpawnPoint(serverLevel, dimensionInfo, isSuitable, settings);
                    LevelData.RespawnData data = new LevelData.RespawnData(new GlobalPos(serverLevel.dimension(), pos), 0.0f, 0.0f);
                    serverLevel.setRespawnData(data);
                    settings.setSpawn(data);
                    spawnPositions.put(serverLevel.dimension(), pos);
                    return true;
                }
            } else {
                BlockPos pos = findSafeSpawnPoint(serverLevel, dimensionInfo, isSuitable, settings);
                LevelData.RespawnData data = new LevelData.RespawnData(new GlobalPos(serverLevel.dimension(), pos), 0.0f, 0.0f);
                serverLevel.setRespawnData(data);
                settings.setSpawn(data);
                spawnPositions.put(serverLevel.dimension(), pos);
                return true;
            }
        }
        return false;
    }

    private static boolean isOutsideBuilding(IDimensionInfo provider, BlockPos pos) {
        ChunkCoord coord = new ChunkCoord(provider.getType(), pos.getX() >> 4, pos.getZ() >> 4);
        BuildingInfo info = BuildingInfo.getBuildingInfo(coord, provider);
        return !(info.isCity() && info.hasBuilding);
    }

    private static int getSqRadius(int radius, float pct) {
        return (int) ((radius * pct) * (radius * pct));
    }

    private static double squaredHorizontalDistance(int x1, int z1, int x2, int z2) {
        double dx = x1 - (double) x2;
        double dz = z1 - (double) z2;
        return dx * dx + dz * dz;
    }

    private static BlockPos findSafeSpawnPoint(Level world, IDimensionInfo provider, @Nonnull Predicate<BlockPos> isSuitable,
                                    @Nonnull ServerLevelData serverLevelData) {
        Random rand = new Random(provider.getSeed());
        int radius = provider.getProfile().SPAWN_CHECK_RADIUS;
        int attempts = 0;
//        int bottom = world.getWorldType().getMinimumSpawnHeight(world);
        while (true) {
            for (int i = 0 ; i < 200 ; i++) {
                int x = rand.nextInt(radius * 2) - radius;
                int z = rand.nextInt(radius * 2) - radius;
                attempts++;

                if (!isSuitable.test(new BlockPos(x, 128, z))) {
                    continue;
                }

                ChunkCoord coord = new ChunkCoord(provider.getType(), x >> 4, z >> 4);
                Preset profile = provider.getProfile();

                for (int y = profile.GROUNDLEVEL-5 ; y < 125 ; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isValidStandingPosition(world, pos)) {
//                        serverLevelData.setSpawn(pos.above(), 0.0f);
                        return pos.above();
                    }
                }
            }
            radius += provider.getProfile().SPAWN_RADIUS_INCREASE;
            if (attempts > provider.getProfile().SPAWN_CHECK_ATTEMPTS) {
                Urbex.setup.getLogger().error("Can't find a valid spawn position!");
                throw new RuntimeException("Can't find a valid spawn position!");
            }
        }
    }

    static boolean isValidStandingPosition(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.isFaceSturdy(world, pos, Direction.UP)) {
            return false;
        }
        if (state.is(Blocks.BEDROCK)) {
            return false;
        }
        if (!world.getBlockState(pos.above()).isAir() || !world.getBlockState(pos.above(2)).isAir()) {
            return false;
        }
        return true;
//        return state.getBlock().isTopSolid(state) && state.getBlock().isFullCube(state) && state.getBlock().isOpaqueCube(state) && world.isAirBlock(pos.up()) && world.isAirBlock(pos.up(2));
//        return state.canOcclude();
    }

}
