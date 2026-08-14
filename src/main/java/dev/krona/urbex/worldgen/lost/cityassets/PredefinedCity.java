package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.PredefinedCityDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedStreet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

public class PredefinedCity {

    private final Identifier name;
    private final ResourceKey<Level> dimension;
    private final int chunkX;
    private final int chunkZ;
    private final int radius;
    private final String cityStyle;
    private final List<PredefinedBuilding> predefinedBuildings = new ArrayList<>();
    private final List<PredefinedStreet> predefinedStreets = new ArrayList<>();

    /**
     * Builds a fully resolved predefined city from its {@code extends} chain, root first: each
     * scalar takes the value of the last entry that declares one, so a second city can be the
     * first one moved by declaring nothing but its own {@code chunkx}/{@code chunkz}. The building
     * and street lists go through {@link Mergeable} so a declared list replaces unless it opts into
     * appending.
     */
    public PredefinedCity(Identifier id, List<PredefinedCityDefinition> chainRootFirst) {
        name = id;
        String declaredDimension = null;
        Integer declaredChunkX = null;
        Integer declaredChunkZ = null;
        Integer declaredRadius = null;
        String declaredCityStyle = null;
        for (PredefinedCityDefinition object : chainRootFirst) {
            if (object.getDimension() != null) {
                declaredDimension = object.getDimension();
            }
            if (object.getChunkX() != null) {
                declaredChunkX = object.getChunkX();
            }
            if (object.getChunkZ() != null) {
                declaredChunkZ = object.getChunkZ();
            }
            if (object.getRadius() != null) {
                declaredRadius = object.getRadius();
            }
            if (object.getCityStyle() != null) {
                declaredCityStyle = object.getCityStyle();
            }
        }
        dimension = ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(Resolved.require(declaredDimension, name, "dimension")));
        chunkX = Resolved.require(declaredChunkX, name, "chunkx");
        chunkZ = Resolved.require(declaredChunkZ, name, "chunkz");
        radius = Resolved.require(declaredRadius, name, "radius");
        cityStyle = declaredCityStyle;
        for (PredefinedCityDefinition object : chainRootFirst) {
            if (object.getPredefinedBuildings() != null) {
                Mergeable.apply(predefinedBuildings, object.getPredefinedBuildings());
            }
            if (object.getPredefinedStreets() != null) {
                Mergeable.apply(predefinedStreets, object.getPredefinedStreets());
            }
        }
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public int getRadius() {
        return radius;
    }

    public String getCityStyle() {
        return cityStyle;
    }

    public List<PredefinedBuilding> getPredefinedBuildings() {
        return predefinedBuildings;
    }

    public List<PredefinedStreet> getPredefinedStreets() {
        return predefinedStreets;
    }

    /** The fully-qualified id, e.g. {@code "urbex:spawncity"}. */
    public String getName() {
        return name.toString();
    }

    public Identifier getId() {
        return name;
    }
}
