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

    public static final RegistryAssetRegistry<Variant, VariantRE> VARIANTS = new RegistryAssetRegistry<>(CustomRegistries.VARIANTS_REGISTRY_KEY, Variant::new);
    public static final RegistryAssetRegistry<Condition, ConditionRE> CONDITIONS = new RegistryAssetRegistry<>(CustomRegistries.CONDITIONS_REGISTRY_KEY, Condition::new);
    public static final RegistryAssetRegistry<WorldStyle, WorldStyleRE> WORLDSTYLES = new RegistryAssetRegistry<>(CustomRegistries.WORLDSTYLES_REGISTRY_KEY, WorldStyle::new);
    public static final RegistryAssetRegistry<CityStyle, CityStyleRE> CITYSTYLES = new RegistryAssetRegistry<>(CustomRegistries.CITYSTYLES_REGISTRY_KEY, CityStyle::new);
    public static final RegistryAssetRegistry<BuildingPart, BuildingPartRE> PARTS = new RegistryAssetRegistry<>(CustomRegistries.PART_REGISTRY_KEY, BuildingPart::new);
    public static final RegistryAssetRegistry<Building, BuildingRE> BUILDINGS = new RegistryAssetRegistry<>(CustomRegistries.BUILDING_REGISTRY_KEY, Building::new);
    public static final RegistryAssetRegistry<MultiBuilding, MultiBuildingRE> MULTI_BUILDINGS = new RegistryAssetRegistry<>(CustomRegistries.MULTIBUILDINGS_REGISTRY_KEY, MultiBuilding::new);
    public static final RegistryAssetRegistry<Style, StyleRE> STYLES = new RegistryAssetRegistry<>(CustomRegistries.STYLE_REGISTRY_KEY, Style::new);
    public static final RegistryAssetRegistry<Palette, PaletteRE> PALETTES = new RegistryAssetRegistry<>(CustomRegistries.PALETTE_REGISTRY_KEY, Palette::new);
    public static final RegistryAssetRegistry<ScatteredBuilding, ScatteredRE> SCATTERED = new RegistryAssetRegistry<>(CustomRegistries.SCATTERED_REGISTRY_KEY, ScatteredBuilding::new);
    public static final RegistryAssetRegistry<PredefinedCity, PredefinedCityRE> PREDEFINED_CITIES = new RegistryAssetRegistry<>(CustomRegistries.PREDEFINEDCITIES_REGISTRY_KEY, PredefinedCity::new);
    public static final RegistryAssetRegistry<StuffObject, StuffSettingsRE> STUFF = new RegistryAssetRegistry<>(CustomRegistries.STUFF_REGISTRY_KEY, StuffObject::new);

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
        SCATTERED.reset();
        PREDEFINED_CITIES.reset();
        STUFF.reset();
        STUFF_BY_TAG.clear();
        Presets.reset();
        loaded = false;
        loadedPredefined = false;
    }

    /**
     * Resolves every registered asset in every registry that has required fields.
     * <p>
     * Requiredness is checked when a chain is resolved rather than when a file is decoded (see
     * {@link Resolved}), so an asset that is never resolved is never validated. Everything below
     * would otherwise be built lazily on first lookup from a worldgen worker thread, which would
     * turn "this file is missing 'terrainfix'" from a refusal to load the world into an exception
     * mid-generation - and would never raise it at all for an asset no world happens to reference.
     * Resolving them all here keeps the rule the design states: fail at load, naming the file.
     * <p>
     * This does mean a broken third-party asset fails the world even when the player never selects
     * it. That is the intended trade, not a side effect.
     * <p>
     * Order is deliberate only in one place: {@code VARIANTS} before {@code PALETTES}, because
     * compiling a palette entry that names a variant reaches into that registry. The lookup is
     * lazy, so this is tidiness rather than a requirement.
     */
    public static void load(CommonLevelAccessor level) {
        if (loaded) {
            return;
        }
        VARIANTS.loadAll(level);
        PALETTES.loadAll(level);
        CONDITIONS.loadAll(level);
        STYLES.loadAll(level);
        PARTS.loadAll(level);
        BUILDINGS.loadAll(level);
        MULTI_BUILDINGS.loadAll(level);
        SCATTERED.loadAll(level);
        WORLDSTYLES.loadAll(level);
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
