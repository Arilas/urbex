package dev.krona.urbex.gui;

import dev.krona.urbex.config.Preset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The row widgets themselves need a running client to exercise, but the rule for what a row is
 * <em>called</em> is pure and worth pinning: since Task 4 an entry's label is exactly its
 * {@link PresetSelection.Entry#name()} - there is no more "based on" provenance to decorate it with
 * (a preset's own id, carried in {@code Preset.getId()}, is all {@code publish()} needs, and it is
 * never shown to the player as a suffix).
 */
class PresetListWidgetTest {

    private static PresetSelection.Entry builtIn(String id) {
        Identifier identifier = Identifier.fromNamespaceAndPath("urbex", id);
        return new PresetSelection.Entry(identifier, Component.literal(id), new Preset(identifier));
    }

    private static PresetSelection.Entry customized() {
        Preset preset = new Preset(Identifier.fromNamespaceAndPath("urbex", "default"));
        return new PresetSelection.Entry(PresetSelection.CUSTOMIZED_ID,
                Component.translatable("urbex.preset.custom"), preset);
    }

    @Test
    void builtInPresetsKeepTheirBareName() {
        PresetSelection.Entry entry = builtIn("rare");
        Component label = PresetListWidget.buildLabel(entry);
        assertSame(entry.name(), label, "a row must not decorate the entry's name at all");
    }

    @Test
    void theCustomizedEntryAlsoJustShowsItsOwnName() {
        PresetSelection.Entry entry = customized();
        Component label = PresetListWidget.buildLabel(entry);
        assertSame(entry.name(), label);
    }
}
