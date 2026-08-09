package dev.krona.urbex.gui;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RecreateProfileRestoreTest {

    private static CompoundTag payload(String profile, String json) {
        CompoundTag data = new CompoundTag();
        data.putString("profile", profile);
        data.putString("json", json);
        return data;
    }

    @Test
    public void parsesModernSavedDataWrapper() {
        // SavedData files wrap the codec payload in a "data" compound
        CompoundTag root = new CompoundTag();
        root.put("data", payload("rare", ""));
        Optional<RecreateProfileRestore.Pending> pending = RecreateProfileRestore.parse(root);
        assertEquals("rare", pending.orElseThrow().profile());
        assertEquals("", pending.orElseThrow().json());
    }

    @Test
    public void parsesCustomizedProfileWithJson() {
        CompoundTag root = new CompoundTag();
        root.put("data", payload("customized", "{\"citychance\":0.9}"));
        Optional<RecreateProfileRestore.Pending> pending = RecreateProfileRestore.parse(root);
        assertEquals("customized", pending.orElseThrow().profile());
        assertEquals("{\"citychance\":0.9}", pending.orElseThrow().json());
    }

    @Test
    public void emptyProfileMeansNothingToRestore() {
        CompoundTag root = new CompoundTag();
        root.put("data", payload("", ""));
        assertTrue(RecreateProfileRestore.parse(root).isEmpty());
    }

    @Test
    public void missingDataCompoundMeansNothingToRestore() {
        assertTrue(RecreateProfileRestore.parse(new CompoundTag()).isEmpty());
    }
}
