package dev.krona.urbex.gui.settings;

import dev.krona.urbex.config.UrbexProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

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
            case TOGGLE -> createToggle(d, target, onChanged, width);
            case CYCLE -> createCycle(d, target, onChanged, width);
            case TEXT -> createText(d, target, onChanged, width);
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
            return logMapper != null ? logMapper.fromSlider(value) : linearFromSlider(descriptor, value);
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

        EditBox box = new EditBox(font, 0, 0, width, HEIGHT, Component.translatable(d.nameKey()));
        box.setValue(isArray ? String.join(", ", (String[]) initialValue) : (String) initialValue);
        box.setResponder(text -> {
            d.setter().accept(target, isArray ? splitList(text) : text);
            onChanged.run();
        });
        box.setTooltip(Tooltip.create(Component.translatable(d.tooltipKey())));
        return box;
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
