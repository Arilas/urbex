package dev.krona.urbex.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The mod's own configuration, as a plain codec-backed record. Replaces the NeoForge
 * {@code ModConfigSpec} that came in through Forge Config API Port - the last NeoForge artifact
 * in a Fabric-only mod (issue #75).
 * <p>
 * One flat schema serves two files: the global {@code config/urbex/urbex.json} and an optional
 * per-world {@code <world>/serverconfig/urbex.json} whose keys override the global ones (the
 * merge happens at the JSON level, so a world file only needs the keys it changes).
 */
public record UrbexConfig(
        List<String> dimensionsWithProfiles,
        int heightSampleSize,
        String specialBedBlock,
        String selectedProfile,
        String selectedCustomJson,
        int todoQueueSize,
        boolean forceSaplingGrowth,
        int cacheCleanupSeconds,
        List<String> avoidStructures,
        boolean avoidStructuresAdjacent,
        boolean avoidSurfaceStructures,
        boolean structuresYieldToCities,
        boolean avoidVillages,
        boolean avoidVillagesAdjacent,
        boolean avoidFlattening) {

    public static final UrbexConfig DEFAULT = new UrbexConfig(
            List.of("urbex:city=biosphere"),
            3,
            "minecraft:diamond_block",
            "",
            "",
            20,
            true,
            300,
            List.of("minecraft:mansion", "minecraft:jungle_pyramid", "minecraft:desert_pyramid",
                    "minecraft:igloo", "minecraft:swamp_huts", "minecraft:pillager_outpost"),
            false,
            false,
            false,
            true,
            false,
            true);

    public static final Codec<UrbexConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().optionalFieldOf("dimensionsWithProfiles", DEFAULT.dimensionsWithProfiles()).forGetter(UrbexConfig::dimensionsWithProfiles),
            Codec.intRange(1, 100).optionalFieldOf("heightSampleSize", DEFAULT.heightSampleSize()).forGetter(UrbexConfig::heightSampleSize),
            Codec.STRING.optionalFieldOf("specialBedBlock", DEFAULT.specialBedBlock()).forGetter(UrbexConfig::specialBedBlock),
            Codec.STRING.optionalFieldOf("selectedProfile", DEFAULT.selectedProfile()).forGetter(UrbexConfig::selectedProfile),
            Codec.STRING.optionalFieldOf("selectedCustomJson", DEFAULT.selectedCustomJson()).forGetter(UrbexConfig::selectedCustomJson),
            Codec.intRange(1, 100000).optionalFieldOf("todoQueueSize", DEFAULT.todoQueueSize()).forGetter(UrbexConfig::todoQueueSize),
            Codec.BOOL.optionalFieldOf("forceSaplingGrowth", DEFAULT.forceSaplingGrowth()).forGetter(UrbexConfig::forceSaplingGrowth),
            Codec.intRange(1, 86400).optionalFieldOf("cacheCleanupSeconds", DEFAULT.cacheCleanupSeconds()).forGetter(UrbexConfig::cacheCleanupSeconds),
            Codec.STRING.listOf().optionalFieldOf("avoidStructures", DEFAULT.avoidStructures()).forGetter(UrbexConfig::avoidStructures),
            Codec.BOOL.optionalFieldOf("avoidStructuresAdjacent", DEFAULT.avoidStructuresAdjacent()).forGetter(UrbexConfig::avoidStructuresAdjacent),
            Codec.BOOL.optionalFieldOf("avoidSurfaceStructures", DEFAULT.avoidSurfaceStructures()).forGetter(UrbexConfig::avoidSurfaceStructures),
            Codec.BOOL.optionalFieldOf("structuresYieldToCities", DEFAULT.structuresYieldToCities()).forGetter(UrbexConfig::structuresYieldToCities),
            Codec.BOOL.optionalFieldOf("avoidVillages", DEFAULT.avoidVillages()).forGetter(UrbexConfig::avoidVillages),
            Codec.BOOL.optionalFieldOf("avoidVillagesAdjacent", DEFAULT.avoidVillagesAdjacent()).forGetter(UrbexConfig::avoidVillagesAdjacent),
            Codec.BOOL.optionalFieldOf("avoidFlattening", DEFAULT.avoidFlattening()).forGetter(UrbexConfig::avoidFlattening)
    ).apply(instance, UrbexConfig::new));

    /** Parses a config from JSON; empty if any present key fails validation. */
    public static Optional<UrbexConfig> fromJson(JsonObject json) {
        return CODEC.parse(JsonOps.INSTANCE, json).result();
    }

    public static JsonObject toJson(UrbexConfig config) {
        return CODEC.encodeStart(JsonOps.INSTANCE, config).getOrThrow().getAsJsonObject();
    }

    /** Shallow key-level merge: every key the overlay carries replaces the base's. */
    public static JsonObject merge(JsonObject base, JsonObject overlay) {
        JsonObject merged = base.deepCopy();
        for (Map.Entry<String, JsonElement> entry : overlay.entrySet()) {
            merged.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return merged;
    }
}
