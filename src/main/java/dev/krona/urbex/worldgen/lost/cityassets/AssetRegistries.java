package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.config.Presets;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.worldgen.lost.regassets.*;
import net.minecraft.world.level.CommonLevelAccessor;


import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

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

    /**
     * The stuff-by-tag index, replaced wholesale rather than filled in place.
     * <p>
     * It used to be a {@code ConcurrentHashMap} filled with {@code putAll}, which is not one
     * operation: a worldgen worker calling {@link #stuffForTag} while {@link #load} was midway
     * through that {@code putAll} could see some tags present and others still missing, and a
     * missing tag is silent - {@code Stuff.generateStuff} simply places nothing for it. Now
     * {@code load} builds the whole index privately and publishes it with the single volatile write
     * below, so a reader sees either the previous index or the complete new one and never a state
     * in between. The map handed over is unmodifiable and is never touched again after the write.
     */
    private static volatile Map<String, List<StuffObject>> stuffByTag = Map.of();

    // Guards load() and reset() against each other and against concurrent loads. load() is no
    // longer confined to the server thread: CityFeature.getDimensionInfo calls it, and generation
    // runs on the parallel worldgen worker pool (see the "No lock" note in CityFeature).
    private static final Object LOAD_LOCK = new Object();

    // Volatile, and written after the maps they guard are filled, so the fast path out of load()
    // and loadPredefinedStuff() can skip the lock entirely once the work is done.
    private static volatile boolean loaded = false;
    private static volatile boolean loadedPredefined = false;

    /**
     * Every stuff object filed under {@code tag}, or null when the tag has none (or when nothing is
     * loaded yet). The returned list is immutable and safe to iterate from a worker thread.
     */
    @Nullable
    public static List<StuffObject> stuffForTag(String tag) {
        return stuffByTag.get(tag);
    }

    /**
     * Whether {@link #load} has completed and not been undone by {@link #reset} since.
     * <p>
     * Read on the generation path by {@code Stuff.generateStuff}, which is the one consumer whose
     * failure mode is silent: every other registry re-resolves lazily through
     * {@link RegistryAssetRegistry#get}, but the stuff-by-tag index has no lazy rebuild, so
     * generating while this is false writes an undecorated chunk and saves it. False here during
     * generation is never legitimate after Task 5c - it means something called {@link #reset}
     * mid-generation - which is why the check is on this flag rather than on the index being empty
     * (a pack that ships no stuff files legitimately has an empty index).
     */
    public static boolean isLoaded() {
        return loaded;
    }

    public static void reset() {
        synchronized (LOAD_LOCK) {
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
            stuffByTag = Map.of();
            Presets.reset();
            loaded = false;
            loadedPredefined = false;
        }
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
     * <p>
     * Called from two places, for two different reasons. {@code ServerEventHandlers} calls it from
     * {@code ServerLevelEvents.LOAD} so the validation above happens while the world is loading and
     * a broken pack refuses the world instead of failing later; {@code CityFeature.getDimensionInfo}
     * calls it on the generation path so that no chunk can ever generate against an unloaded
     * registry, whenever generation starts and whatever has reset the registries since. The second
     * caller means this runs on worldgen worker threads, hence the lock: exactly one thread does the
     * work and the rest wait for it, rather than several racing through {@code loadAll} and building
     * the tag index from a half-filled {@code STUFF}.
     */
    public static void load(CommonLevelAccessor level) {
        if (loaded) {
            return;
        }
        synchronized (LOAD_LOCK) {
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
            // One write, publishing a map that is complete before the reference escapes.
            stuffByTag = groupStuffByTag(STUFF.getIterable());
            loaded = true;
        }
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
     * Returns immutable lists inside an unmodifiable map: the whole structure is finished before
     * {@link #load} publishes it, and nothing writes to it afterwards, so worldgen workers can read
     * it without locking. (The former {@code CopyOnWriteArrayList} bought thread safety for an
     * {@code add} that no longer happens after publication.)
     */
    static SortedMap<String, List<StuffObject>> groupStuffByTag(Iterable<StuffObject> stuff) {
        SortedMap<String, List<StuffObject>> byTag = new TreeMap<>();
        stuff.forEach(object -> object.getSettings().getTags().forEach(
                tag -> byTag.computeIfAbsent(tag, t -> new ArrayList<>()).add(object)));
        byTag.replaceAll((tag, list) -> {
            list.sort(Comparator.comparing(StuffObject::getId));
            return List.copyOf(list);
        });
        return Collections.unmodifiableSortedMap(byTag);
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
        // Same lock as load(): City's predefined maps are built from worker threads too, and the
        // latch below must not be set by one thread while another is still inside loadAll.
        synchronized (LOAD_LOCK) {
            if (loadedPredefined) {
                return;
            }
            PREDEFINED_CITIES.loadAll(level);
            loadedPredefined = true;
        }
    }
}
