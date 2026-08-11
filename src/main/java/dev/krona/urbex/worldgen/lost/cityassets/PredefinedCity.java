package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.PredefinedCityRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
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
     * Builds a fully resolved predefined city from its {@code extends} chain, root first. Its
     * scalars are all required, so the leaf's win; the building and street lists go through
     * {@link Mergeable} so a declared list replaces unless it opts into appending.
     */
    public PredefinedCity(List<PredefinedCityRE> chainRootFirst) {
        PredefinedCityRE leaf = chainRootFirst.get(chainRootFirst.size() - 1);
        name = leaf.getRegistryName();
        dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(leaf.getDimension()));
        chunkX = leaf.getChunkX();
        chunkZ = leaf.getChunkZ();
        radius = leaf.getRadius();
        cityStyle = leaf.getCityStyle();
        for (PredefinedCityRE object : chainRootFirst) {
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

    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }
}
