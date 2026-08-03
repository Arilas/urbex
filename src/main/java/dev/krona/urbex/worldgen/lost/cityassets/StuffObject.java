package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.StuffSettingsRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.resources.Identifier;

public class StuffObject {

    private final Identifier name;
    private final StuffSettingsRE settings;

    public StuffObject(StuffSettingsRE settings) {
        this.settings = settings;
        this.name = settings.getRegistryName();
    }

    public String getName() {
        return DataTools.toName(name);
    }

    public Identifier getId() {
        return name;
    }

    public StuffSettingsRE getSettings() {
        return settings;
    }
}
