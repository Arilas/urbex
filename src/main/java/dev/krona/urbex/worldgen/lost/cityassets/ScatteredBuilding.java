package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.ScatteredRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
     */
    public ScatteredBuilding(List<ScatteredRE> chainRootFirst) {
        name = chainRootFirst.get(chainRootFirst.size() - 1).getRegistryName();
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

    public String getName() {
        return DataTools.toName(name);
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

        private static final Map<String, TerrainHeight> BY_NAME = Arrays.stream(values()).collect(Collectors.toMap(TerrainHeight::getSerializedName, (v) -> v));

        TerrainHeight(String name) {
            this.name = name;
        }

        private final String name;

        public static final TerrainHeight byName(String name) {
            return BY_NAME.get(name);
        }

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

        private static final Map<String, TerrainFix> BY_NAME = Arrays.stream(values()).collect(Collectors.toMap(TerrainFix::getSerializedName, (v) -> v));

        TerrainFix(String name) {
            this.name = name;
        }

        private final String name;

        public static final TerrainFix byName(String name) {
            return BY_NAME.get(name);
        }

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
