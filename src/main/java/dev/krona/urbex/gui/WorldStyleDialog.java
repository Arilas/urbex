package dev.krona.urbex.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The modal "Select World Style" picker raised from the {@link CitiesTab} world-style button: a
 * scrollable {@link ObjectSelectionList} of the registered style ids plus a cancel button. Vanilla
 * ships no combobox, so this is the dropdown - a click on a row hands the chosen id to the caller and
 * closes; cancel (or Escape) leaves the current style untouched. The currently effective style opens
 * pre-selected and tinted, so the player sees what generates now before changing it.
 * <p>
 * {@link #preselectIndex} is the one pure, headless-testable piece ({@code WorldStyleDialogTest});
 * everything else is GL widget code exercised only manually. Nothing here lays widgets at fixed
 * screen coordinates: the block is sized from the style count and centred in whatever screen area it
 * opens over, matching {@link SaveAsDialog}.
 */
public class WorldStyleDialog extends Screen {

    /** Beyond this the single-column list of short ids just gets emptier; cap and centre instead. */
    private static final int MAX_WIDTH = 220;
    private static final int SCREEN_MARGIN = 20;
    private static final int ROW_HEIGHT = 20;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int TITLE_GAP = 8;
    private static final int TEXT_INSET = 6;

    private static final int STYLE_COLOR = 0xffffffff;
    /** The style that generates right now, so it reads as "current" even after the player arrows away. */
    private static final int CURRENT_STYLE_COLOR = 0xffffff55;

    private final Screen parent;
    private final List<String> styles;
    private final String current;
    private final Consumer<String> onSelect;

    @Nullable
    private StyleList list;
    private int titleY;

    public WorldStyleDialog(Screen parent, List<String> styles, String current, Consumer<String> onSelect) {
        super(Component.translatable("urbex.screen.worldstyle.title"));
        this.parent = parent;
        this.styles = List.copyOf(styles);
        this.current = current == null ? "" : current;
        this.onSelect = onSelect;
    }

    /**
     * The index of {@code current} within {@code choices} - the row to pre-select and highlight - or
     * {@code -1} when {@code current} is absent (the disabled row's empty style, or a stale id a
     * registry change dropped). Pure: no widget or game state.
     */
    static int preselectIndex(List<String> choices, @Nullable String current) {
        if (current == null) {
            return -1;
        }
        return choices.indexOf(current);
    }

    @Override
    protected void init() {
        int width = Math.min(this.width - SCREEN_MARGIN * 2, MAX_WIDTH);
        int x = (this.width - width) / 2;

        // Reserve the title above and the cancel button below; the list takes the middle, clamped to
        // the rows it actually has so a two-style install isn't a tall empty box, and to the screen so
        // a huge datapack list still scrolls inside the dialog rather than off it.
        int reservedTop = font.lineHeight + TITLE_GAP;
        int reservedBottom = GAP + BUTTON_HEIGHT;
        int maxListHeight = Math.max(ROW_HEIGHT, this.height - SCREEN_MARGIN * 2 - reservedTop - reservedBottom);
        int listHeight = Math.min(maxListHeight, Math.max(ROW_HEIGHT, styles.size() * ROW_HEIGHT));

        int blockHeight = reservedTop + listHeight + reservedBottom;
        int top = Math.max(SCREEN_MARGIN, (this.height - blockHeight) / 2);
        titleY = top;
        int listTop = top + reservedTop;

        this.list = new StyleList(minecraft, width, listHeight, listTop, x);
        addRenderableWidget(list);

        Button cancel = Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x, listTop + listHeight + GAP, width, BUTTON_HEIGHT).build();
        addRenderableWidget(cancel);
    }

    /** Commits a chosen style to the caller and closes back to the parent screen. */
    private void choose(String style) {
        onSelect.accept(style);
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // GLFW_KEY_ENTER (257) / GLFW_KEY_KP_ENTER (335): confirm the highlighted row from the
        // keyboard, matching how vanilla single-choice dialogs behave. Arrow keys only move the
        // highlight (vanilla's list navigation); Escape closes via the inherited onClose.
        if ((event.key() == 257 || event.key() == 335) && list != null && list.getSelected() != null) {
            choose(list.getSelected().style);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, this.width / 2, titleY, 0xffffffff);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    /**
     * The scrollable list of style ids. A click on a row confirms it (vanilla has already made it the
     * selected entry by the time the row's {@code mouseClicked} runs), so the dialog behaves like a
     * dropdown rather than a select-then-confirm form.
     */
    private class StyleList extends ObjectSelectionList<StyleList.StyleRow> {

        StyleList(Minecraft minecraft, int width, int height, int top, int x) {
            super(minecraft, width, height, top, ROW_HEIGHT);
            this.centerListVertically = false;

            List<StyleRow> rows = new ArrayList<>();
            for (String style : styles) {
                StyleRow row = new StyleRow(style);
                rows.add(row);
                addEntry(row);
            }
            int selected = preselectIndex(styles, current);
            if (selected >= 0) {
                setSelected(rows.get(selected));
            }
            updateSizeAndPosition(width, height, x, top);
        }

        @Override
        public int getRowWidth() {
            // Full dialog width minus the scrollbar gutter, instead of the vanilla fixed 220.
            return Math.max(0, getWidth() - scrollbarWidth() - 4);
        }

        class StyleRow extends ObjectSelectionList.Entry<StyleRow> {

            private final String style;

            StyleRow(String style) {
                this.style = style;
            }

            @Override
            public Component getNarration() {
                return Component.literal(style);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                choose(style);
                return true;
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
                Font font = Minecraft.getInstance().font;
                int textY = getContentY() + Math.max(0, (getContentHeight() - font.lineHeight) / 2);
                int color = style.equals(current) ? CURRENT_STYLE_COLOR : STYLE_COLOR;
                graphics.text(font, Component.literal(style), getContentX() + TEXT_INSET, textY, color);
            }
        }
    }
}
