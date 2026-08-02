package mcjty.lostcities.mixin;

import mcjty.lostcities.worldgen.StructureSuppressor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a Lost Cities city win over a structure that would otherwise be stamped on top of it.
 * See StructureSuppressor; without the 'structuresYieldToCities' option this does nothing.
 */
@Mixin(StructureStart.class)
public class StructureStartMixin {

    @Inject(method = "placeInChunk", at = @At("HEAD"), cancellable = true)
    private void lostcities$yieldToCities(WorldGenLevel level, StructureManager structureManager,
                                          ChunkGenerator generator, RandomSource random,
                                          BoundingBox box, ChunkPos chunkPos, CallbackInfo ci) {
        StructureStart self = (StructureStart) (Object) this;
        if (StructureSuppressor.suppressedByCity(level, chunkPos, self.getBoundingBox())) {
            ci.cancel();
        }
    }
}
