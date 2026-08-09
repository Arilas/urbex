package dev.krona.urbex.mixin;

import dev.krona.urbex.gui.RecreateProfileRestore;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures which world is being re-created so the Urbex profile stored in that world's saved
 * data can be restored into the new CreateWorldScreen (issue #85). Vanilla only restores what
 * level.dat holds, and the profile is not in it.
 */
@Mixin(WorldSelectionList.WorldListEntry.class)
public class WorldListEntryMixin {

    @Inject(method = "recreateWorld", at = @At("HEAD"))
    private void urbex$captureRecreateSource(CallbackInfo ci) {
        WorldSelectionList.WorldListEntry self = (WorldSelectionList.WorldListEntry) (Object) this;
        RecreateProfileRestore.capture(self.getLevelSummary().getLevelId());
    }
}
