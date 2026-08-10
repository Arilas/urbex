package dev.krona.urbex.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import javax.annotation.Nullable;

/**
 * Exposes {@code CreateWorldScreen}'s private {@code tempDataPackRepository} (issue #66): the
 * repository of data packs enabled for the world currently being created, which is what
 * {@code urbex/worldstyles} should really be scanned from - not the client's general resource
 * pack repository, which knows nothing about packs a player enables just for this new world.
 * <p>
 * Nullable: the field is only populated once the player opens the "Data Packs" screen during
 * creation; callers must fall back to the client resource pack repository when this is null.
 */
@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenAccessor {

    @Accessor("tempDataPackRepository")
    @Nullable
    PackRepository urbex$getTempDataPackRepository();
}
