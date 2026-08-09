package dev.krona.urbex.setup;


import dev.krona.urbex.Urbex;
import dev.krona.urbex.worldgen.LostCityFeature;
import dev.krona.urbex.worldgen.LostCitySphereFeature;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.feature.Feature;


public class Registration {

    private static LostCityFeature lostCityFeature;
    private static LostCitySphereFeature lostCitySphereFeature;

    public static LostCityFeature lostCityFeature() {
        return lostCityFeature;
    }

    public static LostCitySphereFeature lostCitySphereFeature() {
        return lostCitySphereFeature;
    }

    public static void init() {
        lostCityFeature = Registry.register(BuiltInRegistries.FEATURE,
                Identifier.fromNamespaceAndPath(Urbex.MODID, "city"), new LostCityFeature());
        lostCitySphereFeature = Registry.register(BuiltInRegistries.FEATURE,
                Identifier.fromNamespaceAndPath(Urbex.MODID, "spheres"), new LostCitySphereFeature());
    }

    public static final Identifier LOSTCITY = Identifier.fromNamespaceAndPath(Urbex.MODID, "city");

    public static final ResourceKey<DimensionType> DIMENSION_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE, LOSTCITY);
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, LOSTCITY);
}
