package dev.krona.urbex.setup;

import com.google.gson.JsonParser;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.CitiesTabAccess;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.Presets;
import dev.krona.urbex.config.ConfigRepository;
import dev.krona.urbex.data.UrbexData;
import dev.krona.urbex.worldgen.lost.regassets.PresetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.RetiredPresetKeyException;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Which configuration is in effect, and the decisions taken from it.
 * <p>
 * Reading and writing the files is {@link ConfigRepository}'s job; turning what they say into
 * identifiers and dimension rules is {@link GlobalConfig}'s. What is left here is publication - one
 * slot for the global config and one for the running world's - plus the per-dimension preset choice
 * derived from it (issue #130).
 * <p>
 * The accessors are plain methods. They were {@code Supplier} constants so that call sites written
 * against the NeoForge {@code ModConfigSpec} did not have to change when it was removed (#75); the
 * spec has been gone for two releases and the shape was only ever a migration aid.
 */
public class Config {

    public static final boolean DEBUG = false;

    /** {@code preset[@worldstyle]} entries with no explicit worldstyle resolve to this. */
    public static final Identifier DEFAULT_WORLD_STYLE = Identifier.fromNamespaceAndPath("urbex", "standard");

    /** The single-entry mix every path that does not name its own styles resolves to. */
    public static final WorldStyleMix DEFAULT_WORLD_STYLE_MIX = WorldStyleMix.of(DEFAULT_WORLD_STYLE);

    /** The currently active config: global, with the running world's overrides applied. */
    private static volatile GlobalConfig active = GlobalConfig.DEFAULT;
    /** The global config alone, restored when a world's overrides are dropped. */
    private static volatile GlobalConfig global = GlobalConfig.DEFAULT;

    /** Everything in effect right now, typed. */
    public static GlobalConfig active() {
        return active;
    }

    public static int todoQueueSize() {
        return active.file().todoQueueSize();
    }

    public static boolean forceSaplingGrowth() {
        return active.file().forceSaplingGrowth();
    }

    public static int cacheCleanupSeconds() {
        return active.file().cacheCleanupSeconds();
    }

    /**
     * How many entries one planning cache may hold before it starts evicting.
     *
     * <p>Per cache, not in total. The default of 16384 is roughly a 128x128-chunk region's worth of
     * one kind of answer - far more than a player generates in a session, so it costs nothing in
     * normal play and bounds what a long exploration run can accumulate (issue #132).</p>
     */
    public static int cacheMaxEntries() {
        return active.file().cacheMaxEntries();
    }

    public static int heightSampleSize() {
        return active.file().heightSampleSize();
    }

    public static boolean avoidSurfaceStructures() {
        return active.file().avoidSurfaceStructures();
    }

    public static boolean structuresYieldToCities() {
        return active.file().structuresYieldToCities();
    }

    public static boolean avoidVillages() {
        return active.file().avoidVillages();
    }

    public static boolean avoidFlattening() {
        return active.file().avoidFlattening();
    }

    public static boolean experimentalMultiWorldStyles() {
        return active.file().experimentalMultiWorldStyles();
    }

    /**
     * How much of the Cities tab this install lets the player touch (issue #204).
     * <p>
     * Read on the client, off the <em>global</em> config: the create-world screen runs before any
     * server exists, so a world's own {@code serverconfig} overrides cannot be in effect yet. That is
     * the right file anyway - this is a modpack-shipped decision, not a per-world one.
     */
    public static CitiesTabAccess citiesTabAccess() {
        return active.file().citiesTabAccess();
    }

    /** The global config's own preset selection, or null. What the Cities tab starts on. */
    @Nullable
    public static Identifier configuredPreset() {
        return active.selectedPreset();
    }

    /** The global config's own world styles. Only meaningful alongside {@link #configuredPreset()}. */
    public static WorldStyleMix configuredWorldStyles() {
        return active.selectedWorldStyles();
    }

    /**
     * Applies the {@code experimentalMultiWorldStyles} opt-in to a mix that arrived from anywhere -
     * a config line, a client publication, a saved world. With the flag off a multi-entry mix is
     * reduced to its primary style and the reduction logged.
     * <p>
     * The gate is on the value rather than only on the UI: a save or a config file hand-edited to
     * carry a mix must not quietly get one on an install that never opted in.
     */
    public static WorldStyleMix gateMix(WorldStyleMix mix, String context) {
        return gateMix(mix, experimentalMultiWorldStyles(), context);
    }

    /**
     * @see #gateMix(WorldStyleMix, String)
     *
     * <p>Takes the flag rather than reading it, for the one caller that has to gate a mix while the
     * config carrying the flag is still being built - see {@link GlobalConfig#of}.</p>
     */
    static WorldStyleMix gateMix(WorldStyleMix mix, boolean allowMixes, String context) {
        if (mix.isSingle() || allowMixes) {
            return mix;
        }
        WorldStyleMix reduced = mix.reducedToPrimary();
        Urbex.getLogger().warn("{} names {} world styles, but experimentalMultiWorldStyles is off; "
                + "generating with '{}' alone.", context, mix.entries().size(), reduced.primary());
        return reduced;
    }

    /**
     * Loads the global config. Called once from mod init.
     * <p>
     * Reading the file, migrating a legacy one and writing it back are {@link ConfigRepository}'s
     * business; what happens here is publication - the two slots every other path reads (issue #130).
     */
    public static void loadGlobal(Path configDir) {
        global = GlobalConfig.of(ConfigRepository.loadGlobal(configDir));
        active = global;
    }

    /**
     * Applies this world's own overrides over the global config. Called at SERVER_STARTING, before
     * any worldgen.
     */
    public static void applyWorldOverrides(MinecraftServer server) {
        active = GlobalConfig.of(ConfigRepository.applyWorldOverrides(
                global.file(), server.getWorldPath(LevelResource.ROOT)));
        resetPresetCache();
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

    public static void reset() {
        WorldSelectionHandoff.discard();
        dimensionPresetCache = null;
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
        // Parsed once, when the config was published, rather than per cache build. The messages a
        // malformed entry produces therefore reach the log at load time, where a player can act on
        // them, instead of from whichever worldgen worker first needed the cache.
        for (GlobalConfig.DimensionRule rule : active.dimensionRules()) {
            cache.put(rule.dimension(), rule.choice());
        }

        UrbexData data = UrbexData.getData(level);
        WorldSelectionResolver.Resolution resolution = WorldSelectionResolver.resolve(
                publishedSelection(), savedSelection(data), !data.getSelectedPreset().isEmpty(),
                configuredSelection(), level.dimension() == Level.OVERWORLD).orElse(null);
        Identifier selectedPreset = null;
        WorldStyleMix selectedWorldStyles = null;
        String selectedOverrides = null;
        if (resolution != null) {
            WorldSelection selection = resolution.selection();
            selectedPreset = selection.preset();
            selectedWorldStyles = selection.worldStyles();
            selectedOverrides = selection.patch().orElse(null);
            if (resolution.persist()) {
                data.setChoice(selectedPreset.toString(), selectedWorldStyles,
                        selectedOverrides == null ? "" : selectedOverrides);
            }
        }

        if (selectedPreset != null) {
            RegistryAccess access = level.registryAccess();
            Registry<dev.krona.urbex.worldgen.lost.regassets.PresetDefinition> presets =
                    access.lookupOrThrow(CustomRegistries.PRESET_REGISTRY_KEY);
            Registry<dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition> worldStyles =
                    access.lookupOrThrow(CustomRegistries.WORLDSTYLES_REGISTRY_KEY);
            if (presets.get(selectedPreset).isEmpty()) {
                Urbex.getLogger().error("Unknown Urbex preset '{}' selected for the overworld; ignoring. Valid presets: {}",
                        selectedPreset, String.join(", ", sortedIds(presets)));
                selectedPreset = null;
            } else {
                // Per entry rather than all-or-nothing: one datapack going missing should cost that
                // pack's cities, not the whole mix. Only an empty remainder falls back to the default.
                List<WorldStyleMix.Entry> known = new ArrayList<>();
                for (WorldStyleMix.Entry candidate : selectedWorldStyles.entries()) {
                    if (worldStyles.get(candidate.style()).isPresent()) {
                        known.add(candidate);
                    } else {
                        Urbex.getLogger().error("Unknown Urbex worldstyle '{}' selected for the overworld; "
                                        + "dropping it from the mix. Valid worldstyles: {}",
                                candidate.style(), String.join(", ", sortedIds(worldStyles)));
                    }
                }
                selectedWorldStyles = known.isEmpty() ? DEFAULT_WORLD_STYLE_MIX : WorldStyleMix.of(known);
            }
        }

        if (selectedPreset != null) {
            cache.put(Level.OVERWORLD, new PresetChoice(selectedPreset, selectedWorldStyles, Optional.ofNullable(selectedOverrides)));
        }

        // Read the half-built map directly. This used to call back into getProfileForDimension,
        // which worked only because the field was assigned before it was filled; now that the
        // field is published last, that call would not terminate.
        PresetChoice overworldChoice = cache.get(Level.OVERWORLD);
        if (overworldChoice != null) {
            Preset overworldPreset = Presets.resolve(level.registryAccess(), overworldChoice.preset());
            // The GENERATE_NETHER probe must see the same preset DimensionRuntime.create will
            // actually generate with - including any client-published/saved overrides overlay - or an
            // override that flips GENERATE_NETHER (on or off) would silently not count here.
            if (overworldChoice.overridesJson().isPresent()) {
                try {
                    PresetDefinition re = PresetDefinition.parseOverrides(
                            JsonParser.parseString(overworldChoice.overridesJson().get()));
                    overworldPreset = Presets.applyOverrides(overworldPreset, re);
                } catch (RetiredPresetKeyException e) {
                    throw e;
                } catch (Exception e) {
                    Urbex.getLogger().error("Malformed Urbex preset overrides for the overworld; " +
                            "the GENERATE_NETHER probe will see the un-overridden preset.", e);
                }
            }
            if (overworldPreset.generateNether()) {
                cache.put(Level.NETHER, new PresetChoice(
                        Identifier.fromNamespaceAndPath("urbex", "cavern"), DEFAULT_WORLD_STYLE_MIX, Optional.empty()));
            }
        }
        return cache;
    }

    /**
     * What the create-world screen published, or null. See {@link WorldSelectionHandoff}.
     * <p>
     * Gated again on the way out, not only where it was published: the flag can be off on the
     * install that reads a publication even if it was on where one was written, and a mix must never
     * reach generation on an install that never opted in.
     */
    @Nullable
    private static WorldSelection publishedSelection() {
        WorldSelection published = WorldSelectionHandoff.pending();
        if (published == null) {
            return null;
        }
        return new WorldSelection(published.preset(),
                gateMix(published.worldStyles(), "The world being created"), published.patch());
    }

    /**
     * What this world already recorded, or null.
     * <p>
     * Fail-soft throughout: saved data can be hand-edited between sessions, and a malformed id must
     * degrade to "no selection" rather than taking a worldgen worker down.
     */
    @Nullable
    private static WorldSelection savedSelection(UrbexData data) {
        String savedPreset = data.getSelectedPreset();
        if (savedPreset.isEmpty()) {
            return null;
        }
        Identifier preset = Identifier.tryParse(savedPreset);
        if (preset == null) {
            Urbex.getLogger().error("Malformed saved preset id '{}' in world data; treating the "
                    + "overworld's selection as unset.", savedPreset);
            return null;
        }
        // getSelectedWorldStyles is itself fail-soft, for the same reason.
        String savedOverrides = data.getSelectedOverrides();
        return new WorldSelection(preset,
                gateMix(data.getSelectedWorldStyles(), "This world's saved selection"),
                savedOverrides.isEmpty() ? Optional.empty() : Optional.of(savedOverrides));
    }

    /** The global config's own selection, or null. Already parsed - see {@link GlobalConfig}. */
    @Nullable
    private static WorldSelection configuredSelection() {
        Identifier preset = active.selectedPreset();
        return preset == null ? null : new WorldSelection(preset, active.selectedWorldStyles());
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
        Registry<dev.krona.urbex.worldgen.lost.regassets.PresetDefinition> presets =
                server.registryAccess().lookupOrThrow(CustomRegistries.PRESET_REGISTRY_KEY);
        Registry<dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition> worldStyles =
                server.registryAccess().lookupOrThrow(CustomRegistries.WORLDSTYLES_REGISTRY_KEY);

        Identifier selected = active.selectedPreset();
        if (selected != null) {
            requirePreset(presets, selected, "config selectedPreset '" + selected + "'");
        }
        for (Identifier style : active.selectedWorldStyles().styles()) {
            requireWorldStyle(worldStyles, style, "config selectedWorldStyle '" + style + "'");
        }

        // Over the parsed rules: a malformed entry was reported when the config was published and is
        // not in this list, which is what stops it being reported a second time here.
        for (GlobalConfig.DimensionRule rule : active.dimensionRules()) {
            String where = "dimensionsWithPresets entry for '" + rule.dimension().identifier() + "'";
            requirePreset(presets, rule.choice().preset(), where);
            for (Identifier style : rule.choice().worldStyles().styles()) {
                requireWorldStyle(worldStyles, style, where);
            }
        }
    }

    private static void requirePreset(Registry<dev.krona.urbex.worldgen.lost.regassets.PresetDefinition> presets, Identifier id, String context) {
        if (presets.get(id).isEmpty()) {
            throw new IllegalStateException("Unknown Urbex preset '" + id + "' (" + context + "). Valid presets: "
                    + String.join(", ", sortedIds(presets)));
        }
    }

    private static void requireWorldStyle(Registry<dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition> worldStyles, Identifier id, String context) {
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
        return active.avoidStructures().contains(id);
    }

    public static boolean hasAvoidedStructures() {
        return !active.avoidStructures().isEmpty();
    }
}
