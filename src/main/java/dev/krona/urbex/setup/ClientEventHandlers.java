package dev.krona.urbex.setup;

import dev.krona.urbex.gui.ClientProfileSetup;
import dev.krona.urbex.gui.PresetSelection;
import dev.krona.urbex.gui.RecreateProfileRestore;
import dev.krona.urbex.worldgen.CityFeature;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

public class ClientEventHandlers {

    private static java.lang.ref.WeakReference<CreateWorldScreen> lastCreateWorldScreen = null;

    public static void register() {
        // The Urbex entry point in world creation is the "Cities" tab (added by
        // CreateWorldScreenTabMixin), not a button any more. What still has to happen here is the
        // Re-Create handoff (issue #85): the stashed profile must be consumed once per genuinely
        // new CreateWorldScreen - not on every rebuild of the same screen after a window resize.
        // BEFORE_INIT, not AFTER_INIT: the tab (and with it the preset list's initial selection) is
        // built inside CreateWorldScreen.init(), so the restore has to have landed in
        // PresetSelection by then for a re-created world to show its profile pre-selected.
        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof CreateWorldScreen createWorldScreen) {
                if (lastCreateWorldScreen == null || lastCreateWorldScreen.get() != createWorldScreen) {
                    lastCreateWorldScreen = new java.lang.ref.WeakReference<>(createWorldScreen);
                    RecreateProfileRestore.consumePending();
                }
            }
        });

        // Clean up client-side state when leaving a world/server
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientProfileSetup.CLIENT_SETUP.reset();
            PresetSelection.CLIENT.reset();
            Config.reset();
            CityFeature.globalDimensionInfoDirtyCounter++;
        });
    }
}
