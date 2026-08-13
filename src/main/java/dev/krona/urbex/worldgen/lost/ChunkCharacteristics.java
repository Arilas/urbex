package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.worldgen.lost.cityassets.Building;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.cityassets.MultiBuilding;
import net.minecraft.resources.Identifier;

public class ChunkCharacteristics {
    // Written once, while this object is still thread-confined, and published by the putIfAbsent in
    // getChunkCharacteristics - which is what makes it visible to the threads that read it. It used
    // to be volatile because setCityRaw() flipped it after publication, from whichever thread was
    // generating that chunk, on a value its neighbours had already derived from; that write is gone
    // and so is the reason for the keyword (issue #126).
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
