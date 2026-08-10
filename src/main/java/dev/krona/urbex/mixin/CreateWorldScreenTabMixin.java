package dev.krona.urbex.mixin;

import dev.krona.urbex.gui.CitiesTab;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Arrays;

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
public class CreateWorldScreenTabMixin {

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
}
