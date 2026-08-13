package dev.krona.urbex.setup;


import dev.krona.urbex.Urbex;
import dev.krona.urbex.worldgen.CityFeature;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;


/**
 * Registers the configured-feature entry point.
 * <p>
 * It used to keep the registered instance in a static field so the carver-stage mixin could find
 * something to call. Nothing reads that field now: {@link CityFeature#generateFromPipeline} is
 * static, because it holds no state and reads everything from the level's published runtime (issue
 * #129). What is left is the registration itself, which the feature needs to be nameable from a
 * datapack.
 */
public class Registration {

    public static void init() {
        Registry.register(BuiltInRegistries.FEATURE,
                Identifier.fromNamespaceAndPath(Urbex.MODID, "city"), new CityFeature());
    }
}
