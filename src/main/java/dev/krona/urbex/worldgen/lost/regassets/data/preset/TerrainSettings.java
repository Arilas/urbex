package dev.krona.urbex.worldgen.lost.regassets.data.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.LandscapeType;

import java.util.Optional;
import java.util.Set;

public record TerrainSettings(
        Optional<LandscapeType> landscapeType,
        Optional<Integer> groundLevel,
        Optional<Integer> seaLevel,
        Optional<String> liquidBlock,
        Optional<String> baseBlock,
        Optional<Integer> bedrockLayer,
        Optional<Integer> terrainFixLowerMinOffset,
        Optional<Integer> terrainFixLowerMaxOffset,
        Optional<Integer> terrainFixUpperMinOffset,
        Optional<Integer> terrainFixUpperMaxOffset,
        Optional<Integer> oceanCorrectionBorder,
        Optional<Boolean> avoidWater,
        Optional<Boolean> useAvgHeightmap) {

    public static final Set<String> KEYS = Set.of("landscapeType", "groundLevel", "seaLevel", "liquidBlock", "baseBlock", "bedrockLayer", "terrainFixLowerMinOffset", "terrainFixLowerMaxOffset", "terrainFixUpperMinOffset", "terrainFixUpperMaxOffset", "oceanCorrectionBorder", "avoidWater", "useAvgHeightmap");

    private static final Codec<LandscapeType> LANDSCAPE_TYPE_CODEC = Codec.STRING.comapFlatMap(
            s -> {
                LandscapeType type = LandscapeType.getTypeByName(s);
                return type == null
                        ? com.mojang.serialization.DataResult.error(() -> "Unknown landscapeType '" + s + "'")
                        : com.mojang.serialization.DataResult.success(type);
            },
            LandscapeType::getName);

    private static final Codec<TerrainSettings> RAW = RecordCodecBuilder.create(i ->
            i.group(
                    LANDSCAPE_TYPE_CODEC.optionalFieldOf("landscapeType").forGetter(TerrainSettings::landscapeType),
                    Codec.intRange(2, 256).optionalFieldOf("groundLevel").forGetter(TerrainSettings::groundLevel),
                    Codec.intRange(-1, 256).optionalFieldOf("seaLevel").forGetter(TerrainSettings::seaLevel),
                    Codec.STRING.optionalFieldOf("liquidBlock").forGetter(TerrainSettings::liquidBlock),
                    Codec.STRING.optionalFieldOf("baseBlock").forGetter(TerrainSettings::baseBlock),
                    Codec.intRange(0, 10).optionalFieldOf("bedrockLayer").forGetter(TerrainSettings::bedrockLayer),
                    Codec.intRange(-40, 40).optionalFieldOf("terrainFixLowerMinOffset").forGetter(TerrainSettings::terrainFixLowerMinOffset),
                    Codec.intRange(-40, 40).optionalFieldOf("terrainFixLowerMaxOffset").forGetter(TerrainSettings::terrainFixLowerMaxOffset),
                    Codec.intRange(-40, 40).optionalFieldOf("terrainFixUpperMinOffset").forGetter(TerrainSettings::terrainFixUpperMinOffset),
                    Codec.intRange(-40, 40).optionalFieldOf("terrainFixUpperMaxOffset").forGetter(TerrainSettings::terrainFixUpperMaxOffset),
                    Codec.intRange(-255, 255).optionalFieldOf("oceanCorrectionBorder").forGetter(TerrainSettings::oceanCorrectionBorder),
                    Codec.BOOL.optionalFieldOf("avoidWater").forGetter(TerrainSettings::avoidWater),
                    Codec.BOOL.optionalFieldOf("useAvgHeightmap").forGetter(TerrainSettings::useAvgHeightmap)
            ).apply(i, TerrainSettings::new));
    public static final Codec<TerrainSettings> CODEC = UnknownKeys.warning(RAW, KEYS, "terrain");

    public void apply(Preset p) {
        landscapeType.ifPresent(v -> p.LANDSCAPE_TYPE = v);
        groundLevel.ifPresent(v -> p.GROUNDLEVEL = v);
        seaLevel.ifPresent(v -> p.SEALEVEL = v);
        liquidBlock.ifPresent(v -> p.LIQUID_BLOCK = v);
        baseBlock.ifPresent(v -> p.BASE_BLOCK = v);
        bedrockLayer.ifPresent(v -> p.BEDROCK_LAYER = v);
        terrainFixLowerMinOffset.ifPresent(v -> p.TERRAIN_FIX_LOWER_MIN_OFFSET = v);
        terrainFixLowerMaxOffset.ifPresent(v -> p.TERRAIN_FIX_LOWER_MAX_OFFSET = v);
        terrainFixUpperMinOffset.ifPresent(v -> p.TERRAIN_FIX_UPPER_MIN_OFFSET = v);
        terrainFixUpperMaxOffset.ifPresent(v -> p.TERRAIN_FIX_UPPER_MAX_OFFSET = v);
        oceanCorrectionBorder.ifPresent(v -> p.OCEAN_CORRECTION_BORDER = v);
        avoidWater.ifPresent(v -> p.AVOID_WATER = v);
        useAvgHeightmap.ifPresent(v -> p.USE_AVG_HEIGHTMAP = v);
    }
}