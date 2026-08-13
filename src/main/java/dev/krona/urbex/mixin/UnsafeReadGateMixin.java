package dev.krona.urbex.mixin;

import dev.krona.urbex.setup.DigestCheck;
import dev.krona.urbex.worldgen.UnsafeReadCounter;
import net.minecraft.core.BlockPos;
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
 * Counts Urbex-attributable cross-chunk terrain access - both reads and writes - for the digest
 * check's gate.
 *
 * <p>Reads are caught on {@code getChunk}: any resolution of a chunk outside the current write
 * radius. Writes are caught on {@code ensureCanWrite}: {@code WorldGenRegion.setBlock} calls it
 * first and returns {@code false} without ever reaching {@code getChunk} when the target position
 * is out of radius, so a cross-chunk write is invisible to the read-side injection alone - it is
 * silently dropped by vanilla, not resolved through a counted path. Both injections key on the
 * same distance-from-centre check {@code ensureCanWrite} itself uses, and both attribute the
 * violation the same way: the innermost {@code dev.krona.urbex} stack frame.
 *
 * <p>Disabled unless {@link DigestCheck#PROP_FAIL_ON_UNSAFE_READ} is set, which only the digest run
 * configurations do. The flag is a {@code static final boolean} read once so the JIT can eliminate
 * the whole body in normal play - this sits on {@code getChunk} and {@code ensureCanWrite}, both
 * hot paths.
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
        urbex$recordFromStack();
    }

    /**
     * {@code WorldGenRegion.setBlock} calls {@code ensureCanWrite(BlockPos)} first and returns
     * {@code false} immediately when it fails - {@code getChunk} is never reached, so a cross-chunk
     * write would otherwise be silently dropped by vanilla and invisible to
     * {@link #urbex$countUnsafeRead}. Computed independently here rather than read off the method's
     * return value: {@code ensureCanWrite} can also return {@code false} for a position within
     * radius but outside build height during a chunk upgrade, which is not a cross-chunk violation
     * and must not be counted as one.
     */
    @Inject(method = "ensureCanWrite(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"))
    private void urbex$countUnsafeWrite(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!urbex$enabled) {
            return;
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int distance = Math.max(Math.abs(chunkX - centerChunkX), Math.abs(chunkZ - centerChunkZ));
        if (distance <= writeRadius) {
            return;
        }
        urbex$recordFromStack();
    }

    @Unique
    private static final StackWalker URBEX_WALKER = StackWalker.getInstance();

    /**
     * Attributes this call to the nearest Urbex frame, or to nothing if there is none.
     *
     * <p>A {@link StackWalker} rather than {@code new Throwable().getStackTrace()}, which
     * materialises the entire stack as {@code StackTraceElement} objects before the first one is
     * looked at. Most calls that reach here are vanilla's own cross-chunk reads with no Urbex frame
     * anywhere below them, so the whole array was built and discarded to record nothing: a JFR
     * profile of {@code runDigestCheckAvoid} attributed <strong>815 MiB</strong> to this method over
     * 625 chunks - against a reported {@code unsafeReads=0}. The gate is off in normal play, so this
     * was never a player-facing cost, but it was 8% of every allocation figure this milestone
     * measured (issue #132).</p>
     */
    @Unique
    private static void urbex$recordFromStack() {
        URBEX_WALKER.walk(frames -> frames
                        .filter(frame -> frame.getClassName().startsWith("dev.krona.urbex"))
                        .findFirst()
                        .map(frame -> frame.toStackTraceElement().toString()))
                .ifPresent(UnsafeReadCounter::record);
    }
}
