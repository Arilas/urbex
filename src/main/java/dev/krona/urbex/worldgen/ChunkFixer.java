package dev.krona.urbex.worldgen;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
import net.minecraft.world.level.WorldGenLevel;

public class ChunkFixer {


    private static void executePostTodo(ChunkCoord coord, IDimensionInfo provider, WorldGenLevel region) {
        BuildingInfo info = BuildingInfo.getBuildingInfo(coord, provider);
        info.getPostTodo().forEach((pos, todo) -> todo.accept(region));
        info.clearPostTodo();
    }

    /**
     * The region, not {@code info.getWorld()}: the post-todos read and write blocks, and only the
     * region generating this chunk is guaranteed to have the chunks they touch.
     */
    public static void fix(IDimensionInfo info, ChunkCoord coord, WorldGenLevel region) {
        executePostTodo(coord, info, region);
    }
}
