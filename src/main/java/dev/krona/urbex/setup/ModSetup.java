package dev.krona.urbex.setup;

import dev.krona.urbex.config.ProfileSetup;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
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
        ForgeEventHandlers.register();
        AssetRegistries.reset();
    }
}
