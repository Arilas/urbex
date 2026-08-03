package dev.krona.urbex.varia;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public class ComponentFactory {

    public static MutableComponent literal(String text) {
        return Component.literal(text);
    }

    public static MutableComponent keybind(String keybind) {
        return Component.keybind(keybind);
    }
}
