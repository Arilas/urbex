package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.resources.Identifier;

/**
 * Requiredness for datapack assets is enforced <em>after</em> the {@code extends} chain is applied,
 * not by the codec. A child that omits a field inherits it; a field nothing in the whole chain
 * declares is the error this raises.
 */
public class Resolved {

    private Resolved() {
    }

    public static <T> T require(T value, Identifier owner, String field) {
        if (value == null) {
            throw new IllegalStateException("'" + owner + "' declares no '" + field
                    + "', and neither does anything it extends");
        }
        return value;
    }
}
