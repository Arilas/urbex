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

    private static Codec<UrbexData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("profile").forGetter(d -> d.selectedProfile),
            Codec.STRING.fieldOf("json").forGetter(d -> d.selectedJson)
            ).apply(instance, UrbexData::new));

    private static final SavedDataType<UrbexData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("urbex", NAME),
            UrbexData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private String selectedProfile = "";
    private String selectedJson = "";

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

    public UrbexData(String profile, String json) {
        selectedProfile = profile;
        selectedJson = json;
    }

    public void setProfile(String profile, String json) {
        selectedProfile = profile;
        selectedJson = json;
        setDirty();
    }

    public String getSelectedProfile() {
        return selectedProfile;
    }

    public String getSelectedJson() {
        return selectedJson;
    }
}
