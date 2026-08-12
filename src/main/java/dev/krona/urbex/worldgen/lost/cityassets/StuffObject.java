package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.StuffSettingsRE;
import net.minecraft.resources.Identifier;

import java.util.List;

public class StuffObject {

    private final Identifier name;
    private final StuffSettingsRE settings;

    /**
     * Builds a fully resolved stuff object from its {@code extends} chain, root first. The fold
     * lives on {@link StuffSettingsRE#resolve} so generation keeps reading one settings object and
     * never has to know a chain was involved.
     */
    public StuffObject(Identifier id, List<StuffSettingsRE> chainRootFirst) {
        this.settings = StuffSettingsRE.resolve(id, chainRootFirst);
        this.name = id;
    }

    /** The fully-qualified id, e.g. {@code "urbex:signs"}. */
    public String getName() {
        return name.toString();
    }

    public Identifier getId() {
        return name;
    }

    public StuffSettingsRE getSettings() {
        return settings;
    }
}
