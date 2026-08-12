package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.HighwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.ObjectSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.PartSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.RailwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.ScatteredReference;
import dev.krona.urbex.worldgen.lost.regassets.data.ScatteredSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.StreetParts;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks every asset a world can reach and reports the references that name nothing.
 *
 * <p>Cross-asset references are held in the compiled model as <strong>names</strong>, not as objects:
 * a city style's {@code buildings} selector, a world style's highway wiring, a building's
 * {@code parts}. Generation resolves them one at a time, on whichever chunk first needs one - there
 * are around forty {@code assets().parts().getOrThrow(...)} sites in {@code CityGenerator},
 * {@code Highways}, {@code Railways} and {@code Scattered}. A building naming a part that no datapack
 * registers is therefore an exception from a worldgen worker, on a chunk somewhere out in the world,
 * rather than a message about a file (issue #56).</p>
 *
 * <p>This resolves all of them once, at compile time, and reports them together through
 * {@link AssetDiagnostics}. Nothing here reads a registry, a level or a server - it reads the
 * finished {@link AssetSnapshot} and nothing else, which is what #128 made possible.</p>
 *
 * <h2>Reachability, and why it is not "every registered asset"</h2>
 *
 * <p>The walk starts from what a world can actually select and follows references outward. That is
 * the same rule {@link AssetCompiler#promoteReachableCityStyleProblems} already applies to city
 * styles, and for the same reason: requiredness is a property of the end of a chain. A part that only
 * an unreachable city style names is not a broken pack, and sweeping every registered asset instead
 * would refuse worlds over files that are never used - which is precisely the mistake an earlier
 * draft of the compiler made with city styles.</p>
 *
 * <p><strong>Fatal, not a warning.</strong> Every reference below already throws from a worker today.
 * Reporting it at load makes it earlier, not stricter. The exception is the optional street
 * components, which generation reaches with {@code getOrWarn} - a pack may legitimately leave one
 * out - so an absent one of those is left alone here too.</p>
 */
public final class AssetGraph {

    private final AssetSnapshot assets;
    private final AssetDiagnostics diagnostics;
    /** Every (kind, id) already walked, so a diamond in the graph is visited once and a cycle ends. */
    private final Set<String> seen = new HashSet<>();
    private final Deque<Runnable> pending = new ArrayDeque<>();

    private AssetGraph(AssetSnapshot assets, AssetDiagnostics diagnostics) {
        this.assets = assets;
        this.diagnostics = diagnostics;
    }

    /**
     * Reports every dangling reference reachable from {@code reachableCityStyles} and from the
     * snapshot's world styles and predefined cities.
     *
     * @param reachableCityStyles the city styles something can select, already resolved. Handed over
     *                            as objects rather than as names on purpose: the compiler works the
     *                            set out from the world styles, the presets and the predefined
     *                            cities - a route only the registries expose - and taking it
     *                            pre-resolved means this class cannot become a fifth place that
     *                            looks a city style up by name (see {@code CityStyleLookupSitesTest},
     *                            which exists because such a site reverts silently to failing from a
     *                            worldgen worker). One that did not compile is not here, and is
     *                            already reported by the compiler.
     */
    public static void validate(AssetSnapshot assets, Collection<CityStyle> reachableCityStyles,
                                AssetDiagnostics diagnostics) {
        AssetGraph graph = new AssetGraph(assets, diagnostics);
        for (WorldStyle style : assets.worldStyles().all()) {
            graph.walkWorldStyle(style);
        }
        for (PredefinedCity city : assets.predefinedCities().all()) {
            graph.walkPredefinedCity(city);
        }
        for (CityStyle style : reachableCityStyles) {
            graph.walkCityStyle(style);
        }
        graph.drain();
    }

    /** Depth is unbounded in a datapack, so the walk is a queue rather than recursion. */
    private void drain() {
        while (!pending.isEmpty()) {
            pending.poll().run();
        }
    }

    private void walkWorldStyle(WorldStyle style) {
        if (!first("worldstyle", style.getId())) {
            return;
        }
        Identifier owner = style.getId();
        style(owner, style.getOutsideStyle(), "outsidestyle");
        // citystyles are deliberately not followed from here: the compiler already resolved which
        // ones anything can select, and hands them to validate() as the walk's other root.
        ScatteredSettings scattered = style.getScatteredSettings();
        if (scattered != null) {
            for (ScatteredReference reference : scattered.getList()) {
                scattered(owner, reference.getName(), "scattered.list");
            }
        }
        PartSelector selector = style.getPartSelector();
        HighwayParts highways = selector.highwayParts();
        parts(owner, highways.tunnel(), "parts.highways.tunnel");
        parts(owner, highways.open(), "parts.highways.open");
        parts(owner, highways.bridge(), "parts.highways.bridge");
        parts(owner, highways.tunnelBi(), "parts.highways.tunnelbi");
        parts(owner, highways.openBi(), "parts.highways.openbi");
        parts(owner, highways.bridgeBi(), "parts.highways.bridgebi");
        RailwayParts railways = selector.railwayParts();
        parts(owner, railways.stationUnderground(), "parts.railways.stationunderground");
        parts(owner, railways.stationOpen(), "parts.railways.stationopen");
        parts(owner, railways.stationUndergroundStairs(), "parts.railways.stationundergroundstairs");
        parts(owner, railways.stationStaircase(), "parts.railways.stationstaircase");
        parts(owner, railways.stationStaircaseSurface(), "parts.railways.stationstaircasesurface");
        parts(owner, railways.rails3Split(), "parts.railways.rails3split");
        parts(owner, railways.railsDown2(), "parts.railways.railsdown2");
        parts(owner, railways.railsDown1(), "parts.railways.railsdown1");
        parts(owner, railways.railsBend(), "parts.railways.railsbend");
        parts(owner, railways.railsFlat(), "parts.railways.railsflat");
        parts(owner, railways.railsHorizontal(), "parts.railways.railshorizontal");
        parts(owner, railways.railsHorizontalEnd(), "parts.railways.railshorizontalend");
        parts(owner, railways.railsHorizontalWater(), "parts.railways.railshorizontalwater");
        parts(owner, railways.railsVertical(), "parts.railways.railsvertical");
        parts(owner, railways.railsVerticalWater(), "parts.railways.railsverticalwater");
        parts(owner, railways.stationOpenRoof(), "parts.railways.stationopenroof");
    }

    private void walkCityStyle(CityStyle style) {
        if (!first("citystyle", style.getId())) {
            return;
        }
        Identifier owner = style.getId();
        style(owner, style.getStyle(), "style");
        for (ObjectSelector selector : style.selectorList(CityStyle.Sel.BUILDING)) {
            building(owner, selector.value(), "selectors.buildings");
        }
        for (ObjectSelector selector : style.selectorList(CityStyle.Sel.MULTI_BUILDING)) {
            multiBuilding(owner, selector.value(), "selectors.multibuildings");
        }
        selectorParts(owner, style, CityStyle.Sel.BRIDGE, "selectors.bridges");
        selectorParts(owner, style, CityStyle.Sel.LARGE_BRIDGE, "selectors.largebridges");
        selectorParts(owner, style, CityStyle.Sel.PARK, "selectors.parks");
        selectorParts(owner, style, CityStyle.Sel.FOUNTAIN, "selectors.fountains");
        selectorParts(owner, style, CityStyle.Sel.STAIR, "selectors.stairs");
        selectorParts(owner, style, CityStyle.Sel.FRONT, "selectors.fronts");
        selectorParts(owner, style, CityStyle.Sel.RAIL_DUNGEON, "selectors.raildungeons");
        streetParts(owner, style.getStreetParts(), "streetblocks.parts");
        streetParts(owner, style.getLargeStreetParts(), "streetblocks.largeparts");
        streetParts(owner, style.getTertiaryStreetParts(), "streetblocks.tertiaryparts");
    }

    private void selectorParts(Identifier owner, CityStyle style, CityStyle.Sel kind, String field) {
        for (ObjectSelector selector : style.selectorList(kind)) {
            part(owner, selector.value(), field);
        }
    }

    private void streetParts(Identifier owner, @Nullable StreetParts family, String field) {
        if (family == null) {
            return;
        }
        parts(owner, family.straight(), field + ".straight");
        parts(owner, family.end(), field + ".end");
        parts(owner, family.bend(), field + ".bend");
        parts(owner, family.t(), field + ".t");
        parts(owner, family.none(), field + ".none");
        parts(owner, family.all(), field + ".all");
        parts(owner, family.connector(), field + ".connector");
        parts(owner, family.stair(), field + ".stair");
    }

    private void walkBuilding(Building building) {
        if (!first("building", building.getId())) {
            return;
        }
        for (String name : building.partNames()) {
            part(building.getId(), name, "parts");
        }
    }

    private void walkMultiBuilding(MultiBuilding multi) {
        if (!first("multibuilding", multi.getId())) {
            return;
        }
        if (multi.getBuildingSet() != null) {
            for (String name : multi.getBuildingSet()) {
                building(multi.getId(), name, "buildings");
            }
        }
    }

    private void walkScattered(ScatteredBuilding scattered) {
        if (!first("scattered", scattered.getId())) {
            return;
        }
        // Either arm may be absent: a scattered entry declares 'buildings' or 'multibuilding', and
        // the constructor requires one of the two rather than both.
        if (scattered.getBuildings() != null) {
            for (String name : scattered.getBuildings()) {
                building(scattered.getId(), name, "buildings");
            }
        }
        multiBuilding(scattered.getId(), scattered.getMultibuilding(), "multibuilding");
    }

    private void walkPredefinedCity(PredefinedCity city) {
        if (!first("predefinedcity", city.getId())) {
            return;
        }
        if (city.getPredefinedBuildings() == null) {
            return;
        }
        for (PredefinedBuilding building : city.getPredefinedBuildings()) {
            if (building.multi()) {
                multiBuilding(city.getId(), building.building(), "buildings");
            } else {
                building(city.getId(), building.building(), "buildings");
            }
        }
    }

    // ------------------------------------------------------------------ one reference

    private void parts(Identifier owner, @Nullable List<String> names, String field) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            part(owner, name, field);
        }
    }

    private void part(Identifier owner, @Nullable String name, String field) {
        resolve(owner, name, field, assets.parts(), this::walkPart);
    }

    private void building(Identifier owner, @Nullable String name, String field) {
        resolve(owner, name, field, assets.buildings(), this::walkBuilding);
    }

    private void multiBuilding(Identifier owner, @Nullable String name, String field) {
        resolve(owner, name, field, assets.multiBuildings(), this::walkMultiBuilding);
    }

    private void scattered(Identifier owner, @Nullable String name, String field) {
        resolve(owner, name, field, assets.scattered(), this::walkScattered);
    }

    private void style(Identifier owner, @Nullable String name, String field) {
        resolve(owner, name, field, assets.styles(), style -> { });
    }

    /** A part is a leaf for this walk: its {@code refpalette} was resolved when it compiled. */
    private void walkPart(BuildingPart part) {
    }

    /**
     * Resolves one name, reporting it if it names nothing and queueing what it names if it does.
     * <p>
     * An unparseable name is reported as absent rather than thrown: {@code DataTools.fromName}
     * refuses a bare name deliberately (a reference is written fully qualified), and an author who
     * wrote one needs that in the same report as everything else, not as the exception that ended
     * the walk.
     */
    private <T> void resolve(Identifier owner, @Nullable String name, String field,
                             AssetIndex<T> index, java.util.function.Consumer<T> walk) {
        if (name == null || name.isBlank()) {
            return;
        }
        Identifier id;
        try {
            id = DataTools.fromName(name);
        } catch (RuntimeException e) {
            diagnostics.record(index.registry(), owner,
                    "'" + field + "' names '" + name + "', which is not a valid asset id: " + e.getMessage());
            return;
        }
        T found = index.get(id);
        if (found == null) {
            diagnostics.record(index.registry(), owner,
                    "'" + field + "' names '" + name + "', which no loaded datapack registers");
            return;
        }
        pending.add(() -> walk.accept(found));
    }

    /** True the first time this asset is reached, so a diamond costs one visit and a cycle ends. */
    private boolean first(String kind, Identifier id) {
        return seen.add(kind + "/" + id);
    }
}
