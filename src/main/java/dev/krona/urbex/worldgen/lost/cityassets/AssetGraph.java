package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.data.ConditionPart;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelection;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.HighwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.ObjectSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.PartRef;
import dev.krona.urbex.worldgen.lost.regassets.data.PartSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.RailwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.ScatteredReference;
import dev.krona.urbex.worldgen.lost.regassets.data.ScatteredSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.StreetParts;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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
    /**
     * Every (part, style, building, road) already checked. Separate from {@link #seen} on purpose: a
     * missing reference is reported once per asset, but a character is a question about a
     * <em>usage</em>, and the same part under two styles is two questions with two answers.
     */
    private final Set<String> checkedUsages = new HashSet<>();
    /** Every dangling reference already reported, so four paths to one typo are one line. */
    private final Set<String> reported = new HashSet<>();
    /**
     * The reachable city styles by id, so a world style can pair its road wiring with the styles that
     * world can actually put under it. Built from the collection handed in - this is not a lookup
     * into the snapshot's city-style index, which would make this class a name-resolution site.
     */
    private final Map<Identifier, CityStyle> reachable = new HashMap<>();
    private final Deque<Runnable> pending = new ArrayDeque<>();

    /** Every namespace something loaded registers Urbex assets in; see {@link ReferenceProvider}. */
    private final Set<String> assetNamespaces = new HashSet<>();

    private AssetGraph(AssetSnapshot assets, AssetDiagnostics diagnostics) {
        this.assets = assets;
        this.diagnostics = diagnostics;
        for (Identifier id : assets.parts().ids()) {
            assetNamespaces.add(id.getNamespace());
        }
        for (Identifier id : assets.buildings().ids()) {
            assetNamespaces.add(id.getNamespace());
        }
    }

    /**
     * Reports every dangling reference reachable from {@code reachableCityStyles} and from the
     * snapshot's world styles and predefined cities.
     *
     * @param reachableCityStyles the city styles something can select, already resolved. Handed over
     *                            as objects rather than as names on purpose: the compiler works the
     *                            set out from world-style base/edge selectors and explicit
     *                            predefined-city styles - routes only the registries expose - and
     *                            taking it pre-resolved means this class cannot become another place
     *                            that looks a city style up by name (see {@code CityStyleLookupSitesTest},
     *                            which exists because such a site reverts silently to failing from a
     *                            worldgen worker). One that did not compile is not here, and is
     *                            already reported by the compiler.
     */
    public static void validate(AssetSnapshot assets, Collection<CityStyle> reachableCityStyles,
                                AssetDiagnostics diagnostics) {
        AssetGraph graph = new AssetGraph(assets, diagnostics);
        for (CityStyle style : reachableCityStyles) {
            graph.reachable.put(style.getId(), style);
        }
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
        // A road part is placed in whatever chunk the road runs through, so the palette it is drawn
        // against is that chunk's city style - not the world style's. Pairing with each city style
        // this world style can select is the precise answer; checking against every reachable style
        // would report combinations no world can produce, which is the direction that costs someone a
        // world. The style-less usage is what carries the geometry check when a world style selects
        // nothing that resolved.
        List<Style> roadStyles = new ArrayList<>();
        roadStyles.add(null);
        for (Pair<Predicate<Holder<Biome>>, Pair<Float, CityStyleSelection>> selected : style.cityStyleSelectors()) {
            CityStyleSelection selection = selected.getRight().getRight();
            addRoadStyle(roadStyles, selection.citystyle());
            selection.edge().ifPresent(edge -> addRoadStyle(roadStyles, edge.citystyle()));
        }
        PartSelector selector = style.getPartSelector();
        HighwayParts highways = selector.highwayParts();
        roadParts(owner, highways.tunnel(), "parts.highways.tunnel", roadStyles);
        roadParts(owner, highways.open(), "parts.highways.open", roadStyles);
        roadParts(owner, highways.bridge(), "parts.highways.bridge", roadStyles);
        roadParts(owner, highways.tunnelBi(), "parts.highways.tunnelbi", roadStyles);
        roadParts(owner, highways.openBi(), "parts.highways.openbi", roadStyles);
        roadParts(owner, highways.bridgeBi(), "parts.highways.bridgebi", roadStyles);
        RailwayParts railways = selector.railwayParts();
        roadParts(owner, railways.stationUnderground(), "parts.railways.stationunderground", roadStyles);
        roadParts(owner, railways.stationOpen(), "parts.railways.stationopen", roadStyles);
        roadParts(owner, railways.stationUndergroundStairs(), "parts.railways.stationundergroundstairs", roadStyles);
        roadParts(owner, railways.stationStaircase(), "parts.railways.stationstaircase", roadStyles);
        roadParts(owner, railways.stationStaircaseSurface(), "parts.railways.stationstaircasesurface", roadStyles);
        roadParts(owner, railways.rails3Split(), "parts.railways.rails3split", roadStyles);
        roadParts(owner, railways.railsDown2(), "parts.railways.railsdown2", roadStyles);
        roadParts(owner, railways.railsDown1(), "parts.railways.railsdown1", roadStyles);
        roadParts(owner, railways.railsBend(), "parts.railways.railsbend", roadStyles);
        roadParts(owner, railways.railsFlat(), "parts.railways.railsflat", roadStyles);
        roadParts(owner, railways.railsHorizontal(), "parts.railways.railshorizontal", roadStyles);
        roadParts(owner, railways.railsHorizontalEnd(), "parts.railways.railshorizontalend", roadStyles);
        roadParts(owner, railways.railsHorizontalWater(), "parts.railways.railshorizontalwater", roadStyles);
        roadParts(owner, railways.railsVertical(), "parts.railways.railsvertical", roadStyles);
        roadParts(owner, railways.railsVerticalWater(), "parts.railways.railsverticalwater", roadStyles);
        roadParts(owner, railways.stationOpenRoof(), "parts.railways.stationopenroof", roadStyles);
    }

    private void addRoadStyle(List<Style> roadStyles, String cityStyleName) {
        CityStyle city = reachable.get(DataTools.fromName(cityStyleName));
        if (city != null) {
            Style resolved = assets.styles().get(city.getStyle());
            if (resolved != null) {
                roadStyles.add(resolved);
            }
        }
    }

    private void walkCityStyle(CityStyle style) {
        if (!first("citystyle", style.getId())) {
            return;
        }
        Identifier owner = style.getId();
        style(owner, style.getStyle(), "style");
        Style palette = assets.styles().get(style.getStyle());
        if (palette != null) {
            PaletteCharacterCheck.checkCityStyle(style, palette, diagnostics);
            // Once per style rather than once per city style that names it: MODEL.062 is a property of
            // the style's own randompalettes, and two city styles sharing a style would otherwise
            // report one alias twice - which is the noise LOAD.013's > Why measures at 45 warnings.
            if (first("style-aliases", palette.getId())) {
                PaletteCharacterCheck.checkAliases(palette, diagnostics);
            }
            for (List<Pair<Float, Palette>> group : palette.paletteChoices()) {
                for (Pair<Float, Palette> choice : group) {
                    walkPalette(choice.getRight());
                }
            }
        }
        for (ObjectSelector selector : style.selectorList(CityStyle.Sel.BUILDING)) {
            building(owner, selector.value(), "selectors.buildings", palette);
        }
        for (ObjectSelector selector : style.selectorList(CityStyle.Sel.MULTI_BUILDING)) {
            multiBuilding(owner, selector.value(), "selectors.multibuildings", palette);
        }
        selectorParts(owner, style, CityStyle.Sel.BRIDGE, "selectors.bridges", palette);
        selectorParts(owner, style, CityStyle.Sel.LARGE_BRIDGE, "selectors.largebridges", palette);
        selectorParts(owner, style, CityStyle.Sel.PARK, "selectors.parks", palette);
        selectorParts(owner, style, CityStyle.Sel.FOUNTAIN, "selectors.fountains", palette);
        selectorParts(owner, style, CityStyle.Sel.STAIR, "selectors.stairs", palette);
        selectorParts(owner, style, CityStyle.Sel.FRONT, "selectors.fronts", palette);
        selectorParts(owner, style, CityStyle.Sel.RAIL_DUNGEON, "selectors.raildungeons", palette);
        streetParts(owner, style.getStreetParts(), "streetblocks.parts", palette);
        streetParts(owner, style.getLargeStreetParts(), "streetblocks.largeparts", palette);
        streetParts(owner, style.getTertiaryStreetParts(), "streetblocks.tertiaryparts", palette);
    }

    private void selectorParts(Identifier owner, CityStyle style, CityStyle.Sel kind, String field,
                               @Nullable Style palette) {
        for (ObjectSelector selector : style.selectorList(kind)) {
            // Not a road: bridges, parks, fountains and the rest are placed inside a chunk at a
            // computed offset, so their size is theirs to choose.
            part(owner, selector.value(), field, usage(palette, null, false, field, owner));
        }
    }

    /** One road usage per style the part can be drawn against, so the geometry is checked once. */
    private void roadParts(Identifier owner, @Nullable List<String> names, String field,
                           List<Style> roadStyles) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            for (Style roadStyle : roadStyles) {
                part(owner, name, field, new PartUsage(roadStyle, null, true, field, owner));
            }
        }
    }

    private void streetParts(Identifier owner, @Nullable StreetParts family, String field,
                            @Nullable Style palette) {
        if (family == null) {
            return;
        }
        roadParts(owner, family.straight(), field + ".straight", java.util.Arrays.asList(null, palette));
        roadParts(owner, family.end(), field + ".end", java.util.Arrays.asList(null, palette));
        roadParts(owner, family.bend(), field + ".bend", java.util.Arrays.asList(null, palette));
        roadParts(owner, family.t(), field + ".t", java.util.Arrays.asList(null, palette));
        roadParts(owner, family.none(), field + ".none", java.util.Arrays.asList(null, palette));
        roadParts(owner, family.all(), field + ".all", java.util.Arrays.asList(null, palette));
        roadParts(owner, family.connector(), field + ".connector", java.util.Arrays.asList(null, palette));
        roadParts(owner, family.stair(), field + ".stair", java.util.Arrays.asList(null, palette));
    }

    /**
     * A building's parts are checked under the style that reached it, so the same building selected by
     * two city styles is two sets of answers - which is the whole reason a usage exists.
     * {@link #seen} still gates the <em>reference</em> walk to once per building; the character check
     * has its own gate on the usage.
     */
    private void walkBuilding(Building building, @Nullable Style palette) {
        // Keyed by the style as well as the building: the same building under two city styles is two
        // sets of character answers, and both have to be reached. Finite either way, so a cycle still
        // ends - there are only so many (building, style) pairs.
        if (!first("building/" + styleKey(palette), building.getId())) {
            return;
        }
        for (String name : building.partNames()) {
            part(building.getId(), name, "parts", usage(palette, building, false, "parts", building.getId()));
        }
        // The matchers on those same entries. Not dereferences - a parts entry whose 'belowpart'
        // names nothing simply never fires - so they follow the soft rule, like a condition's.
        for (PartRef ref : building.partConditions()) {
            softAssetRefs(building.getId(), ref.getInpart(), "parts.inpart", assets.parts());
            softAssetRefs(building.getId(), ref.getBelowPart(), "parts.belowpart", assets.parts());
            softAssetRefs(building.getId(), ref.getInbuilding(), "parts.inbuilding", assets.buildings());
        }
    }

    private void walkMultiBuilding(MultiBuilding multi, @Nullable Style palette) {
        if (!first("multibuilding/" + styleKey(palette), multi.getId())) {
            return;
        }
        if (multi.getBuildingSet() != null) {
            for (String name : multi.getBuildingSet()) {
                building(multi.getId(), name, "buildings", palette);
            }
        }
    }

    private void walkScattered(ScatteredBuilding scattered) {
        if (!first("scattered", scattered.getId())) {
            return;
        }
        // Either arm may be absent: a scattered entry declares 'buildings' or 'multibuilding', and
        // the constructor requires one of the two rather than both.
        // No style: a scattered building lands wherever the terrain allows and takes the style of
        // the chunk it lands in, which the walk cannot know. Its references are still checked.
        if (scattered.getBuildings() != null) {
            for (String name : scattered.getBuildings()) {
                building(scattered.getId(), name, "buildings", null);
            }
        }
        multiBuilding(scattered.getId(), scattered.getMultibuilding(), "multibuilding", null);
    }

    private void walkPredefinedCity(PredefinedCity city) {
        if (!first("predefinedcity", city.getId())) {
            return;
        }
        if (city.getPredefinedBuildings() == null) {
            return;
        }
        for (PredefinedBuilding building : city.getPredefinedBuildings()) {
            // An explicit predefined style is an eagerly checked root and supplies the palette
            // context. An omitted style follows a runtime-selected world-style family, so there is
            // no single palette to attach to this static usage walk.
            Style palette = paletteOf(city.getCityStyle());
            if (building.multi()) {
                multiBuilding(city.getId(), building.building(), "buildings", palette);
            } else {
                building(city.getId(), building.building(), "buildings", palette);
            }
        }
    }

    // ------------------------------------------------------------------ one reference

    private void parts(Identifier owner, @Nullable List<String> names, String field,
                       @Nullable PartUsage usage) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            part(owner, name, field, usage);
        }
    }

    private void part(Identifier owner, @Nullable String name, String field, @Nullable PartUsage usage) {
        resolve(owner, name, field, assets.parts(), part -> checkPart(part, usage));
    }

    /**
     * Everything that is a question about a part <em>where it is used</em>, rather than about the
     * part on its own.
     *
     * <p>{@code usage} is null where the walk reached a part without knowing the style it will be
     * drawn against - today only a scattered building's parts, whose chunk takes its style from the
     * terrain around it. Those keep the reference check and skip the character check rather than
     * being checked against a style they might not be used with.</p>
     */
    private void checkPart(BuildingPart part, @Nullable PartUsage usage) {
        if (usage == null || !checkedUsages.add(usage.key(part.getId()))) {
            return;
        }
        if (usage.road()) {
            checkRoadGeometry(part, usage);
        }
        PaletteCharacterCheck.check(part, usage, diagnostics);
    }

    /**
     * A part wired into a street, highway or railway slot is addressed as a whole chunk, and nothing
     * clamps it: {@code ChunkDriver.current} converts chunk-local to absolute unchanged and
     * {@code block()} masks the result with {@code & 0xf}, so a part wider than 16 <strong>wraps
     * around and overwrites its own beginning</strong> - no exception, nothing in the log, just a
     * road that comes out wrong. {@code BuildingPart.checkGeometry} proves a part is self-consistent;
     * this is the separate question of whether it fits where it was wired in.
     */
    private void checkRoadGeometry(BuildingPart part, PartUsage usage) {
        if (part.getXSize() != 16 || part.getZSize() != 16) {
            diagnostics.record("urbex:parts", part.getId(),
                    "is wired into '" + usage.owner() + "' (" + usage.field() + ") as a road piece, "
                            + "which is placed as a whole chunk, but it is " + part.getXSize() + "x"
                            + part.getZSize() + " rather than 16x16; the driver masks coordinates to "
                            + "the chunk, so the overflow would silently overwrite the part's own start");
        }
    }

    private void building(Identifier owner, @Nullable String name, String field, @Nullable Style palette) {
        resolve(owner, name, field, assets.buildings(), building -> walkBuilding(building, palette));
    }

    private void multiBuilding(Identifier owner, @Nullable String name, String field,
                               @Nullable Style palette) {
        resolve(owner, name, field, assets.multiBuildings(), multi -> walkMultiBuilding(multi, palette));
    }

    /** A usage, or null when there is no style to check characters against. */
    @Nullable
    private static PartUsage usage(@Nullable Style palette, @Nullable Building building, boolean road,
                                   String field, Identifier owner) {
        return palette == null && !road ? null : new PartUsage(palette, building, road, field, owner);
    }

    /** The style a city style draws from, or null if that reference is the thing that is broken. */
    @Nullable
    private Style paletteOf(@Nullable String cityStyleName) {
        if (cityStyleName == null) {
            return null;
        }
        CityStyle city = reachable.get(DataTools.fromName(cityStyleName));
        return city == null ? null : assets.styles().get(city.getStyle());
    }

    private void scattered(Identifier owner, @Nullable String name, String field) {
        resolve(owner, name, field, assets.scattered(), this::walkScattered);
    }

    private void style(Identifier owner, @Nullable String name, String field) {
        resolve(owner, name, field, assets.styles(), style -> { });
    }

    /**
     * The conditions a palette's markers name, and what those conditions in turn reference.
     *
     * <p>A {@code loot} or {@code mob} marker holds a <em>condition</em> name, which
     * {@code CityGenerator.getRandomSpawnerMob} resolves with {@code getOrThrow} - so a marker naming
     * a condition nothing registers is a crashed chunk, and fatal here like every other dereference.
     * What the condition then hands back is a loot table or an entity id, which is not, and is
     * treated as such below.</p>
     */
    private void walkPalette(@Nullable Palette palette) {
        if (palette == null || !first("palette", palette.getId())) {
            return;
        }
        for (Palette.PE entry : palette.getPalette().values()) {
            Palette.Info info = entry.info();
            if (info == null) {
                continue;
            }
            condition(palette.getId(), info.loot(), "loot", false);
            condition(palette.getId(), info.mobId(), "mob", true);
        }
    }

    /**
     * @param entities whether this condition's values are entity ids. A condition is a weighted list
     *                 of strings and nothing in it says what they are - the marker that named it does:
     *                 a {@code mob} marker's values are entity types, a {@code loot} marker's are loot
     *                 tables. Reached as both, it is walked as both.
     */
    private void condition(Identifier owner, @Nullable String name, String field, boolean entities) {
        resolve(owner, name, field, assets.conditions(), condition -> walkCondition(condition, entities));
    }

    /**
     * A condition's own references, none of which generation dereferences - so none of them is fatal
     * and some are not even reported.
     *
     * <p>Its matchers ({@code inpart}, {@code inbuilding}) are string membership tests: naming a part
     * nothing registers does not crash, the condition just silently never fires. Its values are loot
     * tables and entity ids handed to Minecraft. Both are the shape a pack may deliberately write for
     * content it does not require, so {@link ReferenceProvider} decides whether the absence is worth a
     * line at all: it is when the pack or mod that would provide it <em>is</em> installed, and it is
     * not when nobody has it.</p>
     */
    private void walkCondition(Condition condition, boolean entities) {
        if (!first("condition/" + entities, condition.getId())) {
            return;
        }
        for (ConditionPart entry : condition.entries()) {
            softAssetRefs(condition.getId(), entry.getInpart(), "values.inpart", assets.parts());
            softAssetRefs(condition.getId(), entry.getBelowPart(), "values.belowpart", assets.parts());
            softAssetRefs(condition.getId(), entry.getInbuilding(), "values.inbuilding", assets.buildings());
            if (entities) {
                softEntityRef(condition.getId(), entry.getValue());
            }
        }
    }

    /** A matcher naming an Urbex asset: reported only when a pack using that namespace is loaded. */
    private void softAssetRefs(Identifier owner, @Nullable Set<String> names, String field,
                               AssetIndex<?> index) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            Identifier id;
            try {
                id = DataTools.fromName(name);
            } catch (RuntimeException e) {
                continue;   // an unqualified matcher is the codec's business, not this walk's
            }
            if (index.get(id) != null || !ReferenceProvider.packIsInstalled(id, assetNamespaces)) {
                continue;
            }
            if (reported.add(index.registry() + "|" + owner + "|" + field + "|" + name)) {
                diagnostics.warn(index.registry(), owner,
                        "'" + field + "' matches on '" + name + "', which no loaded datapack "
                                + "registers even though other assets in that namespace are loaded; "
                                + "this condition can never fire for it");
            }
        }
    }

    /**
     * An entity id a {@code mob} condition hands to a spawner. Never fatal - a spawner with an
     * unknown entity is an empty spawner, not a crash - and silent unless the mod that would provide
     * it is installed, which is the case a pack listing another mod's mobs deliberately relies on.
     * <p>
     * Loot tables have no equivalent check: they live in a reloadable server registry rather than in
     * the frozen ones this compiles against, so at this point in the load there is nothing to ask.
     */
    private void softEntityRef(Identifier owner, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Identifier id = Identifier.tryParse(value);
        if (id == null || !ReferenceProvider.modIsInstalled(id)
                || BuiltInRegistries.ENTITY_TYPE.get(ResourceKey.create(Registries.ENTITY_TYPE, id)).isPresent()) {
            return;
        }
        if (reported.add("entity|" + owner + "|" + value)) {
            diagnostics.warn(assets.conditions().registry(), owner,
                    "hands back mob '" + value + "', which '" + id.getNamespace()
                            + "' is installed but does not provide; a spawner using it stays empty");
        }
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
            if (reported.add(index.registry() + "|" + owner + "|" + field + "|" + name)) {
                diagnostics.record(index.registry(), owner,
                        "'" + field + "' names '" + name + "', which is not a valid asset id: " + e.getMessage());
            }
            return;
        }
        T found = index.get(id);
        if (found == null) {
            // Deduped on the reference itself, not on the walk: the same bad name reached by four
            // paths is one thing to fix, and the walk now visits a building once per style.
            if (reported.add(index.registry() + "|" + owner + "|" + field + "|" + name)) {
                diagnostics.record(index.registry(), owner,
                        "'" + field + "' names '" + name + "', which no loaded datapack registers");
            }
            return;
        }
        pending.add(() -> walk.accept(found));
    }

    /** True the first time this asset is reached, so a diamond costs one visit and a cycle ends. */
    private boolean first(String kind, Identifier id) {
        return seen.add(kind + "/" + id);
    }

    private static String styleKey(@Nullable Style palette) {
        return palette == null ? "-" : palette.getId().toString();
    }
}
