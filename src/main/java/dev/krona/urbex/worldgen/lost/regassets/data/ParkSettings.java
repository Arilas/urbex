package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * For a city style this object represents settings for parks
 */
public class ParkSettings {
    private final Boolean avoidFoliage;
    private final Boolean parkBorder;
    private final Boolean parkElevation;
    private final Integer parkStreetThreshold;
    private final Character parkElevationBlock;
    private final Character grassBlock;
    private final Character lampBlock;
    private final Integer lampSpacing;

    public static final Codec<ParkSettings> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.BOOL.optionalFieldOf("avoidfoliage").forGetter(l -> Optional.ofNullable(l.avoidFoliage)),
                    Codec.BOOL.optionalFieldOf("parkborder").forGetter(l -> Optional.ofNullable(l.parkBorder)),
                    Codec.BOOL.optionalFieldOf("parkelevation").forGetter(l -> Optional.ofNullable(l.parkElevation)),
                    Codec.INT.optionalFieldOf("parkstreetthreshold").forGetter(l -> Optional.ofNullable(l.parkStreetThreshold)),
                    Codec.STRING.optionalFieldOf("elevation").forGetter(l -> DataTools.toNullable(l.parkElevationBlock)),
                    Codec.STRING.optionalFieldOf("grass").forGetter(l -> DataTools.toNullable(l.grassBlock)),
                    Codec.STRING.optionalFieldOf("lamp").forGetter(l -> DataTools.toNullable(l.lampBlock)),
                    Codec.intRange(1, 16).optionalFieldOf("lampspacing").forGetter(l -> Optional.ofNullable(l.lampSpacing))
            ).apply(instance, ParkSettings::new));

    public Boolean getAvoidFoliage() { return avoidFoliage; }

    public Boolean getParkBorder() { return parkBorder; }

    public Boolean getParkElevation() { return parkElevation; }

    public Integer getParkStreetThreshold() { return parkStreetThreshold; }

    public Character getParkElevationBlock() { return parkElevationBlock; }

    public Character getGrassBlock() { return grassBlock; }

    /**
     * The character a park section stands its lamps on, or null for a park with no lamps.
     * <p>
     * A park surface is generated rather than assembled from a part, so there is no slice anyone can
     * write a light into: before this, no datapack could light a park at any lighting density, and a
     * city's parks were the one place a player was guaranteed to find mobs. Naming a character here
     * is how a style says "put this fixture on the grass", and if that character carries
     * {@code lightSource} it obeys lighting density exactly like a lamp in a street part.
     */
    public Character getLampBlock() { return lampBlock; }

    /** Blocks between lamps, on a world-aligned grid so a run of park chunks lines up. */
    public Integer getLampSpacing() { return lampSpacing; }

    public ParkSettings(Optional<Boolean> avoidFoliage,
                        Optional<Boolean> parkBorder,
                        Optional<Boolean> parkElevation,
                        Optional<Integer> parkStreetThreshold,
                        Optional<String> parkElevationBlock,
                        Optional<String> grassBlock,
                        Optional<String> lampBlock,
                        Optional<Integer> lampSpacing) {
        this.avoidFoliage = avoidFoliage.orElse(null);
        this.parkBorder = parkBorder.orElse(null);
        this.parkElevation = parkElevation.orElse(null);
        this.parkStreetThreshold = parkStreetThreshold.orElse(null);
        this.parkElevationBlock = DataTools.getNullableChar(parkElevationBlock);
        this.grassBlock = DataTools.getNullableChar(grassBlock);
        this.lampBlock = DataTools.getNullableChar(lampBlock);
        this.lampSpacing = lampSpacing.orElse(null);
    }
}
