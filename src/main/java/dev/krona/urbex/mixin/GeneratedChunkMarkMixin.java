package dev.krona.urbex.mixin;

import dev.krona.urbex.worldgen.GeneratedChunkMark;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives every chunk one boolean: has Urbex generated here yet.
 *
 * <p>See {@link GeneratedChunkMark} for why the mark belongs on the chunk rather than in a map. The
 * field is deliberately not serialised - it answers a question about one generation pass, and a chunk
 * read back from disk has finished generating.</p>
 *
 * <p>{@code synchronized} so that claiming is one operation rather than a check and an act. It costs
 * nothing worth measuring: the body is a read and a write of one field, it calls nothing, and it
 * cannot be contended in practice, because the two callers are ordered by the chunk pipeline
 * ({@code CARVERS} strictly before {@code FEATURES} for a given chunk). It also cannot deadlock,
 * whoever else uses the chunk's monitor, because it acquires nothing else while holding it.</p>
 */
@Mixin(ChunkAccess.class)
public class GeneratedChunkMarkMixin implements GeneratedChunkMark {

    @Unique
    private boolean urbex$generated;

    @Override
    public synchronized boolean urbex$claimGeneration() {
        if (urbex$generated) {
            return false;
        }
        urbex$generated = true;
        return true;
    }
}
