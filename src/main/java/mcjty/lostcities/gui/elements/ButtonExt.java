package mcjty.lostcities.gui.elements;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * A standard vanilla-styled button with a fluent tooltip helper.
 *
 * Note: this used to extend PlainTextButton, which (a) renders no button background and
 * (b) caches its label at construction time, so setMessage() calls from refreshButtons()
 * were never reflected on screen in the 26.2 extract-based GUI pipeline. Button.Plain is
 * the regular vanilla button: it draws the widget/button sprite background and renders
 * getMessage() fresh every frame, so label updates show immediately.
 */
public class ButtonExt extends Button.Plain {

    public ButtonExt(int x, int y, int w, int h, Component message, OnPress action) {
        super(x, y, w, h, message, action, DEFAULT_NARRATION);
    }

    public ButtonExt tooltip(Component tooltip) {
        setTooltip(Tooltip.create(tooltip));
        return this;
    }
}
