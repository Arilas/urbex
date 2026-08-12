package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.setup.CustomRegistries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

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
        AssetIndex<Variant> variants = AssetStage.compileAll(access,
                CustomRegistries.VARIANTS_REGISTRY_KEY, (id, chain) -> new Variant(chain), diagnostics);
        // The access, not the variants index, until Palette's dependency is narrowed: Palette still
        // reaches AssetRegistries.VARIANTS for a `variant` entry. Both resolve the same registry, so
        // the compiled result is identical; see the design note for the sequencing.
        AssetIndex<Palette> palettes = AssetStage.compileAll(access,
                CustomRegistries.PALETTE_REGISTRY_KEY, (id, chain) -> new Palette(access, chain), diagnostics);
        AssetIndex<Condition> conditions = AssetStage.compileAll(access,
                CustomRegistries.CONDITIONS_REGISTRY_KEY, (id, chain) -> new Condition(chain), diagnostics);
        AssetIndex<Style> styles = AssetStage.compileAll(access,
                CustomRegistries.STYLE_REGISTRY_KEY, (id, chain) -> new Style(chain), diagnostics);
        AssetIndex<BuildingPart> parts = AssetStage.compileAll(access,
                CustomRegistries.PART_REGISTRY_KEY, (id, chain) -> new BuildingPart(access, chain), diagnostics);
        AssetIndex<Building> buildings = AssetStage.compileAll(access,
                CustomRegistries.BUILDING_REGISTRY_KEY, (id, chain) -> new Building(access, chain), diagnostics);
        AssetIndex<MultiBuilding> multiBuildings = AssetStage.compileAll(access,
                CustomRegistries.MULTIBUILDINGS_REGISTRY_KEY, (id, chain) -> new MultiBuilding(chain), diagnostics);
        AssetIndex<ScatteredBuilding> scattered = AssetStage.compileAll(access,
                CustomRegistries.SCATTERED_REGISTRY_KEY, (id, chain) -> new ScatteredBuilding(chain), diagnostics);
        AssetIndex<WorldStyle> worldStyles = AssetStage.compileAll(access,
                CustomRegistries.WORLDSTYLES_REGISTRY_KEY, (id, chain) -> new WorldStyle(chain), diagnostics);
        // Wholesale, unlike AssetRegistries.load's reachability sweep. The sweep existed because
        // resolving every registered city style refuses a world over a file that is not wrong - an
        // abstract base that is complete only through its children. Aggregated diagnostics change
        // that calculus: an unreachable base that fails to resolve is a line in a report, and the
        // caller decides. Reachability then becomes a question about which failures are fatal, which
        // is #56's remaining half, not about which files get compiled.
        AssetIndex<CityStyle> cityStyles = AssetStage.compileAll(access,
                CustomRegistries.CITYSTYLES_REGISTRY_KEY, (id, chain) -> new CityStyle(chain), diagnostics);
        AssetIndex<PredefinedCity> predefinedCities = AssetStage.compileAll(access,
                CustomRegistries.PREDEFINEDCITIES_REGISTRY_KEY, (id, chain) -> new PredefinedCity(chain), diagnostics);
        AssetIndex<StuffObject> stuff = AssetStage.compileAll(access,
                CustomRegistries.STUFF_REGISTRY_KEY, (id, chain) -> new StuffObject(chain), diagnostics);

        AssetSnapshot snapshot = new AssetSnapshot(variants, palettes, conditions, styles, parts,
                buildings, multiBuildings, scattered, worldStyles, cityStyles, predefinedCities,
                stuff, groupStuffByTag(stuff));
        Urbex.getLogger().info("Compiled {} Urbex assets ({} problem(s))",
                snapshot.totalAssets(), diagnostics.size());
        return snapshot;
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
    static SortedMap<String, List<StuffObject>> groupStuffByTag(AssetIndex<StuffObject> stuff) {
        SortedMap<String, List<StuffObject>> byTag = new TreeMap<>();
        for (StuffObject object : stuff.all()) {
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
