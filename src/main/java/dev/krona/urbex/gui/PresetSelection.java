package dev.krona.urbex.gui;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.config.Presets;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.setup.WorldSelection;
import dev.krona.urbex.setup.WorldSelectionHandoff;
import dev.krona.urbex.worldgen.lost.regassets.PresetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.RetiredPresetKeyException;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import dev.krona.urbex.setup.WorldStyleMix;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
     * One selectable row. {@code preset} is {@code null} for the Disabled row and for the synthesized
     * "unlisted" row (see {@link #unlisted}); every other entry - built-in or the transient
     * customized one - carries the resolved {@link Preset} it would generate with.
     * {@code Preset.getId()} on the customized entry's preset is the base preset it was customized
     * from (an unchanged, immutable field that survives {@link Preset#toDraft()}), which is what
     * {@link #publish()} reports as the preset id underneath the overrides.
     * <p>
     * A {@code null} preset therefore means "nothing here can be resolved, edited or previewed", not
     * "nothing will generate" - the unlisted row publishes a real selection. Callers that mean the
     * latter must test {@code id} against {@link #DISABLED_ID}.
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

    /**
     * A restored selection whose preset the enabled datapacks do not offer - a pack turned off since
     * the world was made, or one that never marked its preset browsable.
     *
     * <p>It gets a row of its own so the tab shows what the world will actually generate with.
     * Before this it showed Disabled while {@link #restore} had already published the saved id, so
     * the screen said "no cities" about a world that was about to have them (issue #202).</p>
     */
    private record Unlisted(Identifier presetId, @Nullable String overridesJson) {
    }

    /** Registry-backed browsable presets, injected by {@link CitiesTab}; empty until then. */
    private List<Entry> availablePresets = List.of();

    private Entry selected = DISABLED_ENTRY;
    @Nullable
    private Preset customized = null;
    /** The preset the {@link #customized} draft was edited from, for the row's label and Revert. */
    @Nullable
    private Entry customizedBase = null;
    @Nullable
    private Unlisted unlisted = null;

    private List<String> availableWorldStyles = List.of();
    @Nullable
    private WorldStyleMix selectedWorldStyles = null;

    @Nullable
    private PendingRestore pendingRestore = null;

    /**
     * One-shot latch for {@link #applyConfiguredDefault}. The Cities tab is rebuilt on every
     * {@code CreateWorldScreen.init()} - every window resize included - so without this a player who
     * deliberately chose Disabled would have the modpack's default put back the next time they
     * resized the window.
     */
    private boolean configuredDefaultApplied = false;

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
        // A pack that was turned back on since the unlisted row was synthesized offers the real
        // entry again; prefer it, so the exceptional row does not outlive the condition it reports.
        if (unlisted != null && findAvailable(unlisted.presetId()) != null) {
            unlisted = null;
        }
        // The customized row is positioned relative to its base, so the base has to be re-resolved
        // against every fresh injection - the Entry objects are rebuilt each time.
        if (customized != null) {
            customizedBase = findAvailable(customized.getId());
        }
        if (pendingRestore != null) {
            reconcilePendingRestore();
        } else {
            select(selected.id());
        }
    }

    /**
     * The full, current list of choices: {@code disabled} first, then the unlisted row when a restore
     * produced one, then the injected browsable presets (already {@code urbex:default} first and the
     * rest in {@code Identifier}'s own path-then-namespace order, not alphabetical on the whole id,
     * per {@code Presets.listBrowsable}).
     * <p>
     * The transient customized entry sits <em>directly after the preset it was customized from</em>
     * rather than at the end of the list (issue #201). Appended last it was row 14 of 14 with the
     * shipped presets, which on a list too short to show them all put it off-screen - so pressing
     * Done in the editor appeared to do nothing at all. Next to its base it lands where the player
     * was already looking. A customization whose base is no longer injected still goes last, since
     * there is nothing left to sit beside.
     */
    public List<Entry> entries() {
        List<Entry> result = new ArrayList<>(availablePresets.size() + 3);
        result.add(DISABLED_ENTRY);
        if (unlisted != null) {
            result.add(unlistedEntry());
        }
        boolean customPlaced = false;
        for (Entry entry : availablePresets) {
            result.add(entry);
            if (customized != null && customizedBase != null && customizedBase.id().equals(entry.id())) {
                result.add(customizedEntry());
                customPlaced = true;
            }
        }
        if (customized != null && !customPlaced) {
            result.add(customizedEntry());
        }
        return result;
    }

    /**
     * The customized row. Named for the preset it was edited from and marked with the same {@code *}
     * the editor puts in its own title, because "Custom" alone - over the base preset's icon and
     * description, both of which read through - was indistinguishable from another stock preset.
     */
    private Entry customizedEntry() {
        Component name = customizedBase == null
                ? Component.translatable("urbex.preset.custom")
                : Component.translatable("urbex.preset.custom.of", customizedBase.name());
        return new Entry(CUSTOMIZED_ID, name, customized);
    }

    /** The row standing in for a saved preset the enabled datapacks do not offer. See {@link Unlisted}. */
    private Entry unlistedEntry() {
        return new Entry(unlisted.presetId(),
                Component.translatable("urbex.preset.unlisted", unlisted.presetId().toString()), null);
    }

    @Nullable
    private Entry findAvailable(Identifier id) {
        for (Entry entry : availablePresets) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Starts the tab on the selection the global config names, for modpacks that are built around a
     * particular preset (issue #204). Call after {@link #setAvailablePresets}, once per screen.
     * <p>
     * Deliberately narrow. It applies only on the first call, and only when nothing else has already
     * spoken for the selection: a Re-Create restore (pending or already reconciled into an unlisted
     * row) is this world's own history and outranks a pack default, and a selection that is no longer
     * Disabled means the player has already chosen. Everything about it is best-effort - a configured
     * preset the datapacks do not offer simply leaves the tab on Disabled, and the server still
     * resolves and reports the id through {@code Config.buildPresetCache}.
     *
     * @return whether the selection was actually changed, so the caller knows to publish
     */
    public boolean applyConfiguredDefault(@Nullable Identifier preset, @Nullable WorldStyleMix styles) {
        if (configuredDefaultApplied) {
            return false;
        }
        configuredDefaultApplied = true;
        if (preset == null || pendingRestore != null || unlisted != null
                || !DISABLED_ID.equals(selected.id()) || findEntry(preset) == null) {
            return false;
        }
        select(preset);
        if (styles != null) {
            selectedWorldStyles = styles;
        }
        return true;
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
     * that pack's cities, not the balance the player set up for the others.
     * <p>
     * Two things this deliberately does <em>not</em> do (issue #202). An <strong>empty</strong>
     * injected list is "the registry was not reachable" - {@code CitiesTab.registeredWorldStyles}
     * returns {@code Map.of()} for that - and not "every style you chose is invalid", so it prunes
     * nothing; this runs on every tab construction, window resizes included, and it used to be able
     * to quietly reset a restored or hand-picked style back to the default and then republish the
     * default on the next click. And a mix that prunes away to nothing keeps its primary rather than
     * falling back to {@code null}: the client does not get to silently rewrite the player's choice,
     * and an id no registry knows is reported and dropped server-side by
     * {@code Config.buildPresetCache}, which is where that message is worth reading.
     */
    public void setAvailableWorldStyles(List<String> ids) {
        this.availableWorldStyles = ids == null ? List.of() : List.copyOf(ids);
        if (selectedWorldStyles == null || availableWorldStyles.isEmpty()) {
            return;
        }
        List<WorldStyleMix.Entry> kept = new ArrayList<>();
        for (WorldStyleMix.Entry entry : selectedWorldStyles.entries()) {
            if (availableWorldStyles.contains(entry.style().toString())) {
                kept.add(entry);
            }
        }
        selectedWorldStyles = kept.isEmpty()
                ? selectedWorldStyles.reducedToPrimary() : WorldStyleMix.of(kept);
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

    /**
     * Supplies the Customize editor's draft and selects it.
     * <p>
     * Settled on the way in, so what the entry carries is a resolved preset like every other entry's
     * - the editor's draft goes on being editable and this does not (issue #10).
     */
    public void applyCustomized(PresetDraft draft) {
        this.customized = draft.resolve();
        // Preset.getId() is the base preset the draft was taken from, carried unchanged through
        // toDraft()/resolve() - which is what lets the row be named after it and sit beside it.
        this.customizedBase = findAvailable(customized.getId());
        select(CUSTOMIZED_ID);
    }

    /**
     * Drops the customization and goes back to the preset it was edited from, for the tab's Revert
     * action. Without it a customization was a one-way door: the row could be left, but never
     * removed, and the only way back to the stock preset was to abandon the screen (issue #201).
     * <p>
     * Falls back to Disabled only if the base preset is no longer injected at all.
     */
    public void revertCustomization() {
        if (customized == null) {
            return;
        }
        Identifier baseId = customized.getId();
        customized = null;
        customizedBase = null;
        select(findAvailable(baseId) != null ? baseId : DISABLED_ID);
    }

    /** Whether a customization exists at all - what the tab's Revert action is offered for. */
    public boolean hasCustomization() {
        return customized != null;
    }

    /**
     * The name of the preset the current customization was edited from, or {@code null} when there is
     * no customization (or its base is no longer injected). Drives the detail panel's
     * "modified copy of X" line.
     */
    @Nullable
    public Component customizedBaseName() {
        return customizedBase == null ? null : customizedBase.name();
    }

    /**
     * The stock preset the current customization was edited from, or {@code null} when there is no
     * customization (or its base is no longer injected). This is what the editor's Reset means, as
     * opposed to the customization itself, which is what it opens on.
     */
    @Nullable
    public Preset customizedBasePreset() {
        return customizedBase == null ? null : customizedBase.preset();
    }

    public void reset() {
        availablePresets = List.of();
        selected = DISABLED_ENTRY;
        customized = null;
        customizedBase = null;
        unlisted = null;
        selectedWorldStyles = null;
        availableWorldStyles = List.of();
        pendingRestore = null;
        configuredDefaultApplied = false;
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
     * @param overridesJson the saved {@code PresetDefinition} overrides JSON, or empty for a plain preset.
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
        // Validated BEFORE publishing, not after: the published patch is read on a worldgen worker
        // thread the moment a chunk generates (DimensionRuntime.create), so an unparseable string
        // must never reach it - publishing it and only catching the parse failure later (in
        // reconcilePendingRestore's best-effort visual reconciliation) would leave the bad JSON sitting
        // in the handoff while the visual selection quietly fell back to plain.
        String overrides = validatedOverrides(overridesJson, presetId);

        Config.resetPresetCache();
        WorldSelectionHandoff.publish(new WorldSelection(presetId, worldStyles,
                Optional.ofNullable(overrides)));
        Urbex.getLogger().info("Restored Urbex preset '{}' for world re-creation", presetId);

        this.selectedWorldStyles = worldStyles;
        this.pendingRestore = new PendingRestore(presetId, overrides);
        reconcilePendingRestore();
    }

    /**
     * Validates a saved-data overrides string through {@link PresetDefinition#parseOverrides} before it is allowed
     * anywhere near {@link Config#overridesFromClient} - that field is read on a worldgen worker
     * thread the instant a chunk generates ({@code DimensionRuntime.create}), so a malformed string
     * must never be published in the first place. Returns {@code null} - "plain preset, no
     * overrides" - for blank or otherwise malformed input. The central parser's
     * {@link RetiredPresetKeyException} is deliberately rethrown so removed fields cannot degrade
     * into a plain-preset fallback.
     */
    @Nullable
    private static String validatedOverrides(String overridesJson, Identifier presetId) {
        if (overridesJson == null || overridesJson.isEmpty()) {
            return null;
        }
        try {
            PresetDefinition.parseOverrides(JsonParser.parseString(overridesJson));
            return overridesJson;
        } catch (RetiredPresetKeyException e) {
            throw e;
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
     * parse here is a backstop, not the primary defense - kept anyway so an unrelated decode/apply
     * error still degrades to "select it plain" instead of throwing out of a widget callback.
     * Retired preset keys remain a hard failure here just as they are at every override boundary.
     */
    private void reconcilePendingRestore() {
        PendingRestore pending = pendingRestore;
        Entry base = findEntry(pending.presetId());
        if (base == null) {
            if (availablePresets.isEmpty()) {
                // Nothing has been injected yet, so this is "too early", not "unknown". Keep
                // waiting; what was published is already correct either way.
                return;
            }
            // The registry was read and does not offer this preset - a datapack turned off since the
            // world was made, or one that never tagged the preset browsable. Stand a row in for it
            // rather than leaving the tab on Disabled while restore() has already published the
            // saved id: the screen must not say "no cities" about a world that is about to have them
            // (issue #202). Server-side validation still gets the final word once a chunk generates.
            pendingRestore = null;
            unlisted = new Unlisted(pending.presetId(), pending.overridesJson());
            select(pending.presetId());
            Urbex.getLogger().warn("The re-created world's preset '{}' is not among the presets these "
                    + "datapacks offer; showing it as unlisted.", pending.presetId());
            return;
        }
        pendingRestore = null;
        if (pending.overridesJson() == null || base.preset() == null) {
            select(pending.presetId());
            return;
        }
        try {
            PresetDefinition re = PresetDefinition.parseOverrides(JsonParser.parseString(pending.overridesJson()));
            applyCustomized(Presets.applyOverrides(base.preset(), re).toDraft());
        } catch (RetiredPresetKeyException e) {
            throw e;
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
     * The cache reset mirrors {@link #publish()}: this changes what the next
     * world would generate with just as much as publishing does.
     */
    public void discardPublication() {
        if (!WorldSelectionHandoff.isPending()) {
            return;
        }
        Config.resetPresetCache();
        WorldSelectionHandoff.discard();
        Urbex.getLogger().debug("World creation abandoned; discarded the published Urbex selection");
    }

    /**
     * Publishes the current selection so it reaches world generation, through
     * {@link WorldSelectionHandoff}.
     * <ul>
     *   <li>Disabled: nothing published.</li>
     *   <li>A plain built-in entry: its own id, the effective worldStyle, no overrides.</li>
     *   <li>The transient customized entry: the <em>base</em> preset id it was customized from
     *       (carried, unchanged, in {@code Preset.getId()} through every {@link Preset#copy()}), the
     *       effective worldStyle, and the full edited preset encoded as a {@link PresetDefinition} overlay -
     *       so the server rebuilds exactly what the editor showed, not an approximation.</li>
     *   <li>The unlisted row: the saved id and overrides verbatim, since nothing here can resolve
     *       them. Re-selecting that row therefore republishes exactly what {@link #restore} did.</li>
     * </ul>
     */
    public void publish() {
        Entry entry = selected;

        Config.resetPresetCache();

        if (DISABLED_ID.equals(entry.id())) {
            WorldSelectionHandoff.discard();
            return;
        }

        // Before the null-preset check below: an unlisted entry carries no resolved Preset but does
        // name a real selection, and discarding it would have the tab silently turn the world's own
        // preset off (issue #202).
        if (unlisted != null && unlisted.presetId().equals(entry.id())) {
            WorldSelectionHandoff.publish(new WorldSelection(unlisted.presetId(),
                    Config.gateMix(effectiveWorldStyles(), "The world being created"),
                    Optional.ofNullable(unlisted.overridesJson())));
            return;
        }

        if (entry.preset() == null) {
            WorldSelectionHandoff.discard();
            return;
        }

        // Gated here as well as server-side: with experimentalMultiWorldStyles off, what the client
        // publishes must already be what a non-opted-in install would generate.
        WorldStyleMix worldStyles = Config.gateMix(effectiveWorldStyles(), "The world being created");

        if (CUSTOMIZED_ID.equals(entry.id())) {
            Preset preset = entry.preset();
            PresetDefinition re = preset.toDefinition();
            JsonElement json = PresetDefinition.CODEC.encodeStart(JsonOps.INSTANCE, re).getOrThrow();
            WorldSelectionHandoff.publish(new WorldSelection(preset.getId(), worldStyles,
                    Optional.of(GSON.toJson(json))));
        } else {
            WorldSelectionHandoff.publish(new WorldSelection(entry.id(), worldStyles));
        }
    }
}
