package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
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
import dev.krona.urbex.worldgen.lost.regassets.data.RetiredKeys;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.Set;

/**
 * The datapack-facing preset format: every field is optional, and only the fields actually
 * present in the JSON are applied on top of an {@code extends} chain (see {@code Presets.resolve}).
 * Mirrors the {@code WorldStyleRE} registry-entry idiom.
 */
public class PresetRE implements Extendable {

    public static final Set<String> KEYS = Set.of("extends", "name", "description", "extraDescription", "warning",
            "icon", "terrain", "cities", "buildings", "roads", "highways", "railways", "destruction", "decoration",
            "spawn", "atmosphere", "misc");

    /**
     * The six non-section keys, as one {@link MapCodec} inlined into the preset's own JSON object -
     * {@code "name"} and its five neighbours stay top-level keys, exactly where they were authored.
     * <p>
     * Not a shape anyone asked for: {@code RecordCodecBuilder.group} tops out at sixteen fields, and
     * adding {@code name} made seventeen. Bundling the metadata behind a {@code MapCodec} is the one
     * way to buy a field back without moving a key or nesting one, so this record exists purely to
     * be flattened again and never appears in the format.
     */
    private record Meta(Optional<Identifier> extendsId,
                        Optional<String> name,
                        Optional<String> description,
                        Optional<String> extraDescription,
                        Optional<String> warning,
                        Optional<String> icon) {
    }

    private static final MapCodec<Meta> META = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(Meta::extendsId),
                    Codec.STRING.optionalFieldOf("name").forGetter(Meta::name),
                    Codec.STRING.optionalFieldOf("description").forGetter(Meta::description),
                    Codec.STRING.optionalFieldOf("extraDescription").forGetter(Meta::extraDescription),
                    Codec.STRING.optionalFieldOf("warning").forGetter(Meta::warning),
                    Codec.STRING.optionalFieldOf("icon").forGetter(Meta::icon)
            ).apply(instance, Meta::new));

    private static final Codec<PresetRE> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    META.forGetter(PresetRE::meta),
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

    /**
     * Retired-key rejection outside the unknown-key warning, so {@code inherit}/{@code parent} fail
     * the decode instead of being reported as one more ignorable typo. Presets are the only registry
     * where an unknown key is even mentioned - the other twelve drop it silently - which is exactly
     * why the retired keys cannot be left to that path. See {@link RetiredKeys}.
     */
    public static final Codec<PresetRE> CODEC = RetiredKeys.reject(
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
    private final Optional<AtmosphereSettings> atmosphere;
    private final Optional<MiscSettings> misc;

    public PresetRE(Optional<Identifier> extendsId,
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
                     Optional<AtmosphereSettings> atmosphere,
                     Optional<MiscSettings> misc) {
        this(new Meta(extendsId, displayName, description, extraDescription, warning, icon),
                terrain, cities, buildings, roads, highways, railways, destruction, decoration,
                spawn, atmosphere, misc);
    }

    /** The codec's own constructor; see {@link Meta} for why the metadata arrives bundled. */
    private PresetRE(Meta meta,
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
        this.extendsId = meta.extendsId();
        this.displayName = meta.name();
        this.description = meta.description();
        this.extraDescription = meta.extraDescription();
        this.warning = meta.warning();
        this.icon = meta.icon();
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

    private Meta meta() {
        return new Meta(extendsId, displayName, description, extraDescription, warning, icon);
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

    public Optional<AtmosphereSettings> atmosphere() {
        return atmosphere;
    }

    public Optional<MiscSettings> misc() {
        return misc;
    }

    /** Applies metadata (if present) then each present section's {@code apply}, onto {@code p}. */
    public void applyTo(Preset p) {
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
        atmosphere.ifPresent(s -> s.apply(p));
        misc.ifPresent(s -> s.apply(p));
    }


}
