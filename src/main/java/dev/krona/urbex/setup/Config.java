package dev.krona.urbex.setup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.Presets;
import dev.krona.urbex.config.UrbexConfig;
import dev.krona.urbex.data.UrbexData;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
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

    /** {@code preset[@worldstyle]} entries with no explicit worldstyle resolve to this. */
    public static final Identifier DEFAULT_WORLD_STYLE = Identifier.fromNamespaceAndPath("urbex", "standard");

    /** The currently active config: global, with the running world's overrides applied. */
    private static volatile UrbexConfig active = UrbexConfig.DEFAULT;
    /** The global config alone, restored when a world's overrides are dropped. */
    private static volatile UrbexConfig global = UrbexConfig.DEFAULT;

    public static final Supplier<String> SELECTED_PRESET = () -> active.selectedPreset();
    public static final Supplier<String> SELECTED_WORLD_STYLE = () -> active.selectedWorldStyle();
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
        resetPresetCache();
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
     * Dimension -> resolved preset choice, built once and then published whole.
     * <p>
     * Reached from worker threads: {@code CityFeature} calls {@link #getPresetChoiceForDimension}
     * during generation, and nothing serialises callers. It is
     * {@code volatile} so a reader either sees {@code null} or a complete map, and a
     * {@code ConcurrentHashMap} in case a future caller needs to add to it after publication.
     * Racing builders produce identical maps; the loser's is dropped.
     */
    private static volatile Map<ResourceKey<Level>, PresetChoice> dimensionPresetCache = null;

    // Selection as published by the client.
    public static Identifier presetFromClient = null;
    public static Identifier worldStyleFromClient = null;
    public static String overridesFromClient = null;

    // Lazily filled from avoidStructures by cacheAvoidedStructures(). Must start out null:
    // that method only fills the set when it is still null.
    private static Set<Identifier> AVOID_STRUCTURES_SET = null;

    public static void reset() {
        presetFromClient = null;
        worldStyleFromClient = null;
        overridesFromClient = null;
        dimensionPresetCache = null;
        AVOID_STRUCTURES_SET = null;
        active = global;
    }

    public static void resetPresetCache() {
        dimensionPresetCache = null;
    }

    public static PresetChoice getPresetChoiceForDimension(ServerLevel level, ResourceKey<Level> type) {
        Map<ResourceKey<Level>, PresetChoice> cache = dimensionPresetCache;
        if (cache == null) {
            cache = buildPresetCache(level);
            // Published last, and only once it is complete. See the field's comment.
            dimensionPresetCache = cache;
        }
        return cache.get(type);
    }

    /**
     * Parses one {@code dimensionsWithPresets} entry: {@code dimension=preset[@worldstyle]}. The
     * preset and worldstyle names must name their namespace: {@link DataTools#fromName} rejects a
     * bare one rather than defaulting it, so {@code minecraft:overworld=default} is refused and
     * {@code minecraft:overworld=urbex:default} is not. (The dimension id on the left is parsed by
     * {@link Identifier#parse} and does still default, to {@code minecraft} - it is a vanilla id,
     * not a datapack cross-reference.) Malformed entries - wrong arity on either side of {@code =},
     * or an id that fails to parse - are logged and rejected rather than thrown, so one bad line in
     * the config doesn't take the whole list down.
     * <p>
     * The rejection messages below carry {@code e.getMessage()} through, because for the two
     * {@code fromName} calls that is the only place the "add a namespace, e.g. urbex:default" hint
     * exists - and a config written before namespaces were mandatory is exactly the case that hits
     * it, so it is the one message that user will see.
     */
    public static Optional<Map.Entry<ResourceKey<Level>, PresetChoice>> parseDimensionPresetEntry(String entry) {
        String[] split = entry.split("=");
        if (split.length != 2) {
            Urbex.getLogger().error("Bad format for config value: '{}'! Expected 'dimension=preset[@worldstyle]'.", entry);
            return Optional.empty();
        }
        ResourceKey<Level> dimensionType;
        try {
            dimensionType = ResourceKey.create(Registries.DIMENSION, Identifier.parse(split[0]));
        } catch (Exception e) {
            Urbex.getLogger().error("Bad dimension id in config value: '{}'!", entry);
            return Optional.empty();
        }

        String presetPart = split[1];
        String presetName = presetPart;
        Identifier worldStyle = DEFAULT_WORLD_STYLE;
        int at = presetPart.indexOf('@');
        if (at >= 0) {
            presetName = presetPart.substring(0, at);
            String stylePart = presetPart.substring(at + 1);
            try {
                worldStyle = DataTools.fromName(stylePart);
            } catch (Exception e) {
                Urbex.getLogger().error("Bad worldstyle id in config value: '{}'! {}", entry, e.getMessage());
                return Optional.empty();
            }
        }

        Identifier presetId;
        try {
            presetId = DataTools.fromName(presetName);
        } catch (Exception e) {
            Urbex.getLogger().error("Bad preset id in config value: '{}'! {}", entry, e.getMessage());
            return Optional.empty();
        }

        return Optional.of(Map.entry(dimensionType, new PresetChoice(presetId, worldStyle, Optional.empty())));
    }

    /**
     * Mirrors the old profile-cache build, three-valued now: {@code dimensionsWithPresets}
     * entries first, then the overworld's own selection - client-published (persisted into
     * {@link UrbexData}), else the world's saved selection, else (overworld only) the global
     * config's {@code selectedPreset}/{@code selectedWorldStyle} - which replaces whatever a
     * config entry put at {@link Level#OVERWORLD}, exactly as the old flow did. Finally, if the
     * resolved overworld preset has {@code GENERATE_NETHER} set, the nether is pointed at
     * {@code urbex:cavern}, overriding any explicit nether entry - also unchanged from before.
     * <p>
     * {@code dimensionsWithPresets} entries and the global config's own selection are already
     * checked once at server start by {@link #validateSelectedPresets}, but the client-published
     * and saved-data ids are not - a player's client or an old/hand-edited save can hand this
     * method an id nothing validated. So whatever ends up selected for the overworld here is
     * resolved against the live registries right before publication, same as
     * {@link #validateSelectedPresets}'s checks, just logging instead of throwing: this runs on a
     * worldgen worker thread while a chunk is generating, not at a point a player can act on, so an
     * unknown id is reported and the selection dropped (worldstyle falls back to
     * {@link #DEFAULT_WORLD_STYLE}) rather than taking generation down.
     */
    private static Map<ResourceKey<Level>, PresetChoice> buildPresetCache(ServerLevel level) {
        Map<ResourceKey<Level>, PresetChoice> cache = new ConcurrentHashMap<>();
        for (String dp : active.dimensionsWithPresets()) {
            parseDimensionPresetEntry(dp).ifPresent(e -> cache.put(e.getKey(), e.getValue()));
        }

        UrbexData data = UrbexData.getData(level);
        Identifier selectedPreset = null;
        Identifier selectedWorldStyle = null;
        String selectedOverrides = null;

        if (presetFromClient != null) {
            selectedPreset = presetFromClient;
            selectedWorldStyle = worldStyleFromClient != null ? worldStyleFromClient : DEFAULT_WORLD_STYLE;
            selectedOverrides = overridesFromClient;
            // Remember the client's selection in SavedData.
            data.setChoice(selectedPreset.toString(), selectedWorldStyle.toString(),
                    selectedOverrides == null ? "" : selectedOverrides);
        } else {
            String savedPreset = data.getSelectedPreset();
            if (!savedPreset.isEmpty()) {
                selectedPreset = Identifier.tryParse(savedPreset);
                if (selectedPreset == null) {
                    Urbex.getLogger().error("Malformed saved preset id '{}' in world data; treating the overworld's selection as unset.", savedPreset);
                } else {
                    String savedStyle = data.getSelectedWorldStyle();
                    if (savedStyle.isEmpty()) {
                        selectedWorldStyle = DEFAULT_WORLD_STYLE;
                    } else {
                        selectedWorldStyle = Identifier.tryParse(savedStyle);
                        if (selectedWorldStyle == null) {
                            Urbex.getLogger().error("Malformed saved worldstyle id '{}' in world data; using {}.", savedStyle, DEFAULT_WORLD_STYLE);
                            selectedWorldStyle = DEFAULT_WORLD_STYLE;
                        }
                    }
                    String savedOverrides = data.getSelectedOverrides();
                    selectedOverrides = savedOverrides.isEmpty() ? null : savedOverrides;
                }
            } else if (level.dimension() == Level.OVERWORLD) {
                String globalPreset = Config.SELECTED_PRESET.get();
                if (globalPreset != null && !globalPreset.isEmpty()) {
                    selectedPreset = DataTools.fromName(globalPreset);
                    String globalStyle = Config.SELECTED_WORLD_STYLE.get();
                    selectedWorldStyle = globalStyle == null || globalStyle.isEmpty()
                            ? DEFAULT_WORLD_STYLE : DataTools.fromName(globalStyle);
                }
            }
        }

        if (selectedPreset != null) {
            RegistryAccess access = level.registryAccess();
            Registry<dev.krona.urbex.worldgen.lost.regassets.PresetRE> presets =
                    access.lookupOrThrow(CustomRegistries.PRESET_REGISTRY_KEY);
            Registry<dev.krona.urbex.worldgen.lost.regassets.WorldStyleRE> worldStyles =
                    access.lookupOrThrow(CustomRegistries.WORLDSTYLES_REGISTRY_KEY);
            if (presets.get(selectedPreset).isEmpty()) {
                Urbex.getLogger().error("Unknown Urbex preset '{}' selected for the overworld; ignoring. Valid presets: {}",
                        selectedPreset, String.join(", ", sortedIds(presets)));
                selectedPreset = null;
            } else if (worldStyles.get(selectedWorldStyle).isEmpty()) {
                Urbex.getLogger().error("Unknown Urbex worldstyle '{}' selected for the overworld; using {}. Valid worldstyles: {}",
                        selectedWorldStyle, DEFAULT_WORLD_STYLE, String.join(", ", sortedIds(worldStyles)));
                selectedWorldStyle = DEFAULT_WORLD_STYLE;
            }
        }

        if (selectedPreset != null) {
            cache.put(Level.OVERWORLD, new PresetChoice(selectedPreset, selectedWorldStyle, Optional.ofNullable(selectedOverrides)));
        }

        // Read the half-built map directly. This used to call back into getProfileForDimension,
        // which worked only because the field was assigned before it was filled; now that the
        // field is published last, that call would not terminate.
        PresetChoice overworldChoice = cache.get(Level.OVERWORLD);
        if (overworldChoice != null) {
            Preset overworldPreset = Presets.resolve(level.registryAccess(), overworldChoice.preset());
            // The GENERATE_NETHER probe must see the same preset CityFeature.getDimensionInfo will
            // actually generate with - including any client-published/saved overrides overlay - or an
            // override that flips GENERATE_NETHER (on or off) would silently not count here.
            if (overworldChoice.overridesJson().isPresent()) {
                try {
                    PresetRE re = PresetRE.CODEC.parse(JsonOps.INSTANCE,
                            JsonParser.parseString(overworldChoice.overridesJson().get())).getOrThrow();
                    overworldPreset = Presets.applyOverrides(overworldPreset, re);
                } catch (Exception e) {
                    Urbex.getLogger().error("Malformed Urbex preset overrides for the overworld; " +
                            "the GENERATE_NETHER probe will see the un-overridden preset.", e);
                }
            }
            if (overworldPreset.GENERATE_NETHER) {
                cache.put(Level.NETHER, new PresetChoice(
                        Identifier.fromNamespaceAndPath("urbex", "cavern"), DEFAULT_WORLD_STYLE, Optional.empty()));
            }
        }
        return cache;
    }

    /**
     * Validate that every preset id referenced by config actually exists, so an unknown preset
     * fails loudly at server start with the list of valid ids instead of blowing up later in
     * {@link #getPresetChoiceForDimension} during world init. Same for every referenced worldstyle
     * id.
     *
     * Must run after {@link #applyWorldOverrides} so the world's selectedPreset is in effect.
     */
    public static void validateSelectedPresets(MinecraftServer server) {
        Registry<dev.krona.urbex.worldgen.lost.regassets.PresetRE> presets =
                server.registryAccess().lookupOrThrow(CustomRegistries.PRESET_REGISTRY_KEY);
        Registry<dev.krona.urbex.worldgen.lost.regassets.WorldStyleRE> worldStyles =
                server.registryAccess().lookupOrThrow(CustomRegistries.WORLDSTYLES_REGISTRY_KEY);

        String selected = SELECTED_PRESET.get();
        if (selected != null && !selected.isEmpty()) {
            requirePreset(presets, DataTools.fromName(selected), "config selectedPreset '" + selected + "'");
        }
        String selectedStyle = SELECTED_WORLD_STYLE.get();
        if (selectedStyle != null && !selectedStyle.isEmpty()) {
            requireWorldStyle(worldStyles, DataTools.fromName(selectedStyle), "config selectedWorldStyle '" + selectedStyle + "'");
        }

        for (String dp : active.dimensionsWithPresets()) {
            Optional<Map.Entry<ResourceKey<Level>, PresetChoice>> parsed = parseDimensionPresetEntry(dp);
            // Malformed entries are reported by parseDimensionPresetEntry itself; not this method's concern.
            parsed.ifPresent(e -> {
                requirePreset(presets, e.getValue().preset(), "dimensionsWithPresets entry '" + dp + "'");
                requireWorldStyle(worldStyles, e.getValue().worldStyle(), "dimensionsWithPresets entry '" + dp + "'");
            });
        }
    }

    private static void requirePreset(Registry<dev.krona.urbex.worldgen.lost.regassets.PresetRE> presets, Identifier id, String context) {
        if (presets.get(id).isEmpty()) {
            throw new IllegalStateException("Unknown Urbex preset '" + id + "' (" + context + "). Valid presets: "
                    + String.join(", ", sortedIds(presets)));
        }
    }

    private static void requireWorldStyle(Registry<dev.krona.urbex.worldgen.lost.regassets.WorldStyleRE> worldStyles, Identifier id, String context) {
        if (worldStyles.get(id).isEmpty()) {
            throw new IllegalStateException("Unknown Urbex worldstyle '" + id + "' (" + context + "). Valid worldstyles: "
                    + String.join(", ", sortedIds(worldStyles)));
        }
    }

    /** Every id a registry holds, sorted, for "valid ids are ..." diagnostics. */
    private static List<String> sortedIds(Registry<?> registry) {
        List<String> ids = new ArrayList<>();
        registry.keySet().forEach(i -> ids.add(i.toString()));
        Collections.sort(ids);
        return ids;
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
