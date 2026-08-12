package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.*;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public class CityStyleRE implements Extendable {

    private static final Codec<CityStyleRE> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.FLOAT.optionalFieldOf("explosionchance").forGetter(l -> Optional.ofNullable(l.explosionChance)),
                    Codec.STRING.optionalFieldOf("style").forGetter(l -> Optional.ofNullable(l.style)),
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(CityStyleRE::getExtends),
                    Codec.STRING.optionalFieldOf("name").forGetter(l -> Optional.ofNullable(l.displayName)),
                    Codec.STRING.listOf().optionalFieldOf("stuff_tags").forGetter(l -> Optional.ofNullable(l.stuffTags)),
                    GeneralSettings.CODEC.optionalFieldOf("generalblocks").forGetter(l -> Optional.ofNullable(l.generalSettings)),
                    BuildingSettings.CODEC.optionalFieldOf("buildingsettings").forGetter(l -> Optional.ofNullable(l.buildingSettings)),
                    CorridorSettings.CODEC.optionalFieldOf("corridorblocks").forGetter(l -> Optional.ofNullable(l.corridorSettings)),
                    ParkSettings.CODEC.optionalFieldOf("parkblocks").forGetter(l -> Optional.ofNullable(l.parkSettings)),
                    RailSettings.CODEC.optionalFieldOf("railblocks").forGetter(l -> Optional.ofNullable(l.railSettings)),
                    StreetSettings.CODEC.optionalFieldOf("streetblocks").forGetter(l -> Optional.ofNullable(l.streetSettings)),
                    Selectors.CODEC.optionalFieldOf("selectors").forGetter(l -> Optional.ofNullable(l.selectors))
            ).apply(instance, CityStyleRE::new));

    /** Retired-key rejection wraps every registry's codec; see {@link RetiredKeys}. */
    public static final Codec<CityStyleRE> CODEC = RetiredKeys.reject(RAW, "citystyle");


    private final Float explosionChance;
    private final String style;
    private final Optional<Identifier> extendsId;

    // The human-readable label for this city style. Null means "not declared here", so the chain
    // reads it from an ancestor; a chain that declares none anywhere falls back to the id.
    private final String displayName;

    private final List<String> stuffTags;

    private final GeneralSettings generalSettings;
    private final BuildingSettings buildingSettings;
    private final CorridorSettings corridorSettings;
    private final ParkSettings parkSettings;
    private final RailSettings railSettings;
    private final StreetSettings streetSettings;

    private final Selectors selectors;

    public CityStyleRE(
            Optional<Float> explosionChance,
            Optional<String> style,
            Optional<Identifier> extendsId,
            Optional<String> displayName,
            Optional<List<String>> stuffTags,
            Optional<GeneralSettings> generalSettings,
            Optional<BuildingSettings> buildingSettings,
            Optional<CorridorSettings> corridorSettings,
            Optional<ParkSettings> parkSettings,
            Optional<RailSettings> railSettings,
            Optional<StreetSettings> streetSettings,
            Optional<Selectors> selectors) {
        this.explosionChance = explosionChance.orElse(null);
        this.style = style.orElse(null);
        this.extendsId = extendsId;
        this.displayName = displayName.orElse(null);
        this.stuffTags = stuffTags.orElse(null);
        this.generalSettings = generalSettings.orElse(null);
        this.buildingSettings = buildingSettings.orElse(null);
        this.corridorSettings = corridorSettings.orElse(null);
        this.parkSettings = parkSettings.orElse(null);
        this.railSettings = railSettings.orElse(null);
        this.streetSettings = streetSettings.orElse(null);
        this.selectors = selectors.orElse(null);
    }

    public Float getExplosionChance() {
        return explosionChance;
    }

    @Nullable
    public String getDisplayName() {
        return displayName;
    }

    @Nullable
    public List<String> getStuffTags() {
        return stuffTags;
    }

    public String getStyle() {
        return style;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    public Optional<GeneralSettings> getGeneralSettings() {
        return Optional.ofNullable(generalSettings);
    }

    public Optional<BuildingSettings> getBuildingSettings() {
        return Optional.ofNullable(buildingSettings);
    }

    public Optional<CorridorSettings> getCorridorSettings() {
        return Optional.ofNullable(corridorSettings);
    }

    public Optional<ParkSettings> getParkSettings() { return Optional.ofNullable(parkSettings); }

    public Optional<RailSettings> getRailSettings() {
        return Optional.ofNullable(railSettings);
    }

    public Optional<StreetSettings> getStreetSettings() {
        return Optional.ofNullable(streetSettings);
    }

    public Optional<Selectors> getSelectors() {
        return Optional.ofNullable(selectors);
    }


}
