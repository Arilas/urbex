package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.config.Presets;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.worldgen.lost.regassets.*;
import net.minecraft.world.level.CommonLevelAccessor;


import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class AssetRegistries {

    public static final RegistryAssetRegistry<Variant, VariantRE> VARIANTS = new RegistryAssetRegistry<>(CustomRegistries.VARIANTS_REGISTRY_KEY, chain -> new Variant(chain.get(chain.size() - 1)));
    public static final RegistryAssetRegistry<Condition, ConditionRE> CONDITIONS = new RegistryAssetRegistry<>(CustomRegistries.CONDITIONS_REGISTRY_KEY, chain -> new Condition(chain.get(chain.size() - 1)));
    public static final RegistryAssetRegistry<WorldStyle, WorldStyleRE> WORLDSTYLES = new RegistryAssetRegistry<>(CustomRegistries.WORLDSTYLES_REGISTRY_KEY, chain -> new WorldStyle(chain.get(chain.size() - 1)));
    public static final RegistryAssetRegistry<CityStyle, CityStyleRE> CITYSTYLES = new RegistryAssetRegistry<>(CustomRegistries.CITYSTYLES_REGISTRY_KEY, CityStyle::new);
    public static final RegistryAssetRegistry<BuildingPart, BuildingPartRE> PARTS = new RegistryAssetRegistry<>(CustomRegistries.PART_REGISTRY_KEY, chain -> new BuildingPart(chain.get(chain.size() - 1)));
    public static final RegistryAssetRegistry<Building, BuildingRE> BUILDINGS = new RegistryAssetRegistry<>(CustomRegistries.BUILDING_REGISTRY_KEY, chain -> new Building(chain.get(chain.size() - 1)));
    public static final RegistryAssetRegistry<MultiBuilding, MultiBuildingRE> MULTI_BUILDINGS = new RegistryAssetRegistry<>(CustomRegistries.MULTIBUILDINGS_REGISTRY_KEY, chain -> new MultiBuilding(chain.get(chain.size() - 1)));
    public static final RegistryAssetRegistry<Style, StyleRE> STYLES = new RegistryAssetRegistry<>(CustomRegistries.STYLE_REGISTRY_KEY, chain -> new Style(chain.get(chain.size() - 1)));
    public static final RegistryAssetRegistry<Palette, PaletteRE> PALETTES = new RegistryAssetRegistry<>(CustomRegistries.PALETTE_REGISTRY_KEY, chain -> new Palette(chain.get(chain.size() - 1)));
    public static final RegistryAssetRegistry<ScatteredBuilding, ScatteredRE> SCATTERED = new RegistryAssetRegistry<>(CustomRegistries.SCATTERED_REGISTRY_KEY, chain -> new ScatteredBuilding(chain.get(chain.size() - 1)));
    public static final RegistryAssetRegistry<PredefinedCity, PredefinedCityRE> PREDEFINED_CITIES = new RegistryAssetRegistry<>(CustomRegistries.PREDEFINEDCITIES_REGISTRY_KEY, chain -> new PredefinedCity(chain.get(chain.size() - 1)));
    public static final RegistryAssetRegistry<StuffObject, StuffSettingsRE> STUFF = new RegistryAssetRegistry<>(CustomRegistries.STUFF_REGISTRY_KEY, chain -> new StuffObject(chain.get(chain.size() - 1)));

    public static final Map<String, List<StuffObject>> STUFF_BY_TAG = new ConcurrentHashMap<>();

    // Volatile, and written after the maps they guard are filled: load() is called from the server
    // thread on every tick, while worker threads are reading the registries during generation.
    private static volatile boolean loaded = false;
    private static volatile boolean loadedPredefined = false;

    public static void reset() {
        VARIANTS.reset();
        CONDITIONS.reset();
        WORLDSTYLES.reset();
        PARTS.reset();
        BUILDINGS.reset();
        CITYSTYLES.reset();
        MULTI_BUILDINGS.reset();
        STYLES.reset();
        PALETTES.reset();
        PREDEFINED_CITIES.reset();
        STUFF.reset();
        STUFF_BY_TAG.clear();
        Presets.reset();
        loaded = false;
        loadedPredefined = false;
    }

    public static void load(CommonLevelAccessor level) {
        if (loaded) {
            return;
        }
        PARTS.loadAll(level);
        BUILDINGS.loadAll(level);
        STUFF.loadAll(level);
        STUFF.getIterable().forEach(stuff -> stuff.getSettings().getTags().forEach(tag -> {
            List<StuffObject> list = STUFF_BY_TAG.get(tag);
            if (list == null) {
                List<StuffObject> fresh = new CopyOnWriteArrayList<>();
                List<StuffObject> raced = STUFF_BY_TAG.putIfAbsent(tag, fresh);
                list = raced != null ? raced : fresh;
            }
            list.add(stuff);
        }));
        loaded = true;
    }

    public static void loadPredefinedStuff(CommonLevelAccessor level) {
        if (loadedPredefined) {
            return;
        }
        if (level == null) {
            // Nothing to load without a level (the world-creation preview, chiefly - its
            // NullDimensionInfo.getWorld() is always null). Don't latch the "loaded" flag on a
            // no-op: a real level arriving later must still get its predefined cities (#67).
            return;
        }
        PREDEFINED_CITIES.loadAll(level);
        loadedPredefined = true;
    }
}
