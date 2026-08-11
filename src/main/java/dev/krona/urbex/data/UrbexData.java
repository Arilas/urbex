package dev.krona.urbex.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
    private static Codec<UrbexData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("preset", "").forGetter(d -> d.selectedPreset),
            Codec.STRING.optionalFieldOf("worldStyle", "").forGetter(d -> d.selectedWorldStyle),
            Codec.STRING.optionalFieldOf("overrides", "").forGetter(d -> d.selectedOverrides)
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

    public UrbexData(String preset, String worldStyle, String overrides) {
        selectedPreset = preset;
        selectedWorldStyle = worldStyle;
        selectedOverrides = overrides;
    }

    public void setChoice(String preset, String worldStyle, String overridesJson) {
        selectedPreset = preset;
        selectedWorldStyle = worldStyle;
        selectedOverrides = overridesJson;
        setDirty();
    }

    public String getSelectedPreset() {
        return selectedPreset;
    }

    public String getSelectedWorldStyle() {
        return selectedWorldStyle;
    }

    public String getSelectedOverrides() {
        return selectedOverrides;
    }
}
