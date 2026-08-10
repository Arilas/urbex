package dev.krona.urbex.setup;

import dev.krona.urbex.gui.UrbexConfigScreen;
import dev.krona.urbex.gui.ClientProfileSetup;
import dev.krona.urbex.gui.PresetSelection;
import dev.krona.urbex.gui.RecreateProfileRestore;
import dev.krona.urbex.worldgen.CityFeature;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;

public class ClientEventHandlers {

    private static java.lang.ref.WeakReference<CreateWorldScreen> lastCreateWorldScreen = null;

    public static void register() {
        // Inject the "Cities" button into the world creation screen.
        // (The decorative config icon that was blitted in ScreenEvent.Render.Post on NeoForge
        // has been dropped; Fabric's screen API in 26.2 has no direct post-render hook.)
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof CreateWorldScreen createWorldScreen) {
                // A genuinely new screen, not a rebuild of the same one after a window resize:
                // consume a profile stashed by the Re-Create flow (issue #85)
                if (lastCreateWorldScreen == null || lastCreateWorldScreen.get() != createWorldScreen) {
                    lastCreateWorldScreen = new java.lang.ref.WeakReference<>(createWorldScreen);
                    RecreateProfileRestore.consumePending();
                }
                Button citiesButton = Button.builder(Component.literal("Cities"), b ->
                        Minecraft.getInstance().gui.setScreen(new UrbexConfigScreen(createWorldScreen))
                ).bounds(screen.width - 100, 40, 70, 20).build();
                citiesButton.visible = false;
                Screens.getWidgets(screen).add(citiesButton);
                // Only show the button while the "More" tab is active
                ScreenEvents.afterTick(screen).register(s ->
                        citiesButton.visible = createWorldScreen.tabManager.getCurrentTab() instanceof CreateWorldScreen.MoreTab);
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
