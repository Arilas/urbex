package dev.krona.urbex.setup;

import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ModSetup {

    public static Logger logger = null;

    public static Logger getLogger() {
        return logger;
    }

    public void preInit() {
        logger = LogManager.getLogger();
    }

    public void init() {
        ServerEventHandlers.register();
        AssetRegistries.reset();

        // The server config (selectedPreset) is only loaded by server start, so validation
        // has to happen here rather than in preInit - failing loudly beats NPEing during world init.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            // Overrides first: validation must see the world's own selectedPreset
            Config.applyWorldOverrides(server);
            Config.validateSelectedPresets(server);
        });
    }
}
