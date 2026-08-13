package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedStreet;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where every predefined city, building and street sits, by chunk.
 *
 * <p>All five maps are a pure function of two things the snapshot already holds -
 * {@link AssetSnapshot#predefinedCities()} and {@link AssetSnapshot#multiBuildings()} - so they are
 * compiled once with the rest of the world's assets and never rebuilt. Nothing here is per-level,
 * per-seed or per-preset.</p>
 *
 * <p>This replaces five {@code static final} maps on {@code City}, each filled lazily from whichever
 * worker thread arrived first and latched by its own {@code static volatile boolean}. The latch is
 * what made those maps a problem rather than a cache: it had to be guarded on
 * {@code provider.getWorld() != null} so a world-creation preview - which has no level and may hold
 * an empty snapshot - could not latch "ready" over an empty map and permanently stop a later real
 * level from populating it (issue #67), and it had to be cleared by a {@code cleanPredefinedCache()}
 * that three unrelated call sites remembered to call, one of them the preview, clearing maps live
 * worldgen was reading. An index that is finished before it is published needs neither (issue
 * #129).</p>
 *
 * <p><strong>Conflicts are still last-writer-wins.</strong> Two predefined cities claiming one chunk
 * resolve in {@link AssetIndex#all()} order, exactly as before, because this is built by the same
 * walk in the same order. Two files claiming one chunk is a pack authoring error whichever way it
 * breaks, and choosing a rule for it (first-wins, or a load error naming both) is an asset-validation
 * decision that belongs with issue #56, not something to smuggle into an ownership move. The one
 * ordering that does change is noted at the occupancy expansion in {@link #build}.</p>
 */
public final class PredefinedIndex {

    /**
     * A predefined building as seen from one of the chunks it covers.
     *
     * @param offsetX chunk offset from the building's declared top-left; always 0 for a single-chunk
     *                building
     * @param offsetZ as {@code offsetX}
     */
    public record BuildingAt(PredefinedBuilding building, int offsetX, int offsetZ) {}

    private static final PredefinedIndex EMPTY = new PredefinedIndex(
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    private final Map<ChunkCoord, PredefinedCity> cities;
    private final Map<ChunkCoord, PredefinedBuilding> buildings;
    private final Map<ChunkCoord, PredefinedStreet> streets;
    private final Map<ChunkCoord, BuildingAt> buildingOccupancy;
    private final Map<ChunkCoord, PredefinedStreet> streetOccupancy;

    private PredefinedIndex(Map<ChunkCoord, PredefinedCity> cities,
                            Map<ChunkCoord, PredefinedBuilding> buildings,
                            Map<ChunkCoord, PredefinedStreet> streets,
                            Map<ChunkCoord, BuildingAt> buildingOccupancy,
                            Map<ChunkCoord, PredefinedStreet> streetOccupancy) {
        this.cities = cities;
        this.buildings = buildings;
        this.streets = streets;
        this.buildingOccupancy = buildingOccupancy;
        this.streetOccupancy = streetOccupancy;
    }

    /** The index for a world with no predefined cities at all. */
    public static PredefinedIndex empty() {
        return EMPTY;
    }

    /**
     * Builds the index from the compiled cities.
     *
     * <p>{@code multiBuildings} is consulted only to learn how many chunks a multi-building covers.
     * A predefined building naming a multi-building no pack registers is a dangling reference, which
     * {@link AssetGraph} reports as a load problem; here it is skipped, so the compiler reaches its
     * aggregated report instead of throwing out of the middle of the build (issue #56).</p>
     */
    public static PredefinedIndex build(AssetIndex<PredefinedCity> predefinedCities,
                                        AssetIndex<MultiBuilding> multiBuildings) {
        Map<ChunkCoord, PredefinedCity> cities = new HashMap<>();
        // Insertion-ordered, so the occupancy expansion below runs in the order the cities declared
        // their buildings. The map it replaces was a ConcurrentHashMap walked in hash order, so two
        // multi-buildings whose footprints overlap - already a pack authoring error - used to be
        // resolved by ChunkCoord hash and are now resolved by declaration order. Deterministic
        // either way; this one can be explained to whoever wrote the pack.
        Map<ChunkCoord, PredefinedBuilding> buildings = new LinkedHashMap<>();
        Map<ChunkCoord, PredefinedStreet> streets = new HashMap<>();
        Map<ChunkCoord, BuildingAt> buildingOccupancy = new HashMap<>();
        Map<ChunkCoord, PredefinedStreet> streetOccupancy = new HashMap<>();

        for (PredefinedCity city : predefinedCities.all()) {
            cities.put(new ChunkCoord(city.getDimension(), city.getChunkX(), city.getChunkZ()), city);
            for (PredefinedBuilding building : city.getPredefinedBuildings()) {
                buildings.put(new ChunkCoord(city.getDimension(),
                        city.getChunkX() + building.relChunkX(),
                        city.getChunkZ() + building.relChunkZ()), building);
            }
            for (PredefinedStreet street : city.getPredefinedStreets()) {
                ChunkCoord at = new ChunkCoord(city.getDimension(),
                        city.getChunkX() + street.relChunkX(),
                        city.getChunkZ() + street.relChunkZ());
                streets.put(at, street);
                streetOccupancy.put(at, street);
            }
        }

        // Over the finished building map rather than inside the loop above, because that is what the
        // code this replaces did: it walked PREDEFINED_BUILDING_MAP's entries, not the cities. With
        // two buildings declared on one chunk the map has already picked a winner, and expanding the
        // loser's footprint here would put chunks in the occupancy map whose root holds a different
        // building.
        for (Map.Entry<ChunkCoord, PredefinedBuilding> entry : buildings.entrySet()) {
            PredefinedBuilding building = entry.getValue();
            ChunkCoord root = entry.getKey();
            if (!building.multi()) {
                buildingOccupancy.put(root, new BuildingAt(building, 0, 0));
                continue;
            }
            MultiBuilding multi = multiBuildings.get(building.building());
            if (multi == null) {
                continue;
            }
            for (int x = 0; x < multi.getDimX(); x++) {
                for (int z = 0; z < multi.getDimZ(); z++) {
                    buildingOccupancy.put(root.offset(x, z), new BuildingAt(building, x, z));
                }
            }
        }

        return new PredefinedIndex(Map.copyOf(cities), Map.copyOf(buildings), Map.copyOf(streets),
                Map.copyOf(buildingOccupancy), Map.copyOf(streetOccupancy));
    }

    /** The predefined city centred on {@code coord}, or null. */
    @Nullable
    public PredefinedCity cityAt(ChunkCoord coord) {
        return cities.get(coord);
    }

    /** The predefined building declared <em>at</em> {@code coord} - its top-left chunk - or null. */
    @Nullable
    public PredefinedBuilding buildingAt(ChunkCoord coord) {
        return buildings.get(coord);
    }

    /** The predefined street declared at {@code coord}, or null. */
    @Nullable
    public PredefinedStreet streetAt(ChunkCoord coord) {
        return streets.get(coord);
    }

    /**
     * The predefined building covering {@code coord}, with this chunk's offset within it, or null.
     * Unlike {@link #buildingAt} this answers for every chunk of a multi-building, not just its
     * top-left.
     */
    @Nullable
    public BuildingAt buildingCovering(ChunkCoord coord) {
        return buildingOccupancy.get(coord);
    }

    /** The predefined street covering {@code coord}, or null. */
    @Nullable
    public PredefinedStreet streetCovering(ChunkCoord coord) {
        return streetOccupancy.get(coord);
    }

    /** Whether a predefined building or street claims {@code coord}. */
    public boolean isOccupied(ChunkCoord coord) {
        return buildingOccupancy.containsKey(coord) || streetOccupancy.containsKey(coord);
    }

    /** Whether this world declares any predefined content at all. */
    public boolean isEmpty() {
        return cities.isEmpty() && buildings.isEmpty() && streets.isEmpty();
    }
}
