package dev.krona.urbex.config;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * How much of the Cities tab a modpack lets its players touch (issue #204).
 *
 * <p>One three-valued setting rather than a pair of {@code lockSelection} / {@code hideCitiesTab}
 * booleans: of the four combinations two booleans can express, {@code lock = false, hide = true} has
 * no meaning, and a schema that can express it invites the question of what it does.</p>
 *
 * <p>None of these change what generates - only who gets to choose it. The configured selection
 * reaches world generation the same way in all three, through {@code Config.configuredSelection},
 * and is recorded into the world on first load whichever is set.</p>
 */
public enum CitiesTabAccess implements StringRepresentable {

    /** The default: the player picks a preset, a world style and any customization they like. */
    EDITABLE("editable"),

    /**
     * The tab renders, showing the configured selection, with every control inactive. The player can
     * see what the pack chose for them - which is the part {@link #HIDDEN} gives up.
     */
    LOCKED("locked"),

    /** The tab is not added to the world-creation screen at all. */
    HIDDEN("hidden");

    public static final Codec<CitiesTabAccess> CODEC =
            StringRepresentable.fromEnum(CitiesTabAccess::values);

    private final String name;

    CitiesTabAccess(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** Whether the tab is offered at all. */
    public boolean visible() {
        return this != HIDDEN;
    }

    /** Whether the player may change the selection. False for both {@link #LOCKED} and {@link #HIDDEN}. */
    public boolean editable() {
        return this == EDITABLE;
    }
}
