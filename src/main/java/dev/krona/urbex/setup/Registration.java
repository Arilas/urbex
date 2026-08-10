package dev.krona.urbex.setup;


import dev.krona.urbex.Urbex;
import dev.krona.urbex.worldgen.CityFeature;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;


public class Registration {

    private static CityFeature cityFeature;

    public static CityFeature cityFeature() {
        return cityFeature;
    }

    public static void init() {
        cityFeature = Registry.register(BuiltInRegistries.FEATURE,
                Identifier.fromNamespaceAndPath(Urbex.MODID, "city"), new CityFeature());
    }
}
