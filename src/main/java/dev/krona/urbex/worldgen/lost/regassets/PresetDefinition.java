package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.BuildingSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.CitySettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.DecorationSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.DestructionSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.HighwaySettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.MiscSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.RailwaySettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.RoadSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.SpawnSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.TerrainSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.RetiredKeys;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.Set;

/**
 * The datapack-facing preset format: every field is optional, and only the fields actually
 * present in the JSON are applied on top of an {@code extends} chain (see {@code Presets.resolve}).
 * Mirrors the {@code WorldStyleDefinition} registry-entry idiom.
 */
public class PresetDefinition implements Extendable {

    public static final Set<String> KEYS = Set.of("extends", "name", "description", "extraDescription", "warning",
            "icon", "terrain", "cities", "buildings", "roads", "highways", "railways", "destruction", "decoration",
            "spawn", "misc");

    private static final Codec<PresetDefinition> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(PresetDefinition::getExtends),
                    Codec.STRING.optionalFieldOf("name").forGetter(PresetDefinition::displayName),
                    Codec.STRING.optionalFieldOf("description").forGetter(PresetDefinition::description),
                    Codec.STRING.optionalFieldOf("extraDescription").forGetter(PresetDefinition::extraDescription),
                    Codec.STRING.optionalFieldOf("warning").forGetter(PresetDefinition::warning),
                    Codec.STRING.optionalFieldOf("icon").forGetter(PresetDefinition::icon),
                    TerrainSettings.CODEC.optionalFieldOf("terrain").forGetter(PresetDefinition::terrain),
                    CitySettings.CODEC.optionalFieldOf("cities").forGetter(PresetDefinition::cities),
                    BuildingSettings.CODEC.optionalFieldOf("buildings").forGetter(PresetDefinition::buildings),
                    RoadSettings.CODEC.optionalFieldOf("roads").forGetter(PresetDefinition::roads),
                    HighwaySettings.CODEC.optionalFieldOf("highways").forGetter(PresetDefinition::highways),
                    RailwaySettings.CODEC.optionalFieldOf("railways").forGetter(PresetDefinition::railways),
                    DestructionSettings.CODEC.optionalFieldOf("destruction").forGetter(PresetDefinition::destruction),
                    DecorationSettings.CODEC.optionalFieldOf("decoration").forGetter(PresetDefinition::decoration),
                    SpawnSettings.CODEC.optionalFieldOf("spawn").forGetter(PresetDefinition::spawn),
                    MiscSettings.CODEC.optionalFieldOf("misc").forGetter(PresetDefinition::misc)
            ).apply(instance, PresetDefinition::new));

    /**
     * Retired-key rejection outside the unknown-key warning, so {@code inherit}/{@code parent} fail
     * the decode instead of being reported as one more ignorable typo. Presets are the only registry
     * where an unknown key is even mentioned - the other twelve drop it silently - which is exactly
     * why the retired keys cannot be left to that path. See {@link RetiredKeys}.
     */
    public static final Codec<PresetDefinition> CODEC = RetiredKeys.reject(
            dev.krona.urbex.worldgen.lost.regassets.data.preset.UnknownKeys.warning(RAW, KEYS, "preset"),
            "preset");


    private final Optional<Identifier> extendsId;
    private final Optional<String> displayName;
    private final Optional<String> description;
    private final Optional<String> extraDescription;
    private final Optional<String> warning;
    private final Optional<String> icon;
    private final Optional<TerrainSettings> terrain;
    private final Optional<CitySettings> cities;
    private final Optional<BuildingSettings> buildings;
    private final Optional<RoadSettings> roads;
    private final Optional<HighwaySettings> highways;
    private final Optional<RailwaySettings> railways;
    private final Optional<DestructionSettings> destruction;
    private final Optional<DecorationSettings> decoration;
    private final Optional<SpawnSettings> spawn;
    private final Optional<MiscSettings> misc;

    public PresetDefinition(Optional<Identifier> extendsId,
                     Optional<String> displayName,
                     Optional<String> description,
                     Optional<String> extraDescription,
                     Optional<String> warning,
                     Optional<String> icon,
                     Optional<TerrainSettings> terrain,
                     Optional<CitySettings> cities,
                     Optional<BuildingSettings> buildings,
                     Optional<RoadSettings> roads,
                     Optional<HighwaySettings> highways,
                     Optional<RailwaySettings> railways,
                     Optional<DestructionSettings> destruction,
                     Optional<DecorationSettings> decoration,
                     Optional<SpawnSettings> spawn,
                     Optional<MiscSettings> misc) {
        this.extendsId = extendsId;
        this.displayName = displayName;
        this.description = description;
        this.extraDescription = extraDescription;
        this.warning = warning;
        this.icon = icon;
        this.terrain = terrain;
        this.cities = cities;
        this.buildings = buildings;
        this.roads = roads;
        this.highways = highways;
        this.railways = railways;
        this.destruction = destruction;
        this.decoration = decoration;
        this.spawn = spawn;
        this.misc = misc;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    /**
     * The human-readable label the Cities tab shows instead of the id. Inherited through
     * {@code extends} exactly like {@link #description()}, so a pack that extends
     * {@code urbex:default} and forgets to restate it is labelled "Default" - restate it.
     */
    public Optional<String> displayName() {
        return displayName;
    }

    public Optional<String> description() {
        return description;
    }

    public Optional<String> extraDescription() {
        return extraDescription;
    }

    public Optional<String> warning() {
        return warning;
    }

    public Optional<String> icon() {
        return icon;
    }

    public Optional<TerrainSettings> terrain() {
        return terrain;
    }

    public Optional<CitySettings> cities() {
        return cities;
    }

    public Optional<BuildingSettings> buildings() {
        return buildings;
    }

    public Optional<RoadSettings> roads() {
        return roads;
    }

    public Optional<HighwaySettings> highways() {
        return highways;
    }

    public Optional<RailwaySettings> railways() {
        return railways;
    }

    public Optional<DestructionSettings> destruction() {
        return destruction;
    }

    public Optional<DecorationSettings> decoration() {
        return decoration;
    }

    public Optional<SpawnSettings> spawn() {
        return spawn;
    }

    public Optional<MiscSettings> misc() {
        return misc;
    }

    /** Applies metadata (if present) then each present section's {@code apply}, onto {@code p}. */
    public void applyTo(PresetDraft p) {
        displayName.ifPresent(p::setName);
        description.ifPresent(p::setDescription);
        extraDescription.ifPresent(p::setExtraDescription);
        warning.ifPresent(p::setWarning);
        icon.ifPresent(p::setIconFile);
        terrain.ifPresent(s -> s.apply(p));
        cities.ifPresent(s -> s.apply(p));
        buildings.ifPresent(s -> s.apply(p));
        roads.ifPresent(s -> s.apply(p));
        highways.ifPresent(s -> s.apply(p));
        railways.ifPresent(s -> s.apply(p));
        destruction.ifPresent(s -> s.apply(p));
        decoration.ifPresent(s -> s.apply(p));
        spawn.ifPresent(s -> s.apply(p));
        misc.ifPresent(s -> s.apply(p));
    }


}
