package mcjty.lostcities.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import javax.annotation.Nonnull;

public class LostData extends SavedData {

    public static final String NAME = "lostcities_data";

    private String selectedProfile = "";
    private String selectedJson = "";

    @Nonnull
    public static LostData getData(Level level) {
        if (level.isClientSide) {
            throw new RuntimeException("Don't access this client-side!");
        }
        MinecraftServer server = level.getServer();
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        DimensionDataStorage storage = overworld.getDataStorage();
        return storage.computeIfAbsent(new Factory<>(LostData::new, LostData::new), NAME);
    }

    public LostData() {
    }

    public LostData(CompoundTag tag, HolderLookup.Provider provider) {
        selectedProfile = tag.getString("profile");
        selectedJson = tag.getString("json");
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

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putString("profile", selectedProfile);
        tag.putString("json", selectedJson);
        return tag;
    }
}
