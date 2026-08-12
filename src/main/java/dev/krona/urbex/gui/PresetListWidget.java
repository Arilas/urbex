package dev.krona.urbex.gui;

import dev.krona.urbex.config.Preset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * The preset picker on the left of the Cities tab: one row per {@link PresetSelection.Entry},
 * {@code disabled} first, customs last. Picking a row is the single place the player's choice is
 * committed - it {@code select}s <em>and</em> {@code publish}es on {@link PresetSelection#CLIENT},
 * because {@code select} alone never reaches world generation.
 */
public class PresetListWidget extends ObjectSelectionList<PresetListWidget.Row> {

    /** Row height: a 20x20 icon plus 2px of breathing room above and below. */
    public static final int ROW_HEIGHT = 24;

    private static final int ICON_SIZE = 20;
    private static final int ICON_TEXT_GAP = 4;

    /**
     * Source rect for the profile icons. The shipped {@code textures/gui/icon_*.png} are all
     * 128x128 (verified with a PNG header dump over the whole directory), but the value only has
     * to be self-consistent: {@code blit}'s UVs are {@code (u + uWidth) / texWidth}, so passing the
     * same number as both the sampled size and the texture size always maps the full texture,
     * whatever a datapack-supplied icon's real pixel size turns out to be.
     */
    private static final int ICON_TEXTURE_SIZE = 128;

    private static final int FALLBACK_TILE_COLOR = 0xff2f2f2f;
    private static final int FALLBACK_TILE_BORDER_COLOR = 0xff5f5f5f;
    private static final int ROW_TEXT_COLOR = 0xffffffff;

    private final Consumer<PresetSelection.Entry> onSelectionChanged;

    /**
     * Guards {@link #setSelected} while {@link #refreshEntries()} restores the already-committed
     * selection: that is not the player choosing something, so it must not re-publish or reset the
     * detail panel's scroll/preview state.
     */
    private boolean restoringSelection;

    public PresetListWidget(Minecraft minecraft, int width, int height, int y,
                            Consumer<PresetSelection.Entry> onSelectionChanged) {
        super(minecraft, width, height, y, ROW_HEIGHT);
        this.onSelectionChanged = onSelectionChanged;
        this.centerListVertically = false;
        refreshEntries();
    }

    /** Rebuilds the rows from {@link PresetSelection#CLIENT} and re-selects its current entry. */
    public final void refreshEntries() {
        restoringSelection = true;
        try {
            clearEntries();
            Identifier selectedId = PresetSelection.CLIENT.selected().id();
            Row toSelect = null;
            for (PresetSelection.Entry entry : PresetSelection.CLIENT.entries()) {
                Row row = new Row(entry);
                addEntry(row);
                if (entry.id().equals(selectedId)) {
                    toSelect = row;
                }
            }
            if (toSelect != null) {
                setSelected(toSelect);
            }
        } finally {
            restoringSelection = false;
        }
    }

    /**
     * The one funnel for "a row became the selected one": vanilla routes both mouse clicks and
     * arrow-key navigation through {@code setFocused}, which calls this (confirmed by decompiling
     * {@code AbstractSelectionList.setFocused} / {@code ObjectSelectionList.nextFocusPath}).
     * {@code null} - vanilla clearing the selection when focus leaves the list - is passed through
     * but commits nothing.
     */
    @Override
    public void setSelected(@Nullable Row row) {
        Row previous = getSelected();
        super.setSelected(row);
        if (row == null || restoringSelection || row == previous) {
            return;
        }
        PresetSelection.CLIENT.select(row.entry.id());
        PresetSelection.CLIENT.publish();
        onSelectionChanged.accept(row.entry);
    }

    @Override
    public int getRowWidth() {
        // Full column width minus the scrollbar gutter, instead of the vanilla fixed 220.
        return Math.max(0, getWidth() - scrollbarWidth() - 4);
    }

    public class Row extends ObjectSelectionList.Entry<Row> {

        private final PresetSelection.Entry entry;
        private final Component label;
        @Nullable
        private final Identifier icon;

        Row(PresetSelection.Entry entry) {
            this.entry = entry;
            this.label = buildLabel(entry);
            this.icon = resolveIcon(entry);
        }

        public PresetSelection.Entry entry() {
            return entry;
        }

        /**
         * The label plus the preset's id. The row itself shows only the label - since presets grew a
         * {@code name}, that is a human phrase and no longer says which datapack owns the preset -
         * and this is where the id stays reachable without costing the row a second line.
         */
        @Override
        public Component getNarration() {
            return Component.literal(label.getString() + " (" + entry.id() + ")");
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int left = getContentX();
            int top = getContentY();
            int iconY = top + Math.max(0, (getContentHeight() - ICON_SIZE) / 2);
            renderIcon(graphics, left, iconY);

            int textX = left + ICON_SIZE + ICON_TEXT_GAP;
            int textY = top + Math.max(0, (getContentHeight() - font.lineHeight) / 2);
            // Scissor rather than truncate: long preset names (a custom's "based on" suffix in
            // particular) must not bleed out of the list column into the detail panel.
            graphics.enableScissor(textX, top, getContentRight(), getContentBottom());
            graphics.text(font, label, textX, textY, ROW_TEXT_COLOR);
            graphics.disableScissor();
        }

        private void renderIcon(GuiGraphicsExtractor graphics, int x, int y) {
            if (icon != null) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, icon, x, y, 0.0F, 0.0F,
                        ICON_SIZE, ICON_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE);
                return;
            }
            graphics.fill(x, y, x + ICON_SIZE, y + ICON_SIZE, FALLBACK_TILE_BORDER_COLOR);
            graphics.fill(x + 1, y + 1, x + ICON_SIZE - 1, y + ICON_SIZE - 1, FALLBACK_TILE_COLOR);
        }
    }

    /**
     * A row's label is exactly its entry's name - since Task 4 there is no more "based on" provenance
     * to decorate it with (a preset's own id is carried, unshown, in {@code Preset.getId()}, purely
     * for {@link PresetSelection#publish()}'s benefit).
     */
    static Component buildLabel(PresetSelection.Entry entry) {
        return entry.name();
    }

    /**
     * The preset's icon, or {@code null} when it has none or the texture is not actually shipped -
     * blitting a missing {@link Identifier} would draw the magenta/black "missing texture"
     * checkerboard, which reads as a bug rather than as "this preset has no art".
     */
    @Nullable
    private static Identifier resolveIcon(PresetSelection.Entry entry) {
        Preset preset = entry.preset();
        Identifier icon = preset == null ? null : preset.getIcon();
        if (icon == null) {
            return null;
        }
        return Minecraft.getInstance().getResourceManager().getResource(icon).isPresent() ? icon : null;
    }
}
