package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.worldgen.lost.cityassets.Building;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.cityassets.MultiBuilding;
import net.minecraft.resources.Identifier;

public class LostChunkCharacteristics {
    public boolean isCity;
    public boolean couldHaveBuilding;   // True if this chunk could contain a building
    public MultiPos multiPos;           // Equal to SINGLE if a single building
    public int cityLevel;               // 0 is lowest city level
    public Identifier cityStyleId;
    public CityStyle cityStyle;
    public Identifier multiBuildingId;
    public MultiBuilding multiBuilding;
    public Identifier buildingTypeId;
    public Building buildingType;
}
