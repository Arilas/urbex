package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.MultiBuildingRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.resources.Identifier;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MultiBuilding {

    private final Identifier name;
    private final int dimX;
    private final int dimZ;
    private final List<List<String>> buildings;
    private final Set<String> buildingSet;

    public MultiBuilding(MultiBuildingRE object) {
        name = object.getRegistryName();
        this.dimX = object.getDimX();
        this.dimZ = object.getDimZ();
        this.buildings = object.getBuildings();
        this.buildingSet = new HashSet<>();
        for (List<String> row : buildings) {
            for (String building : row) {
                if (building != null && !building.isEmpty()) {
                    buildingSet.add(building);
                }
            }
        }
    }

    public String getBuilding(int x, int z) {
        return buildings.get(x).get(z);
    }

    public int getDimX() {
        return dimX;
    }

    public int getDimZ() {
        return dimZ;
    }

    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }

    public Set<String> getBuildingSet() {
        return buildingSet;
    }
}
