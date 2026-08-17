package dev.krona.urbex.setup;

import dev.krona.urbex.worldgen.GenerationSession;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.PlanningContext;
import dev.krona.urbex.worldgen.SiteSpawnClaims;
import dev.krona.urbex.worldgen.lost.*;
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

    private static final Map<ResourceKey<Level>, Pending> spawnPositions = new HashMap<>();

    /**
     * A spawn this class chose, waiting for the first player to arrive.
     *
     * @param pos    where the player should end up
     * @param forced whether to put them there even if the world spawn already says so.
     *               <p>
     *               The difference is vanilla's {@code fudgeSpawnLocation}, which does not use the
     *               world spawn as given: with sky light it searches around it on the
     *               {@code MOTION_BLOCKING} heightmap for somewhere to stand. For a city on the
     *               surface that lands on the same street and nobody notices. For a spawn forty
     *               blocks underground it finds the roof of the world instead, so the world spawn is
     *               correct, the log says so, and the player is standing in daylight.
     *               <p>
     *               So a site's spawn is forced: the fudge is precisely what has to be undone. The
     *               unforced case is the older one this map was built for - a single-player world
     *               whose {@code level.dat} did not exist when the spawn was chosen, where the world
     *               spawn is wrong and correcting it is the whole job.
     */
    private record Pending(BlockPos pos, boolean forced) {}

    static void reset() {
        spawnPositions.clear();
    }

    public static void onPlayerFirstJoin(ServerPlayer serverPlayer) {
        ServerLevel level = serverPlayer.level();
        ResourceKey<Level> dimKey = level.dimension();

        Pending pending = spawnPositions.get(dimKey);
        if (pending == null) {
            return;
        }
        BlockPos correctPos = pending.pos();
        LevelData.RespawnData rd = level.getRespawnData();
        BlockPos currentWorldSpawn = rd.pos();

        if (!pending.forced() && currentWorldSpawn.equals(correctPos)) {
            return;
        }
        LevelData.RespawnData newd = new LevelData.RespawnData(new GlobalPos(level.dimension(), correctPos), 0.0f, 0.0f);
        if (!currentWorldSpawn.equals(correctPos)) {
            level.setRespawnData(newd);
            if (level.getLevelData() instanceof ServerLevelData data) {
                data.setSpawn(newd);
            }
        }
        serverPlayer.teleportTo(level, correctPos.getX() + 0.5, correctPos.getY(), correctPos.getZ() + 0.5, Collections.emptySet(), serverPlayer.getYRot(), serverPlayer.getXRot(), true);
        if (pending.forced()) {
            // Their personal respawn point too, as though they had slept there. Without it the first
            // death undoes the whole thing: respawning goes back through the world spawn and the
            // same fudge, and a player who woke up sealed underground finds themselves on the
            // surface for good the first time something kills them.
            serverPlayer.setRespawnPosition(new ServerPlayer.RespawnConfig(newd, true), false);
        }
        spawnPositions.remove(dimKey);
    }

    /**
     * Called from MinecraftServerMixin when the initial spawn position is being determined.
     * Returns true if Lost Cities took over spawn placement (the vanilla logic should be skipped).
     */
    public static boolean onCreateSpawnPoint(ServerLevel serverLevel, ServerLevelData settings) {
        LevelAccessor world = serverLevel;
        // Ahead of everything below, including the "does Urbex generate here at all" check. A site
        // claim is a mod saying something more specific than a preset's spawn rules can, and it has
        // to work in a level that has no preset - a vanilla overworld with bunkers under it, which
        // is exactly the configuration the site API exists to allow.
        if (applySiteSpawn(serverLevel, settings)) {
            return true;
        }
        {
            PlanningContext dimensionInfo = GenerationSession.planningFor(serverLevel);
            if (dimensionInfo == null) {
                return false;
            }
            Preset profile = dimensionInfo.preset();

            Predicate<BlockPos> isSuitable = pos -> true;
            boolean needsCheck = false;

            if (!profile.spawnBiome().isEmpty()) {
                final Biome spawnBiome = serverLevel.registryAccess().lookupOrThrow(Registries.BIOME).getValue(Identifier.parse(profile.spawnBiome()));
                if (spawnBiome == null) {
                    ModSetup.getLogger().error("Cannot find biome '{}' for the player to spawn in !", profile.spawnBiome());
                } else {
                    isSuitable = blockPos -> world.getBiome(blockPos).value() == spawnBiome;
                    needsCheck = true;
                }
            } else if (!profile.spawnCity().isEmpty()) {
                final PredefinedCity city = dimensionInfo.assets().predefinedCities().get(profile.spawnCity());
                if (city == null) {
                    ModSetup.getLogger().error("Cannot find city '{}' for the player to spawn in !", profile.spawnCity());
                } else {
                    float sqradius = getSqRadius(city.getRadius(), 0.8f);
                    isSuitable = blockPos -> city.getDimension() == serverLevel.dimension() &&
                            squaredHorizontalDistance(city.getChunkX()*16+8, city.getChunkZ()*16+8, blockPos.getX(), blockPos.getZ()) < sqradius;
                    needsCheck = true;
                }
            }

            if (profile.spawnNotInBuilding()) {
                isSuitable = isSuitable.and(blockPos -> isOutsideBuilding(dimensionInfo, blockPos));
                needsCheck = true;
            } else if (!profile.forceSpawnBuildings().isEmpty() || !profile.forceSpawnParts().isEmpty()) {
                Set<String> buildings = Set.copyOf(profile.forceSpawnBuildings());
                Set<String> parts = Set.copyOf(profile.forceSpawnParts());
                isSuitable = isSuitable.and(blockPos -> {
                    ChunkCoord coord = new ChunkCoord(dimensionInfo.dimension(), blockPos.getX() >> 4, blockPos.getZ() >> 4);
                    ChunkPlan info = ChunkPlan.getChunkPlan(coord, dimensionInfo);
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
            } else if (profile.forceSpawnInBuilding()) {
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
                    spawnPositions.put(serverLevel.dimension(), new Pending(pos, false));
                    return true;
                }
            } else {
                BlockPos pos = findSafeSpawnPoint(serverLevel, dimensionInfo, isSuitable, settings);
                LevelData.RespawnData data = new LevelData.RespawnData(new GlobalPos(serverLevel.dimension(), pos), 0.0f, 0.0f);
                serverLevel.setRespawnData(data);
                settings.setSpawn(data);
                spawnPositions.put(serverLevel.dimension(), new Pending(pos, false));
                return true;
            }
        }
        return false;
    }

    /**
     * Places the spawn inside a claimed site, if a mod asked for one and the search found somewhere.
     *
     * <p>The anchor a claim yields is the plan's ground floor at a chunk's centre - a Y that is
     * correct about the layout and says nothing about what block is actually there. So this walks up
     * from it looking for somewhere to stand, exactly as {@code findSafeSpawnPoint} does for the
     * dimension's own rules, and reading those blocks is what forces the one chunk to generate.</p>
     *
     * <p>Eight blocks of headroom searched, not the whole column: the floor of a street is where the
     * plan says it is, and a position further up than that is inside whatever was built above, not a
     * better spot on the same street.</p>
     */
    private static boolean applySiteSpawn(ServerLevel serverLevel, ServerLevelData settings) {
        if (SiteSpawnClaims.isEmpty()) {
            return false;
        }
        SiteSpawnClaims.Anchor anchor = SiteSpawnClaims.findAnchor(serverLevel);
        if (anchor == null) {
            return false;
        }
        for (int dy = 0; dy <= 8; dy++) {
            BlockPos candidate = anchor.pos().above(dy);
            if (!isValidStandingPosition(serverLevel, candidate)) {
                continue;
            }
            BlockPos spawn = candidate.above();
            LevelData.RespawnData data = new LevelData.RespawnData(
                    new GlobalPos(serverLevel.dimension(), spawn), 0.0f, 0.0f);
            serverLevel.setRespawnData(data);
            settings.setSpawn(data);
            // Forced, because the player still has to be moved even though the world spawn is now
            // right: vanilla fudges the arrival position onto the surface heightmap. See Pending.
            spawnPositions.put(serverLevel.dimension(), new Pending(spawn, true));
            Urbex.getLogger().info("Urbex site '{}' placed this world's spawn at {}, {}, {}.",
                    anchor.site(), spawn.getX(), spawn.getY(), spawn.getZ());
            return true;
        }
        Urbex.getLogger().warn("Urbex site '{}' offered a spawn at {}, {}, {}, but there was nowhere "
                        + "to stand within eight blocks of it. The world keeps its own spawn.",
                anchor.site(), anchor.pos().getX(), anchor.pos().getY(), anchor.pos().getZ());
        return false;
    }

    private static boolean isOutsideBuilding(PlanningContext provider, BlockPos pos) {
        ChunkCoord coord = new ChunkCoord(provider.dimension(), pos.getX() >> 4, pos.getZ() >> 4);
        ChunkPlan info = ChunkPlan.getChunkPlan(coord, provider);
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

    private static BlockPos findSafeSpawnPoint(Level world, PlanningContext provider, @Nonnull Predicate<BlockPos> isSuitable,
                                    @Nonnull ServerLevelData serverLevelData) {
        Random rand = new Random(provider.seed());
        int radius = provider.preset().spawnCheckRadius();
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

                ChunkCoord coord = new ChunkCoord(provider.dimension(), x >> 4, z >> 4);
                Preset profile = provider.preset();

                for (int y = profile.groundLevel()-5 ; y < 125 ; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isValidStandingPosition(world, pos)) {
//                        serverLevelData.setSpawn(pos.above(), 0.0f);
                        return pos.above();
                    }
                }
            }
            radius += provider.preset().spawnRadiusIncrease();
            if (attempts > provider.preset().spawnCheckAttempts()) {
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
