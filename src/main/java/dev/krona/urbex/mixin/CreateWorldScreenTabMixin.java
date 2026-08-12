package dev.krona.urbex.mixin;

import dev.krona.urbex.gui.CitiesTab;
import dev.krona.urbex.gui.PresetSelection;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * Adds Urbex's {@link CitiesTab} to the vanilla world-creation screen's tab bar.
 * <p>
 * {@code CreateWorldScreen.init()} builds the bar in a single expression -
 * {@code MenuTabBar.builder(tabManager, width).addTabs(new GameTab(), new WorldTab(), new MoreTab()).build()} -
 * and stores the result straight into the {@code tabNavigationBar} field (verified by decompiling
 * {@code init()}). There is no later point at which a tab can still be added, so this widens the
 * varargs array on its way into {@code addTabs} rather than injecting at {@code TAIL}. {@code
 * addTabs} takes exactly one (array) parameter, so {@code @ModifyArg} needs no index. The default
 * {@code require = 1} from {@code urbex.mixins.json} makes a silently missed target a hard client
 * failure rather than a tab that quietly stops existing.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenTabMixin {

    @Shadow
    @Nullable
    private MenuTabBar tabNavigationBar;

    @ModifyArg(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/tabs/MenuTabBar$Builder;addTabs([Lnet/minecraft/client/gui/components/tabs/Tab;)Lnet/minecraft/client/gui/components/tabs/MenuTabBar$Builder;"
            )
    )
    private Tab[] urbex$appendCitiesTab(Tab[] tabs) {
        Tab[] withCities = Arrays.copyOf(tabs, tabs.length + 1);
        withCities[tabs.length] = new CitiesTab((CreateWorldScreen) (Object) this);
        return withCities;
    }

    /**
     * Un-publishes the Urbex selection when the player backs out of world creation (issue #113).
     * <p>
     * {@code PresetSelection.publish()} writes the selection into three process-global
     * {@code Config} fields, which is how it reaches the integrated server. Nothing used to take
     * them back: abandon the screen, load a <em>different</em> existing world, and
     * {@code Config.buildPresetCache} read the leftovers, generated that world with them and wrote
     * them into its {@code UrbexData} - overwriting the selection that world was created with.
     * <p>
     * {@code onClose} is the one hook that means "abandoned" and only that.
     * {@code ScreenEvents.remove} fires whenever the screen is replaced, including a trip to the
     * Urbex customize editor, and clearing there would drop a selection the player is still
     * editing. World <em>creation</em> does not come through here either: {@code
     * createWorldAndCleanup} calls {@code popScreen()} directly rather than {@code onClose()}, so
     * the published values survive exactly as long as they are needed.
     */
    @Inject(method = "onClose", at = @At("HEAD"))
    private void urbex$discardAbandonedSelection(CallbackInfo ci) {
        PresetSelection.CLIENT.discardPublication();
    }

    /**
     * Puts the player back on the Cities tab after a trip to the Urbex editor.
     * <p>
     * Returning from that editor is a {@code setScreen(parent)}, which re-runs {@code init()} - and
     * {@code init()}'s own tail calls {@code selectTab(0, false)}, i.e. the Game tab (verified by
     * decompiling {@code init()}). Injecting after that call re-selects Cities, using exactly the
     * API vanilla just used: {@code selectTab(int, boolean)} routes to
     * {@code TabManager.setCurrentTab} as long as the bar is not focused, which it is not until
     * {@code Screen.init(..)} calls {@code setInitialFocus()} after this.
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void urbex$reselectCitiesTab(CallbackInfo ci) {
        if (!CitiesTab.consumeReopenOnCitiesTab() || tabNavigationBar == null) {
            return;
        }
        List<Tab> tabs = tabNavigationBar.getTabs();
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i) instanceof CitiesTab) {
                tabNavigationBar.selectTab(i, false);
                return;
            }
        }
    }
}
