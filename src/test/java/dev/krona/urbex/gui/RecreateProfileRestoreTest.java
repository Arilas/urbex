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

    /**
     * Issue #202. {@code UrbexData.setChoice} writes the weighted form to {@code worldStyleMix} and
     * leaves {@code worldStyle} holding the primary alone, so reading only the latter - which is what
     * this did - silently collapsed a re-created world from a mix to one style. The read order here
     * has to be the one {@code UrbexData.getSelectedWorldStyles} already uses.
     */
    @Test
    public void aMixIsReadFromWorldStyleMixRatherThanCollapsingToThePrimary() {
        CompoundTag data = payload("urbex:default", "urbex:standard", "");
        data.putString("worldStyleMix", "urbex:standard*0.25+urbexmt:moderntweaks*0.75");
        CompoundTag root = new CompoundTag();
        root.put("data", data);

        assertEquals("urbex:standard*0.25+urbexmt:moderntweaks*0.75",
                RecreateProfileRestore.parse(root).orElseThrow().worldStyle());
    }

    /** A single-style world writes only the legacy key, and must keep restoring exactly as it did. */
    @Test
    public void anAbsentMixFallsBackToTheLegacySingleWorldStyleKey() {
        CompoundTag root = new CompoundTag();
        root.put("data", payload("urbex:default", "urbex:lcmt", ""));

        assertEquals("urbex:lcmt", RecreateProfileRestore.parse(root).orElseThrow().worldStyle());
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
