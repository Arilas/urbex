package dev.krona.urbex;

import dev.krona.urbex.setup.*;
import dev.krona.urbex.varia.ServerAccess;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.file.Path;

public class Urbex implements ModInitializer {
    public static final String MODID = "urbex";

    public static final Logger LOGGER = LogManager.getLogger(Urbex.MODID);

    public static final ModSetup setup = new ModSetup();
    public static Urbex instance;

    @Override
    public void onInitialize() {
        instance = this;

        Registration.init();
        CustomRegistries.init();

        Path configPath = FabricLoader.getInstance().getConfigDir();
        File dir = new File(configPath + File.separator + "urbex");
        dir.mkdirs();

        Config.loadGlobal(configPath);

        setup.preInit();
        setup.init();

        // Headless worldgen regression check; no-op without -Durbex.digestCheck
        DigestCheck.registerIfRequested();

        // Track the current server (replaces NeoForge's ServerLifecycleHooks)
        ServerLifecycleEvents.SERVER_STARTING.register(server -> ServerAccess.setServer(server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerAccess.setServer(null));

    }

    public static Logger getLogger() {
        return LOGGER;
    }
}
