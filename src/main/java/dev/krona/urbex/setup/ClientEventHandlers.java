package dev.krona.urbex.setup;

import dev.krona.urbex.gui.CitiesTab;
import dev.krona.urbex.gui.PresetSelection;
import dev.krona.urbex.gui.RecreateProfileRestore;
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
                    // A brand new screen, so any "come back on the Cities tab" request left over
                    // from an editor trip the player abandoned (Escape out of the editor goes to
                    // the title screen, not back here) is stale. A return from the editor re-inits
                    // the *same* screen instance and so does not take this branch.
                    CitiesTab.forgetReopenOnCitiesTab();
                }
                // The per-screen event holders are rebuilt on every init, immediately before
                // BEFORE_INIT fires (fabric-screen-api ScreenMixin), so this re-registers cleanly
                // rather than stacking up listeners across window resizes.
                ScreenEvents.remove(createWorldScreen).register(s -> CitiesTab.closeActivePreview());
            }
        });

        // Clean up client-side state when leaving a world/server.
        //
        // Client state only. This used to bump CityFeature's dimension-info counter as well, to
        // drop the integrated server's cached dimension state on the way out of a single-player
        // world - which fired on the client thread while that server was still draining in-flight
        // generation, and could reset the asset registries underneath a worker (issue #125). The
        // server's own state is retired by GenerationSession at SERVER_STOPPING, on the server
        // thread, with nothing generating.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PresetSelection.CLIENT.reset();
            Config.reset();
        });
    }
}
