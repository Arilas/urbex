package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.AtmosphereSettings;
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
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.Set;

/**
 * The datapack-facing preset format: every field is optional, and only the fields actually
 * present in the JSON are applied on top of an {@code extends} chain (see {@code Presets.resolve}).
 * Mirrors the {@code WorldStyleRE} registry-entry idiom.
 */
public class PresetRE implements IAsset<PresetRE>, Extendable {

    public static final Set<String> KEYS = Set.of("extends", "description", "extraDescription", "warning", "icon",
            "terrain", "cities", "buildings", "roads", "highways", "railways", "destruction", "decoration",
            "spawn", "atmosphere", "misc");

    private static final Codec<PresetRE> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(PresetRE::getExtends),
                    Codec.STRING.optionalFieldOf("description").forGetter(PresetRE::description),
                    Codec.STRING.optionalFieldOf("extraDescription").forGetter(PresetRE::extraDescription),
                    Codec.STRING.optionalFieldOf("warning").forGetter(PresetRE::warning),
                    Codec.STRING.optionalFieldOf("icon").forGetter(PresetRE::icon),
                    TerrainSettings.CODEC.optionalFieldOf("terrain").forGetter(PresetRE::terrain),
                    CitySettings.CODEC.optionalFieldOf("cities").forGetter(PresetRE::cities),
                    BuildingSettings.CODEC.optionalFieldOf("buildings").forGetter(PresetRE::buildings),
                    RoadSettings.CODEC.optionalFieldOf("roads").forGetter(PresetRE::roads),
                    HighwaySettings.CODEC.optionalFieldOf("highways").forGetter(PresetRE::highways),
                    RailwaySettings.CODEC.optionalFieldOf("railways").forGetter(PresetRE::railways),
                    DestructionSettings.CODEC.optionalFieldOf("destruction").forGetter(PresetRE::destruction),
                    DecorationSettings.CODEC.optionalFieldOf("decoration").forGetter(PresetRE::decoration),
                    SpawnSettings.CODEC.optionalFieldOf("spawn").forGetter(PresetRE::spawn),
                    AtmosphereSettings.CODEC.optionalFieldOf("atmosphere").forGetter(PresetRE::atmosphere),
                    MiscSettings.CODEC.optionalFieldOf("misc").forGetter(PresetRE::misc)
            ).apply(instance, PresetRE::new));

    public static final Codec<PresetRE> CODEC =
            dev.krona.urbex.worldgen.lost.regassets.data.preset.UnknownKeys.warning(RAW, KEYS, "preset");

    private Identifier name;

    private final Optional<Identifier> extendsId;
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
    private final Optional<AtmosphereSettings> atmosphere;
    private final Optional<MiscSettings> misc;

    public PresetRE(Optional<Identifier> extendsId,
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
                     Optional<AtmosphereSettings> atmosphere,
                     Optional<MiscSettings> misc) {
        this.extendsId = extendsId;
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
        this.atmosphere = atmosphere;
        this.misc = misc;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
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

    public Optional<AtmosphereSettings> atmosphere() {
        return atmosphere;
    }

    public Optional<MiscSettings> misc() {
        return misc;
    }

    /** Applies metadata (if present) then each present section's {@code apply}, onto {@code p}. */
    public void applyTo(Preset p) {
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
        atmosphere.ifPresent(s -> s.apply(p));
        misc.ifPresent(s -> s.apply(p));
    }

    @Override
    public PresetRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }
}
