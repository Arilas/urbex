package dev.krona.urbex.worldgen;

import dev.krona.urbex.setup.Config;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Lost Cities generates its cities at the RAW_GENERATION decoration step, which is the very
 * first one. Vanilla and modded structures are placed at their own (later) step, so whatever
 * they build lands on top of a finished city - a pillager outpost punched through a building.
 * The 'avoidStructures' options solve this the other way around, by moving the city out of
 * the way; this one lets the city win instead, by skipping structure placement in the chunks
 * and at the heights a city actually occupies.
 */
public class StructureSuppressor {

    /**
     * True if a structure spanning 'structureBox' must not be placed into 'chunkPos'.
     * Only the part of the structure that overlaps the city is dropped, so a structure at the
     * edge of a city keeps the pieces that fall outside it.
     */
    public static boolean suppressedByCity(WorldGenLevel level, ChunkPos chunkPos, BoundingBox structureBox) {
        if (!Config.STRUCTURES_YIELD_TO_CITIES.get()) {
            return false;
        }
        PlanningContext diminfo = GenerationSession.planningFor(level);
        if (diminfo == null) {
            return false;   // No Urbex profile for this dimension, or the level is not loaded
        }

        // No lock and no setWorld: the city caches are concurrent now, and PlanningContext's level
        // is final. Structure placement runs on the worker pool too, so this used to be one of the
        // threads contending for the per-dimension monitor.
        ChunkCoord coord = new ChunkCoord(diminfo.dimension(), chunkPos.x(), chunkPos.z());
        if (!ChunkPlan.isCity(coord, diminfo)) {
            return false;
        }
        ChunkPlan info = ChunkPlan.getChunkPlan(coord, diminfo);
        int ground = info.getCityGroundLevel();
        // One extra floor of slack at both ends so a structure does not clip the roof or
        // undermine the lowest cellar.
        int top = ground + (info.hasBuilding ? info.getNumFloors() * CityGenerator.FLOORHEIGHT : 0)
                + CityGenerator.FLOORHEIGHT;
        int bottom = ground - info.getNumCellars() * CityGenerator.FLOORHEIGHT
                - CityGenerator.FLOORHEIGHT;
        // Structures that pass well under the city (ancient cities, mineshafts, deep
        // ruined portals) never conflict with it, so leave those alone.
        return structureBox.maxY() >= bottom && structureBox.minY() <= top;
    }
}
