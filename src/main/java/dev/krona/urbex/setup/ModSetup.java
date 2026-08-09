package dev.krona.urbex.setup;

import dev.krona.urbex.config.ProfileSetup;
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
        ProfileSetup.setupProfiles();
    }

    public void init() {
        ServerEventHandlers.register();
        AssetRegistries.reset();

        // The server config (selectedProfile) is only loaded by server start, so validation
        // has to happen here rather than in preInit - failing loudly beats NPEing during world init.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> Config.validateSelectedProfiles());
    }
}
