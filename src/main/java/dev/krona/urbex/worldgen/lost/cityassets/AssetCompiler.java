package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.worldgen.lost.regassets.PredefinedCityRE;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.CitySettings;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Builds one {@link AssetSnapshot} from a world's frozen registries.
 *
 * <p>Runs once per world load, on the thread that loads it, and publishes nothing until every stage
 * has finished. Compare {@code AssetRegistries.load}, which filled twelve static maps in place and
 * latched a flag when it was done: a reader arriving midway through that saw some registries
 * populated and others empty, and an empty registry is silent (issue #128).</p>
 *
 * <h2>Stage order is a requirement, not tidiness</h2>
 *
 * <p>Each stage may read the indexes already built, and several must. A stage moved above its
 * dependency does not fail - it reads an empty index and compiles assets that quietly reference
 * nothing, which is the failure mode {@code AssetRegistries.load}'s own ordering comments were
 * written about. The order below is the dependency order:</p>
 *
 * <ul>
 *   <li><b>variants</b> first: palettes resolve a {@code variant} entry against them.</li>
 *   <li><b>palettes</b> before <b>styles</b>, <b>parts</b> and <b>buildings</b>: a style's
 *       {@code randompalettes}, and a part's or building's {@code refpalette}, name one.</li>
 *   <li><b>citystyles</b> before <b>predefinedcities</b>: a predefined city names a city style.</li>
 *   <li><b>stuff</b> before the tag index derived from it.</li>
 * </ul>
 *
 * <p>Requiredness is checked per stage as each chain resolves (see {@link Resolved}), and every
 * failure is recorded rather than thrown, so a pack with problems in four stages produces one report
 * instead of four world loads (issue #56).</p>
 */
public final class AssetCompiler {

    private AssetCompiler() {
    }

    /**
     * Compiles everything, or reports everything that stopped it.
     *
     * @param diagnostics collects per-asset failures; the caller decides whether to refuse the world
     * @return the snapshot. Incomplete when {@code diagnostics} is non-empty - a caller that ignores
     *         the diagnostics and publishes anyway gets exactly the partially-compiled view this
     *         class exists to make impossible, so don't.
     */
    public static AssetSnapshot compile(RegistryAccess access, AssetDiagnostics diagnostics) {
        // The block registry the whole compilation resolves against, taken once from the world
        // being loaded. Every block string below reaches it through a parameter: resolution used
        // to pick a registry for itself, from a static server reference that may or may not have
        // been populated by the time it ran (issues #60, #128).
        HolderLookup<Block> blockLookup = access.lookupOrThrow(Registries.BLOCK);
        AssetIndex<Variant> variants = AssetStage.compileAll(access,
                CustomRegistries.VARIANTS_REGISTRY_KEY, (id, chain) -> new Variant(id, blockLookup, chain), diagnostics);
        AssetIndex<Palette> palettes = AssetStage.compileAll(access,
                CustomRegistries.PALETTE_REGISTRY_KEY, (id, chain) -> new Palette(id, blockLookup, variants, chain), diagnostics);
        AssetIndex<Condition> conditions = AssetStage.compileAll(access,
                CustomRegistries.CONDITIONS_REGISTRY_KEY, (id, chain) -> new Condition(id, chain), diagnostics);
        AssetIndex<Style> styles = AssetStage.compileAll(access,
                CustomRegistries.STYLE_REGISTRY_KEY, (id, chain) -> new Style(id, palettes, chain), diagnostics);
        AssetIndex<BuildingPart> parts = AssetStage.compileAll(access,
                CustomRegistries.PART_REGISTRY_KEY, (id, chain) -> new BuildingPart(id, blockLookup, variants, palettes, chain), diagnostics);
        AssetIndex<Building> buildings = AssetStage.compileAll(access,
                CustomRegistries.BUILDING_REGISTRY_KEY, (id, chain) -> new Building(id, blockLookup, variants, palettes, chain), diagnostics);
        AssetIndex<MultiBuilding> multiBuildings = AssetStage.compileAll(access,
                CustomRegistries.MULTIBUILDINGS_REGISTRY_KEY, (id, chain) -> new MultiBuilding(id, chain), diagnostics);
        AssetIndex<ScatteredBuilding> scattered = AssetStage.compileAll(access,
                CustomRegistries.SCATTERED_REGISTRY_KEY, (id, chain) -> new ScatteredBuilding(id, chain), diagnostics);
        AssetIndex<WorldStyle> worldStyles = AssetStage.compileAll(access,
                CustomRegistries.WORLDSTYLES_REGISTRY_KEY, (id, chain) -> new WorldStyle(id, chain), diagnostics);
        // City styles are the one kind where "failed to compile" is not the same as "wrong", so they
        // are compiled into a local report and only the reachable failures are promoted into the
        // caller's. Requiredness is a property of the end of a chain, and a city style may exist only
        // to be extended: the bundled citystyle_config declares a street width and nothing else, and
        // is complete only through citystyle_common, which extends it. Resolving every registered
        // style and failing on any of them refuses a world over a file that is not wrong - which is
        // exactly what an earlier draft of this compiler did.
        AssetDiagnostics cityStyleProblems = new AssetDiagnostics();
        AssetIndex<CityStyle> cityStyles = AssetStage.compileAll(access,
                CustomRegistries.CITYSTYLES_REGISTRY_KEY, (id, chain) -> new CityStyle(id, chain), cityStyleProblems);
        AssetIndex<PredefinedCity> predefinedCities = AssetStage.compileAll(access,
                CustomRegistries.PREDEFINEDCITIES_REGISTRY_KEY, (id, chain) -> new PredefinedCity(id, chain), diagnostics);
        AssetIndex<StuffObject> stuff = AssetStage.compileAll(access,
                CustomRegistries.STUFF_REGISTRY_KEY, (id, chain) -> new StuffObject(id, chain), diagnostics);

        promoteReachableCityStyleProblems(access, worldStyles, cityStyles, cityStyleProblems, diagnostics);

        AssetSnapshot snapshot = new AssetSnapshot(variants, palettes, conditions, styles, parts,
                buildings, multiBuildings, scattered, worldStyles, cityStyles, predefinedCities,
                stuff, groupStuffByTag(stuff.all()));
        Urbex.getLogger().info("Compiled {} Urbex assets ({} problem(s))",
                snapshot.totalAssets(), diagnostics.size());
        return snapshot;
    }

    /**
     * Turns "this city style did not compile" into a load error only when something can select it.
     *
     * <p>Three routes name a city style from inside the registries, and this walks all three: a world
     * style's {@code citystyles} selectors, a preset's {@code cities.cityStyleAlternative}, and a
     * predefined city's {@code citystyle}. A fourth exists and cannot be seen from here - the
     * per-world override that arrives as JSON through {@code UrbexData} - and is checked by
     * {@code DimensionRuntime.create} against the compiled index instead.</p>
     *
     * <p>Presets and predefined cities are read one raw registry entry at a time rather than through
     * their own {@code extends} resolution: a value declared anywhere in a chain is a value some asset
     * in that chain resolves to, so the union over raw entries is the same set of city styles, and
     * reading them raw means a broken preset chain is not turned into a city-style failure.</p>
     */
    private static void promoteReachableCityStyleProblems(RegistryAccess access,
                                                          AssetIndex<WorldStyle> worldStyles,
                                                          AssetIndex<CityStyle> cityStyles,
                                                          AssetDiagnostics candidates,
                                                          AssetDiagnostics fatal) {
        Set<Identifier> reachable = new HashSet<>();
        for (WorldStyle style : worldStyles.all()) {
            for (Pair<Predicate<Holder<Biome>>, Pair<Float, String>> selector : style.cityStyleSelectors()) {
                reachable.add(DataTools.fromName(selector.getRight().getRight()));
            }
        }
        for (PresetRE preset : access.lookupOrThrow(CustomRegistries.PRESET_REGISTRY_KEY)) {
            preset.cities().flatMap(CitySettings::cityStyleAlternative)
                    .filter(name -> !name.isBlank())
                    .ifPresent(name -> reachable.add(DataTools.fromName(name)));
        }
        for (PredefinedCityRE city : access.lookupOrThrow(CustomRegistries.PREDEFINEDCITIES_REGISTRY_KEY)) {
            if (city.getCityStyle() != null && !city.getCityStyle().isBlank()) {
                reachable.add(DataTools.fromName(city.getCityStyle()));
            }
        }
        for (AssetDiagnostics.Problem problem : candidates.problems()) {
            if (problem.asset() != null && reachable.contains(problem.asset())) {
                fatal.record(problem.registry(), problem.asset(), problem.message());
            } else {
                // Not an error: a root nothing names is never resolved, exactly as before.
                Urbex.getLogger().debug("Unreachable Urbex city style did not resolve, which is legal: {}",
                        problem);
            }
        }
        for (Identifier name : reachable) {
            if (cityStyles.get(name) == null && candidates.problems().stream()
                    .noneMatch(problem -> name.equals(problem.asset()))) {
                fatal.record(cityStyles.registry(), name,
                        "is selected by a world style, preset or predefined city, but no loaded "
                                + "datapack registers it");
            }
        }
    }

    /**
     * Files every stuff object under each tag it declares, each tag's list sorted by
     * {@link StuffObject#getId()}.
     * <p>
     * The sort is not cosmetic. {@code Stuff.generateStuff} walks these lists assigning a
     * {@code stuffOrdinal}, and that ordinal is the RNG slot address every placement attempt of that
     * decoration draws from. The source order is an {@link AssetIndex}'s values - a {@code Map.copyOf}
     * of a {@code HashMap}, i.e. {@code Identifier} hash order - so without this, which of two
     * decorations sharing a tag is ordinal 0 would be decided by {@code hash("urbex:chains")} versus
     * {@code hash("urbex:cobweb")}, and renaming either file would relocate both throughout the
     * world. {@code Identifier}'s natural order (path, then namespace) is the same total order
     * {@code MultiChunk}'s city-style sort uses, so one asset kind is not ordered two ways in two
     * places.
     */
    static SortedMap<String, List<StuffObject>> groupStuffByTag(Iterable<StuffObject> stuff) {
        SortedMap<String, List<StuffObject>> byTag = new TreeMap<>();
        for (StuffObject object : stuff) {
            for (String tag : object.getSettings().getTags()) {
                byTag.computeIfAbsent(tag, t -> new ArrayList<>()).add(object);
            }
        }
        byTag.replaceAll((tag, list) -> {
            list.sort(Comparator.comparing(StuffObject::getId));
            return List.copyOf(list);
        });
        return Collections.unmodifiableSortedMap(byTag);
    }
}
