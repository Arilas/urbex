package dev.krona.urbex.worldgen.lost.regassets.data.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.config.Preset;

import java.util.Optional;
import java.util.Set;

public record RailwaySettings(
        Optional<Boolean> railwaysEnabled,
        Optional<Boolean> railwayStationsEnabled,
        Optional<Boolean> railwaySurfaceStationsEnabled,
        Optional<Boolean> railwaysCanEnd,
        Optional<Float> railwayDungeonChance) {

    public static final Set<String> KEYS = Set.of("railwaysEnabled", "railwayStationsEnabled", "railwaySurfaceStationsEnabled", "railwaysCanEnd", "railwayDungeonChance");

    private static final Codec<RailwaySettings> RAW = RecordCodecBuilder.create(i ->
            i.group(
                    Codec.BOOL.optionalFieldOf("railwaysEnabled").forGetter(RailwaySettings::railwaysEnabled),
                    Codec.BOOL.optionalFieldOf("railwayStationsEnabled").forGetter(RailwaySettings::railwayStationsEnabled),
                    Codec.BOOL.optionalFieldOf("railwaySurfaceStationsEnabled").forGetter(RailwaySettings::railwaySurfaceStationsEnabled),
                    Codec.BOOL.optionalFieldOf("railwaysCanEnd").forGetter(RailwaySettings::railwaysCanEnd),
                    Codec.floatRange(0f, 1f).optionalFieldOf("railwayDungeonChance").forGetter(RailwaySettings::railwayDungeonChance)
            ).apply(i, RailwaySettings::new));
    public static final Codec<RailwaySettings> CODEC = UnknownKeys.warning(RAW, KEYS, "railways");

    public void apply(Preset p) {
        railwaysEnabled.ifPresent(v -> p.RAILWAYS_ENABLED = v);
        railwayStationsEnabled.ifPresent(v -> p.RAILWAY_STATIONS_ENABLED = v);
        railwaySurfaceStationsEnabled.ifPresent(v -> p.RAILWAY_SURFACE_STATIONS_ENABLED = v);
        railwaysCanEnd.ifPresent(v -> p.RAILWAYS_CAN_END = v);
        railwayDungeonChance.ifPresent(v -> p.RAILWAY_DUNGEON_CHANCE = v);
    }
}
