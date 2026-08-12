package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.MultiBuildingDefinition;
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
    public MultiBuilding(Identifier id, List<MultiBuildingDefinition> chainRootFirst) {
        name = id;
        Integer declaredDimX = null;
        Integer declaredDimZ = null;
        List<List<String>> declaredBuildings = null;
        for (MultiBuildingDefinition object : chainRootFirst) {
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
        checkGeometry();
        this.buildingSet = new HashSet<>();
        for (List<String> row : buildings) {
            for (String building : row) {
                if (building != null && !building.isEmpty()) {
                    buildingSet.add(building);
                }
            }
        }
    }

    /**
     * {@code dimx}/{@code dimz} and the grid resolve from independent links of the chain, so the
     * two can contradict each other even though each is individually present - typically a child
     * that resized one dimension while inheriting the grid. Per-field requiredness cannot state
     * this, so it is checked here, at the fold, rather than left to surface as an
     * IndexOutOfBoundsException from {@link #getBuilding}: {@code MultiChunk.placeBuilding}
     * reserves a cell for every {@code xx < dimX} and those coordinates come straight back.
     */
    private void checkGeometry() {
        if (buildings.size() != dimX) {
            throw new IllegalStateException("Multi-building '" + name + "' declares dimx " + dimX
                    + " and dimz " + dimZ + " but its grid holds " + buildings.size()
                    + " row(s) of the " + dimX + " that dimx declares");
        }
        for (int x = 0; x < dimX; x++) {
            int actual = buildings.get(x).size();
            if (actual != dimZ) {
                throw new IllegalStateException("Multi-building '" + name + "' declares dimx "
                        + dimX + " and dimz " + dimZ + " but row " + x + " holds " + actual
                        + " of the " + dimZ + " entries that dimz declares");
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
