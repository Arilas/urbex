package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.ScatteredRE;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class ScatteredBuilding {

    private final Identifier name;
    private final List<String> buildings;
    private final String multibuilding;
    private final ScatteredBuilding.TerrainHeight terrainheight;
    private final ScatteredBuilding.TerrainFix terrainfix;
    private final int heightoffset;

    /**
     * Builds a fully resolved scattered building from its {@code extends} chain, root first: each
     * scalar takes the value of the last entry that declares one, and the building list goes
     * through {@link Mergeable} so a declared list replaces unless it opts into appending.
     * <p>
     * {@code terrainheight} and {@code terrainfix} are required of the chain rather than of each
     * file, so a variant that only swaps its building list inherits both.
     * <p>
     * {@code buildings} and {@code multibuilding} are required as a <em>pair</em>: the resolved
     * chain must leave at least one, and neither is required on its own, which is the one shape
     * {@link Resolved#require} cannot state. Left unchecked, a chain declaring neither loaded and
     * then threw from {@code Scattered.generate} the first time the entry was placed.
     */
    public ScatteredBuilding(Identifier id, List<ScatteredRE> chainRootFirst) {
        name = id;
        List<String> declaredBuildings = new ArrayList<>();
        boolean anyBuildings = false;
        String multibuilding = null;
        int heightoffset = 0;
        TerrainHeight terrainheight = null;
        TerrainFix terrainfix = null;
        for (ScatteredRE object : chainRootFirst) {
            if (object.getBuildings() != null) {
                Mergeable.apply(declaredBuildings, object.getBuildings());
                anyBuildings = true;
            }
            if (object.getMultibuilding() != null) {
                multibuilding = object.getMultibuilding();
            }
            if (object.getHeightoffset() != null) {
                heightoffset = object.getHeightoffset();
            }
            if (object.getTerrainheight() != null) {
                terrainheight = object.getTerrainheight();
            }
            if (object.getTerrainfix() != null) {
                terrainfix = object.getTerrainfix();
            }
        }
        this.buildings = anyBuildings ? List.copyOf(declaredBuildings) : null;
        this.multibuilding = multibuilding;
        if (this.buildings == null && this.multibuilding == null) {
            throw new IllegalStateException("'" + name + "' declares neither 'buildings' nor "
                    + "'multibuilding', and neither does anything it extends");
        }
        this.terrainheight = Resolved.require(terrainheight, name, "terrainheight");
        this.terrainfix = Resolved.require(terrainfix, name, "terrainfix");
        this.heightoffset = heightoffset;
    }

    @Nullable
    public List<String> getBuildings() {
        return buildings;
    }

    @Nullable
    public String getMultibuilding() {
        return multibuilding;
    }

    public TerrainHeight getTerrainheight() {
        return terrainheight;
    }

    public TerrainFix getTerrainfix() {
        return terrainfix;
    }

    public int getHeightoffset() {
        return heightoffset;
    }

    /** The fully-qualified id, e.g. {@code "urbex:oilrig"}. */
    public String getName() {
        return name.toString();
    }

    public Identifier getId() {
        return name;
    }

    public enum TerrainHeight implements StringRepresentable {
        LOWEST("lowest"),
        AVERAGE("average"),
        HIGHEST("highest"),
        OCEAN("ocean")
        ;

        TerrainHeight(String name) {
            this.name = name;
        }

        private final String name;

        @Override
        public String toString() {
            return name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public enum TerrainFix implements StringRepresentable {
        NONE("none"),               // Do nothing with the terrain
        CLEAR("clear"),             // Clear from generation point upwards
        REPEATSLICE("repeatslice")  // Repeat the bottom slice downwards until it hits a solid block
        ;

        TerrainFix(String name) {
            this.name = name;
        }

        private final String name;

        @Override
        public String toString() {
            return name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
