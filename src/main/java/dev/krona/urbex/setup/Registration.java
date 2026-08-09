package dev.krona.urbex.setup;


import dev.krona.urbex.Urbex;
import dev.krona.urbex.worldgen.CityFeature;
import dev.krona.urbex.worldgen.SphereFeature;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.feature.Feature;


public class Registration {

    private static CityFeature cityFeature;
    private static SphereFeature sphereFeature;

    public static CityFeature cityFeature() {
        return cityFeature;
    }

    public static SphereFeature sphereFeature() {
        return sphereFeature;
    }

    public static void init() {
        cityFeature = Registry.register(BuiltInRegistries.FEATURE,
                Identifier.fromNamespaceAndPath(Urbex.MODID, "city"), new CityFeature());
        sphereFeature = Registry.register(BuiltInRegistries.FEATURE,
                Identifier.fromNamespaceAndPath(Urbex.MODID, "spheres"), new SphereFeature());
    }

    public static final Identifier CITY_ID = Identifier.fromNamespaceAndPath(Urbex.MODID, "city");

    public static final ResourceKey<DimensionType> DIMENSION_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE, CITY_ID);
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, CITY_ID);
}
