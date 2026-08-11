package dev.krona.urbex.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.krona.urbex.Urbex;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class ProfileSetup {

    public static final Map<String, UrbexProfile> STANDARD_PROFILES = new HashMap<>();

    /**
     * The subset of {@link #STANDARD_PROFILES} that came from a user file in
     * {@code config/urbex/profiles/} rather than from {@link #initStandardProfiles()} - i.e. the
     * hand-saved custom presets from the Customize editor. These surface as their own selectable rows
     * in {@code PresetSelection.entries()} (customs, last), which is why they must be told apart from
     * the internal non-public built-in variants that stay hidden.
     */
    public static final Set<String> USER_PROFILES = new LinkedHashSet<>();

    /** Provenance for {@link #USER_PROFILES}: the id of the preset each was "saved as" a copy of. */
    public static final Map<String, String> PROFILE_BASED_ON = new HashMap<>();

    static void initStandardProfiles() {
        STANDARD_PROFILES.clear();
        UrbexProfile profile;

//        profile = new UrbexProfile("customized", false);
//        profile.setDescription("Customized profile");
//        standardProfiles.put(profile.getName(), profile);

        profile = new UrbexProfile("default", true);
        profile.setIconFile("textures/gui/icon_default.png");
        STANDARD_PROFILES.put(profile.getName(), profile);

        profile = new UrbexProfile("cavern", true);
        profile.setDescription("This profile is meant for a cavern type world. There are lights in the building but the outside is very dark.");
        profile.setExtraDescription("This is very hard. It's recommended you enable a bonus chest!");
        profile.setWarning("Use this in combination with the Lost Worlds 'caves' world type");
        profile.setIconFile("textures/gui/icon_cavern.png");
        profile.LANDSCAPE_TYPE = LandscapeType.CAVERN;
        profile.HORIZON = 128;
        profile.FOG_RED = 0.0f;
        profile.FOG_GREEN = 0.0f;
        profile.FOG_BLUE = 0.0f;
        profile.FOG_DENSITY = 0.02f;
        profile.EXPLOSION_CHANCE = 0;
        profile.MINI_EXPLOSION_CHANCE = 0;
        profile.LIGHTING_DENSITY = 0.65f;
        profile.RAILWAYS_ENABLED = false;
        profile.GROUNDLEVEL = 40;
        profile.SEALEVEL = 32;
        profile.CITY_LEVEL0_HEIGHT = 40+4;
        profile.CITY_LEVEL1_HEIGHT = 40+12;
        profile.CITY_LEVEL2_HEIGHT = 40+20;
        profile.CITY_LEVEL3_HEIGHT = 40+28;
        profile.CITY_LEVEL4_HEIGHT = 40+36;
        profile.CITY_LEVEL5_HEIGHT = 40+42;
        profile.CITY_LEVEL6_HEIGHT = 40+50;
        profile.CITY_LEVEL7_HEIGHT = 40+58;
//        profile.setIconFile("textures/gui/icon_default.png");
        STANDARD_PROFILES.put(profile.getName(), profile);

        profile = new UrbexProfile("nodamage", true);
        profile.setDescription("Like default but no explosion damage");
        profile.setExtraDescription("Ruins and rubble are disabled and ravines are disabled in cities");
        profile.setIconFile("textures/gui/icon_nodamage.png");
        profile.EXPLOSION_CHANCE = 0;
        profile.MINI_EXPLOSION_CHANCE = 0;
        profile.RUIN_CHANCE = 0;
        profile.RUBBLELAYER = false;
        STANDARD_PROFILES.put(profile.getName(), profile);

        profile = new UrbexProfile("floating", true);
        profile.setDescription("Cities on floating islands");
        profile.setExtraDescription("Note! No mineshafts or strongholds in this profile!");
        profile.setWarning("Preferably use this in combination with the Lost Worlds 'islands' world type");
        profile.setIconFile("textures/gui/icon_floating.png");
        profile.CITY_CHANCE = 0.03f;
        profile.LANDSCAPE_TYPE = LandscapeType.FLOATING;
        profile.HORIZON = 0;
//        profile.WATERLEVEL_OFFSET = 70;
        profile.HIGHWAY_SUPPORTS = false;
        profile.BUILDING_MAXCELLARS = 1;
        profile.RAILWAYS_CAN_END = true;
        profile.RAILWAYS_ENABLED = false;
        profile.RAILWAY_STATIONS_ENABLED = false;
        profile.HIGHWAY_DISTANCE_MASK = 15;
        profile.GROUNDLEVEL = 50;
        profile.CITY_LEVEL0_HEIGHT = 50;
        profile.CITY_LEVEL1_HEIGHT = 56;
        profile.CITY_LEVEL2_HEIGHT = 62;
        profile.CITY_LEVEL3_HEIGHT = 68;
        profile.CITY_LEVEL4_HEIGHT = 76;
        profile.CITY_LEVEL5_HEIGHT = 84;
        profile.CITY_LEVEL6_HEIGHT = 92;
        profile.CITY_LEVEL7_HEIGHT = 100;
        STANDARD_PROFILES.put(profile.getName(), profile);

        profile = new UrbexProfile("rarecities", true);
        profile.setDescription("Cities are rare");
        profile.setIconFile("textures/gui/icon_rarecities.png");
        profile.CITY_CHANCE = 0.001;
        profile.RUIN_CHANCE = 0;
        profile.HIGHWAY_REQUIRES_TWO_CITIES = false;
        profile.RAILWAYS_CAN_END = true;
        STANDARD_PROFILES.put(profile.getName(), profile);

        profile = new UrbexProfile("onlycities", true);
        profile.setDescription("The entire world is a city");
        profile.setIconFile("textures/gui/icon_onlycities.png");
        profile.CITY_CHANCE = 0.2;
        profile.CITY_MAXRADIUS = 256;
        STANDARD_PROFILES.put(profile.getName(), profile);

        profile = new UrbexProfile("tallbuildings", true);
        profile.setDescription("Very tall buildings (performance heavy)");
        profile.setIconFile("textures/gui/icon_tallbuildings.png");
        profile.BUILDING_MINFLOORS = 4;
        profile.BUILDING_MINFLOORS_CHANCE = 8;
        profile.BUILDING_MAXFLOORS_CHANCE = 15;
        profile.BUILDING_MAXFLOORS = 19;
        profile.DEBRIS_TO_NEARBYCHUNK_FACTOR = 175;
        profile.EXPLOSION_CHANCE = 0.006f;
        profile.EXPLOSION_MAXHEIGHT = 256;
        profile.EXPLOSION_MAXRADIUS = 60;
        profile.EXPLOSION_MINHEIGHT = 130;
        profile.MINI_EXPLOSION_CHANCE = 0.09f;
        profile.MINI_EXPLOSION_MAXHEIGHT = 256;
        profile.MINI_EXPLOSION_MAXRADIUS = 14;
        profile.MINI_EXPLOSION_MINRADIUS = 3;
        profile.RUIN_CHANCE = 0.01f;
        STANDARD_PROFILES.put(profile.getName(), profile);

        profile = new UrbexProfile("safe", true);
        profile.setDescription("Safe mode: no spawners, lighting but no loot");
        profile.setIconFile("textures/gui/icon_safe.png");
        profile.GENERATE_SPAWNERS = false;
        profile.LIGHTING_DENSITY = 1.00f;
        profile.LOOT_DENSITY = 0.00f;
        STANDARD_PROFILES.put(profile.getName(), profile);

        profile = new UrbexProfile("ancient", true);
        profile.setDescription("Ancient jungle city, leafs, ruined buildings");
//        profile.setExtraDescription("Note! This disables many biomes like deserts, plains, extreme hills, ...");
        profile.setIconFile("textures/gui/icon_ancient.png");
        profile.THICKNESS_OF_RANDOM_LEAFBLOCKS = 6;
        profile.CHANCE_OF_RANDOM_LEAFBLOCKS = 0.05f;
        profile.EXPLOSION_CHANCE = 0;
        profile.MINI_EXPLOSION_CHANCE = 0;
//        profile.MINI_EXPLOSION_MAXRADIUS = 10;
        profile.RUBBLELAYER = true;
        profile.RUBBLE_DIRT_SCALE = 2.0f;
        profile.RUBBLE_LEAVE_SCALE = 2.0f;
        profile.RUIN_CHANCE = 0.9f;
        profile.RUIN_MINLEVEL_PERCENT = 0.0f;
        profile.RUIN_MAXLEVEL_PERCENT = 0.9f;
        profile.LIGHTING_DENSITY = 0.05f;
        profile.LOOT_DENSITY = 0.40f;
        STANDARD_PROFILES.put(profile.getName(), profile);

        profile = new UrbexProfile("wasteland", true);
        profile.setDescription("Wasteland, no water, bare land");
        profile.setExtraDescription("This profile works best with Biomes O Plenty and the Wastify mod");
        profile.setIconFile("textures/gui/icon_wasteland.png");
//        profile.WATERLEVEL_OFFSET = 70;
        profile.CHANCE_OF_RANDOM_LEAFBLOCKS = 0.01f;
        profile.RUBBLELAYER = true;
        profile.RUBBLE_DIRT_SCALE = 2.0f;
        profile.RUBBLE_LEAVE_SCALE = 0.0f;
        profile.RUIN_CHANCE = 0.5f;
        profile.RUIN_MINLEVEL_PERCENT = 0.5f;
        profile.RUIN_MAXLEVEL_PERCENT = 0.9f;
        profile.AVOID_WATER = true;
        profile.AVOID_FOLIAGE = true;
        profile.LIGHTING_DENSITY = 0.05f;
        profile.LOOT_DENSITY = 0.40f;
        STANDARD_PROFILES.put(profile.getName(), profile);

        profile = new UrbexProfile("atlantis", true);
        profile.setDescription("Drowned cities, raised waterlevel (to 89)");
        profile.setWarning("Preferably use this in combination with the Lost Worlds 'atlantis' world type");
        profile.setIconFile("textures/gui/icon_atlantis.png");
//        profile.WATERLEVEL_OFFSET = -20;
        profile.SEALEVEL = 89;
        profile.RUIN_CHANCE = 0.1f;
        STANDARD_PROFILES.put(profile.getName(), profile);

//        profile = new UrbexProfile("chisel", true);
//        profile.setDescription("Use Chisel blocks (only if chisel is available!)");
//        profile.setIconFile("textures/gui/icon_chisel.png");
//        profile.setWorldStyle("chisel");
//        standardProfiles.put(profile.getName(), profile);
//
//        profile = new UrbexProfile("realistic", true);
//        profile.setDescription("Realistic worldgen (similar to Quark's)");
//        profile.setIconFile("textures/gui/icon_realistic.png");
//        profile.GENERATOR_OPTIONS = "{\"coordinateScale\":175.0,\"heightScale\":75.0,\"lowerLimitScale\":512.0,\"upperLimitScale\":512.0,\"depthNoiseScaleX\":200.0,\"depthNoiseScaleZ\":200.0,\"depthNoiseScaleExponent\":0.5,\"mainNoiseScaleX\":165.0,\"mainNoiseScaleY\":106.61267,\"mainNoiseScaleZ\":165.0,\"baseSize\":8.267606,\"stretchY\":13.387607,\"biomeDepthWeight\":1.2,\"biomeDepthOffset\":0.2,\"biomeScaleWeight\":3.4084506,\"biomeScaleOffset\":0.0,\"seaLevel\":63,\"useCaves\":true,\"useDungeons\":true,\"dungeonChance\":7,\"useStrongholds\":true,\"useVillages\":true,\"useMineShafts\":true,\"useTemples\":true,\"useMonuments\":true,\"useRavines\":true,\"useWaterLakes\":true,\"waterLakeChance\":49,\"useLavaLakes\":true,\"lavaLakeChance\":80,\"useLavaOceans\":false,\"fixedBiome\":-1,\"biomeSize\":4,\"riverSize\":5,\"dirtSize\":33,\"dirtCount\":10,\"dirtMinHeight\":0,\"dirtMaxHeight\":256,\"gravelSize\":33,\"gravelCount\":8,\"gravelMinHeight\":0,\"gravelMaxHeight\":256,\"graniteSize\":33,\"graniteCount\":10,\"graniteMinHeight\":0,\"graniteMaxHeight\":80,\"dioriteSize\":33,\"dioriteCount\":10,\"dioriteMinHeight\":0,\"dioriteMaxHeight\":80,\"andesiteSize\":33,\"andesiteCount\":10,\"andesiteMinHeight\":0,\"andesiteMaxHeight\":80,\"coalSize\":17,\"coalCount\":20,\"coalMinHeight\":0,\"coalMaxHeight\":128,\"ironSize\":9,\"ironCount\":20,\"ironMinHeight\":0,\"ironMaxHeight\":64,\"goldSize\":9,\"goldCount\":2,\"goldMinHeight\":0,\"goldMaxHeight\":32,\"redstoneSize\":8,\"redstoneCount\":8,\"redstoneMinHeight\":0,\"redstoneMaxHeight\":16,\"diamondSize\":8,\"diamondCount\":1,\"diamondMinHeight\":0,\"diamondMaxHeight\":16,\"lapisSize\":7,\"lapisCount\":1,\"lapisCenterHeight\":16,\"lapisSpread\":16}";
//        standardProfiles.put(profile.getName(), profile);

        profile = new UrbexProfile("largecities", true);
        profile.setIconFile("textures/gui/icon_default.png");
        profile.CITY_CHANCE = -1;
        profile.CITY_PERLIN_SCALE = 7.0;
        profile.CITY_PERLIN_OFFSET = 0.2;
        profile.CITY_PERLIN_INNERSCALE = 0.1;
        profile.CITY_THRESHOLD = .1f;
        profile.CITY_STYLE_THRESHOLD = .4f;
        profile.CITY_STYLE_ALTERNATIVE = "citystyle_border";
        profile.LIGHTING_DENSITY = 0.35f;
        profile.BUILDING_MAXFLOORS = 9;
        profile.BUILDING_MAXFLOORS_CHANCE = 7;
        profile.BUILDING_CHANCE = .4f;
        STANDARD_PROFILES.put(profile.getName(), profile);
    }

    public static void setupProfiles() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path profileDir = configDir.resolve("urbex/profiles");

        initStandardProfiles();
        writeProfileFiles(profileDir);

        // Names known before any user file is read are the built-ins; anything read from the user dir
        // whose name isn't among them is a hand-saved custom.
        Set<String> builtinNames = Set.copyOf(STANDARD_PROFILES.keySet());
        USER_PROFILES.clear();
        PROFILE_BASED_ON.clear();

        Urbex.getLogger().info("Reading profiles from 'config/urbex/profiles'");
        readProfiles(profileDir, builtinNames);
    }

    static void writeProfileFiles(Path profileDir) {
        Path defaultsDir = profileDir.resolve("defaults");
        // defaults/ is regenerated every launch as read-only reference material.
        defaultsDir.toFile().mkdirs();
        for (Map.Entry<String, UrbexProfile> entry : STANDARD_PROFILES.entrySet()) {
            writeProfile(defaultsDir, entry.getKey(), entry.getValue());
        }

        // profiles/ belongs to the user. Seed a file only when it is absent; never overwrite.
        for (Map.Entry<String, UrbexProfile> entry : STANDARD_PROFILES.entrySet()) {
            File target = profileDir.resolve(entry.getKey() + ".json").toFile();
            if (!target.exists()) {
                writeProfile(profileDir, entry.getKey(), entry.getValue());
            }
        }
    }

    private static void writeProfile(Path dir, String name, UrbexProfile profile) {
        JsonObject jsonObject = profile.toJson(true);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try {
            try (PrintWriter writer = new PrintWriter(new File(dir.toString(), name + ".json"))) {
                writer.print(gson.toJson(jsonObject));
                writer.flush();
            }
        } catch (FileNotFoundException e) {
            Urbex.getLogger().error("Couldn't save profile '{}'!", name);
        }
    }

    private static void readProfiles(Path profileDir, Set<String> builtinNames) {
        // listFiles is non-recursive and only matches "*.json" names directly inside profileDir,
        // so the "defaults" subdirectory (a directory, not a "*.json" file) is never picked up here.
        File[] files = new File(profileDir.toString()).listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            // profileDir doesn't exist (or isn't a directory) - nothing to read.
            return;
        }
        for (File file : files) {
            String name = file.getName();
            try {
                String json = FileUtils.readFileToString(file, "UTF-8");
                String[] split = name.split("\\.");
                String id = split[0];
                UrbexProfile profile = new UrbexProfile(id, json);
                STANDARD_PROFILES.put(id, profile);
                if (!builtinNames.contains(id)) {
                    // A file the user (or the Save-as editor) created: a first-class custom preset.
                    USER_PROFILES.add(id);
                    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                    if (root.has("basedOn")) {
                        PROFILE_BASED_ON.put(id, root.getAsJsonPrimitive("basedOn").getAsString());
                    }
                }
            } catch (IOException e) {
                Urbex.getLogger().error("Couldn't read profile '{}'!", name);
                return;
            }
        }

    }
}
