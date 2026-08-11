package dev.krona.urbex.worldgen.lost.regassets.data;

import dev.krona.urbex.Urbex;
import net.minecraft.resources.Identifier;

import java.util.Optional;

public class DataTools {

    public static Optional<String> toNullable(Character c) {
        if (c == null) {
            return Optional.empty();
        } else {
            return Optional.of(Character.toString(c));
        }
    }

    public static Character getNullableChar(Optional<String> opt) {
        return opt.isPresent() ? opt.get().charAt(0) : null;
    }

    public static String toName(Identifier rl) {
        if (rl.getNamespace().equals(Urbex.MODID)) {
            return rl.getPath();
        } else {
            return rl.toString();
        }
    }

    public static Identifier fromName(String name) {
        if (!name.contains(":")) {
            throw new IllegalArgumentException("Unqualified datapack reference '" + name
                    + "': references must name their namespace, e.g. '" + Urbex.MODID + ":" + name + "'");
        }
        return Identifier.parse(name);
    }

    /**
     * Inverse of {@link #toName}, for the client-only display round trip the Cities tab, the
     * customize editor and the preview use (worldStyle ids are held as {@code toName}-shortened
     * strings there because {@link dev.krona.urbex.config.Preset} carries no worldStyle field of
     * its own). A bare string reaching this method was produced by {@code toName} from a real,
     * already-resolved {@code urbex}-namespace {@link Identifier} - it is not an authored
     * reference, so it must not go through {@link #fromName}'s strict check. Never call this on a
     * string a datapack or config file wrote; only on a value this mod's own code already emitted.
     */
    public static Identifier fromDisplayName(String name) {
        return name.contains(":") ? Identifier.parse(name) : Identifier.fromNamespaceAndPath(Urbex.MODID, name);
    }
}
