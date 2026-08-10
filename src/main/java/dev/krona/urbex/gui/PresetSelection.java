package dev.krona.urbex.gui;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.ProfileSetup;
import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.worldgen.CityFeature;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client-side state for "which Urbex preset generates this world", replacing the old editor
 * screen's client-side profile state for the redesigned Cities tab (Task 3 onwards; that old state
 * holder was removed in Phase 2). Pure state - nothing here touches widgets, so it's unit-testable
 * headless.
 * <p>
 * Custom (hand-edited) profiles are never written into {@link ProfileSetup#STANDARD_PROFILES}
 * by this class outside of {@link #publish()} - they live only in the selection itself, supplied
 * by the customize editor via {@link #applyCustomized}.
 */
public final class PresetSelection {

    /** The shared client-side selection driving the Cities tab and the customize editor. */
    public static final PresetSelection CLIENT = new PresetSelection();

    public static final String DISABLED_ID = "disabled";
    public static final String CUSTOM_ID = "customized";

    /**
     * One selectable preset. {@code basedOn} is only meaningful when {@code custom} is true: it's
     * the id of the public preset the customization started from, or {@link #CUSTOM_ID} itself
     * when that origin isn't known (e.g. a customization restored from saved world data, which
     * only carries the resulting JSON, not its lineage).
     */
    public record Entry(String id, Component name, boolean custom, String basedOn, Optional<UrbexProfile> profile) {
    }

    private static final Entry DISABLED_ENTRY =
            new Entry(DISABLED_ID, Component.translatable("urbex.preset.disabled"), false, "", Optional.empty());

    private Entry selected = DISABLED_ENTRY;
    private UrbexProfile customProfile = null;
    private String customBasedOn = "";

    /**
     * The worldStyle ids the Cities tab currently offers, injected from the live datapack registry
     * ({@code AssetRegistries.WORLDSTYLES} via the preview's {@code RegistryAccess}) - the enumeration
     * can't be built headless, so it's supplied rather than read here. Empty until the tab injects it.
     */
    private List<String> availableWorldStyles = List.of();

    /**
     * The player's chosen worldStyle, orthogonal to the preset (spec 1a). {@code null} means "no
     * override - use the selected preset's own worldStyle"; a non-null value that differs from the
     * preset's own is published as an editor-style customization at {@link #publish()} time.
     */
    private String selectedWorldStyle = null;

    public PresetSelection() {
    }

    /**
     * The full, current list of choices: {@code disabled} first, then public built-in presets
     * ({@code default} first, then alphabetical - same ordering as the old editor's profile
     * cycling), then the custom entry (if any) last.
     */
    public List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        result.add(DISABLED_ENTRY);

        List<String> publicIds = new ArrayList<>();
        for (Map.Entry<String, UrbexProfile> e : ProfileSetup.STANDARD_PROFILES.entrySet()) {
            if (e.getValue().isPublic()) {
                publicIds.add(e.getKey());
            }
        }
        publicIds.sort((a, b) -> {
            if ("default".equals(a)) {
                return -1;
            }
            if ("default".equals(b)) {
                return 1;
            }
            return a.compareTo(b);
        });
        for (String id : publicIds) {
            result.add(new Entry(id, Component.literal(id), false, "", Optional.of(ProfileSetup.STANDARD_PROFILES.get(id))));
        }

        // Hand-saved custom presets (from the Customize editor's "Save as"), sorted by name, each a
        // first-class row carrying its "based on" provenance. Filtered to ones still registered, and
        // never a reserved selection id: CUSTOM_ID is the transient row below (not a saved file), and
        // DISABLED_ID would double-list against the built-in Disabled row - guard both even against a
        // stray on-disk file that shouldn't exist.
        List<String> userIds = new ArrayList<>();
        for (String id : ProfileSetup.USER_PROFILES) {
            if (!CUSTOM_ID.equals(id) && !DISABLED_ID.equals(id) && ProfileSetup.STANDARD_PROFILES.containsKey(id)) {
                userIds.add(id);
            }
        }
        userIds.sort(String::compareTo);
        for (String id : userIds) {
            String basedOn = ProfileSetup.PROFILE_BASED_ON.getOrDefault(id, "");
            result.add(new Entry(id, Component.literal(id), true, basedOn, Optional.of(ProfileSetup.STANDARD_PROFILES.get(id))));
        }

        if (customProfile != null) {
            result.add(new Entry(CUSTOM_ID, Component.translatable("urbex.preset.custom"), true, customBasedOn, Optional.of(customProfile)));
        }
        return result;
    }

    /** Selects the entry with the given id. Unknown ids (e.g. a stale id from a rebuilt list) are a no-op. */
    public void select(String id) {
        for (Entry entry : entries()) {
            if (entry.id().equals(id)) {
                selected = entry;
                dropInvalidWorldStyle();
                return;
            }
        }
    }

    /**
     * The worldStyle ids the Cities tab renders in its selector, injected from the live registry.
     * The tab hides the control when this has {@code <= 1} entries (the common "standard-only" case).
     * Injecting a list that no longer contains the chosen style clears the override back to "use the
     * preset's own", so a stale choice never survives a registry that dropped it.
     */
    public void setAvailableWorldStyles(List<String> ids) {
        this.availableWorldStyles = ids == null ? List.of() : List.copyOf(ids);
        dropInvalidWorldStyle();
    }

    /** What the Cities tab renders in its worldStyle selector; empty until injected. */
    public List<String> styleChoices() {
        return availableWorldStyles;
    }

    /**
     * Records the player's chosen worldStyle. {@code null} (or the selected preset's own style)
     * means "no override". Doesn't publish - the caller republishes so the change reaches the server.
     */
    public void setWorldStyle(String id) {
        this.selectedWorldStyle = id;
    }

    /** The chosen worldStyle override, or {@code null} for "use the selected preset's own". */
    public String selectedWorldStyle() {
        return selectedWorldStyle;
    }

    /**
     * The worldStyle the current selection actually generates with - the chosen override if any, else
     * the selected preset's own. Empty for the disabled row (no profile). Drives the preview and the
     * selector's displayed value, so both follow a preset change even when no override is set.
     */
    public String effectiveWorldStyle() {
        UrbexProfile profile = selected.profile().orElse(null);
        if (profile == null) {
            return "";
        }
        return selectedWorldStyle != null ? selectedWorldStyle : profile.getWorldStyle();
    }

    /** Resets a chosen style the current registry no longer offers, so it can't be published stale. */
    private void dropInvalidWorldStyle() {
        if (selectedWorldStyle != null && !availableWorldStyles.contains(selectedWorldStyle)) {
            selectedWorldStyle = null;
        }
    }

    /**
     * The effective worldStyle override for an entry, or {@code null} when the selection publishes
     * as-is: no chosen style, no profile (the disabled row), or a chosen style that already matches
     * the preset's own. A non-null result is a style that genuinely differs from the preset default.
     */
    @Nullable
    private String worldStyleOverride(Entry entry) {
        if (selectedWorldStyle == null || entry.profile().isEmpty()) {
            return null;
        }
        return selectedWorldStyle.equals(entry.profile().get().getWorldStyle()) ? null : selectedWorldStyle;
    }

    public Entry selected() {
        return selected;
    }

    /** Supplies a hand-edited profile from the (future) customize editor and selects it. */
    public void applyCustomized(UrbexProfile copy, String basedOn) {
        this.customProfile = copy;
        this.customBasedOn = basedOn == null ? "" : basedOn;
        select(CUSTOM_ID);
    }

    public void reset() {
        selected = DISABLED_ENTRY;
        customProfile = null;
        customBasedOn = "";
        selectedWorldStyle = null;
        availableWorldStyles = List.of();
    }

    /**
     * Restores a profile selection read from an existing world's saved data, for the vanilla
     * Re-Create flow (issue #85), and immediately {@link #publish()}es it. Publishing here (rather
     * than waiting for the Cities tab to be opened) is what makes the restored choice actually
     * reach the server if the player never opens that tab before creating the world - matching the
     * old editor's restore-from-saved-data path, which selected the profile inline for exactly this
     * reason. An unknown profile name is logged and leaves the current selection (and anything
     * already published) untouched.
     */
    public void restore(String profileName, String json) {
        if (profileName == null || profileName.isEmpty()) {
            return;
        }
        if (json != null && !json.isEmpty()) {
            UrbexProfile copy = new UrbexProfile(CUSTOM_ID, false);
            copy.copyFrom(new UrbexProfile(CUSTOM_ID, json));
            applyCustomized(copy, CUSTOM_ID);
            publish();
            return;
        }
        UrbexProfile profile = ProfileSetup.STANDARD_PROFILES.get(profileName);
        if (profile != null) {
            selected = new Entry(profileName, Component.literal(profileName), false, "", Optional.of(profile));
            publish();
        } else {
            Urbex.getLogger().warn("Re-created world used unknown Urbex profile '{}'; ignoring", profileName);
        }
    }

    /**
     * Publishes the current selection so it reaches world generation - the exact same contract as
     * the old editor's profile selection: set {@code Config.profileFromClient} ({@code null} for
     * "disabled", meaning no profile override - verbatim what the old editor's client-side profile
     * state produced), bump the dirty counter, reset the profile cache, and for a customized profile
     * also mirror it into {@code ProfileSetup.STANDARD_PROFILES} and {@code Config.jsonFromClient}.
     */
    public void publish() {
        Entry entry = selected;
        // A chosen worldStyle that differs from the preset's own is an editor-style customization:
        // it publishes exactly like a hand-edited profile (CUSTOM_ID + full JSON below), so the server
        // sees the switched style with no special worldStyle plumbing of its own.
        String worldStyle = worldStyleOverride(entry);
        boolean publishAsCustom = entry.custom() || worldStyle != null;

        // Custom entries (the transient "customized" row and hand-saved user presets alike) publish
        // under CUSTOM_ID with their full JSON below, so the server reconstructs them from the JSON
        // rather than needing the (possibly server-absent) user profile file by name.
        if (publishAsCustom) {
            Config.profileFromClient = CUSTOM_ID;
        } else {
            Config.profileFromClient = DISABLED_ID.equals(entry.id()) ? null : entry.id();
        }

        CityFeature.globalDimensionInfoDirtyCounter++;
        Config.resetProfileCache();

        if (publishAsCustom && entry.profile().isPresent()) {
            UrbexProfile source = entry.profile().get();
            UrbexProfile published = ProfileSetup.STANDARD_PROFILES
                    .computeIfAbsent(CUSTOM_ID, k -> new UrbexProfile(CUSTOM_ID, false));
            published.copyFrom(source);
            if (worldStyle != null) {
                published.setWorldStyle(worldStyle);
            }
            Config.jsonFromClient = published.toJson(false).toString();
        } else {
            // A plain preset (or disabled) reaches the server by name only. Clear any JSON a prior
            // custom/worldStyle publish left behind, or the server would apply that stale JSON on top
            // of the now-plain profile name.
            Config.jsonFromClient = null;
        }
    }
}
