package dev.krona.urbex.worldgen.lost.regassets.data.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.config.PresetDraft;

import java.util.Optional;
import java.util.Set;

public record RoadSettings(
        Optional<Integer> primaryRoadSpacingX,
        Optional<Integer> primaryRoadSpacingZ,
        Optional<Float> primaryRoadOptionalChance,
        Optional<Integer> primaryRoadForceEvery,
        Optional<Integer> secondaryRoadMinCountX,
        Optional<Integer> secondaryRoadMaxCountX,
        Optional<Integer> secondaryRoadMinCountZ,
        Optional<Integer> secondaryRoadMaxCountZ,
        Optional<Integer> minimumRoadSeparation,
        Optional<Integer> minimumRoadEdgeDistance,
        Optional<Float> tertiaryRoadChance,
        Optional<Integer> tertiaryRoadMinLength,
        Optional<Integer> tertiaryRoadMaxLength,
        Optional<Float> plannedPrimaryBridgeChance,
        Optional<Integer> plannedPrimaryBridgeMaxLength,
        Optional<Float> openLotParkChance,
        Optional<Boolean> parkElevation,
        Optional<Boolean> parkBorder,
        Optional<Integer> parkStreetThreshold,
        Optional<Float> fountainChance,
        Optional<Float> corridorChance,
        Optional<Float> bridgeChance,
        Optional<Boolean> bridgeSupports) {

    public static final Set<String> KEYS = Set.of("primaryRoadSpacingX", "primaryRoadSpacingZ", "primaryRoadOptionalChance", "primaryRoadForceEvery", "secondaryRoadMinCountX", "secondaryRoadMaxCountX", "secondaryRoadMinCountZ", "secondaryRoadMaxCountZ", "minimumRoadSeparation", "minimumRoadEdgeDistance", "tertiaryRoadChance", "tertiaryRoadMinLength", "tertiaryRoadMaxLength", "plannedPrimaryBridgeChance", "plannedPrimaryBridgeMaxLength", "openLotParkChance", "parkElevation", "parkBorder", "parkStreetThreshold", "fountainChance", "corridorChance", "bridgeChance", "bridgeSupports");

    private record Part1(
            Optional<Integer> primaryRoadSpacingX,
            Optional<Integer> primaryRoadSpacingZ,
            Optional<Float> primaryRoadOptionalChance,
            Optional<Integer> primaryRoadForceEvery,
            Optional<Integer> secondaryRoadMinCountX,
            Optional<Integer> secondaryRoadMaxCountX,
            Optional<Integer> secondaryRoadMinCountZ,
            Optional<Integer> secondaryRoadMaxCountZ,
            Optional<Integer> minimumRoadSeparation,
            Optional<Integer> minimumRoadEdgeDistance,
            Optional<Float> tertiaryRoadChance,
            Optional<Integer> tertiaryRoadMinLength,
            Optional<Integer> tertiaryRoadMaxLength,
            Optional<Float> plannedPrimaryBridgeChance) {
        private static final MapCodec<Part1> CODEC = RecordCodecBuilder.mapCodec(i ->
                i.group(
                        Codec.intRange(8, 128).optionalFieldOf("primaryRoadSpacingX").forGetter(Part1::primaryRoadSpacingX),
                        Codec.intRange(8, 128).optionalFieldOf("primaryRoadSpacingZ").forGetter(Part1::primaryRoadSpacingZ),
                        Codec.floatRange(0.0f, 1.0f).optionalFieldOf("primaryRoadOptionalChance").forGetter(Part1::primaryRoadOptionalChance),
                        Codec.intRange(1, 16).optionalFieldOf("primaryRoadForceEvery").forGetter(Part1::primaryRoadForceEvery),
                        Codec.intRange(0, 128).optionalFieldOf("secondaryRoadMinCountX").forGetter(Part1::secondaryRoadMinCountX),
                        Codec.intRange(0, 128).optionalFieldOf("secondaryRoadMaxCountX").forGetter(Part1::secondaryRoadMaxCountX),
                        Codec.intRange(0, 128).optionalFieldOf("secondaryRoadMinCountZ").forGetter(Part1::secondaryRoadMinCountZ),
                        Codec.intRange(0, 128).optionalFieldOf("secondaryRoadMaxCountZ").forGetter(Part1::secondaryRoadMaxCountZ),
                        Codec.intRange(2, 32).optionalFieldOf("minimumRoadSeparation").forGetter(Part1::minimumRoadSeparation),
                        Codec.intRange(2, 32).optionalFieldOf("minimumRoadEdgeDistance").forGetter(Part1::minimumRoadEdgeDistance),
                        Codec.floatRange(0.0f, 1.0f).optionalFieldOf("tertiaryRoadChance").forGetter(Part1::tertiaryRoadChance),
                        Codec.intRange(1, 32).optionalFieldOf("tertiaryRoadMinLength").forGetter(Part1::tertiaryRoadMinLength),
                        Codec.intRange(1, 32).optionalFieldOf("tertiaryRoadMaxLength").forGetter(Part1::tertiaryRoadMaxLength),
                        Codec.floatRange(0.0f, 1.0f).optionalFieldOf("plannedPrimaryBridgeChance").forGetter(Part1::plannedPrimaryBridgeChance)
                ).apply(i, Part1::new));
    }

    private record Part2(
            Optional<Integer> plannedPrimaryBridgeMaxLength,
            Optional<Float> openLotParkChance,
            Optional<Boolean> parkElevation,
            Optional<Boolean> parkBorder,
            Optional<Integer> parkStreetThreshold,
            Optional<Float> fountainChance,
            Optional<Float> corridorChance,
            Optional<Float> bridgeChance,
            Optional<Boolean> bridgeSupports) {
        private static final MapCodec<Part2> CODEC = RecordCodecBuilder.mapCodec(i ->
                i.group(
                        Codec.intRange(1, 64).optionalFieldOf("plannedPrimaryBridgeMaxLength").forGetter(Part2::plannedPrimaryBridgeMaxLength),
                        Codec.floatRange(0.0f, 1.0f).optionalFieldOf("openLotParkChance").forGetter(Part2::openLotParkChance),
                        Codec.BOOL.optionalFieldOf("parkElevation").forGetter(Part2::parkElevation),
                        Codec.BOOL.optionalFieldOf("parkBorder").forGetter(Part2::parkBorder),
                        Codec.intRange(0, 8).optionalFieldOf("parkStreetThreshold").forGetter(Part2::parkStreetThreshold),
                        Codec.floatRange(0.0f, 1.0f).optionalFieldOf("fountainChance").forGetter(Part2::fountainChance),
                        Codec.floatRange(0.0f, 1.0f).optionalFieldOf("corridorChance").forGetter(Part2::corridorChance),
                        Codec.floatRange(0.0f, 1.0f).optionalFieldOf("bridgeChance").forGetter(Part2::bridgeChance),
                        Codec.BOOL.optionalFieldOf("bridgeSupports").forGetter(Part2::bridgeSupports)
                ).apply(i, Part2::new));
    }

    private static final Codec<RoadSettings> RAW = Codec.mapPair(Part1.CODEC, Part2.CODEC).xmap(
            pair -> new RoadSettings(
                    pair.getFirst().primaryRoadSpacingX(),
                    pair.getFirst().primaryRoadSpacingZ(),
                    pair.getFirst().primaryRoadOptionalChance(),
                    pair.getFirst().primaryRoadForceEvery(),
                    pair.getFirst().secondaryRoadMinCountX(),
                    pair.getFirst().secondaryRoadMaxCountX(),
                    pair.getFirst().secondaryRoadMinCountZ(),
                    pair.getFirst().secondaryRoadMaxCountZ(),
                    pair.getFirst().minimumRoadSeparation(),
                    pair.getFirst().minimumRoadEdgeDistance(),
                    pair.getFirst().tertiaryRoadChance(),
                    pair.getFirst().tertiaryRoadMinLength(),
                    pair.getFirst().tertiaryRoadMaxLength(),
                    pair.getFirst().plannedPrimaryBridgeChance(),
                    pair.getSecond().plannedPrimaryBridgeMaxLength(),
                    pair.getSecond().openLotParkChance(),
                    pair.getSecond().parkElevation(),
                    pair.getSecond().parkBorder(),
                    pair.getSecond().parkStreetThreshold(),
                    pair.getSecond().fountainChance(),
                    pair.getSecond().corridorChance(),
                    pair.getSecond().bridgeChance(),
                    pair.getSecond().bridgeSupports()),
            s -> com.mojang.datafixers.util.Pair.of(
                    new Part1(s.primaryRoadSpacingX(), s.primaryRoadSpacingZ(), s.primaryRoadOptionalChance(), s.primaryRoadForceEvery(), s.secondaryRoadMinCountX(), s.secondaryRoadMaxCountX(), s.secondaryRoadMinCountZ(), s.secondaryRoadMaxCountZ(), s.minimumRoadSeparation(), s.minimumRoadEdgeDistance(), s.tertiaryRoadChance(), s.tertiaryRoadMinLength(), s.tertiaryRoadMaxLength(), s.plannedPrimaryBridgeChance()),
                    new Part2(s.plannedPrimaryBridgeMaxLength(), s.openLotParkChance(), s.parkElevation(), s.parkBorder(), s.parkStreetThreshold(), s.fountainChance(), s.corridorChance(), s.bridgeChance(), s.bridgeSupports()))
    ).codec();
    public static final Codec<RoadSettings> CODEC = UnknownKeys.warning(RAW, KEYS, "roads");

    public void apply(PresetDraft p) {
        primaryRoadSpacingX.ifPresent(v -> p.PRIMARY_ROAD_SPACING_X = v);
        primaryRoadSpacingZ.ifPresent(v -> p.PRIMARY_ROAD_SPACING_Z = v);
        primaryRoadOptionalChance.ifPresent(v -> p.PRIMARY_ROAD_OPTIONAL_CHANCE = v);
        primaryRoadForceEvery.ifPresent(v -> p.PRIMARY_ROAD_FORCE_EVERY = v);
        secondaryRoadMinCountX.ifPresent(v -> p.SECONDARY_ROAD_MIN_COUNT_X = v);
        secondaryRoadMaxCountX.ifPresent(v -> p.SECONDARY_ROAD_MAX_COUNT_X = v);
        secondaryRoadMinCountZ.ifPresent(v -> p.SECONDARY_ROAD_MIN_COUNT_Z = v);
        secondaryRoadMaxCountZ.ifPresent(v -> p.SECONDARY_ROAD_MAX_COUNT_Z = v);
        minimumRoadSeparation.ifPresent(v -> p.MINIMUM_ROAD_SEPARATION = v);
        minimumRoadEdgeDistance.ifPresent(v -> p.MINIMUM_ROAD_EDGE_DISTANCE = v);
        tertiaryRoadChance.ifPresent(v -> p.TERTIARY_ROAD_CHANCE = v);
        tertiaryRoadMinLength.ifPresent(v -> p.TERTIARY_ROAD_MIN_LENGTH = v);
        tertiaryRoadMaxLength.ifPresent(v -> p.TERTIARY_ROAD_MAX_LENGTH = v);
        plannedPrimaryBridgeChance.ifPresent(v -> p.PLANNED_PRIMARY_BRIDGE_CHANCE = v);
        plannedPrimaryBridgeMaxLength.ifPresent(v -> p.PLANNED_PRIMARY_BRIDGE_MAX_LENGTH = v);
        openLotParkChance.ifPresent(v -> p.OPEN_LOT_PARK_CHANCE = v);
        parkElevation.ifPresent(v -> p.PARK_ELEVATION = v);
        parkBorder.ifPresent(v -> p.PARK_BORDER = v);
        parkStreetThreshold.ifPresent(v -> p.PARK_STREET_THRESHOLD = v);
        fountainChance.ifPresent(v -> p.FOUNTAIN_CHANCE = v);
        corridorChance.ifPresent(v -> p.CORRIDOR_CHANCE = v);
        bridgeChance.ifPresent(v -> p.BRIDGE_CHANCE = v);
        bridgeSupports.ifPresent(v -> p.BRIDGE_SUPPORTS = v);
    }
}
