package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.resources.Identifier;

/**
 * Requiredness for datapack assets is enforced <em>after</em> the {@code extends} chain is applied,
 * not by the codec. A child that omits a field inherits it; a field nothing in the whole chain
 * declares is the error this raises.
 * <p>
 * This rests on DFU's {@code optionalFieldOf} being the <em>strict</em> variant: a key that is
 * present but malformed still fails the decode rather than reading as absent. Were it lenient, a
 * typo in a value would silently become "not declared here" and the file would quietly inherit its
 * ancestor's value instead - the one way moving requiredness off the codec could have gone silent.
 * <p>
 * Raising this at load rather than during generation depends on every registered asset actually
 * being resolved; see {@link AssetRegistries#load}.
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
