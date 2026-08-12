package dev.krona.urbex.setup;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.worldgen.lost.regassets.*;
import dev.krona.urbex.worldgen.lost.regassets.StuffSettingsDefinition;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;

public class CustomRegistries {

    public static final ResourceKey<Registry<BuildingDefinition>> BUILDING_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Urbex.MODID, "buildings"));

    public static final ResourceKey<Registry<PaletteDefinition>> PALETTE_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Urbex.MODID, "palettes"));

    public static final ResourceKey<Registry<BuildingPartDefinition>> PART_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Urbex.MODID, "parts"));

    public static final ResourceKey<Registry<StyleDefinition>> STYLE_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Urbex.MODID, "styles"));

    public static final ResourceKey<Registry<ConditionDefinition>> CONDITIONS_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Urbex.MODID, "conditions"));

    public static final ResourceKey<Registry<CityStyleDefinition>> CITYSTYLES_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Urbex.MODID, "citystyles"));

    public static final ResourceKey<Registry<MultiBuildingDefinition>> MULTIBUILDINGS_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Urbex.MODID, "multibuildings"));

    public static final ResourceKey<Registry<VariantDefinition>> VARIANTS_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Urbex.MODID, "variants"));

    public static final ResourceKey<Registry<WorldStyleDefinition>> WORLDSTYLES_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Urbex.MODID, "worldstyles"));

    public static final ResourceKey<Registry<PredefinedCityDefinition>> PREDEFINEDCITIES_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Urbex.MODID, "predefinedcities"));

    public static final ResourceKey<Registry<ScatteredDefinition>> SCATTERED_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Urbex.MODID, "scattered"));

    public static final ResourceKey<Registry<StuffSettingsDefinition>> STUFF_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Urbex.MODID, "stuff"));

    public static final ResourceKey<Registry<PresetDefinition>> PRESET_REGISTRY_KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Urbex.MODID, "presets"));

    public static void init() {
        // Fabric: register the datapack ("dynamic") registries. These are server-side only
        // (not synced to clients), matching the NeoForge DataPackRegistryEvent behavior
        // (no network codec was provided there either).
        DynamicRegistries.register(BUILDING_REGISTRY_KEY, BuildingDefinition.CODEC);
        DynamicRegistries.register(PALETTE_REGISTRY_KEY, PaletteDefinition.CODEC);
        DynamicRegistries.register(PART_REGISTRY_KEY, BuildingPartDefinition.CODEC);
        DynamicRegistries.register(STYLE_REGISTRY_KEY, StyleDefinition.CODEC);
        DynamicRegistries.register(CONDITIONS_REGISTRY_KEY, ConditionDefinition.CODEC);
        DynamicRegistries.register(CITYSTYLES_REGISTRY_KEY, CityStyleDefinition.CODEC);
        DynamicRegistries.register(MULTIBUILDINGS_REGISTRY_KEY, MultiBuildingDefinition.CODEC);
        DynamicRegistries.register(VARIANTS_REGISTRY_KEY, VariantDefinition.CODEC);
        DynamicRegistries.register(WORLDSTYLES_REGISTRY_KEY, WorldStyleDefinition.CODEC);
        DynamicRegistries.register(PREDEFINEDCITIES_REGISTRY_KEY, PredefinedCityDefinition.CODEC);
        DynamicRegistries.register(SCATTERED_REGISTRY_KEY, ScatteredDefinition.CODEC);
        DynamicRegistries.register(STUFF_REGISTRY_KEY, StuffSettingsDefinition.CODEC);
        DynamicRegistries.register(PRESET_REGISTRY_KEY, PresetDefinition.CODEC);
    }
}
