package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.config.Presets;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.worldgen.lost.regassets.*;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.CitySettings;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import org.apache.commons.lang3.tuple.Pair;


import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;

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
     * The stuff-by-tag index and whether it was actually loaded, as one value.
     * <p>
     * They are one record rather than two fields because {@code Stuff.generateStuff} has to consult
     * both - an empty index alone is legitimate (a pack may ship no stuff files) and so is a
     * momentarily unloaded registry - and two volatile fields cannot be read together. Reading them
     * separately is a genuine tear, not a theoretical one: volatile accesses are totally ordered
     * consistently with program order, so with a writer doing {@code map} then {@code loaded} and a
     * reader doing {@code map} then {@code loaded}, the order {@code W_map, R_map, R_loaded,
     * W_loaded} is legal - the reader sees the emptied index and the stale {@code loaded == true},
     * concludes nothing is wrong, and generates a silently undecorated chunk. Reversing the writes
     * only narrows that: a reader that saw the emptied index would then be guaranteed to see
     * {@code loaded == false} <em>from that reset</em>, but a subsequent {@link #load} completing
     * between the two reads restores {@code true} and the same silent outcome. One field and one
     * read removes the interleaving instead of making it unlikely.
     *
     * @param byTag  each tag's stuff, sorted; unmodifiable, and never touched after publication
     * @param loaded whether {@link #load} completed and {@link #reset} has not run since
     */
    public record StuffIndex(Map<String, List<StuffObject>> byTag, boolean loaded) {
    }

    /**
     * Replaced wholesale, never filled in place.
     * <p>
     * The index used to be a {@code ConcurrentHashMap} filled with {@code putAll}, which is not one
     * operation: a worldgen worker reading it while {@link #load} was midway through that
     * {@code putAll} could see some tags present and others still missing, and a missing tag is
     * silent - {@code Stuff.generateStuff} simply places nothing for it. Now {@code load} builds the
     * whole thing privately and publishes it with the single volatile write below.
     */
    private static volatile StuffIndex stuffIndex = new StuffIndex(Map.of(), false);

    // Guards load() and reset() against each other and against concurrent loads. load() is no
    // longer confined to the server thread: CityFeature.getDimensionInfo calls it, and generation
    // runs on the parallel worldgen worker pool (see the "No lock" note in CityFeature).
    private static final Object LOAD_LOCK = new Object();

    // Volatile, and written after the map it guards is filled, so the fast path out of
    // loadPredefinedStuff() can skip the lock entirely once the work is done.
    private static volatile boolean loadedPredefined = false;

    /**
     * The index and its loaded flag, taken together in one volatile read.
     * <p>
     * Callers that need both must call this <em>once</em> and use what it returns - asking again, or
     * combining it with {@link #isLoaded()}, is the tear the record exists to prevent. Holding the
     * value also makes a caller that walks several tags immune to a {@link #reset()} landing mid-walk:
     * everything under the reference is immutable, so the walk sees one index throughout.
     */
    public static StuffIndex stuffIndex() {
        return stuffIndex;
    }

    /**
     * Whether {@link #load} has completed and not been undone by {@link #reset} since.
     * <p>
     * For callers that need <em>only</em> this. Anything that also needs the index must take
     * {@link #stuffIndex()} once and read {@link StuffIndex#loaded()} off it, or it is back to
     * reading a pair that can tear.
     */
    public static boolean isLoaded() {
        return stuffIndex.loaded();
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
            Presets.reset();
            // One write, so no reader can catch the index emptied but still flagged loaded.
            stuffIndex = new StuffIndex(Map.of(), false);
            loadedPredefined = false;
        }
    }

    /**
     * Resolves every registered asset in the ten registries listed below, plus every city style
     * anything can select; see the {@code CITYSTYLES} note further down for why that one is by
     * reachability rather than wholesale.
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
     * <b>{@code CITYSTYLES} is swept by reachability rather than wholesale</b>; see
     * {@link #loadReachableCityStyles} for what "reachable" enumerates and why it is that list.
     * Resolving <em>every</em> registered city style would refuse the world over a file that is not
     * wrong, because requiredness is a property of the end of a chain and a city style may exist only
     * to be extended - the bundled {@code citystyles/citystyle_config.json} declares a street width
     * and nothing else, and is complete only through {@code citystyle_common}, which extends it.
     * Reachability answers that on its own: a root nothing names is never resolved, and a style
     * something can actually select is validated here like every other registry's.
     * {@code PREDEFINED_CITIES} is absent for an unrelated reason: {@link #loadPredefinedStuff}
     * loads it, under the same lock.
     * <p>
     * <b>Two orderings below are requirements, not tidiness, and both are silent when broken.</b>
     * {@link RegistryAssetRegistry#getIterable()} returns the <em>resolved cache</em> -
     * {@code assets.values()}, which {@link RegistryAssetRegistry#reset()} empties and only
     * {@code loadAll} refills - so any step that iterates a registry must run after that registry's
     * {@code loadAll}, and iterating early yields an empty sweep rather than an error:
     * <ul>
     * <li>{@code WORLDSTYLES.loadAll} before {@link #loadReachableCityStyles}, whose first act is
     *     {@code for (WorldStyle style : WORLDSTYLES.getIterable())}. Moved after it, route 1 - the
     *     world style {@code citystyles} selectors, the primary route and the one most of that
     *     method's doc is about - sweeps zero city styles. Nothing fails: the world loads, the suite
     *     stays green and the digests stay clean, and a city style whose chain leaves a required
     *     field undeclared goes back to throwing from a worldgen worker mid-generation, which is the
     *     exact failure this method exists to prevent.</li>
     * <li>{@code STUFF.loadAll} before the {@code groupStuffByTag(STUFF.getIterable())} below, for
     *     the same reason: reordered, the tag index is published empty but flagged loaded, and
     *     {@code Stuff.generateStuff} silently places nothing.</li>
     * </ul>
     * Only {@code VARIANTS} before {@code PALETTES} is tidiness: compiling a palette entry that names
     * a variant reaches into that registry, but through {@code get}, which resolves on demand.
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
        if (stuffIndex.loaded()) {
            return;
        }
        synchronized (LOAD_LOCK) {
            if (stuffIndex.loaded()) {
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
            loadReachableCityStyles(level);
            STUFF.loadAll(level);
            // One write, publishing a map that is complete before the reference escapes - and
            // publishing the flag with it, so the two can never be observed out of step.
            stuffIndex = new StuffIndex(groupStuffByTag(STUFF.getIterable()), true);
        }
    }

    /**
     * Resolves every city style anything in the pack can select, so its required fields are checked
     * at world load like every other registry's rather than from a worldgen worker mid-generation.
     * <p>
     * <b>Four routes name a city style; this sweep covers the three that live in the registries.</b>
     * They were traced from the two {@code CITYSTYLES} lookup sites in the mod, {@code City.java:282}
     * and {@code BuildingInfo.java:374}, back to where the name they are handed comes from:
     * <ol>
     * <li>a world style's {@code citystyles} selectors, which is what {@code City.getCityStyleInt}
     *     and {@code getCityStyleForCityCenter} draw from;</li>
     * <li>a preset's {@code cities.cityStyleAlternative}, taken when the city factor falls below
     *     {@code CITY_STYLE_THRESHOLD} ({@code City.java:242,258}) - the route by which the bundled
     *     {@code citystyle_border} is generated, and one no world style mentions;</li>
     * <li>a predefined city's {@code citystyle} ({@code City.java:209-210}), which overrides the
     *     roll for that city entirely;</li>
     * <li><b>the per-world preset <em>overrides</em></b> - the same {@code cityStyleAlternative}
     *     field, but arriving as override JSON rather than from a registry entry, so nothing here
     *     can see it. It is user-reachable: {@code CITY_STYLE_ALTERNATIVE} is a free-text box in the
     *     customization GUI ({@code Settings.java:466}), published or restored through
     *     {@code UrbexData}, and applied by {@code CityFeature.getDimensionInfo}. That one is
     *     checked where it is built, by the {@link #requireCityStyle} call in {@code CityFeature}
     *     right after {@code Presets.applyOverrides} - once per dimension, before any chunk work.</li>
     * </ol>
     * {@code BuildingInfo}'s lookup adds no fifth route: the name it passes is the winner of a
     * {@code Counter} over ids of styles the surrounding chunks already resolved through routes 1-4.
     * A <em>new</em> selection path has to add itself to this list or to {@code CityFeature}, or it
     * goes back to failing mid-generation; {@code CityStyleLookupSitesTest} fails the build if a new
     * lookup <em>site</em> appears, though it cannot see a new name <em>source</em> feeding an
     * existing one.
     * <p>
     * Presets and predefined cities are read one registry entry at a time rather than through their
     * own {@code extends} resolution, deliberately: a value declared anywhere in a chain is a value
     * some asset in that chain resolves to, so the union over raw entries is the same set of city
     * styles, and reading them raw means a broken preset chain is not turned into a world-load
     * failure by a sweep that is about city styles.
     * <p>
     * A null level resolves nothing, matching {@code RegistryAssetRegistry.loadAll}: the caller in
     * {@code AssetsLoadedBeforeGenerationTest} hands one in deliberately.
     */
    private static void loadReachableCityStyles(CommonLevelAccessor level) {
        if (level == null) {
            return;
        }
        for (WorldStyle style : WORLDSTYLES.getIterable()) {
            for (Pair<Predicate<Holder<Biome>>, Pair<Float, String>> selector : style.cityStyleSelectors()) {
                requireCityStyle(level, selector.getRight().getRight(), style.getId());
            }
        }
        Registry<PresetRE> presets = level.registryAccess().lookupOrThrow(CustomRegistries.PRESET_REGISTRY_KEY);
        for (PresetRE preset : presets) {
            preset.cities().flatMap(CitySettings::cityStyleAlternative).ifPresent(
                    name -> requireCityStyle(level, name, presets.getKey(preset)));
        }
        Registry<PredefinedCityRE> cities =
                level.registryAccess().lookupOrThrow(CustomRegistries.PREDEFINEDCITIES_REGISTRY_KEY);
        for (PredefinedCityRE city : cities) {
            requireCityStyle(level, city.getCityStyle(), cities.getKey(city));
        }
    }

    /**
     * Resolves one city style by name, so a name that does not exist or whose chain leaves a
     * required field undeclared fails here rather than from a worldgen worker.
     * <p>
     * The only place {@code CITYSTYLES} is looked up outside generation, and the entry point route 4
     * uses: {@code CityFeature.getDimensionInfo} calls it for the preset it has just built, which is
     * the only form of the alternative-style setting no registry sweep can reach. Blank is not a
     * name - {@code Preset.CITY_STYLE_ALTERNATIVE} starts as {@code ""} and most presets never set
     * it - so it resolves nothing rather than failing.
     * <p>
     * {@code selectedBy} only shapes the message, and it is the point of the wrapper: the underlying
     * error names the city style and the field it is missing, which on its own leaves an author
     * hunting for what pulled that style in.
     */
    public static void requireCityStyle(CommonLevelAccessor level, @Nullable String name, Object selectedBy) {
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            CITYSTYLES.getOrThrow(level, name);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "City style '" + name + "', selected by '" + selectedBy + "', cannot be used: "
                            + e.getMessage(), e);
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
