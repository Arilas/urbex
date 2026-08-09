package dev.krona.urbex.gui;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.LostCityProfile;
import dev.krona.urbex.config.ProfileSetup;
import dev.krona.urbex.varia.ComponentFactory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class LostCitySetup {

    public static final LostCitySetup CLIENT_SETUP = new LostCitySetup(() -> {});

    private List<String> profiles = null;

    private String profile = null;
    private LostCityProfile customizedProfile = null;

    private final Runnable refreshPreview;

    public LostCitySetup(Runnable refreshPreview) {
        this.refreshPreview = refreshPreview;
    }

    public LostCityProfile getCustomizedProfile() {
        return customizedProfile;
    }

    public void reset() {
        profiles = null;
        profile = null;
        customizedProfile = null;
    }

    public boolean isCustomizable() {
        if (profile == null) {
            return false;
        }
        if ("customized".equals(profile)) {
            return false;
        }
        return true;
    }

    public String getProfile() {
        return profile;
    }

    public String getProfileLabel() {
        return profile == null ? "Disabled" : profile;
    }

    public Component getProfileInfo() {
        return get()
                .map(p -> ComponentFactory.literal(p.getDescription() + "\n").append(
                        ComponentFactory.literal(p.getExtraDescription() + "\n").withStyle(ChatFormatting.AQUA)).append(
                        ComponentFactory.literal(p.getWarning()).withStyle(ChatFormatting.RED)
                ))
                .orElse(ComponentFactory.literal("Click here to select an Urbex profile for your world"));
    }

    public String getWorldStyleLabel() {
        return get().isEmpty() ? "n.a." : get().get().getWorldStyle();
    }

    public void setProfile(String profile) {
        this.profile = profile;
        refreshPreview.run();
    }

    public void copyFrom(LostCitySetup other) {
        this.profile = other.profile;
        this.customizedProfile = other.customizedProfile;
    }

    /**
     * Restores a profile selection read from an existing world's saved data, for the vanilla
     * Re-Create flow (issue #85). Mirrors what {@link GuiLCConfig#selectProfile} publishes so
     * the restored choice reaches the server even if the Cities screen is never opened.
     */
    public void restoreFromSavedData(String profileName, String json) {
        if (profileName == null || profileName.isEmpty()) {
            return;
        }
        if (json != null && !json.isEmpty()) {
            customizedProfile = new LostCityProfile("customized", false);
            customizedProfile.copyFrom(new LostCityProfile("customized", json));
            ProfileSetup.STANDARD_PROFILES.put("customized", customizedProfile);
            profile = "customized";
            GuiLCConfig.selectProfile("customized", customizedProfile);
        } else if (ProfileSetup.STANDARD_PROFILES.containsKey(profileName)) {
            profile = profileName;
            GuiLCConfig.selectProfile(profileName, null);
        } else {
            Urbex.getLogger().warn("Re-created world used unknown Urbex profile '{}'; ignoring", profileName);
        }
        refreshPreview.run();
    }

    public void customize() {
        if (profile == null) {
            throw new IllegalStateException("Cannot happen!");
        }
        customizedProfile = new LostCityProfile("customized", false);
        LostCityProfile original = ProfileSetup.STANDARD_PROFILES.get(profile);
        ProfileSetup.STANDARD_PROFILES.put("customized", customizedProfile);
        profiles.add("customized");
        customizedProfile.copyFrom(original);
        profile = "customized";
        refreshPreview.run();
    }

    public Optional<LostCityProfile> get() {
        if (profile == null) {
            return Optional.empty();
        } else if ("customized".equals(profile)) {
            return Optional.ofNullable(customizedProfile);
        } else {
            return Optional.of(ProfileSetup.STANDARD_PROFILES.get(profile));
        }
    }

//    public Optional<Configuration> getConfig() {
//        if (profile == null) {
//            return Optional.empty();
//        } else if ("customized".equals(profile)) {
//
//        } else {
//
//        }
//    }


    private static String worldStyleToName(Identifier rl) {
        String path = rl.getPath();
        int idx = path.lastIndexOf('/');
        if (idx != -1) {
            path = path.substring(idx+1);
        }
        idx = path.lastIndexOf('.');
        if (idx != -1) {
            path = path.substring(0, idx);
        }
        if (!Urbex.MODID.equals(rl.getNamespace())) {
            path = rl.getNamespace() + ":" + path;
        }
        return path;
    }

    public void toggleWorldStyle() {
        PackRepository repository = Minecraft.getInstance().getResourcePackRepository();
        CloseableResourceManager resourceManager = new MultiPackResourceManager(PackType.SERVER_DATA, repository.openAllSelected());
        Map<Identifier, Resource> map = resourceManager.listResources("urbex/worldstyles", s -> s.toString().endsWith(".json"));
        List<String> styles = map.keySet().stream().map(LostCitySetup::worldStyleToName).collect(Collectors.toList());
        String current = get().map(LostCityProfile::getWorldStyle).orElse("<none>");
        int idx = styles.indexOf(current);
        if (idx == -1) {
            idx = 0;
        } else {
            idx++;
            if (idx >= styles.size()) {
                idx = 0;
            }
        }
        if (get().isPresent()) {
            get().get().setWorldStyle(styles.get(idx));
        }
        refreshPreview.run();
    }

    public void toggleProfile() {
        if (profiles == null) {
            String preferedProfile = "default";
            profiles = ProfileSetup.STANDARD_PROFILES.entrySet().stream()
                    .filter(entry -> entry.getValue().isPublic())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            profiles.sort((o1, o2) -> {
                if (preferedProfile.equals(o1)) {
                    return -1;
                }
                if (preferedProfile.equals(o2)) {
                    return 1;
                }
                return o1.compareTo(o2);
            });
        }

        if (profile == null) {
            profile = profiles.get(0);
        } else {
            int i = profiles.indexOf(profile);
            if (i == -1 || i >= profiles.size()-1) {
                profile = null;
            } else {
                profile = profiles.get(i+1);
            }
        }
        refreshPreview.run();
    }
}
