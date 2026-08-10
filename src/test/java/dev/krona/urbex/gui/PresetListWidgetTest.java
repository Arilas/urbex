package dev.krona.urbex.gui;

import dev.krona.urbex.config.UrbexProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The row widgets themselves need a running client to exercise, but the rule for what a row is
 * <em>called</em> is pure and worth pinning: built-ins render their bare name, customs get the
 * pencil prefix, and the "based on" suffix only appears when the lineage is actually known.
 */
class PresetListWidgetTest {

    private static PresetSelection.Entry builtIn(String id) {
        return new PresetSelection.Entry(id, Component.literal(id), false, "",
                Optional.of(new UrbexProfile(id, true)));
    }

    private static PresetSelection.Entry custom(String basedOn) {
        return new PresetSelection.Entry(PresetSelection.CUSTOM_ID, Component.translatable("urbex.preset.custom"),
                true, basedOn, Optional.of(new UrbexProfile(PresetSelection.CUSTOM_ID, false)));
    }

    private static String keyOf(Component component) {
        TranslatableContents contents = assertInstanceOf(TranslatableContents.class, component.getContents(),
                "expected a translatable component, got: " + component);
        return contents.getKey();
    }

    @Test
    void builtInPresetsKeepTheirBareName() {
        PresetSelection.Entry entry = builtIn("rare");
        Component label = PresetListWidget.buildLabel(entry);
        assertSame(entry.name(), label, "a built-in row must not decorate its name at all");
    }

    @Test
    void customPresetsGetThePencilPrefix() {
        Component label = PresetListWidget.buildLabel(custom("rare"));
        assertEquals("urbex.preset.custom_prefix", keyOf(label));
        List<Component> siblings = label.getSiblings();
        assertEquals(2, siblings.size(), "expected name + based-on suffix after the prefix");
        assertEquals("urbex.preset.custom", keyOf(siblings.get(0)));
    }

    @Test
    void customPresetsNameTheBuiltInTheyStartedFrom() {
        Component label = PresetListWidget.buildLabel(custom("rare"));
        Component suffix = label.getSiblings().get(1);
        assertEquals("urbex.preset.custom_suffix", keyOf(suffix));
        TranslatableContents contents = (TranslatableContents) suffix.getContents();
        assertEquals(1, contents.getArgs().length);
        assertEquals("rare", contents.getArgs()[0]);
    }

    @Test
    void customPresetsWithUnknownLineageGetNoSuffix() {
        // PresetSelection uses CUSTOM_ID as the "restored from saved world data, origin unknown"
        // marker; echoing that marker back at the player would say nothing.
        Component label = PresetListWidget.buildLabel(custom(PresetSelection.CUSTOM_ID));
        assertEquals(1, label.getSiblings().size(), "expected the name only, with no based-on suffix");
        assertTrue(label.getSiblings().stream().noneMatch(c -> c.getContents() instanceof TranslatableContents t
                && "urbex.preset.custom_suffix".equals(t.getKey())));
    }

    @Test
    void customPresetsWithNoLineageGetNoSuffix() {
        Component label = PresetListWidget.buildLabel(custom(""));
        assertEquals(1, label.getSiblings().size(), "expected the name only, with no based-on suffix");
    }
}
