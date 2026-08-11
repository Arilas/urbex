package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.config.Presets;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.worldgen.lost.regassets.*;
import net.minecraft.world.level.CommonLevelAccessor;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
        STUFF_BY_TAG.putAll(groupStuffByTag(STUFF.getIterable()));
        loaded = true;
    }

    /**
     * Files every stuff object under each tag it declares, each tag's list sorted by
     * {@link StuffObject#getId()}.
     * <p>
     * The sort is not cosmetic. {@code Stuff.generateStuff} walks these lists assigning a
     * {@code stuffOrdinal}, and that ordinal is the RNG slot address every placement attempt of
     * that decoration draws from. The source order is {@code STUFF.getIterable()}, which is a
     * {@code ConcurrentHashMap}'s values - i.e. {@code Identifier} hash-bucket order - so without
     * this, which of two decorations sharing a tag is ordinal 0 was decided by
     * {@code hash("urbex:chains")} versus {@code hash("urbex:cobweb")}, and renaming either file
     * would have relocated both throughout the world. {@code Identifier}'s natural order (path,
     * then namespace) is the same total order {@code MultiChunk}'s city-style sort uses, so one
     * asset kind is not ordered two ways in two places.
     * <p>
     * Returns immutable lists: worldgen workers read {@code STUFF_BY_TAG} while the server thread
     * is in {@code load}, and an immutable list's contents are safely published by the map write
     * alone. (The former {@code CopyOnWriteArrayList} bought thread safety for an
     * {@code add} that no longer happens after publication.)
     */
    static Map<String, List<StuffObject>> groupStuffByTag(Iterable<StuffObject> stuff) {
        Map<String, List<StuffObject>> byTag = new TreeMap<>();
        stuff.forEach(object -> object.getSettings().getTags().forEach(
                tag -> byTag.computeIfAbsent(tag, t -> new ArrayList<>()).add(object)));
        byTag.replaceAll((tag, list) -> {
            list.sort(Comparator.comparing(StuffObject::getId));
            return List.copyOf(list);
        });
        return byTag;
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
