package dev.krona.urbex.gui;

import dev.krona.urbex.setup.WorldStyleMix;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The modal "Select World Style" picker raised from the {@link CitiesTab} world-style button.
 * <p>
 * Two modes share one dialog. <b>Single</b> - the only mode when
 * {@code experimentalMultiWorldStyles} is off - is a scrollable {@link ObjectSelectionList} of the
 * registered styles plus a cancel button, where a click on a row hands the chosen style to the
 * caller and closes. Vanilla ships no combobox, so that list is the dropdown. <b>Mix</b> is the
 * experimental editor: every registered style gets a row with an enable toggle, a weight stepper and
 * a live normalized percentage, and Done commits the whole weighted set.
 * <p>
 * Every row is labelled by the style's own {@code name} with its id underneath, so a pack that names
 * itself reads as "Modern Tweaks" rather than {@code urbexmt:moderntweaks} - and the id stays
 * visible, because it is what a config line or a bug report has to say. A style with no name is
 * drawn as one centred id line rather than the same text twice.
 * <p>
 * Weights are raw rather than percentages. A player types the balance they mean - {@code 0.1} and
 * {@code 0.9} - and reads back {@code 10%} / {@code 90%}; being made to produce numbers that sum to
 * something is work the dialog can do instead.
 * <p>
 * {@link #preselectIndex}, {@link #rowsFor}, {@link #normalize}, {@link #toMix} and
 * {@link #canDisable} are the pure, headless-testable core ({@code WorldStyleDialogTest});
 * everything else is GL widget code exercised only manually. Nothing here lays widgets at fixed
 * screen coordinates: the block is sized from the style count and centred in whatever screen area it
 * opens over.
 */
public class WorldStyleDialog extends Screen {

    /** Beyond this the single-column list of short ids just gets emptier; cap and centre instead. */
    private static final int MAX_WIDTH = 220;
    /** The mix editor carries four controls per row on top of the label, so it needs more room. */
    private static final int MAX_MIX_WIDTH = 360;
    private static final int SCREEN_MARGIN = 20;
    /** Two stacked text lines (name over id) plus 4px of breathing room above and below. */
    private static final int ROW_HEIGHT = 26;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int TITLE_GAP = 8;
    private static final int TEXT_INSET = 6;
    private static final int LINE_GAP = 1;

    /** Widths of the mix row's fixed-size controls, right to left from the row's trailing edge. */
    private static final int TOGGLE_WIDTH = 18;
    private static final int STEP_BUTTON_WIDTH = 16;
    private static final int WEIGHT_TEXT_WIDTH = 34;
    private static final int PERCENT_TEXT_WIDTH = 34;

    /** Bounds and granularity of a weight stepper. Weights are relative, so the range is generous. */
    static final float MIN_WEIGHT = 0.05f;
    static final float MAX_WEIGHT = 10.0f;
    static final float WEIGHT_STEP = 0.05f;

    private static final int STYLE_COLOR = 0xffffffff;
    /** The style that generates right now, so it reads as "current" even after the player arrows away. */
    private static final int CURRENT_STYLE_COLOR = 0xffffff55;
    /** The id line: present but subordinate to the name above it. */
    private static final int ID_COLOR = 0xff9f9f9f;
    /** A row that is not in the mix, dimmed so the enabled set reads at a glance. */
    private static final int DISABLED_COLOR = 0xff707070;

    private final Screen parent;
    private final List<String> styles;
    /** Style id -> label; a style missing from it falls back to showing its id as the name. */
    private final Map<String, String> names;
    private final WorldStyleMix current;
    private final boolean allowMixing;
    private final Consumer<WorldStyleMix> onSelect;

    /** The mix editor's row model. Only meaningful while {@link #mixing} is true. */
    private final List<MixRow> rows = new ArrayList<>();
    private boolean mixing;

    @Nullable
    private StyleList list;
    private int titleY;

    /**
     * One editable row of the mix editor: a registered style, whether it is in the mix, and its
     * relative weight.
     */
    public record MixRow(String style, boolean enabled, float weight) {
    }

    public WorldStyleDialog(Screen parent, List<String> styles, Map<String, String> names,
                            WorldStyleMix current, boolean allowMixing, Consumer<WorldStyleMix> onSelect) {
        super(Component.translatable("urbex.screen.worldstyle.title"));
        this.parent = parent;
        this.styles = List.copyOf(styles);
        this.names = Map.copyOf(names);
        this.current = current;
        // Mixing needs something to mix: with one registered style the editor would be a single row
        // pinned on at 100%, which is exactly what the plain list already says.
        this.allowMixing = allowMixing && styles.size() > 1;
        this.onSelect = onSelect;
        this.rows.addAll(rowsFor(this.styles, current));
        // Open in whichever mode describes what currently generates, so the dialog shows the truth
        // before it offers to change it.
        this.mixing = this.allowMixing && !current.isSingle();
    }

    /** The label for a style id, falling back to the id itself when the caller supplied none. */
    private String nameOf(String style) {
        return names.getOrDefault(style, style);
    }

    /**
     * The index of {@code current} within {@code choices} - the row to pre-select and highlight - or
     * {@code -1} when {@code current} is absent (a stale id a registry change dropped). Pure: no
     * widget or game state.
     */
    static int preselectIndex(List<String> choices, @Nullable String current) {
        if (current == null) {
            return -1;
        }
        return choices.indexOf(current);
    }

    /**
     * The rows a mix editor opens with: every registered style, in the order the tab injected them,
     * with the ones {@code current} names enabled at their chosen weight and the rest off at a
     * neutral 1.
     */
    public static List<MixRow> rowsFor(List<String> choices, WorldStyleMix current) {
        Map<String, Float> chosen = new LinkedHashMap<>();
        for (WorldStyleMix.Entry entry : current.entries()) {
            chosen.put(entry.style().toString(), entry.weight());
        }
        List<MixRow> rows = new ArrayList<>(choices.size());
        for (String choice : choices) {
            Float weight = chosen.get(choice);
            rows.add(new MixRow(choice, weight != null, weight != null ? weight : 1.0f));
        }
        return rows;
    }

    /**
     * The percentage beside each row: its share of the enabled rows' total weight, rounded to a
     * whole number, or {@code -1} for a disabled row (which shows a dash instead).
     */
    public static List<Integer> normalize(List<MixRow> rows) {
        float total = 0;
        for (MixRow row : rows) {
            if (row.enabled()) {
                total += row.weight();
            }
        }
        List<Integer> percentages = new ArrayList<>(rows.size());
        for (MixRow row : rows) {
            percentages.add(row.enabled() && total > 0 ? Math.round(row.weight() / total * 100f) : -1);
        }
        return percentages;
    }

    /** The mix the enabled rows describe. Never empty: {@link #canDisable} keeps one row on. */
    public static WorldStyleMix toMix(List<MixRow> rows) {
        List<WorldStyleMix.Entry> entries = new ArrayList<>();
        for (MixRow row : rows) {
            if (row.enabled()) {
                entries.add(new WorldStyleMix.Entry(DataTools.fromName(row.style()), row.weight()));
            }
        }
        return WorldStyleMix.of(entries);
    }

    /**
     * Whether the row at {@code index} may be switched off. The last enabled row may not: there is
     * no such thing as a world with no world style, so the toggle is inert rather than letting the
     * player reach a state Done would have to refuse.
     */
    public static boolean canDisable(List<MixRow> rows, int index) {
        if (!rows.get(index).enabled()) {
            return true;
        }
        int enabled = 0;
        for (MixRow row : rows) {
            if (row.enabled()) {
                enabled++;
            }
        }
        return enabled > 1;
    }

    @Override
    protected void init() {
        int width = Math.min(this.width - SCREEN_MARGIN * 2, mixing ? MAX_MIX_WIDTH : MAX_WIDTH);
        int x = (this.width - width) / 2;

        // Reserve the title above and the button row below; the list takes the middle, clamped to
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

        int buttonY = listTop + listHeight + GAP;
        if (allowMixing) {
            // The mode switch sits with the buttons rather than in the title bar: the title row is
            // centred text, and a checkbox there would have to be positioned against it.
            Checkbox mix = Checkbox.builder(Component.translatable("urbex.screen.worldstyle.mix"), font)
                    .selected(mixing)
                    .onValueChange((box, selected) -> setMixing(selected))
                    .build();
            mix.setPosition(x, buttonY);
            addRenderableWidget(mix);
        }

        if (mixing) {
            int buttonWidth = Math.min(100, (width - GAP) / 2);
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> choose(toMix(rows)))
                    .bounds(x + width - buttonWidth * 2 - GAP, buttonY, buttonWidth, BUTTON_HEIGHT).build());
            addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                    .bounds(x + width - buttonWidth, buttonY, buttonWidth, BUTTON_HEIGHT).build());
        } else {
            // Single mode commits on a row click, so cancel is the only button - exactly as before.
            int cancelWidth = allowMixing ? width / 2 : width;
            addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                    .bounds(x + width - cancelWidth, buttonY, cancelWidth, BUTTON_HEIGHT).build());
        }
    }

    /**
     * Switches between the plain picker and the mix editor, rebuilding the widgets.
     * <p>
     * Leaving mix mode keeps the heaviest enabled row and drops the rest, so the single-mode list has
     * one unambiguous current style to highlight rather than silently keeping a mix the player can no
     * longer see.
     */
    private void setMixing(boolean enabled) {
        if (mixing == enabled) {
            return;
        }
        mixing = enabled;
        if (!enabled) {
            String primary = toMix(rows).primary().toString();
            List<MixRow> collapsed = new ArrayList<>(rows.size());
            for (MixRow row : rows) {
                collapsed.add(new MixRow(row.style(), row.style().equals(primary), row.weight()));
            }
            rows.clear();
            rows.addAll(collapsed);
        }
        rebuildWidgets();
    }

    private void stepWeight(int index, float delta) {
        MixRow row = rows.get(index);
        rows.set(index, new MixRow(row.style(), row.enabled(),
                Mth.clamp(Math.round((row.weight() + delta) / WEIGHT_STEP) * WEIGHT_STEP, MIN_WEIGHT, MAX_WEIGHT)));
    }

    private void toggleRow(int index) {
        MixRow row = rows.get(index);
        if (row.enabled() && !canDisable(rows, index)) {
            return;
        }
        rows.set(index, new MixRow(row.style(), !row.enabled(), row.weight()));
    }

    /** Commits a chosen selection to the caller and closes back to the parent screen. */
    private void choose(WorldStyleMix styles) {
        onSelect.accept(styles);
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // GLFW_KEY_ENTER (257) / GLFW_KEY_KP_ENTER (335): confirm from the keyboard, matching how
        // vanilla single-choice dialogs behave. In mix mode that commits the whole set, since there
        // is no single "highlighted" answer. Arrow keys only move the highlight (vanilla's list
        // navigation); Escape closes via the inherited onClose.
        if (event.key() == 257 || event.key() == 335) {
            if (mixing) {
                choose(toMix(rows));
                return true;
            }
            if (list != null && list.getSelected() != null) {
                choose(WorldStyleMix.of(DataTools.fromName(list.getSelected().style)));
                return true;
            }
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
     * The scrollable list of styles. In single mode a click on a row confirms it (vanilla has already
     * made it the selected entry by the time the row's {@code mouseClicked} runs), so the dialog
     * behaves like a dropdown rather than a select-then-confirm form. In mix mode a row is a small
     * form of its own and a click lands on whichever control it hit.
     */
    private class StyleList extends ObjectSelectionList<StyleList.StyleRow> {

        StyleList(Minecraft minecraft, int width, int height, int top, int x) {
            super(minecraft, width, height, top, ROW_HEIGHT);
            this.centerListVertically = false;

            List<StyleRow> entries = new ArrayList<>();
            for (int i = 0; i < styles.size(); i++) {
                StyleRow row = new StyleRow(styles.get(i), i);
                entries.add(row);
                addEntry(row);
            }
            if (!mixing) {
                int selected = preselectIndex(styles, current.primary().toString());
                if (selected >= 0) {
                    setSelected(entries.get(selected));
                }
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
            private final int index;

            StyleRow(String style, int index) {
                this.style = style;
                this.index = index;
            }

            @Override
            public Component getNarration() {
                String name = nameOf(style);
                Component label = name.equals(style)
                        ? Component.literal(style) : Component.literal(name + " (" + style + ")");
                if (!mixing) {
                    return label;
                }
                MixRow row = rows.get(index);
                int percent = normalize(rows).get(index);
                return Component.literal((row.enabled() ? percent + "%, " : "off, ")).append(label);
            }

            /** The x of a control's leading edge, given how many pixels of row sit to its right. */
            private int trailingX(int fromRight, int controlWidth) {
                return getContentX() + getContentWidth() - fromRight - controlWidth;
            }

            private int percentX() {
                return trailingX(0, PERCENT_TEXT_WIDTH);
            }

            private int plusX() {
                return trailingX(PERCENT_TEXT_WIDTH, STEP_BUTTON_WIDTH);
            }

            private int weightX() {
                return trailingX(PERCENT_TEXT_WIDTH + STEP_BUTTON_WIDTH, WEIGHT_TEXT_WIDTH);
            }

            private int minusX() {
                return trailingX(PERCENT_TEXT_WIDTH + STEP_BUTTON_WIDTH + WEIGHT_TEXT_WIDTH, STEP_BUTTON_WIDTH);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                if (!mixing) {
                    choose(WorldStyleMix.of(DataTools.fromName(style)));
                    return true;
                }
                double mouseX = event.x();
                if (mouseX >= minusX() && mouseX < minusX() + STEP_BUTTON_WIDTH) {
                    stepWeight(index, -WEIGHT_STEP);
                } else if (mouseX >= plusX() && mouseX < plusX() + STEP_BUTTON_WIDTH) {
                    stepWeight(index, WEIGHT_STEP);
                } else {
                    // Anywhere else on the row toggles it - a bigger target than a checkbox alone,
                    // and the name itself is the natural thing to click.
                    toggleRow(index);
                }
                return true;
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
                Font font = Minecraft.getInstance().font;
                boolean enabled = !mixing || rows.get(index).enabled();
                int labelX = getContentX() + (mixing ? TOGGLE_WIDTH : TEXT_INSET);
                int color = enabled
                        ? (style.equals(current.primary().toString()) ? CURRENT_STYLE_COLOR : STYLE_COLOR)
                        : DISABLED_COLOR;

                if (mixing) {
                    MixRow row = rows.get(index);
                    int mid = getContentY() + Math.max(0, (getContentHeight() - font.lineHeight) / 2);
                    graphics.text(font, Component.literal(row.enabled() ? "[x]" : "[ ]"),
                            getContentX(), mid, color);
                    graphics.text(font, Component.literal("-"), minusX() + 5, mid, color);
                    graphics.text(font, Component.literal(String.format(Locale.ROOT, "%.2f", row.weight())),
                            weightX(), mid, color);
                    graphics.text(font, Component.literal("+"), plusX() + 5, mid, color);
                    int percent = normalize(rows).get(index);
                    graphics.text(font, Component.literal(percent < 0 ? "-" : percent + "%"),
                            percentX(), mid, color);
                }

                // A style with no name of its own is already labelled by its id, so the second line
                // would repeat it verbatim; draw the single line centred instead.
                String name = nameOf(style);
                if (name.equals(style)) {
                    int textY = getContentY() + Math.max(0, (getContentHeight() - font.lineHeight) / 2);
                    graphics.text(font, Component.literal(style), labelX, textY, color);
                    return;
                }
                int block = font.lineHeight * 2 + LINE_GAP;
                int top = getContentY() + Math.max(0, (getContentHeight() - block) / 2);
                graphics.text(font, Component.literal(name), labelX, top, color);
                graphics.text(font, Component.literal(style), labelX, top + font.lineHeight + LINE_GAP,
                        enabled ? ID_COLOR : DISABLED_COLOR);
            }
        }
    }
}
