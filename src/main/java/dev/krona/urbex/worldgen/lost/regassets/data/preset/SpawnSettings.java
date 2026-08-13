package dev.krona.urbex.worldgen.lost.regassets.data.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.config.PresetDraft;
import java.util.List;

import java.util.Optional;
import java.util.Set;

public record SpawnSettings(
        Optional<String> spawnBiome,
        Optional<String> spawnCity,
        Optional<Boolean> spawnNotInBuilding,
        Optional<Boolean> forceSpawnInBuilding,
        Optional<List<String>> forceSpawnBuildings,
        Optional<List<String>> forceSpawnParts,
        Optional<Integer> spawnCheckRadius,
        Optional<Integer> spawnRadiusIncrease,
        Optional<Integer> spawnCheckAttempts) {

    public static final Set<String> KEYS = Set.of("spawnBiome", "spawnCity", "spawnNotInBuilding", "forceSpawnInBuilding", "forceSpawnBuildings", "forceSpawnParts", "spawnCheckRadius", "spawnRadiusIncrease", "spawnCheckAttempts");

    private static final Codec<SpawnSettings> RAW = RecordCodecBuilder.create(i ->
            i.group(
                    Codec.STRING.optionalFieldOf("spawnBiome").forGetter(SpawnSettings::spawnBiome),
                    Codec.STRING.optionalFieldOf("spawnCity").forGetter(SpawnSettings::spawnCity),
                    Codec.BOOL.optionalFieldOf("spawnNotInBuilding").forGetter(SpawnSettings::spawnNotInBuilding),
                    Codec.BOOL.optionalFieldOf("forceSpawnInBuilding").forGetter(SpawnSettings::forceSpawnInBuilding),
                    Codec.STRING.listOf().optionalFieldOf("forceSpawnBuildings").forGetter(SpawnSettings::forceSpawnBuildings),
                    Codec.STRING.listOf().optionalFieldOf("forceSpawnParts").forGetter(SpawnSettings::forceSpawnParts),
                    Codec.intRange(1, 100000).optionalFieldOf("spawnCheckRadius").forGetter(SpawnSettings::spawnCheckRadius),
                    Codec.intRange(1, 100000).optionalFieldOf("spawnRadiusIncrease").forGetter(SpawnSettings::spawnRadiusIncrease),
                    Codec.intRange(1, 1000000).optionalFieldOf("spawnCheckAttempts").forGetter(SpawnSettings::spawnCheckAttempts)
            ).apply(i, SpawnSettings::new));
    public static final Codec<SpawnSettings> CODEC = UnknownKeys.warning(RAW, KEYS, "spawn");

    public void apply(PresetDraft p) {
        spawnBiome.ifPresent(v -> p.SPAWN_BIOME = v);
        spawnCity.ifPresent(v -> p.SPAWN_CITY = v);
        spawnNotInBuilding.ifPresent(v -> p.SPAWN_NOT_IN_BUILDING = v);
        forceSpawnInBuilding.ifPresent(v -> p.FORCE_SPAWN_IN_BUILDING = v);
        forceSpawnBuildings.ifPresent(v -> p.FORCE_SPAWN_BUILDINGS = v);
        forceSpawnParts.ifPresent(v -> p.FORCE_SPAWN_PARTS = v);
        spawnCheckRadius.ifPresent(v -> p.SPAWN_CHECK_RADIUS = v);
        spawnRadiusIncrease.ifPresent(v -> p.SPAWN_RADIUS_INCREASE = v);
        spawnCheckAttempts.ifPresent(v -> p.SPAWN_CHECK_ATTEMPTS = v);
    }
}
