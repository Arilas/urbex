package dev.krona.urbex.worldgen.lost.cityassets;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Every compiled Urbex asset a world generates from, finished and published in one go.
 *
 * <p>One snapshot per <strong>world load</strong>, not per level. The thirteen asset registries are
 * Fabric dynamic registries, loaded once while the world loads and frozen (issue #61), and every
 * level in that world reads the same ones - so the snapshot belongs to the server's
 * {@code GenerationSession}, and each level's {@code DimensionRuntime} references this one instance
 * rather than compiling its own.</p>
 *
 * <p>What it replaces is twelve {@code static final} registries, a readiness latch and a
 * {@code reset()}. Nothing here can be reset, half-built or compiled on demand: a chunk holds a
 * reference to a snapshot for the whole of its generation, and the worst a reload can do is publish a
 * different one for the next chunk (issue #128).</p>
 *
 * @param stuffByTag each stuff tag's objects, sorted by id. Derived rather than looked up, because
 *                   the sort order is an RNG address - see {@link #stuffFor}.
 * @param predefined where the predefined cities, buildings and streets sit, by chunk. Also derived:
 *                   it is a pure function of {@code predefinedCities} and {@code multiBuildings},
 *                   and used to be five lazily-latched static maps on {@code City} (issue #129).
 */
public record AssetSnapshot(
        AssetIndex<Palette> palettes,
        AssetIndex<Condition> conditions,
        AssetIndex<Style> styles,
        AssetIndex<BuildingPart> parts,
        AssetIndex<Building> buildings,
        AssetIndex<MultiBuilding> multiBuildings,
        AssetIndex<ScatteredBuilding> scattered,
        AssetIndex<WorldStyle> worldStyles,
        AssetIndex<CityStyle> cityStyles,
        AssetIndex<PredefinedCity> predefinedCities,
        AssetIndex<StuffObject> stuff,
        SortedMap<String, List<StuffObject>> stuffByTag,
        PredefinedIndex predefined
) {

    /**
     * The stuff filed under {@code tag}, or empty.
     *
     * <p>Empty rather than null on a miss, unlike the map this replaces: {@code Stuff.generateStuff}
     * had to distinguish "no such tag" from "the index was cleared underneath me", and a snapshot
     * cannot be cleared, so the second case no longer exists and the first is not an error - a pack
     * may ship no stuff for a tag some other pack's city style names.</p>
     */
    public List<StuffObject> stuffFor(String tag) {
        return stuffByTag.getOrDefault(tag, List.of());
    }

    /** Total compiled assets, for the one-line summary the compiler logs. */
    public int totalAssets() {
        return palettes.size() + conditions.size() + styles.size() + parts.size()
                + buildings.size() + multiBuildings.size() + scattered.size() + worldStyles.size()
                + cityStyles.size() + predefinedCities.size() + stuff.size();
    }

    /** An empty snapshot, for a context that has no registries at all (a test, a null level). */
    public static AssetSnapshot empty() {
        return new AssetSnapshot(
                AssetIndex.empty("urbex:palettes"),
                AssetIndex.empty("urbex:conditions"),
                AssetIndex.empty("urbex:styles"),
                AssetIndex.empty("urbex:parts"),
                AssetIndex.empty("urbex:buildings"),
                AssetIndex.empty("urbex:multibuildings"),
                AssetIndex.empty("urbex:scattered"),
                AssetIndex.empty("urbex:worldstyles"),
                AssetIndex.empty("urbex:citystyles"),
                AssetIndex.empty("urbex:predefinedcities"),
                AssetIndex.empty("urbex:stuff"),
                java.util.Collections.emptySortedMap(),
                PredefinedIndex.empty());
    }
}
