package dev.krona.urbex.setup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.UrbexConfig;
import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.config.ProfileSetup;
import dev.krona.urbex.data.UrbexData;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Configuration access. Values come from the codec-backed {@link UrbexConfig}: the global
 * {@code config/urbex/urbex.json}, optionally overridden per world by
 * {@code <world>/serverconfig/urbex.json}. Legacy Forge Config API Port TOML files are read
 * once and migrated (issue #75).
 * <p>
 * The public surface is kept supplier-shaped ({@code Config.X.get()}) so the many call sites
 * did not have to change when the ModConfigSpec backing was removed.
 */
public class Config {

    public static final boolean DEBUG = false;

    /** The currently active config: global, with the running world's overrides applied. */
    private static volatile UrbexConfig active = UrbexConfig.DEFAULT;
    /** The global config alone, restored when a world's overrides are dropped. */
    private static volatile UrbexConfig global = UrbexConfig.DEFAULT;

    public static final Supplier<String> SPECIAL_BED_BLOCK = () -> active.specialBedBlock();
    public static final Supplier<String> SELECTED_PROFILE = () -> active.selectedProfile();
    public static final Supplier<String> SELECTED_CUSTOM_JSON = () -> active.selectedCustomJson();
    public static final Supplier<Integer> TODO_QUEUE_SIZE = () -> active.todoQueueSize();
    public static final Supplier<Boolean> FORCE_SAPLING_GROWTH = () -> active.forceSaplingGrowth();
    public static final Supplier<Integer> CACHE_CLEANUP_SECONDS = () -> active.cacheCleanupSeconds();
    public static final Supplier<Integer> HEIGHT_SAMPLE_SIZE = () -> active.heightSampleSize();
    public static final Supplier<Boolean> AVOID_STRUCTURES_ADJACENT = () -> active.avoidStructuresAdjacent();
    public static final Supplier<Boolean> AVOID_SURFACE_STRUCTURES = () -> active.avoidSurfaceStructures();
    public static final Supplier<Boolean> STRUCTURES_YIELD_TO_CITIES = () -> active.structuresYieldToCities();
    public static final Supplier<Boolean> AVOID_VILLAGES = () -> active.avoidVillages();
    public static final Supplier<Boolean> AVOID_VILLAGES_ADJACENT = () -> active.avoidVillagesAdjacent();
    public static final Supplier<Boolean> AVOID_FLATTENING = () -> active.avoidFlattening();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Loads the global config from {@code config/urbex/urbex.json}, migrating the legacy
     * {@code common.toml} on first run. Called once from mod init.
     */
    public static void loadGlobal(Path configDir) {
        Path dir = configDir.resolve("urbex");
        Path file = dir.resolve("urbex.json");
        JsonObject json = null;
        if (Files.exists(file)) {
            json = readJson(file);
        } else {
            Path legacy = dir.resolve("common.toml");
            if (Files.exists(legacy)) {
                json = readLegacyToml(legacy);
                Urbex.getLogger().info("Migrating legacy config {} to {}", legacy, file);
            }
        }
        if (json != null) {
            Optional<UrbexConfig> parsed = UrbexConfig.fromJson(json);
            if (parsed.isPresent()) {
                global = parsed.get();
            } else {
                Urbex.getLogger().error("Invalid config in {} - using defaults. Fix or delete the file.", file);
            }
        }
        active = global;
        // Write back the full, normalized file so every available option is visible
        try {
            Files.createDirectories(dir);
            Files.writeString(file, GSON.toJson(UrbexConfig.toJson(global)));
        } catch (IOException e) {
            Urbex.getLogger().error("Could not write {}", file, e);
        }
    }

    /**
     * Applies {@code <world>/serverconfig/urbex.json} (or the legacy
     * {@code urbex-server.toml}) over the global config. Called at SERVER_STARTING, before any
     * worldgen; the merge is per-key, so a world file only carries what it changes.
     */
    public static void applyWorldOverrides(MinecraftServer server) {
        UrbexConfig result = global;
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("serverconfig");
        Path file = dir.resolve("urbex.json");
        JsonObject overrides = null;
        if (Files.exists(file)) {
            overrides = readJson(file);
        } else {
            Path legacy = dir.resolve("urbex-server.toml");
            if (Files.exists(legacy)) {
                overrides = readLegacyToml(legacy);
                Urbex.getLogger().info("Migrating legacy world config {} to {}", legacy, file);
                try {
                    Files.createDirectories(dir);
                    Files.writeString(file, GSON.toJson(overrides));
                } catch (IOException e) {
                    Urbex.getLogger().error("Could not write {}", file, e);
                }
            }
        }
        if (overrides != null && !overrides.isEmpty()) {
            JsonObject merged = UrbexConfig.merge(UrbexConfig.toJson(global), overrides);
            Optional<UrbexConfig> parsed = UrbexConfig.fromJson(merged);
            if (parsed.isPresent()) {
                result = parsed.get();
                Urbex.getLogger().info("Applied {} world config override(s) from {}", overrides.size(), file);
            } else {
                Urbex.getLogger().error("Invalid world config in {} - ignoring it.", file);
            }
        }
        active = result;
        AVOID_STRUCTURES_SET = null;
        resetProfileCache();
    }

    private static JsonObject readJson(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            Urbex.getLogger().error("Could not read {}", file, e);
            return null;
        }
    }

    private static JsonObject readLegacyToml(Path file) {
        try {
            return dev.krona.urbex.config.LegacyToml.toJson(Files.readAllLines(file));
        } catch (IOException e) {
            Urbex.getLogger().error("Could not read {}", file, e);
            return null;
        }
    }

    /**
     * Dimension -> profile name, built once and then published whole.
     * <p>
     * Reached from worker threads: {@code CityFeature} and {@code StructureSuppressor} both call
     * {@link #getProfileForDimension} during generation, and nothing serialises them. It is
     * {@code volatile} so a reader either sees {@code null} or a complete map, and a
     * {@code ConcurrentHashMap} because {@link #registerUrbexDimension} can still add to it
     * after publication. Racing builders produce identical maps; the loser's is dropped.
     */
    private static volatile Map<ResourceKey<Level>, String> dimensionProfileCache = null;

    // Profile as selected by the client
    public static String profileFromClient = null;
    public static String jsonFromClient = null;

    // Lazily filled from avoidStructures by cacheAvoidedStructures(). Must start out null:
    // that method only fills the set when it is still null.
    private static Set<Identifier> AVOID_STRUCTURES_SET = null;

    public static void reset() {
        profileFromClient = null;
        jsonFromClient = null;
        dimensionProfileCache = null;
        AVOID_STRUCTURES_SET = null;
        active = global;
    }

    public static void resetProfileCache() {
        dimensionProfileCache = null;
    }

    // @todo BAD
    public static void registerUrbexDimension(ServerLevel level, ResourceKey<Level> type, String profile) {
        String profileForDimension = getProfileForDimension(level, type);
        if (profileForDimension == null) {
            // getProfileForDimension has published a cache by the time it returns, so read the
            // field once rather than twice - a concurrent reset() must not turn this into an NPE.
            Map<ResourceKey<Level>, String> cache = dimensionProfileCache;
            if (cache != null) {
                cache.put(type, profile);
            }
        }
    }

    public static String getProfileForDimension(ServerLevel level, ResourceKey<Level> type) {
        Map<ResourceKey<Level>, String> cache = dimensionProfileCache;
        if (cache == null) {
            cache = buildProfileCache(level);
            // Published last, and only once it is complete. See the field's comment.
            dimensionProfileCache = cache;
        }
        return cache.get(type);
    }

    private static Map<ResourceKey<Level>, String> buildProfileCache(ServerLevel level) {
        Map<ResourceKey<Level>, String> cache = new ConcurrentHashMap<>();
        for (String dp : active.dimensionsWithProfiles()) {
            String[] split = dp.split("=");
            if (split.length != 2) {
                Urbex.getLogger().error("Bad format for config value: '{}'!", dp);
            } else {
                ResourceKey<Level> dimensionType = ResourceKey.create(Registries.DIMENSION, Identifier.parse(split[0]));
                String profileName = split[1];
                UrbexProfile profile = ProfileSetup.STANDARD_PROFILES.get(profileName);
                if (profile != null) {
                    cache.put(dimensionType, profileName);
                } else {
                    Urbex.getLogger().error("Cannot find profile: {} for dimension {}!", profileName, split[0]);
                }
            }
        }

        UrbexData data = UrbexData.getData(level);
        String selectedProfile = "";
        String selectedJson = "";
        if (Config.profileFromClient != null && !Config.profileFromClient.isEmpty()) {
            if (Config.jsonFromClient != null && !Config.jsonFromClient.isEmpty()) {
                selectedJson = Config.jsonFromClient;
            }
            selectedProfile = Config.profileFromClient;
            // Remember the profile selected by the client in SavedData
            data.setProfile(selectedProfile, selectedJson);
        } else {
            // Check if SavedData has a profile selected
            selectedProfile = data.getSelectedProfile();
            selectedJson = data.getSelectedJson();
            // If this is also empty get from config for the overworld
            if (level.dimension() == Level.OVERWORLD) {
                if (selectedJson.isEmpty()) {
                    selectedJson = Config.SELECTED_CUSTOM_JSON.get();
                }
                if (selectedProfile.isEmpty()) {
                    selectedProfile = Config.SELECTED_PROFILE.get();
                }
            }
        }

        if (!selectedProfile.isEmpty()) {
            cache.put(Level.OVERWORLD, selectedProfile);
            if (!selectedJson.isEmpty()) {
                UrbexProfile profile = new UrbexProfile("customized", selectedJson);
                if (!ProfileSetup.STANDARD_PROFILES.containsKey("customized")) {
                    ProfileSetup.STANDARD_PROFILES.put("customized", new UrbexProfile("customized", false));
                }
                ProfileSetup.STANDARD_PROFILES.get("customized").copyFrom(profile);
            }
        }

        // Read the half-built map directly. This used to call back into getProfileForDimension,
        // which worked only because the field was assigned before it was filled; now that the
        // field is published last, that call would not terminate.
        String profile = cache.get(Level.OVERWORLD);
        if (profile != null && !profile.isEmpty()) {
            if (ProfileSetup.STANDARD_PROFILES.get(profile).GENERATE_NETHER) {
                cache.put(Level.NETHER, "cavern");
            }
        }
        return cache;
    }

    /**
     * Validate that every profile name referenced by config actually exists, so an unknown
     * profile fails loudly at server start with the list of valid names instead of NPEing
     * later in {@link #getProfileForDimension} during world init.
     *
     * Must run after {@link ProfileSetup#setupProfiles()} has populated {@code STANDARD_PROFILES}
     * and after {@link #applyWorldOverrides} so the world's selectedProfile is in effect.
     */
    public static void validateSelectedProfiles() {
        String selected = SELECTED_PROFILE.get();
        if (selected != null && !selected.isEmpty() && !ProfileSetup.STANDARD_PROFILES.containsKey(selected)) {
            throw new IllegalStateException(
                    "Unknown Urbex profile '" + selected + "'. Valid profiles: "
                    + String.join(", ", new TreeSet<>(ProfileSetup.STANDARD_PROFILES.keySet())));
        }
        for (String dp : active.dimensionsWithProfiles()) {
            String[] split = dp.split("=");
            if (split.length == 2) {
                String profileName = split[1];
                if (!ProfileSetup.STANDARD_PROFILES.containsKey(profileName)) {
                    throw new IllegalStateException(
                            "Unknown Urbex profile '" + profileName + "' in dimensionsWithProfiles entry '" + dp
                            + "'. Valid profiles: " + String.join(", ", new TreeSet<>(ProfileSetup.STANDARD_PROFILES.keySet())));
                }
            }
            // Malformed entries (missing '=') are reported by getProfileForDimension itself; not this method's concern.
        }
    }

    public static boolean isAvoidedStructure(Identifier id) {
        cacheAvoidedStructures();
        return AVOID_STRUCTURES_SET.contains(id);
    }

    public static boolean hasAvoidedStructures() {
        cacheAvoidedStructures();
        return !AVOID_STRUCTURES_SET.isEmpty();
    }

    private static void cacheAvoidedStructures() {
        if (AVOID_STRUCTURES_SET == null) {
            Set<Identifier> set = new HashSet<>();
            for (String s : active.avoidStructures()) {
                set.add(Identifier.parse(s));
            }
            AVOID_STRUCTURES_SET = set;
        }
    }
}
