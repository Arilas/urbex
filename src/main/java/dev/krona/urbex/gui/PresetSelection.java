package dev.krona.urbex.gui;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.Presets;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.worldgen.CityFeature;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import dev.krona.urbex.setup.WorldStyleMix;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side state for "which Urbex preset generates this world", registry-driven since Task 4:
 * every selectable entry (bar the built-in Disabled row) is injected by {@link CitiesTab} from the
 * world-creation {@code RegistryAccess} via {@code Presets.listBrowsable} + {@code Presets.resolve} -
 * this class holds no static preset table of its own and knows nothing about any registry. Pure
 * state - nothing here touches widgets, so it's unit-testable headless.
 */
public final class PresetSelection {

    /** The built-in "no cities" row. Not a real registered preset. */
    public static final Identifier DISABLED_ID = Identifier.fromNamespaceAndPath("urbex", "disabled");
    /** Sentinel id for the transient, hand-edited entry the Customize editor produces. Never a real
     *  registered preset, and never published as-is (see {@link #publish()}). */
    public static final Identifier CUSTOMIZED_ID = Identifier.fromNamespaceAndPath("urbex", "customized");

    private static final Gson GSON = new Gson();

    /**
     * One selectable row. {@code preset} is {@code null} only for the Disabled row; every other
     * entry (built-in or the transient customized one) always carries the resolved {@link Preset} it
     * would generate with. {@code Preset.getId()} on the customized entry's preset is the base
     * preset it was customized from (an unchanged, immutable field that survives {@link Preset#copy()}),
     * which is what {@link #publish()} reports as the preset id underneath the overrides.
     */
    public record Entry(Identifier id, Component name, @Nullable Preset preset) {
    }

    private static final Entry DISABLED_ENTRY =
            new Entry(DISABLED_ID, Component.translatable("urbex.preset.disabled"), null);

    // Declared AFTER everything its constructor / instance-field initializers depend on (chiefly
    // DISABLED_ENTRY, which `selected` initializes to) - see the old class's note on this; the same
    // static-init-order hazard applies here.
    /** The shared client-side selection driving the Cities tab and the customize editor. */
    public static final PresetSelection CLIENT = new PresetSelection();

    /** The remembered choice a Re-Create restore couldn't yet show (no matching injected entry at
     *  the time it ran) - reconciled against {@link #availablePresets} the next time it changes. */
    private record PendingRestore(Identifier presetId, @Nullable String overridesJson) {
    }

    /** Registry-backed browsable presets, injected by {@link CitiesTab}; empty until then. */
    private List<Entry> availablePresets = List.of();

    private Entry selected = DISABLED_ENTRY;
    @Nullable
    private Preset customized = null;

    private List<String> availableWorldStyles = List.of();
    @Nullable
    private WorldStyleMix selectedWorldStyles = null;

    @Nullable
    private PendingRestore pendingRestore = null;

    public PresetSelection() {
    }

    /**
     * Injects the registry-backed presets the Cities tab currently offers: {@code CitiesTab} builds
     * this from {@code Presets.listBrowsable} + {@code Presets.resolve} against the world-creation
     * {@code RegistryAccess} - the enumeration can't be built headless, so it's supplied rather than
     * read here. Re-selects the current choice against the fresh list (a no-op if it's no longer
     * present, matching {@link #select}'s existing "unknown id" rule), and reconciles a Re-Create
     * restore that arrived before any entries existed yet.
     */
    public void setAvailablePresets(List<Entry> entries) {
        this.availablePresets = entries == null ? List.of() : List.copyOf(entries);
        if (pendingRestore != null) {
            reconcilePendingRestore();
        } else {
            select(selected.id());
        }
    }

    /**
     * The full, current list of choices: {@code disabled} first, then the injected browsable presets
     * (already {@code urbex:default} first and the rest in {@code Identifier}'s own path-then-namespace
     * order, not alphabetical on the whole id, per {@code Presets.listBrowsable}), then
     * the transient customized entry (if any) last.
     */
    public List<Entry> entries() {
        List<Entry> result = new ArrayList<>(availablePresets.size() + 2);
        result.add(DISABLED_ENTRY);
        result.addAll(availablePresets);
        if (customized != null) {
            result.add(new Entry(CUSTOMIZED_ID, Component.translatable("urbex.preset.custom"), customized));
        }
        return result;
    }

    /** Selects the entry with the given id. Unknown ids (e.g. a stale id from a rebuilt list) are a no-op. */
    public void select(Identifier id) {
        Entry found = findEntry(id);
        if (found != null) {
            selected = found;
        }
    }

    @Nullable
    private Entry findEntry(Identifier id) {
        for (Entry entry : entries()) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * The worldStyle ids the Cities tab renders in its selector, injected from the live registry.
     * The tab hides the control when this has {@code <= 1} entries (the common "standard-only" case).
     * <p>
     * A style the injected list no longer carries is pruned from the chosen mix rather than
     * clearing the whole selection: turning one datapack off on the Data Packs screen should cost
     * that pack's cities, not the balance the player set up for the others. Only when nothing is
     * left does the override go back to "use the default".
     */
    public void setAvailableWorldStyles(List<String> ids) {
        this.availableWorldStyles = ids == null ? List.of() : List.copyOf(ids);
        if (selectedWorldStyles == null) {
            return;
        }
        List<WorldStyleMix.Entry> kept = new ArrayList<>();
        for (WorldStyleMix.Entry entry : selectedWorldStyles.entries()) {
            if (availableWorldStyles.contains(entry.style().toString())) {
                kept.add(entry);
            }
        }
        selectedWorldStyles = kept.isEmpty() ? null : WorldStyleMix.of(kept);
    }

    /** What the Cities tab renders in its worldStyle selector; empty until injected. */
    public List<String> styleChoices() {
        return availableWorldStyles;
    }

    /**
     * Records the player's chosen worldStyles - orthogonal to the preset (spec 1a): a {@link Preset}
     * carries no worldStyle field of its own any more. {@code null} means "no override - use the
     * default". Doesn't publish - the caller republishes so the change reaches the server.
     */
    public void setWorldStyles(WorldStyleMix styles) {
        this.selectedWorldStyles = styles;
    }

    /** The chosen worldStyle override, or {@code null} for "use the default". */
    @Nullable
    public WorldStyleMix selectedWorldStyles() {
        return selectedWorldStyles;
    }

    /** The worldStyles that will actually generate: the chosen override, or the default. */
    public WorldStyleMix effectiveWorldStyles() {
        return selectedWorldStyles != null ? selectedWorldStyles : Config.DEFAULT_WORLD_STYLE_MIX;
    }

    /**
     * One representative fully-qualified worldStyle id - the mix's primary. For the places that
     * still want a single name: the tab's label when only one style is chosen, and diagnostics. A
     * single selection therefore reads exactly as it did before mixing existed.
     */
    public String effectiveWorldStyle() {
        return effectiveWorldStyles().primary().toString();
    }

    public Entry selected() {
        // Belt-and-suspenders against a static-init-order regression (see CLIENT's declaration): never
        // hand back null, since callers like PresetListWidget dereference selected().id() on first open.
        if (selected == null) {
            selected = DISABLED_ENTRY;
        }
        return selected;
    }

    /** Supplies a hand-edited preset copy from the Customize editor and selects it. */
    public void applyCustomized(Preset copy) {
        this.customized = copy;
        select(CUSTOMIZED_ID);
    }

    public void reset() {
        availablePresets = List.of();
        selected = DISABLED_ENTRY;
        customized = null;
        selectedWorldStyles = null;
        availableWorldStyles = List.of();
        pendingRestore = null;
    }

    /**
     * Restores a preset selection read from an existing world's saved data, for the vanilla
     * Re-Create flow (issue #85), and immediately publishes the raw ids/JSON to {@link Config} -
     * unconditionally, so the choice reaches the server even if the player never opens the Cities tab
     * before creating the world (matching the old editor's restore-from-saved-data path). This needs
     * no registry access, so it never fails on a genuinely unknown preset the way {@code select} would -
     * that check happens server-side, same as any other client-published id (see
     * {@code Config.buildPresetCache}).
     * <p>
     * The <em>visual</em> selection is a separate, best-effort concern: {@code RecreateProfileRestore}
     * runs before {@code CitiesTab} has injected any entries ({@code ScreenEvents.BEFORE_INIT} fires
     * before the tab is built), so there is nothing yet to select against. The attempt is retried by
     * {@link #setAvailablePresets} once real entries exist.
     *
     * @param preset       the saved preset id ({@code namespace:path}); empty means nothing to restore.
     * @param worldStyle   the saved worldStyle id, or empty for {@link Config#DEFAULT_WORLD_STYLE}.
     * @param overridesJson the saved {@code PresetRE} overrides JSON, or empty for a plain preset.
     */
    public void restore(String preset, String worldStyle, String overridesJson) {
        if (preset == null || preset.isEmpty()) {
            return;
        }
        Identifier presetId = Identifier.tryParse(preset);
        if (presetId == null) {
            Urbex.getLogger().warn("Re-created world used a malformed Urbex preset id '{}'; ignoring", preset);
            return;
        }
        // A bare qualified id parses as a one-entry mix, so a world saved before mixing existed
        // restores exactly as it always did.
        WorldStyleMix worldStyles;
        try {
            worldStyles = (worldStyle == null || worldStyle.isEmpty())
                    ? Config.DEFAULT_WORLD_STYLE_MIX : WorldStyleMix.parse(worldStyle);
        } catch (IllegalArgumentException e) {
            Urbex.getLogger().warn("Re-created world used a malformed Urbex worldstyle spec '{}'; using {}.",
                    worldStyle, Config.DEFAULT_WORLD_STYLE_MIX.format());
            worldStyles = Config.DEFAULT_WORLD_STYLE_MIX;
        }
        worldStyles = Config.gateMix(worldStyles, "The re-created world's saved selection");
        // Validated BEFORE publishing, not after: Config.overridesFromClient is read on a worldgen
        // worker thread the moment a chunk generates (CityFeature.getDimensionInfo), so an unparseable
        // string must never reach it - publishing it and only catching the parse failure later (in
        // reconcilePendingRestore's best-effort visual reconciliation) would leave the bad JSON sitting
        // in Config while the visual selection quietly fell back to plain.
        String overrides = validatedOverrides(overridesJson, presetId);

        CityFeature.globalDimensionInfoDirtyCounter++;
        Config.resetPresetCache();
        Config.presetFromClient = presetId;
        Config.worldStyleMixFromClient = worldStyles;
        Config.overridesFromClient = overrides;
        Urbex.getLogger().info("Restored Urbex preset '{}' for world re-creation", presetId);

        this.selectedWorldStyles = worldStyles;
        this.pendingRestore = new PendingRestore(presetId, overrides);
        reconcilePendingRestore();
    }

    /**
     * Validates a saved-data overrides string against {@link PresetRE#CODEC} before it is allowed
     * anywhere near {@link Config#overridesFromClient} - that field is read on a worldgen worker
     * thread the instant a chunk generates ({@code CityFeature.getDimensionInfo}), so a string that
     * fails to parse must never be published in the first place. Returns {@code null} - "plain
     * preset, no overrides" - for a blank input or one that fails to parse (logged either way the
     * failure differs).
     */
    @Nullable
    private static String validatedOverrides(String overridesJson, Identifier presetId) {
        if (overridesJson == null || overridesJson.isEmpty()) {
            return null;
        }
        try {
            PresetRE.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(overridesJson)).getOrThrow();
            return overridesJson;
        } catch (Exception e) {
            Urbex.getLogger().warn("Re-created world '{}' had malformed Urbex preset overrides; " +
                    "restoring it as the plain preset instead.", presetId, e);
            return null;
        }
    }

    /**
     * Tries to turn a pending {@link #restore} into a real visual selection against whatever
     * {@link #availablePresets} currently holds. Leaves the pending restore in place (retried on the
     * next {@link #setAvailablePresets}) if the base preset isn't among them yet. The overrides JSON
     * (if any) was already validated by {@link #restore} before it ever reached {@link Config}, so the
     * parse here is a backstop, not the primary defense - kept anyway so a decode edge case this
     * method's own {@code applyTo}/{@code applyOverrides} step might hit still degrades to "select it
     * plain" instead of throwing out of a widget callback.
     */
    private void reconcilePendingRestore() {
        PendingRestore pending = pendingRestore;
        Entry base = findEntry(pending.presetId());
        if (base == null) {
            // Not found yet (or genuinely unknown - the server-side check will report that once a
            // chunk generates). Keep waiting; the raw Config fields are already correct either way.
            return;
        }
        pendingRestore = null;
        if (pending.overridesJson() == null || base.preset() == null) {
            select(pending.presetId());
            return;
        }
        try {
            PresetRE re = PresetRE.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(pending.overridesJson())).getOrThrow();
            applyCustomized(Presets.applyOverrides(base.preset(), re));
        } catch (Exception e) {
            Urbex.getLogger().warn("Could not rebuild the restored customized preset '{}'; showing it plain.",
                    pending.presetId(), e);
            select(pending.presetId());
        }
    }

    /**
     * Takes back whatever {@link #publish()} or {@link #restore} put in {@link Config}, for a world
     * creation that never happened (issue #113).
     * <p>
     * The three fields are process-global and used to survive an abandoned create screen until the
     * next DISCONNECT, so the next world loaded in the same session - a completely different,
     * already-existing one - generated with them and had them written into its own
     * {@code UrbexData}. Called from {@code CreateWorldScreenTabMixin} on {@code onClose}, which is
     * the abandon path and not the create path; see that mixin for why not {@code ScreenEvents
     * .remove}.
     * <p>
     * The cache reset and the counter bump mirror {@link #publish()}: this changes what the next
     * world would generate with just as much as publishing does.
     */
    public void discardPublication() {
        if (Config.presetFromClient == null && Config.worldStyleMixFromClient == null
                && Config.overridesFromClient == null) {
            return;
        }
        CityFeature.globalDimensionInfoDirtyCounter++;
        Config.resetPresetCache();
        Config.presetFromClient = null;
        Config.worldStyleMixFromClient = null;
        Config.overridesFromClient = null;
        Urbex.getLogger().debug("World creation abandoned; discarded the published Urbex selection");
    }

    /**
     * Publishes the current selection so it reaches world generation: the three {@link Config}
     * fields the server reads in {@code Config.buildPresetCache}.
     * <ul>
     *   <li>Disabled: all three {@code null}.</li>
     *   <li>A plain built-in entry: its own id, the effective worldStyle, no overrides.</li>
     *   <li>The transient customized entry: the <em>base</em> preset id it was customized from
     *       (carried, unchanged, in {@code Preset.getId()} through every {@link Preset#copy()}), the
     *       effective worldStyle, and the full edited preset encoded as a {@link PresetRE} overlay -
     *       so the server rebuilds exactly what the editor showed, not an approximation.</li>
     * </ul>
     */
    public void publish() {
        Entry entry = selected;

        CityFeature.globalDimensionInfoDirtyCounter++;
        Config.resetPresetCache();

        if (DISABLED_ID.equals(entry.id()) || entry.preset() == null) {
            Config.presetFromClient = null;
            Config.worldStyleMixFromClient = null;
            Config.overridesFromClient = null;
            return;
        }

        // Gated here as well as server-side: with experimentalMultiWorldStyles off, what the client
        // publishes must already be what a non-opted-in install would generate.
        Config.worldStyleMixFromClient = Config.gateMix(effectiveWorldStyles(), "The world being created");

        if (CUSTOMIZED_ID.equals(entry.id())) {
            Preset preset = entry.preset();
            Config.presetFromClient = preset.getId();
            PresetRE re = preset.toRE();
            JsonElement json = PresetRE.CODEC.encodeStart(JsonOps.INSTANCE, re).getOrThrow();
            Config.overridesFromClient = GSON.toJson(json);
        } else {
            Config.presetFromClient = entry.id();
            Config.overridesFromClient = null;
        }
    }
}
