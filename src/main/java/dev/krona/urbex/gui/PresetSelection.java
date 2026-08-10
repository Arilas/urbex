package dev.krona.urbex.gui;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.ProfileSetup;
import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.worldgen.CityFeature;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client-side state for "which Urbex preset generates this world", replacing
 * {@link ClientProfileSetup} for the redesigned Cities tab (Task 3 onwards). Pure state - nothing
 * here touches widgets, so it's unit-testable headless. {@link ClientProfileSetup} stays alive
 * for the old editor screen until Phase 2 of the redesign removes it.
 * <p>
 * Custom (hand-edited) profiles are never written into {@link ProfileSetup#STANDARD_PROFILES}
 * by this class outside of {@link #publish()} - they live only in the selection itself, supplied
 * by the future editor via {@link #applyCustomized}.
 */
public final class PresetSelection {

    /** The shared client-side selection, mirroring {@link ClientProfileSetup#CLIENT_SETUP}. */
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

    public PresetSelection() {
    }

    /**
     * The full, current list of choices: {@code disabled} first, then public built-in presets
     * ({@code default} first, then alphabetical - same ordering as the old
     * {@code ClientProfileSetup.toggleProfile}), then the custom entry (if any) last.
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
                return;
            }
        }
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
    }

    /**
     * Restores a profile selection read from an existing world's saved data, for the vanilla
     * Re-Create flow (issue #85), and immediately {@link #publish()}es it. Publishing here (rather
     * than waiting for the Cities tab to be opened) is what makes the restored choice actually
     * reach the server if the player never opens that tab before creating the world - matching the
     * old {@code ClientProfileSetup.restoreFromSavedData}, which called
     * {@code UrbexConfigScreen.selectProfile} inline for exactly this reason. An unknown profile
     * name is logged and leaves the current selection (and anything already published) untouched.
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
     * the old {@code UrbexConfigScreen.selectProfile}: set {@code Config.profileFromClient} ({@code
     * null} for "disabled", meaning no profile override - verbatim what the old
     * {@code ClientProfileSetup.getProfile()} produced), bump the dirty counter, reset the profile
     * cache, and for a customized profile also mirror it into {@code ProfileSetup.STANDARD_PROFILES}
     * and {@code Config.jsonFromClient}.
     */
    public void publish() {
        Entry entry = selected;
        Config.profileFromClient = DISABLED_ID.equals(entry.id()) ? null : entry.id();

        CityFeature.globalDimensionInfoDirtyCounter++;
        Config.resetProfileCache();

        if (entry.custom() && entry.profile().isPresent()) {
            UrbexProfile profile = entry.profile().get();
            ProfileSetup.STANDARD_PROFILES
                    .computeIfAbsent(CUSTOM_ID, k -> new UrbexProfile(CUSTOM_ID, false))
                    .copyFrom(profile);
            Config.jsonFromClient = profile.toJson(false).toString();
        }
    }
}
