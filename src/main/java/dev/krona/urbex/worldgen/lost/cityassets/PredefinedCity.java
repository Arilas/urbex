package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.PredefinedCityRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
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

    public PredefinedCity(PredefinedCityRE object) {
        name = object.getRegistryName();
        dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(object.getDimension()));
        chunkX = object.getChunkX();
        chunkZ = object.getChunkZ();
        radius = object.getRadius();
        cityStyle = object.getCityStyle();
        if (object.getPredefinedBuildings() != null) {
            predefinedBuildings.addAll(object.getPredefinedBuildings());
        }
        if (object.getPredefinedStreets() != null) {
            predefinedStreets.addAll(object.getPredefinedStreets());
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
