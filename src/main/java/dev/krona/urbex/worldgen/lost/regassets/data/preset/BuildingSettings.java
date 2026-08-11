package dev.krona.urbex.worldgen.lost.regassets.data.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.MultiBuildingStreetConflict;

import java.util.Optional;
import java.util.Set;

public record BuildingSettings(
        Optional<Float> buildingChance,
        Optional<Integer> buildingMinFloors,
        Optional<Integer> buildingMaxFloors,
        Optional<Integer> buildingMinFloorsChance,
        Optional<Integer> buildingMaxFloorsChance,
        Optional<Integer> buildingMinCellars,
        Optional<Integer> buildingMaxCellars,
        Optional<Float> buildingDoorwayChance,
        Optional<Float> buildingFrontChance,
        Optional<Boolean> multiUseCorner,
        Optional<MultiBuildingStreetConflict> multiBuildingStreetConflict,
        Optional<Boolean> generateSpawners) {

    public static final Set<String> KEYS = Set.of("buildingChance", "buildingMinFloors", "buildingMaxFloors", "buildingMinFloorsChance", "buildingMaxFloorsChance", "buildingMinCellars", "buildingMaxCellars", "buildingDoorwayChance", "buildingFrontChance", "multiUseCorner", "multiBuildingStreetConflict", "generateSpawners");

    private static final Codec<MultiBuildingStreetConflict> MULTI_BUILDING_STREET_CONFLICT_CODEC = Codec.STRING.comapFlatMap(
            s -> {
                try {
                    return com.mojang.serialization.DataResult.success(MultiBuildingStreetConflict.byName(s));
                } catch (IllegalArgumentException e) {
                    return com.mojang.serialization.DataResult.error(e::getMessage);
                }
            },
            v -> v.name().toLowerCase(java.util.Locale.ROOT));

    private static final Codec<BuildingSettings> RAW = RecordCodecBuilder.create(i ->
            i.group(
                    Codec.floatRange(0.0f, 1.0f).optionalFieldOf("buildingChance").forGetter(BuildingSettings::buildingChance),
                    Codec.intRange(0, 60).optionalFieldOf("buildingMinFloors").forGetter(BuildingSettings::buildingMinFloors),
                    Codec.intRange(0, 60).optionalFieldOf("buildingMaxFloors").forGetter(BuildingSettings::buildingMaxFloors),
                    Codec.intRange(1, 60).optionalFieldOf("buildingMinFloorsChance").forGetter(BuildingSettings::buildingMinFloorsChance),
                    Codec.intRange(1, 60).optionalFieldOf("buildingMaxFloorsChance").forGetter(BuildingSettings::buildingMaxFloorsChance),
                    Codec.intRange(0, 20).optionalFieldOf("buildingMinCellars").forGetter(BuildingSettings::buildingMinCellars),
                    Codec.intRange(0, 20).optionalFieldOf("buildingMaxCellars").forGetter(BuildingSettings::buildingMaxCellars),
                    Codec.floatRange(0.0f, 1.0f).optionalFieldOf("buildingDoorwayChance").forGetter(BuildingSettings::buildingDoorwayChance),
                    Codec.floatRange(0.0f, 1.0f).optionalFieldOf("buildingFrontChance").forGetter(BuildingSettings::buildingFrontChance),
                    Codec.BOOL.optionalFieldOf("multiUseCorner").forGetter(BuildingSettings::multiUseCorner),
                    MULTI_BUILDING_STREET_CONFLICT_CODEC.optionalFieldOf("multiBuildingStreetConflict").forGetter(BuildingSettings::multiBuildingStreetConflict),
                    Codec.BOOL.optionalFieldOf("generateSpawners").forGetter(BuildingSettings::generateSpawners)
            ).apply(i, BuildingSettings::new));
    public static final Codec<BuildingSettings> CODEC = UnknownKeys.warning(RAW, KEYS, "buildings");

    public void apply(Preset p) {
        buildingChance.ifPresent(v -> p.BUILDING_CHANCE = v);
        buildingMinFloors.ifPresent(v -> p.BUILDING_MINFLOORS = v);
        buildingMaxFloors.ifPresent(v -> p.BUILDING_MAXFLOORS = v);
        buildingMinFloorsChance.ifPresent(v -> p.BUILDING_MINFLOORS_CHANCE = v);
        buildingMaxFloorsChance.ifPresent(v -> p.BUILDING_MAXFLOORS_CHANCE = v);
        buildingMinCellars.ifPresent(v -> p.BUILDING_MINCELLARS = v);
        buildingMaxCellars.ifPresent(v -> p.BUILDING_MAXCELLARS = v);
        buildingDoorwayChance.ifPresent(v -> p.BUILDING_DOORWAYCHANCE = v);
        buildingFrontChance.ifPresent(v -> p.BUILDING_FRONTCHANCE = v);
        multiUseCorner.ifPresent(v -> p.MULTI_USE_CORNER = v);
        multiBuildingStreetConflict.ifPresent(v -> p.MULTI_BUILDING_STREET_CONFLICT = v);
        generateSpawners.ifPresent(v -> p.GENERATE_SPAWNERS = v);
    }
}
