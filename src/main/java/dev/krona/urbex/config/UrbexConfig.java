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
 *
 * @param experimentalMultiWorldStyles opts in to selecting several world styles at once, balanced
 *        by weight, so cities from several datapacks can share one world. Off by default, and
 *        gating behaviour rather than only the UI: a save or a config line hand-edited to carry a
 *        mix is reduced to its primary style on an install that never opted in.
 */
public record UrbexConfig(
        List<String> dimensionsWithPresets,
        int heightSampleSize,
        String selectedPreset,
        String selectedWorldStyle,
        int todoQueueSize,
        boolean forceSaplingGrowth,
        int cacheCleanupSeconds,
        List<String> avoidStructures,
        boolean avoidSurfaceStructures,
        boolean structuresYieldToCities,
        boolean avoidVillages,
        boolean avoidFlattening,
        boolean experimentalMultiWorldStyles) {

    public static final UrbexConfig DEFAULT = new UrbexConfig(
            List.of(),
            3,
            "",
            "",
            20,
            true,
            300,
            List.of("minecraft:mansion", "minecraft:jungle_pyramid", "minecraft:desert_pyramid",
                    "minecraft:igloo", "minecraft:swamp_huts", "minecraft:pillager_outpost"),
            false,
            false,
            true,
            true,
            false);

    public static final Codec<UrbexConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().optionalFieldOf("dimensionsWithPresets", DEFAULT.dimensionsWithPresets()).forGetter(UrbexConfig::dimensionsWithPresets),
            Codec.intRange(1, 100).optionalFieldOf("heightSampleSize", DEFAULT.heightSampleSize()).forGetter(UrbexConfig::heightSampleSize),
            Codec.STRING.optionalFieldOf("selectedPreset", DEFAULT.selectedPreset()).forGetter(UrbexConfig::selectedPreset),
            Codec.STRING.optionalFieldOf("selectedWorldStyle", DEFAULT.selectedWorldStyle()).forGetter(UrbexConfig::selectedWorldStyle),
            Codec.intRange(1, 100000).optionalFieldOf("todoQueueSize", DEFAULT.todoQueueSize()).forGetter(UrbexConfig::todoQueueSize),
            Codec.BOOL.optionalFieldOf("forceSaplingGrowth", DEFAULT.forceSaplingGrowth()).forGetter(UrbexConfig::forceSaplingGrowth),
            Codec.intRange(1, 86400).optionalFieldOf("cacheCleanupSeconds", DEFAULT.cacheCleanupSeconds()).forGetter(UrbexConfig::cacheCleanupSeconds),
            Codec.STRING.listOf().optionalFieldOf("avoidStructures", DEFAULT.avoidStructures()).forGetter(UrbexConfig::avoidStructures),
            Codec.BOOL.optionalFieldOf("avoidSurfaceStructures", DEFAULT.avoidSurfaceStructures()).forGetter(UrbexConfig::avoidSurfaceStructures),
            Codec.BOOL.optionalFieldOf("structuresYieldToCities", DEFAULT.structuresYieldToCities()).forGetter(UrbexConfig::structuresYieldToCities),
            Codec.BOOL.optionalFieldOf("avoidVillages", DEFAULT.avoidVillages()).forGetter(UrbexConfig::avoidVillages),
            Codec.BOOL.optionalFieldOf("avoidFlattening", DEFAULT.avoidFlattening()).forGetter(UrbexConfig::avoidFlattening),
            Codec.BOOL.optionalFieldOf("experimentalMultiWorldStyles", DEFAULT.experimentalMultiWorldStyles()).forGetter(UrbexConfig::experimentalMultiWorldStyles)
    ).apply(instance, UrbexConfig::new));

    /**
     * The same fields, all required, used only for writing the global file - see {@link #toFullJson}.
     * Never used to read: a config file is allowed to name only what it changes.
     */
    private static final Codec<UrbexConfig> FULL_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().fieldOf("dimensionsWithPresets").forGetter(UrbexConfig::dimensionsWithPresets),
            Codec.intRange(1, 100).fieldOf("heightSampleSize").forGetter(UrbexConfig::heightSampleSize),
            Codec.STRING.fieldOf("selectedPreset").forGetter(UrbexConfig::selectedPreset),
            Codec.STRING.fieldOf("selectedWorldStyle").forGetter(UrbexConfig::selectedWorldStyle),
            Codec.intRange(1, 100000).fieldOf("todoQueueSize").forGetter(UrbexConfig::todoQueueSize),
            Codec.BOOL.fieldOf("forceSaplingGrowth").forGetter(UrbexConfig::forceSaplingGrowth),
            Codec.intRange(1, 86400).fieldOf("cacheCleanupSeconds").forGetter(UrbexConfig::cacheCleanupSeconds),
            Codec.STRING.listOf().fieldOf("avoidStructures").forGetter(UrbexConfig::avoidStructures),
            Codec.BOOL.fieldOf("avoidSurfaceStructures").forGetter(UrbexConfig::avoidSurfaceStructures),
            Codec.BOOL.fieldOf("structuresYieldToCities").forGetter(UrbexConfig::structuresYieldToCities),
            Codec.BOOL.fieldOf("avoidVillages").forGetter(UrbexConfig::avoidVillages),
            Codec.BOOL.fieldOf("avoidFlattening").forGetter(UrbexConfig::avoidFlattening),
            Codec.BOOL.fieldOf("experimentalMultiWorldStyles").forGetter(UrbexConfig::experimentalMultiWorldStyles)
    ).apply(instance, UrbexConfig::new));

    /** Parses a config from JSON; empty if any present key fails validation. */
    public static Optional<UrbexConfig> fromJson(JsonObject json) {
        return CODEC.parse(JsonOps.INSTANCE, json).result();
    }

    /**
     * Encodes the differences from the defaults. Every key whose value <em>is</em> the default is
     * omitted, because {@link Codec#optionalFieldOf(String, Object)} omits it - which is what makes
     * a world's override file carry only what it changes.
     */
    public static JsonObject toJson(UrbexConfig config) {
        return CODEC.encodeStart(JsonOps.INSTANCE, config).getOrThrow().getAsJsonObject();
    }

    /**
     * Encodes every key, whatever its value.
     *
     * <p>For the global file, which is written back on every start so that it documents itself: a
     * player reading {@code config/urbex/urbex.json} should see the options they could set. With
     * {@link #toJson} they saw a fresh install write {@code {}} and had to go and find the key names
     * somewhere else - the write-back had claimed to be "the full, normalized file" since the
     * ModConfigSpec was replaced by a codec (issue #75), and quietly had not been one.</p>
     *
     * <p>The second codec exists because there is no way to ask a {@link Codec} built from
     * {@code optionalFieldOf} to encode a default anyway. {@link UrbexConfigTest} guards the two
     * against drifting apart, so a field added to the record and not to this one fails a test rather
     * than silently stopping being written.</p>
     */
    public static JsonObject toFullJson(UrbexConfig config) {
        return FULL_CODEC.encodeStart(JsonOps.INSTANCE, config).getOrThrow().getAsJsonObject();
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
