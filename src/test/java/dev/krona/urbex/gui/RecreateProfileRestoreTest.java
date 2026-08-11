package dev.krona.urbex.gui;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RecreateProfileRestoreTest {

    private static CompoundTag payload(String preset, String worldStyle, String overrides) {
        CompoundTag data = new CompoundTag();
        data.putString("preset", preset);
        data.putString("worldStyle", worldStyle);
        data.putString("overrides", overrides);
        return data;
    }

    @Test
    public void parsesModernSavedDataWrapper() {
        // SavedData files wrap the codec payload in a "data" compound
        CompoundTag root = new CompoundTag();
        root.put("data", payload("urbex:rare", "urbex:standard", ""));
        Optional<RecreateProfileRestore.Pending> pending = RecreateProfileRestore.parse(root);
        assertEquals("urbex:rare", pending.orElseThrow().preset());
        assertEquals("urbex:standard", pending.orElseThrow().worldStyle());
        assertEquals("", pending.orElseThrow().overridesJson());
    }

    @Test
    public void parsesCustomizedPresetWithOverridesJson() {
        CompoundTag root = new CompoundTag();
        root.put("data", payload("urbex:default", "urbex:standard", "{\"cities\":{\"cityChance\":0.9}}"));
        Optional<RecreateProfileRestore.Pending> pending = RecreateProfileRestore.parse(root);
        assertEquals("urbex:default", pending.orElseThrow().preset());
        assertEquals("{\"cities\":{\"cityChance\":0.9}}", pending.orElseThrow().overridesJson());
    }

    @Test
    public void emptyPresetMeansNothingToRestore() {
        CompoundTag root = new CompoundTag();
        root.put("data", payload("", "", ""));
        assertTrue(RecreateProfileRestore.parse(root).isEmpty());
    }

    @Test
    public void missingDataCompoundMeansNothingToRestore() {
        assertTrue(RecreateProfileRestore.parse(new CompoundTag()).isEmpty());
    }
}
