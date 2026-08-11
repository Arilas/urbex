package dev.krona.urbex.setup;

import net.fabricmc.api.ClientModInitializer;

public class ClientSetup implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientEventHandlers.register();
    }
}
