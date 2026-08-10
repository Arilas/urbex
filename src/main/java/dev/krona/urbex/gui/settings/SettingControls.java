package dev.krona.urbex.gui.settings;

import dev.krona.urbex.config.UrbexProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Builds the editing widget for a single {@link SettingDescriptor}, per {@link SettingDescriptor#kind()}:
 * a slider, a boolean toggle, an enum cycle button, or a text box. Every widget writes straight through
 * {@link SettingDescriptor#getter()}/{@link SettingDescriptor#setter()} and calls the supplied
 * {@code onChanged} after every edit; this class holds no state itself and knows nothing about the screen
 * that will host the widget (Task 6) - it only needs a target width, since heights are always the vanilla
 * standard 20 and nothing here places widgets at fixed screen coordinates.
 */
public final class SettingControls {

    /** Vanilla's standard row height, used for every control kind - only widths vary per layout. */
    private static final int HEIGHT = 20;

    private SettingControls() {
    }

    public static AbstractWidget create(SettingDescriptor d, UrbexProfile target, Runnable onChanged, int width) {
        return switch (d.kind()) {
            case SLIDER -> createSlider(d, target, onChanged, width);
            case CHANCE_PERLIN -> createChancePerlin(d, target, onChanged, width);
            case TOGGLE -> createToggle(d, target, onChanged, width);
            case CYCLE -> createCycle(d, target, onChanged, width);
            case TEXT -> createText(d, target, onChanged, width);
            case NUMBER -> createNumber(d, target, onChanged, width);
        };
    }

    // ---- SLIDER ---------------------------------------------------------

    private static AbstractWidget createSlider(SettingDescriptor d, UrbexProfile target, Runnable onChanged, int width) {
        double initialValue = (Double) d.getter().apply(target);
        LogValueMapper logMapper = d.logScale() ? new LogValueMapper(d.min(), d.max()) : null;
        double initialPosition = logMapper != null ? logMapper.toSlider(initialValue) : linearToSlider(d, initialValue);

        SliderWidget widget = new SliderWidget(d, target, onChanged, logMapper, width, initialPosition,
                sliderLabel(d, initialValue));
        widget.setTooltip(Tooltip.create(Component.translatable(d.tooltipKey())));
        return widget;
    }

    private static double linearToSlider(SettingDescriptor d, double value) {
        double clamped = Math.max(d.min(), Math.min(d.max(), value));
        return (clamped - d.min()) / (d.max() - d.min());
    }

    private static double linearFromSlider(SettingDescriptor d, double t) {
        double clampedT = Math.max(0.0, Math.min(1.0, t));
        return d.min() + clampedT * (d.max() - d.min());
    }

    /**
     * Snaps a continuous slider value to the nearest multiple of {@code step}, anchored at {@code min} and
     * clamped to {@code [min, max]}. Anchoring the grid at {@code min} (rather than at 0) means a range whose
     * lower bound is not itself a multiple of {@code step} — e.g. {@code min = 0.5}, {@code step = 0.5} — still
     * snaps to on-range values, and it keeps both endpoints reachable. A {@code step <= 0} means "no snapping":
     * the value passes through unchanged (used by log sliders, whose exponential travel makes a linear step
     * meaningless). Pure and package-private so {@code SliderStepMathTest} can drive it headlessly.
     */
    static double snapToStep(double value, double min, double max, double step) {
        if (!(step > 0.0)) {
            return value;
        }
        double snapped = min + Math.round((value - min) / step) * step;
        return Math.max(min, Math.min(max, snapped));
    }

    private static MutableComponent sliderLabel(SettingDescriptor d, double value) {
        return Component.translatable(d.nameKey()).append(Component.literal(": " + LogValueMapper.format(value)));
    }

    /**
     * {@code value} (inherited from {@link AbstractSliderButton}) is always the normalized {@code [0, 1]}
     * handle position, never the real field value - {@link #currentValue()} converts through the log or
     * linear mapping on demand. {@code applyValue()} writes that back through the descriptor's setter (the
     * Task 4 boxing convention: sliders are always boxed {@link Double}, and the setter narrows to the
     * field's real type); {@code updateMessage()} keeps the "name: value" readout in sync. Both are called
     * by {@code AbstractSliderButton.setValue}, in that order, whenever the handle moves.
     */
    private static final class SliderWidget extends AbstractSliderButton {
        private final SettingDescriptor descriptor;
        private final UrbexProfile target;
        private final Runnable onChanged;
        private final LogValueMapper logMapper;

        private SliderWidget(SettingDescriptor descriptor, UrbexProfile target, Runnable onChanged,
                              LogValueMapper logMapper, int width, double initialPosition, Component initialLabel) {
            super(0, 0, width, HEIGHT, initialLabel, initialPosition);
            this.descriptor = descriptor;
            this.target = target;
            this.onChanged = onChanged;
            this.logMapper = logMapper;
        }

        private double currentValue() {
            if (logMapper != null) {
                // Log sliders travel exponentially, so a linear step is meaningless — snapToStep is a no-op for
                // step<=0, but log descriptors are read on this branch and left continuous regardless.
                return logMapper.fromSlider(value);
            }
            // Snap in the value-read path so BOTH the written value (applyValue) and the readout label
            // (updateMessage) reflect the same stepped value.
            double raw = linearFromSlider(descriptor, value);
            return snapToStep(raw, descriptor.min(), descriptor.max(), descriptor.step());
        }

        @Override
        protected void updateMessage() {
            setMessage(sliderLabel(descriptor, currentValue()));
        }

        @Override
        protected void applyValue() {
            descriptor.setter().accept(target, currentValue());
            onChanged.run();
        }
    }

    // ---- CHANCE_PERLIN ----------------------------------------------------

    /**
     * The {@code CITY_CHANCE}-only composite: a "perlin city map" toggle beside a positive-range log slider,
     * coordinated against the single field via {@link PerlinCityChance}. Toggle on ⇒ field is {@code -1} and
     * the slider is inert (disabled); toggle off ⇒ field is the slider's current positive value.
     */
    private static AbstractWidget createChancePerlin(SettingDescriptor d, UrbexProfile target, Runnable onChanged, int width) {
        double field = (Double) d.getter().apply(target);
        boolean perlinOn = PerlinCityChance.isPerlin(field);
        double sliderValue = PerlinCityChance.sliderValue(field, d.min());

        LogValueMapper mapper = new LogValueMapper(d.min(), d.max());
        SliderWidget slider = new SliderWidget(d, target, onChanged, mapper, width,
                mapper.toSlider(sliderValue), sliderLabel(d, sliderValue));
        slider.setTooltip(Tooltip.create(Component.translatable(d.tooltipKey())));
        slider.active = !perlinOn;

        return new PerlinChanceControl(d, target, onChanged, slider, perlinOn, width);
    }

    /**
     * Hosts the perlin toggle and the log slider as a single {@link AbstractWidget} so the row model's
     * one-control-per-row contract holds. The toggle owns the {@code -1} sentinel; the slider only ever
     * writes a positive value, and only while it is enabled (perlin off) - so the setter never writes a
     * positive value while perlin is on. Mouse events reach whichever child is under the cursor (each child
     * ignores clicks outside its own bounds); keyboard events go to the last-focused child.
     */
    private static final class PerlinChanceControl extends AbstractWidget {

        /** Gap between toggle and slider; and the toggle's share of the row width. */
        private static final int GAP = 6;
        private static final int TOGGLE_WIDTH_PERCENT = 42;

        private final SliderWidget slider;
        private final CycleButton<Boolean> toggle;
        private AbstractWidget focusedChild;

        private PerlinChanceControl(SettingDescriptor d, UrbexProfile target, Runnable onChanged,
                                    SliderWidget slider, boolean perlinOn, int width) {
            super(0, 0, width, HEIGHT, Component.translatable(d.nameKey()));
            this.slider = slider;
            this.toggle = CycleButton
                    .booleanBuilder(CommonComponents.OPTION_ON, CommonComponents.OPTION_OFF, perlinOn)
                    .create(0, 0, width, HEIGHT, Component.translatable(d.nameKey() + ".perlin"),
                            (b, on) -> {
                                slider.active = !on;
                                // Perlin on writes -1; perlin off restores the slider's current positive value.
                                d.setter().accept(target, PerlinCityChance.toField(on, slider.currentValue()));
                                onChanged.run();
                            });
            this.toggle.setTooltip(Tooltip.create(Component.translatable(d.nameKey() + ".perlin.tooltip")));
            layoutChildren();
        }

        private int toggleWidth() {
            return Math.max(0, width * TOGGLE_WIDTH_PERCENT / 100);
        }

        private void layoutChildren() {
            int tw = toggleWidth();
            toggle.setX(getX());
            toggle.setY(getY());
            toggle.setWidth(tw);
            toggle.setHeight(height);

            int sliderX = getX() + tw + GAP;
            slider.setX(sliderX);
            slider.setY(getY());
            slider.setWidth(Math.max(0, getX() + width - sliderX));
            slider.setHeight(height);
        }

        @Override
        public void setX(int x) {
            super.setX(x);
            layoutChildren();
        }

        @Override
        public void setY(int y) {
            super.setY(y);
            layoutChildren();
        }

        @Override
        public void setWidth(int w) {
            super.setWidth(w);
            layoutChildren();
        }

        @Override
        public void setHeight(int h) {
            super.setHeight(h);
            layoutChildren();
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            toggle.extractRenderState(graphics, mouseX, mouseY, partialTick);
            slider.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
            toggle.updateNarration(output);
            slider.updateNarration(output);
        }

        // ---- input routing: mouse by position, keyboard to the focused child ----------------

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (toggle.mouseClicked(event, doubleClick)) {
                setFocusedChild(toggle);
                return true;
            }
            if (slider.mouseClicked(event, doubleClick)) {
                setFocusedChild(slider);
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            boolean handled = toggle.mouseReleased(event);
            handled |= slider.mouseReleased(event);
            return handled;
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
            // Only the focused child (the slider being dragged) responds; the other returns false.
            boolean handled = toggle.mouseDragged(event, dragX, dragY);
            handled |= slider.mouseDragged(event, dragX, dragY);
            return handled;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            return focusedChild != null && focusedChild.keyPressed(event);
        }

        @Override
        public boolean keyReleased(KeyEvent event) {
            return focusedChild != null && focusedChild.keyReleased(event);
        }

        @Override
        public boolean charTyped(CharacterEvent event) {
            return focusedChild != null && focusedChild.charTyped(event);
        }

        private void setFocusedChild(AbstractWidget child) {
            if (focusedChild != child && focusedChild != null) {
                focusedChild.setFocused(false);
            }
            focusedChild = child;
            child.setFocused(true);
        }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            if (!focused) {
                toggle.setFocused(false);
                slider.setFocused(false);
                focusedChild = null;
            } else if (focusedChild == null) {
                // Default keyboard focus to the interactive child: the slider when enabled, else the toggle.
                setFocusedChild(slider.active ? slider : toggle);
            }
        }
    }

    // ---- TOGGLE -----------------------------------------------------------

    private static AbstractWidget createToggle(SettingDescriptor d, UrbexProfile target, Runnable onChanged, int width) {
        boolean initialValue = (Boolean) d.getter().apply(target);
        CycleButton<Boolean> button = CycleButton
                .booleanBuilder(CommonComponents.OPTION_ON, CommonComponents.OPTION_OFF, initialValue)
                .create(0, 0, width, HEIGHT, Component.translatable(d.nameKey()),
                        (b, v) -> {
                            d.setter().accept(target, v);
                            onChanged.run();
                        });
        button.setTooltip(Tooltip.create(Component.translatable(d.tooltipKey())));
        return button;
    }

    // ---- CYCLE --------------------------------------------------------------

    /**
     * The descriptor only knows the getter returns "the enum's boxed type" (Task 4's boxing convention);
     * it carries no {@code Class<? extends Enum>} to build a type-safe {@code CycleButton<E>} from. Reading
     * the constants off the current value (per the brief) sidesteps that: every profile always has a real
     * value here, so there is always a concrete instance to ask.
     */
    private static AbstractWidget createCycle(SettingDescriptor d, UrbexProfile target, Runnable onChanged, int width) {
        Object initialValue = d.getter().apply(target);
        // getDeclaringClass(), not getClass(): a constant with a class body (a constant-specific anonymous
        // subclass) would make getClass() return that subclass, whose getEnumConstants() is null;
        // getDeclaringClass() always resolves to the real enum type regardless. LandscapeType has no such
        // bodies today, so this only matters defensively for future CYCLE enums.
        Class<?> enumType = ((Enum<?>) initialValue).getDeclaringClass();
        Object[] constants = enumType.getEnumConstants();
        if (constants == null) {
            throw new IllegalStateException("getDeclaringClass() did not resolve to an enum type: " + enumType);
        }
        CycleButton<Object> button = CycleButton.builder(SettingControls::enumValueLabel, initialValue)
                .withValues(constants)
                .create(0, 0, width, HEIGHT, Component.translatable(d.nameKey()),
                        (b, v) -> {
                            d.setter().accept(target, v);
                            onChanged.run();
                        });
        button.setTooltip(Tooltip.create(Component.translatable(d.tooltipKey())));
        return button;
    }

    /**
     * {@code urbex.enum.<enum simple name>.<constant name>}, all lowercase - e.g.
     * {@code urbex.enum.landscapetype.cavernspheres}. General on purpose: any future {@code CYCLE} enum gets
     * a per-value label for free by following the same naming scheme in the lang file.
     *
     * <p>Package-private (not {@code private}) so {@code SettingsCompletenessTest} can assert every CYCLE
     * descriptor's enum constants resolve to a lang entry without duplicating this formula.</p>
     */
    static String enumLangKey(Enum<?> value) {
        String typeName = value.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT);
        return "urbex.enum." + typeName + "." + value.name().toLowerCase(Locale.ROOT);
    }

    /** Looks up the per-value lang key; falls back to a title-cased constant name if it isn't present. */
    private static Component enumValueLabel(Object enumConstant) {
        Enum<?> value = (Enum<?>) enumConstant;
        String key = enumLangKey(value);
        if (Language.getInstance().has(key)) {
            return Component.translatable(key);
        }
        String raw = value.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Component.literal(Character.toUpperCase(raw.charAt(0)) + raw.substring(1));
    }

    // ---- TEXT -----------------------------------------------------------

    /**
     * Backs both {@code String} and {@code String[]} fields (Task 4's boxing convention: list-valued
     * fields box as {@code String[]}). Arrays round-trip through a comma-separated display: joined for
     * display, split-and-stripped on every edit.
     */
    private static AbstractWidget createText(SettingDescriptor d, UrbexProfile target, Runnable onChanged, int width) {
        Font font = Minecraft.getInstance().font;
        Object initialValue = d.getter().apply(target);
        boolean isArray = initialValue instanceof String[];

        Component label = Component.translatable(d.nameKey());
        EditBox box = new EditBox(font, 0, 0, width, HEIGHT, label);
        // Identifier and comma-joined list values easily run past the EditBox default cap; without a
        // roomier limit setValue() would silently truncate the populated value (BUG 2b).
        box.setMaxLength(1024);
        box.setValue(isArray ? String.join(", ", (String[]) initialValue) : (String) initialValue);
        box.setResponder(text -> {
            d.setter().accept(target, isArray ? splitList(text) : text);
            onChanged.run();
        });

        // A bare EditBox renders only its editable content (its message is narration-only), so the
        // TEXT settings read as unlabeled boxes. Wrap it with a visible label that names the setting
        // and stays readable even once the field holds a value (BUG 2a).
        LabeledTextField field = new LabeledTextField(font, label, box, width, HEIGHT);
        field.setTooltip(Tooltip.create(Component.translatable(d.tooltipKey())));
        return field;
    }

    // ---- NUMBER ---------------------------------------------------------

    /**
     * A typed numeric field for open-ended values a slider cannot honestly express. It reuses the same
     * {@link LabeledTextField} composite as {@link ControlKind#TEXT} (label left, editable box right), but the
     * box holds a number: the current value is formatted in, and every edit parses back to a {@link Double}
     * (the Task 4 boxing convention shared with sliders) before the descriptor's setter narrows it to the
     * field's real type. Non-numeric, out-of-range or partial input (empty, a lone sign, letters, a value past
     * the descriptor's {@code [min, max]}, or — for an {@code int} field — a value beyond the {@code int} range)
     * is rejected: the setter is simply not called, so the field keeps its last valid value.
     * {@link SettingDescriptor#integerOnly()} makes an {@code int}-backed field reject decimals as well.
     */
    private static AbstractWidget createNumber(SettingDescriptor d, UrbexProfile target, Runnable onChanged, int width) {
        Font font = Minecraft.getInstance().font;
        boolean integerOnly = d.integerOnly();
        double initialValue = (Double) d.getter().apply(target);

        Component label = Component.translatable(d.nameKey());
        EditBox box = new EditBox(font, 0, 0, width, HEIGHT, label);
        // Room for large distances/attempt counts and long decimals without silent truncation.
        box.setMaxLength(32);
        box.setValue(formatNumber(initialValue, integerOnly));
        box.setResponder(text -> {
            Double parsed = validateNumber(text, integerOnly, d.min(), d.max());
            if (parsed != null) {
                d.setter().accept(target, parsed);
                onChanged.run();
            }
            // else: unparseable, partial or out-of-range input — keep the last valid value (do not write the field).
        });

        LabeledTextField field = new LabeledTextField(font, label, box, width, HEIGHT);
        field.setTooltip(Tooltip.create(Component.translatable(d.tooltipKey())));
        return field;
    }

    /** Formats the current value for the box: integers without a fraction, decimals trimmed of trailing zeros. */
    private static String formatNumber(double value, boolean integerOnly) {
        if (integerOnly) {
            return Long.toString(Math.round(value));
        }
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * Parses and validates the box text, returning a boxed {@link Double} the setter may safely accept, or
     * {@code null} when the text is not a complete, in-range number. This is the whole safety contract of a
     * NUMBER field, so it is deliberately pure and package-private (no GL, no widget state) — the headless
     * {@code NumberFieldValidationTest} drives it directly.
     *
     * <p>Rejection (returns {@code null}, so the caller leaves the field untouched):</p>
     * <ul>
     *     <li>empty, blank, or a lone sign ({@code "-"} / {@code "+"}) — a partial edit in progress;</li>
     *     <li>anything not parseable as a number, or a non-finite {@code double};</li>
     *     <li>for an {@code integerOnly} field: any fractional part, and any magnitude outside the {@code int}
     *         range — this is what stops e.g. {@code "3000000000"} from silently wrapping negative through the
     *         setter's {@code (int)} narrowing;</li>
     *     <li>any value outside the descriptor's {@code [min, max]} accepted range (the config validation
     *         bounds) — so a NUMBER field never writes past its own declared range.</li>
     * </ul>
     */
    static Double validateNumber(String text, boolean integerOnly, double min, double max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.equals("-") || trimmed.equals("+")) {
            return null;
        }
        double parsed;
        try {
            if (integerOnly) {
                // Long.parseLong rejects any fractional part outright; the int-range guard below rejects a value
                // that parses but would overflow the int field.
                long asLong = Long.parseLong(trimmed);
                if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
                    return null;
                }
                parsed = asLong;
            } else {
                parsed = Double.parseDouble(trimmed);
                if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (parsed < min || parsed > max) {
            return null;
        }
        return parsed;
    }

    /**
     * A TEXT setting shown as a readable label on the left and its editable {@link EditBox} on the
     * right, so the row makes clear <em>which</em> setting it is even when the field holds a value.
     * It is a single {@link AbstractWidget} to fit the row model's one-control-per-row contract, and
     * forwards every input event to the box (the only interactive part) so the field focuses, edits
     * and writes back exactly as a standalone EditBox would.
     */
    private static final class LabeledTextField extends AbstractWidget {

        /** Gap between label and field; and the label's share cap (percent of the row width). */
        private static final int LABEL_GAP = 6;
        private static final int LABEL_WIDTH_PERCENT = 45;

        private final Font font;
        private final Component label;
        private final EditBox box;

        private LabeledTextField(Font font, Component label, EditBox box, int width, int height) {
            super(0, 0, width, height, label);
            this.font = font;
            this.label = label;
            this.box = box;
            layoutBox();
        }

        /** The label reads at its natural width, capped near half the row so the field keeps room. */
        private int labelRegionWidth() {
            return Mth.clamp(font.width(label), 0, Math.max(0, width * LABEL_WIDTH_PERCENT / 100));
        }

        private void layoutBox() {
            int boxX = getX() + labelRegionWidth() + LABEL_GAP;
            int boxRight = getX() + width;
            box.setX(boxX);
            box.setY(getY());
            box.setWidth(Math.max(0, boxRight - boxX));
            box.setHeight(height);
        }

        @Override
        public void setX(int x) {
            super.setX(x);
            layoutBox();
        }

        @Override
        public void setY(int y) {
            super.setY(y);
            layoutBox();
        }

        @Override
        public void setWidth(int w) {
            super.setWidth(w);
            layoutBox();
        }

        @Override
        public void setHeight(int h) {
            super.setHeight(h);
            layoutBox();
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            String shown = font.plainSubstrByWidth(label.getString(), labelRegionWidth());
            int textY = getY() + Math.max(0, (height - font.lineHeight) / 2);
            graphics.text(font, shown, getX(), textY, 0xffffffff);
            box.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, label);
            output.add(NarratedElementType.HINT, Component.literal(box.getValue()));
        }

        // ---- input forwarding: the box is the only interactive part -------------------------

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return box.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            return box.mouseReleased(event);
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
            return box.mouseDragged(event, dragX, dragY);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            return box.keyPressed(event);
        }

        @Override
        public boolean keyReleased(KeyEvent event) {
            return box.keyReleased(event);
        }

        @Override
        public boolean charTyped(CharacterEvent event) {
            return box.charTyped(event);
        }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            box.setFocused(focused);
        }
    }

    private static String[] splitList(String text) {
        if (text.isBlank()) {
            return new String[0];
        }
        String[] parts = text.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].strip();
        }
        return parts;
    }
}
