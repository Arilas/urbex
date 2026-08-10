package dev.krona.urbex.mixin;

import dev.krona.urbex.setup.DigestCheck;
import dev.krona.urbex.worldgen.UnsafeReadCounter;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts Urbex-attributable cross-chunk reads, for the digest check's gate.
 *
 * <p>Disabled unless {@link DigestCheck#PROP_FAIL_ON_UNSAFE_READ} is set, which only the digest run
 * configurations do. The flag is a {@code static final boolean} read once so the JIT can eliminate
 * the whole body in normal play - this sits on {@code getChunk}, a hot path.
 */
@Mixin(WorldGenRegion.class)
public abstract class UnsafeReadGateMixin {

    @Shadow private int centerChunkX;
    @Shadow private int centerChunkZ;
    @Shadow private int writeRadius;

    @Unique
    private static final boolean urbex$enabled = System.getProperty(DigestCheck.PROP_FAIL_ON_UNSAFE_READ) != null;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"))
    private void urbex$countUnsafeRead(int chunkX, int chunkZ, ChunkStatus status, boolean load,
                                       CallbackInfoReturnable<ChunkAccess> cir) {
        if (!urbex$enabled) {
            return;
        }
        int distance = Math.max(Math.abs(chunkX - centerChunkX), Math.abs(chunkZ - centerChunkZ));
        if (distance <= writeRadius) {
            return;
        }
        for (StackTraceElement element : new Throwable().getStackTrace()) {
            if (element.getClassName().startsWith("dev.krona.urbex")) {
                UnsafeReadCounter.record(element.toString());
                return;
            }
        }
    }
}
