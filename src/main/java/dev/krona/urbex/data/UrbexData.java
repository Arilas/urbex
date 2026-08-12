package dev.krona.urbex.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.setup.WorldStyleMix;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.storage.SavedDataStorage;

import javax.annotation.Nonnull;

public class UrbexData extends SavedData {

    public static final String NAME = "data";

    // optionalFieldOf, not fieldOf: old worlds stored {profile, json} under different keys. Those
    // are simply absent here and ignored - a clean break, the world regenerates its selection as
    // unset rather than crashing on load.
    //
    // worldStyleMix is the newest key and carries the weighted form; worldStyle stays the only key
    // a single-style world writes, so such a save is byte-identical to what it was before mixing
    // existed. See getSelectedWorldStyles for the read order.
    public static final Codec<UrbexData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("preset", "").forGetter(d -> d.selectedPreset),
            Codec.STRING.optionalFieldOf("worldStyle", "").forGetter(d -> d.selectedWorldStyle),
            Codec.STRING.optionalFieldOf("overrides", "").forGetter(d -> d.selectedOverrides),
            Codec.STRING.optionalFieldOf("worldStyleMix", "").forGetter(d -> d.selectedWorldStyleMix)
            ).apply(instance, UrbexData::new));

    private static final SavedDataType<UrbexData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("urbex", NAME),
            UrbexData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private String selectedPreset = "";
    private String selectedWorldStyle = "";
    private String selectedOverrides = "";
    private String selectedWorldStyleMix = "";

    @Nonnull
    public static UrbexData getData(Level level) {
        if (level.isClientSide()) {
            throw new RuntimeException("Don't access this client-side!");
        }
        MinecraftServer server = level.getServer();
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        SavedDataStorage storage = overworld.getDataStorage();
        return storage.computeIfAbsent(TYPE);
    }

    public UrbexData() {
    }

    public UrbexData(String preset, String worldStyle, String overrides, String worldStyleMix) {
        selectedPreset = preset;
        selectedWorldStyle = worldStyle;
        selectedOverrides = overrides;
        selectedWorldStyleMix = worldStyleMix;
    }

    /**
     * Records the world's selection. A single-style choice writes only the legacy
     * {@code worldStyle} key and leaves {@code worldStyleMix} empty, so a world that uses one style
     * saves exactly what it always did; only a genuine mix writes the new key.
     */
    public void setChoice(String preset, WorldStyleMix styles, String overridesJson) {
        selectedPreset = preset;
        selectedWorldStyle = styles.primary().toString();
        selectedWorldStyleMix = styles.isSingle() ? "" : styles.format();
        selectedOverrides = overridesJson;
        setDirty();
    }

    public String getSelectedPreset() {
        return selectedPreset;
    }

    /**
     * The raw legacy single-style string. Kept for the Re-Create restore path, which hands
     * {@code PresetSelection} strings rather than parsed values; prefer
     * {@link #getSelectedWorldStyles()} everywhere else.
     */
    public String getSelectedWorldStyle() {
        return selectedWorldStyle;
    }

    /** The raw mix string, empty for a single-style world. */
    public String getSelectedWorldStyleMix() {
        return selectedWorldStyleMix;
    }

    /**
     * The styles this world was created with: {@code worldStyleMix} when present, else the legacy
     * single {@code worldStyle}, else the default.
     * <p>
     * Fail-soft on both, deliberately. This is read on a worldgen worker thread the moment a chunk
     * generates, so a corrupted or hand-edited save has to degrade to the default rather than take
     * generation down.
     */
    @Nonnull
    public WorldStyleMix getSelectedWorldStyles() {
        WorldStyleMix fromMix = tryParse(selectedWorldStyleMix, "mix");
        if (fromMix != null) {
            return fromMix;
        }
        WorldStyleMix fromSingle = tryParse(selectedWorldStyle, "id");
        return fromSingle != null ? fromSingle : Config.DEFAULT_WORLD_STYLE_MIX;
    }

    private static WorldStyleMix tryParse(String spec, String what) {
        if (spec == null || spec.isEmpty()) {
            return null;
        }
        try {
            return WorldStyleMix.parse(spec);
        } catch (IllegalArgumentException e) {
            Urbex.getLogger().error("Malformed saved worldstyle {} '{}' in world data; using {}.",
                    what, spec, Config.DEFAULT_WORLD_STYLE_MIX.format());
            return null;
        }
    }

    public String getSelectedOverrides() {
        return selectedOverrides;
    }
}
