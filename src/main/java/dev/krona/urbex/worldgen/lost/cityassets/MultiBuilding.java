package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.MultiBuildingRE;
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

    /**
     * Builds a fully resolved multi-building from its {@code extends} chain, root first: each field
     * takes the value of the last entry that declares one, so a child can resize a grid it inherits
     * or restate a grid at the size it inherits. The grid replaces its ancestor's wholesale rather
     * than merging, because a half-inherited grid would contradict {@code dimx}/{@code dimz}.
     */
    public MultiBuilding(List<MultiBuildingRE> chainRootFirst) {
        name = chainRootFirst.get(chainRootFirst.size() - 1).getRegistryName();
        Integer declaredDimX = null;
        Integer declaredDimZ = null;
        List<List<String>> declaredBuildings = null;
        for (MultiBuildingRE object : chainRootFirst) {
            if (object.getDimX() != null) {
                declaredDimX = object.getDimX();
            }
            if (object.getDimZ() != null) {
                declaredDimZ = object.getDimZ();
            }
            if (object.getBuildings() != null) {
                declaredBuildings = object.getBuildings();
            }
        }
        this.dimX = Resolved.require(declaredDimX, name, "dimx");
        this.dimZ = Resolved.require(declaredDimZ, name, "dimz");
        this.buildings = Resolved.require(declaredBuildings, name, "buildings");
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

    /** The fully-qualified id, e.g. {@code "urbex:oilrig"}. */
    public String getName() {
        return name.toString();
    }

    public Identifier getId() {
        return name;
    }

    public Set<String> getBuildingSet() {
        return buildingSet;
    }
}
